package com.remotephone.direct;

import android.os.Handler;
import android.os.Looper;

/**
 * Controller-side wake coordinator.
 *
 * Keeps wake retry/acknowledgement policy out of ViewerActivity. The Host's
 * existing status messages are treated as the acknowledgement: READY or LOCKED
 * confirms the device became interactive; SLEEPING keeps the retry sequence
 * alive; capture-approval still confirms the Host control path is responding.
 */
public final class WakeManager {
    public interface Sender {
        void sendWake();
    }

    public interface Listener {
        void onWakeMessage(String message);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Sender sender;
    private final Listener listener;

    private volatile int generation;
    private volatile boolean waiting;
    private volatile String lastState = "";

    public WakeManager(Sender sender, Listener listener) {
        this.sender = sender;
        this.listener = listener;
    }

    public void requestWake() {
        final int token = ++generation;
        waiting = true;
        lastState = "";
        listener.onWakeMessage("Waking Host…");

        scheduleAttempt(token, 0L);
        scheduleAttempt(token, 900L);
        scheduleAttempt(token, 1800L);
        main.postDelayed(() -> finishIfStillWaiting(token), 3200L);
    }

    public void onHostStatus(String state) {
        if (!waiting || state == null) return;
        lastState = state;
        if ("Host sleeping".equals(state)) return;

        final int token = generation;
        main.post(() -> {
            if (!waiting || generation != token) return;
            waiting = false;
            generation++;
            listener.onWakeMessage(resultMessage(state));
        });
    }

    public void cancel() {
        waiting = false;
        generation++;
        main.removeCallbacksAndMessages(null);
    }

    private void scheduleAttempt(final int token, long delayMs) {
        main.postDelayed(() -> {
            if (!waiting || generation != token) return;
            sender.sendWake();
        }, delayMs);
    }

    private void finishIfStillWaiting(int token) {
        if (!waiting || generation != token) return;
        waiting = false;
        generation++;
        if ("Host sleeping".equals(lastState)) {
            listener.onWakeMessage("Wake sent — Host is still sleeping");
        } else {
            listener.onWakeMessage("Wake sent — waiting for Host response");
        }
    }

    private static String resultMessage(String state) {
        if ("Host ready".equals(state)) return "Host awake";
        if ("Host locked".equals(state)) return "Host awake — locked";
        if ("Host needs capture approval".equals(state))
            return "Host responded — capture approval required";
        return "Host responded";
    }
}
