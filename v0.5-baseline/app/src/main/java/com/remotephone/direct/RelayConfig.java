package com.remotephone.direct;

public final class RelayConfig {
    // Cloudflare relay endpoint for internet Remote ID routing.
    public static final String BASE_URL = "https://remotephone-relay.monikarobinson19813.workers.dev";

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
