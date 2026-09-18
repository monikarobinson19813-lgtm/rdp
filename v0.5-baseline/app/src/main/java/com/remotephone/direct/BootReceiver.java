package com.remotephone.direct;

import android.app.*;
import android.content.*;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    private static final int RECOVERY_NOTIFICATION = 223;

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action) &&
                !Intent.ACTION_BOOT_COMPLETED.equals(action) &&
                !Intent.ACTION_USER_UNLOCKED.equals(action)) return;
        if (!HostRecoveryState.shouldRun(context)) return;

        if (!HostConfig.hasDirectBootRecoveryConfig(context)) {
            if (Intent.ACTION_USER_UNLOCKED.equals(action) && HostConfig.isConfigured(context)) {
                HostConfig.prepareDirectBoot(context);
            }
            if (!HostConfig.hasDirectBootRecoveryConfig(context)) {
                postRecoveryNotification(context, "Unlock the Host once to restore unattended recovery");
                return;
            }
        }

        try {
            Intent recover = new Intent(context, HostService.class);
            recover.setAction(HostService.ACTION_RECOVER);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(recover);
            else context.startService(recover);
        } catch (Exception e) {
            postRecoveryNotification(context, "Host recovery needs attention after restart");
        }
    }

    private void postRecoveryNotification(Context context, String message) {
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
                    context, 220, open,
                    PendingIntent.FLAG_UPDATE_CURRENT |
                            (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(context, "remotephone_host")
                    : new Notification.Builder(context);
            b.setContentTitle("RemotePhone Host recovery")
                    .setContentText(message)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentIntent(pi)
                    .setOnlyAlertOnce(true);
            nm.notify(RECOVERY_NOTIFICATION, b.build());
        } catch (Exception ignored) {}
    }
}
