package com.remotephone.direct;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Controller-side known-credential unlock helper. Device credentials are never persisted. */
public final class LockScreenManager {
    public interface Sender { void send(byte[] payload); }
    public interface StatusSink { void show(String message); }

    private final Sender sender;
    private final StatusSink status;

    public LockScreenManager(Sender sender, StatusSink status) {
        this.sender = sender;
        this.status = status;
    }

    public void requestPin(String pin) {
        String value = pin == null ? "" : pin.trim();
        if (value.length() < 4 || value.length() > 16) {
            show("Enter the Host device PIN (4–16 digits)");
            return;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                show("Host device PIN must contain digits only");
                return;
            }
        }
        send(CryptoChannel.UNLOCK_PIN, value, "PIN unlock sent — waiting for Host state…");
    }

    public void requestPattern(String pattern) {
        String value = pattern == null ? "" : pattern.replaceAll("[^1-9]", "");
        if (value.length() < 4 || value.length() > 9) {
            show("Enter the known pattern as 4–9 unique points, e.g. 1-2-5-8");
            return;
        }
        boolean[] used = new boolean[10];
        for (int i = 0; i < value.length(); i++) {
            int n = value.charAt(i) - '0';
            if (used[n]) {
                show("Pattern points cannot repeat");
                return;
            }
            used[n] = true;
        }
        send(CryptoChannel.UNLOCK_PATTERN, value, "Pattern unlock sent — waiting for Host state…");
    }

    public void onResult(byte[] payload) {
        if (payload == null || payload.length < 1) {
            show("Host did not return an unlock result");
            return;
        }
        switch (payload[0]) {
            case CryptoChannel.UNLOCK_RESULT_ACCEPTED: show("Unlock attempt accepted — checking Host…"); break;
            case CryptoChannel.UNLOCK_RESULT_UNSUPPORTED: show("Android/OEM did not expose secure lock controls remotely"); break;
            case CryptoChannel.UNLOCK_RESULT_NOT_LOCKED: show("Host is no longer locked"); break;
            default: show("Unlock request was rejected"); break;
        }
    }

    public void showTransportUnavailable() { show("Host connection is unavailable — reconnect before unlocking"); }
    public void showNotLocked() { show("Host is not currently reporting a secure lock"); }

    private void send(byte method, String credential, String successMessage) {
        byte[] secret = credential.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[secret.length + 1];
        payload[0] = method;
        System.arraycopy(secret, 0, payload, 1, secret.length);
        Arrays.fill(secret, (byte)0);
        try {
            sender.send(payload);
            show(successMessage);
        } finally {
            Arrays.fill(payload, (byte)0);
        }
    }

    private void show(String message) { if (status != null) status.show(message); }
}
