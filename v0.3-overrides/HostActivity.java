package com.remotephone.direct;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.security.SecureRandom;
import java.util.List;

public class HostActivity extends Activity {
    private static final int REQ_CAPTURE = 4401;
    private static final int PORT = 49200;
    private TextView accessStatus, addressText, codeText, hostStatus, deviceIdText;
    private EditText friendlyName;
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

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 56, 40, 40);
        scroll.addView(root);

        root.addView(t("HOST — v0.3", 27));
        root.addView(t("This is the phone kept at home/office and accessed remotely from your Controller.", 15));

        friendlyName = new EditText(this);
        friendlyName.setHint("Host name, e.g. Home Host or Office Host");
        friendlyName.setSingleLine(true);
        friendlyName.setText(getPreferences(MODE_PRIVATE).getString("friendlyName", "Home Host"));
        root.addView(friendlyName);
        Button saveName = new Button(this);
        saveName.setText("SAVE HOST NAME");
        root.addView(saveName);

        accessStatus = t("", 15);
        root.addView(accessStatus);
        Button access = new Button(this);
        access.setText("ENABLE REMOTE CONTROL");
        root.addView(access);

        deviceIdText = t("", 22);
        deviceIdText.setPadding(0, 28, 0, 6);
        root.addView(deviceIdText);

        codeText = t("", 24);
        root.addView(codeText);

        Button rotate = new Button(this);
        rotate.setText("GENERATE NEW 6-DIGIT PIN");
        root.addView(rotate);

        Button start = new Button(this);
        start.setText("START HOST");
        root.addView(start);
        Button stop = new Button(this);
        stop.setText("STOP HOST");
        root.addView(stop);

        hostStatus = t("Host stopped", 16);
        root.addView(hostStatus);

        TextView audioNote = t("Remote audio needs Microphone permission only because Android requires RECORD_AUDIO permission for playback capture. RemotePhone does not use the Host microphone in v0.3.", 13);
        audioNote.setPadding(0, 18, 0, 6);
        root.addView(audioNote);

        TextView next = t("v0.3 target: your Controller will connect using Remote ID + PIN over the internet. Until that routing layer is active, the local test address remains available below.", 13);
        next.setPadding(0, 16, 0, 8);
        root.addView(next);

        Button advanced = new Button(this);
        advanced.setText("SHOW ADVANCED LOCAL TEST ADDRESS");
        root.addView(advanced);
        addressText = t("", 15);
        addressText.setVisibility(View.GONE);
        root.addView(addressText);

        TextView security = t("Security: Remote ID identifies this Host. The 6-digit PIN authorizes a session. Android unlock PIN/password/pattern is never stored by RemotePhone Direct.", 13);
        security.setPadding(0, 20, 0, 0);
        root.addView(security);

        setContentView(scroll);

        saveName.setOnClickListener(v -> {
            String n = friendlyName.getText().toString().trim();
            if (n.isEmpty()) n = "Host";
            getPreferences(MODE_PRIVATE).edit().putString("friendlyName", n).apply();
            friendlyName.setText(n);
            Toast.makeText(this, "Host name saved", Toast.LENGTH_SHORT).show();
        });
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        rotate.setOnClickListener(v -> {
            pairingCode = newCode();
            getPreferences(MODE_PRIVATE).edit().putString("code", pairingCode).apply();
            refresh();
        });
        advanced.setOnClickListener(v -> {
            boolean show = addressText.getVisibility() != View.VISIBLE;
            addressText.setVisibility(show ? View.VISIBLE : View.GONE);
            advanced.setText(show ? "HIDE ADVANCED LOCAL TEST ADDRESS" : "SHOW ADVANCED LOCAL TEST ADDRESS");
        });
        start.setOnClickListener(v -> requestProjection());
        stop.setOnClickListener(v -> {
            Intent i = new Intent(this, HostService.class);
            i.setAction(HostService.ACTION_STOP);
            startService(i);
            hostStatus.setText("Host stopped");
        });

        refresh();
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO}, 99);
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 98);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        accessStatus.setText(RemoteAccessibilityService.isReady() ? "✓ Remote control enabled" : "⚠ Remote control not enabled yet");
        deviceIdText.setText("Remote ID:  " + formatDeviceId(deviceId));
        codeText.setText("Session PIN:  " + pairingCode);

        List<String> ips = NetUtil.ipv4Addresses();
        StringBuilder s = new StringBuilder("Advanced local test address:\n");
        if (ips.isEmpty()) s.append("No active IPv4 network found");
        else for (String ip : ips) s.append(ip).append(":").append(PORT).append("\n");
        addressText.setText(s.toString().trim());

        hostStatus.setText(HostService.isRunning() ? "✓ Host ready" : "Host stopped");
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
            i.putExtra("remotephone.remote_id", deviceId);
            i.putExtra("remotephone.host_name", friendlyName.getText().toString().trim());
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            hostStatus.setText("Starting Host…");
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
