package com.eurobuddha.freezepeach;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/** Full-screen photo viewer view: pinch-to-zoom, drag-to-pan, double-tap to toggle, single tap to dismiss. */
public class ZoomImageView extends ImageView {

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector tapDetector;
    private float scale = 1f;
    private Runnable onDismiss;

    public ZoomImageView(Context c) {
        super(c);
        setScaleType(ScaleType.FIT_CENTER);
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                scale = Math.max(1f, Math.min(4f, scale * d.getScaleFactor()));
                setScaleX(scale); setScaleY(scale);
                if (scale == 1f) { setTranslationX(0); setTranslationY(0); }
                return true;
            }
        });
        tapDetector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (scale > 1.01f) { animate().scaleX(1f).scaleY(1f).translationX(0).translationY(0).setDuration(180).start(); scale = 1f; }
                else if (onDismiss != null) onDismiss.run();
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                scale = scale > 1.01f ? 1f : 2.5f;
                animate().scaleX(scale).scaleY(scale).translationX(0).translationY(0).setDuration(200).start();
                return true;
            }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (scale > 1.01f) { setTranslationX(getTranslationX() - dx); setTranslationY(getTranslationY() - dy); }
                return true;
            }
        });
    }

    public void setOnDismiss(Runnable r) { this.onDismiss = r; }

    @Override public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        tapDetector.onTouchEvent(e);
        return true;
    }
}
