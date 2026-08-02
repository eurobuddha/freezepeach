package com.eurobuddha.freezepeach;

/** Short display label for a publicId (the full 130-char id stays the addressable one). */
public final class Fp {

    /** A short display label = a TRUNCATION of the real id, e.g. {@code 0x1a2b3c4d…9f0a}
     *  ({@code prefix(10) + "…" + suffix(4)}, matching iOS). This is a label only — NOT the
     *  addressable id. The full 130-char id is shown copyable on the My-code sheet so this short
     *  form can never be mistaken for something to save. */
    public static String shortId(String publicId) {
        if (publicId == null) return "";
        String s = publicId.trim();
        if (s.length() <= 14) return s;              // already short — show verbatim
        return s.substring(0, 10) + "…" + s.substring(s.length() - 4);
    }

    // Curated avatar palette — tasteful, legible with white text, deliberately no muddy green/yellow.
    private static final int[] AVATAR_COLORS = {
        0xFF4C82FF, // blue
        0xFF6C6CF0, // indigo
        0xFF9B6CFF, // violet
        0xFFC964D8, // orchid
        0xFFE8618F, // pink
        0xFFFF6B6B, // coral
        0xFFF2954B, // amber
        0xFF2BAEB0, // teal
        0xFF3EB6E8, // sky
        0xFF7E8AA0, // slate
    };

    /** A deterministic, always-legible avatar colour from the id — picked from a curated palette. */
    public static int avatarColor(String publicId) {
        int v;
        if (publicId != null && publicId.length() >= 6) {
            try { v = Integer.parseInt(publicId.substring(2, 6), 16); } catch (Exception e) { v = publicId.hashCode(); }
        } else v = 0;
        return AVATAR_COLORS[Math.abs(v) % AVATAR_COLORS.length];
    }

    private Fp() {}
}
