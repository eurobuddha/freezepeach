package com.eurobuddha.freezepeach;

import android.content.SharedPreferences;

/**
 * Two-theme design system for FreezePeach — a runtime day/night toggle.
 *   DARK  = "Onyx Electric" (near-black + electric blue)
 *   LIGHT = "Ink"           (warm paper + near-black bubbles + peach accent)
 * Slick, minimal, high-contrast. Colours are no-arg getters so the whole UI re-themes on toggle().
 */
public final class Theme {

    public enum Mode { DARK, LIGHT }
    private static Mode mode = Mode.DARK;
    private static SharedPreferences prefs;

    public static void init(SharedPreferences p) {
        prefs = p;
        mode = "light".equals(p.getString("theme", "dark")) ? Mode.LIGHT : Mode.DARK;
    }
    public static boolean isDark() { return mode == Mode.DARK; }
    public static void toggle() {
        mode = isDark() ? Mode.LIGHT : Mode.DARK;
        if (prefs != null) prefs.edit().putString("theme", isDark() ? "dark" : "light").apply();
    }
    private static int pick(int dark, int light) { return isDark() ? dark : light; }

    public static int BG()        { return pick(0xFF090A0D, 0xFFF7F6F3); }  // page
    public static int SURFACE()   { return pick(0xFF12141A, 0xFFFFFFFF); }  // header / composer
    public static int RECV()      { return pick(0xFF181C24, 0xFFEEEBE5); }  // received bubble / input
    public static int SENT()      { return pick(0xFF4C82FF, 0xFF16130F); }  // sent bubble
    public static int SENT_TEXT() { return pick(0xFFFFFFFF, 0xFFF7EFE7); }
    public static int ACCENT()    { return pick(0xFF5B8BFF, 0xFFFF6A45); }
    public static int TEXT()      { return pick(0xFFEDEFF3, 0xFF1A1712); }
    public static int DIM()       { return pick(0xFF7B828F, 0xFF9C948A); }
    public static int HAIR()      { return pick(0x14FFFFFF, 0x12000000); }
    public static int GLOW()      { return pick(0x2A5B8BFF, 0x1AFF6A45); }  // low-alpha accent glow strip
    public static int PRESS()     { return pick(0x1FFFFFFF, 0x14000000); }  // touch overlay
    public static int SCRIM()     { return pick(0xB3000000, 0x66000000); }  // behind bottom sheets
    public static int DANGER()    { return pick(0xFFFF5C5C, 0xFFD93A2B); }
    public static int ON_ACCENT() { return 0xFFFFFFFF; }                    // label on an accent fill
    public static float AV_S()    { return isDark() ? 0.52f : 0.58f; }      // avatar saturation
    public static float AV_V()    { return isDark() ? 0.70f : 0.82f; }      // avatar value

    private Theme() {}
}
