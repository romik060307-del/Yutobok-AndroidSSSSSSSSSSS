package com.rkdesign.rkcamera;

/**
 * Pewarnaan bergaya iPhone: bayangan sedikit terangkat, highlight dilembutkan,
 * kontras tengah halus, sedikit hangat, saturasi "vibrance" yang menjaga warna kulit.
 *
 * Ini pendekatan umum, bukan pipeline asli Apple (ISP / Neural Engine / Deep Fusion).
 * Fungsi yang sama dipakai untuk foto (per piksel) dan video (lewat 3D LUT),
 * jadi tampilan foto dan video konsisten.
 */
public final class IPhoneLook {
    public final String name;
    private final float contrast;      // kekuatan kurva S (0 = datar)
    private final float shadowLift;    // angkat bayangan-tengah (negatif = lebih gelap)
    private final float highlightRoll; // pelembut highlight
    private final float warmth;        // + hangat, - dingin
    private final float saturation;    // pengali saturasi dasar
    private final float vibrance;      // tambahan saturasi untuk warna yang masih pucat
    private final float mono;          // 0 = berwarna, 1 = hitam putih
    private final float tintR, tintG, tintB;

    private IPhoneLook(String name, float contrast, float shadowLift, float highlightRoll,
                       float warmth, float saturation, float vibrance, float mono,
                       float tintR, float tintG, float tintB) {
        this.name = name;
        this.contrast = contrast;
        this.shadowLift = shadowLift;
        this.highlightRoll = highlightRoll;
        this.warmth = warmth;
        this.saturation = saturation;
        this.vibrance = vibrance;
        this.mono = mono;
        this.tintR = tintR;
        this.tintG = tintG;
        this.tintB = tintB;
    }

    /** Urutan: STANDAR, CERAH, HANGAT, DINGIN, DRAMATIS, MONO, SILVERTONE, NOIR. */
    public static final IPhoneLook[] ALL = {
            new IPhoneLook("STANDAR",    0.30f,  0.045f, 0.45f,  0.012f, 1.04f, 0.30f, 0f, 1f, 1f, 1f),
            new IPhoneLook("CERAH",      0.42f,  0.040f, 0.45f,  0.010f, 1.16f, 0.45f, 0f, 1f, 1f, 1f),
            new IPhoneLook("HANGAT",     0.35f,  0.045f, 0.45f,  0.045f, 1.08f, 0.35f, 0f, 1f, 1f, 1f),
            new IPhoneLook("DINGIN",     0.35f,  0.045f, 0.45f, -0.040f, 1.08f, 0.35f, 0f, 1f, 1f, 1f),
            new IPhoneLook("DRAMATIS",   0.70f,  0.000f, 0.55f,  0.000f, 0.88f, 0.10f, 0f, 1f, 1f, 1f),
            new IPhoneLook("MONO",       0.30f,  0.030f, 0.45f,  0.000f, 0.00f, 0.00f, 1f, 1f, 1f, 1f),
            new IPhoneLook("SILVERTONE", 0.25f,  0.060f, 0.50f,  0.000f, 0.00f, 0.00f, 1f, 0.975f, 0.99f, 1.025f),
            new IPhoneLook("NOIR",       0.90f, -0.010f, 0.55f,  0.000f, 0.00f, 0.00f, 1f, 1f, 1f, 1f),
            // Preset tambahan untuk variasi hasil langsung di dalam aplikasi.
            new IPhoneLook("NATURAL",    0.20f,  0.025f, 0.42f,  0.000f, 1.00f, 0.12f, 0f, 1f, 1f, 1f),
            new IPhoneLook("VIVID",      0.46f,  0.025f, 0.42f,  0.000f, 1.28f, 0.48f, 0f, 1f, 1f, 1f),
            new IPhoneLook("GOLDEN",     0.34f,  0.040f, 0.48f,  0.065f, 1.10f, 0.30f, 0f, 1f, 1f, 1f),
            new IPhoneLook("ARCTIC",     0.32f,  0.035f, 0.45f, -0.065f, 1.08f, 0.28f, 0f, 1f, 1f, 1f),
            new IPhoneLook("CINEMA",     0.52f,  0.015f, 0.58f,  0.018f, 0.92f, 0.18f, 0f, 1f, 1f, 1f),
            new IPhoneLook("FADED",      0.12f,  0.075f, 0.38f,  0.015f, 0.86f, 0.12f, 0f, 1f, 1f, 1f),
            new IPhoneLook("PORTRAIT",   0.28f,  0.045f, 0.46f,  0.025f, 1.06f, 0.22f, 0f, 1f, 1f, 1f),
            new IPhoneLook("SUNSET",     0.42f,  0.025f, 0.48f,  0.085f, 1.18f, 0.34f, 0f, 1f, 1f, 1f),
    };

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    /** Bobot 0..1 berbentuk tenda: naik dari lo ke mid, turun dari mid ke hi. */
    private static float tent(float x, float lo, float mid, float hi) {
        if (x <= lo || x >= hi) return 0f;
        return x < mid ? (x - lo) / (mid - lo) : (hi - x) / (hi - mid);
    }

    /** Masukan dan keluaran dalam rentang 0..1 (ruang sRGB, tanpa linearisasi agar cepat). */
    public void apply(float r, float g, float b, float[] out) {
        float y = 0.2126f * r + 0.7152f * g + 0.0722f * b;

        // Kurva tonal pada luma, lalu selisihnya ditambahkan ke semua kanal (menjaga warna).
        float t = y;
        float inv = 1f - y;
        t += shadowLift * 6.75f * y * inv * inv;           // angkat bayangan-tengah, hitam murni tetap
        float h = clamp01((y - 0.65f) / 0.35f);
        t -= highlightRoll * 0.5f * h * h * (1f - h);      // lembutkan highlight, putih murni tetap
        t += contrast * y * inv * (2f * y - 1f);           // kurva S halus
        float delta = t - y;
        r += delta; g += delta; b += delta;

        // Suhu warna: sedikit hangat seperti kebanyakan hasil iPhone.
        r *= 1f + warmth;
        b *= 1f - warmth;
        r = clamp01(r); g = clamp01(g); b = clamp01(b);

        float y2 = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float sat = max > 1e-4f ? (max - min) / max : 0f;

        // Lindungi warna kulit (oranye) dari saturasi berlebih.
        float skin = 0f;
        if (r >= g && g >= b && (r - b) > 1e-4f) {
            skin = tent((g - b) / (r - b), 0.12f, 0.40f, 0.80f);
        }
        float vib = 1f + vibrance * (1f - sat) * (1f - 0.6f * skin);
        float mult = saturation * vib;
        r = clamp01(y2 + (r - y2) * mult);
        g = clamp01(y2 + (g - y2) * mult);
        b = clamp01(y2 + (b - y2) * mult);

        if (mono > 0f) {
            r = r + (clamp01(y2 * tintR) - r) * mono;
            g = g + (clamp01(y2 * tintG) - g) * mono;
            b = b + (clamp01(y2 * tintB) - b) * mono;
        }
        out[0] = r; out[1] = g; out[2] = b;
    }

    private static int to255(float v) { return Math.round(clamp01(v) * 255f); }

    /** Bangun 3D LUT [R][G][B] berisi ARGB, dipakai efek video SingleColorLut. */
    public int[][][] buildCube(int size) {
        int[][][] cube = new int[size][size][size];
        float[] o = new float[3];
        float scale = size - 1f;
        for (int ri = 0; ri < size; ri++) {
            for (int gi = 0; gi < size; gi++) {
                for (int bi = 0; bi < size; bi++) {
                    apply(ri / scale, gi / scale, bi / scale, o);
                    cube[ri][gi][bi] = 0xFF000000 | (to255(o[0]) << 16) | (to255(o[1]) << 8) | to255(o[2]);
                }
            }
        }
        return cube;
    }
}
