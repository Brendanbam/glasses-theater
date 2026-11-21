package com.brendanbam.glassestheater;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/**
 * A manga page that can be zoomed and panned: pinch, double-tap, drag, and controller panning.
 * Scale types follow Mihon (fit screen / width / height, original size, smart fit). Wide pages
 * that overflow start at the edge you read from first (right edge in right-to-left mode).
 * Taps, swipes and long presses are passed to the reader.
 */
class ZoomImageView extends ImageView {

    interface Listener {
        void onTap(float x, float y);
        void onSwipe(float vx, float vy);
        void onLongPress();
    }

    private final Matrix matrix = new Matrix();
    private final float[] values = new float[9];
    private final ScaleGestureDetector scaler;
    private final GestureDetector gestures;
    private Listener listener;
    private int scaleType = ReaderPrefs.SCALE_SMART;
    private boolean rtl, doubleTapZoom = true;
    private float baseScale = 1f;
    private boolean scaling;

    ZoomImageView(Context c) {
        super(c);
        setScaleType(ScaleType.MATRIX);
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector d) { scaling = true; return true; }
            @Override public boolean onScale(ScaleGestureDetector d) {
                zoomBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
            @Override public void onScaleEnd(ScaleGestureDetector d) { scaling = false; }
        });
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (listener != null) listener.onTap(e.getX(), e.getY());
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (!doubleTapZoom) { if (listener != null) listener.onTap(e.getX(), e.getY()); return true; }
                if (isZoomed()) animateTo(baseScale, e.getX(), e.getY(), true);
                else animateTo(baseScale * 2.2f, e.getX(), e.getY(), false);
                return true;
            }
            @Override public void onLongPress(MotionEvent e) { if (listener != null) listener.onLongPress(); }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (scaling) return true;
                panBy(-dx, -dy);
                return true;
            }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (scaling || listener == null) return false;
                // Only a page turn when the page can't scroll further that way
                boolean horizontal = Math.abs(vx) > Math.abs(vy);
                if (horizontal && !canPan(vx > 0 ? 1 : -1, 0)) listener.onSwipe(vx, 0);
                else if (!horizontal && !canPan(0, vy > 0 ? 1 : -1)) listener.onSwipe(0, vy);
                return true;
            }
        });
    }

    void setListener(Listener l) { listener = l; }

    void configure(int scaleType, boolean rtl, boolean doubleTapZoom) {
        this.scaleType = scaleType;
        this.rtl = rtl;
        this.doubleTapZoom = doubleTapZoom;
        reset();
    }

    @Override
    public void setImageBitmap(Bitmap bm) {
        super.setImageBitmap(bm);
        reset();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        reset();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaler.onTouchEvent(e);
        gestures.onTouchEvent(e);
        return true;
    }

    /** Fits the page per the scale type and moves it to its starting position. */
    void reset() {
        if (getDrawable() == null || getWidth() == 0) return;
        float vw = getWidth(), vh = getHeight();
        float bw = getDrawable().getIntrinsicWidth(), bh = getDrawable().getIntrinsicHeight();
        if (bw <= 0 || bh <= 0) return;
        float fit = Math.min(vw / bw, vh / bh);
        switch (scaleType) {
            case ReaderPrefs.SCALE_WIDTH: baseScale = vw / bw; break;
            case ReaderPrefs.SCALE_HEIGHT: baseScale = vh / bh; break;
            case ReaderPrefs.SCALE_ORIGINAL: baseScale = getResources().getDisplayMetrics().density / 2.5f; break;
            case ReaderPrefs.SCALE_SMART:
                // Wide pages (spreads) are zoomed to full height and panned, like Mihon's auto-zoom
                baseScale = (bw / bh > (vw / vh) * 1.25f) ? vh / bh : fit;
                break;
            default: baseScale = fit;
        }
        matrix.reset();
        matrix.postScale(baseScale, baseScale);
        float cw = bw * baseScale, ch = bh * baseScale;
        float tx = cw <= vw ? (vw - cw) / 2 : (rtl ? vw - cw : 0);
        float ty = ch <= vh ? (vh - ch) / 2 : 0;
        matrix.postTranslate(tx, ty);
        setImageMatrix(matrix);
    }

    float scale() {
        matrix.getValues(values);
        return values[Matrix.MSCALE_X];
    }

    boolean isZoomed() { return scale() > baseScale * 1.05f; }

    private RectF content() {
        RectF r = new RectF(0, 0, getDrawable() == null ? 0 : getDrawable().getIntrinsicWidth(),
                getDrawable() == null ? 0 : getDrawable().getIntrinsicHeight());
        matrix.mapRect(r);
        return r;
    }

    /** Whether the page can move in this direction (dx/dy = +1 shows more of the left/top). */
    boolean canPan(int dx, int dy) {
        RectF r = content();
        float slop = 2f;
        if (dx > 0) return r.left < -slop;
        if (dx < 0) return r.right > getWidth() + slop;
        if (dy > 0) return r.top < -slop;
        if (dy < 0) return r.bottom > getHeight() + slop;
        return false;
    }

    /** Moves the page; returns false if it was already at that edge (so the reader can turn the page). */
    boolean panBy(float dx, float dy) {
        RectF before = content();
        matrix.postTranslate(dx, dy);
        clamp();
        setImageMatrix(matrix);
        RectF after = content();
        return Math.abs(after.left - before.left) > 0.5f || Math.abs(after.top - before.top) > 0.5f;
    }

    private void zoomBy(float factor, float fx, float fy) {
        float s = scale();
        float target = Math.max(baseScale, Math.min(baseScale * 5f, s * factor));
        matrix.postScale(target / s, target / s, fx, fy);
        clamp();
        setImageMatrix(matrix);
    }

    private void animateTo(float target, float fx, float fy, boolean thenReset) {
        float start = scale();
        ValueAnimator a = ValueAnimator.ofFloat(start, target);
        a.setDuration(220);
        final float[] last = {start};
        a.addUpdateListener(v -> {
            float now = (float) v.getAnimatedValue();
            matrix.postScale(now / last[0], now / last[0], fx, fy);
            last[0] = now;
            clamp();
            setImageMatrix(matrix);
        });
        if (thenReset) a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) { reset(); }
        });
        a.start();
    }

    /** Keeps the page covering the view when bigger than it, centered when smaller. */
    private void clamp() {
        RectF r = content();
        float vw = getWidth(), vh = getHeight();
        float dx = 0, dy = 0;
        if (r.width() <= vw) dx = (vw - r.width()) / 2 - r.left;
        else if (r.left > 0) dx = -r.left;
        else if (r.right < vw) dx = vw - r.right;
        if (r.height() <= vh) dy = (vh - r.height()) / 2 - r.top;
        else if (r.top > 0) dy = -r.top;
        else if (r.bottom < vh) dy = vh - r.bottom;
        matrix.postTranslate(dx, dy);
    }

    /** Controller zoom (in = true) around the center. */
    void zoomStep(boolean in) {
        zoomBy(in ? 1.35f : 1 / 1.35f, getWidth() / 2f, getHeight() / 2f);
    }
}
