package dev.lemoyne.comfort;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure-Java decision logic and session trackers for Comfort & Startup, testable on a desktop JVM.
 */
public final class ComfortPolicy {
    /**
     * hmgapid enforces MAX_ARGS = 16 on wire frames ("vehicle" + up to 15 reading names).
     * Chunking vehicle reads into <= 8 keys per call keeps argc <= 9 with generous headroom.
     */
    public static final int MAX_VEHICLE_BATCH_KEYS = 8;

    private ComfortPolicy() {}

    /** Splits a vehicle reading key array into sub-arrays of at most {@code maxPerBatch} keys. */
    public static List<String[]> chunkVehicleKeys(String[] keys, int maxPerBatch) {
        if (keys == null || keys.length == 0) {
            return Collections.emptyList();
        }
        int step = Math.max(1, Math.min(15, maxPerBatch));
        List<String[]> chunks = new ArrayList<String[]>((keys.length + step - 1) / step);
        for (int i = 0; i < keys.length; i += step) {
            int end = Math.min(keys.length, i + step);
            chunks.add(Arrays.copyOfRange(keys, i, end));
        }
        return chunks;
    }

    public static final class Action {
        public final String command;   // "seat_heat", "seat_vent", or "wheel_heat"
        public final String target;    // seat name ("driver", ...) or "on" for wheel_heat
        public final String level;     // "low"/"medium"/"high" for seats, "" for wheel_heat

        public Action(String command, String target, String level) {
            this.command = command;
            this.target = target;
            this.level = level == null ? "" : level;
        }

        public String[] args() {
            return level.isEmpty() ? new String[] { target } : new String[] { target, level };
        }

        @Override
        public String toString() {
            return level.isEmpty() ? command + " " + target : command + " " + target + " " + level;
        }
    }

    public static final class Plan {
        public final List<Action> actions;
        public final String summary;

        public Plan(List<Action> actions, String summary) {
            this.actions = Collections.unmodifiableList(actions);
            this.summary = summary;
        }
    }

    /** Rejects NaN, Infinite, CAN sentinels (215/255), and DATC's -40.0 F raw-zero wake sentinel. */
    public static boolean validAmbientF(float f) {
        return !Float.isNaN(f) && !Float.isInfinite(f) && Math.abs(f + 40f) > 0.05f && f >= -58f && f <= 158f;
    }

    /** Rejects NaN, Infinite, CAN sentinels (87.5/101.7/123.9/255), and DATC's -40.0 C raw-zero wake sentinel. */
    public static boolean validAmbientC(float c) {
        return !Float.isNaN(c) && !Float.isInfinite(c) && Math.abs(c + 40f) > 0.05f && c >= -50f && c <= 70f;
    }

    public static float celsiusToFahrenheit(float c) {
        return c * 9f / 5f + 32f;
    }

    public static float fahrenheitToCelsius(float f) {
        return (f - 32f) * 5f / 9f;
    }

    public static String formatTenths(float v) {
        int t = Math.round(v * 10f);
        if (t % 10 == 0) {
            return String.valueOf(t / 10);
        }
        return String.format(Locale.US, "%.1f", Float.valueOf(t / 10f));
    }

    /** Parses an outside_temp_f reading string ("28.5" or "-"), returning Float.NaN when unavailable or sentinel. */
    public static float parseAmbientF(String text) {
        if (text == null || text.isEmpty() || "-".equals(text)) return Float.NaN;
        try {
            float f = Float.parseFloat(text.trim());
            return validAmbientF(f) ? f : Float.NaN;
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    /** Parses an outside_temp (°C) reading string ("4.5" or "-"), returning Float.NaN when unavailable or sentinel. */
    public static float parseAmbientC(String text) {
        if (text == null || text.isEmpty() || "-".equals(text)) return Float.NaN;
        try {
            float c = Float.parseFloat(text.trim());
            return validAmbientC(c) ? c : Float.NaN;
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    /** Reads ambient temperature in °F from a vehicle readings map, falling back to converting {@code outside_temp} (°C). */
    public static float readAmbientF(Map<String, String> readings) {
        if (readings == null) return Float.NaN;
        float f = parseAmbientF(readings.get("outside_temp_f"));
        if (!Float.isNaN(f)) return f;
        float c = parseAmbientC(readings.get("outside_temp"));
        return Float.isNaN(c) ? Float.NaN : celsiusToFahrenheit(c);
    }

    /** Reads ambient temperature in °C from a vehicle readings map, falling back to converting {@code outside_temp_f} (°F). */
    public static float readAmbientC(Map<String, String> readings) {
        if (readings == null) return Float.NaN;
        float c = parseAmbientC(readings.get("outside_temp"));
        if (!Float.isNaN(c)) return c;
        float f = parseAmbientF(readings.get("outside_temp_f"));
        return Float.isNaN(f) ? Float.NaN : fahrenheitToCelsius(f);
    }

    /** Formats live ambient temperature in the vehicle's active unit ("18.5°C" / "65°F"), or "" when unavailable. */
    public static String formatAmbientDisplay(Map<String, String> readings, boolean metric) {
        if (metric) {
            float c = readAmbientC(readings);
            return Float.isNaN(c) ? "" : formatTenths(c) + "°C";
        }
        float f = readAmbientF(readings);
        return Float.isNaN(f) ? "" : Math.round(f) + "°F";
    }

    /**
     * Fills missing ("-") or sentinel ("255", "-40") push-only vehicle readings
     * ({@code outside_temp_f}, {@code outside_temp}, {@code wheel_heat}) from
     * {@link OccupantSignalProbe.CabinState}'s fresh Binder push bridge or cross-unit conversion,
     * ensuring Metric (°C) vehicles work seamlessly even when HMG-API's {@code outside_temp_f}
     * carries an unpopulated sentinel or holds at "-".
     */
    public static Map<String, String> mergeCabinFallback(
            Map<String, String> readings, OccupantSignalProbe.CabinState cabin) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (readings != null) {
            out.putAll(readings);
        }

        float curF = parseAmbientF(out.get("outside_temp_f"));
        float curC = parseAmbientC(out.get("outside_temp"));
        float cabF = cabin == null ? Float.NaN : parseAmbientF(cabin.outsideTempF);
        float cabC = cabin == null ? Float.NaN : parseAmbientC(cabin.outsideTempC);

        // If HMG-API has mismatched F vs C (e.g. unpopulated 0.0 F alongside valid C), prefer CabinPushBridge
        if (!Float.isNaN(curF) && !Float.isNaN(curC)
                && Math.abs(curF - celsiusToFahrenheit(curC)) > 3.0f) {
            if (!Float.isNaN(cabF) && !Float.isNaN(cabC)) {
                curF = cabF;
                curC = cabC;
                out.put("outside_temp_f", cabin.outsideTempF);
                out.put("outside_temp", cabin.outsideTempC);
            } else if (cabin != null && cabin.tempUnit == OccupantSignalProbe.TEMP_UNIT_C) {
                curF = celsiusToFahrenheit(curC);
                out.put("outside_temp_f", formatTenths(curF));
            } else if (cabin != null && cabin.tempUnit == OccupantSignalProbe.TEMP_UNIT_F) {
                curC = fahrenheitToCelsius(curF);
                out.put("outside_temp", formatTenths(curC));
            }
        }

        if (Float.isNaN(curF)) {
            if (!Float.isNaN(cabF)) {
                curF = cabF;
                out.put("outside_temp_f", cabin.outsideTempF);
            } else if (!Float.isNaN(curC)) {
                curF = celsiusToFahrenheit(curC);
                out.put("outside_temp_f", formatTenths(curF));
            } else if (!Float.isNaN(cabC)) {
                curF = celsiusToFahrenheit(cabC);
                out.put("outside_temp_f", formatTenths(curF));
            } else if (out.containsKey("outside_temp_f")) {
                out.put("outside_temp_f", "-");
            }
        }

        if (Float.isNaN(curC)) {
            if (!Float.isNaN(cabC)) {
                curC = cabC;
                out.put("outside_temp", cabin.outsideTempC);
            } else if (!Float.isNaN(curF)) {
                curC = fahrenheitToCelsius(curF);
                out.put("outside_temp", formatTenths(curC));
            } else if (!Float.isNaN(cabF)) {
                curC = fahrenheitToCelsius(cabF);
                out.put("outside_temp", formatTenths(curC));
            } else if (out.containsKey("outside_temp")) {
                out.put("outside_temp", "-");
            }
        }

        if (cabin != null) {
            String curWheel = out.get("wheel_heat");
            if ((curWheel == null || curWheel.isEmpty() || "-".equals(curWheel))
                    && ("0".equals(cabin.wheelHeat) || "1".equals(cabin.wheelHeat))) {
                out.put("wheel_heat", cabin.wheelHeat);
            }
        }
        return out;
    }

    public static Plan evaluateClimate(ComfortConfig cfg, float tempF, Map<String, String> readings) {
        return evaluateClimate(
                cfg, tempF, readings,
                OccupantSignalProbe.PassengerPresence.UNAVAILABLE,
                OccupantSignalProbe.SeatCapabilities.UNKNOWN);
    }

    public static Plan evaluateClimate(
            ComfortConfig cfg,
            float tempF,
            Map<String, String> readings,
            OccupantSignalProbe.PassengerPresence passengerPresence) {
        return evaluateClimate(
                cfg, tempF, readings, passengerPresence,
                OccupantSignalProbe.SeatCapabilities.UNKNOWN);
    }

    /**
     * Unit-aware climate evaluation: compares {@code outside_temp} (°C) directly against {@code cfg.*C}
     * when {@code metric} is true, or {@code outside_temp_f} (°F) against {@code cfg.*F} when false.
     */
    public static Plan evaluateClimate(
            ComfortConfig cfg,
            Map<String, String> readings,
            OccupantSignalProbe.PassengerPresence passengerPresence,
            OccupantSignalProbe.SeatCapabilities seatCaps,
            boolean metric) {
        if (!metric) {
            return evaluateClimate(cfg, readAmbientF(readings), readings, passengerPresence, seatCaps);
        }
        float tempC = readAmbientC(readings);
        if (!cfg.climateEnabled || !validAmbientC(tempC)) {
            return new Plan(Collections.<Action>emptyList(), "Climate disabled or invalid ambient temp");
        }
        int driverHeat = 0;
        if (tempC <= cfg.heatHighC) driverHeat = 3;
        else if (tempC <= cfg.heatMedC) driverHeat = 2;
        else if (tempC <= cfg.heatLowC) driverHeat = 1;

        int driverVent = 0;
        if (driverHeat == 0 && cfg.ventEnabled && tempC >= cfg.ventLowC) {
            driverVent = tempC >= cfg.ventHighC ? 3 : (tempC >= cfg.ventMedC ? 2 : 1);
        }
        boolean wheelShouldRun = cfg.wheelEnabled && tempC <= cfg.wheelC;
        return buildClimatePlan(
                cfg, readings, passengerPresence, seatCaps,
                driverHeat, driverVent, wheelShouldRun,
                formatTenths(tempC) + "°C");
    }

    /**
     * Evaluates cold-start heat, steering wheel heat, and summer ventilation targets for a drive,
     * skipping any seat or wheel already active from Bluelink remote climate or manual driver input,
     * gating front-passenger heat/ventilation on live passenger seat occupancy when enabled, and
     * skipping optional seat ventilation or rear seat heaters when confirmed absent on the vehicle.
     */
    public static Plan evaluateClimate(
            ComfortConfig cfg,
            float tempF,
            Map<String, String> readings,
            OccupantSignalProbe.PassengerPresence passengerPresence,
            OccupantSignalProbe.SeatCapabilities seatCaps) {
        if (!cfg.climateEnabled || !validAmbientF(tempF)) {
            return new Plan(Collections.<Action>emptyList(), "Climate disabled or invalid ambient temp");
        }
        int driverHeat = 0;
        if (tempF <= cfg.heatHighF) driverHeat = 3;
        else if (tempF <= cfg.heatMedF) driverHeat = 2;
        else if (tempF <= cfg.heatLowF) driverHeat = 1;

        int driverVent = 0;
        if (driverHeat == 0 && cfg.ventEnabled && tempF >= cfg.ventLowF) {
            driverVent = tempF >= cfg.ventHighF ? 3 : (tempF >= cfg.ventMedF ? 2 : 1);
        }
        boolean wheelShouldRun = cfg.wheelEnabled && tempF <= cfg.wheelF;
        return buildClimatePlan(
                cfg, readings, passengerPresence, seatCaps,
                driverHeat, driverVent, wheelShouldRun,
                String.format(Locale.US, "%.1f F", Float.valueOf(tempF)));
    }

    private static Plan buildClimatePlan(
            ComfortConfig cfg,
            Map<String, String> readings,
            OccupantSignalProbe.PassengerPresence passengerPresence,
            OccupantSignalProbe.SeatCapabilities seatCaps,
            int driverHeat,
            int driverVent,
            boolean wheelShouldRun,
            String tempLabel) {
        OccupantSignalProbe.SeatCapabilities caps = seatCaps == null
                ? OccupantSignalProbe.SeatCapabilities.UNKNOWN
                : seatCaps;
        List<Action> out = new ArrayList<Action>();
        List<String> notes = new ArrayList<String>();

        if (driverHeat > 0) {
            addSeatAction(out, notes, readings, "seat_heat", "driver", driverHeat, false);
            addPassengerSeatAction(out, notes, readings, "seat_heat",
                    followerLevel(driverHeat, cfg.passengerPolicy), cfg.passengerAutoDetect, passengerPresence);
            if (caps.isRearLeftHeatSupported()) {
                addSeatAction(out, notes, readings, "seat_heat", "rear_left",
                        followerLevel(driverHeat, cfg.rearLeftPolicy), true);
            }
            if (caps.isRearRightHeatSupported()) {
                addSeatAction(out, notes, readings, "seat_heat", "rear_right",
                        followerLevel(driverHeat, cfg.rearRightPolicy), true);
            }
        } else if (driverVent > 0 && caps.isFrontVentSupported()) {
            addSeatAction(out, notes, readings, "seat_vent", "driver", driverVent, false);
            addPassengerSeatAction(out, notes, readings, "seat_vent",
                    followerLevel(driverVent, cfg.passengerPolicy), cfg.passengerAutoDetect, passengerPresence);
        }

        if (wheelShouldRun) {
            String wh = readings == null ? null : readings.get("wheel_heat");
            if ("1".equals(wh)) {
                notes.add("wheel already on");
            } else {
                out.add(new Action("wheel_heat", "on", ""));
                notes.add("wheel on");
            }
        }

        String summary = notes.isEmpty()
                ? "No climate needed at " + tempLabel
                : tempLabel + ": " + join(notes);
        return new Plan(out, summary);
    }

    public static Plan evaluatePassengerBoarding(
            ComfortConfig cfg,
            float tempF,
            Map<String, String> readings,
            OccupantSignalProbe.PassengerPresence passengerPresence) {
        return evaluatePassengerBoarding(
                cfg, tempF, readings, passengerPresence,
                OccupantSignalProbe.SeatCapabilities.UNKNOWN);
    }

    /**
     * Unit-aware passenger boarding evaluation: compares {@code outside_temp} (°C) against {@code cfg.*C}
     * when {@code metric} is true, or {@code outside_temp_f} (°F) against {@code cfg.*F} when false.
     */
    public static Plan evaluatePassengerBoarding(
            ComfortConfig cfg,
            Map<String, String> readings,
            OccupantSignalProbe.PassengerPresence passengerPresence,
            OccupantSignalProbe.SeatCapabilities seatCaps,
            boolean metric) {
        if (!metric) {
            return evaluatePassengerBoarding(
                    cfg, readAmbientF(readings), readings, passengerPresence, seatCaps);
        }
        float tempC = readAmbientC(readings);
        if (!cfg.climateEnabled
                || !cfg.passengerAutoDetect
                || ComfortConfig.POLICY_OFF.equals(cfg.passengerPolicy)
                || !validAmbientC(tempC)
                || passengerPresence == null
                || !passengerPresence.available
                || !passengerPresence.occupied) {
            return new Plan(Collections.<Action>emptyList(), "No passenger climate needed");
        }
        int driverHeat = 0;
        if (tempC <= cfg.heatHighC) driverHeat = 3;
        else if (tempC <= cfg.heatMedC) driverHeat = 2;
        else if (tempC <= cfg.heatLowC) driverHeat = 1;

        int driverVent = 0;
        if (driverHeat == 0 && cfg.ventEnabled && tempC >= cfg.ventLowC) {
            driverVent = tempC >= cfg.ventHighC ? 3 : (tempC >= cfg.ventMedC ? 2 : 1);
        }
        return buildPassengerBoardingPlan(
                cfg, readings, seatCaps, driverHeat, driverVent, formatTenths(tempC) + "°C");
    }

    /**
     * Evaluates front-passenger seat heating or ventilation when a passenger boards after the
     * initial startup climate sequence already ran with an empty passenger seat.
     */
    public static Plan evaluatePassengerBoarding(
            ComfortConfig cfg,
            float tempF,
            Map<String, String> readings,
            OccupantSignalProbe.PassengerPresence passengerPresence,
            OccupantSignalProbe.SeatCapabilities seatCaps) {
        if (!cfg.climateEnabled
                || !cfg.passengerAutoDetect
                || ComfortConfig.POLICY_OFF.equals(cfg.passengerPolicy)
                || !validAmbientF(tempF)
                || passengerPresence == null
                || !passengerPresence.available
                || !passengerPresence.occupied) {
            return new Plan(Collections.<Action>emptyList(), "No passenger climate needed");
        }
        int driverHeat = 0;
        if (tempF <= cfg.heatHighF) driverHeat = 3;
        else if (tempF <= cfg.heatMedF) driverHeat = 2;
        else if (tempF <= cfg.heatLowF) driverHeat = 1;

        int driverVent = 0;
        if (driverHeat == 0 && cfg.ventEnabled && tempF >= cfg.ventLowF) {
            driverVent = tempF >= cfg.ventHighF ? 3 : (tempF >= cfg.ventMedF ? 2 : 1);
        }
        return buildPassengerBoardingPlan(
                cfg, readings, seatCaps, driverHeat, driverVent,
                String.format(Locale.US, "%.1f F", Float.valueOf(tempF)));
    }

    private static Plan buildPassengerBoardingPlan(
            ComfortConfig cfg,
            Map<String, String> readings,
            OccupantSignalProbe.SeatCapabilities seatCaps,
            int driverHeat,
            int driverVent,
            String tempLabel) {
        if (isSeatAlreadyActive(readings, "passenger")) {
            return new Plan(Collections.<Action>emptyList(), "Passenger detected (seat already active)");
        }
        OccupantSignalProbe.SeatCapabilities caps = seatCaps == null
                ? OccupantSignalProbe.SeatCapabilities.UNKNOWN
                : seatCaps;

        if (driverHeat > 0) {
            int passHeat = followerLevel(driverHeat, cfg.passengerPolicy);
            if (passHeat > 0) {
                String lvl = levelName(passHeat);
                return new Plan(
                        Collections.singletonList(new Action("seat_heat", "passenger", lvl)),
                        "Passenger detected (" + tempLabel + "): passenger heat " + lvl);
            }
        } else if (driverVent > 0 && caps.isFrontVentSupported()) {
            int passVent = followerLevel(driverVent, cfg.passengerPolicy);
            if (passVent > 0) {
                String lvl = levelName(passVent);
                return new Plan(
                        Collections.singletonList(new Action("seat_vent", "passenger", lvl)),
                        "Passenger detected (" + tempLabel + "): passenger vent " + lvl);
            }
        }
        return new Plan(Collections.<Action>emptyList(), "No passenger climate needed");
    }

    public static int followerLevel(int driverLevel, String policy) {
        if (driverLevel <= 0 || ComfortConfig.POLICY_OFF.equals(policy)) return 0;
        if (ComfortConfig.POLICY_MATCH.equals(policy)) return driverLevel;
        if (ComfortConfig.POLICY_LOWER.equals(policy)) return Math.max(0, driverLevel - 1);
        return 0;
    }

    /** Fallback level when a 2-step rear seat refuses "medium": drops to "low". */
    public static String fallbackTwoStep(String level) {
        return "medium".equals(level) ? "low" : level;
    }

    private static void addPassengerSeatAction(
            List<Action> out,
            List<String> notes,
            Map<String, String> readings,
            String cmd,
            int level,
            boolean autoDetect,
            OccupantSignalProbe.PassengerPresence presence) {
        if (level <= 0) return;
        if (isSeatAlreadyActive(readings, "passenger")) {
            notes.add("passenger kept (already active)");
            return;
        }
        if (autoDetect && presence != null && presence.available && !presence.occupied) {
            notes.add("passenger empty");
            return;
        }
        String lvl = levelName(level);
        out.add(new Action(cmd, "passenger", lvl));
        notes.add("passenger" + ("seat_vent".equals(cmd) ? " vent " : " heat ") + lvl);
    }

    private static void addSeatAction(
            List<Action> out,
            List<String> notes,
            Map<String, String> readings,
            String cmd,
            String seat,
            int level,
            boolean rear) {
        if (level <= 0) return;
        if (isSeatAlreadyActive(readings, seat)) {
            notes.add(seat + " kept (already active)");
            return;
        }
        String lvl = levelName(level);
        out.add(new Action(cmd, seat, lvl));
        notes.add(seat + ("seat_vent".equals(cmd) ? " vent " : " heat ") + lvl + (rear ? "" : ""));
    }

    /** BCM seat_state: 2 is off, 3-5 ventilation, 6-8 heat; 0/15/- unknown or IG2 off. */
    public static boolean isSeatAlreadyActive(Map<String, String> readings, String seat) {
        if (readings == null) return false;
        String raw = readings.get("seat_state." + seat);
        if (raw != null && !"-".equals(raw)) {
            try {
                int st = Integer.parseInt(raw.trim());
                if (st >= 3 && st <= 8) return true;
            } catch (NumberFormatException ignored) {}
        }
        String h = readings.get("seat_heat." + seat);
        if (h != null && ("1".equals(h) || "2".equals(h) || "3".equals(h))) return true;
        String v = readings.get("seat_vent." + seat);
        return v != null && ("1".equals(v) || "2".equals(v) || "3".equals(v));
    }

    /**
     * True when BCM reports a live powered seat climate controller state (2 = off/standby,
     * 3-5 = ventilation, 6-8 = heat). When vehicle cabin/IG2 power is off (including unoccupied
     * overnight charging or ACC-only) or CAN is still initializing on cold boot, BCM reports 0, 15, or "-".
     */
    public static boolean isBcmSeatClimateReady(Map<String, String> readings) {
        if (readings == null) return false;
        String raw = readings.get("seat_state.driver");
        if (raw != null && !"-".equals(raw)) {
            try {
                int st = Integer.parseInt(raw.trim());
                if (st >= 2 && st <= 8) return true;
            } catch (NumberFormatException ignored) {}
        }
        String h = readings.get("seat_heat.driver");
        return h != null && ("0".equals(h) || "1".equals(h) || "2".equals(h) || "3".equals(h));
    }

    /** True when HSCAN reports active AC or DC charging (> 0). */
    public static boolean isChargingActive(Map<String, String> readings) {
        if (readings == null) return false;
        String raw = readings.get("charging");
        if (raw == null || raw.isEmpty() || "-".equals(raw)) return false;
        try {
            return Integer.parseInt(raw.trim()) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * True when the vehicle is in an active comfort session:
     *   1. EV_RDY drive ready (hv.ready == "1"), OR
     *   2. Powered Park cabin session (plugged-in Ignition ON / charging cabin session / Utility Mode / IG2 ON),
     *      where E-GMP EVs keep hv.ready == "0" while tethered to a charger, gated on:
     *        - head-unit display awake/interactive (blocks unoccupied overnight charging & remote Bluelink wakes),
     *        - gear in Park ("1"), and
     *        - BCM seat climate powered and ready (seat_state.driver in 2..8).
     */
    public static boolean isActiveClimateSession(Map<String, String> readings, boolean screenInteractive) {
        return isActiveClimateSession(readings, screenInteractive, -1);
    }

    /**
     * Ignition-aware overload for plugged-in / Utility Mode cabin sessions: triggers immediately
     * when {@code BCM.getIGNStatus == true} ({@code ignOn == 1}) or BCM seat climate is powered
     * ({@code seat_state.driver} in {@code 2..8}), while staying silent in ACC standby ({@code ignOn == 0}).
     */
    public static boolean isActiveClimateSession(
            Map<String, String> readings, boolean screenInteractive, int ignOn) {
        if (readings == null) return false;
        if ("1".equals(readings.get("hv.ready"))) return true;
        return screenInteractive
                && "1".equals(readings.get("gear.park"))
                && (isBcmSeatClimateReady(readings) || ignOn == 1);
    }

    /**
     * True when the car is plugged in and charging in Park with the display awake, but the driver
     * entered ACC mode (e.g. pressed Brake + Start while tethered on E-GMP) where BCM Ignition
     * ({@code IG2}) is off and seat/wheel heaters are unpowered until Start is pressed without brake.
     */
    public static boolean isPluggedInAccStandby(
            Map<String, String> readings, boolean screenInteractive, int ignOn) {
        if (readings == null) return false;
        if ("1".equals(readings.get("hv.ready"))) return false;
        return screenInteractive
                && "1".equals(readings.get("gear.park"))
                && isChargingActive(readings)
                && !isBcmSeatClimateReady(readings)
                && ignOn != 1;
    }

    /**
     * Verifies from live vehicle telemetry that a commanded climate action actually took effect in BCM/VR.
     * Rejects uninitialized cold-boot states ("-", "0", "15") as well as off ("2") so early commands
     * dropped while USM/VR/BCM finish booting are always retried.
     */
    public static boolean isActionConfirmed(Action act, Map<String, String> readings) {
        if (act == null || readings == null) return false;
        if ("wheel_heat".equals(act.command)) {
            return "on".equals(act.target)
                    ? "1".equals(readings.get("wheel_heat"))
                    : "0".equals(readings.get("wheel_heat"));
        }
        String raw = readings.get("seat_state." + act.target);
        int st = -1;
        if (raw != null && !"-".equals(raw)) {
            try {
                st = Integer.parseInt(raw.trim());
            } catch (NumberFormatException ignored) {}
        }
        if ("seat_heat".equals(act.command)) {
            if (st >= 6 && st <= 8) return true;
            String h = readings.get("seat_heat." + act.target);
            return "1".equals(h) || "2".equals(h) || "3".equals(h);
        }
        if ("seat_vent".equals(act.command)) {
            if (st >= 3 && st <= 5) return true;
            String v = readings.get("seat_vent." + act.target);
            return "1".equals(v) || "2".equals(v) || "3".equals(v);
        }
        return false;
    }

    /**
     * True only when a command refusal is permanent (owner denied permission or seat lacks hardware);
     * false for cold-boot/IPC transient errors ("vehicle service isn't running", "car's service failed",
     * "no answer", "code 75", "waiting for the owner") so they stay queued for retry.
     */
    public static boolean isPermanentCommandRefusal(String message) {
        if (message == null) return false;
        return message.contains("didn't allow")
                || message.contains("not declared in mod.json")
                || message.contains("no ventilation")
                || message.contains("unknown command");
    }

    /**
     * True only when CAN positively confirms EV Ready propulsion with gear out of Park (R/N/D),
     * preventing uninitialized cold-boot gear_p_status (0/-1) from prematurely aborting startup_notice.
     */
    public static boolean isConfirmedOutOfPark(Map<String, String> readings) {
        if (readings == null) return false;
        return "1".equals(readings.get("hv.ready")) && "0".equals(readings.get("gear.park"));
    }

    public static String levelName(int level) {
        return level >= 3 ? "high" : (level == 2 ? "medium" : "low");
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    /**
     * Tracks EV_RDY and plugged-in/Utility cabin sessions across cold boots, parked power-cycles,
     * QuickBoot power-offs, and QuickBoot STR suspends.
     */
    public static final class DriveTracker {
        private boolean appliedThisDrive;
        private int offTicks;
        private long lastSleepGap = Long.MIN_VALUE;
        private long lastWakeSeq = Long.MIN_VALUE;

        public synchronized void onSuspendCheck(long sleepGapMs) {
            if (lastSleepGap != Long.MIN_VALUE && sleepGapMs - lastSleepGap >= 3000L) {
                appliedThisDrive = false;
                offTicks = 0;
            }
            lastSleepGap = sleepGapMs;
        }

        public synchronized void onWakeSeqCheck(long wakeSeq) {
            if (lastWakeSeq != Long.MIN_VALUE && wakeSeq > lastWakeSeq) {
                appliedThisDrive = false;
                offTicks = 0;
            }
            lastWakeSeq = wakeSeq;
        }

        /** Resets session state on explicit QuickBoot vehicle power-off (QB_SLEEP / QB_POW_OFF). */
        public synchronized void onPowerOff() {
            appliedThisDrive = false;
            offTicks = 0;
        }

        /** Returns true when a new EV_RDY drive or powered cabin session needs auto-climate evaluation. */
        public synchronized boolean shouldTrigger(Map<String, String> readings, boolean screenInteractive) {
            return shouldTrigger(readings, screenInteractive, -1);
        }

        public synchronized boolean shouldTrigger(
                Map<String, String> readings, boolean screenInteractive, int ignOn) {
            if (isActiveClimateSession(readings, screenInteractive, ignOn)) {
                offTicks = 0;
                return !appliedThisDrive;
            }
            String hvReady = readings == null ? null : readings.get("hv.ready");
            if (("0".equals(hvReady) || ignOn == 0)
                    && !isBcmSeatClimateReady(readings)
                    && ignOn != 1) {
                offTicks++;
                if (offTicks >= 2) {
                    appliedThisDrive = false;
                }
            }
            return false;
        }

        /** Legacy single-signal helper for EV_RDY-only tests. */
        public synchronized boolean shouldTrigger(String hvReady) {
            Map<String, String> m = "1".equals(hvReady) || "0".equals(hvReady)
                    ? Collections.singletonMap("hv.ready", hvReady)
                    : Collections.<String, String>emptyMap();
            return shouldTrigger(m, false, -1);
        }

        public synchronized boolean isCompleted() {
            return appliedThisDrive;
        }

        public synchronized void markCompleted() {
            appliedThisDrive = true;
        }

        public synchronized void reset() {
            appliedThisDrive = false;
            offTicks = 0;
        }
    }

    /**
     * Tracks mid-session front-passenger boarding so if the vehicle started with an empty passenger
     * seat and a passenger boards later (either still in Park or during the drive), passenger seat
     * warming/ventilation activates automatically once without overriding manual changes.
     */
    public static final class PassengerBoardingTracker {
        private boolean sessionReady;
        private boolean appliedForCurrentOccupant;
        private int occupiedTicks;
        private int emptyTicks;

        public synchronized void onSessionClimateEvaluated(
                ComfortConfig cfg, OccupantSignalProbe.PassengerPresence presenceAtStart) {
            sessionReady = true;
            occupiedTicks = 0;
            emptyTicks = 0;
            boolean policyActive = cfg != null && !ComfortConfig.POLICY_OFF.equals(cfg.passengerPolicy);
            boolean alreadyHandled = policyActive
                    && (presenceAtStart == null
                            || !presenceAtStart.available
                            || presenceAtStart.occupied);
            appliedForCurrentOccupant = alreadyHandled;
        }

        public synchronized boolean shouldTriggerLateBoarding(
                ComfortConfig cfg,
                Map<String, String> readings,
                boolean screenInteractive,
                OccupantSignalProbe.PassengerPresence presence) {
            return shouldTriggerLateBoarding(cfg, readings, screenInteractive, -1, presence);
        }

        public synchronized boolean shouldTriggerLateBoarding(
                ComfortConfig cfg,
                Map<String, String> readings,
                boolean screenInteractive,
                int ignOn,
                OccupantSignalProbe.PassengerPresence presence) {
            if (!sessionReady
                    || cfg == null
                    || !cfg.climateEnabled
                    || !cfg.passengerAutoDetect
                    || ComfortConfig.POLICY_OFF.equals(cfg.passengerPolicy)
                    || !isActiveClimateSession(readings, screenInteractive, ignOn)
                    || presence == null
                    || !presence.available) {
                return false;
            }
            if (presence.occupied) {
                emptyTicks = 0;
                occupiedTicks++;
                if (appliedForCurrentOccupant) {
                    return false;
                }
                return presence.asstBelt == 1 || occupiedTicks >= 2;
            }
            occupiedTicks = 0;
            emptyTicks++;
            if (emptyTicks >= 6) {
                appliedForCurrentOccupant = false;
            }
            return false;
        }

        public synchronized void markBoardingHandled() {
            appliedForCurrentOccupant = true;
        }

        public synchronized void reset() {
            sessionReady = false;
            appliedForCurrentOccupant = false;
            occupiedTicks = 0;
            emptyTicks = 0;
        }
    }

    /**
     * Tracks startup safety notice dismissal across cold boots, SCREEN_OFF/SCREEN_ON, and QuickBoot STR suspends.
     */
    public static final class WelcomeTracker {
        private boolean pending = true;   // armed on initial cold boot
        private boolean screenOn = true;
        private long wakeAtMs;
        private long lastSleepGap = Long.MIN_VALUE;
        private long lastWakeSeq = Long.MIN_VALUE;

        public WelcomeTracker(long nowMs) {
            this.wakeAtMs = nowMs;
        }

        public synchronized void onScreenOff() {
            screenOn = false;
            pending = true;
        }

        public synchronized void onScreenOn(long nowMs) {
            if (!screenOn || pending) {
                wakeAtMs = nowMs;
            }
            screenOn = true;
            if (!pending) pending = true;
        }

        public synchronized void onSuspendCheck(long sleepGapMs, long nowMs) {
            if (lastSleepGap != Long.MIN_VALUE && sleepGapMs - lastSleepGap >= 3000L) {
                pending = true;
                screenOn = true;
                wakeAtMs = nowMs;
            }
            lastSleepGap = sleepGapMs;
        }

        public synchronized void onWakeSeqCheck(long wakeSeq, long nowMs) {
            if (lastWakeSeq != Long.MIN_VALUE && wakeSeq > lastWakeSeq) {
                pending = true;
                screenOn = true;
                wakeAtMs = nowMs;
            }
            lastWakeSeq = wakeSeq;
        }

        public synchronized boolean isPending() {
            return pending && screenOn;
        }

        public synchronized boolean readyToDismiss(long nowMs, int delayMs) {
            return pending && screenOn && (nowMs - wakeAtMs >= delayMs);
        }

        public synchronized void markDone() {
            pending = false;
        }
    }

    /** Default cold-boot deferral window (ms after sys.boot_completed=1) before starting ComfortService/climate. */
    public static final long COLD_BOOT_RUNWAY_MS = 12_000L;

    /**
     * True when the vehicle is in a silent parked wake or Mobis dark standby where neither the
     * startup notice dismisser nor background cabin/IPC polling should run:
     *  - Never true when BCM Ignition ({@code ignOn == 1}) is ON (active drive, Utility Mode, or plugged-in IG ON).
     *  - True when OpenLink's dark-standby marker ({@code /dev/.openlink-bt}) is present.
     *  - True when Mobis BlackScreen or AV-PowerOff ({@code mobisSleeping == 1}) is active.
     *  - True when OEM Micom power status is OFF (2), DELAY_MODE (6), or PREPARING_OFF (7) unless
     *    the display is confirmed awake ({@code mobisSleeping == 0}, e.g. driver entry welcome screen).
     */
    public static boolean isSilentParkedStandby(
            boolean openLinkBtMarker, int powerStatus, int mobisSleeping, int ignOn) {
        if (ignOn == 1) {
            return false;
        }
        if (openLinkBtMarker) {
            return true;
        }
        if (mobisSleeping == 1) {
            return true;
        }
        if (powerStatus == 2 || powerStatus == 6 || powerStatus == 7) {
            return mobisSleeping != 0;
        }
        return false;
    }

    /**
     * True once the post-boot_completed AA/CarPlay connection runway has completed, OR wireless
     * projection is already established, OR the owner opened the Comfort UI directly.
     */
    public static boolean isColdBootRunwayReady(
            long nowMs,
            long bootReadyAtMs,
            long runwayWaitMs,
            boolean projectionConnected,
            boolean uiForeground) {
        if (uiForeground || projectionConnected || runwayWaitMs <= 0L) {
            return true;
        }
        return nowMs - bootReadyAtMs >= runwayWaitMs;
    }

    /**
     * Parses Linux {@code /proc/net/tcp} or {@code /proc/net/tcp6} text for an ESTABLISHED ({@code 01})
     * Wireless Android Auto / Connectivity SoftAP ({@code 192.168.43.1} -> {@code 012BA8C0}) or
     * Wireless CarPlay ({@code 192.168.49.1} -> {@code 0131A8C0}) session.
     */
    public static boolean isProjectionTcpEstablished(String procNetTcp) {
        if (procNetTcp == null || procNetTcp.isEmpty()) {
            return false;
        }
        int len = procNetTcp.length();
        int lineStart = 0;
        while (lineStart < len) {
            int nl = procNetTcp.indexOf('\n', lineStart);
            int lineEnd = nl < 0 ? len : nl;
            if (isProjectionTcpLine(procNetTcp, lineStart, lineEnd)) {
                return true;
            }
            lineStart = lineEnd + 1;
        }
        return false;
    }

    private static boolean isProjectionTcpLine(String s, int start, int end) {
        // Columns: sl local_address rem_address st ...
        int p = skipSpaces(s, start, end);
        if (p >= end || s.charAt(p) == 's') return false;   // header "sl ..."
        p = skipNonSpaces(s, p, end);
        p = skipSpaces(s, p, end);
        int locStart = p;
        int locEnd = skipNonSpaces(s, locStart, end);
        p = skipSpaces(s, locEnd, end);
        p = skipNonSpaces(s, p, end);   // rem_address
        p = skipSpaces(s, p, end);
        int stStart = p;
        int stEnd = skipNonSpaces(s, stStart, end);
        if (stEnd - stStart != 2 || s.charAt(stStart) != '0' || s.charAt(stStart + 1) != '1') {
            return false;
        }
        int colon = -1;
        for (int i = locStart; i < locEnd; i++) {
            if (s.charAt(i) == ':') {
                colon = i;
                break;
            }
        }
        if (colon < locStart + 8) return false;
        String hostHex = s.substring(locStart, colon).toUpperCase(Locale.US);
        return hostHex.endsWith("012BA8C0") || hostHex.endsWith("0131A8C0");
    }

    private static int skipSpaces(String s, int i, int end) {
        while (i < end && s.charAt(i) <= ' ') i++;
        return i;
    }

    private static int skipNonSpaces(String s, int i, int end) {
        while (i < end && s.charAt(i) > ' ') i++;
        return i;
    }

    /** Checks live {@code /proc/net/tcp} and {@code /proc/net/tcp6} for an active projection TCP socket. */
    public static boolean isProjectionTcpConnected() {
        return isProjectionTcpEstablished(readSmallTextFile("/proc/net/tcp"))
                || isProjectionTcpEstablished(readSmallTextFile("/proc/net/tcp6"));
    }

    /**
     * Zero-IPC check against {@code /data/hmgapi/public/state.json} (world-readable since HMG-API 1.0.0):
     * returns {@code true} ONLY when {@code mods.<modId>.enabled} is explicitly {@code false}.
     */
    public static boolean isModDisabledInPublicState(String stateJson, String modId) {
        if (stateJson == null || stateJson.isEmpty() || modId == null || modId.isEmpty()) {
            return false;
        }
        int modsIdx = stateJson.indexOf("\"mods\"");
        if (modsIdx < 0) return false;
        String needle = "\"" + modId + "\"";
        int idIdx = stateJson.indexOf(needle, modsIdx + 6);
        if (idIdx < 0) return false;
        int braceStart = stateJson.indexOf('{', idIdx + needle.length());
        if (braceStart < 0) return false;
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        int braceEnd = -1;
        for (int i = braceStart; i < stateJson.length(); i++) {
            char c = stateJson.charAt(i);
            if (inStr) {
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    braceEnd = i;
                    break;
                }
            }
        }
        if (braceEnd < 0) return false;
        String modBlock = stateJson.substring(braceStart, braceEnd + 1);
        int enIdx = modBlock.indexOf("\"enabled\"");
        if (enIdx < 0) return false;
        int colon = modBlock.indexOf(':', enIdx + 9);
        if (colon < 0) return false;
        int v = skipSpaces(modBlock, colon + 1, modBlock.length());
        return modBlock.startsWith("false", v);
    }

    static String readSmallTextFile(String path) {
        java.io.File f = new java.io.File(path);
        if (!f.isFile()) return "";
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            byte[] buf = new byte[32768];
            int total = 0;
            while (total < buf.length) {
                int n = in.read(buf, total, buf.length - total);
                if (n <= 0) break;
                total += n;
            }
            return total <= 0 ? "" : new String(buf, 0, total, "UTF-8");
        } catch (Throwable ignored) {
            return "";
        } finally {
            if (in != null) {
                try { in.close(); } catch (Throwable ignored) {}
            }
        }
    }
}
