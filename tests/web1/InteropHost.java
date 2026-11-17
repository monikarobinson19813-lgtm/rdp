package com.remotephone.direct;

import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;

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
                        CryptoChannel.Message m = ch.read();
                        if (m.type != CryptoChannel.TYPE_PING) throw new IllegalStateException("expected PING");
                        ch.send(CryptoChannel.TYPE_PING, m.payload);
                    } catch (Exception expectedForNegativeCases) {
                        // F31 negative case intentionally closes before TYPE_AUTH.
                    }
                }
            }
        }
    }
}
