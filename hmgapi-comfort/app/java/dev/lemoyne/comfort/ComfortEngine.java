package dev.lemoyne.comfort;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import hmgapi.client.HmgApi;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Background coordinator for Comfort & Startup:
 * - Event-driven startup notice dismissal on cold boot and QuickBoot wake
 * - Multi-tier cold/warm seat & steering wheel climate on EV Ready and plugged-in/Utility cabin sessions
 */
public final class ComfortEngine {
    private static final String TAG = "HmgComfort";
    private static final String MOD_ID = "hmgapi-comfort";
    private static final String HW_PREFS = "comfort_hw_caps";
    private static final File STATE_FILE = new File("/data/hmgapi/public/state.json");
    private static final File OPENLINK_BT_FILE = new File("/dev/.openlink-bt");
    private static final long COLD_BOOT_UPTIME_GUARD_MS = 26_000L;
    static final String[] READ_KEYS = {
        "hv.ready", "gear.park", "charging", "outside_temp_f", "outside_temp", "wheel_heat",
        "seat_heat.driver", "seat_heat.passenger", "seat_heat.rear_left", "seat_heat.rear_right",
        "seat_vent.driver", "seat_vent.passenger",
        "seat_state.driver", "seat_state.passenger", "seat_state.rear_left", "seat_state.rear_right"
    };

    public interface Listener {
        void onStateUpdated(Snapshot snapshot);
    }

    public static final class Snapshot {
        public final ComfortConfig config;
        public final Map<String, String> readings;
        public final OccupantSignalProbe.PassengerPresence passengerPresence;
        public final OccupantSignalProbe.CabinState cabinState;
        public final OccupantSignalProbe.SeatCapabilities seatCaps;
        public final String lastClimateStatus;
        public final String lastWelcomeStatus;
        public final boolean connected;
        public final boolean metricUnits;
        public final long configSeq;

        Snapshot(
                ComfortConfig config,
                Map<String, String> readings,
                OccupantSignalProbe.PassengerPresence passengerPresence,
                OccupantSignalProbe.CabinState cabinState,
                OccupantSignalProbe.SeatCapabilities seatCaps,
                String lastClimateStatus,
                String lastWelcomeStatus,
                boolean connected) {
            this(config, readings, passengerPresence, cabinState, seatCaps,
                    lastClimateStatus, lastWelcomeStatus, connected, false, 0L);
        }

        Snapshot(
                ComfortConfig config,
                Map<String, String> readings,
                OccupantSignalProbe.PassengerPresence passengerPresence,
                OccupantSignalProbe.CabinState cabinState,
                OccupantSignalProbe.SeatCapabilities seatCaps,
                String lastClimateStatus,
                String lastWelcomeStatus,
                boolean connected,
                boolean metricUnits) {
            this(config, readings, passengerPresence, cabinState, seatCaps,
                    lastClimateStatus, lastWelcomeStatus, connected, metricUnits, 0L);
        }

        Snapshot(
                ComfortConfig config,
                Map<String, String> readings,
                OccupantSignalProbe.PassengerPresence passengerPresence,
                OccupantSignalProbe.CabinState cabinState,
                OccupantSignalProbe.SeatCapabilities seatCaps,
                String lastClimateStatus,
                String lastWelcomeStatus,
                boolean connected,
                boolean metricUnits,
                long configSeq) {
            this.config = config;
            this.readings = readings;
            this.passengerPresence = passengerPresence == null
                    ? OccupantSignalProbe.PassengerPresence.UNAVAILABLE
                    : passengerPresence;
            this.cabinState = cabinState == null
                    ? OccupantSignalProbe.CabinState.UNAVAILABLE
                    : cabinState;
            this.seatCaps = seatCaps == null
                    ? OccupantSignalProbe.SeatCapabilities.UNKNOWN
                    : seatCaps;
            this.lastClimateStatus = lastClimateStatus;
            this.lastWelcomeStatus = lastWelcomeStatus;
            this.connected = connected;
            this.metricUnits = metricUnits;
            this.configSeq = configSeq;
        }
    }

    private static ComfortEngine instance;

    public static synchronized ComfortEngine getInstance(Context ctx) {
        if (instance == null) {
            instance = new ComfortEngine(ctx.getApplicationContext());
        }
        return instance;
    }

    /**
     * Reads vehicle telemetry in <= 8-key batches so hmgapid's wire MAX_ARGS (16) is never exceeded,
     * falling back per-key if an older HMG-API runtime has not declared one of the newer readings.
     */
    static Map<String, String> readVehicleReadings(HmgApi api, String[] keys) throws IOException {
        Map<String, String> merged = new LinkedHashMap<String, String>();
        List<String[]> batches = ComfortPolicy.chunkVehicleKeys(keys, ComfortPolicy.MAX_VEHICLE_BATCH_KEYS);
        for (int i = 0; i < batches.size(); i++) {
            String[] batch = batches.get(i);
            try {
                Map<String, String> part = api.vehicle(batch);
                if (part != null) {
                    merged.putAll(part);
                }
            } catch (HmgApi.HmgApiException e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (msg.contains("not declared")) {
                    for (int k = 0; k < batch.length; k++) {
                        String key = batch[k];
                        try {
                            Map<String, String> single = api.vehicle(key);
                            String val = single == null ? null : single.get(key);
                            merged.put(key, val == null ? "-" : val);
                        } catch (HmgApi.HmgApiException e2) {
                            String m2 = e2.getMessage() == null ? "" : e2.getMessage();
                            if (m2.contains("not declared")) {
                                merged.put(key, "-");
                            } else {
                                throw e2;
                            }
                        }
                    }
                } else {
                    throw e;
                }
            }
        }
        return merged;
    }

    /**
     * Returns true ONLY when hmgapid definitively answers code 13 ("not allowed" / mod disabled)
     * across two checks, preventing transient cold-boot conditions — hmgapid not up yet, socket
     * timeout, MAX_WORKERS busy (code 75), or brief PackageManager list rewrites — from stopping
     * or skipping ComfortService.
     */
    public static boolean isModExplicitlyDisabled(HmgApi api) {
        if (api == null) return false;
        try {
            if (api.call("whoami").code != 13) return false;
            sleepQuietly(300);
            return api.call("whoami").code == 13;
        } catch (IOException e) {
            return false;
        }
    }

    private final Context appContext;
    private final List<Listener> listeners = new ArrayList<Listener>();
    private final ComfortPolicy.DriveTracker driveTracker = new ComfortPolicy.DriveTracker();
    private final ComfortPolicy.PassengerBoardingTracker boardingTracker =
            new ComfortPolicy.PassengerBoardingTracker();
    private final ComfortPolicy.WelcomeTracker welcomeTracker =
            new ComfortPolicy.WelcomeTracker(SystemClock.elapsedRealtime());
    private final OccupantSignalProbe.LoopbackClient occupancyClient =
            new OccupantSignalProbe.LoopbackClient(OccupantSignalProbe.DEFAULT_PORT, null);

    private volatile ComfortConfig config = ComfortConfig.defaults();
    private volatile Map<String, String> lastReadings = new LinkedHashMap<String, String>();
    private volatile OccupantSignalProbe.PassengerPresence lastPassengerPresence =
            OccupantSignalProbe.PassengerPresence.UNAVAILABLE;
    private volatile OccupantSignalProbe.CabinState lastCabinState =
            OccupantSignalProbe.CabinState.UNAVAILABLE;
    private volatile OccupantSignalProbe.SeatCapabilities seatCaps =
            OccupantSignalProbe.SeatCapabilities.UNKNOWN;
    private volatile int tempUnit = OccupantSignalProbe.TEMP_UNIT_UNKNOWN;
    private volatile String lastClimateStatus = "Waiting for vehicle start";
    private volatile String lastWelcomeStatus = "Armed for startup";
    private volatile boolean connected;
    private volatile boolean running;
    private volatile boolean reloadSettings = true;
    private volatile boolean forceClimateRun;
    private volatile boolean forceWelcomeRun;
    private volatile boolean mobisAwake = true;
    private volatile boolean uiForeground;
    private final Object wakeLock = new Object();
    private final ComfortPolicy.SettingsSyncGuard syncGuard =
            new ComfortPolicy.SettingsSyncGuard();
    private boolean wakeSignaled;
    private boolean lastPublicStateDisabled;
    private Thread worker;
    private Thread saveWorker;
    private int welcomeAttempts;
    private int outOfParkWelcomeChecks;
    private int tempTimeoutRetries;
    private long lastEngineSleepGap = Long.MIN_VALUE;
    private long lastEngineWakeSeq = Long.MIN_VALUE;
    private int lastEngineMobisSleeping = -1;
    private boolean showingAccStandbyNote;

    private ComfortEngine(Context appContext) {
        this.appContext = appContext;
        this.seatCaps = loadCachedSeatCapabilities(appContext);
        this.tempUnit = loadCachedTempUnit(appContext);
        refreshFromPublicStateFile();
        this.reloadSettings = true;
    }

    private static OccupantSignalProbe.SeatCapabilities loadCachedSeatCapabilities(Context ctx) {
        if (ctx == null) return OccupantSignalProbe.SeatCapabilities.UNKNOWN;
        try {
            SharedPreferences sp = ctx.getSharedPreferences(HW_PREFS, Context.MODE_PRIVATE);
            return new OccupantSignalProbe.SeatCapabilities(
                    sp.getInt("front_vent", -1),
                    sp.getInt("rear_left_heat", -1),
                    sp.getInt("rear_right_heat", -1));
        } catch (Throwable ignored) {
            return OccupantSignalProbe.SeatCapabilities.UNKNOWN;
        }
    }

    private static int loadCachedTempUnit(Context ctx) {
        if (ctx == null) return OccupantSignalProbe.TEMP_UNIT_UNKNOWN;
        try {
            SharedPreferences sp = ctx.getSharedPreferences(HW_PREFS, Context.MODE_PRIVATE);
            int u = sp.getInt("temp_unit", OccupantSignalProbe.TEMP_UNIT_UNKNOWN);
            return (u == OccupantSignalProbe.TEMP_UNIT_C || u == OccupantSignalProbe.TEMP_UNIT_F)
                    ? u
                    : OccupantSignalProbe.TEMP_UNIT_UNKNOWN;
        } catch (Throwable ignored) {
            return OccupantSignalProbe.TEMP_UNIT_UNKNOWN;
        }
    }

    private boolean isMetricUnits() {
        return tempUnit == OccupantSignalProbe.TEMP_UNIT_C;
    }

    private void updateSeatCapabilities(OccupantSignalProbe.SeatCapabilities live) {
        if (live == null) return;
        OccupantSignalProbe.SeatCapabilities prev = this.seatCaps;
        OccupantSignalProbe.SeatCapabilities next = prev.mergeKnown(live);
        if (!next.equals(prev)) {
            this.seatCaps = next;
            try {
                SharedPreferences sp = appContext.getSharedPreferences(HW_PREFS, Context.MODE_PRIVATE);
                sp.edit()
                        .putInt("front_vent", next.frontVent)
                        .putInt("rear_left_heat", next.rearLeftHeat)
                        .putInt("rear_right_heat", next.rearRightHeat)
                        .apply();
            } catch (Throwable ignored) {
            }
            Log.i(TAG, "[hmgapi-comfort] detected seat hardware capabilities: " + next.summary());
        }
    }

    private void updateTempUnit(int liveUnit, Map<String, String> rawReadings) {
        int next = OccupantSignalProbe.TEMP_UNIT_UNKNOWN;
        if (liveUnit == OccupantSignalProbe.TEMP_UNIT_C || liveUnit == OccupantSignalProbe.TEMP_UNIT_F) {
            next = liveUnit;
        } else if (this.tempUnit == OccupantSignalProbe.TEMP_UNIT_UNKNOWN && rawReadings != null) {
            float f = ComfortPolicy.parseAmbientF(rawReadings.get("outside_temp_f"));
            float c = ComfortPolicy.parseAmbientC(rawReadings.get("outside_temp"));
            if (Float.isNaN(f) && !Float.isNaN(c)) {
                next = OccupantSignalProbe.TEMP_UNIT_C;
            }
        }
        if ((next == OccupantSignalProbe.TEMP_UNIT_C || next == OccupantSignalProbe.TEMP_UNIT_F)
                && next != this.tempUnit) {
            this.tempUnit = next;
            try {
                SharedPreferences sp = appContext.getSharedPreferences(HW_PREFS, Context.MODE_PRIVATE);
                sp.edit().putInt("temp_unit", next).apply();
            } catch (Throwable ignored) {
            }
            Log.i(TAG, "[hmgapi-comfort] detected vehicle temperature unit: "
                    + (next == OccupantSignalProbe.TEMP_UNIT_C ? "C" : "F"));
        }
    }

    public synchronized void ensureStarted() {
        if (running && worker != null && worker.isAlive()) return;
        running = true;
        worker = new Thread("ComfortEngineLoop") {
            @Override
            public void run() {
                loop();
            }
        };
        worker.setDaemon(true);
        worker.start();
    }

    public synchronized void stop() {
        flushPendingSettingsSave();
        running = false;
        wakeWorker();
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }

    public void addListener(Listener l) {
        synchronized (listeners) {
            if (!listeners.contains(l)) listeners.add(l);
        }
        l.onStateUpdated(snapshot());
    }

    public void removeListener(Listener l) {
        synchronized (listeners) {
            listeners.remove(l);
        }
    }

    public long getConfigSeq() {
        return syncGuard.getConfigSeq();
    }

    public Snapshot snapshot() {
        ComfortConfig cfg;
        long seq;
        synchronized (syncGuard) {
            cfg = config;
            seq = syncGuard.getConfigSeq();
        }
        return new Snapshot(cfg, new LinkedHashMap<String, String>(lastReadings),
                lastPassengerPresence, lastCabinState, seatCaps,
                lastClimateStatus, lastWelcomeStatus, connected, isMetricUnits(), seq);
    }

    /**
     * Updates in-memory configuration immediately on UI edit and schedules a single
     * debounced {@code setting-set} write so rapid stepper taps never snap back or
     * spawn concurrent {@code Engine publish-state} Dalvik forks.
     */
    public void applyUserConfig(ComfortConfig next) {
        if (next == null) return;
        synchronized (syncGuard) {
            this.config = next;
            this.reloadSettings = false;
            syncGuard.onLocalEdit(next, SystemClock.elapsedRealtime());
            ensureSaveWorkerLocked();
            syncGuard.notifyAll();
        }
    }

    public void flushPendingSettingsSave() {
        synchronized (syncGuard) {
            if (!syncGuard.hasPendingOrInFlightSave()) {
                return;
            }
            syncGuard.requestImmediateFlush(SystemClock.elapsedRealtime());
            ensureSaveWorkerLocked();
            syncGuard.notifyAll();
        }
    }

    private void ensureSaveWorkerLocked() {
        if (saveWorker != null && saveWorker.isAlive()) {
            return;
        }
        saveWorker = new Thread("ComfortSettingsSaver") {
            @Override
            public void run() {
                runSaveLoop();
            }
        };
        saveWorker.setDaemon(true);
        saveWorker.start();
    }

    private void runSaveLoop() {
        while (true) {
            ComfortConfig toWrite;
            synchronized (syncGuard) {
                long waitMs = syncGuard.msUntilNextSave(SystemClock.elapsedRealtime());
                if (waitMs < 0L) {
                    saveWorker = null;
                    return;
                }
                if (waitMs > 0L) {
                    try {
                        syncGuard.wait(waitMs);
                    } catch (InterruptedException ignored) {
                    }
                    continue;
                }
                toWrite = syncGuard.takeDueSave(SystemClock.elapsedRealtime());
                if (toWrite == null) {
                    continue;
                }
            }

            try {
                JSONObject payload = toJson(toWrite);
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        HmgApi.connect().timeout(3000).saveSettings(payload);
                        break;
                    } catch (Throwable err) {
                        if (attempt == 0 && syncGuard.msUntilNextSave(SystemClock.elapsedRealtime()) < 0L) {
                            sleepQuietly(300);
                        } else {
                            Log.w(TAG, "[hmgapi-comfort] settings save failed: " + err);
                        }
                    }
                }
            } catch (Throwable err) {
                Log.w(TAG, "[hmgapi-comfort] settings encode failed: " + err);
            } finally {
                synchronized (syncGuard) {
                    syncGuard.onSaveFinished(SystemClock.elapsedRealtime());
                }
            }
        }
    }

    public void setUiForeground(boolean foreground) {
        this.uiForeground = foreground;
        if (foreground) {
            mobisAwake = true;
            wakeWorker();
        }
    }

    public void onScreenOff() {
        mobisAwake = false;
        welcomeTracker.onScreenOff();
        welcomeAttempts = 0;
        outOfParkWelcomeChecks = 0;
        wakeWorker();
    }

    public void onScreenOn() {
        mobisAwake = true;
        welcomeTracker.onScreenOn(SystemClock.elapsedRealtime());
        welcomeAttempts = 0;
        outOfParkWelcomeChecks = 0;
        wakeWorker();
    }

    public void onVehiclePowerOff() {
        mobisAwake = false;
        driveTracker.onPowerOff();
        boardingTracker.reset();
        welcomeTracker.onScreenOff();
        welcomeAttempts = 0;
        outOfParkWelcomeChecks = 0;
        tempTimeoutRetries = 0;
        showingAccStandbyNote = false;
        wakeWorker();
    }

    public void onSupervisorPing() {
        wakeWorker();
    }

    public void requestSettingsReload() {
        if (syncGuard.hasPendingOrInFlightSave()) {
            return;
        }
        reloadSettings = true;
        wakeWorker();
    }

    public void onExternalStateChanged() {
        if (syncGuard.isSettlingLocalSave(SystemClock.elapsedRealtime())) {
            return;
        }
        reloadSettings = true;
        wakeWorker();
    }

    public void triggerClimateNow() {
        forceClimateRun = true;
        wakeWorker();
    }

    public void triggerWelcomeNow() {
        forceWelcomeRun = true;
        wakeWorker();
    }

    private boolean isDisplayOn() {
        try {
            PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isScreenOn();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private boolean isScreenInteractive(OccupantSignalProbe.CabinState cabin) {
        if (uiForeground) {
            return true;
        }
        if (cabin != null) {
            if (cabin.avAwake == 1) {
                return isDisplayOn();
            }
            if (cabin.avAwake == 0) {
                return false;
            }
        }
        return mobisAwake && isDisplayOn();
    }

    private OccupantSignalProbe.CabinState readMergedTelemetry(HmgApi api) throws IOException {
        Map<String, String> raw = readVehicleReadings(api, READ_KEYS);
        OccupantSignalProbe.CabinState cabin = occupancyClient.queryCabinState();
        if (cabin == null) {
            cabin = OccupantSignalProbe.CabinState.UNAVAILABLE;
        }
        updateSeatCapabilities(cabin.seatCaps);
        updateTempUnit(cabin.tempUnit, raw);
        Map<String, String> merged = ComfortPolicy.mergeCabinFallback(raw, cabin);
        lastReadings = merged;
        lastCabinState = cabin;
        lastPassengerPresence = cabin.passenger;
        return cabin;
    }

    private void wakeWorker() {
        synchronized (wakeLock) {
            wakeSignaled = true;
            wakeLock.notifyAll();
        }
    }

    private void waitQuietly(long ms) {
        if (ms <= 0L) return;
        synchronized (wakeLock) {
            if (!wakeSignaled && running) {
                try {
                    wakeLock.wait(ms);
                } catch (InterruptedException ignored) {
                }
            }
            wakeSignaled = false;
        }
    }

    private boolean refreshFromPublicStateFile() {
        long mtime = STATE_FILE.isFile() ? STATE_FILE.lastModified() : 0L;
        if (mtime <= 0L) return false;
        if (!syncGuard.hasPublicStateMtimeChanged(mtime)) {
            return lastPublicStateDisabled;
        }
        long seqBefore = syncGuard.getConfigSeq();
        String raw = ComfortPolicy.readSmallTextFile(STATE_FILE.getAbsolutePath());
        if (raw.isEmpty()) return false;
        syncGuard.recordPublicStateMtime(mtime);
        lastPublicStateDisabled = ComfortPolicy.isModDisabledInPublicState(raw, MOD_ID);
        if (lastPublicStateDisabled) {
            return true;
        }
        if (!syncGuard.canApplyPublicStateSettings(SystemClock.elapsedRealtime(), seqBefore)) {
            return false;
        }
        try {
            JSONObject root = new JSONObject(raw);
            JSONObject mods = root.optJSONObject("mods");
            JSONObject mod = mods == null ? null : mods.optJSONObject(MOD_ID);
            JSONObject s = mod == null ? null : mod.optJSONObject("settings");
            if (s != null) {
                ComfortConfig parsed = parseConfig(s);
                synchronized (syncGuard) {
                    if (syncGuard.canApplyPublicStateSettings(SystemClock.elapsedRealtime(), seqBefore)) {
                        config = parsed;
                        syncGuard.onExternalSettingsApplied();
                        reloadSettings = false;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void loop() {
        Log.i(TAG, "[hmgapi-comfort] background coordinator started");
        boolean firstColdTick = true;
        while (running) {
            try {
                // Dormant standby: zero @hmgapi IPC and zero OEM Binder queries while screen is off / silent wake
                boolean darkStandby = !uiForeground
                        && !forceClimateRun
                        && !forceWelcomeRun
                        && !"1".equals(lastReadings.get("hv.ready"))
                        && (OPENLINK_BT_FILE.isFile() || (!mobisAwake && !isDisplayOn()));
                if (darkStandby) {
                    waitQuietly(15_000L);
                    continue;
                }

                // Honor the cold-boot AA/CarPlay connection runway unless the Comfort UI is open
                if (!uiForeground
                        && !forceClimateRun
                        && !forceWelcomeRun
                        && SystemClock.elapsedRealtime() < COLD_BOOT_UPTIME_GUARD_MS
                        && !ComfortPolicy.isProjectionTcpConnected()) {
                    waitQuietly(2000L);
                    continue;
                }

                long now = SystemClock.elapsedRealtime();
                long sleepGap = now - SystemClock.uptimeMillis();
                long wakeSeq = WelcomeDaemon.readWakeSeq();
                if ((lastEngineSleepGap != Long.MIN_VALUE && sleepGap - lastEngineSleepGap >= 3000L)
                        || (lastEngineWakeSeq != Long.MIN_VALUE && wakeSeq > lastEngineWakeSeq)) {
                    mobisAwake = isDisplayOn();
                    occupancyClient.notifyWake();
                    boardingTracker.reset();
                    welcomeAttempts = 0;
                    outOfParkWelcomeChecks = 0;
                    tempTimeoutRetries = 0;
                    showingAccStandbyNote = false;
                }
                lastEngineSleepGap = sleepGap;
                lastEngineWakeSeq = wakeSeq;

                driveTracker.onSuspendCheck(sleepGap);
                driveTracker.onWakeSeqCheck(wakeSeq);
                welcomeTracker.onSuspendCheck(sleepGap, now);
                welcomeTracker.onWakeSeqCheck(wakeSeq, now);

                if (refreshFromPublicStateFile()) {
                    connected = false;
                    notifyListeners();
                    waitQuietly(5000L);
                    continue;
                }

                if (firstColdTick) {
                    firstColdTick = false;
                    if (!forceWelcomeRun && occupancyClient.pingDaemon()) {
                        // WelcomeDaemon already owns cold-boot dismissal; skip duplicate @hmgapi command
                        welcomeTracker.markDone();
                        lastWelcomeStatus = stamp("Dismissed on start (fast-path)");
                    }
                }

                if (seatCaps.frontVent == -1 || seatCaps.rearLeftHeat == -1 || seatCaps.rearRightHeat == -1
                        || tempUnit == OccupantSignalProbe.TEMP_UNIT_UNKNOWN) {
                    OccupantSignalProbe.CabinState earlyCabin = occupancyClient.queryCabinState();
                    if (earlyCabin != null) {
                        updateSeatCapabilities(earlyCabin.seatCaps);
                        updateTempUnit(earlyCabin.tempUnit, null);
                    }
                }

                HmgApi api = HmgApi.connect().timeout(5000);
                if (!connected) {
                    HmgApi.Result who;
                    try {
                        who = api.call("whoami");
                    } catch (IOException e) {
                        connected = false;
                        notifyListeners();
                        waitQuietly(1500L);
                        continue;
                    }
                    String whoText = who.text == null ? "" : who.text.trim();
                    if (who.code == 13) {
                        connected = false;
                        notifyListeners();
                        waitQuietly(5000L);
                        continue;
                    }
                    if (who.code != 0 || "root".equals(whoText) || whoText.isEmpty()) {
                        // Transient hmgapid MAX_WORKERS busy (code 75): back off briefly without dropping state
                        waitQuietly(800L);
                        continue;
                    }
                    connected = true;
                }

                if (reloadSettings) {
                    if (syncGuard.hasPendingOrInFlightSave()) {
                        reloadSettings = false;
                    } else {
                        long seqBefore = syncGuard.getConfigSeq();
                        try {
                            ComfortConfig parsed = parseConfig(api.settings());
                            synchronized (syncGuard) {
                                if (syncGuard.canApplyDirectSettings(seqBefore)) {
                                    config = parsed;
                                    syncGuard.onExternalSettingsApplied();
                                }
                                reloadSettings = false;
                            }
                        } catch (IOException e) {
                            Log.w(TAG, "[hmgapi-comfort] settings read retry later: " + e);
                        }
                    }
                }

                // 1. Startup safety notice fallback dismissal (give WelcomeDaemon 600 ms head start on wake)
                int effectiveWelcomeDelay = Math.max(config.welcomeDelayMs, 600);
                if (forceWelcomeRun || (config.welcomeDismiss
                        && welcomeTracker.readyToDismiss(SystemClock.elapsedRealtime(), effectiveWelcomeDelay))) {
                    boolean manual = forceWelcomeRun;
                    forceWelcomeRun = false;
                    handleWelcome(api, manual);
                }

                OccupantSignalProbe.CabinState cabin;
                try {
                    cabin = readMergedTelemetry(api);
                } catch (IOException e) {
                    connected = false;
                    throw e;
                }
                Map<String, String> v = lastReadings;

                if (cabin.mobisSleeping == 1) {
                    mobisAwake = false;
                    if (!"1".equals(v.get("hv.ready"))) {
                        driveTracker.onPowerOff();
                        boardingTracker.reset();
                        welcomeTracker.onScreenOff();
                        welcomeAttempts = 0;
                        outOfParkWelcomeChecks = 0;
                        tempTimeoutRetries = 0;
                        showingAccStandbyNote = false;
                    }
                    lastEngineMobisSleeping = 1;
                } else if (cabin.mobisSleeping == 0) {
                    mobisAwake = true;
                    if (lastEngineMobisSleeping == 1) {
                        if (!"1".equals(v.get("hv.ready"))) {
                            driveTracker.onPowerOff();
                            boardingTracker.reset();
                            tempTimeoutRetries = 0;
                            showingAccStandbyNote = false;
                        }
                        welcomeTracker.onScreenOn(SystemClock.elapsedRealtime());
                        welcomeAttempts = 0;
                        outOfParkWelcomeChecks = 0;
                    }
                    lastEngineMobisSleeping = 0;
                }

                OccupantSignalProbe.PassengerPresence presence = cabin.passenger;

                // 2. Cold/Warm auto-climate on EV Ready or plugged-in/Utility cabin session (or manual test button)
                boolean manualClimate = forceClimateRun;
                forceClimateRun = false;
                boolean interactive = isScreenInteractive(cabin);
                boolean startSession = driveTracker.shouldTrigger(v, interactive, cabin.ignOn);
                if (!driveTracker.isCompleted() && !startSession) {
                    boardingTracker.reset();
                }

                if (manualClimate || (config.climateEnabled && startSession)) {
                    showingAccStandbyNote = false;
                    runClimateSequence(api, manualClimate);
                } else if (boardingTracker.shouldTriggerLateBoarding(
                        config, v, interactive, cabin.ignOn, presence)) {
                    runPassengerBoardingSequence(api, v, presence);
                } else if (config.climateEnabled
                        && !driveTracker.isCompleted()
                        && ComfortPolicy.isPluggedInAccStandby(v, interactive, cabin.ignOn)) {
                    String ambDisp = ComfortPolicy.formatAmbientDisplay(v, isMetricUnits());
                    String ambTag = ambDisp.isEmpty() ? "" : (" (" + ambDisp + ")");
                    String nextMsg = "Charging (ACC)" + ambTag
                            + " — press Start (no brake) for Ignition ON & heaters";
                    if (!showingAccStandbyNote || !lastClimateStatus.endsWith(nextMsg)) {
                        lastClimateStatus = stamp(nextMsg);
                        showingAccStandbyNote = true;
                    }
                } else if (showingAccStandbyNote && !driveTracker.isCompleted()) {
                    showingAccStandbyNote = false;
                    lastClimateStatus = "Waiting for vehicle start";
                }

                notifyListeners();
                long pollSleepMs = (config.welcomeDismiss && welcomeTracker.isPending())
                        ? 400L
                        : ((uiForeground || showingAccStandbyNote)
                                ? 1200L
                                : (driveTracker.isCompleted() ? 3500L : 2500L));
                waitQuietly(pollSleepMs);
            } catch (Throwable t) {
                Log.w(TAG, "[hmgapi-comfort] loop error: " + t);
                notifyListeners();
                waitQuietly(1500L);
            }
        }
        Log.i(TAG, "[hmgapi-comfort] background coordinator stopped");
    }

    private void handleWelcome(HmgApi api, boolean manual) {
        try {
            String res = api.command("startup_notice", "dismiss");
            welcomeTracker.markDone();
            welcomeAttempts = 0;
            outOfParkWelcomeChecks = 0;
            lastWelcomeStatus = stamp("Dismissed (" + res + ")");
            Log.i(TAG, "[hmgapi-comfort] startup_notice dismiss -> " + res);
        } catch (HmgApi.HmgApiException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("once per start")) {
                welcomeTracker.markDone();
                welcomeAttempts = 0;
                outOfParkWelcomeChecks = 0;
                lastWelcomeStatus = stamp("Dismissed on start (fast-path)");
                Log.i(TAG, "[hmgapi-comfort] startup_notice -> already dismissed by WelcomeDaemon");
            } else if ((msg.contains("isn't in P") || msg.contains("not in P")) && !manual) {
                Map<String, String> pCheck = lastReadings;
                try {
                    pCheck = api.vehicle("gear.park", "hv.ready");
                } catch (Throwable ignored) {}
                if (ComfortPolicy.isConfirmedOutOfPark(pCheck)) {
                    outOfParkWelcomeChecks++;
                    if (outOfParkWelcomeChecks >= 3) {
                        welcomeTracker.markDone();
                        welcomeAttempts = 0;
                        outOfParkWelcomeChecks = 0;
                        lastWelcomeStatus = stamp("Skipped (shifted out of Park)");
                        Log.i(TAG, "[hmgapi-comfort] startup_notice -> " + msg);
                        return;
                    }
                } else {
                    outOfParkWelcomeChecks = 0;
                }
                welcomeAttempts++;
                if (welcomeAttempts >= 40) {
                    welcomeTracker.markDone();
                    welcomeAttempts = 0;
                    outOfParkWelcomeChecks = 0;
                    lastWelcomeStatus = stamp("Gave up waiting for Park: " + msg);
                } else {
                    lastWelcomeStatus = stamp("Waiting for Park signal (" + welcomeAttempts + ")...");
                }
            } else if (msg.contains("hasn't allowed") || msg.contains("didn't allow")) {
                welcomeTracker.markDone();
                welcomeAttempts = 0;
                outOfParkWelcomeChecks = 0;
                lastWelcomeStatus = stamp(msg);
                Log.i(TAG, "[hmgapi-comfort] startup_notice -> " + msg);
            } else {
                welcomeAttempts++;
                if (welcomeAttempts >= 40) {
                    welcomeTracker.markDone();
                    welcomeAttempts = 0;
                    outOfParkWelcomeChecks = 0;
                    lastWelcomeStatus = stamp("Gave up: " + msg);
                } else {
                    lastWelcomeStatus = stamp("Retrying (" + welcomeAttempts + "): " + msg);
                }
            }
        } catch (IOException e) {
            welcomeAttempts++;
            if (welcomeAttempts >= 20) {
                welcomeTracker.markDone();
                welcomeAttempts = 0;
                outOfParkWelcomeChecks = 0;
            }
            lastWelcomeStatus = stamp("Error: " + e.getMessage());
        }
    }

    private void runClimateSequence(HmgApi api, boolean manual) throws IOException {
        if (!manual) {
            if (config.welcomeDismiss
                    && welcomeTracker.readyToDismiss(SystemClock.elapsedRealtime(), config.welcomeDelayMs)) {
                handleWelcome(api, false);
            }
            sleepQuietly(1500);   // let BCM/USM/VR settle after EV Ready / cabin power transition
        }
        // Wait up to 60 s for valid post-wake outside temperature (°C or °F) and BCM seat climate readiness
        // (bounded to 5 s when manually tapped in ACC standby so the UI responds promptly)
        Map<String, String> v = null;
        OccupantSignalProbe.CabinState cabin = lastCabinState;
        boolean hasTemp = false;
        long tempValidSince = 0L;
        int powerLostTicks = 0;
        boolean initialAccManual = manual
                && cabin.ignOn == 0
                && !"1".equals(lastReadings.get("hv.ready"))
                && !ComfortPolicy.isBcmSeatClimateReady(lastReadings);
        long deadline = SystemClock.elapsedRealtime() + (initialAccManual ? 5000L : 60000L);
        while (running && SystemClock.elapsedRealtime() < deadline) {
            if (config.welcomeDismiss
                    && welcomeTracker.readyToDismiss(SystemClock.elapsedRealtime(), config.welcomeDelayMs)) {
                handleWelcome(api, false);
            }
            try {
                cabin = readMergedTelemetry(api);
                v = lastReadings;
            } catch (IOException e) {
                Log.w(TAG, "[hmgapi-comfort] vehicle read retry during climate wait: " + e);
                sleepQuietly(1200);
                continue;
            }
            if (!manual) {
                if ("0".equals(v.get("hv.ready"))
                        && !ComfortPolicy.isBcmSeatClimateReady(v)
                        && cabin.ignOn != 1) {
                    powerLostTicks++;
                    if (powerLostTicks >= 2) {
                        lastClimateStatus = stamp("Aborted (vehicle turned off)");
                        return;
                    }
                } else {
                    powerLostTicks = 0;
                }
            }
            hasTemp = !Float.isNaN(ComfortPolicy.readAmbientF(v));
            boolean inAccStandby = !"1".equals(v.get("hv.ready"))
                    && !ComfortPolicy.isBcmSeatClimateReady(v)
                    && cabin.ignOn == 0;
            if (manual && inAccStandby && (hasTemp || cabin.datcSentinel)) {
                String ambDisp = ComfortPolicy.formatAmbientDisplay(v, isMetricUnits());
                String ambTag = ambDisp.isEmpty() ? "" : (" (" + ambDisp + ")");
                lastClimateStatus = stamp("Ignition in ACC" + ambTag
                        + " — press Start (no brake) for Ignition ON & heaters");
                return;
            }
            if (hasTemp) {
                if (tempValidSince == 0L) {
                    tempValidSince = SystemClock.elapsedRealtime();
                }
                if (manual
                        || ComfortPolicy.isBcmSeatClimateReady(v)
                        || SystemClock.elapsedRealtime() - tempValidSince >= 10000L) {
                    break;
                }
                lastClimateStatus = stamp("Waiting for seat climate controller...");
                notifyListeners();
                sleepQuietly(1000);
                continue;
            }
            lastClimateStatus = stamp("Waiting for valid outside temperature...");
            notifyListeners();
            sleepQuietly(1500);
        }

        if (manual
                && v != null
                && !"1".equals(v.get("hv.ready"))
                && !ComfortPolicy.isBcmSeatClimateReady(v)
                && cabin.ignOn == 0) {
            String ambDisp = ComfortPolicy.formatAmbientDisplay(v, isMetricUnits());
            String ambTag = ambDisp.isEmpty() ? "" : (" (" + ambDisp + ")");
            lastClimateStatus = stamp("Ignition in ACC" + ambTag
                    + " — press Start (no brake) for Ignition ON & heaters");
            return;
        }

        if (!hasTemp || v == null) {
            if (manual) {
                lastClimateStatus = stamp("Outside temperature unavailable (check Ignition ON)");
                return;
            }
            if (tempTimeoutRetries < 2) {
                tempTimeoutRetries++;
                lastClimateStatus = stamp("Outside temperature delayed (retry " + tempTimeoutRetries + "/2)...");
                Log.w(TAG, "[hmgapi-comfort] outside temperature unavailable after 60 s; will retry ("
                        + tempTimeoutRetries + "/2)");
                return;
            }
            driveTracker.markCompleted();
            lastClimateStatus = stamp("Skipped (outside temperature unavailable)");
            Log.w(TAG, "[hmgapi-comfort] outside temperature unavailable; skipping climate for this session");
            return;
        }

        OccupantSignalProbe.PassengerPresence presence = cabin.passenger;
        if (!manual
                && config.passengerAutoDetect
                && !ComfortConfig.POLICY_OFF.equals(config.passengerPolicy)
                && !presence.available) {
            for (int i = 0; i < 5 && running && !presence.available; i++) {
                sleepQuietly(500);
                OccupantSignalProbe.CabinState refreshed = occupancyClient.queryCabinState();
                if (refreshed != null) {
                    cabin = refreshed;
                    updateSeatCapabilities(cabin.seatCaps);
                    updateTempUnit(cabin.tempUnit, null);
                    presence = cabin.passenger;
                }
            }
        }
        lastCabinState = cabin;
        lastPassengerPresence = presence;

        tempTimeoutRetries = 0;
        ComfortPolicy.Plan plan = ComfortPolicy.evaluateClimate(
                config, v, presence, seatCaps, isMetricUnits());
        if (plan.actions.isEmpty()) {
            driveTracker.markCompleted();
            boardingTracker.onSessionClimateEvaluated(config, presence);
            lastClimateStatus = stamp(plan.summary);
            Log.i(TAG, "[hmgapi-comfort] " + plan.summary);
            return;
        }

        boolean appliedAll = executeActionsWithRetry(api, plan.actions);
        driveTracker.markCompleted();
        boardingTracker.onSessionClimateEvaluated(config, presence);
        lastClimateStatus = stamp(plan.summary + (appliedAll ? "" : " (partial)"));
        Log.i(TAG, "[hmgapi-comfort] " + lastClimateStatus);
        if (config.toastOnApply) {
            HmgApi.toast(appContext, "Comfort: " + plan.summary);
        }
    }

    private void runPassengerBoardingSequence(
            HmgApi api,
            Map<String, String> v,
            OccupantSignalProbe.PassengerPresence presence) throws IOException {
        if (Float.isNaN(ComfortPolicy.readAmbientF(v))) {
            return;
        }
        ComfortPolicy.Plan plan = ComfortPolicy.evaluatePassengerBoarding(
                config, v, presence, seatCaps, isMetricUnits());
        boardingTracker.markBoardingHandled();
        if (plan.actions.isEmpty()) {
            Log.i(TAG, "[hmgapi-comfort] " + plan.summary);
            return;
        }
        boolean appliedAll = executeActionsWithRetry(api, plan.actions);
        lastClimateStatus = stamp(plan.summary + (appliedAll ? "" : " (partial)"));
        Log.i(TAG, "[hmgapi-comfort] " + lastClimateStatus);
        if (config.toastOnApply) {
            HmgApi.toast(appContext, "Comfort: " + plan.summary);
        }
    }

    private boolean executeActionsWithRetry(HmgApi api, List<ComfortPolicy.Action> actions) throws IOException {
        List<ComfortPolicy.Action> pending = new ArrayList<ComfortPolicy.Action>(actions);
        boolean hadPermanentRefusal = false;
        int ownerWaits = 0;
        for (int attempt = 0; attempt < 6 && !pending.isEmpty() && running; ) {
            if (config.welcomeDismiss
                    && welcomeTracker.readyToDismiss(SystemClock.elapsedRealtime(), config.welcomeDelayMs)) {
                handleWelcome(api, false);
            }
            boolean waitingOwner = false;
            List<ComfortPolicy.Action> candidates = new ArrayList<ComfortPolicy.Action>();
            for (ComfortPolicy.Action act : pending) {
                ComfortPolicy.Action actual = act;
                try {
                    api.command(act.command, act.args());
                    candidates.add(actual);
                } catch (HmgApi.HmgApiException e) {
                    String msg = e.getMessage() == null ? "" : e.getMessage();
                    if (msg.contains("two levels") && "medium".equals(act.level)) {
                        actual = new ComfortPolicy.Action(
                                act.command, act.target, ComfortPolicy.fallbackTwoStep(act.level));
                        try {
                            api.command(actual.command, actual.args());
                            candidates.add(actual);
                        } catch (HmgApi.HmgApiException e2) {
                            String msg2 = e2.getMessage() == null ? "" : e2.getMessage();
                            if (ComfortPolicy.isPermanentCommandRefusal(msg2)) {
                                hadPermanentRefusal = true;
                            } else {
                                candidates.add(actual);
                            }
                            Log.w(TAG, "[hmgapi-comfort] two-step fallback (" + actual + "): " + msg2);
                        } catch (IOException e2) {
                            candidates.add(actual);
                            Log.w(TAG, "[hmgapi-comfort] two-step fallback I/O (" + actual + "): " + e2);
                        }
                    } else if (msg.contains("waiting for the owner")) {
                        waitingOwner = true;
                        candidates.add(actual);
                    } else if (ComfortPolicy.isPermanentCommandRefusal(msg)) {
                        hadPermanentRefusal = true;
                        Log.w(TAG, "[hmgapi-comfort] permanent command refusal (" + act + "): " + msg);
                    } else {
                        candidates.add(actual);
                        Log.w(TAG, "[hmgapi-comfort] transient command failure (" + act + ", attempt "
                                + (attempt + 1) + "): " + msg);
                    }
                } catch (IOException e) {
                    candidates.add(actual);
                    Log.w(TAG, "[hmgapi-comfort] command I/O error (" + act + ", attempt "
                            + (attempt + 1) + "): " + e);
                }
                sleepQuietly(250);
            }

            if (candidates.isEmpty()) {
                return false;
            }

            if (waitingOwner && ownerWaits < 6) {
                ownerWaits++;
                lastClimateStatus = stamp("Waiting for owner command approval on screen...");
                notifyListeners();
                sleepQuietly(5000);
            } else {
                attempt++;
                sleepQuietly(2000);   // > Hub's 1000 ms cache window so fresh BCM/VR telemetry is verified
            }

            try {
                readMergedTelemetry(api);
                Map<String, String> check = lastReadings;
                List<ComfortPolicy.Action> retry = new ArrayList<ComfortPolicy.Action>();
                for (ComfortPolicy.Action act : candidates) {
                    if (!ComfortPolicy.isActionConfirmed(act, check)) {
                        retry.add(act);
                    }
                }
                pending = retry;
            } catch (IOException e) {
                Log.w(TAG, "[hmgapi-comfort] verification read failed: " + e);
                pending = candidates;
            }
        }
        return pending.isEmpty() && !hadPermanentRefusal;
    }

    public static ComfortConfig parseConfig(JSONObject j) {
        ComfortConfig d = ComfortConfig.defaults();
        if (j == null) return d;
        boolean welcomeDismiss = j.optBoolean("welcome_dismiss", d.welcomeDismiss);
        int welcomeDelayMs = j.optInt("welcome_delay_ms", d.welcomeDelayMs);
        boolean climateEnabled = j.optBoolean("climate_enabled", d.climateEnabled);
        int heatHighF = j.optInt("heat_high_f", d.heatHighF);
        int heatMedF = j.optInt("heat_med_f", d.heatMedF);
        int heatLowF = j.optInt("heat_low_f", d.heatLowF);
        boolean wheelEnabled = j.optBoolean("wheel_enabled", d.wheelEnabled);
        int wheelF = j.optInt("wheel_f", d.wheelF);
        String passengerPolicy = j.optString("passenger_policy", d.passengerPolicy);
        boolean passengerAutoDetect = j.optBoolean("passenger_auto_detect", d.passengerAutoDetect);
        String rearLeftPolicy = j.optString("rear_left_policy", d.rearLeftPolicy);
        String rearRightPolicy = j.optString("rear_right_policy", d.rearRightPolicy);
        boolean ventEnabled = j.optBoolean("vent_enabled", d.ventEnabled);
        int ventLowF = j.optInt("vent_low_f", d.ventLowF);
        int ventMedF = j.optInt("vent_med_f", d.ventMedF);
        int ventHighF = j.optInt("vent_high_f", d.ventHighF);
        boolean toastOnApply = j.optBoolean("toast_on_apply", d.toastOnApply);
        if (j.has("heat_high_c")) {
            return new ComfortConfig(
                    welcomeDismiss, welcomeDelayMs,
                    climateEnabled, heatHighF, heatMedF, heatLowF,
                    wheelEnabled, wheelF,
                    passengerPolicy, passengerAutoDetect,
                    rearLeftPolicy, rearRightPolicy,
                    ventEnabled, ventLowF, ventMedF, ventHighF,
                    toastOnApply,
                    j.optInt("heat_high_c", ComfortConfig.fToC(heatHighF)),
                    j.optInt("heat_med_c", ComfortConfig.fToC(heatMedF)),
                    j.optInt("heat_low_c", ComfortConfig.fToC(heatLowF)),
                    j.optInt("wheel_c", ComfortConfig.fToC(wheelF)),
                    j.optInt("vent_low_c", ComfortConfig.fToC(ventLowF)),
                    j.optInt("vent_med_c", ComfortConfig.fToC(ventMedF)),
                    j.optInt("vent_high_c", ComfortConfig.fToC(ventHighF)));
        }
        return new ComfortConfig(
                welcomeDismiss, welcomeDelayMs,
                climateEnabled, heatHighF, heatMedF, heatLowF,
                wheelEnabled, wheelF,
                passengerPolicy, passengerAutoDetect,
                rearLeftPolicy, rearRightPolicy,
                ventEnabled, ventLowF, ventMedF, ventHighF,
                toastOnApply);
    }

    public static JSONObject toJson(ComfortConfig c) throws JSONException {
        JSONObject j = new JSONObject();
        j.put("welcome_dismiss", c.welcomeDismiss);
        j.put("welcome_delay_ms", c.welcomeDelayMs);
        j.put("climate_enabled", c.climateEnabled);
        j.put("heat_high_f", c.heatHighF);
        j.put("heat_med_f", c.heatMedF);
        j.put("heat_low_f", c.heatLowF);
        j.put("wheel_enabled", c.wheelEnabled);
        j.put("wheel_f", c.wheelF);
        j.put("passenger_policy", c.passengerPolicy);
        j.put("passenger_auto_detect", c.passengerAutoDetect);
        j.put("rear_left_policy", c.rearLeftPolicy);
        j.put("rear_right_policy", c.rearRightPolicy);
        j.put("vent_enabled", c.ventEnabled);
        j.put("vent_low_f", c.ventLowF);
        j.put("vent_med_f", c.ventMedF);
        j.put("vent_high_f", c.ventHighF);
        j.put("toast_on_apply", c.toastOnApply);
        j.put("heat_high_c", c.heatHighC);
        j.put("heat_med_c", c.heatMedC);
        j.put("heat_low_c", c.heatLowC);
        j.put("wheel_c", c.wheelC);
        j.put("vent_low_c", c.ventLowC);
        j.put("vent_med_c", c.ventMedC);
        j.put("vent_high_c", c.ventHighC);
        return j;
    }

    private void notifyListeners() {
        Snapshot s = snapshot();
        List<Listener> copy;
        synchronized (listeners) {
            copy = new ArrayList<Listener>(listeners);
        }
        for (Listener l : copy) {
            try {
                l.onStateUpdated(s);
            } catch (Throwable ignored) {}
        }
    }

    private static String stamp(String msg) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        return "[" + t + "] " + msg;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {}
    }
}
