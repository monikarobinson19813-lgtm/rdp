package com.remotephone.direct;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
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
import android.widget.Toast;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class HostService extends Service {
    public static final String ACTION_STOP = "com.remotephone.direct.STOP";
    public static final String ACTION_RECOVER = "com.remotephone.direct.RECOVER";
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
    private volatile String relayToken;
    private KeyPair hostIdentity;

    private ExecutorService acceptExecutor, relayExecutor, encodeExecutor, audioExecutor;
    private ScheduledExecutorService recoveryExecutor;
    private final AtomicBoolean relayLoopActive = new AtomicBoolean(false);
    private final AtomicBoolean localSessionActive = new AtomicBoolean(false);
    private volatile ControlLink controlLink;
    private final AtomicBoolean encodeBusy = new AtomicBoolean(false);
    private final AtomicBoolean recoveryFrameBusy = new AtomicBoolean(false);
    private volatile boolean recoveryViewAvailable;
    private volatile long lastFrameAt;
    private volatile long relayCongestedUntilElapsed;
    private volatile boolean cellularTransport;
    private volatile int adaptiveStreamLevel;
    private volatile double streamFreshnessEwmaMs = -1d;
    private volatile int streamFreshnessGoodSamples;
    private volatile int physicalWidth, physicalHeight, streamWidth, streamHeight, streamDensity;
    private PowerManager.WakeLock cpuWakeLock;
    private BroadcastReceiver screenReceiver;
    private BroadcastReceiver networkReceiver;
    private DisplayManager displayManager;
    private DisplayManager.DisplayListener displayListener;
    private AudioRecord audioRecord;
    private volatile boolean audioEnabled;

    public static boolean isRunning() { return running; }
    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            HostRecoveryState.setDesiredRunning(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) {
            if (projection == null && intent != null) {
                Intent data = (Intent) intent.getParcelableExtra(EXTRA_RESULT_DATA);
                int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
                if (data != null && resultCode == Activity.RESULT_OK) {
                    if (startProjection(resultCode, data)) {
                        startDisplayWatcher();
                        startAudioCapture();
                        try { ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIF + 1); }
                        catch (Exception ignored) {}
                        sendHostStatus();
                    } else {
                        postNeedsApprovalNotification();
                    }
                }
            }
            ensureForegroundState();
            acquireCpuWakeLock();
            startRecoveryWatchdog();
            return START_STICKY;
        }
        if (intent == null || ACTION_RECOVER.equals(intent.getAction())) {
            if (!HostRecoveryState.shouldRun(this)) {
                stopSelf();
                return START_NOT_STICKY;
            }
            recoverWithoutProjection();
            return running ? START_STICKY : START_NOT_STICKY;
        }

        pairingCode = intent.getStringExtra(EXTRA_CODE);
        if (pairingCode == null || pairingCode.length() != 6)
            pairingCode = HostConfig.getOrCreateSessionPin(this);
        remoteId = intent.getStringExtra("remotephone.remote_id");
        if (remoteId == null || remoteId.length() != 9)
            remoteId = HostConfig.getOrCreateRemoteId(this);
        relayToken = HostConfig.getOrCreateRelayToken(this);
        HostConfig.prepareDirectBoot(this);

        Intent data = (Intent) intent.getParcelableExtra(EXTRA_RESULT_DATA);
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        if (data == null || resultCode != Activity.RESULT_OK) return START_NOT_STICKY;

        try {
            hostIdentity = HostIdentity.getOrCreate();
        } catch (Exception e) {
            Toast.makeText(this, "Unable to create Host security identity", Toast.LENGTH_LONG).show();
            return START_NOT_STICKY;
        }

        HostRecoveryState.setDesiredRunning(this, true);
        running = true;
        if (!ensureForegroundState(true)) {
            running = false;
            HostRecoveryState.setDesiredRunning(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }
        acquireCpuWakeLock();
        registerScreenStateReceiver();
        registerNetworkStateReceiver();
        if (!startProjection(resultCode, data)) {
            running = false;
            HostRecoveryState.setDesiredRunning(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }
        startDisplayWatcher();
        startAudioCapture();
        startLocalServer();
        if (RelayConfig.isConfigured()) {
            startRelayLoop();
            startControlLink();
        }
        startRecoveryWatchdog();
        return START_STICKY;
    }

    private void recoverWithoutProjection() {
        pairingCode = HostConfig.getRecoverySessionPin(this);
        remoteId = HostConfig.getRecoveryRemoteId(this);
        relayToken = HostConfig.getRecoveryRelayToken(this);
        if (pairingCode.length() != 6 || remoteId.length() != 9 || relayToken.length() < 64) {
            postNeedsApprovalNotification();
            stopSelf();
            return;
        }
        try {
            hostIdentity = HostIdentity.getOrCreate();
        } catch (Exception e) {
            postNeedsApprovalNotification();
            stopSelf();
            return;
        }

        running = true;
        ensureForegroundState();
        acquireCpuWakeLock();
        registerScreenStateReceiver();
        registerNetworkStateReceiver();
        ensurePhysicalDisplayMetrics();
        startLocalServer();
        if (RelayConfig.isConfigured()) {
            startRelayLoop();
            startControlLink();
        }
        startRecoveryWatchdog();
        postNeedsApprovalNotification();
        sendHostStatus();
    }

    private void ensureForegroundState() {
        ensureForegroundState(projection != null);
    }

    /**
     * Android requires the service to already be promoted with the
     * MEDIA_PROJECTION foreground-service type before getMediaProjection().
     * Recovery mode intentionally uses SPECIAL_USE on Android 14+ instead.
     */
    private boolean ensureForegroundState(boolean mediaProjectionMode) {
        try {
            createNotificationChannel();
            Intent open = new Intent(this, HostActivity.class);
            PendingIntent pi = PendingIntent.getActivity(this, 102, open,
                    PendingIntent.FLAG_UPDATE_CURRENT |
                            (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            String text;
            if (!mediaProjectionMode)
                text = "Host recovered; screen capture approval required";
            else if (remoteId != null && remoteId.length() == 9 && RelayConfig.isConfigured())
                text = "Internet Remote ID: " + formatRemoteId(remoteId);
            else if (RelayConfig.isConfigured())
                text = "Host service active";
            else
                text = "Local testing ready; internet relay not configured";
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, "remotephone_host")
                    : new Notification.Builder(this);
            b.setContentTitle("RemotePhone Host is ready")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_sys_upload)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true);
            Notification notification = b.build();

            if (Build.VERSION.SDK_INT >= 29) {
                if (mediaProjectionMode) {
                    startForeground(NOTIF, notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
                } else if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(NOTIF, notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                } else {
                    startForeground(NOTIF, notification);
                }
            } else {
                startForeground(NOTIF, notification);
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
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
            if (cpuWakeLock != null && cpuWakeLock.isHeld()) return;
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

    private void registerNetworkStateReceiver() {
        if (networkReceiver != null) return;
        updateNetworkTransportMode();
        networkReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                updateNetworkTransportMode();
                relayCongestedUntilElapsed = 0L;
                adaptiveStreamLevel = cellularTransport ? 2 : 0;
                streamFreshnessEwmaMs = -1d;
                streamFreshnessGoodSamples = 0;
                if (!running || !RelayConfig.isConfigured()) return;
                RelaySocket rs = relaySocket;
                if (rs != null) rs.close();
                CryptoChannel c = channel;
                if (c != null) c.close();
                ControlLink ctl = controlLink;
                if (ctl != null) ctl.reset();
            }
        };
        IntentFilter f = new IntentFilter(android.net.ConnectivityManager.CONNECTIVITY_ACTION);
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(networkReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(networkReceiver, f);
        } catch (Exception ignored) {
            networkReceiver = null;
        }
    }

    private void updateNetworkTransportMode() {
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
            android.net.Network network = cm == null ? null : cm.getActiveNetwork();
            android.net.NetworkCapabilities caps =
                    cm == null || network == null ? null : cm.getNetworkCapabilities(network);
            cellularTransport = caps != null &&
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Exception ignored) {
            cellularTransport = false;
        }
    }

    private int effectiveStreamLevel() {
        int level = adaptiveStreamLevel;
        if (cellularTransport) level = Math.max(level, 1);
        if (SystemClock.elapsedRealtime() < relayCongestedUntilElapsed)
            level = Math.max(level, 3);
        return Math.max(0, Math.min(3, level));
    }

    private long targetFrameIntervalMs() {
        switch (effectiveStreamLevel()) {
            case 3: return 500L;
            case 2: return 250L;
            case 1: return 160L;
            default: return 83L;
        }
    }

    private void handleStreamFeedback(byte[] payload) {
        if (payload == null || payload.length < 8) return;
        try {
            DataInputStream d = new DataInputStream(new ByteArrayInputStream(payload));
            long hostFrameTimestamp = d.readLong();
            long ageMs = System.currentTimeMillis() - hostFrameTimestamp;
            if (ageMs < 0L || ageMs > 60000L) return;

            double previous = streamFreshnessEwmaMs;
            streamFreshnessEwmaMs = previous < 0d
                    ? ageMs
                    : (previous * 0.65d) + (ageMs * 0.35d);
            double age = streamFreshnessEwmaMs;

            int target;
            if (age >= 2000d) target = 3;
            else if (age >= 900d) target = 2;
            else if (age >= 450d) target = 1;
            else target = 0;

            if (target > adaptiveStreamLevel) {
                // Controller sees stale video: reduce bandwidth immediately.
                adaptiveStreamLevel = target;
                streamFreshnessGoodSamples = 0;
            } else if (target < adaptiveStreamLevel && age < 350d) {
                // Restore quality gradually after several fresh frames.
                streamFreshnessGoodSamples++;
                if (streamFreshnessGoodSamples >= 3) {
                    adaptiveStreamLevel = Math.max(target, adaptiveStreamLevel - 1);
                    streamFreshnessGoodSamples = 0;
                }
            } else {
                streamFreshnessGoodSamples = 0;
            }
        } catch (Exception ignored) {}
    }

    private String hostState() {
        try {
            PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
            if (!pm.isInteractive()) return "Host sleeping";
            KeyguardManager km = (KeyguardManager)getSystemService(KEYGUARD_SERVICE);
            if (km != null) {
                boolean deviceLocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? km.isDeviceLocked()
                        : km.isKeyguardLocked();
                if (deviceLocked) return "Host locked";
            }
        } catch (Exception ignored) {}
        if (projection == null) return recoveryViewAvailable ? "Host recovery view" : "Host needs capture approval";
        return "Host ready";
    }

    private void sendHostStatus() {
        byte[] payload = hostState().getBytes(StandardCharsets.UTF_8);
        CryptoChannel c = channel;
        if (c != null) {
            try { c.send(CryptoChannel.TYPE_STATUS, payload); }
            catch (Exception e) { closeChannel(c); }
        }
        ControlLink ctl = controlLink;
        if (ctl != null && ctl.isConnected()) {
            try { ctl.send(CryptoChannel.TYPE_STATUS, payload); }
            catch (Exception e) { ctl.reset(); }
        }
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

    private void ensurePhysicalDisplayMetrics() {
        try {
            WindowManager wm = (WindowManager)getSystemService(WINDOW_SERVICE);
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            physicalWidth = dm.widthPixels;
            physicalHeight = dm.heightPixels;
        } catch (Exception ignored) {}
    }

    private boolean startProjection(int resultCode, Intent data) {
        if (!ensureForegroundState(true)) return false;
        try {
            MediaProjectionManager m = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = m.getMediaProjection(resultCode, data);
            if (projection == null) return false;
            recoveryViewAvailable = false;
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { enterCaptureRecoveryMode(); }
            }, new Handler(Looper.getMainLooper()));
            if (encodeExecutor != null) encodeExecutor.shutdownNow();
            encodeExecutor = Executors.newSingleThreadExecutor();
            configureDisplayCapture();
            ensureForegroundState(true);
            return true;
        } catch (Exception e) {
            projection = null;
            ensureForegroundState(false);
            return false;
        }
    }

    private synchronized void enterCaptureRecoveryMode() {
        if (!running) return;
        projection = null;
        recoveryViewAvailable = false;
        recoveryFrameBusy.set(false);
        ensurePhysicalDisplayMetrics();
        try { if (displayManager != null && displayListener != null) displayManager.unregisterDisplayListener(displayListener); } catch (Exception ignored) {}
        displayListener = null;
        displayManager = null;
        try { if (audioRecord != null) { audioRecord.stop(); audioRecord.release(); } } catch (Exception ignored) {}
        audioRecord = null;
        if (audioExecutor != null) audioExecutor.shutdownNow();
        audioExecutor = null;
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) {}
        virtualDisplay = null;
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        reader = null;
        if (encodeExecutor != null) encodeExecutor.shutdownNow();
        encodeExecutor = null;
        physicalWidth = physicalHeight = streamWidth = streamHeight = streamDensity = 0;
        ensureForegroundState();
        postNeedsApprovalNotification();
        sendHostStatus();
    }

    private synchronized void configureDisplayCapture() {
        if (projection == null) return;
        WindowManager wm = (WindowManager)getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        int newPhysicalWidth = dm.widthPixels;
        int newPhysicalHeight = dm.heightPixels;
        int newStreamWidth = Math.min(720, newPhysicalWidth);
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
            if (now - lastFrameAt < targetFrameIntervalMs() || !encodeBusy.compareAndSet(false, true)) {
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

            int level = effectiveStreamLevel();
            int outW;
            int jpegQuality;
            int byteBudget;
            long queueBudget;
            if (level >= 3) {
                outW = Math.min(180, w);
                jpegQuality = 10;
                byteBudget = 9 * 1024;
                queueBudget = 9L * 1024L;
            } else if (level == 2) {
                outW = Math.min(300, w);
                jpegQuality = 22;
                byteBudget = 18 * 1024;
                queueBudget = 18L * 1024L;
            } else if (level == 1) {
                outW = Math.min(480, w);
                jpegQuality = 32;
                byteBudget = 32 * 1024;
                queueBudget = 32L * 1024L;
            } else {
                outW = w;
                jpegQuality = 45;
                byteBudget = Integer.MAX_VALUE;
                queueBudget = 48L * 1024L;
            }

            int outH = Math.max(2, (int)Math.round((double)h * outW / Math.max(1, w)));
            if ((outH & 1) == 1) outH--;

            Bitmap output = frame;
            if (outW != w || outH != h)
                output = Bitmap.createScaledBitmap(frame, outW, outH, true);

            ByteArrayOutputStream jpg = new ByteArrayOutputStream(level > 0 ? 32_000 : 96_000);
            output.compress(Bitmap.CompressFormat.JPEG, jpegQuality, jpg);
            byte[] jpeg = jpg.toByteArray();

            if (level > 0 && jpeg.length > byteBudget) {
                jpg.reset();
                output.compress(Bitmap.CompressFormat.JPEG, Math.max(8, jpegQuality - 10), jpg);
                jpeg = jpg.toByteArray();
            }

            if (output != frame) output.recycle();
            frame.recycle();

            long frameTimestamp = System.currentTimeMillis();
            ByteArrayOutputStream payload = new ByteArrayOutputStream(jpeg.length + 20);
            DataOutputStream d = new DataOutputStream(payload);
            d.writeInt(outW); d.writeInt(outH); d.writeLong(frameTimestamp); d.writeInt(jpeg.length); d.write(jpeg); d.flush();

            boolean sent = c.sendDroppable(
                    CryptoChannel.TYPE_FRAME, payload.toByteArray(), queueBudget);
            if (!sent)
                relayCongestedUntilElapsed = SystemClock.elapsedRealtime() + 5000L;
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
                        localSessionActive.set(true);
                        CryptoChannel current = channel;
                        if (current != null && current != candidate) current.close();
                        try {
                            runSession(candidate, true);
                        } finally {
                            localSessionActive.set(false);
                        }
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
        if (!relayLoopActive.compareAndSet(false, true)) return;
        relayExecutor = Executors.newSingleThreadExecutor();
        relayExecutor.execute(() -> {
            long retryDelayMs = 1000L;
            try {
                while (running && RelayConfig.isConfigured()) {
                    if (localSessionActive.get()) {
                        sleepQuietly(400);
                        continue;
                    }
                    RelaySocket rs = null;
                    CryptoChannel candidate = null;
                    boolean sessionEstablished = false;
                    try {
                        rs = RelaySocket.connectHost(remoteId, relayToken);
                        if (!running) break;
                        RelaySocket previous = relaySocket;
                        relaySocket = rs;
                        if (previous != null && previous != rs) previous.close();

                        candidate = CryptoChannel.accept(rs, pairingCode, hostIdentity);
                        if (localSessionActive.get()) {
                            candidate.close();
                            candidate = null;
                            continue;
                        }
                        sessionEstablished = true;
                        retryDelayMs = 1000L;
                        runSession(candidate, false);
                    } catch (Exception ignored) {
                        // The loop below owns recovery; every failed transport is discarded.
                    } finally {
                        if (relaySocket == rs) relaySocket = null;
                        if (candidate != null) candidate.close();
                        if (rs != null) rs.close();
                    }

                    if (running && RelayConfig.isConfigured()) {
                        sleepQuietly(retryDelayMs);
                        if (!sessionEstablished)
                            retryDelayMs = Math.min(10000L, retryDelayMs * 2L);
                    }
                }
            } finally {
                relayLoopActive.set(false);
            }
        });
    }

    private void startRecoveryWatchdog() {
        if (recoveryExecutor != null && !recoveryExecutor.isShutdown()) return;
        recoveryExecutor = Executors.newSingleThreadScheduledExecutor();
        recoveryExecutor.scheduleWithFixedDelay(() -> {
            if (!running || !RelayConfig.isConfigured()) return;
            try {
                if (!relayLoopActive.get()) startRelayLoop();
                if (projection == null) trySendRecoveryFrame();
                ControlLink ctl = controlLink;
                if (ctl == null || !ctl.isWorkerAlive()) {
                    synchronized (HostService.this) {
                        ctl = controlLink;
                        if (ctl == null || !ctl.isWorkerAlive()) {
                            if (ctl != null) ctl.close();
                            controlLink = null;
                            startControlLink();
                        }
                    }
                }
            } catch (Exception ignored) {}
        }, 5, 10, TimeUnit.SECONDS);
    }

    private boolean canAttemptRecoveryView() {
        if (Build.VERSION.SDK_INT < 30 || !RemoteAccessibilityService.isReady()) return false;
        try {
            PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) return false;
            KeyguardManager km = (KeyguardManager)getSystemService(KEYGUARD_SERVICE);
            if (km != null) {
                boolean locked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? km.isDeviceLocked() : km.isKeyguardLocked();
                if (locked) return false;
            }
        } catch (Exception ignored) { return false; }
        return true;
    }

    private void trySendRecoveryFrame() {
        CryptoChannel current = channel;
        if (projection != null || current == null || !canAttemptRecoveryView()) return;
        if (!recoveryFrameBusy.compareAndSet(false, true)) return;
        ensurePhysicalDisplayMetrics();
        boolean started = RemoteAccessibilityService.requestRecoveryFrame(
                new RemoteAccessibilityService.RecoveryFrameCallback() {
                    @Override public void onFrame(int width, int height, byte[] jpeg) {
                        try {
                            CryptoChannel active = channel;
                            if (!running || projection != null || active == null || active != current) return;
                            recoveryViewAvailable = true;
                            sendHostStatus();
                            sendRecoveryFrame(active, width, height, jpeg);
                        } finally {
                            recoveryFrameBusy.set(false);
                        }
                    }

                    @Override public void onFailure(int errorCode) {
                        recoveryFrameBusy.set(false);
                    }
                });
        if (!started) recoveryFrameBusy.set(false);
    }

    private void sendRecoveryFrame(CryptoChannel c, int width, int height, byte[] jpeg) {
        try {
            ByteArrayOutputStream payload = new ByteArrayOutputStream(jpeg.length + 20);
            DataOutputStream d = new DataOutputStream(payload);
            d.writeInt(width);
            d.writeInt(height);
            d.writeLong(System.currentTimeMillis());
            d.writeInt(jpeg.length);
            d.write(jpeg);
            d.flush();
            c.send(CryptoChannel.TYPE_RECOVERY_FRAME, payload.toByteArray());
        } catch (Exception e) {
            closeChannel(c);
        }
    }

    private synchronized void startControlLink() {
        if (!running || !RelayConfig.isConfigured() || controlLink != null) return;
        ControlLink link = ControlLink.forHost(
                remoteId,
                pairingCode,
                relayToken,
                hostIdentity,
                new ControlLink.Listener() {
                    @Override public void onConnected(String ignored) {
                        sendHostStatus();
                    }

                    @Override public void onMessage(CryptoChannel.Message message) {
                        ControlLink current = controlLink;
                        if (current == null) return;
                        try {
                            if (message.type == CryptoChannel.TYPE_PING) {
                                current.send(CryptoChannel.TYPE_PING, new byte[0]);
                            } else if (message.type == CryptoChannel.TYPE_CONTROL &&
                                    message.payload != null && message.payload.length > 0 &&
                                    message.payload[0] == CryptoChannel.CONTROL_WAKE) {
                                wakeHost();
                            } else if (message.type == CryptoChannel.TYPE_UNLOCK) {
                                byte result = evaluateUnlockRequest(message.payload);
                                current.send(CryptoChannel.TYPE_UNLOCK_RESULT, new byte[]{result});
                                new Handler(Looper.getMainLooper()).postDelayed(
                                        HostService.this::sendHostStatus, 900);
                            } else if (message.type == CryptoChannel.TYPE_STREAM_FEEDBACK) {
                                handleStreamFeedback(message.payload);
                            }
                        } catch (Exception e) {
                            current.reset();
                        }
                    }

                    @Override public void onDisconnected() {}
                });
        controlLink = link;
        link.start();
    }

    private void runSession(CryptoChannel candidate, boolean localTransport) throws Exception {
        synchronized (this) {
            if (!localTransport && localSessionActive.get())
                throw new IOException("Local session active");
            CryptoChannel old = channel;
            channel = candidate;
            if (old != null && old != candidate) old.close();
        }
        audioEnabled = false;
        try {
            sendInfo(candidate);
            sendHostStatus();
            if (projection == null) trySendRecoveryFrame();
            readCommands(candidate);
        } finally {
            synchronized (this) {
                if (channel == candidate) channel = null;
            }
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
            else if (m.type == CryptoChannel.TYPE_UNLOCK) handleUnlock(c, m.payload);
            else if (m.type == CryptoChannel.TYPE_STREAM_FEEDBACK) handleStreamFeedback(m.payload);
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

    private byte evaluateUnlockRequest(byte[] p) {
        byte result = CryptoChannel.UNLOCK_RESULT_BAD_REQUEST;
        try {
            KeyguardManager km = (KeyguardManager)getSystemService(KEYGUARD_SERVICE);
            boolean locked = km != null && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? km.isDeviceLocked()
                    : km.isKeyguardLocked());
            if (!locked) {
                return CryptoChannel.UNLOCK_RESULT_NOT_LOCKED;
            }
            if (p == null || p.length < 2) return result;

            byte method = p[0];
            String credential = new String(p, 1, p.length - 1, StandardCharsets.UTF_8);
            boolean accepted = method == CryptoChannel.UNLOCK_PIN
                    ? RemoteAccessibilityService.submitKnownPin(credential)
                    : method == CryptoChannel.UNLOCK_PATTERN &&
                    RemoteAccessibilityService.submitKnownPattern(credential);
            result = accepted
                    ? CryptoChannel.UNLOCK_RESULT_ACCEPTED
                    : CryptoChannel.UNLOCK_RESULT_UNSUPPORTED;
        } catch (Exception ignored) {
            result = CryptoChannel.UNLOCK_RESULT_UNSUPPORTED;
        }
        return result;
    }

    private void handleUnlock(CryptoChannel c, byte[] p) {
        byte result = evaluateUnlockRequest(p);
        try { c.send(CryptoChannel.TYPE_UNLOCK_RESULT, new byte[]{result}); }
        catch (Exception e) { closeChannel(c); }
        new Handler(Looper.getMainLooper()).postDelayed(this::sendHostStatus, 900);
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

    @Override public void onTaskRemoved(Intent rootIntent) {
        // Removing the UI task must not stop an active unattended Host service.
        if (running) {
            ensureForegroundState();
            acquireCpuWakeLock();
            sendHostStatus();
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        running = false;
        audioEnabled = false;
        recoveryViewAvailable = false;
        recoveryFrameBusy.set(false);
        CryptoChannel c = channel;
        channel = null;
        if (c != null) c.close();
        ControlLink ctl = controlLink;
        controlLink = null;
        if (ctl != null) ctl.close();
        RelaySocket rs = relaySocket;
        relaySocket = null;
        if (rs != null) rs.close();
        try { if (server != null) server.close(); } catch(Exception ignored) {}
        try { if (displayManager != null && displayListener != null) displayManager.unregisterDisplayListener(displayListener); } catch(Exception ignored) {}
        try { if (screenReceiver != null) unregisterReceiver(screenReceiver); } catch(Exception ignored) {}
        try { if (networkReceiver != null) unregisterReceiver(networkReceiver); } catch(Exception ignored) {}
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
        if (recoveryExecutor != null) recoveryExecutor.shutdownNow();
        super.onDestroy();
    }
}
