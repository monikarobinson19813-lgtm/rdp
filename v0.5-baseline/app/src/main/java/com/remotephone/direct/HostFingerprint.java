package com.remotephone.direct;

import java.security.MessageDigest;

public final class HostFingerprint {
    private static final int VERIFY_HEX_CHARS = 20; // 80 bits per F31.

    private HostFingerprint() {}

    public static String verificationCode(byte[] identitySpki) throws Exception {
        if (identitySpki == null || identitySpki.length == 0) {
            throw new IllegalArgumentException("identity SPKI required");
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(identitySpki);
        StringBuilder hex = new StringBuilder(VERIFY_HEX_CHARS);
        for (int i = 0; i < VERIFY_HEX_CHARS / 2; i++) {
            hex.append(Character.forDigit((digest[i] >>> 4) & 0x0f, 16));
            hex.append(Character.forDigit(digest[i] & 0x0f, 16));
        }

        StringBuilder grouped = new StringBuilder(VERIFY_HEX_CHARS + 4);
        for (int i = 0; i < hex.length(); i += 4) {
            if (i > 0) grouped.append(' ');
            grouped.append(hex, i, Math.min(i + 4, hex.length()));
        }
        return grouped.toString();
    }
}
