package com.remotephone.direct;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
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

    private static final byte TYPE_AUTH = 100;
    private static final byte TYPE_AUTH_OK = 101;
    private static final byte TYPE_AUTH_FAIL = 102;
    private static final byte[] MAGIC_V2 = new byte[]{'R','P','D','2'};
    private static final int MAX_HANDSHAKE_BLOB = 8192;

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final SecretKeySpec aesKey;
    private final SecureRandom random = new SecureRandom();
    private final String peerFingerprint;

    private CryptoChannel(Socket socket, DataInputStream in, DataOutputStream out, byte[] sessionKey, String peerFingerprint) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.aesKey = new SecretKeySpec(Arrays.copyOf(sessionKey, 32), "AES");
        this.peerFingerprint = peerFingerprint == null ? "" : peerFingerprint;
    }

    /** Host side. Uses the persistent Android Keystore identity. */
    public static CryptoChannel accept(Socket socket, String pairingCode) throws Exception {
        return accept(socket, pairingCode, HostIdentity.getOrCreate());
    }

    public static CryptoChannel accept(Socket socket, String pairingCode, KeyPair identity) throws Exception {
        if (pairingCode == null || pairingCode.length() != 6) throw new SecurityException("Invalid session PIN");
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(15000);
        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

        byte[] magic = new byte[4];
        in.readFully(magic);
        if (!Arrays.equals(magic, MAGIC_V2)) throw new SecurityException("Unsupported protocol");

        byte[] clientNonce = new byte[16];
        in.readFully(clientNonce);
        byte[] clientEphemeralBytes = readBlob(in);
        PublicKey clientEphemeral = decodeEcPublic(clientEphemeralBytes);

        KeyPair hostEphemeral = generateEphemeralEc();
        byte[] hostEphemeralBytes = hostEphemeral.getPublic().getEncoded();
        byte[] identityBytes = identity.getPublic().getEncoded();
        byte[] serverNonce = new byte[16];
        new SecureRandom().nextBytes(serverNonce);

        byte[] transcript = concat(clientNonce, serverNonce, clientEphemeralBytes, hostEphemeralBytes);
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(identity.getPrivate());
        signer.update(transcript);
        byte[] signature = signer.sign();

        writeBlob(out, identityBytes);
        writeBlob(out, hostEphemeralBytes);
        out.write(serverNonce);
        writeBlob(out, signature);
        out.flush();

        byte[] shared = ecdh(hostEphemeral.getPrivate(), clientEphemeral);
        byte[] session = deriveSession(shared, transcript);
        String fingerprint = fingerprint(identityBytes);
        CryptoChannel ch = new CryptoChannel(socket, in, out, session, fingerprint);

        Message auth = ch.read();
        if (auth.type != TYPE_AUTH || !MessageDigest.isEqual(
                auth.payload,
                pairingCode.getBytes(StandardCharsets.UTF_8))) {
            try { ch.send(TYPE_AUTH_FAIL, new byte[0]); } catch (Exception ignored) {}
            ch.close();
            throw new SecurityException("Wrong session PIN");
        }
        ch.send(TYPE_AUTH_OK, new byte[0]);
        socket.setSoTimeout(0);
        return ch;
    }

    /** Controller side. Pass a saved Host fingerprint to detect Host identity changes. */
    public static CryptoChannel connect(Socket socket, String pairingCode) throws Exception {
        return connect(socket, pairingCode, null);
    }

    public static CryptoChannel connect(Socket socket, String pairingCode, String expectedHostFingerprint) throws Exception {
        if (pairingCode == null || pairingCode.length() != 6) throw new SecurityException("Invalid session PIN");
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(15000);
        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

        KeyPair clientEphemeral = generateEphemeralEc();
        byte[] clientEphemeralBytes = clientEphemeral.getPublic().getEncoded();
        byte[] clientNonce = new byte[16];
        new SecureRandom().nextBytes(clientNonce);

        out.write(MAGIC_V2);
        out.write(clientNonce);
        writeBlob(out, clientEphemeralBytes);
        out.flush();

        byte[] identityBytes = readBlob(in);
        byte[] hostEphemeralBytes = readBlob(in);
        byte[] serverNonce = new byte[16];
        in.readFully(serverNonce);
        byte[] signature = readBlob(in);

        PublicKey hostIdentity = decodeEcPublic(identityBytes);
        PublicKey hostEphemeral = decodeEcPublic(hostEphemeralBytes);
        String actualFingerprint = fingerprint(identityBytes);
        if (expectedHostFingerprint != null && !expectedHostFingerprint.trim().isEmpty() &&
                !constantTimeTextEquals(normalizeFingerprint(expectedHostFingerprint), normalizeFingerprint(actualFingerprint))) {
            throw new SecurityException("Host identity changed. Remove and re-add this Host only if you intentionally reinstalled/reset it.");
        }

        byte[] transcript = concat(clientNonce, serverNonce, clientEphemeralBytes, hostEphemeralBytes);
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(hostIdentity);
        verifier.update(transcript);
        if (!verifier.verify(signature)) throw new SecurityException("Host identity verification failed");

        byte[] shared = ecdh(clientEphemeral.getPrivate(), hostEphemeral);
        byte[] session = deriveSession(shared, transcript);
        CryptoChannel ch = new CryptoChannel(socket, in, out, session, actualFingerprint);
        ch.send(TYPE_AUTH, pairingCode.getBytes(StandardCharsets.UTF_8));
        Message authResult = ch.read();
        if (authResult.type != TYPE_AUTH_OK) {
            ch.close();
            throw new SecurityException("Wrong session PIN");
        }
        socket.setSoTimeout(0);
        return ch;
    }

    public String peerFingerprint() { return peerFingerprint; }

    public synchronized void send(byte type, byte[] payload) throws Exception {
        if (payload == null) payload = new byte[0];
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

    private static KeyPair generateEphemeralEc() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        return gen.generateKeyPair();
    }

    private static PublicKey decodeEcPublic(byte[] encoded) throws Exception {
        KeyFactory f = KeyFactory.getInstance("EC");
        return f.generatePublic(new X509EncodedKeySpec(encoded));
    }

    private static byte[] ecdh(PrivateKey privateKey, PublicKey publicKey) throws Exception {
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(privateKey);
        agreement.doPhase(publicKey, true);
        return agreement.generateSecret();
    }

    private static byte[] deriveSession(byte[] shared, byte[] transcript) throws Exception {
        byte[] salt = sha256(concat("RemotePhone-v0.3".getBytes(StandardCharsets.UTF_8), transcript));
        byte[] prk = hmac(salt, shared);
        return hmac(prk, concat("session-key".getBytes(StandardCharsets.UTF_8), transcript));
    }

    private static void writeBlob(DataOutputStream out, byte[] b) throws IOException {
        if (b == null || b.length < 1 || b.length > MAX_HANDSHAKE_BLOB) throw new IOException("Invalid handshake blob");
        out.writeInt(b.length);
        out.write(b);
    }

    private static byte[] readBlob(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 1 || n > MAX_HANDSHAKE_BLOB) throw new IOException("Invalid handshake blob size");
        byte[] b = new byte[n];
        in.readFully(b);
        return b;
    }

    private static String fingerprint(byte[] publicKey) throws Exception {
        byte[] d = sha256(publicKey);
        StringBuilder s = new StringBuilder(d.length * 2);
        for (byte b : d) s.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
        return s.toString();
    }

    private static String normalizeFingerprint(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.US).replaceAll("[^0-9a-f]", "");
    }

    private static boolean constantTimeTextEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] sha256(byte[] b) throws Exception { return MessageDigest.getInstance("SHA-256").digest(b); }
    private static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256"); m.init(new SecretKeySpec(key, "HmacSHA256")); return m.doFinal(data);
    }
    private static byte[] concat(byte[]... parts) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); for (byte[] p : parts) b.write(p); return b.toByteArray();
    }
}
