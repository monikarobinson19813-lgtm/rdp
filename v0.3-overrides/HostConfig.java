package com.remotephone.direct;

import android.content.Context;
import android.content.SharedPreferences;
import java.security.SecureRandom;
import java.util.Locale;

public final class HostConfig {
    private static final String PREF = "remotephone_host_config";
    private static final SecureRandom RANDOM = new SecureRandom();

    private HostConfig() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static String getOrCreateRemoteId(Context c) {
        SharedPreferences p = prefs(c);
        String id = p.getString("remote_id", null);
        if (id == null || id.length() != 9) {
            id = String.format(Locale.US, "%09d", 100_000_000 + RANDOM.nextInt(900_000_000));
            p.edit().putString("remote_id", id).apply();
        }
        return id;
    }

    public static String getOrCreateSessionPin(Context c) {
        SharedPreferences p = prefs(c);
        String pin = p.getString("session_pin", null);
        if (pin == null || pin.length() != 6) {
            pin = newPin();
            p.edit().putString("session_pin", pin).apply();
        }
        return pin;
    }

    public static String rotateSessionPin(Context c) {
        String pin = newPin();
        prefs(c).edit().putString("session_pin", pin).apply();
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
        return token;
    }

    public static String getFriendlyName(Context c) {
        return prefs(c).getString("friendly_name", "Home Host");
    }

    public static void setFriendlyName(Context c, String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) n = "Host";
        prefs(c).edit().putString("friendly_name", n).apply();
    }

    public static boolean isConfigured(Context c) {
        String id = prefs(c).getString("remote_id", null);
        return id != null && id.length() == 9;
    }

    private static String newPin() {
        return String.format(Locale.US, "%06d", RANDOM.nextInt(1_000_000));
    }
}
