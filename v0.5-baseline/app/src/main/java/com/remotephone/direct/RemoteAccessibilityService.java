package com.remotephone.direct;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * Host-side accessibility bridge.
 * v0.3.1 adds simultaneous two-finger gestures.
 * v0.3.2 adds a best-effort, user-initiated lock-screen credential submit path.
 * Credentials are never stored by this service.
 */
public class RemoteAccessibilityService extends AccessibilityService {
    private static volatile RemoteAccessibilityService instance;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    public static boolean isReady() {
        return instance != null;
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
                submitUnlockCredential(s, text);
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

    public static boolean submitKnownPin(String pin) {
        RemoteAccessibilityService s = instance;
        if (s == null || pin == null || pin.length() < 4 || pin.length() > 16) return false;
        for (int i = 0; i < pin.length(); i++) if (!Character.isDigit(pin.charAt(i))) return false;
        return submitUnlockCredential(s, pin);
    }

    /** Best-effort only; no blind coordinate guessing is used. */
    public static boolean submitKnownPattern(String pattern) {
        RemoteAccessibilityService s = instance;
        if (s == null || pattern == null) return false;
        String digits = pattern.replaceAll("[^1-9]", "");
        if (digits.length() < 4 || digits.length() > 9) return false;
        boolean[] used = new boolean[10];
        for (int i = 0; i < digits.length(); i++) {
            int n = digits.charAt(i) - '0';
            if (used[n]) return false;
            used[n] = true;
        }
        try {
            AccessibilityNodeInfo root = s.getRootInActiveWindow();
            AccessibilityNodeInfo patternNode = findPatternNode(root);
            if (patternNode == null) return false;
            Rect bounds = new Rect();
            patternNode.getBoundsInScreen(bounds);
            if (bounds.width() < 90 || bounds.height() < 90) return false;
            float cellW = bounds.width() / 3f;
            float cellH = bounds.height() / 3f;
            Path path = new Path();
            for (int i = 0; i < digits.length(); i++) {
                int index = digits.charAt(i) - '1';
                int row = index / 3;
                int col = index % 3;
                float x = bounds.left + (col + 0.5f) * cellW;
                float y = bounds.top + (row + 0.5f) * cellH;
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            long duration = Math.max(300L, digits.length() * 120L);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, duration))
                    .build();
            return s.dispatchGesture(gesture, null, null);
        } catch (Exception ignored) { return false; }
    }

    /**
     * Best-effort only. Android/OEM policy may hide or block secure keyguard nodes.
     * Supports editable password/PIN fields when exposed, and PIN keypads whose
     * digit buttons are exposed to Accessibility.
     */
    private static boolean submitUnlockCredential(RemoteAccessibilityService s, String credential) {
        if (credential == null || credential.length() < 1 || credential.length() > 64) return false;
        try {
            AccessibilityNodeInfo root = s.getRootInActiveWindow();
            if (root == null) return false;

            AccessibilityNodeInfo editable = findEditable(root);
            if (editable != null) {
                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, credential);
                boolean set = editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                if (!set) return false;
                root = s.getRootInActiveWindow();
                clickConfirmIfPresent(root);
                return true;
            }

            for (int i = 0; i < credential.length(); i++) {
                char ch = credential.charAt(i);
                if (!Character.isDigit(ch)) return false;
                root = s.getRootInActiveWindow();
                if (root == null) return false;
                AccessibilityNodeInfo digit = findNodeByExactLabel(root, String.valueOf(ch));
                if (digit == null || !clickNodeOrParent(digit)) return false;
                try { Thread.sleep(55); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
            }

            root = s.getRootInActiveWindow();
            clickConfirmIfPresent(root);
            return true;
        } catch (Exception ignored) {
            return false;
        }
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
