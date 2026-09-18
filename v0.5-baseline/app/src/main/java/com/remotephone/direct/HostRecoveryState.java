package com.remotephone.direct;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.UserManager;

/** Device-protected recovery intent state for unattended Host restart. */
public final class HostRecoveryState {
    private static final String LEGACY_PREF = "remotephone_host_recovery";
    private static final String DIRECT_PREF = "remotephone_host_recovery_direct_boot";
    private static final String KEY_DESIRED_RUNNING = "desired_running";

    private HostRecoveryState() {}

    private static SharedPreferences legacy(Context c) {
        return c.getSharedPreferences(LEGACY_PREF, Context.MODE_PRIVATE);
    }

    private static SharedPreferences direct(Context c) {
        Context dc = Build.VERSION.SDK_INT >= 24 ? c.createDeviceProtectedStorageContext() : c;
        return dc.getSharedPreferences(DIRECT_PREF, Context.MODE_PRIVATE);
    }

    private static boolean isUserUnlocked(Context c) {
        if (Build.VERSION.SDK_INT < 24) return true;
        UserManager um = (UserManager)c.getSystemService(Context.USER_SERVICE);
        return um == null || um.isUserUnlocked();
    }

    public static void setDesiredRunning(Context c, boolean desired) {
        legacy(c).edit().putBoolean(KEY_DESIRED_RUNNING, desired).apply();
        direct(c).edit().putBoolean(KEY_DESIRED_RUNNING, desired).apply();
    }

    public static boolean shouldRun(Context c) {
        SharedPreferences dp = direct(c);
        if (dp.contains(KEY_DESIRED_RUNNING))
            return dp.getBoolean(KEY_DESIRED_RUNNING, false);

        if (!isUserUnlocked(c)) return false;
        boolean legacyValue = legacy(c).getBoolean(KEY_DESIRED_RUNNING, false);
        dp.edit().putBoolean(KEY_DESIRED_RUNNING, legacyValue).apply();
        return legacyValue;
    }
}
