package com.eurobuddha.freezepeach;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;

/** In-app photo editor (filtr): filters, adjustments, rotate, and freehand markup — bakes and returns a JPEG. */
public class EditorActivity extends Activity {

    private static final int BG = 0xFF0A0A0C, PANEL = 0xFF121318, ACCENT = 0xFF4C82FF, TXT = 0xFFFFFFFF, DIM = 0xFF9AA0AC;
    private EditorView editor;
    private Bitmap thumb;
    private LinearLayout panel;
    private float brightness = 0f, contrast = 1f, saturation = 1f;
    private ColorMatrix filter = new ColorMatrix();

    private interface IntCb { void on(int v); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFF000000);
        String path = getIntent().getStringExtra("path");
        Bitmap bmp = loadCorrected(path, 1600);
        if (bmp == null) { setResult(RESULT_CANCELED); finish(); return; }
        thumb = Bitmap.createScaledBitmap(bmp, Math.max(1, bmp.getWidth() * 120 / Math.max(bmp.getWidth(), bmp.getHeight())),
                Math.max(1, bmp.getHeight() * 120 / Math.max(bmp.getWidth(), bmp.getHeight())), true);

        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);

        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(6), dp(10), dp(10), dp(10)); top.setBackgroundColor(BG);
        top.addView(iconBtn(R.drawable.ic_close, v -> { setResult(RESULT_CANCELED); finish(); }));
        TextView title = label("Edit", 18, true, TXT);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(6); title.setLayoutParams(tlp); top.addView(title);
        TextView reset = label("Reset", 14, false, DIM); reset.setPadding(dp(12), dp(8), dp(12), dp(8));
        reset.setClickable(true); reset.setOnClickListener(v -> { filter = new ColorMatrix(); brightness = 0; contrast = 1f; saturation = 1f; applyMatrix(); showFilters(); });
        top.addView(reset);
        root.addView(top);

        editor = new EditorView(this); editor.setBitmap(bmp);
        editor.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(editor);

        panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setBackgroundColor(PANEL);
        panel.setMinimumHeight(dp(96)); root.addView(panel);

        LinearLayout tabs = new LinearLayout(this); tabs.setBackgroundColor(PANEL); tabs.setPadding(dp(4), dp(2), dp(4), dp(4));
        tabs.addView(tab(R.drawable.ic_filter, "Filters", v -> showFilters()));
        tabs.addView(tab(R.drawable.ic_effects, "Effects", v -> showEffects()));
        tabs.addView(tab(R.drawable.ic_tune, "Adjust", v -> showAdjust()));
        tabs.addView(tab(R.drawable.ic_crop, "Crop", v -> showCrop()));
        tabs.addView(tab(R.drawable.ic_rotate, "Rotate", v -> { editor.setCropMode(false); editor.setDrawMode(false); editor.rotate90(); }));
        tabs.addView(tab(R.drawable.ic_edit, "Draw", v -> showDraw()));
        root.addView(tabs);

        LinearLayout sendBar = new LinearLayout(this); sendBar.setBackgroundColor(PANEL); sendBar.setPadding(dp(16), dp(4), dp(16), dp(16));
        TextView send = label("Send", 16, true, TXT); send.setGravity(Gravity.CENTER);
        GradientDrawable sg = new GradientDrawable(); sg.setColor(ACCENT); sg.setCornerRadius(dp(24)); send.setBackground(sg);
        send.setPadding(0, dp(14), 0, dp(14)); send.setClickable(true); send.setOnClickListener(v -> done());
        sendBar.addView(send, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sendBar);

        setContentView(root);
        applyMatrix(); showFilters();
    }

    // ---- tool panels ----
    private void showCrop() {
        editor.setCropMode(true);
        panel.removeAllViews(); panel.setPadding(dp(16), dp(12), dp(16), dp(14));
        panel.addView(label("Drag the corners to crop", 12, false, DIM));
        TextView apply = label("Apply crop", 14, true, TXT); apply.setGravity(Gravity.CENTER);
        GradientDrawable ag = new GradientDrawable(); ag.setColor(0x224C82FF); ag.setStroke(dp(1), ACCENT); ag.setCornerRadius(dp(20));
        apply.setBackground(ag); apply.setPadding(0, dp(11), 0, dp(11)); apply.setClickable(true);
        apply.setOnClickListener(v -> { editor.applyCrop(); showFilters(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); lp.topMargin = dp(10);
        panel.addView(apply, lp);
    }

    private void showFilters() {
        editor.setCropMode(false); editor.setDrawMode(false);
        panel.removeAllViews(); panel.setPadding(0, 0, 0, 0);
        HorizontalScrollView hs = new HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this); row.setPadding(dp(10), dp(12), dp(10), dp(12));
        String[] names = { "Original", "Mono", "Sepia", "Vivid", "Warm", "Cool", "Noir", "Fade" };
        ColorMatrix[] cms = { ident(), mono(), sepia(), vivid(), warm(), cool(), noir(), fade() };
        for (int i = 0; i < names.length; i++) {
            final ColorMatrix cm = cms[i];
            LinearLayout chip = new LinearLayout(this); chip.setOrientation(LinearLayout.VERTICAL); chip.setGravity(Gravity.CENTER_HORIZONTAL);
            chip.setPadding(dp(5), 0, dp(5), 0);
            ImageView iv = new ImageView(this); iv.setImageBitmap(thumb); iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setColorFilter(new ColorMatrixColorFilter(cm));
            iv.setLayoutParams(new LinearLayout.LayoutParams(dp(58), dp(58))); Ui.roundCorners(iv, dp(12));
            chip.addView(iv);
            TextView t = label(names[i], 11, false, DIM); t.setPadding(0, dp(5), 0, 0); chip.addView(t);
            chip.setClickable(true); chip.setOnClickListener(v -> { filter = cm; applyMatrix(); });
            row.addView(chip);
        }
        hs.addView(row); panel.addView(hs);
    }

    private interface Fx { Bitmap render(Bitmap src); }

    private void showEffects() {
        editor.setCropMode(false); editor.setDrawMode(false);
        panel.removeAllViews(); panel.setPadding(0, 0, 0, 0);
        HorizontalScrollView hs = new HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this); row.setPadding(dp(10), dp(12), dp(10), dp(12));
        // dithering + retro palettes
        effectChip(row, "GameBoy",   s -> GlEffects.dither(s, GlEffects.BAYER8, GlEffects.INDEXED, new int[]{ 0x0f380f, 0x306230, 0x8bac0f, 0x9bbc0f }, 0, 0xFFFFFF, 1f));
        effectChip(row, "Amber",     s -> GlEffects.dither(s, GlEffects.BAYER8, GlEffects.INDEXED, new int[]{ 0x1a0d00, 0x7a3d00, 0xff9d1c, 0xffe2a8 }, 0, 0xFFFFFF, 1f));
        effectChip(row, "Phosphor",  s -> GlEffects.dither(s, GlEffects.BAYER4, GlEffects.INDEXED, new int[]{ 0x001200, 0x0c5c20, 0x39ff5a, 0xccffd6 }, 0, 0xFFFFFF, 1f));
        effectChip(row, "Risograph", s -> GlEffects.dither(s, GlEffects.BAYER8, GlEffects.INDEXED, new int[]{ 0x2a2438, 0xd6336c, 0xf06595, 0xf7eede }, 0, 0xFFFFFF, 1f));
        effectChip(row, "Cyberpunk", s -> GlEffects.dither(s, GlEffects.BAYER8, GlEffects.INDEXED, new int[]{ 0x0a0118, 0xff2a6d, 0x05d9e8, 0xd1f7ff }, 0, 0xFFFFFF, 1f));
        effectChip(row, "Newsprint", s -> GlEffects.dither(s, GlEffects.BAYER8, GlEffects.MONO, null, 0x111111, 0xF4F1E3, 1f));
        // halftone
        effectChip(row, "Halftone",  s -> GlEffects.halftone(s, 0, 6f, 0f, false, 0x111111, 0xFFFFFF));
        effectChip(row, "Comic",     s -> GlEffects.halftone(s, 0, 8f, 15f, true, 0x000000, 0xFFFFFF));
        // edge-detect
        effectChip(row, "Edges",     s -> GlEffects.edge(s, 0, 0.22f, 1f, false, 0x39ff5a, 0x001200));
        effectChip(row, "Ink",       s -> GlEffects.edge(s, 2, 0.10f, 1f, false, 0xffffff, 0x0a0a0a));
        // ASCII
        effectChip(row, "ASCII",     s -> GlEffects.ascii(s, false, 0x39ff5a, 0x001200, 8));
        effectChip(row, "ASCII+",    s -> GlEffects.ascii(s, true, 0xffffff, 0x000000, 8));
        // post FX — stack on top of any effect
        effectChip(row, "CRT",       s -> GlEffects.scanlines(GlEffects.crt(s, 0.12f), 0.35f, 3f));
        effectChip(row, "Scanlines", s -> GlEffects.scanlines(s, 0.4f, 3f));
        effectChip(row, "Glitch",    s -> GlEffects.chromatic(s, 4f));
        effectChip(row, "Bloom",     s -> GlEffects.bloom(s, 0.6f, 0.2f, 0.8f, 8f));
        effectChip(row, "Grain",     s -> GlEffects.grain(s, 60f, 1.5f));
        effectChip(row, "Vignette",  s -> GlEffects.vignette(s, 0.6f, 1.2f));
        hs.addView(row); panel.addView(hs);
    }
    private void effectChip(LinearLayout row, String name, final Fx fx) {
        LinearLayout chip = new LinearLayout(this); chip.setOrientation(LinearLayout.VERTICAL); chip.setGravity(Gravity.CENTER_HORIZONTAL); chip.setPadding(dp(5), 0, dp(5), 0);
        final ImageView iv = new ImageView(this); iv.setImageBitmap(thumb); iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(58), dp(58))); Ui.roundCorners(iv, dp(12));
        chip.addView(iv);
        TextView t = label(name, 11, false, DIM); t.setPadding(0, dp(5), 0, 0); chip.addView(t);
        chip.setClickable(true); chip.setOnClickListener(v -> applyFx(fx));
        row.addView(chip);
        new Thread(() -> { final Bitmap prev = fx.render(thumb); runOnUiThread(() -> iv.setImageBitmap(prev)); }).start();
    }
    private void applyFx(final Fx fx) {
        final Bitmap base = editor.getBitmap(); if (base == null) return;
        Toast.makeText(this, "Applying effect…", Toast.LENGTH_SHORT).show();
        new Thread(() -> { final Bitmap out = fx.render(base); runOnUiThread(() -> editor.setBitmap(out)); }).start();
    }

    private void showAdjust() {
        editor.setCropMode(false); editor.setDrawMode(false);
        panel.removeAllViews(); panel.setPadding(dp(18), dp(6), dp(18), dp(12));
        panel.addView(slider("Brightness", -100, 100, Math.round(brightness), p -> { brightness = p; applyMatrix(); }));
        panel.addView(slider("Contrast", 0, 100, Math.round((contrast - 0.5f) / 1.5f * 100), p -> { contrast = 0.5f + p / 100f * 1.5f; applyMatrix(); }));
        panel.addView(slider("Saturation", 0, 100, Math.round(saturation / 2f * 100), p -> { saturation = p / 100f * 2f; applyMatrix(); }));
    }

    private void showDraw() {
        editor.setCropMode(false); editor.setDrawMode(true);
        panel.removeAllViews(); panel.setPadding(dp(12), dp(14), dp(12), dp(14));
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        int[] colors = { 0xFFFF5C5C, 0xFFFFD24C, 0xFF4CE0A0, 0xFF4C82FF, 0xFFB77BFF, 0xFFFFFFFF, 0xFF111111 };
        for (final int col : colors) {
            View sw = new View(this);
            GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(col); g.setStroke(dp(2), 0x66FFFFFF);
            sw.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(32), dp(32)); lp.rightMargin = dp(10); sw.setLayoutParams(lp);
            sw.setClickable(true); sw.setOnClickListener(v -> { editor.setDrawColor(col); editor.setDrawMode(true); });
            row.addView(sw);
        }
        View spacer = new View(this); row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        row.addView(iconBtn(R.drawable.ic_undo, v -> editor.undo()));
        panel.addView(row);
        TextView hint = label("Draw on the photo with your finger", 12, false, DIM); hint.setPadding(0, dp(10), 0, 0); panel.addView(hint);
    }

    private void applyMatrix() {
        ColorMatrix cm = new ColorMatrix(); cm.set(filter);
        ColorMatrix sat = new ColorMatrix(); sat.setSaturation(saturation); cm.postConcat(sat);
        float c = contrast, t = (1f - c) * 128f + brightness;
        cm.postConcat(new ColorMatrix(new float[]{ c, 0, 0, 0, t, 0, c, 0, 0, t, 0, 0, c, 0, t, 0, 0, 0, 1, 0 }));
        editor.setColorMatrix(cm);
    }

    private void done() {
        editor.applyCrop();     // apply a pending crop if the user is mid-crop
        Bitmap out = editor.bake();
        if (out == null) { setResult(RESULT_CANCELED); finish(); return; }
        try {
            File dir = new File(getCacheDir(), "edit"); dir.mkdirs();
            File f = new File(dir, "edit_" + System.nanoTime() + ".jpg");
            try (FileOutputStream fo = new FileOutputStream(f)) { out.compress(Bitmap.CompressFormat.JPEG, 88, fo); }
            Intent res = new Intent(); res.putExtra("out", f.getAbsolutePath()); setResult(RESULT_OK, res);
        } catch (Exception e) { setResult(RESULT_CANCELED); }
        finish();
    }

    // ---- filter matrices ----
    private static ColorMatrix ident() { return new ColorMatrix(); }
    private static ColorMatrix mono() { ColorMatrix c = new ColorMatrix(); c.setSaturation(0); return c; }
    private static ColorMatrix sepia() { ColorMatrix c = new ColorMatrix(); c.setSaturation(0);
        c.postConcat(new ColorMatrix(new float[]{ 1.1f, 0, 0, 0, 18, 0, 1f, 0, 0, 8, 0, 0, 0.82f, 0, -12, 0, 0, 0, 1, 0 })); return c; }
    private static ColorMatrix vivid() { ColorMatrix c = new ColorMatrix(); c.setSaturation(1.55f); return c; }
    private static ColorMatrix warm() { return new ColorMatrix(new float[]{ 1.13f, 0, 0, 0, 12, 0, 1.02f, 0, 0, 4, 0, 0, 0.85f, 0, -6, 0, 0, 0, 1, 0 }); }
    private static ColorMatrix cool() { return new ColorMatrix(new float[]{ 0.87f, 0, 0, 0, -4, 0, 0.98f, 0, 0, 2, 0, 0, 1.16f, 0, 12, 0, 0, 0, 1, 0 }); }
    private static ColorMatrix noir() { ColorMatrix c = new ColorMatrix(); c.setSaturation(0);
        c.postConcat(new ColorMatrix(new float[]{ 1.4f, 0, 0, 0, -34, 0, 1.4f, 0, 0, -34, 0, 0, 1.4f, 0, -34, 0, 0, 0, 1, 0 })); return c; }
    private static ColorMatrix fade() { return new ColorMatrix(new float[]{ 0.9f, 0, 0, 0, 26, 0, 0.9f, 0, 0, 26, 0, 0, 0.9f, 0, 26, 0, 0, 0, 1, 0 }); }

    // ---- helpers ----
    private Bitmap loadCorrected(String path, int maxPx) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true; BitmapFactory.decodeFile(path, o);
            int max = Math.max(o.outWidth, o.outHeight), s = 1; while (max / s > maxPx) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options(); o2.inSampleSize = s;
            Bitmap bm = BitmapFactory.decodeFile(path, o2); if (bm == null) return null;
            int rot = 0;
            try {
                switch (new ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                    case ExifInterface.ORIENTATION_ROTATE_90: rot = 90; break;
                    case ExifInterface.ORIENTATION_ROTATE_180: rot = 180; break;
                    case ExifInterface.ORIENTATION_ROTATE_270: rot = 270; break;
                }
            } catch (Exception e) {}
            if (rot != 0) { Matrix m = new Matrix(); m.postRotate(rot); bm = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true); }
            return bm;
        } catch (Exception e) { return null; }
    }

    private View slider(String name, int min, int max, int value, final IntCb cb) {
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(0, dp(4), 0, dp(4));
        col.addView(label(name, 12, false, DIM));
        SeekBar sb = new SeekBar(this); sb.setMin(min); sb.setMax(max); sb.setProgress(value);
        sb.getProgressDrawable().setTint(ACCENT); sb.getThumb().setTint(ACCENT);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { cb.on(p); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        col.addView(sb); return col;
    }

    private View tab(int icon, String name, View.OnClickListener l) {
        LinearLayout t = new LinearLayout(this); t.setOrientation(LinearLayout.VERTICAL); t.setGravity(Gravity.CENTER);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        t.setPadding(0, dp(8), 0, dp(4)); t.setClickable(true); t.setOnClickListener(l);
        ImageView iv = new ImageView(this); iv.setImageResource(icon); iv.setColorFilter(0xFFE2E4E8);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24))); t.addView(iv);
        TextView tv = label(name, 11, false, DIM); tv.setPadding(0, dp(3), 0, 0); t.addView(tv);
        return t;
    }
    private FrameLayout iconBtn(int icon, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(this); f.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        ImageView iv = new ImageView(this); iv.setImageResource(icon); iv.setColorFilter(TXT);
        f.addView(iv, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        f.setClickable(true); f.setOnClickListener(l); return f;
    }
    private TextView label(String s, int size, boolean bold, int color) {
        TextView t = new TextView(this); t.setText(s); t.setTextColor(color); t.setTextSize(size);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD); return t;
    }
    private int dp(float v) { return Ui.dp(this, v); }
}
