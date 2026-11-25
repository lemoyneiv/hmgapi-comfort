package dev.lemoyne.comfort;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;

/**
 * Lightweight, read-only Gen5W UID-1000 cabin telemetry bridge between {@link WelcomeDaemon}
 * and {@link ComfortEngine} over {@code 127.0.0.1}:
 *  - Front-passenger occupancy & buckle detection ({@code HSCAN} + {@code SYS})
 *  - Optional seat climate hardware capability detection ({@code BCM} + {@code USM})
 *  - Ignition / IGN3 / Mobis BlackScreen / AV-Off / Display-Off state ({@code BCM}, {@code DATC},
 *    {@code BLACKSCREEN}, {@code POWER})
 *  - Fresh Binder push-listener fallback for ambient temperature and wheel heat ({@link CabinPushBridge})
 */
public final class OccupantSignalProbe {

    public static final int DEFAULT_PORT = 35821;

    public static final int TEMP_UNIT_UNKNOWN = -1;
    public static final int TEMP_UNIT_C = 0;
    public static final int TEMP_UNIT_F = 1;

    private static final int MAX_FRAME_BYTES = 64 * 1024;

    public interface PushTelemetrySource {
        void onWake();
        void ensure(boolean ignOn);
        String outsideTempF();
        String outsideTempC();
        String wheelHeat();
        boolean isDatcSentinel();
        int tempUnit();
    }

    public interface SignalSource {
        PassengerPresence readPassengerPresence();
        CabinState readCabinState();
        int readMobisSleepingFast();
        void onWake();
    }

    public interface StandbySignalSource extends SignalSource {
        int readPowerStatusFast();
        int readIgnStatusFast();
    }

    /**
     * Live front-passenger seat occupancy snapshot from Gen5W HSCAN & SYSTEM services:
     *  - HSCAN.getOccupancy: 1 = unoccupied / solo driver, 2 = front passenger occupied
     *  - SYS.getClassStatus: 1 = ODS empty, 2 = adult occupant classified on passenger mat
     *  - HSCAN.getAsstStBltSwSta: 0 = unbuckled, 1 = front-passenger belt buckled
     *  - HSCAN.getPassStOccSta: 1 = occupied on trims exposing direct SBR mat flag
     */
    public static final class PassengerPresence {
        public static final PassengerPresence UNAVAILABLE =
                new PassengerPresence(false, false, -1, -1, -1, -1);

        public final boolean available;
        public final boolean occupied;
        public final int hscanOccupancy;
        public final int sysClassStatus;
        public final int asstBelt;
        public final int passStOcc;

        public PassengerPresence(
                boolean available,
                boolean occupied,
                int hscanOccupancy,
                int sysClassStatus,
                int asstBelt,
                int passStOcc) {
            this.available = available;
            this.occupied = occupied;
            this.hscanOccupancy = hscanOccupancy;
            this.sysClassStatus = sysClassStatus;
            this.asstBelt = asstBelt;
            this.passStOcc = passStOcc;
        }

        public static PassengerPresence fromRaw(
                int hscanOccupancy, int sysClassStatus, int asstBelt, int passStOcc) {
            boolean occupied = (hscanOccupancy == 2)
                    || (sysClassStatus == 2)
                    || (passStOcc == 1)
                    || (asstBelt == 1);
            boolean matReporting = (hscanOccupancy >= 1 && hscanOccupancy <= 14)
                    || (sysClassStatus >= 1 && sysClassStatus <= 14);
            boolean available = occupied || matReporting;
            return new PassengerPresence(
                    available, occupied, hscanOccupancy, sysClassStatus, asstBelt, passStOcc);
        }

        public static PassengerPresence fromSignalMap(Map<String, String> m) {
            if (m == null || m.isEmpty()) return UNAVAILABLE;
            return fromRaw(
                    parseRawInt(m.get("HSCAN.getOccupancy")),
                    parseRawInt(m.get("SYS.getClassStatus")),
                    parseRawInt(m.get("HSCAN.getAsstStBltSwSta")),
                    parseRawInt(m.get("HSCAN.getPassStOccSta")));
        }

        public String encode() {
            return (available ? "1" : "0") + ","
                    + (occupied ? "1" : "0") + ","
                    + hscanOccupancy + ","
                    + sysClassStatus + ","
                    + asstBelt + ","
                    + passStOcc;
        }

        public static PassengerPresence decode(String wire) {
            if (wire == null || wire.isEmpty()) return UNAVAILABLE;
            String[] p = wire.trim().split(",");
            if (p.length < 6) return UNAVAILABLE;
            return new PassengerPresence(
                    "1".equals(p[0]),
                    "1".equals(p[1]),
                    parseRawInt(p[2]),
                    parseRawInt(p[3]),
                    parseRawInt(p[4]),
                    parseRawInt(p[5]));
        }

        public String summary() {
            if (!available) return "sensor unavailable";
            return (occupied ? "occupied" : "empty")
                    + " (occ=" + hscanOccupancy
                    + ", ods=" + sysClassStatus
                    + ", belt=" + asstBelt + ")";
        }

        static int parseRawInt(String s) {
            if (s == null || s.isEmpty()) return -1;
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }

    /**
     * Tri-state optional seat climate hardware presence from Gen5W BCM / USM option getters
     * ({@code 1} = equipped, {@code 0} = confirmed absent, {@code -1} = unknown / pre-CAN):
     *  - {@code frontVent}: front seat ventilation ({@code *SeatHeatOption} in {@code 3..14}, e.g. {@code 6})
     *  - {@code rearLeftHeat}: rear-left seat heater ({@code RLSeatHeatOption} in {@code 1..14})
     *  - {@code rearRightHeat}: rear-right seat heater ({@code RRSeatHeatOption} in {@code 1..14})
     *
     * Absent ({@code 0}) is only asserted once at least one seat option reports a valid positive
     * equipped enum ({@code 1..14}), preventing uninitialized pre-CAN zeros from hiding controls.
     */
    public static final class SeatCapabilities {
        public static final SeatCapabilities UNKNOWN = new SeatCapabilities(-1, -1, -1);
        public static final SeatCapabilities ALL_EQUIPPED = new SeatCapabilities(1, 1, 1);

        public final int frontVent;
        public final int rearLeftHeat;
        public final int rearRightHeat;

        public SeatCapabilities(int frontVent, int rearLeftHeat, int rearRightHeat) {
            this.frontVent = normTri(frontVent);
            this.rearLeftHeat = normTri(rearLeftHeat);
            this.rearRightHeat = normTri(rearRightHeat);
        }

        public boolean isFrontVentSupported() {
            return frontVent != 0;
        }

        public boolean isRearLeftHeatSupported() {
            return rearLeftHeat != 0;
        }

        public boolean isRearRightHeatSupported() {
            return rearRightHeat != 0;
        }

        public boolean hasAnyRearHeatSupported() {
            return isRearLeftHeatSupported() || isRearRightHeatSupported();
        }

        public SeatCapabilities mergeKnown(SeatCapabilities live) {
            if (live == null) return this;
            int fv = live.frontVent != -1 ? live.frontVent : this.frontVent;
            int rl = live.rearLeftHeat != -1 ? live.rearLeftHeat : this.rearLeftHeat;
            int rr = live.rearRightHeat != -1 ? live.rearRightHeat : this.rearRightHeat;
            return new SeatCapabilities(fv, rl, rr);
        }

        public static int pickOption(int primaryBcm, int fallbackUsm) {
            if (primaryBcm >= 1 && primaryBcm <= 14) return primaryBcm;
            if (fallbackUsm >= 1 && fallbackUsm <= 14) return fallbackUsm;
            if (primaryBcm == 0 || fallbackUsm == 0) return 0;
            return -1;
        }

        public static SeatCapabilities fromRawOptions(int drvOpt, int astOpt, int rlOpt, int rrOpt) {
            boolean known = isEquippedCode(drvOpt)
                    || isEquippedCode(astOpt)
                    || isEquippedCode(rlOpt)
                    || isEquippedCode(rrOpt);
            if (!known) {
                return UNKNOWN;
            }
            int fv = (isVentCode(drvOpt) || isVentCode(astOpt)) ? 1 : 0;
            int rl = isEquippedCode(rlOpt) ? 1 : (rlOpt == 0 ? 0 : -1);
            int rr = isEquippedCode(rrOpt) ? 1 : (rrOpt == 0 ? 0 : -1);
            return new SeatCapabilities(fv, rl, rr);
        }

        public static SeatCapabilities fromSignalMap(Map<String, String> m) {
            if (m == null || m.isEmpty()) return UNKNOWN;
            int drv = pickOption(
                    PassengerPresence.parseRawInt(m.get("BCM.getDrvSeatHeatOption")),
                    PassengerPresence.parseRawInt(m.get("USM.getFrontSeatHeat_DrvOption")));
            int ast = pickOption(
                    PassengerPresence.parseRawInt(m.get("BCM.getAstSeatHeatOption")),
                    PassengerPresence.parseRawInt(m.get("USM.getFrontSeatHeat_AstOption")));
            int rl = pickOption(
                    PassengerPresence.parseRawInt(m.get("BCM.getRLSeatHeatOption")),
                    PassengerPresence.parseRawInt(m.get("USM.getRearSeatHeat_LeftOption")));
            int rr = pickOption(
                    PassengerPresence.parseRawInt(m.get("BCM.getRRSeatHeatOption")),
                    PassengerPresence.parseRawInt(m.get("USM.getRearSeatHeat_RightOption")));
            return fromRawOptions(drv, ast, rl, rr);
        }

        public String encode() {
            return frontVent + "," + rearLeftHeat + "," + rearRightHeat;
        }

        public static SeatCapabilities decode(String wire) {
            if (wire == null || wire.isEmpty()) return UNKNOWN;
            String[] p = wire.trim().split(",");
            if (p.length < 3) return UNKNOWN;
            return new SeatCapabilities(
                    PassengerPresence.parseRawInt(p[0]),
                    PassengerPresence.parseRawInt(p[1]),
                    PassengerPresence.parseRawInt(p[2]));
        }

        public String summary() {
            return "vent=" + triChar(frontVent)
                    + ",rl=" + triChar(rearLeftHeat)
                    + ",rr=" + triChar(rearRightHeat);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SeatCapabilities)) return false;
            SeatCapabilities s = (SeatCapabilities) o;
            return frontVent == s.frontVent
                    && rearLeftHeat == s.rearLeftHeat
                    && rearRightHeat == s.rearRightHeat;
        }

        @Override
        public int hashCode() {
            return (frontVent + 2) * 31 * 31 + (rearLeftHeat + 2) * 31 + (rearRightHeat + 2);
        }

        private static boolean isEquippedCode(int opt) {
            return opt >= 1 && opt <= 14;
        }

        private static boolean isVentCode(int opt) {
            return opt >= 3 && opt <= 14;
        }

        private static int normTri(int v) {
            return v == 1 ? 1 : (v == 0 ? 0 : -1);
        }

        private static String triChar(int v) {
            return v == 1 ? "1" : (v == 0 ? "0" : "-");
        }
    }

    /**
     * Combined UID-1000 cabin, ignition, display-standby, push-listener fallback, and seat-option snapshot:
     *  - passenger: front-passenger occupancy & buckle state
     *  - outsideTempF / outsideTempC / wheelHeat: fresh Binder listener push values when HMG-API hub is "-"
     *  - ignOn: BCM.getIGNStatus (1 = Ignition ON, 0 = ACC/OFF, -1 = unknown)
     *  - ign3On: DATC.isIGN3Status (1 = IGN3 active during charging/utility/ready, 0 = OFF, -1 = unknown)
     *  - avAwake: 1 = display & AV active, 0 = BlackScreen / AV-Off / Display-Off, -1 = unknown
     *  - mobisSleeping: 1 = Mobis BlackScreen or AV-Off QuickBoot standby, 0 = awake, -1 = unknown
     *  - datcSentinel: true when DATC pushed -40.0 F / -40.0 C raw-zero sentinel (unpowered IG1/ACC)
     *  - seatCaps: vehicle hardware presence for ventilated front seats & heated rear seats
     *  - tempUnit: vehicle temperature display unit (0 = Celsius, 1 = Fahrenheit, -1 = unknown)
     */
    public static final class CabinState {
        public static final CabinState UNAVAILABLE = new CabinState(
                PassengerPresence.UNAVAILABLE, "-", "-", "-", -1, -1, -1, -1, false,
                SeatCapabilities.UNKNOWN, TEMP_UNIT_UNKNOWN);

        public final PassengerPresence passenger;
        public final String outsideTempF;
        public final String outsideTempC;
        public final String wheelHeat;
        public final int ignOn;
        public final int ign3On;
        public final int avAwake;
        public final int mobisSleeping;
        public final boolean datcSentinel;
        public final SeatCapabilities seatCaps;
        public final int tempUnit;

        public CabinState(
                PassengerPresence passenger,
                String outsideTempF,
                String outsideTempC,
                String wheelHeat,
                int ignOn,
                int ign3On,
                int avAwake,
                int mobisSleeping,
                boolean datcSentinel) {
            this(passenger, outsideTempF, outsideTempC, wheelHeat,
                    ignOn, ign3On, avAwake, mobisSleeping, datcSentinel,
                    SeatCapabilities.UNKNOWN, TEMP_UNIT_UNKNOWN);
        }

        public CabinState(
                PassengerPresence passenger,
                String outsideTempF,
                String outsideTempC,
                String wheelHeat,
                int ignOn,
                int ign3On,
                int avAwake,
                int mobisSleeping,
                boolean datcSentinel,
                SeatCapabilities seatCaps) {
            this(passenger, outsideTempF, outsideTempC, wheelHeat,
                    ignOn, ign3On, avAwake, mobisSleeping, datcSentinel,
                    seatCaps, TEMP_UNIT_UNKNOWN);
        }

        public CabinState(
                PassengerPresence passenger,
                String outsideTempF,
                String outsideTempC,
                String wheelHeat,
                int ignOn,
                int ign3On,
                int avAwake,
                int mobisSleeping,
                boolean datcSentinel,
                SeatCapabilities seatCaps,
                int tempUnit) {
            this.passenger = passenger == null ? PassengerPresence.UNAVAILABLE : passenger;
            this.outsideTempF = normField(outsideTempF);
            this.outsideTempC = normField(outsideTempC);
            this.wheelHeat = normField(wheelHeat);
            this.ignOn = ignOn;
            this.ign3On = ign3On;
            this.avAwake = avAwake;
            this.mobisSleeping = mobisSleeping;
            this.datcSentinel = datcSentinel;
            this.seatCaps = seatCaps == null ? SeatCapabilities.UNKNOWN : seatCaps;
            this.tempUnit = normTempUnit(tempUnit);
        }

        public static int computeAvAwake(int blackScreen, int avPowerOff, int displayOffMode) {
            if (blackScreen == 1 || avPowerOff == 1 || displayOffMode == 1) {
                return 0;
            }
            if (blackScreen == 0 || avPowerOff == 0 || displayOffMode == 0) {
                return 1;
            }
            return -1;
        }

        public static int computeMobisSleeping(int blackScreen, int avPowerOff) {
            if (blackScreen == 1 || avPowerOff == 1) {
                return 1;
            }
            if (blackScreen == 0 || avPowerOff == 0) {
                return 0;
            }
            return -1;
        }

        /**
         * Resolves the vehicle's active temperature unit ({@link #TEMP_UNIT_C}, {@link #TEMP_UNIT_F},
         * or {@link #TEMP_UNIT_UNKNOWN}) across Gen5W USM, DATC, HSCAN, and live {@code DatcStatus}:
         *  - {@code USM.getTemperatureUnit}: {@code 0} = °C, {@code 1} = °F ({@code 250} = unsupported)
         *  - {@code USM.getDistanceSpeedUnit}: {@code 0} = km, {@code 1} = mi (confirms USM initialized)
         *  - {@code DATC.getTemperatureUnit}: {@code 1} = °C, {@code 2} = °F ({@code 0} = uninitialized)
         *  - {@code HSCAN.getFATC6_CR_Fatc_TempDispUnit} / {@code getTeperatureUnit}: {@code 0} = °C, {@code 1} = °F
         *  - {@code pushDatcUnit}: {@code 0} = °C, {@code 1} = °F from active {@code DatcStatus} field validity
         */
        public static int resolveTempUnit(
                int usmTempUnit,
                int usmDistUnit,
                int datcTempUnit,
                int hscanFatcUnit,
                int hscanTempUnit,
                int pushDatcUnit) {
            boolean usmReady = (usmDistUnit == 0 || usmDistUnit == 1);
            if (usmTempUnit == 1) {
                return TEMP_UNIT_F;
            }
            if (usmTempUnit == 0) {
                if (datcTempUnit == 2 || (datcTempUnit != 1 && pushDatcUnit == TEMP_UNIT_F)) {
                    return TEMP_UNIT_F;
                }
                if (usmReady || datcTempUnit == 1 || pushDatcUnit == TEMP_UNIT_C
                        || hscanFatcUnit == 0 || hscanTempUnit == 0) {
                    return TEMP_UNIT_C;
                }
            }
            if (datcTempUnit == 1) {
                return TEMP_UNIT_C;
            }
            if (datcTempUnit == 2) {
                return TEMP_UNIT_F;
            }
            if (pushDatcUnit == TEMP_UNIT_C || pushDatcUnit == TEMP_UNIT_F) {
                return pushDatcUnit;
            }
            if ((hscanFatcUnit == 0 || hscanFatcUnit == 1) && usmReady) {
                return hscanFatcUnit == 0 ? TEMP_UNIT_C : TEMP_UNIT_F;
            }
            if ((hscanTempUnit == 0 || hscanTempUnit == 1) && usmReady) {
                return hscanTempUnit == 0 ? TEMP_UNIT_C : TEMP_UNIT_F;
            }
            return TEMP_UNIT_UNKNOWN;
        }

        public static CabinState fromSignalMap(Map<String, String> m) {
            if (m == null || m.isEmpty()) return UNAVAILABLE;
            int black = parseTriState(m.get("BLACKSCREEN.isBlackScreenShown"));
            int avOff = parseTriState(m.get("POWER.isAvPowerOff"));
            int dispOff = parseTriState(m.get("POWER.isDisplayOffMode"));
            int unit = resolveTempUnit(
                    PassengerPresence.parseRawInt(m.get("USM.getTemperatureUnit")),
                    PassengerPresence.parseRawInt(m.get("USM.getDistanceSpeedUnit")),
                    PassengerPresence.parseRawInt(m.get("DATC.getTemperatureUnit")),
                    PassengerPresence.parseRawInt(m.get("HSCAN.getFATC6_CR_Fatc_TempDispUnit")),
                    PassengerPresence.parseRawInt(m.get("HSCAN.getTeperatureUnit")),
                    PassengerPresence.parseRawInt(m.get("PUSH.temp_unit")));
            return new CabinState(
                    PassengerPresence.fromSignalMap(m),
                    m.get("PUSH.outside_temp_f"),
                    m.get("PUSH.outside_temp"),
                    m.get("PUSH.wheel_heat"),
                    parseTriState(m.get("BCM.getIGNStatus")),
                    parseTriState(m.get("DATC.isIGN3Status")),
                    computeAvAwake(black, avOff, dispOff),
                    computeMobisSleeping(black, avOff),
                    parseTriState(m.get("PUSH.datc_sentinel")) == 1,
                    SeatCapabilities.fromSignalMap(m),
                    unit);
        }

        public String encode() {
            return passenger.encode() + "\t"
                    + outsideTempF + "\t"
                    + outsideTempC + "\t"
                    + wheelHeat + "\t"
                    + ignOn + "\t"
                    + ign3On + "\t"
                    + avAwake + "\t"
                    + mobisSleeping + "\t"
                    + (datcSentinel ? "1" : "0") + "\t"
                    + seatCaps.encode() + "\t"
                    + tempUnit;
        }

        public static CabinState decode(String wire) {
            if (wire == null || wire.isEmpty()) return UNAVAILABLE;
            String[] p = wire.split("\t", -1);
            if (p.length < 9) return UNAVAILABLE;
            SeatCapabilities caps = p.length >= 10
                    ? SeatCapabilities.decode(p[9])
                    : SeatCapabilities.UNKNOWN;
            int unit = p.length >= 11
                    ? PassengerPresence.parseRawInt(p[10])
                    : TEMP_UNIT_UNKNOWN;
            return new CabinState(
                    PassengerPresence.decode(p[0]),
                    p[1],
                    p[2],
                    p[3],
                    PassengerPresence.parseRawInt(p[4]),
                    PassengerPresence.parseRawInt(p[5]),
                    PassengerPresence.parseRawInt(p[6]),
                    PassengerPresence.parseRawInt(p[7]),
                    "1".equals(p[8].trim()),
                    caps,
                    unit);
        }

        public String summary() {
            String u = tempUnit == TEMP_UNIT_C ? "C" : (tempUnit == TEMP_UNIT_F ? "F" : "-");
            return "ign=" + triName(ignOn)
                    + " ign3=" + triName(ign3On)
                    + " av_awake=" + triName(avAwake)
                    + " unit=" + u
                    + " push_temp_f=" + outsideTempF
                    + " push_temp_c=" + outsideTempC
                    + (datcSentinel ? "(sentinel)" : "")
                    + " push_wheel=" + wheelHeat
                    + " caps=[" + seatCaps.summary() + "]";
        }

        private static int normTempUnit(int u) {
            return (u == TEMP_UNIT_C || u == TEMP_UNIT_F) ? u : TEMP_UNIT_UNKNOWN;
        }

        private static String normField(String s) {
            if (s == null) return "-";
            String t = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
            return t.isEmpty() ? "-" : t;
        }

        static int parseTriState(String s) {
            if (s == null) return -1;
            String t = s.trim();
            if ("1".equals(t) || "true".equalsIgnoreCase(t)) return 1;
            if ("0".equals(t) || "false".equalsIgnoreCase(t)) return 0;
            return -1;
        }

        private static String triName(int v) {
            return v == 1 ? "1" : (v == 0 ? "0" : "-");
        }
    }

    private final SignalSource source;

    public OccupantSignalProbe() {
        this(new ReflectionGen5wSignalSource());
    }

    public OccupantSignalProbe(SignalSource source) {
        this.source = source == null ? new ReflectionGen5wSignalSource() : source;
    }

    public OccupantSignalProbe(SignalSource source, String unusedLabel) {
        this(source);
    }

    public PassengerPresence readPassengerPresence() {
        try {
            PassengerPresence p = source.readPassengerPresence();
            return p == null ? PassengerPresence.UNAVAILABLE : p;
        } catch (Throwable t) {
            return PassengerPresence.UNAVAILABLE;
        }
    }

    public CabinState readCabinState() {
        try {
            CabinState c = source.readCabinState();
            return c == null ? CabinState.UNAVAILABLE : c;
        } catch (Throwable t) {
            return CabinState.UNAVAILABLE;
        }
    }

    public int readMobisSleepingFast() {
        try {
            return source.readMobisSleepingFast();
        } catch (Throwable t) {
            return -1;
        }
    }

    public int readPowerStatusFast() {
        try {
            if (source instanceof StandbySignalSource) {
                return ((StandbySignalSource) source).readPowerStatusFast();
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    public int readIgnStatusFast() {
        try {
            if (source instanceof StandbySignalSource) {
                return ((StandbySignalSource) source).readIgnStatusFast();
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    public void onWake() {
        try {
            source.onWake();
        } catch (Throwable ignored) {
        }
    }

    // ---- Loopback Server & Client (UID 1000 WelcomeDaemon <-> Comfort App) ----

    private static void writeFrame(DataOutputStream out, String s) throws IOException {
        byte[] b = (s == null ? "" : s).getBytes("UTF-8");
        out.writeInt(b.length);
        if (b.length > 0) {
            out.write(b);
        }
    }

    private static String readFrame(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > MAX_FRAME_BYTES) {
            throw new IOException("invalid frame length: " + len);
        }
        if (len == 0) {
            return "";
        }
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, "UTF-8");
    }

    public static final class LoopbackServer {
        private final OccupantSignalProbe probe;
        private final ServerSocket serverSocket;
        private volatile boolean running = true;
        private volatile long lastClientNano = 0L;

        public LoopbackServer(OccupantSignalProbe probe, int port) throws IOException {
            this.probe = probe;
            this.serverSocket = new ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"));
        }

        public int getLocalPort() {
            return serverSocket.getLocalPort();
        }

        public long msSinceLastClient() {
            long last = lastClientNano;
            if (last == 0L) return Long.MAX_VALUE;
            long elapsed = (System.nanoTime() - last) / 1_000_000L;
            return elapsed < 0L ? 0L : elapsed;
        }

        public void start() {
            Thread acceptThread = new Thread("ComfortCabinServer") {
                @Override
                public void run() {
                    acceptLoop();
                }
            };
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        public void stop() {
            running = false;
            try {
                serverSocket.close();
            } catch (Throwable ignored) {}
        }

        private void acceptLoop() {
            while (running) {
                Socket client = null;
                try {
                    client = serverSocket.accept();
                    InetAddress addr = client.getInetAddress();
                    if (addr == null || !addr.isLoopbackAddress()) {
                        client.close();
                        continue;
                    }
                    client.setSoTimeout(4000);
                    handleClient(client);
                } catch (Throwable t) {
                    if (!running) break;
                } finally {
                    if (client != null) {
                        try { client.close(); } catch (Throwable ignored) {}
                    }
                }
            }
        }

        private void handleClient(Socket client) throws IOException {
            lastClientNano = System.nanoTime();
            DataInputStream in = new DataInputStream(new BufferedInputStream(client.getInputStream(), 256));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(client.getOutputStream(), 512));
            String cmd = readFrame(in);
            if ("OCCUPANCY".equals(cmd)) {
                PassengerPresence p = probe.readPassengerPresence();
                writeFrame(out, p.encode());
                out.flush();
            } else if ("CABIN_STATE".equals(cmd)) {
                CabinState c = probe.readCabinState();
                writeFrame(out, c.encode());
                out.flush();
            } else if ("WAKE".equals(cmd)) {
                probe.onWake();
                writeFrame(out, "1");
                out.flush();
            }
        }
    }

    public static final class LoopbackClient {
        private final int port;
        private final OccupantSignalProbe fallbackProbe;

        public LoopbackClient(int port, OccupantSignalProbe fallbackProbe) {
            this.port = port;
            this.fallbackProbe = fallbackProbe == null
                    ? new OccupantSignalProbe(new ReflectionGen5wSignalSource())
                    : fallbackProbe;
        }

        public boolean pingDaemon() {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress("127.0.0.1", port), 350);
                return true;
            } catch (IOException e) {
                return false;
            } finally {
                try { s.close(); } catch (Throwable ignored) {}
            }
        }

        public PassengerPresence queryPassengerPresence() {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress("127.0.0.1", port), 300);
                s.setSoTimeout(1200);
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 128));
                DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 256));
                writeFrame(out, "OCCUPANCY");
                out.flush();
                return PassengerPresence.decode(readFrame(in));
            } catch (Throwable ignored) {
                return fallbackProbe.readPassengerPresence();
            } finally {
                try { s.close(); } catch (Throwable ignored) {}
            }
        }

        public CabinState queryCabinState() {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress("127.0.0.1", port), 350);
                s.setSoTimeout(1600);
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 128));
                DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 512));
                writeFrame(out, "CABIN_STATE");
                out.flush();
                return CabinState.decode(readFrame(in));
            } catch (Throwable ignored) {
                return fallbackProbe.readCabinState();
            } finally {
                try { s.close(); } catch (Throwable ignored) {}
            }
        }

        public void notifyWake() {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress("127.0.0.1", port), 300);
                s.setSoTimeout(1000);
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 64));
                DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 64));
                writeFrame(out, "WAKE");
                out.flush();
                readFrame(in);
            } catch (Throwable ignored) {
                fallbackProbe.onWake();
            } finally {
                try { s.close(); } catch (Throwable ignored) {}
            }
        }
    }

    // ---- Reflection-Backed Gen5W OEM Service Signal Source ----

    static final class ReflectionGen5wSignalSource implements StandbySignalSource {

        private static final String HCAN_JAR = "/system/framework/com.hkmc.ext.hcan.jar";

        private final PushTelemetrySource pushSource;
        private boolean pushAwake = true;

        private boolean standbyInitialized;
        private boolean standbyServicesReady;
        private long lastStandbyInitMs;

        private boolean initialized;
        private boolean coreServicesReady;
        private long lastInitAttemptMs;

        private BoundGetter bgHscanOccupancy;
        private BoundGetter bgSysClassStatus;
        private BoundGetter bgAsstBelt;
        private BoundGetter bgPassStOcc;
        private BoundGetter bgBcmIgnStatus;
        private BoundGetter bgDatcIgn3Status;
        private BoundGetter bgBlackScreenShown;
        private BoundGetter bgAvPowerOff;
        private BoundGetter bgDisplayOffMode;
        private BoundGetter bgPowerStatus;
        private BoundGetter bgBcmDrvSeatOpt;
        private BoundGetter bgBcmAstSeatOpt;
        private BoundGetter bgBcmRlSeatOpt;
        private BoundGetter bgBcmRrSeatOpt;
        private BoundGetter bgUsmDrvSeatOpt;
        private BoundGetter bgUsmAstSeatOpt;
        private BoundGetter bgUsmRlSeatOpt;
        private BoundGetter bgUsmRrSeatOpt;
        private BoundGetter bgUsmTempUnit;
        private BoundGetter bgUsmDistUnit;
        private BoundGetter bgDatcTempUnit;
        private BoundGetter bgHscanFatcTempUnit;
        private BoundGetter bgHscanTempUnit;

        ReflectionGen5wSignalSource() {
            this(null);
        }

        ReflectionGen5wSignalSource(PushTelemetrySource pushSource) {
            this.pushSource = pushSource;
        }

        @Override
        public synchronized PassengerPresence readPassengerPresence() {
            ensureInitialized();
            int occ = invokeIntGetter(bgHscanOccupancy, -1);
            int cls = invokeIntGetter(bgSysClassStatus, -1);
            int belt = invokeIntGetter(bgAsstBelt, -1);
            int passOcc = invokeIntGetter(bgPassStOcc, -1);
            return PassengerPresence.fromRaw(occ, cls, belt, passOcc);
        }

        @Override
        public CabinState readCabinState() {
            PassengerPresence passenger;
            SeatCapabilities seatCaps;
            int ignOn;
            int ign3On;
            int avAwake;
            int mobisSleeping;
            int usmTempUnit;
            int usmDistUnit;
            int datcTempUnit;
            int hscanFatcUnit;
            int hscanTempUnit;
            boolean sleepStandby;
            boolean unregisterSleep = false;
            synchronized (this) {
                ensureInitialized();
                int occ = invokeIntGetter(bgHscanOccupancy, -1);
                int cls = invokeIntGetter(bgSysClassStatus, -1);
                int belt = invokeIntGetter(bgAsstBelt, -1);
                int passOcc = invokeIntGetter(bgPassStOcc, -1);
                passenger = PassengerPresence.fromRaw(occ, cls, belt, passOcc);

                int drvOpt = SeatCapabilities.pickOption(
                        invokeIntGetter(bgBcmDrvSeatOpt, -1),
                        invokeIntGetter(bgUsmDrvSeatOpt, -1));
                int astOpt = SeatCapabilities.pickOption(
                        invokeIntGetter(bgBcmAstSeatOpt, -1),
                        invokeIntGetter(bgUsmAstSeatOpt, -1));
                int rlOpt = SeatCapabilities.pickOption(
                        invokeIntGetter(bgBcmRlSeatOpt, -1),
                        invokeIntGetter(bgUsmRlSeatOpt, -1));
                int rrOpt = SeatCapabilities.pickOption(
                        invokeIntGetter(bgBcmRrSeatOpt, -1),
                        invokeIntGetter(bgUsmRrSeatOpt, -1));
                seatCaps = SeatCapabilities.fromRawOptions(drvOpt, astOpt, rlOpt, rrOpt);

                ignOn = invokeIntGetter(bgBcmIgnStatus, -1);
                ign3On = invokeIntGetter(bgDatcIgn3Status, -1);
                int black = invokeIntGetter(bgBlackScreenShown, -1);
                int avOff = invokeIntGetter(bgAvPowerOff, -1);
                int dispOff = invokeIntGetter(bgDisplayOffMode, -1);
                avAwake = CabinState.computeAvAwake(black, avOff, dispOff);
                mobisSleeping = CabinState.computeMobisSleeping(black, avOff);

                usmTempUnit = invokeIntGetter(bgUsmTempUnit, -1);
                usmDistUnit = invokeIntGetter(bgUsmDistUnit, -1);
                datcTempUnit = invokeIntGetter(bgDatcTempUnit, -1);
                hscanFatcUnit = invokeIntGetter(bgHscanFatcTempUnit, -1);
                hscanTempUnit = invokeIntGetter(bgHscanTempUnit, -1);

                sleepStandby = (mobisSleeping == 1 && ignOn != 1);
                if (sleepStandby && pushAwake) {
                    pushAwake = false;
                    unregisterSleep = true;
                } else if (!sleepStandby && !pushAwake) {
                    pushAwake = true;
                }
            }

            String tempF = "-";
            String tempC = "-";
            String wheel = "-";
            boolean sentinel = false;
            int pushTempUnit = TEMP_UNIT_UNKNOWN;
            if (pushSource != null) {
                if (sleepStandby) {
                    if (unregisterSleep) {
                        pushSource.onWake();
                    }
                } else {
                    pushSource.ensure(ignOn == 1);
                    tempF = pushSource.outsideTempF();
                    tempC = pushSource.outsideTempC();
                    wheel = pushSource.wheelHeat();
                    sentinel = pushSource.isDatcSentinel();
                    pushTempUnit = pushSource.tempUnit();
                }
            }
            int tempUnit = CabinState.resolveTempUnit(
                    usmTempUnit, usmDistUnit, datcTempUnit,
                    hscanFatcUnit, hscanTempUnit, pushTempUnit);
            return new CabinState(
                    passenger, tempF, tempC, wheel,
                    ignOn, ign3On, avAwake, mobisSleeping, sentinel, seatCaps, tempUnit);
        }

        @Override
        public synchronized int readMobisSleepingFast() {
            ensureStandbyBound();
            int black = invokeIntGetter(bgBlackScreenShown, -1);
            int avOff = invokeIntGetter(bgAvPowerOff, -1);
            return CabinState.computeMobisSleeping(black, avOff);
        }

        @Override
        public synchronized int readPowerStatusFast() {
            ensureStandbyBound();
            return invokeIntGetter(bgPowerStatus, -1);
        }

        @Override
        public synchronized int readIgnStatusFast() {
            ensureStandbyBound();
            return invokeIntGetter(bgBcmIgnStatus, -1);
        }

        @Override
        public void onWake() {
            synchronized (this) {
                pushAwake = true;
            }
            if (pushSource != null) {
                pushSource.onWake();
            }
        }

        private static int invokeIntGetter(BoundGetter bg, int fallback) {
            if (bg == null) return fallback;
            try {
                Object v = bg.method.invoke(bg.target);
                if (v instanceof Number) {
                    return ((Number) v).intValue();
                }
                if (v instanceof Boolean) {
                    return ((Boolean) v).booleanValue() ? 1 : 0;
                }
            } catch (Throwable ignored) {
            }
            return fallback;
        }

        /**
         * Lightweight standby binder init used by {@link WelcomeDaemon} during cold boot and
         * dark/silent wakes: binds only {@code BCM}, {@code BLACKSCREEN}, and {@code POWER}
         * without opening {@code /system/framework/com.hkmc.ext.hcan.jar} or touching HSCAN/USM/DATC.
         */
        private void ensureStandbyBound() {
            long now = System.currentTimeMillis();
            if ((initialized && coreServicesReady)
                    || (standbyInitialized && (standbyServicesReady || now - lastStandbyInitMs < 5000L))) {
                return;
            }
            standbyInitialized = true;
            lastStandbyInitMs = now;
            try {
                Class<?> smClass = Class.forName("android.os.ServiceManager");
                Method getService = smClass.getMethod("getService", String.class);
                Class<?> ibinderClass = Class.forName("android.os.IBinder");

                Class<?> carSvc = null;
                try {
                    carSvc = Class.forName("com.hkmc.Service");
                } catch (Throwable ignored) {}

                BoundService bcm = bindKnownService(getService, ibinderClass, carSvc,
                        "BCM_SERVICE", "com.hkmc.micom.bcm", "com.hkmc.bcm.IBCMService", null);
                if (bcm != null) {
                    bgBcmIgnStatus = bindGetter(bcm, "getIGNStatus");
                }

                BoundService blackScreen = bindDescriptorService(
                        getService, ibinderClass, carSvc, "BLACKSCREEN_SERVICE");
                if (blackScreen != null) {
                    bgBlackScreenShown = bindGetter(blackScreen, "isBlackScreenShown");
                }

                BoundService power = bindDescriptorService(
                        getService, ibinderClass, carSvc, "POWER_SERVICE");
                if (power != null) {
                    bgAvPowerOff = bindGetter(power, "isAvPowerOff");
                    bgDisplayOffMode = bindGetter(power, "isDisplayOffMode");
                    bgPowerStatus = bindGetter(power, "getPowerStatus");
                }

                standbyServicesReady = (bcm != null && power != null);
            } catch (Throwable ignored) {
            }
        }

        private void ensureInitialized() {
            long now = System.currentTimeMillis();
            if (initialized && (coreServicesReady || now - lastInitAttemptMs < 5000L)) {
                return;
            }
            initialized = true;
            standbyInitialized = true;
            lastInitAttemptMs = now;
            lastStandbyInitMs = now;
            bgHscanOccupancy = null;
            bgSysClassStatus = null;
            bgAsstBelt = null;
            bgPassStOcc = null;
            bgBcmIgnStatus = null;
            bgDatcIgn3Status = null;
            bgBlackScreenShown = null;
            bgAvPowerOff = null;
            bgDisplayOffMode = null;
            bgPowerStatus = null;
            bgBcmDrvSeatOpt = null;
            bgBcmAstSeatOpt = null;
            bgBcmRlSeatOpt = null;
            bgBcmRrSeatOpt = null;
            bgUsmDrvSeatOpt = null;
            bgUsmAstSeatOpt = null;
            bgUsmRlSeatOpt = null;
            bgUsmRrSeatOpt = null;
            bgUsmTempUnit = null;
            bgUsmDistUnit = null;
            bgDatcTempUnit = null;
            bgHscanFatcTempUnit = null;
            bgHscanTempUnit = null;

            try {
                Class<?> smClass = Class.forName("android.os.ServiceManager");
                Method getService = smClass.getMethod("getService", String.class);
                Class<?> ibinderClass = Class.forName("android.os.IBinder");

                Class<?> carSvc = null;
                try {
                    carSvc = Class.forName("com.hkmc.Service");
                } catch (Throwable ignored) {}

                // 1. BCM (ignition status & seat heat/vent hardware option codes)
                BoundService bcm = bindKnownService(getService, ibinderClass, carSvc,
                        "BCM_SERVICE", "com.hkmc.micom.bcm", "com.hkmc.bcm.IBCMService", null);
                bgBcmIgnStatus = bindGetter(bcm, "getIGNStatus");
                bgBcmDrvSeatOpt = bindGetter(bcm, "getDrvSeatHeatOption");
                bgBcmAstSeatOpt = bindGetter(bcm, "getAstSeatHeatOption");
                bgBcmRlSeatOpt = bindGetter(bcm, "getRLSeatHeatOption");
                bgBcmRrSeatOpt = bindGetter(bcm, "getRRSeatHeatOption");

                // 2. HSCAN (front-passenger occupancy, buckle status & FATC temperature unit)
                BoundService hscan = null;
                ClassLoader hcanLoader = ReflectionGen5wSignalSource.class.getClassLoader();
                try {
                    File jar = new File(HCAN_JAR);
                    if (jar.isFile()) {
                        Class<?> pcl = Class.forName("dalvik.system.PathClassLoader");
                        hcanLoader = (ClassLoader) pcl.getConstructor(String.class, ClassLoader.class)
                                .newInstance(HCAN_JAR, hcanLoader);
                    }
                    Class<?> adapter = Class.forName(
                            "com.hkmc.telematics.hscan.tm_interface.TM_HSCANAdapter", true, hcanLoader);
                    hscan = bindKnownService(getService, ibinderClass, adapter,
                            "TM_HSCAN_SERVICE", "tm_hscan_service",
                            "com.hkmc.telematics.hscan.tm_interface.ITM_HSCANService", hcanLoader);
                } catch (Throwable ignored) {}
                bgHscanOccupancy = bindGetter(hscan, "getOccupancy");
                bgAsstBelt = bindGetter(hscan, "getAsstStBltSwSta");
                bgPassStOcc = bindGetter(hscan, "getPassStOccSta");
                bgHscanFatcTempUnit = bindGetter(hscan, "getFATC6_CR_Fatc_TempDispUnit");
                bgHscanTempUnit = bindGetter(hscan, "getTeperatureUnit");

                // 3. SYSTEM (ODS passenger mat classification)
                BoundService sys = bindKnownService(getService, ibinderClass, carSvc,
                        "SYSTEM_SERVICE", "com.hkmc.micom.system", "com.hkmc.micom.ISystemService", null);
                bgSysClassStatus = bindGetter(sys, "getClassStatus");

                // 4. USM (fallback seat option codes & owner temperature/distance unit settings)
                BoundService usm = bindKnownService(getService, ibinderClass, carSvc,
                        "USM_SERVICE", "com.hkmc.micom.usm", "com.hkmc.usm.IUSMService", null);
                bgUsmDrvSeatOpt = bindGetter(usm, "getFrontSeatHeat_DrvOption");
                bgUsmAstSeatOpt = bindGetter(usm, "getFrontSeatHeat_AstOption");
                bgUsmRlSeatOpt = bindGetter(usm, "getRearSeatHeat_LeftOption");
                bgUsmRrSeatOpt = bindGetter(usm, "getRearSeatHeat_RightOption");
                bgUsmTempUnit = bindGetter(usm, "getTemperatureUnit");
                bgUsmDistUnit = bindGetter(usm, "getDistanceSpeedUnit");

                // 5. DATC (IGN3 active status during charging/utility & climate temperature unit)
                BoundService datc = bindKnownService(getService, ibinderClass, carSvc,
                        "DATC_SERVICE", "com.hkmc.micom.datc", "com.hkmc.datc.IDATCService", null);
                bgDatcIgn3Status = bindGetter(datc, "isIGN3Status");
                bgDatcTempUnit = bindGetter(datc, "getTemperatureUnit");

                // 6. BLACKSCREEN & POWER (Mobis QuickBoot sleep, power status & Display-Off detection)
                BoundService blackScreen = bindDescriptorService(
                        getService, ibinderClass, carSvc, "BLACKSCREEN_SERVICE");
                bgBlackScreenShown = bindGetter(blackScreen, "isBlackScreenShown");

                BoundService power = bindDescriptorService(
                        getService, ibinderClass, carSvc, "POWER_SERVICE");
                bgAvPowerOff = bindGetter(power, "isAvPowerOff");
                bgDisplayOffMode = bindGetter(power, "isDisplayOffMode");
                bgPowerStatus = bindGetter(power, "getPowerStatus");

                standbyServicesReady = (bcm != null && power != null);
                coreServicesReady = (bcm != null && hscan != null);
            } catch (Throwable ignored) {
            }
        }

        private static BoundService bindKnownService(
                Method getService,
                Class<?> ibinderClass,
                Class<?> nameHolder,
                String fieldName,
                String fallbackSvcName,
                String ifaceName,
                ClassLoader loader) {
            String svcName = resolveServiceName(nameHolder, fieldName, fallbackSvcName);
            if (svcName == null || svcName.isEmpty()) return null;
            try {
                Object binder = getService.invoke(null, svcName);
                if (binder == null) return null;
                Class<?> stub = loader == null
                        ? Class.forName(ifaceName + "$Stub")
                        : Class.forName(ifaceName + "$Stub", true, loader);
                Object proxy = stub.getMethod("asInterface", ibinderClass).invoke(null, binder);
                if (proxy == null) return null;
                Class<?> iface = loader == null
                        ? Class.forName(ifaceName)
                        : Class.forName(ifaceName, true, loader);
                return new BoundService(proxy, iface);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static BoundService bindDescriptorService(
                Method getService,
                Class<?> ibinderClass,
                Class<?> carSvc,
                String fieldName) {
            String svcName = resolveServiceName(carSvc, fieldName, null);
            if (svcName == null || svcName.isEmpty()) return null;
            try {
                Object binder = getService.invoke(null, svcName);
                if (binder == null) return null;
                Object descObj = ibinderClass.getMethod("getInterfaceDescriptor").invoke(binder);
                if (!(descObj instanceof String)) return null;
                String desc = ((String) descObj).trim();
                if (!desc.startsWith("com.hkmc.")) return null;
                Class<?> stub = Class.forName(desc + "$Stub");
                Object proxy = stub.getMethod("asInterface", ibinderClass).invoke(null, binder);
                if (proxy == null) return null;
                Class<?> iface = Class.forName(desc);
                return new BoundService(proxy, iface);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static BoundGetter bindGetter(BoundService svc, String methodName) {
            if (svc == null) return null;
            try {
                Method m = svc.iface.getMethod(methodName);
                Class<?> rt = m.getReturnType();
                if (rt == int.class || rt == boolean.class || rt == short.class || rt == byte.class) {
                    return new BoundGetter(svc.proxy, m);
                }
            } catch (Throwable ignored) {
            }
            return null;
        }

        private static String resolveServiceName(Class<?> holder, String fieldName, String fallback) {
            if (holder != null) {
                try {
                    Object v = holder.getField(fieldName).get(null);
                    if (v instanceof String && !((String) v).isEmpty()) {
                        return ((String) v).trim();
                    }
                } catch (Throwable ignored) {}
            }
            return fallback;
        }

        private static final class BoundService {
            final Object proxy;
            final Class<?> iface;

            BoundService(Object proxy, Class<?> iface) {
                this.proxy = proxy;
                this.iface = iface;
            }
        }

        private static final class BoundGetter {
            final Object target;
            final Method method;

            BoundGetter(Object target, Method method) {
                this.target = target;
                this.method = method;
            }
        }
    }
}
