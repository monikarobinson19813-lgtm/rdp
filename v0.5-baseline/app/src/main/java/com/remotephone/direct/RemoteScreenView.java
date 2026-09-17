package com.remotephone.direct;

import android.content.Context;
import android.graphics.*;
import android.view.*;

/**
 * Controller-side remote screen surface.
 * v0.3.1 adds two-finger gesture capture while keeping the existing
 * single-gesture wire format intact for normal taps/swipes.
 */
public class RemoteScreenView extends View {
    public interface GestureSink { void onGesture(float x1,float y1,float x2,float y2,long durationMs); }

    private Bitmap bitmap;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();
    private GestureSink sink;

    private float downX, downY;
    private long downAt;
    private boolean validDown;
    private boolean fillMode = true;

    private boolean multiTouch;
    private int p1Id = -1, p2Id = -1;
    private float p1StartX, p1StartY, p2StartX, p2StartY;
    private float p1EndX, p1EndY, p2EndX, p2EndY;
    private long multiDownAt;

    public RemoteScreenView(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
        setKeepScreenOn(true);
    }

    public void setGestureSink(GestureSink s) { sink = s; }
    public void setFillMode(boolean fill) { fillMode = fill; invalidate(); }
    public boolean isFillMode() { return fillMode; }

    public synchronized void setFrame(Bitmap b) {
        Bitmap old = bitmap;
        bitmap = b;
        if (old != null && old != b) old.recycle();
        postInvalidate();
    }

    @Override protected synchronized void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null || getWidth() <= 0 || getHeight() <= 0) return;

        float sx = (float)getWidth() / bitmap.getWidth();
        float sy = (float)getHeight() / bitmap.getHeight();
        float scale = fillMode ? Math.max(sx, sy) : Math.min(sx, sy);
        float w = bitmap.getWidth() * scale;
        float h = bitmap.getHeight() * scale;
        float l = (getWidth() - w) / 2f;
        float t = (getHeight() - h) / 2f;
        dst.set(l, t, l + w, t + h);
        canvas.drawBitmap(bitmap, null, dst, paint);
    }

    private float nx(float x) {
        return Math.max(0f, Math.min(1f, (x - dst.left) / Math.max(1f, dst.width())));
    }

    private float ny(float y) {
        return Math.max(0f, Math.min(1f, (y - dst.top) / Math.max(1f, dst.height())));
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (bitmap == null || sink == null) return true;
        int action = e.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            resetMulti();
            validDown = dst.contains(e.getX(), e.getY());
            if (!validDown) return true;
            p1Id = e.getPointerId(0);
            downX = nx(e.getX());
            downY = ny(e.getY());
            downAt = System.currentTimeMillis();
            return true;
        }

        if (action == MotionEvent.ACTION_POINTER_DOWN && e.getPointerCount() >= 2 && validDown) {
            int secondIndex = e.getActionIndex();
            int firstIndex = e.findPointerIndex(p1Id);
            if (firstIndex < 0) firstIndex = 0;
            if (secondIndex == firstIndex) secondIndex = firstIndex == 0 ? 1 : 0;

            p2Id = e.getPointerId(secondIndex);
            p1StartX = nx(e.getX(firstIndex));
            p1StartY = ny(e.getY(firstIndex));
            p2StartX = nx(e.getX(secondIndex));
            p2StartY = ny(e.getY(secondIndex));
            p1EndX = p1StartX; p1EndY = p1StartY;
            p2EndX = p2StartX; p2EndY = p2StartY;
            multiDownAt = System.currentTimeMillis();
            multiTouch = true;
            return true;
        }

        if (action == MotionEvent.ACTION_MOVE && multiTouch) {
            updateMultiEnds(e);
            return true;
        }

        if (action == MotionEvent.ACTION_POINTER_UP && multiTouch) {
            updateMultiEnds(e);
            sendMultiGesture();
            multiTouch = false;
            validDown = false;
            return true;
        }

        if (action == MotionEvent.ACTION_UP) {
            if (multiTouch) {
                updateMultiEnds(e);
                sendMultiGesture();
                multiTouch = false;
                validDown = false;
                return true;
            }
            if (validDown) {
                validDown = false;
                float x2 = nx(e.getX()), y2 = ny(e.getY());
                long d = Math.max(70, Math.min(1200, System.currentTimeMillis() - downAt));
                float dx = x2 - downX, dy = y2 - downY;
                if (dx * dx + dy * dy < 0.00035f) {
                    x2 = downX + 0.0001f;
                    y2 = downY + 0.0001f;
                    d = 70;
                }
                sink.onGesture(downX, downY, x2, y2, d);
            }
            return true;
        }

        if (action == MotionEvent.ACTION_CANCEL) {
            validDown = false;
            resetMulti();
        }
        return true;
    }

    private void updateMultiEnds(MotionEvent e) {
        int i1 = e.findPointerIndex(p1Id);
        int i2 = e.findPointerIndex(p2Id);
        if (i1 >= 0) {
            p1EndX = nx(e.getX(i1));
            p1EndY = ny(e.getY(i1));
        }
        if (i2 >= 0) {
            p2EndX = nx(e.getX(i2));
            p2EndY = ny(e.getY(i2));
        }
    }

    private void sendMultiGesture() {
        long duration = Math.max(70, Math.min(3000, System.currentTimeMillis() - multiDownAt));
        long packed = packMultiEnd(p1EndX, p1EndY, p2EndX, p2EndY, duration);

        // Backward-compatible sentinel carried through the existing TYPE_GESTURE packet.
        // HostService scales x1 by display width; RemoteAccessibilityService detects x1 < 0
        // and decodes the packed second-finger gesture.
        sink.onGesture(-1f - p1StartX, p1StartY, p2StartX, p2StartY, packed);
    }

    private static long packMultiEnd(float x1, float y1, float x2, float y2, long duration) {
        long a = q13(x1), b = q13(y1), c = q13(x2), d = q13(y2);
        long ms = Math.max(1, Math.min(4095, duration));
        return a | (b << 13) | (c << 26) | (d << 39) | (ms << 52);
    }

    private static int q13(float v) {
        float c = Math.max(0f, Math.min(1f, v));
        return Math.round(c * 8191f);
    }

    private void resetMulti() {
        multiTouch = false;
        p2Id = -1;
    }
}
