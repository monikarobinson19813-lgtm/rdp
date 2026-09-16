package com.remotephone.direct;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.display.*;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.*;
import android.os.*;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class HostService extends Service {
    public static final String ACTION_STOP = "com.remotephone.direct.STOP";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    public static final String EXTRA_CODE = "pairingCode";
    private static final int PORT = 49200;
    private static final int NOTIF = 22;
    private static volatile boolean running;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader reader;
    private ServerSocket server;
    private volatile RelaySocket relaySocket;
    private volatile CryptoChannel channel;
    private volatile String pairingCode;
    private volatile String remoteId;
    private KeyPair hostIdentity;

    private ExecutorService acceptExecutor, relayExecutor, encodeExecutor, audioExecutor;
    private final AtomicBoolean encodeBusy = new AtomicBoolean(false);
    private volatile long lastFrameAt;
    private volatile int physicalWidth, physicalHeight, streamWidth, streamHeight, streamDensity;
    private PowerManager.WakeLock cpuWakeLock;
    private BroadcastReceiver screenReceiver;
    private DisplayManager displayManager;
    private DisplayManager.DisplayListener displayListener;
    private AudioRecord audioRecord;
    private volatile boolean audioEnabled;

    public static boolean isRunning() { return running; }
    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_STICKY;
        if (intent == null) {
            postNeedsApprovalNotification();
            stopSelf();
            return START_NOT_STICKY;
        }

        pairingCode = intent.getStringExtra(EXTRA_CODE);
        if (pairingCode == null || pairingCode.length() != 6)
            pairingCode = HostConfig.getOrCreateSessionPin(this);
        remoteId = intent.getStringExtra("remotephone.remote_id");
        if (remoteId == null || remoteId.length() != 9)
            remoteId = HostConfig.getOrCreateRemoteId(this);

        Intent data = (Intent) intent.getParcelableExtra(EXTRA_RESULT_DATA);
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        if (data == null || resultCode != Activity.RESULT_OK) return START_NOT_STICKY;

        try {
            hostIdentity = HostIdentity.getOrCreate();
        } catch (Exception e) {
            Toast.makeText(this, "Unable to create Host security identity", Toast.LENGTH_LONG).show();
            return START_NOT_STICKY;
        }

        createNotificationChannel();
        startForeground(NOTIF, new Notification.Builder(this, "remotephone_host")
                .setContentTitle("RemotePhone Host is ready")
                .setContentText(RelayConfig.isConfigured() ? "Internet Remote ID: " + formatRemoteId(remoteId) : "Local testing ready; internet relay not configured")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true).build());

        running = true;
        acquireCpuWakeLock();
        registerScreenStateReceiver();
        startProjection(resultCode, data);
        startDisplayWatcher();
        startAudioCapture();
        startLocalServer();
        if (RelayConfig.isConfigured()) startRelayLoop();
        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager n = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            n.createNotificationChannel(new NotificationChannel("remotephone_host", "RemotePhone Host", NotificationManager.IMPORTANCE_LOW));
        }
    }

    private void postNeedsApprovalNotification() {
        try {
            createNotificationChannel();
            Intent open = new Intent(this, HostActivity.class);
            PendingIntent pi = PendingIntent.getActivity(this, 101, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, "remotephone_host")
                    : new Notification.Builder(this);
            b.setContentTitle("RemotePhone Host needs approval")
                    .setContentText("Open Host and approve screen capture again")
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF + 1, b.build());
        } catch (Exception ignored) {}
    }

    private void acquireCpuWakeLock() {
        try {
            PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
            cpuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RemotePhone:HostCpu");
            cpuWakeLock.setReferenceCounted(false);
            cpuWakeLock.acquire();
        } catch (Exception ignored) {}
    }

    private void registerScreenStateReceiver() {
        screenReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { sendHostStatus(); }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(screenReceiver, f);
        } catch (Exception ignored) {}
    }

    private String hostState() {
        try {
            PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
            if (!pm.isInteractive()) return "Host sleeping";
            KeyguardManager km = (KeyguardManager)getSystemService(KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardLocked()) return "Host locked";
        } catch (Exception ignored) {}
        return "Host ready";
    }

    private void sendHostStatus() {
        CryptoChannel c = channel;
        if (c == null) return;
        try { c.send(CryptoChannel.TYPE_STATUS, hostState().getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { closeChannel(c); }
    }

    private void wakeHost() {
        try {
            PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK |
                    PowerManager.ACQUIRE_CAUSES_WAKEUP |
                    PowerManager.ON_AFTER_RELEASE,
                    "RemotePhone:WakeHost");
            wl.acquire(5000);
        } catch (Exception ignored) {}
        new Handler(Looper.getMainLooper()).postDelayed(this::sendHostStatus, 700);
    }

    private void startProjection(int resultCode, Intent data) {
        MediaProjectionManager m = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = m.getMediaProjection(resultCode, data);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopSelf(); }
        }, new Handler(Looper.getMainLooper()));
        encodeExecutor = Executors.newSingleThreadExecutor();
        configureDisplayCapture();
    }

    private synchronized void configureDisplayCapture() {
        if (projection == null) return;
        WindowManager wm = (WindowManager)getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        int newPhysicalWidth = dm.widthPixels;
        int newPhysicalHeight = dm.heightPixels;
        int newStreamWidth = Math.min(600, newPhysicalWidth);
        int newStreamHeight = Math.max(2, (int)Math.round((double)newPhysicalHeight * newStreamWidth / newPhysicalWidth));
        if ((newStreamHeight & 1) == 1) newStreamHeight--;
        int newDensity = Math.max(160, (int)(dm.densityDpi * ((double)newStreamWidth / newPhysicalWidth)));

        physicalWidth = newPhysicalWidth;
        physicalHeight = newPhysicalHeight;
        streamWidth = newStreamWidth;
        streamHeight = newStreamHeight;
        streamDensity = newDensity;

        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}

        final int captureW = streamWidth;
        final int captureH = streamHeight;
        reader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(r -> {
            long now = SystemClock.elapsedRealtime();
            if (now - lastFrameAt < 125 || !encodeBusy.compareAndSet(false, true)) {
                Image skip = r.acquireLatestImage();
                if (skip != null) skip.close();
                return;
            }
            Image img = r.acquireLatestImage();
            if (img == null) { encodeBusy.set(false); return; }
            lastFrameAt = now;
            encodeExecutor.execute(() -> {
                try { encodeAndSend(img, captureW, captureH); }
                finally {
                    try { img.close(); } catch(Exception ignored) {}
                    encodeBusy.set(false);
                }
            });
        }, new Handler(Looper.getMainLooper()));

        virtualDisplay = projection.createVirtualDisplay("RemotePhone",
                captureW, captureH, streamDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, null);
        CryptoChannel c = channel;
        if (c != null) sendInfo(c);
    }

    private void startDisplayWatcher() {
        try {
            displayManager = (DisplayManager)getSystemService(DISPLAY_SERVICE);
            displayListener = new DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int displayId) {}
                @Override public void onDisplayRemoved(int displayId) {}
                @Override public void onDisplayChanged(int displayId) {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try {
                            WindowManager wm = (WindowManager)getSystemService(WINDOW_SERVICE);
                            DisplayMetrics dm = new DisplayMetrics();
                            wm.getDefaultDisplay().getRealMetrics(dm);
                            if (dm.widthPixels != physicalWidth || dm.heightPixels != physicalHeight)
                                configureDisplayCapture();
                        } catch (Exception ignored) {}
                    }, 250);
                }
            };
            displayManager.registerDisplayListener(displayListener, new Handler(Looper.getMainLooper()));
        } catch (Exception ignored) {}
    }

    private void encodeAndSend(Image image, int w, int h) {
        CryptoChannel c = channel;
        if (c == null) return;
        try {
            Image.Plane p = image.getPlanes()[0];
            ByteBuffer buf = p.getBuffer();
            int pixelStride = p.getPixelStride();
            int rowStride = p.getRowStride();
            int rowPadding = rowStride - pixelStride * w;
            Bitmap padded = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buf);
            Bitmap frame = Bitmap.createBitmap(padded, 0, 0, w, h);
            if (frame != padded) padded.recycle();
            ByteArrayOutputStream jpg = new ByteArrayOutputStream(120_000);
            frame.compress(Bitmap.CompressFormat.JPEG, 45, jpg);
            frame.recycle();
            byte[] jpeg = jpg.toByteArray();
            ByteArrayOutputStream payload = new ByteArrayOutputStream(jpeg.length + 20);
            DataOutputStream d = new DataOutputStream(payload);
            d.writeInt(w); d.writeInt(h); d.writeLong(System.currentTimeMillis()); d.writeInt(jpeg.length); d.write(jpeg); d.flush();
            c.send(CryptoChannel.TYPE_FRAME, payload.toByteArray());
        } catch (Exception e) { closeChannel(c); }
    }

    private void startAudioCapture() {
        if (Build.VERSION.SDK_INT < 29 || projection == null) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        try {
            android.media.AudioPlaybackCaptureConfiguration config =
                    new android.media.AudioPlaybackCaptureConfiguration.Builder(projection)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .build();
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(48000)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();
            int min = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufferSize = Math.max(8192, min * 2);
            audioRecord = new AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioPlaybackCaptureConfig(config)
                    .build();
            audioRecord.startRecording();
            audioExecutor = Executors.newSingleThreadExecutor();
            audioExecutor.execute(() -> {
                byte[] buf = new byte[3840];
                while (running && audioRecord != null) {
                    int n;
                    try { n = audioRecord.read(buf, 0, buf.length); }
                    catch (Exception e) { break; }
                    if (n <= 0 || !audioEnabled) continue;
                    CryptoChannel c = channel;
                    if (c == null) continue;
                    try {
                        ByteArrayOutputStream b = new ByteArrayOutputStream(n + 8);
                        DataOutputStream d = new DataOutputStream(b);
                        d.writeInt(48000); d.writeInt(1); d.write(buf, 0, n); d.flush();
                        c.send(CryptoChannel.TYPE_AUDIO, b.toByteArray());
                    } catch (Exception e) { closeChannel(c); }
                }
            });
        } catch (Exception ignored) {
            try { if (audioRecord != null) audioRecord.release(); } catch (Exception ignored2) {}
            audioRecord = null;
        }
    }

    private void startLocalServer() {
        acceptExecutor = Executors.newSingleThreadExecutor();
        acceptExecutor.execute(() -> {
            try {
                server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(PORT));
                while (running && !server.isClosed()) {
                    Socket s = server.accept();
                    CryptoChannel candidate = null;
                    try {
                        candidate = CryptoChannel.accept(s, pairingCode, hostIdentity);
                        runSession(candidate);
                    } catch (Exception e) {
                        if (candidate != null) candidate.close();
                        else try { s.close(); } catch(Exception ignored) {}
                        sleepQuietly(800);
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    private void startRelayLoop() {
        relayExecutor = Executors.newSingleThreadExecutor();
        relayExecutor.execute(() -> {
            while (running && RelayConfig.isConfigured()) {
                RelaySocket rs = null;
                CryptoChannel candidate = null;
                try {
                    rs = RelaySocket.connectHost(remoteId, HostConfig.getOrCreateRelayToken(this));
                    relaySocket = rs;
                    candidate = CryptoChannel.accept(rs, pairingCode, hostIdentity);
                    runSession(candidate);
                } catch (Exception ignored) {
                    if (candidate != null) candidate.close();
                    else if (rs != null) rs.close();
                } finally {
                    if (relaySocket == rs) relaySocket = null;
                }
                if (running) sleepQuietly(1500);
            }
        });
    }

    private void runSession(CryptoChannel candidate) throws Exception {
        CryptoChannel old = channel;
        channel = candidate;
        if (old != null && old != candidate) old.close();
        audioEnabled = false;
        try {
            sendInfo(candidate);
            sendHostStatus();
            readCommands(candidate);
        } finally {
            if (channel == candidate) channel = null;
            audioEnabled = false;
            candidate.close();
        }
    }

    private void sendInfo(CryptoChannel c) {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(b);
            d.writeInt(physicalWidth); d.writeInt(physicalHeight); d.writeInt(streamWidth); d.writeInt(streamHeight); d.flush();
            c.send(CryptoChannel.TYPE_INFO, b.toByteArray());
        } catch(Exception ignored) {}
    }

    private void readCommands(CryptoChannel c) throws Exception {
        while (running && c == channel) {
            CryptoChannel.Message m = c.read();
            if (m.type == CryptoChannel.TYPE_GESTURE) handleGesture(m.payload);
            else if (m.type == CryptoChannel.TYPE_NAV) handleNav(m.payload);
            else if (m.type == CryptoChannel.TYPE_TEXT) handleText(m.payload);
            else if (m.type == CryptoChannel.TYPE_CONTROL) handleControl(m.payload);
            else if (m.type == CryptoChannel.TYPE_PING) c.send(CryptoChannel.TYPE_PING, new byte[0]);
        }
    }

    private void handleControl(byte[] p) {
        if (p == null || p.length < 1) return;
        if (p[0] == CryptoChannel.CONTROL_WAKE) wakeHost();
        else if (p[0] == CryptoChannel.CONTROL_AUDIO_ON) audioEnabled = true;
        else if (p[0] == CryptoChannel.CONTROL_AUDIO_OFF) audioEnabled = false;
    }

    private void handleGesture(byte[] p) throws Exception {
        DataInputStream d = new DataInputStream(new ByteArrayInputStream(p));
        float x1=d.readFloat(), y1=d.readFloat(), x2=d.readFloat(), y2=d.readFloat();
        long duration=d.readLong();
        RemoteAccessibilityService.gesture(x1*physicalWidth, y1*physicalHeight, x2*physicalWidth, y2*physicalHeight, duration);
    }

    private void handleNav(byte[] p) {
        if (p.length < 1) return;
        int action = p[0] == CryptoChannel.NAV_BACK
                ? android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                : p[0] == CryptoChannel.NAV_HOME
                ? android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                : android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS;
        RemoteAccessibilityService.global(action);
    }

    private void handleText(byte[] p) {
        try { RemoteAccessibilityService.setFocusedText(new String(p, StandardCharsets.UTF_8)); }
        catch(Exception ignored) {}
    }

    private void closeChannel(CryptoChannel c) {
        if (channel == c) channel = null;
        c.close();
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String formatRemoteId(String id) {
        if (id == null || id.length() != 9) return id;
        return id.substring(0,3) + " " + id.substring(3,6) + " " + id.substring(6,9);
    }

    @Override public void onDestroy() {
        running = false;
        audioEnabled = false;
        CryptoChannel c = channel;
        channel = null;
        if (c != null) c.close();
        RelaySocket rs = relaySocket;
        relaySocket = null;
        if (rs != null) rs.close();
        try { if (server != null) server.close(); } catch(Exception ignored) {}
        try { if (displayManager != null && displayListener != null) displayManager.unregisterDisplayListener(displayListener); } catch(Exception ignored) {}
        try { if (screenReceiver != null) unregisterReceiver(screenReceiver); } catch(Exception ignored) {}
        try { if (audioRecord != null) { audioRecord.stop(); audioRecord.release(); } } catch(Exception ignored) {}
        audioRecord = null;
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch(Exception ignored) {}
        try { if (reader != null) reader.close(); } catch(Exception ignored) {}
        try { if (projection != null) projection.stop(); } catch(Exception ignored) {}
        try { if (cpuWakeLock != null && cpuWakeLock.isHeld()) cpuWakeLock.release(); } catch(Exception ignored) {}
        if (acceptExecutor != null) acceptExecutor.shutdownNow();
        if (relayExecutor != null) relayExecutor.shutdownNow();
        if (encodeExecutor != null) encodeExecutor.shutdownNow();
        if (audioExecutor != null) audioExecutor.shutdownNow();
        super.onDestroy();
    }
}
