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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ViewerActivity extends Activity {
    private LinearLayout connectPanel, remotePanel;
    private EditText address, code, textInput;
    private TextView status;
    private RemoteScreenView screen;
    private volatile CryptoChannel channel;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);

        connectPanel = new LinearLayout(this);
        connectPanel.setOrientation(LinearLayout.VERTICAL);
        connectPanel.setPadding(36, 56, 36, 36);
        connectPanel.setBackgroundColor(0xFFFFFFFF);
        connectPanel.addView(text("PHONE A — VIEWER v0.2", 25));
        connectPanel.addView(text("For this test build, enter Phone B's address and 6-digit PIN.", 15));

        address = new EditText(this);
        address.setHint("Phone B address, e.g. 192.168.1.25:49200");
        address.setSingleLine(true);
        connectPanel.addView(address);

        code = new EditText(this);
        code.setHint("6-digit PIN");
        code.setSingleLine(true);
        code.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        connectPanel.addView(code);

        Button connect = new Button(this);
        connect.setText("CONNECT");
        connectPanel.addView(connect);
        status = text("Not connected", 14);
        connectPanel.addView(status);
        root.addView(connectPanel, new LinearLayout.LayoutParams(-1, -1));

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
        textInput.setHint("Type on Phone B");
        textInput.setSingleLine(true);
        Button send = b("SEND");
        type.addView(textInput, new LinearLayout.LayoutParams(0, -2, 1));
        type.addView(send, new LinearLayout.LayoutParams(-2, -2));
        remotePanel.addView(type);

        root.addView(remotePanel, new LinearLayout.LayoutParams(-1, -1));
        setContentView(root);

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
            Toast.makeText(this, next ? "Fill screen" : "Fit entire Phone B screen", Toast.LENGTH_SHORT).show();
        });
        screen.setGestureSink(this::sendGesture);
    }

    private void enterViewerUi() {
        connectPanel.setVisibility(View.GONE);
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
        connectPanel.setVisibility(View.VISIBLE);
    }

    private void connectNow() {
        String a = address.getText().toString().trim();
        String c = code.getText().toString().trim();
        if (a.isEmpty() || c.length() != 6) {
            status.setText("Enter Phone B address and a 6-digit PIN");
            return;
        }
        String[] hp = a.split(":");
        if (hp.length != 2) { status.setText("Address must be IP:port for this test build"); return; }
        String host = hp[0];
        int port;
        try { port = Integer.parseInt(hp[1]); }
        catch (Exception e) { status.setText("Invalid port"); return; }

        status.setText("Connecting…");
        io.execute(() -> {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(host, port), 10000);
                CryptoChannel ch = CryptoChannel.connect(s, c);
                channel = ch;
                runOnUiThread(() -> { enterViewerUi(); Toast.makeText(this, "Connected", Toast.LENGTH_SHORT).show(); });
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
            runOnUiThread(() -> { leaveViewerUi(); status.setText("Disconnected"); });
        }
    }

    private void handleFrame(byte[] p) {
        try {
            DataInputStream d = new DataInputStream(new ByteArrayInputStream(p));
            int w = d.readInt(), h = d.readInt();
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

    private static String safeMessage(Throwable e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }
}
