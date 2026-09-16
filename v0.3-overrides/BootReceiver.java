package com.remotephone.direct;

import android.app.*;
import android.content.*;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        // HostActivity.getPreferences() stores its settings in the activity-local
        // shared-preferences file. If this phone has never been configured as a Host,
        // do not show Host recovery notifications (important for Controller-only phones).
        String remoteId = context.getSharedPreferences("HostActivity", Context.MODE_PRIVATE)
                .getString("deviceId", null);
        if (remoteId == null || remoteId.length() != 9) return;

        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel("remotephone_host", "RemotePhone Host", NotificationManager.IMPORTANCE_LOW));
            }
            Intent open = new Intent(context, HostActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(context, 220, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(context, "remotephone_host")
                    : new Notification.Builder(context);
            b.setContentTitle("RemotePhone Host needs restart")
                    .setContentText("Android requires screen-capture approval again after reboot. Tap to restore Host access.")
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            nm.notify(223, b.build());
        } catch (Exception ignored) {}
    }
}
