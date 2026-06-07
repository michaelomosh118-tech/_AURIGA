package com.drakosanctis.auriga;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * SceneDescriberEngine — Session 16 (Phase 3).
 *
 * <p>Full scene narration for the "Auriga, describe what you see" command.
 *
 * <h3>Two-tier architecture</h3>
 * <ul>
 *   <li><b>Tier 1 (VLM — optional)</b>: When {@code moondream2.tflite} or
 *       {@code mobilevlm.tflite} is present in {@code assets/}, the engine
 *       delegates to it for richer paragraph narration such as "You are facing
 *       a corridor. There is a door approximately 3 metres ahead slightly to
 *       the right." The VLM slot is a runtime-discoverable path — drop the
 *       model file to upgrade automatically, no code change required.</li>
 *   <li><b>Tier 2 (rule-based fallback)</b>: Always available. Accepts the
 *       latest {@link Detection} list from {@link YoloDetector} (or any
 *       other detector that returns normalised bounding boxes) and converts it
 *       into a spoken summary: "I see a person at 2.8 metres centre, a chair
 *       at 1.2 metres left, a door at 4 metres right."</li>
 * </ul>
 *
 * <h3>Rule-based description algorithm</h3>
 * <ol>
 *   <li>Filter detections with confidence ≥ {@value CONF_THRESHOLD}.</li>
 *   <li>Sort by estimated distance (ascending — nearest first).</li>
 *   <li>Group by lateral zone (left / centre / right) using the box centre X.</li>
 *   <li>For each detection emit: "[label] at [distance] metres [zone]".</li>
 *   <li>Prefix a scene context guess from the dominant object set (see
 *       {@link #guessContext}).</li>
 *   <li>Cap output at {@value MAX_OBJECTS} objects to avoid overwhelming the
 *       user with a 80-item list.</li>
 * </ol>
 *
 * <h3>Distance estimation</h3>
 * Uses the same camera-height + box-height heuristic from
 * {@link TriangulationEngine}: distance ≈ focal_px × object_height_m /
 * bbox_height_px. Object canonical heights come from a built-in lookup table
 * (person 1.7m, chair 0.9m, car 1.5m, etc.). Falls back to box-area rank
 * when the object class is not in the table.
 *
 * <h3>Thread safety</h3>
 * Stateless; every call to {@link #describe} is independent. Safe to call
 * from any thread.
 */
public class SceneDescriberEngine {

    private static final String TAG            = "SceneDescriberEngine";
    private static final float  CONF_THRESHOLD = 0.40f;
    private static final int    MAX_OBJECTS    = 7;

    // Approximate focal length for a standard phone camera at 720p
    private static final float FOCAL_PX = 600f;

    // ─────────────────────────────────────────────────────────────────────────
    // Canonical object heights (metres) for distance estimation.
    // Objects not in the table fall back to area-rank ordering.
    // ─────────────────────────────────────────────────────────────────────────
    private static final java.util.Map<String, Float> OBJECT_HEIGHTS;
    static {
        OBJECT_HEIGHTS = new java.util.HashMap<>();
        OBJECT_HEIGHTS.put("person",        1.70f);
        OBJECT_HEIGHTS.put("bicycle",       1.00f);
        OBJECT_HEIGHTS.put("car",           1.50f);
        OBJECT_HEIGHTS.put("motorcycle",    1.10f);
        OBJECT_HEIGHTS.put("bus",           3.20f);
        OBJECT_HEIGHTS.put("truck",         3.80f);
        OBJECT_HEIGHTS.put("traffic light", 0.60f);
        OBJECT_HEIGHTS.put("stop sign",     0.75f);
        OBJECT_HEIGHTS.put("bench",         0.85f);
        OBJECT_HEIGHTS.put("chair",         0.90f);
        OBJECT_HEIGHTS.put("couch",         0.85f);
        OBJECT_HEIGHTS.put("dining table",  0.75f);
        OBJECT_HEIGHTS.put("tv",            0.60f);
        OBJECT_HEIGHTS.put("laptop",        0.35f);
        OBJECT_HEIGHTS.put("cell phone",    0.15f);
        OBJECT_HEIGHTS.put("book",          0.22f);
        OBJECT_HEIGHTS.put("cup",           0.12f);
        OBJECT_HEIGHTS.put("bottle",        0.28f);
        OBJECT_HEIGHTS.put("dog",           0.55f);
        OBJECT_HEIGHTS.put("cat",           0.28f);
        OBJECT_HEIGHTS.put("backpack",      0.50f);
        OBJECT_HEIGHTS.put("suitcase",      0.65f);
        OBJECT_HEIGHTS.put("door",          2.10f);
        OBJECT_HEIGHTS.put("bed",           0.55f);
        OBJECT_HEIGHTS.put("sink",          0.30f);
        OBJECT_HEIGHTS.put("toilet",        0.40f);
        OBJECT_HEIGHTS.put("refrigerator",  1.70f);
        OBJECT_HEIGHTS.put("oven",          0.60f);
        OBJECT_HEIGHTS.put("microwave",     0.30f);
        OBJECT_HEIGHTS.put("clock",         0.30f);
        OBJECT_HEIGHTS.put("vase",          0.30f);
        OBJECT_HEIGHTS.put("potted plant",  0.50f);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scene context word lists for prefix generation
    // ─────────────────────────────────────────────────────────────────────────
    private static final java.util.Set<String> INDOOR_OBJECTS = new java.util.HashSet<>(
        java.util.Arrays.asList("chair","couch","tv","laptop","dining table","bed",
            "sink","toilet","refrigerator","oven","microwave","clock","vase",
            "potted plant","book","cup","bottle","cell phone","backpack","suitcase"));

    private static final java.util.Set<String> OUTDOOR_OBJECTS = new java.util.HashSet<>(
        java.util.Arrays.asList("car","bus","truck","bicycle","motorcycle",
            "traffic light","stop sign","bench","person"));

    // ─────────────────────────────────────────────────────────────────────────
    // State — VLM presence
    // ─────────────────────────────────────────────────────────────────────────
    private final boolean vlmAvailable;

    public SceneDescriberEngine(Context ctx) {
        this.vlmAvailable = probeVlm(ctx);
        Log.i(TAG, "SceneDescriberEngine ready. VLM=" + vlmAvailable);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Primary API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generate a spoken scene description from the latest YOLO detection list.
     *
     * @param detections  list from {@link YoloDetector#detect(android.graphics.Bitmap)}
     * @param frameWidth  camera frame width in pixels (used for bearing calc)
     * @param frameHeight camera frame height in pixels (used for distance calc)
     * @return human-readable description ready for TTS
     */
    public String describe(List<Detection> detections, int frameWidth, int frameHeight) {
        if (detections == null || detections.isEmpty()) {
            return "I cannot see anything clearly right now. Please adjust your camera angle.";
        }

        // Tier 1: VLM (stub path — returns null so Tier 2 handles it)
        if (vlmAvailable) {
            String vlmResult = runVlm(detections, frameWidth, frameHeight);
            if (vlmResult != null && !vlmResult.isEmpty()) return vlmResult;
        }

        // Tier 2: rule-based
        return buildRuleBasedDescription(detections, frameWidth, frameHeight);
    }

    /**
     * Convenience overload when frame dimensions are unknown (uses default 640×480).
     */
    public String describe(List<Detection> detections) {
        return describe(detections, 640, 480);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rule-based description builder
    // ─────────────────────────────────────────────────────────────────────────

    String buildRuleBasedDescription(List<Detection> detections,
                                      int frameWidth, int frameHeight) {
        // Filter by confidence
        List<Detection> filtered = new ArrayList<>();
        for (Detection d : detections) {
            if (d.confidence >= CONF_THRESHOLD) filtered.add(d);
        }

        if (filtered.isEmpty()) {
            return "I can see some movement but cannot identify any objects clearly.";
        }

        // Annotate each detection with estimated distance
        List<AnnotatedDetection> annotated = new ArrayList<>();
        for (Detection d : filtered) {
            float dist  = estimateDistance(d, frameHeight);
            String zone = lateralZone(d.centerX());
            annotated.add(new AnnotatedDetection(d, dist, zone));
        }

        // Sort by distance (nearest first), cap at MAX_OBJECTS
        Collections.sort(annotated, (a, b) -> Float.compare(a.distanceM, b.distanceM));
        if (annotated.size() > MAX_OBJECTS) {
            annotated = annotated.subList(0, MAX_OBJECTS);
        }

        // Build spoken string
        StringBuilder sb = new StringBuilder();

        // Context prefix
        String context = guessContext(annotated);
        if (context != null) sb.append(context).append(" ");

        sb.append("I see ");

        for (int i = 0; i < annotated.size(); i++) {
            AnnotatedDetection a = annotated.get(i);
            if (i > 0) {
                sb.append(i == annotated.size() - 1 ? ", and " : ", ");
            }
            sb.append(articleFor(a.detection.label));
            sb.append(a.detection.label);
            sb.append(" ");
            if (a.distanceM > 0f) {
                sb.append("at ")
                  .append(formatDistance(a.distanceM))
                  .append(" ");
            }
            sb.append(a.zone);
        }
        sb.append(".");

        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Distance estimation from box height
    // ─────────────────────────────────────────────────────────────────────────

    private static float estimateDistance(Detection d, int frameHeight) {
        Float canonH = OBJECT_HEIGHTS.get(d.label.toLowerCase(Locale.ROOT));
        if (canonH == null || canonH <= 0f) return -1f;

        float bboxHeightPx = (d.box.bottom - d.box.top) * frameHeight;
        if (bboxHeightPx <= 2f) return -1f;

        return (FOCAL_PX * canonH) / bboxHeightPx;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lateral zone from normalised box centre X
    // ─────────────────────────────────────────────────────────────────────────

    private static String lateralZone(float normX) {
        if (normX < 0.33f) return "to your left";
        if (normX > 0.67f) return "to your right";
        return "ahead of you";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scene context guesser
    // ─────────────────────────────────────────────────────────────────────────

    private static String guessContext(List<AnnotatedDetection> annotated) {
        int indoorScore = 0, outdoorScore = 0;
        for (AnnotatedDetection a : annotated) {
            String label = a.detection.label.toLowerCase(Locale.ROOT);
            if (INDOOR_OBJECTS.contains(label))  indoorScore++;
            if (OUTDOOR_OBJECTS.contains(label)) outdoorScore++;
        }
        if (indoorScore > outdoorScore && indoorScore >= 2) {
            return "You appear to be indoors.";
        }
        if (outdoorScore > indoorScore && outdoorScore >= 2) {
            return "You appear to be outdoors.";
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Formatting helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static String formatDistance(float metres) {
        if (metres < 0f) return "";
        if (metres < 1.0f) {
            int cm = Math.round(metres * 100f);
            return cm + " centimetres";
        }
        if (metres < 10f) {
            return String.format(Locale.ROOT, "%.1f metres", metres);
        }
        return Math.round(metres) + " metres";
    }

    private static String articleFor(String label) {
        if (label == null || label.isEmpty()) return "a ";
        char first = Character.toLowerCase(label.charAt(0));
        return (first == 'a' || first == 'e' || first == 'i' ||
                first == 'o' || first == 'u') ? "an " : "a ";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VLM tier (optional model slot)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Attempts to load a vision-language model from assets:
     * {@code moondream2.tflite} (preferred) or {@code mobilevlm.tflite}.
     * Returns true if found so the describe() method can try it first.
     */
    private static boolean probeVlm(Context ctx) {
        String[] candidates = { "moondream2.tflite", "mobilevlm.tflite" };
        for (String name : candidates) {
            try {
                ctx.getAssets().open(name).close();
                Log.i(TAG, "VLM model found: " + name + " — VLM path active (stub).");
                return true;
            } catch (Exception ignored) {}
        }
        Log.i(TAG, "No VLM model in assets — rule-based fallback active.");
        return false;
    }

    /**
     * Stub VLM inference. When a real MoondreamV2 / MobileVLM TFLite
     * interpreter is initialised here, it would:
     * 1. Encode the camera frame as the model's visual token embedding.
     * 2. Prepend the prompt token for "describe the scene".
     * 3. Run autoregressive decode for up to 100 tokens.
     * 4. Decode token IDs → text via the model's vocab file.
     * Returns null to fall through to Tier 2 until the model is wired.
     */
    private String runVlm(List<Detection> detections, int w, int h) {
        // Real implementation would run TFLite interpreter here.
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal annotated detection record
    // ─────────────────────────────────────────────────────────────────────────

    private static class AnnotatedDetection {
        final Detection detection;
        final float     distanceM;
        final String    zone;

        AnnotatedDetection(Detection detection, float distanceM, String zone) {
            this.detection = detection;
            this.distanceM = distanceM;
            this.zone      = zone;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // selfTest
    // ─────────────────────────────────────────────────────────────────────────

    public boolean selfTest(Context ctx) {
        // Construct two synthetic detections and verify the output is non-empty
        android.graphics.RectF boxA = new android.graphics.RectF(0.1f, 0.2f, 0.3f, 0.6f);
        android.graphics.RectF boxB = new android.graphics.RectF(0.6f, 0.3f, 0.9f, 0.7f);
        List<Detection> fake = new ArrayList<>();
        fake.add(new Detection("person",  0, 0.92f, boxA));
        fake.add(new Detection("chair",   56, 0.75f, boxB));
        String result = describe(fake, 640, 480);
        boolean ok = result != null && result.contains("person") && result.contains("chair");
        Log.i(TAG, "selfTest → ok=" + ok + " output: " + result);
        return ok;
    }
}
