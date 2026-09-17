package com.remotephone.direct;

import java.io.Closeable;
import java.io.IOException;
import java.security.KeyPair;

/**
 * Independent encrypted relay link used for lightweight Host control traffic.
 * It deliberately carries no video/audio frames so heartbeat, status and Wake Host
 * can recover independently from the primary remote-screen stream.
 */
public final class ControlLink implements Closeable {
    public interface Listener {
        void onConnected(String peerFingerprint);
        void onMessage(CryptoChannel.Message message);
        void onDisconnected();
    }

    private final boolean hostSide;
    private final String remoteId;
    private final String pairingCode;
    private final String hostToken;
    private final KeyPair hostIdentity;
    private final String expectedHostFingerprint;
    private final Listener listener;

    private volatile boolean closed;
    private volatile CryptoChannel channel;
    private volatile RelaySocket relaySocket;
    private Thread worker;

    private ControlLink(boolean hostSide,
                        String remoteId,
                        String pairingCode,
                        String hostToken,
                        KeyPair hostIdentity,
                        String expectedHostFingerprint,
                        Listener listener) {
        this.hostSide = hostSide;
        this.remoteId = remoteId;
        this.pairingCode = pairingCode;
        this.hostToken = hostToken;
        this.hostIdentity = hostIdentity;
        this.expectedHostFingerprint = expectedHostFingerprint;
        this.listener = listener;
    }

    public static ControlLink forHost(String remoteId,
                                      String pairingCode,
                                      String hostToken,
                                      KeyPair hostIdentity,
                                      Listener listener) {
        return new ControlLink(true, remoteId, pairingCode, hostToken,
                hostIdentity, null, listener);
    }

    public static ControlLink forController(String remoteId,
                                            String pairingCode,
                                            String expectedHostFingerprint,
                                            Listener listener) {
        return new ControlLink(false, remoteId, pairingCode, null,
                null, expectedHostFingerprint, listener);
    }

    public synchronized void start() {
        if (worker != null || closed) return;
        worker = new Thread(this::runLoop,
                hostSide ? "remotephone-host-control" : "remotephone-controller-control");
        worker.start();
    }

    public boolean isConnected() {
        return !closed && channel != null;
    }

    public void send(byte type, byte[] payload) throws Exception {
        CryptoChannel c = channel;
        if (closed || c == null) throw new IOException("Control link is not connected");
        c.send(type, payload == null ? new byte[0] : payload);
    }

    /** Break only the active transport. The worker remains alive and reconnects. */
    public void reset() {
        CryptoChannel c = channel;
        channel = null;
        if (c != null) c.close();
        RelaySocket rs = relaySocket;
        relaySocket = null;
        if (rs != null) rs.close();
    }

    private void runLoop() {
        long retryDelayMs = 1000L;
        while (!closed) {
            RelaySocket rs = null;
            CryptoChannel c = null;
            boolean established = false;
            try {
                rs = hostSide
                        ? RelaySocket.connectHostControl(remoteId, hostToken)
                        : RelaySocket.connectControllerControl(remoteId);
                if (closed) break;
                relaySocket = rs;

                c = hostSide
                        ? CryptoChannel.accept(rs, pairingCode, hostIdentity)
                        : CryptoChannel.connect(rs, pairingCode, expectedHostFingerprint);
                if (closed) break;
                channel = c;
                established = true;
                retryDelayMs = 1000L;
                if (listener != null) listener.onConnected(c.peerFingerprint());

                while (!closed && channel == c) {
                    CryptoChannel.Message message = c.read();
                    if (listener != null) listener.onMessage(message);
                }
            } catch (Exception ignored) {
                // The loop owns recovery. A failed/stale control transport is discarded.
            } finally {
                if (channel == c) channel = null;
                if (relaySocket == rs) relaySocket = null;
                if (c != null) c.close();
                if (rs != null) rs.close();
                if (listener != null && established) listener.onDisconnected();
            }

            if (!closed) {
                sleepQuietly(retryDelayMs);
                if (!established) retryDelayMs = Math.min(10000L, retryDelayMs * 2L);
            }
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        reset();
        Thread t = worker;
        if (t != null) t.interrupt();
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
