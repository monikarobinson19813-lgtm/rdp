package com.remotephone.direct;

import android.app.*;
import android.content.*;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    private static final String RECOVERY_PREF = "remotephone_host_recovery";
    private static final String KEY_DESIRED_RUNNING = "desired_running";
    private static final int RECOVERY_NOTIFICATION = 223;

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) &&
                !Intent.ACTION_USER_UNLOCKED.equals(action)) return;
        if (!HostConfig.isConfigured(context)) return;
        if (!wasHostExpectedToRun(context)) return;

        postRecoveryNotification(context);
    }

    private boolean wasHostExpectedToRun(Context context) {
        return context.getSharedPreferences(RECOVERY_PREF, Context.MODE_PRIVATE)
                .getBoolean(KEY_DESIRED_RUNNING, false);
    }

    private void postRecoveryNotification(Context context) {
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(
                        "remotephone_host",
                        "RemotePhone Host",
                        NotificationManager.IMPORTANCE_LOW));
            }

            Intent open = new Intent(context, HostActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(
                    context,
                    220,
                    open,
                    PendingIntent.FLAG_UPDATE_CURRENT |
                            (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(context, "remotephone_host")
                    : new Notification.Builder(context);
            b.setContentTitle("RemotePhone Host needs capture approval")
                    .setContentText("Phone restarted. Tap once to restore screen capture and full remote access.")
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(false);
            nm.notify(RECOVERY_NOTIFICATION, b.build());
        } catch (Exception ignored) {}
    }
}
