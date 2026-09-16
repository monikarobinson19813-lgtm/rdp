package com.remotephone.direct;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.*;
import java.security.SecureRandom;
import java.util.List;

public class HostActivity extends Activity {
    private static final int REQ_CAPTURE = 4401;
    private static final int PORT = 49200;
    private TextView accessStatus, addressText, codeText, hostStatus, deviceIdText;
    private String pairingCode;
    private String deviceId;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        pairingCode = getPreferences(MODE_PRIVATE).getString("code", null);
        if (pairingCode == null || pairingCode.length() != 6) {
            pairingCode = newCode();
            getPreferences(MODE_PRIVATE).edit().putString("code", pairingCode).apply();
        }
        deviceId = getPreferences(MODE_PRIVATE).getString("deviceId", null);
        if (deviceId == null || deviceId.length() != 9) {
            deviceId = newDeviceId();
            getPreferences(MODE_PRIVATE).edit().putString("deviceId", deviceId).apply();
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 60, 40, 40);

        TextView title = t("PHONE B — HOST v0.2", 26);
        root.addView(title);
        root.addView(t("1) Enable remote control once.\n2) Tap Start Host and approve screen capture.\n3) For this test build, connect from Phone A using the address + 6-digit PIN below.", 15));

        accessStatus = t("", 15);
        root.addView(accessStatus);
        Button access = new Button(this);
        access.setText("ENABLE REMOTE CONTROL");
        root.addView(access);

        deviceIdText = t("", 20);
        deviceIdText.setPadding(0, 28, 0, 6);
        root.addView(deviceIdText);

        codeText = t("", 24);
        root.addView(codeText);

        Button rotate = new Button(this);
        rotate.setText("GENERATE NEW 6-DIGIT PIN");
        root.addView(rotate);

        addressText = t("", 17);
        addressText.setPadding(0, 24, 0, 10);
        root.addView(addressText);

        Button start = new Button(this);
        start.setText("START HOST");
        root.addView(start);
        Button stop = new Button(this);
        stop.setText("STOP HOST");
        root.addView(stop);

        hostStatus = t("Host stopped", 15);
        root.addView(hostStatus);

        TextView note = t("Device ID is reserved for the internet/relay step. In this test build it does not yet replace the IP address.", 13);
        note.setPadding(0, 28, 0, 0);
        root.addView(note);

        TextView security = t("Security note: this is still a test build. Use the 6-digit PIN only for short testing sessions and rotate it after testing.", 13);
        security.setPadding(0, 16, 0, 0);
        root.addView(security);

        setContentView(root);

        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        rotate.setOnClickListener(v -> {
            pairingCode = newCode();
            getPreferences(MODE_PRIVATE).edit().putString("code", pairingCode).apply();
            refresh();
        });
        start.setOnClickListener(v -> requestProjection());
        stop.setOnClickListener(v -> {
            Intent i = new Intent(this, HostService.class);
            i.setAction(HostService.ACTION_STOP);
            startService(i);
            hostStatus.setText("Host stopped");
        });

        refresh();
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 99);
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        accessStatus.setText(RemoteAccessibilityService.isReady() ? "✓ Remote-control service enabled" : "⚠ Remote-control service not enabled yet");
        deviceIdText.setText("Remote ID:  " + formatDeviceId(deviceId));
        codeText.setText("6-digit PIN:  " + pairingCode);

        List<String> ips = NetUtil.ipv4Addresses();
        StringBuilder s = new StringBuilder("Temporary local address for Phone A:\n");
        if (ips.isEmpty()) s.append("No active IPv4 network found");
        else for (String ip : ips) s.append(ip).append(":").append(PORT).append("\n");
        addressText.setText(s.toString().trim());

        hostStatus.setText(HostService.isRunning() ? "✓ Host is running" : "Host stopped");
    }

    private void requestProjection() {
        if (!RemoteAccessibilityService.isReady())
            Toast.makeText(this, "Enable Remote Control first; viewing works without it, but touch won't.", Toast.LENGTH_LONG).show();
        MediaProjectionManager m = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(m.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            Intent i = new Intent(this, HostService.class);
            i.putExtra(HostService.EXTRA_RESULT_CODE, resultCode);
            i.putExtra(HostService.EXTRA_RESULT_DATA, data);
            i.putExtra(HostService.EXTRA_CODE, pairingCode);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            hostStatus.setText("Starting host…");
        }
    }

    private TextView t(String s, float size) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(size);
        v.setGravity(Gravity.START);
        v.setPadding(0, 10, 0, 10);
        return v;
    }

    private static String newCode() {
        SecureRandom r = new SecureRandom();
        return String.format(java.util.Locale.US, "%06d", r.nextInt(1_000_000));
    }

    private static String newDeviceId() {
        SecureRandom r = new SecureRandom();
        return String.format(java.util.Locale.US, "%09d", 100_000_000 + r.nextInt(900_000_000));
    }

    private static String formatDeviceId(String id) {
        if (id == null || id.length() != 9) return id;
        return id.substring(0,3) + " " + id.substring(3,6) + " " + id.substring(6,9);
    }
}
