package com.remotephone.direct;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
    private TextView status;
    private RemoteScreenView screen;
    private volatile CryptoChannel channel;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

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
        connectPanel.addView(text("Choose a saved Host or add a new Host using its Remote ID.", 15));

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
        address.setHint("Local address, e.g. 192.168.1.25:49200");
        address.setSingleLine(true);
        address.setVisibility(View.GONE);
        connectPanel.addView(address);

        Button connect = new Button(this);
        connect.setText("CONNECT TO HOST");
        connectPanel.addView(connect);
        status = text("Not connected", 14);
        connectPanel.addView(status);
        root.addView(connectScroll, new LinearLayout.LayoutParams(-1, -1));

        remotePanel = new LinearLayout(this);
        remotePanel.setOrientation(LinearLayout.VERTICAL);
        remotePanel.setBackgroundColor(0xFF000000);
        remotePanel.setVisibility(View.GONE);

        screen = new RemoteScreenView(this);
        remotePanel.addView(screen, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        Button back = b("◀"), home = b("●"), recent = b("■"), fit = b("FIT"), disconnect = b("X");
        nav.addView(back, new LinearLayout.LayoutParams(0, -2, 1));
        nav.addView(home, new LinearLayout.LayoutParams(0, -2, 1));
        nav.addView(recent, new LinearLayout.LayoutParams(0, -2, 1));
        nav.addView(fit, new LinearLayout.LayoutParams(0, -2, 1));
        nav.addView(disconnect, new LinearLayout.LayoutParams(0, -2, 1));
        remotePanel.addView(nav);

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
        disconnect.setOnClickListener(v -> disconnect());
        back.setOnClickListener(v -> sendNav(CryptoChannel.NAV_BACK));
        home.setOnClickListener(v -> sendNav(CryptoChannel.NAV_HOME));
        recent.setOnClickListener(v -> sendNav(CryptoChannel.NAV_RECENTS));
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

    private void enterViewerUi() {
        connectPanel.setVisibility(View.GONE);
        ((View)connectPanel.getParent()).setVisibility(View.GONE);
        remotePanel.setVisibility(View.VISIBLE);
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
        refreshHosts();
    }

    private void connectNow() {
        String id = digits(remoteId.getText().toString());
        String c = code.getText().toString().trim();
        String a = address.getText().toString().trim();

        if (id.length() != 9) {
            status.setText("Enter the Host's 9-digit Remote ID");
            return;
        }
        if (c.length() != 6) {
            status.setText("Enter the Host's 6-digit session PIN");
            return;
        }
        if (a.isEmpty()) {
            status.setText("Remote ID internet routing is the next v0.3 networking step. For this development build, open Advanced Local Test and enter the Host address.");
            return;
        }

        String[] hp = a.split(":");
        if (hp.length != 2) { status.setText("Advanced local address must be IP:port"); return; }
        String host = hp[0];
        int port;
        try { port = Integer.parseInt(hp[1]); }
        catch (Exception e) { status.setText("Invalid local test port"); return; }

        saveCurrentHost();
        status.setText("Connecting to Host…");
        io.execute(() -> {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(host, port), 10000);
                CryptoChannel ch = CryptoChannel.connect(s, c);
                channel = ch;
                runOnUiThread(() -> { enterViewerUi(); Toast.makeText(this, "Connected to Host", Toast.LENGTH_SHORT).show(); });
                readLoop(ch);
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Connection failed: " + safeMessage(e)));
            }
        });
    }

    private void readLoop(CryptoChannel ch) {
        try {
            while (channel == ch) {
                CryptoChannel.Message m = ch.read();
                if (m.type == CryptoChannel.TYPE_FRAME) handleFrame(m.payload);
            }
        } catch (Exception ignored) {
        } finally {
            if (channel == ch) channel = null;
            ch.close();
            runOnUiThread(() -> { leaveViewerUi(); status.setText("Disconnected from Host"); });
        }
    }

    private void handleFrame(byte[] p) {
        try {
            DataInputStream d = new DataInputStream(new ByteArrayInputStream(p));
            d.readInt(); d.readInt();
            d.readLong();
            int len = d.readInt();
            if (len < 1 || len > p.length) return;
            byte[] jpg = new byte[len];
            d.readFully(jpg);
            Bitmap bmp = BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
            if (bmp != null) runOnUiThread(() -> screen.setFrame(bmp));
        } catch (Exception ignored) {}
    }

    private void sendGesture(float x1,float y1,float x2,float y2,long duration) {
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

    private void sendText(String t) {
        CryptoChannel ch = channel;
        if (ch == null || t.isEmpty()) return;
        ioSend(() -> ch.send(CryptoChannel.TYPE_TEXT, t.getBytes(StandardCharsets.UTF_8)));
    }

    private void ioSend(Throwing r) {
        new Thread(() -> { try { r.run(); } catch (Exception e) { disconnect(); } }, "remotephone-send").start();
    }

    private interface Throwing { void run() throws Exception; }

    private void disconnect() {
        CryptoChannel c = channel;
        channel = null;
        if (c != null) c.close();
    }

    @Override protected void onDestroy() {
        disconnect();
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
        return m == null ? e.getClass().getSimpleName() : m;
    }
}
