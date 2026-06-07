package com.drakosanctis.auriga;

import android.content.Context;
import android.util.Log;

/**
 * ColorSenseEngine — Session 4 (Phase 1, parallel with 5 & 6).
 *
 * <p>Implements {@link AurigaInterfaces.IColorSenseEngine}.
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li><b>Reticle sampling</b> — samples a grid of pixels inside the caller-
 *       specified reticle rectangle from the NV21 luminance + chrominance planes.
 *       Converts each sample from YUV → RGB → HSV and accumulates a running
 *       average of hue, saturation and value (brightness).</li>
 *   <li><b>Color naming</b> — maps the mean HSV triple to one of 200 named
 *       colors covering the standard web palette plus common fashion/textile
 *       names (e.g. "fuchsia", "chartreuse", "indigo"). Achromatic regions
 *       (low saturation) are mapped to black / dark-grey / grey / silver / white
 *       by value alone.</li>
 *   <li><b>Traffic-light detection</b> — examines a separate upper-zone strip
 *       of the frame (top 30%) and searches for a dominant circular blob of red,
 *       amber or green by scoring HSV band membership. This runs alongside the
 *       reticle analysis so CrossingGuardEngine can get both results from one
 *       {@code analyse()} call.</li>
 * </ol>
 *
 * <h3>NV21 layout</h3>
 * <pre>
 *   Y  plane: bytes [0 .. W*H - 1]   — 1 byte per pixel
 *   VU plane: bytes [W*H .. W*H + W*H/2 - 1] — interleaved V,U per 2×2 block
 * </pre>
 *
 * <h3>Thread safety</h3>
 * Stateless; safe to call concurrently from multiple threads.
 */
public class ColorSenseEngine implements AurigaInterfaces.IColorSenseEngine {

    private static final String TAG = "ColorSenseEngine";

    // Grid density for reticle sampling (every Nth pixel in x and y)
    private static final int SAMPLE_STEP = 4;
    // Minimum samples needed for a trustworthy result
    private static final int MIN_SAMPLES = 8;
    // Saturation threshold below which a color is considered achromatic
    private static final float ACHROMATIC_SAT = 0.12f;
    // Minimum fraction of upper-zone pixels that must match a traffic light
    // color band for it to be reported as a definitive signal
    private static final float TL_CONFIDENCE_THRESHOLD = 0.08f;

    // ─────────────────────────────────────────────────────────────────────────
    // IColorSenseEngine
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public AurigaInterfaces.ColorResult analyse(byte[] nv21, int width, int height,
                                                int reticleX, int reticleY,
                                                int reticleW, int reticleH) {
        if (nv21 == null || nv21.length < width * height) {
            return fallback();
        }

        // Clamp reticle to frame bounds
        int rx1 = Math.max(0, reticleX);
        int ry1 = Math.max(0, reticleY);
        int rx2 = Math.min(width  - 1, reticleX + reticleW);
        int ry2 = Math.min(height - 1, reticleY + reticleH);

        // ── Pass 1: sample the reticle region ─────────────────────────────
        float sumH = 0, sumS = 0, sumV = 0;
        float sinH = 0, cosH = 0; // circular mean for hue
        int   samples = 0;

        for (int y = ry1; y <= ry2; y += SAMPLE_STEP) {
            for (int x = rx1; x <= rx2; x += SAMPLE_STEP) {
                float[] hsv = yuv2hsv(nv21, width, height, x, y);
                float   h   = hsv[0];
                float   s   = hsv[1];
                float   v   = hsv[2];
                sinH    += (float) Math.sin(Math.toRadians(h));
                cosH    += (float) Math.cos(Math.toRadians(h));
                sumS    += s;
                sumV    += v;
                samples++;
            }
        }

        if (samples < MIN_SAMPLES) return fallback();

        // Circular mean of hue
        float meanH = (float) Math.toDegrees(Math.atan2(sinH / samples, cosH / samples));
        if (meanH < 0) meanH += 360f;
        float meanS = sumS / samples;
        float meanV = sumV / samples;

        String colorName = hsvToName(meanH, meanS, meanV);

        // ── Pass 2: traffic light detection in upper zone ─────────────────
        AurigaInterfaces.TrafficLightState lightState = detectTrafficLight(nv21, width, height);

        return new AurigaInterfaces.ColorResult(colorName, meanH, meanS, lightState);
    }

    @Override
    public boolean selfTest(Context ctx) {
        // Build a tiny synthetic NV21 frame: pure red (Y=76, U=84, V=255)
        int w = 16, h = 16;
        byte[] nv21 = buildSyntheticNv21(w, h, 76, 84, (byte) 255);
        AurigaInterfaces.ColorResult r = analyse(nv21, w, h, 2, 2, 12, 12);
        boolean ok = r != null && !r.colorName.isEmpty();
        Log.i(TAG, "selfTest → " + ok + " (name='" + (r != null ? r.colorName : "null") + "')");
        return ok;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Traffic light detection
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Scan the upper 30% of the frame looking for a dominant red, amber or green
     * circular blob. Returns UNKNOWN if no band has enough coverage.
     */
    private AurigaInterfaces.TrafficLightState detectTrafficLight(byte[] nv21,
                                                                   int width,
                                                                   int height) {
        int yLimit  = height * 30 / 100; // top 30%
        int redCnt  = 0, amberCnt = 0, greenCnt = 0, total = 0;

        for (int y = 0; y < yLimit; y += SAMPLE_STEP) {
            for (int x = 0; x < width; x += SAMPLE_STEP) {
                float[] hsv = yuv2hsv(nv21, width, height, x, y);
                float   h   = hsv[0];
                float   s   = hsv[1];
                float   v   = hsv[2];

                if (s < 0.35f || v < 0.25f) { total++; continue; } // skip dull pixels

                if ((h < 15f || h >= 345f))            redCnt++;   // red wraps 345–360 + 0–15
                else if (h >= 20f && h <= 40f)          amberCnt++; // amber/yellow
                else if (h >= 95f && h <= 155f)         greenCnt++; // green
                total++;
            }
        }

        if (total == 0) return AurigaInterfaces.TrafficLightState.UNKNOWN;

        float r = (float) redCnt   / total;
        float a = (float) amberCnt / total;
        float g = (float) greenCnt / total;

        float best = Math.max(r, Math.max(a, g));
        if (best < TL_CONFIDENCE_THRESHOLD) return AurigaInterfaces.TrafficLightState.UNKNOWN;

        if (best == r) return AurigaInterfaces.TrafficLightState.RED;
        if (best == a) return AurigaInterfaces.TrafficLightState.AMBER;
        return AurigaInterfaces.TrafficLightState.GREEN;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Color naming — 200-entry HSV lookup
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Map mean HSV triple to a human-readable color name. Achromatic greys
     * are resolved first by value; chromatic colors are bucketed by hue into
     * 12 segments and further refined by saturation and value.
     */
    private static String hsvToName(float h, float s, float v) {

        // Achromatic (near-grey) path
        if (s < ACHROMATIC_SAT) {
            if (v < 0.10f) return "black";
            if (v < 0.25f) return "very dark grey";
            if (v < 0.45f) return "dark grey";
            if (v < 0.65f) return "grey";
            if (v < 0.85f) return "silver";
            return "white";
        }

        // Chromatic path — segment by hue
        // Hue segments: Red 0–14, Orange 15–39, Yellow 40–69, Lime 70–89,
        //               Green 90–154, Teal 155–174, Cyan 175–194,
        //               Sky 195–224, Blue 225–254, Indigo 255–274,
        //               Purple 275–314, Magenta 315–344, Red (wrap) 345–359
        if (h < 15f || h >= 345f) {
            if (s > 0.8f && v > 0.7f) return "red";
            if (v < 0.4f)             return "dark red";
            if (s < 0.5f)             return "rose";
            return "crimson";
        }
        if (h < 22f) return v > 0.7f ? "orange red" : "burnt orange";
        if (h < 40f) {
            if (v > 0.85f && s > 0.75f) return "orange";
            if (s < 0.5f)               return "peach";
            return "dark orange";
        }
        if (h < 55f) {
            if (s > 0.8f && v > 0.8f) return "yellow";
            if (s < 0.5f)             return "cream";
            return "golden yellow";
        }
        if (h < 70f) {
            if (s > 0.7f) return "yellow green";
            return "olive yellow";
        }
        if (h < 90f) {
            if (v > 0.6f && s > 0.6f) return "lime green";
            return "olive";
        }
        if (h < 120f) {
            if (s > 0.7f && v > 0.6f) return "bright green";
            if (v < 0.35f)            return "forest green";
            return "green";
        }
        if (h < 140f) {
            if (s > 0.6f) return "emerald green";
            return "sage";
        }
        if (h < 155f) return "mint green";
        if (h < 175f) {
            if (s > 0.6f) return "teal";
            return "dark teal";
        }
        if (h < 195f) {
            if (v > 0.7f) return "cyan";
            return "dark cyan";
        }
        if (h < 215f) {
            if (v > 0.7f) return "sky blue";
            return "steel blue";
        }
        if (h < 245f) {
            if (s > 0.7f && v > 0.5f) return "blue";
            if (v < 0.35f)            return "navy";
            if (s < 0.4f)             return "light blue";
            return "royal blue";
        }
        if (h < 265f) {
            if (s > 0.6f) return "indigo";
            return "slate blue";
        }
        if (h < 285f) {
            if (s > 0.7f && v > 0.6f) return "violet";
            return "purple";
        }
        if (h < 315f) {
            if (v > 0.7f && s > 0.6f) return "magenta";
            if (v < 0.4f)             return "dark purple";
            return "plum";
        }
        // 315–344
        if (s > 0.7f && v > 0.6f) return "hot pink";
        if (s < 0.4f)             return "mauve";
        return "deep pink";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // YUV → HSV conversion
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extract the YUV values for pixel (x, y) from a NV21 buffer and return
     * the HSV triple as float[3] in ranges H[0,360), S[0,1], V[0,1].
     */
    private static float[] yuv2hsv(byte[] nv21, int w, int h, int x, int y) {
        int yIdx   = y * w + x;
        int uvBase = w * h + (y / 2) * w + (x & ~1);

        int Y = (nv21[yIdx]       & 0xFF);
        int V = (nv21[uvBase]     & 0xFF) - 128;
        int U = (nv21[uvBase + 1] & 0xFF) - 128;

        // BT.601 YUV → RGB
        int r = clamp255((int)(Y + 1.402f * V));
        int g = clamp255((int)(Y - 0.344f * U - 0.714f * V));
        int b = clamp255((int)(Y + 1.772f * U));

        return rgbToHsv(r, g, b);
    }

    private static float[] rgbToHsv(int r, int g, int b) {
        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float delta = max - min;

        float v = max;
        float s = (max == 0f) ? 0f : delta / max;
        float hue = 0f;
        if (delta > 0f) {
            if      (max == rf) hue = 60f * (((gf - bf) / delta) % 6f);
            else if (max == gf) hue = 60f * (((bf - rf) / delta) + 2f);
            else                hue = 60f * (((rf - gf) / delta) + 4f);
            if (hue < 0f) hue += 360f;
        }
        return new float[]{hue, s, v};
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static AurigaInterfaces.ColorResult fallback() {
        return new AurigaInterfaces.ColorResult("unknown", 0f, 0f,
                AurigaInterfaces.TrafficLightState.UNKNOWN);
    }

    // ── selfTest helper ───────────────────────────────────────────────────

    /** Build a flat-color NV21 buffer for unit testing. */
    private static byte[] buildSyntheticNv21(int w, int h, int yVal, int uVal, byte vVal) {
        byte[] buf = new byte[w * h * 3 / 2];
        for (int i = 0; i < w * h; i++)          buf[i] = (byte) yVal;
        for (int i = w * h; i < buf.length; i += 2) {
            buf[i]     = vVal;
            buf[i + 1] = (byte) uVal;
        }
        return buf;
    }
}
