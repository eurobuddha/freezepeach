package com.eurobuddha.freezepeach;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Image-editor canvas: draws the working bitmap with a live ColorMatrix filter + freehand strokes,
 *  supports 90° rotation, and bakes everything into a final full-resolution bitmap. */
public class EditorView extends View {

    private Bitmap base;
    private final Paint imgPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final List<Stroke> strokes = new ArrayList<>();
    private Stroke current;
    private boolean drawMode = false;
    private int drawColor = 0xFFFF5C5C;
    private float drawWidthDp = 7f;
    private final RectF fit = new RectF();
    private final float density;
    private boolean cropMode = false;
    private final RectF crop = new RectF();
    private int dragCorner = -1;
    private boolean moving = false;
    private float lastX, lastY;

    private static final class Stroke { final List<float[]> pts = new ArrayList<>(); int color; float widthDp; }

    public EditorView(Context c) { super(c); density = c.getResources().getDisplayMetrics().density; }

    public void setBitmap(Bitmap b) { base = b; requestLayout(); invalidate(); }
    public Bitmap getBitmap() { return base; }
    public void setColorMatrix(ColorMatrix cm) { imgPaint.setColorFilter(new ColorMatrixColorFilter(cm)); invalidate(); }
    public void setDrawMode(boolean on) { drawMode = on; }
    public void setDrawColor(int c) { drawColor = c; }
    public void undo() { if (!strokes.isEmpty()) { strokes.remove(strokes.size() - 1); invalidate(); } }

    public void rotate90() {
        if (base == null) return;
        Matrix m = new Matrix(); m.postRotate(90);
        base = Bitmap.createBitmap(base, 0, 0, base.getWidth(), base.getHeight(), m, true);
        for (Stroke s : strokes) for (float[] p : s.pts) { float nx = p[0], ny = p[1]; p[0] = 1f - ny; p[1] = nx; }
        requestLayout(); invalidate();
    }

    private void computeFit() {
        if (base == null || getWidth() == 0) { fit.set(0, 0, 0, 0); return; }
        float vw = getWidth(), vh = getHeight(), bw = base.getWidth(), bh = base.getHeight();
        float scale = Math.min(vw / bw, vh / bh);
        float w = bw * scale, h = bh * scale, l = (vw - w) / 2, t = (vh - h) / 2;
        fit.set(l, t, l + w, t + h);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (base == null) return;
        computeFit();
        canvas.drawBitmap(base, null, fit, imgPaint);
        for (Stroke s : strokes) drawStroke(canvas, s, fit, 1f);
        if (current != null) drawStroke(canvas, current, fit, 1f);
        if (cropMode) drawCropOverlay(canvas);
    }

    private void drawStroke(Canvas canvas, Stroke s, RectF r, float widthScale) {
        if (s.pts.isEmpty()) return;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE); p.setColor(s.color);
        p.setStrokeWidth(s.widthDp * density * widthScale);
        p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        Path path = new Path();
        for (int i = 0; i < s.pts.size(); i++) {
            float x = r.left + s.pts.get(i)[0] * r.width(), y = r.top + s.pts.get(i)[1] * r.height();
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        if (s.pts.size() == 1) canvas.drawPoint(r.left + s.pts.get(0)[0] * r.width(), r.top + s.pts.get(0)[1] * r.height(), p);
        else canvas.drawPath(path, p);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (cropMode && base != null) return handleCrop(e);
        if (!drawMode || base == null) return false;
        computeFit();
        if (fit.width() <= 0) return false;
        float nx = Math.max(0, Math.min(1, (e.getX() - fit.left) / fit.width()));
        float ny = Math.max(0, Math.min(1, (e.getY() - fit.top) / fit.height()));
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                current = new Stroke(); current.color = drawColor; current.widthDp = drawWidthDp;
                current.pts.add(new float[]{ nx, ny }); invalidate(); return true;
            case MotionEvent.ACTION_MOVE:
                if (current != null) { current.pts.add(new float[]{ nx, ny }); invalidate(); } return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (current != null) { strokes.add(current); current = null; invalidate(); } return true;
        }
        return true;
    }

    // ---- crop ----
    public void setCropMode(boolean on) {
        cropMode = on;
        if (on) { drawMode = false; computeFit(); crop.set(fit); }
        invalidate();
    }
    public void applyCrop() {
        if (!cropMode || base == null) { cropMode = false; invalidate(); return; }
        computeFit();
        if (fit.width() <= 0) { cropMode = false; invalidate(); return; }
        float sx = base.getWidth() / fit.width(), sy = base.getHeight() / fit.height();
        int l = (int) clamp((crop.left - fit.left) * sx, 0, base.getWidth() - 2);
        int t = (int) clamp((crop.top - fit.top) * sy, 0, base.getHeight() - 2);
        int r = (int) clamp((crop.right - fit.left) * sx, l + 1, base.getWidth());
        int b = (int) clamp((crop.bottom - fit.top) * sy, t + 1, base.getHeight());
        try { base = Bitmap.createBitmap(base, l, t, r - l, b - t); } catch (Exception e) {}
        strokes.clear(); cropMode = false; requestLayout(); invalidate();
    }
    private boolean handleCrop(MotionEvent e) {
        float x = e.getX(), y = e.getY(), hr = dp(26);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragCorner = nearestCorner(x, y, hr);
                moving = dragCorner < 0 && crop.contains(x, y);
                lastX = x; lastY = y; return true;
            case MotionEvent.ACTION_MOVE:
                float dx = x - lastX, dy = y - lastY; lastX = x; lastY = y;
                if (dragCorner >= 0) resizeCorner(dragCorner, x, y);
                else if (moving) { crop.offset(dx, dy); clampInside(); }
                invalidate(); return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: dragCorner = -1; moving = false; return true;
        }
        return true;
    }
    private int nearestCorner(float x, float y, float hr) {
        float[][] cs = { { crop.left, crop.top }, { crop.right, crop.top }, { crop.right, crop.bottom }, { crop.left, crop.bottom } };
        for (int i = 0; i < 4; i++) if (Math.hypot(x - cs[i][0], y - cs[i][1]) < hr) return i;
        return -1;
    }
    private void resizeCorner(int c, float x, float y) {
        x = clamp(x, fit.left, fit.right); y = clamp(y, fit.top, fit.bottom);
        float min = dp(48);
        switch (c) {
            case 0: crop.left = Math.min(x, crop.right - min); crop.top = Math.min(y, crop.bottom - min); break;
            case 1: crop.right = Math.max(x, crop.left + min); crop.top = Math.min(y, crop.bottom - min); break;
            case 2: crop.right = Math.max(x, crop.left + min); crop.bottom = Math.max(y, crop.top + min); break;
            case 3: crop.left = Math.min(x, crop.right - min); crop.bottom = Math.max(y, crop.top + min); break;
        }
    }
    private void clampInside() {
        if (crop.left < fit.left) crop.offset(fit.left - crop.left, 0);
        if (crop.top < fit.top) crop.offset(0, fit.top - crop.top);
        if (crop.right > fit.right) crop.offset(fit.right - crop.right, 0);
        if (crop.bottom > fit.bottom) crop.offset(0, fit.bottom - crop.bottom);
    }
    private void drawCropOverlay(Canvas canvas) {
        Paint dim = new Paint(); dim.setColor(0xB0000000);
        canvas.drawRect(fit.left, fit.top, fit.right, crop.top, dim);
        canvas.drawRect(fit.left, crop.bottom, fit.right, fit.bottom, dim);
        canvas.drawRect(fit.left, crop.top, crop.left, crop.bottom, dim);
        canvas.drawRect(crop.right, crop.top, fit.right, crop.bottom, dim);
        Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG); grid.setStyle(Paint.Style.STROKE); grid.setColor(0x55FFFFFF); grid.setStrokeWidth(dp(0.8f));
        for (int i = 1; i < 3; i++) {
            float gx = crop.left + crop.width() * i / 3f; canvas.drawLine(gx, crop.top, gx, crop.bottom, grid);
            float gy = crop.top + crop.height() * i / 3f; canvas.drawLine(crop.left, gy, crop.right, gy, grid);
        }
        Paint bd = new Paint(Paint.ANTI_ALIAS_FLAG); bd.setStyle(Paint.Style.STROKE); bd.setColor(0xFFFFFFFF); bd.setStrokeWidth(dp(1.4f));
        canvas.drawRect(crop, bd);
        Paint hp = new Paint(Paint.ANTI_ALIAS_FLAG); hp.setStyle(Paint.Style.STROKE); hp.setColor(0xFFFFFFFF); hp.setStrokeWidth(dp(3)); hp.setStrokeCap(Paint.Cap.ROUND);
        float h = dp(18);
        corner(canvas, hp, crop.left, crop.top, h, h);
        corner(canvas, hp, crop.right, crop.top, -h, h);
        corner(canvas, hp, crop.right, crop.bottom, -h, -h);
        corner(canvas, hp, crop.left, crop.bottom, h, -h);
    }
    private void corner(Canvas c, Paint p, float x, float y, float hx, float hy) {
        c.drawLine(x, y, x + hx, y, p); c.drawLine(x, y, x, y + hy, p);
    }
    private float dp(float v) { return v * density; }
    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Bake the filter + strokes into a fresh full-resolution bitmap. */
    public Bitmap bake() {
        if (base == null) return null;
        computeFit();
        Bitmap out = Bitmap.createBitmap(base.getWidth(), base.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawBitmap(base, 0, 0, imgPaint);
        float widthScale = fit.width() > 0 ? base.getWidth() / fit.width() : 1f;
        RectF full = new RectF(0, 0, base.getWidth(), base.getHeight());
        for (Stroke s : strokes) drawStroke(c, s, full, widthScale);
        return out;
    }
}
