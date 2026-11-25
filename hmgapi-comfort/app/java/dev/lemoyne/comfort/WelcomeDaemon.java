package dev.lemoyne.comfort;

import android.content.ComponentName;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import hmgapi.client.HmgApi;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Method;

/**
 * Low-overhead startup & QuickBoot STR resume daemon run under app_process
 * (user=system, start=android) by hmgapid.
 *
 *  - Launches once sys.boot_completed=1 so zero Dalvik startup work runs during early zygote boot.
 *  - Suppresses startup_notice dismissal and service wakeups during silent parked wakes
 *    (Bluelink polls, charge checks, door unlock, or Mobis BlackScreen / AV-PowerOff).
 *  - Defers starting ComfortService by 12 s on cold boot (or until Wireless AA / CarPlay TCP
 *    projection is already established) so the projection connect window stays contention-free.
 *  - Starts ComfortService directly via ActivityManagerNative Binder IPC (0 am shell forks).
 */
public final class WelcomeDaemon {
    private static final String TAG = "HmgComfort";
    private static final String MOD_ID = "hmgapi-comfort";
    private static final File STATE_FILE = new File("/data/hmgapi/public/state.json");
    private static final File WAKE_FILE = new File("/data/hmgapi/public/wake");
    private static final File OPENLINK_BT_FILE = new File("/dev/.openlink-bt");

    private static Method getPropMethod;
    private static Object cachedAm;
    private static Method cachedStartService;
    private static int cachedStartServiceArity;

    private static volatile OccupantSignalProbe activeProbe;
    private static volatile OccupantSignalProbe.LoopbackServer activeServer;
    private static volatile boolean modDisabled = false;
    private static boolean welcomeDismiss = true;
    private static int welcomeDelayMs = 0;
    private static long lastStateMtime = -1L;

    private WelcomeDaemon() {}

    public static void main(String[] args) {
        initSystemProperties();
        log("welcome fast-path daemon started");

        while (!"1".equals(getProp("sys.boot_completed"))) {
            sleepQuietly(50);
        }

        long bootReadyAtMs = SystemClock.elapsedRealtime();
        startProbeServer();
        refreshSettings(true);

        long lastSleepGap = currentSleepGap();
        long lastWakeSeq = readWakeSeq();

        if (!modDisabled && !isSilentWakeNow() && welcomeDismiss) {
            dismissWithRetry("cold-boot", true);
            lastSleepGap = currentSleepGap();
            lastWakeSeq = readWakeSeq();
        }

        OccupantSignalProbe p0 = activeProbe;
        int lastMobisSleeping = p0 == null ? -1 : p0.readMobisSleepingFast();
        int lastIgnOn = p0 == null ? -1 : p0.readIgnStatusFast();
        boolean wasSilent = isSilentWakeNow();
        boolean serviceStartedOnce = false;
        long lastStartAttemptMs = 0L;
        long lastSupervisorMs = SystemClock.elapsedRealtime();
        int mobisTick = 0;

        // Resident QuickBoot STR, wake.sh, Mobis BlackScreen/AV-Off, and ComfortService supervisor
        while (true) {
            sleepQuietly(wasSilent ? 1500L : 200L);
            refreshSettings(false);

            long now = SystemClock.elapsedRealtime();
            long gap = currentSleepGap();
            long wakeSeq = readWakeSeq();
            boolean gapWoke = gap - lastSleepGap >= 3000L;
            boolean seqWoke = wakeSeq > lastWakeSeq;
            lastSleepGap = gap;
            lastWakeSeq = wakeSeq;

            boolean mobisWoke = false;
            boolean ignWoke = false;
            boolean silentCleared = false;
            mobisTick++;
            if (wasSilent || gapWoke || seqWoke || mobisTick >= 7) {
                mobisTick = 0;
                OccupantSignalProbe p = activeProbe;
                int ms = p == null ? -1 : p.readMobisSleepingFast();
                int ign = p == null ? -1 : p.readIgnStatusFast();
                int pwr = p == null ? -1 : p.readPowerStatusFast();
                boolean silentNow = ComfortPolicy.isSilentParkedStandby(
                        OPENLINK_BT_FILE.isFile(), pwr, ms, ign);

                if (ms == 0 || ms == 1) {
                    if (lastMobisSleeping == 1 && ms == 0) {
                        mobisWoke = true;
                    } else if (lastMobisSleeping == 0 && ms == 1 && ign != 1 && p != null) {
                        p.onWake();
                    }
                    lastMobisSleeping = ms;
                }
                if (ign == 0 || ign == 1) {
                    if (lastIgnOn == 0 && ign == 1) {
                        ignWoke = true;
                    }
                    lastIgnOn = ign;
                }
                if (wasSilent && !silentNow) {
                    silentCleared = true;
                }
                wasSilent = silentNow;
            }

            if (modDisabled || wasSilent) {
                continue;
            }

            if (gapWoke || seqWoke || mobisWoke || silentCleared) {
                OccupantSignalProbe p = activeProbe;
                if (p != null) {
                    p.onWake();
                }
                if (startComfortServiceDirect(ComfortService.ACTION_MOBIS_SCREEN_ON)) {
                    serviceStartedOnce = true;
                    lastStartAttemptMs = now;
                    lastSupervisorMs = now;
                }
                if (welcomeDismiss) {
                    String trigger = gapWoke ? "quickboot-gap"
                            : (seqWoke ? "wake-hook" : (mobisWoke ? "mobis-wake" : "standby-wake"));
                    dismissWithRetry(trigger, false);
                    lastSleepGap = currentSleepGap();
                    lastWakeSeq = readWakeSeq();
                    if (p != null) {
                        int msAfter = p.readMobisSleepingFast();
                        int ignAfter = p.readIgnStatusFast();
                        int pwrAfter = p.readPowerStatusFast();
                        if (msAfter == 0 || msAfter == 1) lastMobisSleeping = msAfter;
                        if (ignAfter == 0 || ignAfter == 1) lastIgnOn = ignAfter;
                        wasSilent = ComfortPolicy.isSilentParkedStandby(
                                OPENLINK_BT_FILE.isFile(), pwrAfter, msAfter, ignAfter);
                    }
                }
                continue;
            }

            if (ignWoke) {
                OccupantSignalProbe p = activeProbe;
                if (p != null) {
                    p.onWake();
                }
                if (startComfortServiceDirect(ComfortService.ACTION_MOBIS_SCREEN_ON)) {
                    serviceStartedOnce = true;
                    lastStartAttemptMs = now;
                    lastSupervisorMs = now;
                }
                continue;
            }

            if (!serviceStartedOnce) {
                if (now - lastStartAttemptMs >= 3000L
                        && ComfortPolicy.isColdBootRunwayReady(
                                now,
                                bootReadyAtMs,
                                ComfortPolicy.COLD_BOOT_RUNWAY_MS,
                                ComfortPolicy.isProjectionTcpConnected(),
                                false)) {
                    lastStartAttemptMs = now;
                    if (startComfortServiceDirect(ComfortService.ACTION_ENSURE_RUNNING)) {
                        serviceStartedOnce = true;
                        lastSupervisorMs = now;
                        log("started ComfortService via Binder after cold-boot runway");
                    }
                }
            } else if (now - lastSupervisorMs >= 60000L) {
                lastSupervisorMs = now;
                OccupantSignalProbe.LoopbackServer srv = activeServer;
                if (srv == null || srv.msSinceLastClient() > 45000L) {
                    startComfortServiceDirect(ComfortService.ACTION_ENSURE_RUNNING);
                }
            }
        }
    }

    private static boolean isSilentWakeNow() {
        OccupantSignalProbe p = activeProbe;
        int pwr = p == null ? -1 : p.readPowerStatusFast();
        int ms = p == null ? -1 : p.readMobisSleepingFast();
        int ign = p == null ? -1 : p.readIgnStatusFast();
        return ComfortPolicy.isSilentParkedStandby(OPENLINK_BT_FILE.isFile(), pwr, ms, ign);
    }

    private static void dismissWithRetry(String trigger, boolean coldBoot) {
        int delay = welcomeDelayMs;
        if (coldBoot && delay < 1500) {
            // Wait briefly after sys.boot_completed=1 so the OEM consent screen is subscribed.
            delay = 1500;
        }
        if (delay > 0) {
            sleepQuietly(delay);
        }
        int maxAttempts = coldBoot ? 50 : 36;
        long retryMs = coldBoot ? 500L : 250L;
        int confirmedOutOfPark = 0;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            refreshSettings(false);
            if (modDisabled) {
                log("welcome fast-path (" + trigger + "): mod disabled");
                return;
            }
            if (isSilentWakeNow()) {
                log("welcome fast-path (" + trigger + "): skipped (silent standby)");
                return;
            }
            try {
                HmgApi api = HmgApi.connect().timeout(5000);
                String res = api.command("startup_notice", "dismiss");
                log("welcome fast-path (" + trigger + ", attempt " + attempt + ") -> " + res);
                return;
            } catch (HmgApi.HmgApiException e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (msg.contains("once per start")
                        || msg.contains("hasn't allowed")
                        || msg.contains("didn't allow")
                        || msg.contains("not allowed")) {
                    log("welcome fast-path (" + trigger + "): " + msg);
                    return;
                }
                if (msg.contains("isn't in P") || msg.contains("not in P")) {
                    try {
                        HmgApi checkApi = HmgApi.connect().timeout(4500);
                        java.util.Map<String, String> v = checkApi.vehicle("gear.park", "hv.ready");
                        if (ComfortPolicy.isConfirmedOutOfPark(v)) {
                            confirmedOutOfPark++;
                            if (confirmedOutOfPark >= 3) {
                                log("welcome fast-path (" + trigger + "): " + msg);
                                return;
                            }
                        } else {
                            confirmedOutOfPark = 0;
                        }
                    } catch (Throwable ignored) {}
                }
                sleepQuietly(retryMs);
            } catch (Throwable ignored) {
                sleepQuietly(retryMs);
            }
        }
        log("welcome fast-path (" + trigger + "): timed out waiting for vehicle hub / Park");
    }

    private static boolean startComfortServiceDirect(String action) {
        if (!"1".equals(getProp("sys.boot_completed")) || modDisabled) {
            return false;
        }
        try {
            Intent svc = new Intent(action);
            svc.setComponent(new ComponentName("dev.lemoyne.comfort", "dev.lemoyne.comfort.ComfortService"));
            Object am = cachedAm;
            Method m = cachedStartService;
            int arity = cachedStartServiceArity;
            if (am == null || m == null) {
                Class<?> amn = Class.forName("android.app.ActivityManagerNative");
                am = amn.getMethod("getDefault").invoke(null);
                if (am != null) {
                    Method[] methods = am.getClass().getMethods();
                    for (int i = 0; i < methods.length; i++) {
                        Method cand = methods[i];
                        if (!"startService".equals(cand.getName())) continue;
                        int len = cand.getParameterTypes().length;
                        if (len == 4 || len == 5 || len == 3) {
                            m = cand;
                            arity = len;
                            break;
                        }
                    }
                }
                if (am != null && m != null) {
                    cachedAm = am;
                    cachedStartService = m;
                    cachedStartServiceArity = arity;
                }
            }
            if (am != null && m != null) {
                Object comp;
                if (arity == 4) {
                    comp = m.invoke(am, null, svc, null, Integer.valueOf(0));
                } else if (arity == 5) {
                    comp = m.invoke(am, null, svc, null, "android", Integer.valueOf(0));
                } else {
                    comp = m.invoke(am, null, svc, null);
                }
                return comp != null;
            }
        } catch (Throwable ignored) {
            cachedAm = null;
            cachedStartService = null;
        }
        // Fallback only if ActivityManagerNative reflection was unavailable on this firmware
        try {
            Process p = new ProcessBuilder(
                    "/system/bin/am", "startservice", "--user", "0",
                    "-a", action,
                    "-n", "dev.lemoyne.comfort/.ComfortService")
                    .redirectErrorStream(true)
                    .start();
            return p.waitFor() == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void startProbeServer() {
        OccupantSignalProbe probe = new OccupantSignalProbe(
                new OccupantSignalProbe.ReflectionGen5wSignalSource(new CabinPushBridge()));
        activeProbe = probe;
        try {
            OccupantSignalProbe.LoopbackServer srv =
                    new OccupantSignalProbe.LoopbackServer(probe, OccupantSignalProbe.DEFAULT_PORT);
            srv.start();
            activeServer = srv;
            log("cabin telemetry bridge listening on 127.0.0.1:" + OccupantSignalProbe.DEFAULT_PORT);
        } catch (Throwable t) {
            log("cabin telemetry bridge unavailable: " + t);
        }
    }

    private static void refreshSettings(boolean force) {
        long mtime = STATE_FILE.isFile() ? STATE_FILE.lastModified() : 0L;
        if (!force && mtime == lastStateMtime) return;
        if (mtime > 0L) {
            try {
                String raw = ComfortPolicy.readSmallTextFile(STATE_FILE.getAbsolutePath());
                if (!raw.isEmpty()) {
                    JSONObject root = new JSONObject(raw);
                    JSONObject mods = root.optJSONObject("mods");
                    JSONObject mod = mods == null ? null : mods.optJSONObject(MOD_ID);
                    if (mod != null) {
                        modDisabled = !mod.optBoolean("enabled", true);
                        JSONObject s = mod.optJSONObject("settings");
                        if (s != null) {
                            welcomeDismiss = s.optBoolean("welcome_dismiss", true);
                            int d = s.optInt("welcome_delay_ms", 800);
                            welcomeDelayMs = Math.max(0, Math.min(10000, d));
                        }
                        lastStateMtime = mtime;
                        return;
                    }
                }
            } catch (Throwable ignored) {}
        }
        try {
            HmgApi api = HmgApi.connect().timeout(3500);
            if (ComfortEngine.isModExplicitlyDisabled(api)) {
                modDisabled = true;
                lastStateMtime = mtime;
                return;
            }
            modDisabled = false;
            JSONObject s = api.settings();
            if (s != null) {
                welcomeDismiss = s.optBoolean("welcome_dismiss", true);
                int d = s.optInt("welcome_delay_ms", 800);
                welcomeDelayMs = Math.max(0, Math.min(10000, d));
                lastStateMtime = mtime;
            }
        } catch (Throwable ignored) {}
    }

    private static long currentSleepGap() {
        return SystemClock.elapsedRealtime() - SystemClock.uptimeMillis();
    }

    static long readWakeSeq() {
        if (!WAKE_FILE.isFile()) return 0L;
        FileInputStream in = null;
        try {
            in = new FileInputStream(WAKE_FILE);
            byte[] b = new byte[32];
            int n = in.read(b);
            if (n <= 0) return 0L;
            return Long.parseLong(new String(b, 0, n, "UTF-8").trim());
        } catch (Throwable ignored) {
            return 0L;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    private static void initSystemProperties() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            getPropMethod = sp.getMethod("get", String.class);
        } catch (Throwable ignored) {}
    }

    private static String getProp(String key) {
        if (getPropMethod != null) {
            try {
                Object v = getPropMethod.invoke(null, key);
                return v == null ? "" : String.valueOf(v).trim();
            } catch (Throwable ignored) {}
        }
        return "";
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void log(String msg) {
        String line = "[hmgapi-comfort] " + msg;
        System.out.println(line);
        try {
            Log.i(TAG, line);
        } catch (Throwable ignored) {}
    }
}
