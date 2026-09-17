package com.remotephone.direct;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;

public class ViewerActivity extends Activity {
    private LinearLayout connectPanel, remotePanel, hostsList;
    private EditText remoteId, friendlyName, address, code, textInput;
    private TextView status, remoteStatus, healthStatus;
    private TextView dashboardSummary;
    private RemoteScreenView screen;
    private WakeManager wakeManager;
    private volatile CryptoChannel channel;
    private volatile ControlLink controlLink;
    private final java.util.concurrent.ConcurrentHashMap<String, ControlLink> hostStatusLinks = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, String> hostStatusById = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile long lastHostStatusPingElapsed;
    private volatile String activeControlHostId = "";
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService health = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean audioOn;
    private volatile boolean manualDisconnect;
    private volatile boolean destroyed;
    private volatile long lastSeenElapsed;
    private volatile long lastPingAttemptElapsed;
    private volatile long lastPingSentElapsed;
    private volatile long latencyMs = -1;
    private volatile boolean pingOutstanding;
    private AudioTrack audioTrack;
    private static final long CONNECTION_WATCHDOG_MS = 12000L;
    private static final long VIDEO_STALE_MS = 6000L;
    private volatile long lastFrameElapsed;
    private volatile String lastHostState = "Host ready";
    private volatile boolean videoStale;
    private volatile HostStateManager.State hostUiState = HostStateManager.State.OFFLINE;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);

        ScrollView connectScroll = new ScrollView(this);
        connectPanel = new LinearLayout(this);
        connectPanel.setOrientation(LinearLayout.VERTICAL);
        connectPanel.setPadding(36, 54, 36, 36);
        connectPanel.setBackgroundColor(0xFFFFFFFF);
        connectScroll.addView(connectPanel);

        connectPanel.addView(text("CONTROLLER — v0.4", 27));
        connectPanel.addView(text("Choose a saved Host or add a Host using its Remote ID.", 15));

        TextView myHostsTitle = text("My Hosts", 20);
        myHostsTitle.setPadding(0, 24, 0, 8);
        connectPanel.addView(myHostsTitle);
        dashboardSummary = text("0 Hosts saved", 14);
        dashboardSummary.setPadding(0, 0, 0, 8);
        connectPanel.addView(dashboardSummary);
        hostsList = new LinearLayout(this);
        hostsList.setOrientation(LinearLayout.VERTICAL);
        connectPanel.addView(hostsList);

        TextView addTitle = text("Add / connect to Host", 18);
        addTitle.setPadding(0, 26, 0, 6);
        connectPanel.addView(addTitle);

        remoteId = new EditText(this);
        remoteId.setHint("9-digit Remote ID");
        remoteId.setSingleLine(true);
        remoteId.setInputType(InputType.TYPE_CLASS_NUMBER);
        connectPanel.addView(remoteId);

        friendlyName = new EditText(this);
        friendlyName.setHint("Friendly name, e.g. Home Host");
        friendlyName.setSingleLine(true);
        connectPanel.addView(friendlyName);

        code = new EditText(this);
        code.setHint("6-digit session PIN");
        code.setSingleLine(true);
        code.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        connectPanel.addView(code);

        Button saveHost = new Button(this);
        saveHost.setText("SAVE TO MY HOSTS");
        connectPanel.addView(saveHost);

        Button advanced = new Button(this);
        advanced.setText("ADVANCED LOCAL TEST");
        connectPanel.addView(advanced);
        address = new EditText(this);
        address.setHint("Optional local address, e.g. 192.168.1.25:49200");
        address.setSingleLine(true);
        address.setVisibility(View.GONE);
        connectPanel.addView(address);

        Button connect = new Button(this);
        connect.setText("CONNECT TO HOST");
        connectPanel.addView(connect);
        status = text(RelayConfig.isConfigured() ? "Internet Remote ID connection ready" : "Internet relay deployment pending; local test remains available", 14);
        connectPanel.addView(status);
        root.addView(connectScroll, new LinearLayout.LayoutParams(-1, -1));

        remotePanel = new LinearLayout(this);
        remotePanel.setOrientation(LinearLayout.VERTICAL);
        remotePanel.setBackgroundColor(0xFF000000);
        remotePanel.setVisibility(View.GONE);

        remoteStatus = new TextView(this);
        remoteStatus.setText("Host ready");
        remoteStatus.setTextColor(0xFFFFFFFF);
        remoteStatus.setTextSize(14);
        remoteStatus.setGravity(Gravity.CENTER);
        remoteStatus.setPadding(8, 6, 8, 2);
        remotePanel.addView(remoteStatus, new LinearLayout.LayoutParams(-1, -2));

        healthStatus = new TextView(this);
        healthStatus.setText("Ping measuring…  •  Last seen --");
        healthStatus.setTextColor(0xFFBBBBBB);
        healthStatus.setTextSize(12);
        healthStatus.setGravity(Gravity.CENTER);
        healthStatus.setPadding(8, 0, 8, 6);
        remotePanel.addView(healthStatus, new LinearLayout.LayoutParams(-1, -2));

        screen = new RemoteScreenView(this);
        remotePanel.addView(screen, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        Button back = b("◀"), home = b("●"), recent = b("■"), fit = b("FIT");
        nav.addView(back, new LinearLayout.LayoutParams(0, -2, 1));
        nav.addView(home, new LinearLayout.LayoutParams(0, -2, 1));
        nav.addView(recent, new LinearLayout.LayoutParams(0, -2, 1));
        nav.addView(fit, new LinearLayout.LayoutParams(0, -2, 1));
        remotePanel.addView(nav);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        Button wake = b("WAKE HOST"), audio = b("AUDIO OFF"), disconnect = b("DISCONNECT");
        controls.addView(wake, new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(audio, new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(disconnect, new LinearLayout.LayoutParams(0, -2, 1));
        remotePanel.addView(controls);

        LinearLayout type = new LinearLayout(this);
        type.setOrientation(LinearLayout.HORIZONTAL);
        textInput = new EditText(this);
        textInput.setHint("Type on Host");
        textInput.setSingleLine(true);
        Button send = b("SEND");
        type.addView(textInput, new LinearLayout.LayoutParams(0, -2, 1));
        type.addView(send, new LinearLayout.LayoutParams(-2, -2));
        remotePanel.addView(type);

        root.addView(remotePanel, new LinearLayout.LayoutParams(-1, -1));
        setContentView(root);

        wakeManager = new WakeManager(
                () -> sendControl(CryptoChannel.CONTROL_WAKE),
                message -> {
                    if (remoteStatus != null) remoteStatus.setText(message);
                });

        saveHost.setOnClickListener(v -> saveCurrentHost());
        advanced.setOnClickListener(v -> {
            boolean show = address.getVisibility() != View.VISIBLE;
            address.setVisibility(show ? View.VISIBLE : View.GONE);
            advanced.setText(show ? "HIDE ADVANCED LOCAL TEST" : "ADVANCED LOCAL TEST");
        });
        connect.setOnClickListener(v -> connectNow());
        disconnect.setOnClickListener(v -> userDisconnect());
        back.setOnClickListener(v -> sendNav(CryptoChannel.NAV_BACK));
        home.setOnClickListener(v -> sendNav(CryptoChannel.NAV_HOME));
        recent.setOnClickListener(v -> sendNav(CryptoChannel.NAV_RECENTS));
        wake.setOnClickListener(v -> wakeManager.requestWake());
        audio.setOnClickListener(v -> {
            audioOn = !audioOn;
            audio.setText(audioOn ? "AUDIO ON" : "AUDIO OFF");
            sendControl(audioOn ? CryptoChannel.CONTROL_AUDIO_ON : CryptoChannel.CONTROL_AUDIO_OFF);
            if (!audioOn) stopAudioPlayback();
        });
        send.setOnClickListener(v -> { sendText(textInput.getText().toString()); textInput.setText(""); });
        fit.setOnClickListener(v -> {
            boolean next = !screen.isFillMode();
            screen.setFillMode(next);
            fit.setText(next ? "FIT" : "FILL");
            Toast.makeText(this, next ? "Fill Controller screen" : "Fit entire Host screen", Toast.LENGTH_SHORT).show();
        });
        screen.setGestureSink(this::sendGesture);
        refreshHosts();
        health.scheduleAtFixedRate(this::healthTick, 1, 1, TimeUnit.SECONDS);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshHosts();
    }

    private void refreshHosts() {
        if (hostsList == null) return;
        hostsList.removeAllViews();
        List<MyHosts.HostRecord> all = MyHosts.load(this);
        ensureHostStatusLinks(all);
        if (dashboardSummary != null)
            dashboardSummary.setText(all.size() + " / " + MyHosts.MAX_HOSTS + " Hosts saved");
        if (all.isEmpty()) {
            TextView none = text("No saved Hosts yet.", 14);
            none.setPadding(0, 6, 0, 6);
            hostsList.addView(none);
            return;
        }
        for (MyHosts.HostRecord h : all) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            Button choose = new Button(this);
            String n = h.name == null || h.name.trim().isEmpty() ? "Host" : h.name.trim();
            choose.setText(n + "\n" + formatDeviceId(h.remoteId) + "\n" + hostStatusById.getOrDefault(h.remoteId, "Offline"));
            choose.setAllCaps(false);
            choose.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            row.addView(choose, new LinearLayout.LayoutParams(0, -2, 1f));
            Button remove = new Button(this);
            remove.setText("REMOVE");
            row.addView(remove, new LinearLayout.LayoutParams(-2, -2));
            hostsList.addView(row);

            choose.setOnClickListener(v -> {
                remoteId.setText(h.remoteId);
                friendlyName.setText(h.name);
                address.setText(h.localAddress);
                String savedPin = ControllerSecretStore.loadPin(this, h.remoteId);
                if (savedPin.length() == 6) {
                    code.setText(savedPin);
                    status.setText("Connecting to " + n + "…");
                    connectNow();
                } else {
                    code.setText("");
                    status.setText("Selected " + n + ". Enter its 6-digit session PIN once to enable one-tap connection.");
                }
            });
            remove.setOnClickListener(v -> {
                stopHostStatusLink(h.remoteId);
                ControllerSecretStore.removePin(this, h.remoteId);
                MyHosts.remove(this, h.remoteId);
                refreshHosts();
            });
        }
    }

    private void saveCurrentHost() {
        String id = digits(remoteId.getText().toString());
        if (id.length() != 9) {
            status.setText("Enter a valid 9-digit Remote ID first");
            return;
        }
        String n = friendlyName.getText().toString().trim();
        if (n.isEmpty()) n = "Host " + id.substring(6);
        String a = address.getText().toString().trim();
        if (MyHosts.find(this, id) == null && MyHosts.load(this).size() >= MyHosts.MAX_HOSTS) {
            status.setText("My Hosts is full — maximum " + MyHosts.MAX_HOSTS + " Hosts");
            return;
        }
        MyHosts.upsert(this, new MyHosts.HostRecord(id, n, a));
        friendlyName.setText(n);
        status.setText("Saved " + n + " to My Hosts. Session PIN was not saved.");
        refreshHosts();
    }

    private void enterViewerUi(String message) {
        connectPanel.setVisibility(View.GONE);
        ((View)connectPanel.getParent()).setVisibility(View.GONE);
        remotePanel.setVisibility(View.VISIBLE);
        remoteStatus.setText(message);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void leaveViewerUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        remotePanel.setVisibility(View.GONE);
        View parent = (View) connectPanel.getParent();
        parent.setVisibility(View.VISIBLE);
        connectPanel.setVisibility(View.VISIBLE);
        audioOn = false;
        stopAudioPlayback();
        refreshHosts();
    }

    private void connectNow() {
        final String id = digits(remoteId.getText().toString());
        final String pin = code.getText().toString().trim();
        final String localAddress = address.getText().toString().trim();

        if (id.length() != 9) {
            status.setText("Enter the Host's 9-digit Remote ID");
            return;
        }
        if (pin.length() != 6) {
            status.setText("Enter the Host's 6-digit session PIN");
            return;
        }
        if (localAddress.isEmpty() && !RelayConfig.isConfigured()) {
            status.setText("Internet relay is not deployed yet. For now, use Advanced Local Test on the same network.");
            return;
        }

        saveCurrentHost();
        stopHostStatusLink(id);
        activeControlHostId = id;
        manualDisconnect = false;
        status.setText(localAddress.isEmpty() ? "Finding Host by Remote ID…" : "Connecting to Host locally…");
        if (localAddress.isEmpty()) {
            MyHosts.HostRecord savedControlHost = MyHosts.find(this, id);
            String expectedControlFingerprint = savedControlHost == null ? "" : savedControlHost.hostFingerprint;
            ControlLink ctl = ControlLink.forController(id, pin, expectedControlFingerprint,
                    new ControlLink.Listener() {
                        @Override public void onConnected(String peerFingerprint) {
                            MyHosts.pinFingerprint(ViewerActivity.this, id, peerFingerprint);
                        }

                        @Override public void onMessage(CryptoChannel.Message message) {
                            if (message.type == CryptoChannel.TYPE_STATUS) {
                                handleStatus(message.payload);
                            } else if (message.type == CryptoChannel.TYPE_PING) {
                                handleHeartbeatResponse();
                            }
                        }

                        @Override public void onDisconnected() {}
                    });
            controlLink = ctl;
            ctl.start();
        }
        io.execute(() -> connectionLoop(id, pin, localAddress));
    }

    private void connectionLoop(String id, String pin, String localAddress) {
        boolean everConnected = false;
        while (!manualDisconnect && !destroyed) {
            Socket socket = null;
            CryptoChannel ch = null;
            try {
                socket = openTransport(id, localAddress);
                MyHosts.HostRecord saved = MyHosts.find(this, id);
                String expectedFingerprint = saved == null ? "" : saved.hostFingerprint;
                ch = CryptoChannel.connect(socket, pin, expectedFingerprint);
                MyHosts.pinFingerprint(this, id, ch.peerFingerprint());
                ControllerSecretStore.savePin(this, id, pin);
                setSavedHostStatus(id, "Online");
                resetHealthCounters();
                channel = ch;

                boolean wasReconnect = everConnected;
                everConnected = true;
                runOnUiThread(() -> {
                    if (remotePanel.getVisibility() != View.VISIBLE)
                        enterViewerUi("Connected — checking Host status…");
                    else
                        remoteStatus.setText(wasReconnect ? "Reconnected to Host" : "Connected to Host");
                    healthStatus.setText("Ping measuring…  •  Last seen now");
                    Toast.makeText(this, wasReconnect ? "Reconnected" : "Connected to Host", Toast.LENGTH_SHORT).show();
                });

                if (audioOn) sendControl(CryptoChannel.CONTROL_AUDIO_ON);
                readSession(ch);
                if (manualDisconnect || destroyed) break;
                runOnUiThread(() -> {
                    hostUiState = HostStateManager.State.RECONNECTING;
                    remoteStatus.setText("Reconnecting to Host…");
                    healthStatus.setText("Health: reconnecting…");
                });
            } catch (Exception e) {
                if (manualDisconnect || destroyed) break;
                String message = safeMessage(e);
                if (!everConnected) {
                    hostUiState = HostStateManager.State.OFFLINE;
                    runOnUiThread(() -> status.setText("Connection failed: " + message));
                    break;
                }
                runOnUiThread(() -> {
                    hostUiState = HostStateManager.State.RECONNECTING;
                    remoteStatus.setText("Reconnecting to Host…");
                    healthStatus.setText("Health: reconnecting… " + message);
                });
            } finally {
                if (channel == ch) channel = null;
                pingOutstanding = false;
                if (ch != null) ch.close();
                else if (socket != null) try { socket.close(); } catch (Exception ignored) {}
            }

            if (!manualDisconnect && !destroyed && everConnected) sleepQuietly(1800);
        }
    }

    private Socket openTransport(String id, String localAddress) throws Exception {
        if (localAddress == null || localAddress.isEmpty()) {
            return RelaySocket.connectController(id);
        }
        String[] hp = localAddress.split(":");
        if (hp.length != 2) throw new IOException("Advanced local address must be IP:port");
        int port;
        try { port = Integer.parseInt(hp[1]); }
        catch (Exception e) { throw new IOException("Invalid local test port"); }
        Socket s = new Socket();
        s.connect(new InetSocketAddress(hp[0], port), 10000);
        return s;
    }

    private void readSession(CryptoChannel ch) throws Exception {
        while (!manualDisconnect && !destroyed && channel == ch) {
            CryptoChannel.Message m = ch.read();
            lastSeenElapsed = SystemClock.elapsedRealtime();
            if (m.type == CryptoChannel.TYPE_FRAME) handleFrame(m.payload);
            else if (m.type == CryptoChannel.TYPE_AUDIO) handleAudio(m.payload);
            else if (m.type == CryptoChannel.TYPE_STATUS) handleStatus(m.payload);
            else if (m.type == CryptoChannel.TYPE_PING) handleHeartbeatResponse();
        }
    }

    private HostStateManager.State stateFromHostStatus(String s) {
        return HostStateManager.fromHostStatus(s);
    }

    private void setHostUiState(HostStateManager.State state) {
        hostUiState = state;
        final String text = HostStateManager.controllerLabel(state);
        runOnUiThread(() -> {
            if (remoteStatus != null) remoteStatus.setText(text);
        });
    }

    private void handleStatus(byte[] p) {
        String s = new String(p, StandardCharsets.UTF_8);
        lastHostState = s;
        WakeManager wake = wakeManager;
        if (wake != null) wake.onHostStatus(s);
        if (!"Host ready".equals(s)) videoStale = false;
        setHostUiState(stateFromHostStatus(s));
    }

    private void healthTick() {
        if (destroyed) return;
        pingHostStatusLinks();
        CryptoChannel ch = channel;
        if (ch == null) return;
        long now = SystemClock.elapsedRealtime();
        long silentFor = lastSeenElapsed > 0 ? now - lastSeenElapsed : 0;
        if (lastSeenElapsed > 0 && silentFor >= CONNECTION_WATCHDOG_MS) {
            final long silentSeconds = Math.max(1, silentFor / 1000);
            runOnUiThread(() -> {
                if (channel == ch) {
                    hostUiState = HostStateManager.State.RECONNECTING;
                    remoteStatus.setText("Reconnecting to Host…");
                    healthStatus.setText("No Host traffic for " + silentSeconds + "s");
                }
            });
            if (channel == ch) ch.close();
            return;
        }
        long frameAge = lastFrameElapsed > 0 ? now - lastFrameElapsed : 0;
        boolean hostAlive = lastSeenElapsed > 0 && now - lastSeenElapsed < VIDEO_STALE_MS;
        boolean streamExpected = "Host ready".equals(lastHostState);
        if (hostAlive && streamExpected && lastFrameElapsed > 0 && frameAge >= VIDEO_STALE_MS) {
            if (!videoStale) {
                videoStale = true;
                final long frameAgeSeconds = Math.max(1, frameAge / 1000);
                runOnUiThread(() -> {
                    if (channel == ch) {
                        hostUiState = HostStateManager.State.STREAM_UNAVAILABLE;
                        remoteStatus.setText("Stream unavailable — Host still online");
                        healthStatus.setText("Video stale for " + frameAgeSeconds + "s");
                    }
                });
            }
        } else if (!streamExpected) {
            videoStale = false;
        }
        if (now - lastPingAttemptElapsed >= 3000 &&
                (!pingOutstanding || now - lastPingSentElapsed >= 6000)) {
            lastPingAttemptElapsed = now;
            lastPingSentElapsed = now;
            pingOutstanding = true;
            try {
                ch.send(CryptoChannel.TYPE_PING, new byte[0]);
            } catch (Exception e) {
                if (channel == ch) ch.close();
                return;
            }
        }
        refreshHealthUi(ch);
    }

    private void handleHeartbeatResponse() {
        long now = SystemClock.elapsedRealtime();
        lastSeenElapsed = now;
        if (pingOutstanding && lastPingSentElapsed > 0) {
            latencyMs = Math.max(0, now - lastPingSentElapsed);
            pingOutstanding = false;
        }
        CryptoChannel ch = channel;
        if (ch != null) refreshHealthUi(ch);
    }

    private void resetHealthCounters() {
        long now = SystemClock.elapsedRealtime();
        lastSeenElapsed = now;
        lastPingAttemptElapsed = 0;
        lastPingSentElapsed = 0;
        latencyMs = -1;
        pingOutstanding = false;
        lastFrameElapsed = now;
        lastHostState = "Host ready";
        videoStale = false;
    }

    private void refreshHealthUi(CryptoChannel expectedChannel) {
        if (expectedChannel == null || healthStatus == null) return;
        long now = SystemClock.elapsedRealtime();
        long seenAgeSeconds = lastSeenElapsed > 0 ? Math.max(0, (now - lastSeenElapsed) / 1000) : -1;
        String pingText = latencyMs >= 0 ? "Ping " + latencyMs + " ms" : "Ping measuring…";
        String seenText;
        if (seenAgeSeconds < 0) seenText = "Last seen --";
        else if (seenAgeSeconds <= 1) seenText = "Last seen now";
        else seenText = "Last seen " + seenAgeSeconds + "s ago";
        String frameText = "";
        if ("Host ready".equals(lastHostState) && lastFrameElapsed > 0) {
            long frameAgeSeconds = Math.max(0, (now - lastFrameElapsed) / 1000);
            if (frameAgeSeconds <= 1) frameText = "  •  Frame now";
            else frameText = "  •  Frame " + frameAgeSeconds + "s ago";
        }
        final String healthText = pingText + "  •  " + seenText + frameText;
        runOnUiThread(() -> {
            if (channel == expectedChannel && healthStatus != null)
                healthStatus.setText(healthText);
        });
    }

    private void handleFrame(byte[] p) {
        try {
            DataInputStream d = new DataInputStream(new ByteArrayInputStream(p));
            d.readInt(); d.readInt(); d.readLong();
            int len = d.readInt();
            if (len < 1 || len > p.length) return;
            byte[] jpg = new byte[len];
            d.readFully(jpg);
            Bitmap bmp = BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
            if (bmp != null) {
                lastFrameElapsed = SystemClock.elapsedRealtime();
                final boolean wasStale = videoStale;
                videoStale = false;
                runOnUiThread(() -> {
                    screen.setFrame(bmp);
                    if (hostUiState == HostStateManager.State.CAPTURE_APPROVAL_REQUIRED && channel != null) {
                        lastHostState = "Host ready";
                        setHostUiState(HostStateManager.State.READY);
                    } else if (wasStale && channel != null) {
                        setHostUiState(stateFromHostStatus(lastHostState));
                    }
                });
            }
        } catch (Exception ignored) {}
    }

    private void handleAudio(byte[] p) {
        if (!audioOn) return;
        try {
            DataInputStream d = new DataInputStream(new ByteArrayInputStream(p));
            int sampleRate = d.readInt();
            int channels = d.readInt();
            int len = p.length - 8;
            if (len <= 0) return;
            byte[] pcm = new byte[len];
            d.readFully(pcm);
            ensureAudioTrack(sampleRate, channels);
            AudioTrack t = audioTrack;
            if (t != null) t.write(pcm, 0, pcm.length);
        } catch (Exception ignored) {}
    }

    private synchronized void ensureAudioTrack(int sampleRate, int channels) {
        if (audioTrack != null) return;
        try {
            int mask = channels == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
            int min = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT);
            audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, mask,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, 8192), AudioTrack.MODE_STREAM);
            audioTrack.play();
        } catch (Exception e) { audioTrack = null; }
    }

    private synchronized void stopAudioPlayback() {
        AudioTrack t = audioTrack;
        audioTrack = null;
        if (t != null) {
            try { t.pause(); } catch (Exception ignored) {}
            try { t.flush(); } catch (Exception ignored) {}
            try { t.release(); } catch (Exception ignored) {}
        }
    }

    private void sendGesture(float x1, float y1, float x2, float y2, long duration) {
        CryptoChannel ch = channel;
        if (ch == null) return;
        ioSend(() -> {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(b);
            d.writeFloat(x1); d.writeFloat(y1); d.writeFloat(x2); d.writeFloat(y2); d.writeLong(duration); d.flush();
            ch.send(CryptoChannel.TYPE_GESTURE, b.toByteArray());
        });
    }

    private void sendNav(byte n) {
        CryptoChannel ch = channel;
        if (ch == null) return;
        ioSend(() -> ch.send(CryptoChannel.TYPE_NAV, new byte[]{n}));
    }

    private void sendControl(byte action) {
        if (action == CryptoChannel.CONTROL_WAKE) {
            ControlLink ctl = controlLink;
            if (ctl != null && ctl.isConnected()) {
                new Thread(() -> {
                    try { ctl.send(CryptoChannel.TYPE_CONTROL, new byte[]{action}); }
                    catch (Exception e) { ctl.reset(); }
                }, "remotephone-wake-control").start();
                return;
            }
        }
        CryptoChannel ch = channel;
        if (ch == null) return;
        ioSend(() -> ch.send(CryptoChannel.TYPE_CONTROL, new byte[]{action}));
    }

    private void sendText(String t) {
        CryptoChannel ch = channel;
        if (ch == null || t.isEmpty()) return;
        ioSend(() -> ch.send(CryptoChannel.TYPE_TEXT, t.getBytes(StandardCharsets.UTF_8)));
    }

    private void ioSend(Throwing r) {
        new Thread(() -> {
            try { r.run(); }
            catch (Exception e) {
                CryptoChannel c = channel;
                if (c != null) c.close();
            }
        }, "remotephone-send").start();
    }

    private interface Throwing { void run() throws Exception; }

    private void userDisconnect() {
        if (wakeManager != null) wakeManager.cancel();
        manualDisconnect = true;
        hostUiState = HostStateManager.State.OFFLINE;
        ControlLink ctl = controlLink;
        controlLink = null;
        if (ctl != null) ctl.close();
        CryptoChannel c = channel;
        channel = null;
        pingOutstanding = false;
        if (c != null) c.close();
        audioOn = false;
        stopAudioPlayback();
        activeControlHostId = "";
        leaveViewerUi();
        status.setText("Disconnected from Host");
    }

    @Override protected void onDestroy() {
        if (wakeManager != null) wakeManager.cancel();
        destroyed = true;
        manualDisconnect = true;
        for (ControlLink link : hostStatusLinks.values()) if (link != null) link.close();
        hostStatusLinks.clear();
        ControlLink ctl = controlLink;
        controlLink = null;
        if (ctl != null) ctl.close();
        CryptoChannel c = channel;
        channel = null;
        pingOutstanding = false;
        if (c != null) c.close();
        stopAudioPlayback();
        io.shutdownNow();
        health.shutdownNow();
        super.onDestroy();
    }

    private Button b(String s) {
        Button x = new Button(this);
        x.setText(s);
        x.setMinWidth(0);
        x.setMinimumWidth(0);
        return x;
    }

    private TextView text(String s, float z) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(z);
        v.setTextColor(0xFF111111);
        v.setPadding(0, 8, 0, 8);
        return v;
    }

    private void ensureHostStatusLinks(List<MyHosts.HostRecord> hosts) {
        if (destroyed || hosts == null || !RelayConfig.isConfigured()) return;
        java.util.HashSet<String> wanted = new java.util.HashSet<>();
        for (MyHosts.HostRecord h : hosts) {
            if (h == null || h.remoteId == null) continue;
            wanted.add(h.remoteId);
            if (h.remoteId.equals(activeControlHostId)) continue;
            if (hostStatusLinks.containsKey(h.remoteId)) continue;
            String pin = ControllerSecretStore.loadPin(this, h.remoteId);
            if (pin.length() != 6) {
                hostStatusById.put(h.remoteId, "PIN required");
                continue;
            }
            hostStatusById.put(h.remoteId, "Offline");
            final String id = h.remoteId;
            ControlLink link = ControlLink.forController(id, pin, h.hostFingerprint,
                    new ControlLink.Listener() {
                        @Override public void onConnected(String peerFingerprint) {
                            MyHosts.pinFingerprint(ViewerActivity.this, id, peerFingerprint);
                            setSavedHostStatus(id, "Online");
                        }

                        @Override public void onMessage(CryptoChannel.Message message) {
                            if (message.type == CryptoChannel.TYPE_STATUS) {
                                String raw = new String(message.payload, StandardCharsets.UTF_8);
                                setSavedHostStatus(id, dashboardStatus(raw));
                            } else if (message.type == CryptoChannel.TYPE_PING) {
                                String current = hostStatusById.get(id);
                                if (current == null || current.startsWith("Offline"))
                                    setSavedHostStatus(id, "Online");
                            }
                        }

                        @Override public void onDisconnected() {
                            setSavedHostStatus(id, "Offline");
                        }
                    });
            ControlLink old = hostStatusLinks.putIfAbsent(id, link);
            if (old == null) link.start();
            else link.close();
        }
        for (String id : new java.util.ArrayList<>(hostStatusLinks.keySet())) {
            if (!wanted.contains(id)) stopHostStatusLink(id);
        }
    }

    private String dashboardStatus(String raw) {
        return HostStateManager.dashboardLabel(HostStateManager.fromHostStatus(raw));
    }

    private void setSavedHostStatus(String id, String value) {
        hostStatusById.put(id, value);
        runOnUiThread(this::refreshHosts);
    }

    private void stopHostStatusLink(String id) {
        ControlLink link = hostStatusLinks.remove(id);
        if (link != null) link.close();
    }

    private void pingHostStatusLinks() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastHostStatusPingElapsed < 5000L) return;
        lastHostStatusPingElapsed = now;
        for (ControlLink link : hostStatusLinks.values()) {
            if (link == null || !link.isConnected()) continue;
            new Thread(() -> {
                try { link.send(CryptoChannel.TYPE_PING, new byte[0]); }
                catch (Exception e) { link.reset(); }
            }, "remotephone-host-status-ping").start();
        }
    }

    private static String digits(String s) {
        if (s == null) return "";
        return s.replaceAll("[^0-9]", "");
    }

    private static String formatDeviceId(String id) {
        if (id == null || id.length() != 9) return id;
        return id.substring(0,3) + " " + id.substring(3,6) + " " + id.substring(6,9);
    }

    private static String safeMessage(Throwable e) {
        String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
