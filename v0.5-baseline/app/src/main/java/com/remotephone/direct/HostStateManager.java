package com.remotephone.direct;

/**
 * Controller-side Host state classifier.
 *
 * Keeps raw Host status strings and transport/video health signals out of the
 * Activity. This class does not bypass Android security; it only describes the
 * state that the Controller can legitimately observe.
 */
public final class HostStateManager {
    public enum State {
        READY,
        SLEEPING,
        LOCKED,
        CAPTURE_APPROVAL_REQUIRED,
        STREAM_UNAVAILABLE,
        RECONNECTING,
        OFFLINE
    }

    private HostStateManager() {}

    public static State fromHostStatus(String raw) {
        if ("Host ready".equals(raw)) return State.READY;
        if ("Host sleeping".equals(raw)) return State.SLEEPING;
        if ("Host locked".equals(raw)) return State.LOCKED;
        if ("Host needs capture approval".equals(raw))
            return State.CAPTURE_APPROVAL_REQUIRED;
        return State.STREAM_UNAVAILABLE;
    }

    public static State resolve(String rawHostStatus,
                                boolean transportConnected,
                                boolean reconnecting,
                                boolean streamStale) {
        if (!transportConnected) return reconnecting ? State.RECONNECTING : State.OFFLINE;

        State reported = fromHostStatus(rawHostStatus);
        if (reported == State.READY && streamStale) return State.STREAM_UNAVAILABLE;
        return reported;
    }

    public static String controllerLabel(State state) {
        if (state == null) return "Host offline";
        switch (state) {
            case READY: return "Host ready";
            case SLEEPING: return "Host sleeping";
            case LOCKED: return "Host locked";
            case CAPTURE_APPROVAL_REQUIRED: return "Host needs capture approval";
            case STREAM_UNAVAILABLE: return "Stream unavailable — Host still online";
            case RECONNECTING: return "Reconnecting to Host…";
            default: return "Host offline";
        }
    }

    public static String dashboardLabel(State state) {
        if (state == null) return "Offline";
        switch (state) {
            case READY: return "Online";
            case SLEEPING: return "Sleeping";
            case LOCKED: return "Locked";
            case CAPTURE_APPROVAL_REQUIRED: return "Online — capture approval";
            case STREAM_UNAVAILABLE: return "Online — stream unavailable";
            case RECONNECTING: return "Reconnecting";
            default: return "Offline";
        }
    }
}
