package com.eurobuddha.freezepeach;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** FreezePeach design kit — reusable, theme-aware view builders (avatars, glow strip, icon/pill buttons,
 *  bubble shapes, press physics). Every colour comes from {@link Theme} so a toggle re-themes everything. */
final class Ui {

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }
    static int alpha(int color, int a) { return (color & 0x00FFFFFF) | (a << 24); }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(radiusPx); return g;
    }
    static GradientDrawable roundStroke(int color, int strokeColor, float radiusPx, int strokeW) {
        GradientDrawable g = round(color, radiusPx); g.setStroke(strokeW, strokeColor); return g;
    }
    static Drawable rippleRect(Context c, float radiusPx) {
        GradientDrawable mask = new GradientDrawable(); mask.setColor(0xFFFFFFFF); mask.setCornerRadius(radiusPx);
        return new RippleDrawable(ColorStateList.valueOf(Theme.PRESS()), null, mask);
    }
    static Drawable rippleOval(Context c) {
        GradientDrawable mask = new GradientDrawable(); mask.setShape(GradientDrawable.OVAL); mask.setColor(0xFFFFFFFF);
        return new RippleDrawable(ColorStateList.valueOf(Theme.PRESS()), null, mask);
    }

    /** Press physics: subtle scale-down on touch, overshoot back. Leaves click handling intact. */
    static void press(final View v) {
        v.setOnTouchListener((view, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start(); break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(150).setInterpolator(new OvershootInterpolator(2f)).start(); break;
            }
            return false;
        });
    }

    static void roundCorners(final View v, final int radiusPx) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) { o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx); }
        });
        v.setClipToOutline(true);
    }

    /** The signature accent glow: a fading centre-bright line + a soft bloom, stacked under a header. */
    static View glowStrip(Context c) {
        LinearLayout col = new LinearLayout(c); col.setOrientation(LinearLayout.VERTICAL);
        View line = new View(c);
        line.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{ alpha(Theme.ACCENT(), 0), Theme.ACCENT(), alpha(Theme.ACCENT(), 0) }));
        col.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 1.5f)));
        View bloom = new View(c);
        bloom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{ Theme.GLOW(), alpha(Theme.GLOW(), 0) }));
        col.addView(bloom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 8)));
        return col;
    }

    /** Colored disc with a gap-ring and monogram — one per contact, hue from id. */
    static FrameLayout avatar(Context c, String pubid, String name, int diaDp) {
        int dia = dp(c, diaDp);
        FrameLayout f = new FrameLayout(c);
        f.setLayoutParams(new ViewGroup.LayoutParams(dia, dia));
        View ring = new View(c);
        GradientDrawable rg = new GradientDrawable(); rg.setShape(GradientDrawable.OVAL);
        rg.setColor(0x00000000); rg.setStroke(dp(c, 1.5f), alpha(Theme.ACCENT(), 0xB3));
        ring.setBackground(rg); f.addView(ring, new FrameLayout.LayoutParams(dia, dia));
        int inset = dp(c, 3);
        View fill = new View(c);
        GradientDrawable fg = new GradientDrawable(); fg.setShape(GradientDrawable.OVAL); fg.setColor(Fp.avatarColor(pubid));
        fill.setBackground(fg);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(dia - 2 * inset, dia - 2 * inset);
        flp.setMargins(inset, inset, inset, inset); f.addView(fill, flp);
        TextView m = new TextView(c);
        boolean has = name != null && !name.trim().isEmpty();
        if (has) {
            m.setText(name.trim().substring(0, 1).toUpperCase());
            m.setTypeface(Typeface.DEFAULT_BOLD); m.setTextColor(alpha(0xFFFFFF, 0xEB));
            m.setTextSize(TypedValue.COMPLEX_UNIT_PX, dia * 0.42f);
        } else {
            m.setText("fp"); m.setTypeface(Typeface.MONOSPACE); m.setTextColor(alpha(0xFFFFFF, 0xB3));
            m.setTextSize(TypedValue.COMPLEX_UNIT_PX, dia * 0.30f);
        }
        m.setGravity(Gravity.CENTER);
        f.addView(m, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return f;
    }

    static ImageView icon(Context c, int resId, int sizeDp, int tint) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(resId); iv.setColorFilter(tint);
        iv.setLayoutParams(new ViewGroup.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return iv;
    }

    /** Circular icon button with a ripple + press, a proper 44dp+ touch target. */
    static FrameLayout iconButton(Context c, int resId, int iconDp, int touchDp, int tint, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(c);
        int t = dp(c, touchDp);
        f.setLayoutParams(new LinearLayout.LayoutParams(t, t));
        ImageView iv = icon(c, resId, iconDp, tint);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(c, iconDp), dp(c, iconDp), Gravity.CENTER);
        f.addView(iv, lp);
        f.setClickable(true); f.setBackground(rippleOval(c));
        if (l != null) f.setOnClickListener(l);
        press(f);
        return f;
    }

    /** Full pill button (52dp) with optional leading icon. */
    static LinearLayout pill(Context c, int iconRes, String label, int bg, int fg, boolean enabled, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(c); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER);
        row.setBackground(round(bg, dp(c, 26)));
        row.setPadding(dp(c, 20), dp(c, 15), dp(c, 20), dp(c, 15));
        if (iconRes != 0) {
            row.addView(icon(c, iconRes, 18, fg));
            View g = new View(c); g.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 8), 1)); row.addView(g);
        }
        TextView t = new TextView(c); t.setText(label); t.setTextColor(fg);
        t.setTypeface(Typeface.DEFAULT_BOLD); t.setTextSize(16); row.addView(t);
        if (enabled && l != null) {
            row.setClickable(true); row.setForeground(rippleRect(c, dp(c, 26)));
            row.setOnClickListener(l); press(row);
        }
        return row;
    }

    static TextView label(Context c, String text, int size, int weight, int color) {
        TextView t = new TextView(c); t.setText(text); t.setTextColor(color);
        t.setTextSize(size); if (weight >= 600) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }
    static TextView section(Context c, String text) {
        TextView t = new TextView(c); t.setText(text.toUpperCase()); t.setTextColor(Theme.DIM());
        t.setTextSize(11); t.setTypeface(Typeface.DEFAULT_BOLD); t.setLetterSpacing(0.12f);
        return t;
    }
    static TextView mono(Context c, String text, int size, int color) {
        TextView t = new TextView(c); t.setText(text); t.setTextColor(color); t.setTextSize(size);
        t.setTypeface(Typeface.MONOSPACE); return t;
    }

    /** Asymmetric chat-bubble background (tight corner on the near side). */
    static GradientDrawable bubble(Context c, boolean mine) {
        float r = dp(c, 19), t = dp(c, 6);
        GradientDrawable g = new GradientDrawable(); g.setColor(mine ? Theme.SENT() : Theme.RECV());
        if (mine) g.setCornerRadii(new float[]{ r, r, r, r, t, t, r, r });
        else      g.setCornerRadii(new float[]{ r, r, r, r, r, r, t, t });
        return g;
    }

    static ColorDrawable transparent() { return new ColorDrawable(0); }

    private Ui() {}
}
