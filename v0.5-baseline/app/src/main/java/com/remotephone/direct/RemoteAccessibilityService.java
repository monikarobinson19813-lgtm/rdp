package com.remotephone.direct;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Host-side accessibility bridge.
 * v0.3.1 adds simultaneous two-finger gestures.
 * v0.3.2 adds a best-effort, user-initiated lock-screen credential submit path.
 * Credentials are never stored by this service.
 */
public class RemoteAccessibilityService extends AccessibilityService {
    private static volatile RemoteAccessibilityService instance;
    private static final Object CREDENTIAL_SURFACE_MONITOR = new Object();

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        synchronized (CREDENTIAL_SURFACE_MONITOR) {
            CREDENTIAL_SURFACE_MONITOR.notifyAll();
        }
    }
    @Override public void onInterrupt() {}

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    public static boolean isReady() {
        return instance != null;
    }

    public interface RecoveryFrameCallback {
        void onFrame(int width, int height, byte[] jpeg);
        void onFailure(int errorCode);
    }

    /**
     * Emergency post-reboot view for Android 11+ when MediaProjection consent is
     * unavailable. Secure windows remain blocked by Android.
     */
    public static boolean requestRecoveryFrame(RecoveryFrameCallback callback) {
        RemoteAccessibilityService s = instance;
        if (s == null || callback == null || Build.VERSION.SDK_INT < 30) return false;
        try {
            s.takeScreenshot(Display.DEFAULT_DISPLAY, s.getMainExecutor(),
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override public void onSuccess(AccessibilityService.ScreenshotResult result) {
                            HardwareBuffer buffer = result.getHardwareBuffer();
                            Bitmap hardware = null;
                            Bitmap software = null;
                            Bitmap output = null;
                            try {
                                ColorSpace colorSpace = result.getColorSpace();
                                hardware = Bitmap.wrapHardwareBuffer(buffer, colorSpace);
                                if (hardware == null) throw new IllegalStateException("Screenshot bitmap unavailable");
                                software = hardware.copy(Bitmap.Config.ARGB_8888, false);
                                if (software == null) throw new IllegalStateException("Screenshot copy unavailable");
                                int outW = Math.min(720, software.getWidth());
                                int outH = Math.max(2, (int)Math.round((double)software.getHeight() * outW / Math.max(1, software.getWidth())));
                                if ((outH & 1) == 1) outH--;
                                output = outW == software.getWidth() && outH == software.getHeight()
                                        ? software
                                        : Bitmap.createScaledBitmap(software, outW, outH, true);
                                ByteArrayOutputStream jpeg = new ByteArrayOutputStream(120_000);
                                if (!output.compress(Bitmap.CompressFormat.JPEG, 42, jpeg))
                                    throw new IllegalStateException("Screenshot compression failed");
                                callback.onFrame(output.getWidth(), output.getHeight(), jpeg.toByteArray());
                            } catch (Exception e) {
                                callback.onFailure(-1);
                            } finally {
                                try { if (output != null && output != software) output.recycle(); } catch (Exception ignored) {}
                                try { if (software != null) software.recycle(); } catch (Exception ignored) {}
                                try { if (hardware != null) hardware.recycle(); } catch (Exception ignored) {}
                                try { if (buffer != null) buffer.close(); } catch (Exception ignored) {}
                            }
                        }

                        @Override public void onFailure(int errorCode) {
                            callback.onFailure(errorCode);
                        }
                    });
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void global(int action) {
        RemoteAccessibilityService s = instance;
        if (s != null) s.performGlobalAction(action);
    }

    public static void setFocusedText(String text) {
        RemoteAccessibilityService s = instance;
        if (s == null || text == null) return;
        try {
            KeyguardManager km = (KeyguardManager)s.getSystemService(KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardLocked()) {
                // Generic remote text must never become a lock-screen credential path.
                return;
            }

            AccessibilityNodeInfo root = s.getRootInActiveWindow();
            if (root == null) return;
            AccessibilityNodeInfo node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (node == null) return;
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        } catch (Exception ignored) {}
    }

    public static boolean awaitCredentialSurface(byte method, long timeoutMs) {
        long deadline = android.os.SystemClock.elapsedRealtime() + Math.max(0L, timeoutMs);
        do {
            if (isCredentialSurfaceReady(method)) return true;
            long remaining = deadline - android.os.SystemClock.elapsedRealtime();
            if (remaining <= 0L) return false;
            synchronized (CREDENTIAL_SURFACE_MONITOR) {
                try {
                    CREDENTIAL_SURFACE_MONITOR.wait(Math.min(remaining, 250L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        } while (true);
    }

    public static boolean awaitKeyguardGone(long timeoutMs) {
        RemoteAccessibilityService s = instance;
        if (s == null) return false;
        long deadline = android.os.SystemClock.elapsedRealtime() + Math.max(0L, timeoutMs);
        do {
            if (!isKeyguardLocked(s)) return true;
            long remaining = deadline - android.os.SystemClock.elapsedRealtime();
            if (remaining <= 0L) return false;
            synchronized (CREDENTIAL_SURFACE_MONITOR) {
                try {
                    CREDENTIAL_SURFACE_MONITOR.wait(Math.min(remaining, 250L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        } while (true);
    }

    public static boolean isCredentialSurfaceReady(byte method) {
        RemoteAccessibilityService s = instance;
        if (s == null || !isKeyguardLocked(s)) return false;
        try {
            AccessibilityNodeInfo root = s.getRootInActiveWindow();
            if (root == null) return false;
            if (method == CryptoChannel.UNLOCK_PIN) {
                AccessibilityNodeInfo entry = findEditable(root);
                if (!isActiveCredentialNode(entry)) return false;
                for (char digit = '0'; digit <= '9'; digit++) {
                    AccessibilityNodeInfo key = findNodeByExactLabel(root, String.valueOf(digit));
                    if (key == null || !key.isVisibleToUser() || !key.isEnabled()) return false;
                }
                return true;
            }
            if (method == CryptoChannel.UNLOCK_PATTERN) {
                return isActiveCredentialNode(findPatternNode(root));
            }
        } catch (Exception ignored) {}
        return false;
    }

    public static boolean submitKnownPin(byte[] pin) {
        RemoteAccessibilityService s = instance;
        if (s == null || pin == null || pin.length < 4 || pin.length > 16) return false;
        for (byte b : pin) if (b < '0' || b > '9') return false;
        if (!isCredentialSurfaceReady(CryptoChannel.UNLOCK_PIN)) return false;
        try {
            for (byte b : pin) {
                // Re-check immediately before every digit delivery.
                if (!isCredentialSurfaceReady(CryptoChannel.UNLOCK_PIN)) return false;
                AccessibilityNodeInfo root = s.getRootInActiveWindow();
                if (root == null) return false;
                AccessibilityNodeInfo digit = findNodeByExactLabel(root, String.valueOf((char)b));
                if (digit == null || !clickNodeOrParent(digit)) return false;
            }
            AccessibilityNodeInfo root = s.getRootInActiveWindow();
            clickConfirmIfPresent(root);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Best-effort only; no blind coordinate guessing is used. */
    public static boolean submitKnownPattern(byte[] pattern) {
        RemoteAccessibilityService s = instance;
        if (s == null || pattern == null) return false;
        byte[] digits = new byte[pattern.length];
        int count = 0;
        boolean[] used = new boolean[10];
        try {
            for (byte b : pattern) {
                if (b < '1' || b > '9') continue;
                int n = b - '0';
                if (used[n]) return false;
                used[n] = true;
                digits[count++] = b;
            }
            if (count < 4 || count > 9) return false;
            if (!isCredentialSurfaceReady(CryptoChannel.UNLOCK_PATTERN)) return false;

            AccessibilityNodeInfo root = s.getRootInActiveWindow();
            AccessibilityNodeInfo patternNode = findPatternNode(root);
            if (!isActiveCredentialNode(patternNode)) return false;
            Rect bounds = new Rect();
            patternNode.getBoundsInScreen(bounds);
            if (bounds.width() < 90 || bounds.height() < 90) return false;

            float cellW = bounds.width() / 3f;
            float cellH = bounds.height() / 3f;
            Path path = new Path();
            for (int i = 0; i < count; i++) {
                int index = digits[i] - '1';
                int row = index / 3;
                int col = index % 3;
                float x = bounds.left + (col + 0.5f) * cellW;
                float y = bounds.top + (row + 0.5f) * cellH;
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }

            // Re-check immediately before the single pattern gesture dispatch.
            if (!isCredentialSurfaceReady(CryptoChannel.UNLOCK_PATTERN)) return false;
            long duration = Math.max(300L, count * 120L);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, duration))
                    .build();
            return s.dispatchGesture(gesture, null, null);
        } catch (Exception ignored) {
            return false;
        } finally {
            java.util.Arrays.fill(digits, (byte)0);
        }
    }

    private static boolean isKeyguardLocked(RemoteAccessibilityService s) {
        try {
            KeyguardManager km = (KeyguardManager)s.getSystemService(KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isActiveCredentialNode(AccessibilityNodeInfo node) {
        // Focusable alone is not enough: require Android to report the credential
        // entry surface as actually focused/active before any credential delivery.
        return node != null && node.isVisibleToUser() && node.isEnabled() &&
                (node.isFocused() || node.isAccessibilityFocused());
    }

    private static AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findEditable(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private static AccessibilityNodeInfo findNodeByExactLabel(AccessibilityNodeInfo root, String label) {
        if (root == null || label == null) return null;
        List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(label);
        if (matches != null) {
            for (AccessibilityNodeInfo n : matches) {
                if (n == null) continue;
                CharSequence t = n.getText();
                CharSequence d = n.getContentDescription();
                if ((t != null && label.contentEquals(t)) || (d != null && label.contentEquals(d))) return n;
            }
        }
        return findNodeRecursive(root, label);
    }

    private static AccessibilityNodeInfo findNodeRecursive(AccessibilityNodeInfo node, String label) {
        if (node == null) return null;
        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        if ((t != null && label.equalsIgnoreCase(t.toString().trim())) ||
                (d != null && label.equalsIgnoreCase(d.toString().trim()))) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findNodeRecursive(node.getChild(i), label);
            if (found != null) return found;
        }
        return null;
    }

    private static AccessibilityNodeInfo findPatternNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        String className = node.getClassName() == null ? "" : node.getClassName().toString().toLowerCase(java.util.Locale.US);
        String viewId = node.getViewIdResourceName() == null ? "" : node.getViewIdResourceName().toLowerCase(java.util.Locale.US);
        if (className.contains("lockpatternview") || viewId.contains("lockpatternview") || viewId.contains("lock_pattern_view")) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findPatternNode(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private static boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        for (int i = 0; cur != null && i < 5; i++) {
            if (cur.isClickable() && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            cur = cur.getParent();
        }
        return false;
    }

    private static void clickConfirmIfPresent(AccessibilityNodeInfo root) {
        if (root == null) return;
        String[] labels = new String[]{"OK", "Enter", "Done", "Unlock", "Submit"};
        for (String label : labels) {
            AccessibilityNodeInfo node = findNodeRecursive(root, label);
            if (node != null && clickNodeOrParent(node)) return;
        }
    }

    public static void gesture(float x1, float y1, float x2, float y2, long duration) {
        RemoteAccessibilityService s = instance;
        if (s == null) return;
        try {
            if (x1 < 0f) {
                dispatchMulti(s, x1, y1, x2, y2, duration);
                return;
            }

            long ms = Math.max(40, Math.min(3000, duration));
            Path path = new Path();
            path.moveTo(x1, y1);
            path.lineTo(x2, y2);
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, ms))
                    .build();
            s.dispatchGesture(g, null, null);
        } catch (Exception ignored) {}
    }

    private static void dispatchMulti(RemoteAccessibilityService s,
                                      float encodedX1, float start1Ypx,
                                      float start2Xpx, float start2Ypx,
                                      long packed) {
        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager)s.getSystemService(WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(dm);
        float w = Math.max(1f, dm.widthPixels);
        float h = Math.max(1f, dm.heightPixels);

        float start1X = clamp01((-encodedX1 / w) - 1f) * w;
        float start1Y = clamp01(start1Ypx / h) * h;
        float start2X = clamp01(start2Xpx / w) * w;
        float start2Y = clamp01(start2Ypx / h) * h;

        float end1X = uq13(packed, 0) * w;
        float end1Y = uq13(packed, 13) * h;
        float end2X = uq13(packed, 26) * w;
        float end2Y = uq13(packed, 39) * h;
        long ms = (packed >>> 52) & 0xFFFL;
        ms = Math.max(40, Math.min(3000, ms));

        Path p1 = new Path();
        p1.moveTo(start1X, start1Y);
        p1.lineTo(end1X, end1Y);

        Path p2 = new Path();
        p2.moveTo(start2X, start2Y);
        p2.lineTo(end2X, end2Y);

        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p1, 0, ms))
                .addStroke(new GestureDescription.StrokeDescription(p2, 0, ms))
                .build();
        s.dispatchGesture(g, null, null);
    }

    private static float uq13(long packed, int shift) {
        return ((packed >>> shift) & 0x1FFFL) / 8191f;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
