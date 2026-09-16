package com.remotephone.direct;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public final class RelaySocket extends Socket {
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build();

    private final PipedInputStream input;
    private final PipedOutputStream incoming;
    private final OutputStream output;
    private final CountDownLatch opened = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile WebSocket webSocket;
    private volatile IOException openFailure;

    private RelaySocket(String url, String role, String hostToken) throws IOException {
        input = new PipedInputStream(4 * 1024 * 1024);
        incoming = new PipedOutputStream(input);
        output = new OutputStream() {
            @Override public void write(int b) throws IOException {
                write(new byte[]{(byte)b}, 0, 1);
            }

            @Override public void write(byte[] b, int off, int len) throws IOException {
                if (closed.get()) throw new EOFException("Relay socket closed");
                if (len <= 0) return;
                WebSocket ws = webSocket;
                if (ws == null || !ws.send(ByteString.of(b, off, len)))
                    throw new IOException("Relay send failed");
            }

            @Override public void flush() {}

            @Override public void close() { RelaySocket.this.close(); }
        };

        Request.Builder request = new Request.Builder()
                .url(url)
                .header("X-RemotePhone-Role", role);
        if (hostToken != null && !hostToken.isEmpty())
            request.header("X-RemotePhone-Host-Token", hostToken);

        webSocket = CLIENT.newWebSocket(request.build(), new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) {
                opened.countDown();
            }

            @Override public void onMessage(WebSocket ws, ByteString bytes) {
                if (closed.get()) return;
                try {
                    byte[] b = bytes.toByteArray();
                    incoming.write(b);
                    incoming.flush();
                } catch (IOException e) {
                    closeInternal();
                }
            }

            @Override public void onClosing(WebSocket ws, int code, String reason) {
                try { ws.close(code, reason); } catch (Exception ignored) {}
                closeInternal();
            }

            @Override public void onClosed(WebSocket ws, int code, String reason) {
                closeInternal();
            }

            @Override public void onFailure(WebSocket ws, Throwable t, Response response) {
                String detail = t == null ? "Relay connection failed" : safeMessage(t);
                if (response != null) {
                    int code = response.code();
                    if (code == 404) detail = "Host offline";
                    else if (code == 409) detail = "Host busy";
                    else if (code == 401 || code == 403) detail = "Host relay authorization failed";
                    else detail = "Relay HTTP " + code + ": " + detail;
                }
                openFailure = new IOException(detail, t);
                opened.countDown();
                closeInternal();
            }
        });

        try {
            if (!opened.await(15, TimeUnit.SECONDS)) {
                close();
                throw new SocketException("Timed out connecting to internet relay");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close();
            throw new SocketException("Interrupted while connecting to internet relay");
        }
        if (openFailure != null) throw openFailure;
        if (closed.get()) throw new SocketException("Relay closed during connection");
    }

    public static RelaySocket connectController(String remoteId) throws IOException {
        return new RelaySocket(RelayConfig.webSocketUrl(remoteId), "controller", null);
    }

    public static RelaySocket connectHost(String remoteId, String hostToken) throws IOException {
        return new RelaySocket(RelayConfig.webSocketUrl(remoteId), "host", hostToken);
    }

    @Override public InputStream getInputStream() throws IOException {
        if (closed.get()) throw new SocketException("Relay socket closed");
        return input;
    }

    @Override public OutputStream getOutputStream() throws IOException {
        if (closed.get()) throw new SocketException("Relay socket closed");
        return output;
    }

    @Override public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        try { if (webSocket != null) webSocket.close(1000, "session closed"); } catch (Exception ignored) {}
        try { incoming.close(); } catch (Exception ignored) {}
        try { input.close(); } catch (Exception ignored) {}
    }

    private void closeInternal() {
        if (!closed.compareAndSet(false, true)) return;
        opened.countDown();
        try { incoming.close(); } catch (Exception ignored) {}
        try { input.close(); } catch (Exception ignored) {}
    }

    @Override public boolean isClosed() { return closed.get(); }
    @Override public void setTcpNoDelay(boolean on) {}
    @Override public void setSoTimeout(int timeout) {}

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
