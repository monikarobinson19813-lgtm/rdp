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
import java.util.List;
import java.util.Locale;

public class HostActivity extends Activity {
    private static final int REQ_CAPTURE = 4401;
    private static final int PORT = 49200;
    private TextView accessStatus, addressText, codeText, hostStatus, deviceIdText, batteryStatus, oemGuidance;
    private EditText friendlyName;
    private String pairingCode;
    private String deviceId;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        pairingCode = HostConfig.getOrCreateSessionPin(this);
        deviceId = HostConfig.getOrCreateRemoteId(this);
        HostConfig.getOrCreateRelayToken(this);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 56, 40, 40);
        scroll.addView(root);

        root.addView(t("HOST — v0.4.1", 27));
        root.addView(t("This is the phone kept at home/office and accessed remotely from your Controller.", 15));

        friendlyName = new EditText(this);
        friendlyName.setHint("Host name, e.g. Home Host or Office Host");
        friendlyName.setSingleLine(true);
        friendlyName.setText(HostConfig.getFriendlyName(this));
        root.addView(friendlyName);
        Button saveName = new Button(this);
        saveName.setText("SAVE HOST NAME");
        root.addView(saveName);

        accessStatus = t("", 15);
        root.addView(accessStatus);
        Button access = new Button(this);
        access.setText("ENABLE REMOTE CONTROL");
        root.addView(access);

        batteryStatus = t("", 15);
        batteryStatus.setPadding(0, 18, 0, 6);
        root.addView(batteryStatus);
        Button batterySettings = new Button(this);
        batterySettings.setText("BATTERY / BACKGROUND SETTINGS");
        root.addView(batterySettings);

        oemGuidance = t("", 13);
        oemGuidance.setPadding(0, 12, 0, 6);
        root.addView(oemGuidance);

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

        TextView batteryNote = t("For unattended access, allow RemotePhone to run in the background. On some phones you may also need to disable vendor battery restrictions or enable auto-start manually.", 13);
        batteryNote.setPadding(0, 18, 0, 6);
        root.addView(batteryNote);

        TextView audioNote = t("Remote audio needs Microphone permission only because Android requires RECORD_AUDIO permission for playback capture. RemotePhone does not use the Host microphone in v0.3.", 13);
        audioNote.setPadding(0, 18, 0, 6);
        root.addView(audioNote);

        TextView next = t("Normal v0.3 connection uses Remote ID + PIN. Advanced local address remains available only for same-network testing.", 13);
        next.setPadding(0, 16, 0, 8);
        root.addView(next);

        Button advanced = new Button(this);
        advanced.setText("SHOW ADVANCED LOCAL TEST ADDRESS");
        root.addView(advanced);
        addressText = t("", 15);
        addressText.setVisibility(View.GONE);
        root.addView(addressText);

        TextView security = t("Security: the 6-digit PIN only authorizes the encrypted session. Session encryption uses high-entropy ECDH keys and a persistent Host identity. Android unlock PIN/password/pattern is never stored by RemotePhone Direct.", 13);
        security.setPadding(0, 20, 0, 0);
        root.addView(security);

        setContentView(scroll);

        saveName.setOnClickListener(v -> {
            HostConfig.setFriendlyName(this, friendlyName.getText().toString());
            friendlyName.setText(HostConfig.getFriendlyName(this));
            Toast.makeText(this, "Host name saved", Toast.LENGTH_SHORT).show();
        });
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        batterySettings.setOnClickListener(v -> openBatterySettings());
        rotate.setOnClickListener(v -> {
            pairingCode = HostConfig.rotateSessionPin(this);
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
        pairingCode = HostConfig.getOrCreateSessionPin(this);
        deviceId = HostConfig.getOrCreateRemoteId(this);
        accessStatus.setText(RemoteAccessibilityService.isReady() ? "✓ Remote control enabled" : "⚠ Remote control not enabled yet");
        refreshBatteryStatus();
        refreshOemGuidance();
        deviceIdText.setText("Remote ID:  " + formatDeviceId(deviceId));
        codeText.setText("Session PIN:  " + pairingCode);

        List<String> ips = NetUtil.ipv4Addresses();
        StringBuilder s = new StringBuilder("Advanced local test address:\n");
        if (ips.isEmpty()) s.append("No active IPv4 network found");
        else for (String ip : ips) s.append(ip).append(":").append(PORT).append("\n");
        addressText.setText(s.toString().trim());

        hostStatus.setText(HostService.isRunning() ? "✓ Host ready" : "Host stopped");
    }

    private void refreshBatteryStatus() {
        if (batteryStatus == null) return;
        if (Build.VERSION.SDK_INT < 23) {
            batteryStatus.setText("✓ Android battery optimization not applicable on this version");
            return;
        }
        PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
        boolean unrestricted = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        batteryStatus.setText(unrestricted
                ? "✓ Android battery optimization: unrestricted"
                : "⚠ Android may restrict Host background activity");
    }

    private void refreshOemGuidance() {
        if (oemGuidance == null) return;
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(Locale.US);
        String message;
        if (maker.contains("oneplus") || maker.contains("oppo") || maker.contains("realme")) {
            message = "OEM tip: allow background activity, disable app battery optimization, and enable auto-launch/auto-start if shown by your phone.";
        } else if (maker.contains("xiaomi") || maker.contains("redmi") || maker.contains("poco")) {
            message = "OEM tip: set Battery saver to No restrictions and enable Autostart for RemotePhone if available.";
        } else if (maker.contains("vivo") || maker.contains("iqoo")) {
            message = "OEM tip: allow high background power usage and enable Autostart for RemotePhone if available.";
        } else if (maker.contains("samsung")) {
            message = "OEM tip: exclude RemotePhone from Sleeping/Deep sleeping apps and allow unrestricted battery use.";
        } else if (maker.contains("huawei") || maker.contains("honor")) {
            message = "OEM tip: allow manual app launch/background activity and exclude RemotePhone from aggressive power management.";
        } else {
            message = "OEM tip: if your phone has Auto-start, Background activity, Sleeping apps, or vendor battery controls, allow RemotePhone there.";
        }
        oemGuidance.setText(message);
    }

    private void openBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            catch (Exception ignored) {}
        }
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
            HostConfig.setFriendlyName(this, friendlyName.getText().toString());
            Intent i = new Intent(this, HostService.class);
            i.putExtra(HostService.EXTRA_RESULT_CODE, resultCode);
            i.putExtra(HostService.EXTRA_RESULT_DATA, data);
            i.putExtra(HostService.EXTRA_CODE, HostConfig.getOrCreateSessionPin(this));
            i.putExtra("remotephone.remote_id", HostConfig.getOrCreateRemoteId(this));
            i.putExtra("remotephone.host_name", HostConfig.getFriendlyName(this));
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            hostStatus.setText("Starting Host…");
            hostStatus.postDelayed(this::refresh, 250);
            hostStatus.postDelayed(this::refresh, 750);
            hostStatus.postDelayed(this::refresh, 1500);
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

    private static String formatDeviceId(String id) {
        if (id == null || id.length() != 9) return id;
        return id.substring(0,3) + " " + id.substring(3,6) + " " + id.substring(6,9);
    }
}
