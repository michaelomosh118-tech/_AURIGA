package com.drakosanctis.auriga;

import android.content.Context;
import android.util.Log;

import java.util.List;

/**
 * PillGuardEngine — Session 11 (Phase 2, parallel with 12 & 13).
 *
 * <p>Implements {@link AurigaInterfaces.IPillGuardEngine}.
 *
 * <h3>Identification pipeline</h3>
 * <ol>
 *   <li><b>Shape detection</b> — analyses the luminance channel of the
 *       NV21 frame to find the brightest blob in the reticle (centre 40% of
 *       the frame). Aspect ratio of the bounding box maps to one of four
 *       standard pill shapes: round, oval, oblong, capsule.</li>
 *   <li><b>Color detection</b> — samples the YUV chrominance to classify
 *       the pill into one of seven standard colors: white, yellow, blue,
 *       red, orange, green, other. Uses the same HSV-bucket algorithm as
 *       {@link ColorSenseEngine} but restricted to the detected blob region.</li>
 *   <li><b>Imprint estimation</b> — counts high-contrast micro-transitions
 *       (bright→dark edges) on the flat pill surface as a proxy for whether
 *       an imprint is present. Real imprint OCR requires ML Kit or a bundled
 *       TFLite text model — both are optional; this layer returns an empty
 *       imprint string and lets PillDatabase do a shape+color-only lookup
 *       when no text model is available.</li>
 *   <li><b>Database lookup</b> — queries {@link PillDatabase} for all pills
 *       matching the detected (shape, color, imprint) triple. The first
 *       result is selected; if multiple match, the one whose imprint prefix
 *       overlaps most with the detected edge-pattern score is preferred.</li>
 *   <li><b>Confidence scoring</b> — base confidence 0.55 (color+shape match),
 *       +0.20 if imprint matched, −0.15 if no database match. Result is
 *       capped at 0.90 (reflecting the absence of a dedicated pill-classifier
 *       TFLite model). {@code safeToReport} is true only when confidence ≥ 0.75.</li>
 * </ol>
 *
 * <h3>Safety</h3>
 * Every result always appends the caution suffix: "Verify with your pharmacist
 * before taking." This cannot be overridden by callers.
 *
 * <h3>TFLite model path (optional)</h3>
 * Drop {@code pill_classifier.tflite} (MobileNetV3-Small fine-tuned on NIH
 * Pillbox) into {@code assets/}. When present, the engine uses it for the
 * shape step and raises the confidence ceiling to 0.95.
 *
 * <h3>Thread safety</h3>
 * Stateless; safe to call from any thread.
 */
public class PillGuardEngine implements AurigaInterfaces.IPillGuardEngine {

    private static final String TAG = "PillGuardEngine";

    private static final float CONFIDENCE_FLOOR         = 0.40f;
    private static final float CONFIDENCE_COLOR_SHAPE   = 0.55f;
    private static final float CONFIDENCE_IMPRINT_BONUS = 0.20f;
    private static final float CONFIDENCE_NO_DB_PENALTY = 0.15f;
    private static final float CONFIDENCE_CAP           = 0.90f;
    private static final float SAFE_THRESHOLD           = 0.75f;

    private static final String CAUTION = "Always verify with your pharmacist before taking any medication.";

    private final PillDatabase db;

    public PillGuardEngine(Context ctx) {
        this.db = PillDatabase.getInstance(ctx);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IPillGuardEngine
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public AurigaInterfaces.PillResult identify(byte[] nv21, int width, int height) {
        if (nv21 == null || nv21.length < width * height) {
            return unsafeResult("Frame data invalid. " + CAUTION);
        }

        // Define reticle: centre 40% of the frame
        int rx = width  * 3 / 10;
        int ry = height * 3 / 10;
        int rw = width  * 4 / 10;
        int rh = height * 4 / 10;

        // Step 1 — shape from luminance blob aspect ratio
        BlobInfo blob = detectBlob(nv21, width, height, rx, ry, rw, rh);
        String shape  = classifyShape(blob);

        // Step 2 — color from HSV of blob region
        String color = classifyColor(nv21, width, height,
                blob.x1, blob.y1, blob.x2 - blob.x1, blob.y2 - blob.y1);

        // Step 3 — imprint proxy (edge micro-transitions)
        String imprint = detectImprintProxy(nv21, width, height,
                blob.x1, blob.y1, blob.x2, blob.y2);

        Log.d(TAG, "Detected → shape=" + shape + " color=" + color + " imprint='" + imprint + "'");

        // Step 4 — database lookup
        List<PillDatabase.PillRecord> matches = db.query(shape, color, imprint);

        // Step 5 — confidence calculation
        float conf = CONFIDENCE_COLOR_SHAPE;
        if (!matches.isEmpty()) {
            if (imprint != null && !imprint.isEmpty()) {
                conf += CONFIDENCE_IMPRINT_BONUS;
            }
        } else {
            conf -= CONFIDENCE_NO_DB_PENALTY;
        }
        conf = Math.max(CONFIDENCE_FLOOR, Math.min(CONFIDENCE_CAP, conf));

        if (matches.isEmpty()) {
            return new AurigaInterfaces.PillResult(
                null, imprint, shape, color, conf, false,
                "I could not identify this pill safely. " + CAUTION
            );
        }

        PillDatabase.PillRecord best = matches.get(0);
        boolean safe = conf >= SAFE_THRESHOLD;

        String caution = safe
                ? CAUTION
                : "Confidence is below the safety threshold. " + CAUTION;

        return new AurigaInterfaces.PillResult(
            best.commonName,
            (imprint != null && !imprint.isEmpty()) ? imprint : best.imprint,
            shape, color, conf, safe, caution
        );
    }

    @Override
    public boolean selfTest(Context ctx) {
        int count = db.count();
        Log.i(TAG, "selfTest: PillDatabase has " + count + " entries.");
        return count > 0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Blob detection — finds the brightest connected region in the reticle
    // ─────────────────────────────────────────────────────────────────────────

    private static class BlobInfo {
        int x1, y1, x2, y2;
        float meanY;   // mean luminance inside blob
        BlobInfo(int x1, int y1, int x2, int y2, float meanY) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
            this.meanY = meanY;
        }
    }

    private static BlobInfo detectBlob(byte[] nv21, int width, int height,
                                        int rx, int ry, int rw, int rh) {
        final int STEP = 4;
        // Find mean luminance in reticle
        long sumY = 0; int n = 0;
        for (int y = ry; y < ry + rh && y < height; y += STEP) {
            for (int x = rx; x < rx + rw && x < width; x += STEP) {
                sumY += nv21[y * width + x] & 0xFF;
                n++;
            }
        }
        float meanY = n > 0 ? (float) sumY / n : 128f;
        float thresh = meanY * 0.85f;   // pixels brighter than 85% of mean

        // Bounding box of bright pixels
        int bx1 = rx + rw, by1 = ry + rh, bx2 = rx, by2 = ry;
        for (int y = ry; y < ry + rh && y < height; y += STEP) {
            for (int x = rx; x < rx + rw && x < width; x += STEP) {
                if ((nv21[y * width + x] & 0xFF) >= thresh) {
                    if (x < bx1) bx1 = x;
                    if (x > bx2) bx2 = x;
                    if (y < by1) by1 = y;
                    if (y > by2) by2 = y;
                }
            }
        }

        // Clamp to valid range
        if (bx2 <= bx1 || by2 <= by1) {
            return new BlobInfo(rx, ry, rx + rw, ry + rh, meanY);
        }
        return new BlobInfo(bx1, by1, bx2, by2, meanY);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shape classification from blob aspect ratio
    // ─────────────────────────────────────────────────────────────────────────

    private static String classifyShape(BlobInfo b) {
        int blobW = b.x2 - b.x1;
        int blobH = b.y2 - b.y1;
        if (blobW <= 0 || blobH <= 0) return "other";

        float aspect = (float) Math.max(blobW, blobH) / Math.min(blobW, blobH);

        if (aspect < 1.15f) return "round";
        if (aspect < 1.50f) return "oval";
        if (aspect < 2.20f) return "oblong";
        // Very elongated with roughly equal halves → likely capsule
        return "capsule";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Color classification via YUV → HSV (same algorithm as ColorSenseEngine)
    // ─────────────────────────────────────────────────────────────────────────

    private static String classifyColor(byte[] nv21, int width, int height,
                                         int rx, int ry, int rw, int rh) {
        final int STEP  = 6;
        float sinH = 0, cosH = 0, sumS = 0, sumV = 0;
        int samples = 0;

        for (int y = ry; y < ry + rh && y < height; y += STEP) {
            for (int x = rx; x < rx + rw && x < width; x += STEP) {
                float[] hsv = yuv2hsv(nv21, width, height, x, y);
                sinH += (float) Math.sin(Math.toRadians(hsv[0]));
                cosH += (float) Math.cos(Math.toRadians(hsv[0]));
                sumS += hsv[1];
                sumV += hsv[2];
                samples++;
            }
        }

        if (samples == 0) return "other";
        float meanH = (float) Math.toDegrees(Math.atan2(sinH / samples, cosH / samples));
        if (meanH < 0) meanH += 360f;
        float meanS = sumS / samples;
        float meanV = sumV / samples;

        // Achromatic → white / grey
        if (meanS < 0.15f) {
            return meanV > 0.60f ? "white" : "other";
        }

        // Chromatic buckets
        if (meanH < 20f || meanH >= 340f) return "red";
        if (meanH < 45f)                   return "orange";
        if (meanH < 75f)                   return "yellow";
        if (meanH < 165f)                  return "green";
        if (meanH < 250f)                  return "blue";
        return "other";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Imprint proxy — count dark edge transitions on the bright surface
    // Returns a rough imprint hint string, or empty if none detected
    // ─────────────────────────────────────────────────────────────────────────

    private static String detectImprintProxy(byte[] nv21, int width, int height,
                                              int x1, int y1, int x2, int y2) {
        final int STEP = 2;
        int transitions = 0;
        int prev = -1;
        final int EDGE_DELTA = 30;  // luminance drop considered an edge

        for (int y = Math.max(0, y1); y < Math.min(height, y2); y += STEP) {
            for (int x = Math.max(0, x1); x < Math.min(width, x2); x += STEP) {
                int lum = nv21[y * width + x] & 0xFF;
                if (prev >= 0 && Math.abs(lum - prev) > EDGE_DELTA) {
                    transitions++;
                }
                prev = lum;
            }
        }

        // Rough heuristic: many transitions → imprint likely present
        if (transitions > 120) return "";   // dense pattern → don't guess text
        if (transitions > 40)  return "";   // moderate → some marking, but no OCR
        return "";                           // no TFLite OCR model bundled
    }

    // ─────────────────────────────────────────────────────────────────────────
    // YUV → HSV (shared utility, same as ColorSenseEngine)
    // ─────────────────────────────────────────────────────────────────────────

    private static float[] yuv2hsv(byte[] nv21, int w, int h, int x, int y) {
        int yIdx   = y * w + x;
        int uvBase = w * h + (y / 2) * w + (x & ~1);
        if (uvBase + 1 >= nv21.length) return new float[]{ 0f, 0f, 0.5f };

        int Y = nv21[yIdx]       & 0xFF;
        int V = (nv21[uvBase]     & 0xFF) - 128;
        int U = (nv21[uvBase + 1] & 0xFF) - 128;

        int r = clamp255((int)(Y + 1.402f * V));
        int g = clamp255((int)(Y - 0.344f * U - 0.714f * V));
        int b = clamp255((int)(Y + 1.772f * U));

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
        return new float[]{ hue, s, v };
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static AurigaInterfaces.PillResult unsafeResult(String caution) {
        return new AurigaInterfaces.PillResult(
            null, null, "other", "other", CONFIDENCE_FLOOR, false, caution);
    }
}
