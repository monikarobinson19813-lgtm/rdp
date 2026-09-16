package com.remotephone.direct;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

public final class CryptoChannel implements Closeable {
    public static final byte TYPE_FRAME = 1;
    public static final byte TYPE_GESTURE = 2;
    public static final byte TYPE_NAV = 3;
    public static final byte TYPE_TEXT = 4;
    public static final byte TYPE_PING = 5;
    public static final byte TYPE_INFO = 6;
    public static final byte TYPE_AUDIO = 7;
    public static final byte TYPE_CONTROL = 8;
    public static final byte TYPE_STATUS = 9;

    public static final byte NAV_BACK = 1;
    public static final byte NAV_HOME = 2;
    public static final byte NAV_RECENTS = 3;

    public static final byte CONTROL_WAKE = 1;
    public static final byte CONTROL_AUDIO_ON = 2;
    public static final byte CONTROL_AUDIO_OFF = 3;

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final SecretKeySpec aesKey;
    private final SecureRandom random = new SecureRandom();

    private CryptoChannel(Socket socket, DataInputStream in, DataOutputStream out, byte[] sessionKey) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.aesKey = new SecretKeySpec(Arrays.copyOf(sessionKey, 32), "AES");
    }

    public static CryptoChannel accept(Socket socket, String pairingCode) throws Exception {
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(15000);
        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        byte[] baseKey = sha256(pairingCode.getBytes(StandardCharsets.UTF_8));

        byte[] magic = new byte[4];
        in.readFully(magic);
        if (!Arrays.equals(magic, new byte[]{'R','P','D','1'})) throw new SecurityException("Bad protocol");
        byte[] clientNonce = new byte[16]; in.readFully(clientNonce);
        byte[] clientMac = new byte[32]; in.readFully(clientMac);
        byte[] expected = hmac(baseKey, concat("viewer".getBytes(StandardCharsets.UTF_8), clientNonce));
        if (!MessageDigest.isEqual(clientMac, expected)) throw new SecurityException("Wrong pairing code");

        byte[] serverNonce = new byte[16]; new SecureRandom().nextBytes(serverNonce);
        byte[] serverMac = hmac(baseKey, concat("host".getBytes(StandardCharsets.UTF_8), clientNonce, serverNonce));
        out.write(serverNonce); out.write(serverMac); out.flush();

        byte[] session = hmac(baseKey, concat("session".getBytes(StandardCharsets.UTF_8), clientNonce, serverNonce));
        socket.setSoTimeout(0);
        return new CryptoChannel(socket, in, out, session);
    }

    public static CryptoChannel connect(Socket socket, String pairingCode) throws Exception {
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(15000);
        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        byte[] baseKey = sha256(pairingCode.getBytes(StandardCharsets.UTF_8));

        byte[] clientNonce = new byte[16]; new SecureRandom().nextBytes(clientNonce);
        out.write(new byte[]{'R','P','D','1'});
        out.write(clientNonce);
        out.write(hmac(baseKey, concat("viewer".getBytes(StandardCharsets.UTF_8), clientNonce)));
        out.flush();

        byte[] serverNonce = new byte[16]; in.readFully(serverNonce);
        byte[] serverMac = new byte[32]; in.readFully(serverMac);
        byte[] expected = hmac(baseKey, concat("host".getBytes(StandardCharsets.UTF_8), clientNonce, serverNonce));
        if (!MessageDigest.isEqual(serverMac, expected)) throw new SecurityException("Host authentication failed");

        byte[] session = hmac(baseKey, concat("session".getBytes(StandardCharsets.UTF_8), clientNonce, serverNonce));
        socket.setSoTimeout(0);
        return new CryptoChannel(socket, in, out, session);
    }

    public synchronized void send(byte type, byte[] payload) throws Exception {
        ByteArrayOutputStream plainBytes = new ByteArrayOutputStream(1 + payload.length);
        plainBytes.write(type); plainBytes.write(payload);
        byte[] iv = new byte[12]; random.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));
        byte[] encrypted = c.doFinal(plainBytes.toByteArray());
        int len = iv.length + encrypted.length;
        out.writeInt(len); out.write(iv); out.write(encrypted); out.flush();
    }

    public Message read() throws Exception {
        int len = in.readInt();
        if (len < 29 || len > 16 * 1024 * 1024) throw new IOException("Invalid packet size");
        byte[] iv = new byte[12]; in.readFully(iv);
        byte[] encrypted = new byte[len - 12]; in.readFully(encrypted);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));
        byte[] plain = c.doFinal(encrypted);
        if (plain.length < 1) throw new IOException("Empty packet");
        return new Message(plain[0], Arrays.copyOfRange(plain, 1, plain.length));
    }

    public static final class Message {
        public final byte type; public final byte[] payload;
        Message(byte type, byte[] payload) { this.type = type; this.payload = payload; }
    }

    @Override public void close() {
        try { socket.close(); } catch (Exception ignored) {}
    }

    private static byte[] sha256(byte[] b) throws Exception { return MessageDigest.getInstance("SHA-256").digest(b); }
    private static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256"); m.init(new SecretKeySpec(key, "HmacSHA256")); return m.doFinal(data);
    }
    private static byte[] concat(byte[]... parts) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); for (byte[] p : parts) b.write(p); return b.toByteArray();
    }
}
