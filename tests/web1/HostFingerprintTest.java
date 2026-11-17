package com.remotephone.direct;

import java.nio.charset.StandardCharsets;

public final class HostFingerprintTest {
    public static void main(String[] args) throws Exception {
        String code = HostFingerprint.verificationCode("abc".getBytes(StandardCharsets.UTF_8));
        if (!"ba78 16bf 8f01 cfea 4141".equals(code)) {
            throw new AssertionError("unexpected verification code: " + code);
        }
        if (!code.matches("[0-9a-f]{4}( [0-9a-f]{4}){4}")) {
            throw new AssertionError("verification code format mismatch");
        }
        try {
            HostFingerprint.verificationCode(new byte[0]);
            throw new AssertionError("empty SPKI should fail");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        System.out.println("WEB1 F31 Host fingerprint PASS");
    }
}
