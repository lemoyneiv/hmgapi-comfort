package dev.lemoyne.comfort;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import hmgapi.client.HmgApi;

/**
 * Non-root sticky service hosting {@link ComfortEngine} and listening for Android + Mobis
 * screen wake/sleep broadcasts and HMG-API state/settings updates.
 */
public final class ComfortService extends Service {
    public static final String ACTION_MOBIS_SCREEN_ON = "com.mobis.intent.action.SCREEN_ON";
    public static final String ACTION_MOBIS_SCREEN_OFF = "com.mobis.intent.action.SCREEN_OFF";
    public static final String ACTION_QB_WAKEUP = "com.daudio.app.qb.QB_WAKEUP";
    public static final String ACTION_QB_POW_ON = "com.daudio.app.qb.QB_POW_ON";
    public static final String ACTION_HKMC_QB_WAKEUP = "com.hkmc.intent.action.QB_WAKEUP";
    public static final String ACTION_QB_SLEEP = "com.daudio.app.qb.QB_SLEEP";
    public static final String ACTION_QB_POW_OFF = "com.daudio.app.qb.QB_POW_OFF";
    public static final String ACTION_ENSURE_RUNNING = "dev.lemoyne.comfort.action.ENSURE_RUNNING";

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private ComfortEngine engine;

    private static boolean isWakeAction(String act) {
        return Intent.ACTION_SCREEN_ON.equals(act)
                || ACTION_MOBIS_SCREEN_ON.equals(act)
                || ACTION_QB_WAKEUP.equals(act)
                || ACTION_QB_POW_ON.equals(act)
                || ACTION_HKMC_QB_WAKEUP.equals(act);
    }

    private static boolean isPowerOffAction(String act) {
        return ACTION_QB_SLEEP.equals(act) || ACTION_QB_POW_OFF.equals(act);
    }

    private static boolean isSleepAction(String act) {
        return Intent.ACTION_SCREEN_OFF.equals(act)
                || ACTION_MOBIS_SCREEN_OFF.equals(act)
                || isPowerOffAction(act);
    }

    private final BroadcastReceiver eventReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || engine == null) return;
            String act = intent.getAction();
            if (isPowerOffAction(act)) {
                engine.onVehiclePowerOff();
            } else if (isSleepAction(act)) {
                engine.onScreenOff();
            } else if (isWakeAction(act)) {
                engine.onScreenOn();
            } else if (HmgApi.ACTION_STATE_CHANGED.equals(act)) {
                checkEnabledAndReload();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        engine = ComfortEngine.getInstance(this);
        engine.ensureStarted();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(ACTION_MOBIS_SCREEN_ON);
        f.addAction(ACTION_MOBIS_SCREEN_OFF);
        f.addAction(ACTION_QB_WAKEUP);
        f.addAction(ACTION_QB_POW_ON);
        f.addAction(ACTION_HKMC_QB_WAKEUP);
        f.addAction(ACTION_QB_SLEEP);
        f.addAction(ACTION_QB_POW_OFF);
        f.addAction(HmgApi.ACTION_STATE_CHANGED);
        registerReceiver(eventReceiver, f);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (engine != null) {
            engine.ensureStarted();
            String act = intent == null ? null : intent.getAction();
            if (isWakeAction(act)) {
                engine.onScreenOn();
            } else if (ACTION_ENSURE_RUNNING.equals(act)) {
                engine.onSupervisorPing();
            } else if (HmgApi.ACTION_STATE_CHANGED.equals(act)) {
                checkEnabledAndReload();
            } else {
                engine.requestSettingsReload();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(eventReceiver);
        } catch (Throwable ignored) {}
        if (engine != null) {
            engine.stop();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void checkEnabledAndReload() {
        String stateJson = ComfortPolicy.readSmallTextFile("/data/hmgapi/public/state.json");
        if (!stateJson.isEmpty() && !ComfortPolicy.isModDisabledInPublicState(stateJson, "hmgapi-comfort")) {
            if (engine != null) {
                engine.onExternalStateChanged();
            }
            return;
        }
        Thread t = new Thread("ComfortStateCheck") {
            @Override
            public void run() {
                try {
                    HmgApi api = HmgApi.connect().timeout(2500);
                    if (ComfortEngine.isModExplicitlyDisabled(api)) {
                        uiHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                stopSelf();
                            }
                        });
                        return;
                    }
                    if (engine != null) {
                        engine.onExternalStateChanged();
                    }
                } catch (Throwable ignored) {}
            }
        };
        t.setDaemon(true);
        t.start();
    }
}
