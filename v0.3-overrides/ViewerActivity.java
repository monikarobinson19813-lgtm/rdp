package com.remotephone.direct;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ViewerActivity extends Activity {
    private LinearLayout connectPanel, remotePanel, hostsList;
    private EditText remoteId, friendlyName, address, code, textInput;
    private TextView status, remoteStatus;
    private RemoteScreenView screen;
    private volatile CryptoChannel channel;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile boolean audioOn;
    private volatile boolean manualDisconnect;
    private volatile boolean destroyed;
    private AudioTrack audioTrack;

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

        connectPanel.addView(text("CONTROLLER — v0.3", 27));
        connectPanel.addView(text("Choose a saved Host or add a Host using its Remote ID.", 15));

        TextView myHostsTitle = text("My Hosts", 20);
        myHostsTitle.setPadding(0, 24, 0, 8);
        connectPanel.addView(myHostsTitle);
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
        remoteStatus.setPadding(8, 6, 8, 6);
        remotePanel.addView(remoteStatus, new LinearLayout.LayoutParams(-1, -2));

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
        wake.setOnClickListener(v -> {
            remoteStatus.setText("Waking Host…");
            sendControl(CryptoChannel.CONTROL_WAKE);
        });
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
    }

    @Override protected void onResume() {
        super.onResume();
        refreshHosts();
    }

    private void refreshHosts() {
        if (hostsList == null) return;
        hostsList.removeAllViews();
        List<MyHosts.HostRecord> all = MyHosts.load(this);
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
            choose.setText(n + "\n" + formatDeviceId(h.remoteId));
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
                code.setText("");
                status.setText("Selected " + n + ". Enter its 6-digit session PIN.");
            });
            remove.setOnClickListener(v -> {
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
        manualDisconnect = false;
        status.setText(localAddress.isEmpty() ? "Finding Host by Remote ID…" : "Connecting to Host locally…");
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
                channel = ch;

                boolean wasReconnect = everConnected;
                everConnected = true;
                runOnUiThread(() -> {
                    if (remotePanel.getVisibility() != View.VISIBLE)
                        enterViewerUi("Connected — checking Host status…");
                    else
                        remoteStatus.setText(wasReconnect ? "Reconnected to Host" : "Connected to Host");
                    Toast.makeText(this, wasReconnect ? "Reconnected" : "Connected to Host", Toast.LENGTH_SHORT).show();
                });

                if (audioOn) sendControl(CryptoChannel.CONTROL_AUDIO_ON);
                readSession(ch);
                if (manualDisconnect || destroyed) break;
                runOnUiThread(() -> remoteStatus.setText("Connection lost — reconnecting…"));
            } catch (Exception e) {
                if (manualDisconnect || destroyed) break;
                String message = safeMessage(e);
                if (!everConnected) {
                    runOnUiThread(() -> status.setText("Connection failed: " + message));
                    break;
                }
                runOnUiThread(() -> remoteStatus.setText("Reconnecting… " + message));
            } finally {
                if (channel == ch) channel = null;
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
            if (m.type == CryptoChannel.TYPE_FRAME) handleFrame(m.payload);
            else if (m.type == CryptoChannel.TYPE_AUDIO) handleAudio(m.payload);
            else if (m.type == CryptoChannel.TYPE_STATUS) handleStatus(m.payload);
        }
    }

    private void handleStatus(byte[] p) {
        String s = new String(p, StandardCharsets.UTF_8);
        runOnUiThread(() -> remoteStatus.setText(s));
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
            if (bmp != null) runOnUiThread(() -> screen.setFrame(bmp));
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
        manualDisconnect = true;
        CryptoChannel c = channel;
        channel = null;
        if (c != null) c.close();
        audioOn = false;
        stopAudioPlayback();
        leaveViewerUi();
        status.setText("Disconnected from Host");
    }

    @Override protected void onDestroy() {
        destroyed = true;
        manualDisconnect = true;
        CryptoChannel c = channel;
        channel = null;
        if (c != null) c.close();
        stopAudioPlayback();
        io.shutdownNow();
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
