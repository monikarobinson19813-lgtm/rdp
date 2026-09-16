package com.remotephone.direct;

public final class RelayConfig {
    // Filled after the Cloudflare Worker is deployed. Keeping this in one file
    // makes the relay endpoint easy to replace without touching session logic.
    public static final String BASE_URL = "";

    private RelayConfig() {}

    public static boolean isConfigured() {
        return BASE_URL != null && (BASE_URL.startsWith("https://") || BASE_URL.startsWith("http://"));
    }

    public static String webSocketUrl(String remoteId) {
        if (!isConfigured()) throw new IllegalStateException("Internet relay is not configured yet");
        String base = BASE_URL.endsWith("/") ? BASE_URL.substring(0, BASE_URL.length() - 1) : BASE_URL;
        if (base.startsWith("https://")) base = "wss://" + base.substring(8);
        else if (base.startsWith("http://")) base = "ws://" + base.substring(7);
        return base + "/relay/" + remoteId;
    }
}
