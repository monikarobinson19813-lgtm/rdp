package com.remotephone.direct;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.UserManager;
import java.security.SecureRandom;
import java.util.Locale;

public final class HostConfig {
    private static final String PREF = "remotephone_host_config";
    private static final String DIRECT_BOOT_PREF = "remotephone_host_config_direct_boot";
    private static final SecureRandom RANDOM = new SecureRandom();

    private HostConfig() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static SharedPreferences directBootPrefs(Context c) {
        Context dc = Build.VERSION.SDK_INT >= 24 ? c.createDeviceProtectedStorageContext() : c;
        return dc.getSharedPreferences(DIRECT_BOOT_PREF, Context.MODE_PRIVATE);
    }

    private static boolean isUserUnlocked(Context c) {
        if (Build.VERSION.SDK_INT < 24) return true;
        UserManager um = (UserManager)c.getSystemService(Context.USER_SERVICE);
        return um == null || um.isUserUnlocked();
    }

    private static void mirror(Context c, String key, String value) {
        if (value == null || value.isEmpty()) return;
        directBootPrefs(c).edit().putString(key, value).apply();
    }

    /** Copies the stable Host credentials needed by the recovery channel into device-protected storage. */
    public static void prepareDirectBoot(Context c) {
        if (!isUserUnlocked(c)) return;
        SharedPreferences source = prefs(c);
        SharedPreferences.Editor e = directBootPrefs(c).edit();
        String id = source.getString("remote_id", null);
        String pin = source.getString("session_pin", null);
        String token = source.getString("relay_token", null);
        String name = source.getString("friendly_name", null);
        if (id != null) e.putString("remote_id", id);
        if (pin != null) e.putString("session_pin", pin);
        if (token != null) e.putString("relay_token", token);
        if (name != null) e.putString("friendly_name", name);
        e.apply();
    }

    public static boolean hasDirectBootRecoveryConfig(Context c) {
        SharedPreferences p = directBootPrefs(c);
        String id = p.getString("remote_id", null);
        String pin = p.getString("session_pin", null);
        String token = p.getString("relay_token", null);
        return id != null && id.length() == 9 && pin != null && pin.length() == 6 && token != null && token.length() >= 64;
    }

    public static String getRecoveryRemoteId(Context c) {
        if (isUserUnlocked(c)) prepareDirectBoot(c);
        return directBootPrefs(c).getString("remote_id", "");
    }

    public static String getRecoverySessionPin(Context c) {
        if (isUserUnlocked(c)) prepareDirectBoot(c);
        return directBootPrefs(c).getString("session_pin", "");
    }

    public static String getRecoveryRelayToken(Context c) {
        if (isUserUnlocked(c)) prepareDirectBoot(c);
        return directBootPrefs(c).getString("relay_token", "");
    }

    public static String getOrCreateRemoteId(Context c) {
        SharedPreferences p = prefs(c);
        String id = p.getString("remote_id", null);
        if (id == null || id.length() != 9) {
            id = String.format(Locale.US, "%09d", 100_000_000 + RANDOM.nextInt(900_000_000));
            p.edit().putString("remote_id", id).apply();
        }
        mirror(c, "remote_id", id);
        return id;
    }

    public static String getOrCreateSessionPin(Context c) {
        SharedPreferences p = prefs(c);
        String pin = p.getString("session_pin", null);
        if (pin == null || pin.length() != 6) {
            pin = newPin();
            p.edit().putString("session_pin", pin).apply();
        }
        mirror(c, "session_pin", pin);
        return pin;
    }

    public static String rotateSessionPin(Context c) {
        String pin = newPin();
        prefs(c).edit().putString("session_pin", pin).apply();
        mirror(c, "session_pin", pin);
        return pin;
    }

    public static String getOrCreateRelayToken(Context c) {
        SharedPreferences p = prefs(c);
        String token = p.getString("relay_token", null);
        if (token == null || token.length() < 64) {
            byte[] b = new byte[32];
            RANDOM.nextBytes(b);
            StringBuilder s = new StringBuilder(64);
            for (byte x : b) s.append(String.format(Locale.US, "%02x", x & 0xff));
            token = s.toString();
            p.edit().putString("relay_token", token).apply();
        }
        mirror(c, "relay_token", token);
        return token;
    }

    public static String getFriendlyName(Context c) {
        return prefs(c).getString("friendly_name", "Home Host");
    }

    public static void setFriendlyName(Context c, String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) n = "Host";
        prefs(c).edit().putString("friendly_name", n).apply();
        mirror(c, "friendly_name", n);
    }

    public static boolean isConfigured(Context c) {
        String id = prefs(c).getString("remote_id", null);
        return id != null && id.length() == 9;
    }

    private static String newPin() {
        return String.format(Locale.US, "%06d", RANDOM.nextInt(1_000_000));
    }
}
