package com.remotephone.direct;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;

public final class InteropHost {
    private static final String TEST_PIN = "314159"; // synthetic CI-only value

    public static void main(String[] args) throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair identity = gen.generateKeyPair();
        try (ServerSocket server = new ServerSocket(0)) {
            System.out.println("PORT=" + server.getLocalPort());
            System.out.flush();
            while (true) {
                try (Socket socket = server.accept()) {
                    try (CryptoChannel ch = CryptoChannel.accept(socket, TEST_PIN, identity)) {
                        CryptoChannel.Message first = ch.read();
                        if (first.type == CryptoChannel.TYPE_PING) {
                            ch.send(CryptoChannel.TYPE_PING, first.payload);
                        } else if (first.type == CryptoChannel.TYPE_GESTURE) {
                            verifyPayloadInterop(ch, first);
                        } else {
                            throw new IllegalStateException("unexpected first message type " + first.type);
                        }
                    } catch (Exception expectedForNegativeCases) {
                        // F31 negative case intentionally closes before TYPE_AUTH.
                    }
                }
            }
        }
    }

    private static void verifyPayloadInterop(CryptoChannel ch, CryptoChannel.Message gesture) throws Exception {
        ByteBuffer g = ByteBuffer.wrap(gesture.payload);
        check(g.remaining() == 24, "gesture length");
        check(g.getFloat() == 0.5f, "gesture x1");
        check(g.getFloat() == 0.25f, "gesture y1");
        check(g.getFloat() == 0.75f, "gesture x2");
        check(g.getFloat() == 0.9f, "gesture y2");
        check(g.getLong() == 123L, "gesture duration");

        CryptoChannel.Message nav = ch.read();
        check(nav.type == CryptoChannel.TYPE_NAV, "nav type");
        check(nav.payload.length == 1 && nav.payload[0] == CryptoChannel.NAV_HOME, "nav payload");

        CryptoChannel.Message text = ch.read();
        check(text.type == CryptoChannel.TYPE_TEXT, "text type");
        check("WEB1 payload ✓".equals(new String(text.payload, StandardCharsets.UTF_8)), "text payload");

        CryptoChannel.Message unlock = ch.read();
        check(unlock.type == CryptoChannel.TYPE_UNLOCK, "unlock type");
        check(unlock.payload.length == 13, "unlock length");
        check(unlock.payload[0] == CryptoChannel.UNLOCK_PIN, "unlock method");
        for (int i = 0; i < 8; i++) check(unlock.payload[1 + i] == (byte)(i + 1), "unlock nonce");
        byte[] credential = Arrays.copyOfRange(unlock.payload, 9, unlock.payload.length);
        check(Arrays.equals(credential, "2468".getBytes(StandardCharsets.UTF_8)), "unlock credential");

        CryptoChannel.Message control = ch.read();
        check(control.type == CryptoChannel.TYPE_CONTROL, "control type");
        check(control.payload.length == 1 && control.payload[0] == CryptoChannel.CONTROL_WAKE, "wake payload");

        CryptoChannel.Message quality = ch.read();
        check(quality.type == CryptoChannel.TYPE_STREAM_QUALITY, "quality type");
        check(quality.payload.length == 1 && quality.payload[0] == CryptoChannel.STREAM_QUALITY_HD, "quality payload");

        byte[] jpeg = new byte[]{(byte)0xff, (byte)0xd8, 0x01, 0x23, (byte)0xff, (byte)0xd9};
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(720);
        out.writeInt(1600);
        out.writeLong(123456789L);
        out.writeInt(jpeg.length);
        out.write(jpeg);
        out.flush();
        ch.send(CryptoChannel.TYPE_FRAME, bytes.toByteArray());
    }

    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError("F40 " + label + " mismatch");
    }
}
