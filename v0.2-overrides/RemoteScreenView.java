package com.remotephone.direct;

import android.content.Context;
import android.graphics.*;
import android.view.*;

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
            validDown = dst.contains(e.getX(), e.getY());
            if (!validDown) return true;
            downX = nx(e.getX());
            downY = ny(e.getY());
            downAt = System.currentTimeMillis();
            return true;
        }
        if (action == MotionEvent.ACTION_UP && validDown) {
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
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) validDown = false;
        return true;
    }
}
