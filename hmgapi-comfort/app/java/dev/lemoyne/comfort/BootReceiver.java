package dev.lemoyne.comfort;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import java.io.File;

/**
 * Starts {@link ComfortService} on QuickBoot Mobis SCREEN_ON, APK update, or live mod enablement.
 * Uses zero-IPC public {@code state.json} reads and defers cold-boot {@code BOOT_COMPLETED} /
 * {@code STATE_CHANGED} starts to {@link WelcomeDaemon}'s post-runway Binder launch so Wireless
 * Android Auto / CarPlay connects without broadcast-queue or {@code @hmgapi} contention.
 */
public final class BootReceiver extends BroadcastReceiver {
    private static final String MOD_ID = "hmgapi-comfort";
    private static final String STATE_PATH = "/data/hmgapi/public/state.json";
    private static final File OPENLINK_BT_FILE = new File("/dev/.openlink-bt");
    private static final long COLD_BOOT_UPTIME_GUARD_MS = 26_000L;

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (context == null) return;
        if (OPENLINK_BT_FILE.isFile()) return;

        String stateJson = ComfortPolicy.readSmallTextFile(STATE_PATH);
        if (ComfortPolicy.isModDisabledInPublicState(stateJson, MOD_ID)) {
            return;
        }

        final String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && SystemClock.elapsedRealtime() < COLD_BOOT_UPTIME_GUARD_MS
                && !ComfortPolicy.isProjectionTcpConnected()) {
            // WelcomeDaemon starts ComfortService via Binder right after the 12 s post-boot runway.
            return;
        }

        try {
            Intent svc = new Intent(context, ComfortService.class);
            if (action != null) svc.setAction(action);
            context.startService(svc);
        } catch (Throwable ignored) {
        }
    }
}
