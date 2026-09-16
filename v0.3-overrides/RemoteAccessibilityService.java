package com.remotephone.direct;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/** Host-side accessibility bridge. v0.3.1 adds simultaneous two-finger gestures. */
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
            AccessibilityNodeInfo root = s.getRootInActiveWindow();
            if (root == null) return;
            AccessibilityNodeInfo node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (node == null) return;
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        } catch (Exception ignored) {}
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
