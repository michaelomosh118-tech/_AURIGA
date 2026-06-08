package com.drakosanctis.auriga;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * DracoAIDEngine — "Virtual Snap" auto-calibration.
 *
 * Watches every frame's YOLO detection list. The moment a "person"
 * detection appears with the bounding box straddling the frame in a
 * way that lets us solve for camera height H_c, it does so silently
 * and rebuilds the FiducialLUT with a physics-derived table — no
 * physical marker, no calibration walk required.
 *
 * The H_c solve (derivation):
 *
 *   For a person of height 1.7 m standing on the floor at distance D,
 *   with the camera at height H_c:
 *
 *     anklePixelY = horizonRow + f * H_c / D          ... (1)
 *     headPixelY  = horizonRow - f * (1.7 - H_c) / D  ... (2)
 *
 *   Let  a = anklePixelY - horizonRow   (pixels below horizon to ankle)
 *        b = horizonRow  - headPixelY   (pixels above horizon to head)
 *
 *   Eliminating D from (1) and (2):
 *
 *     H_c = 1.7 * a / (a + b)
 *         = 1.7 * a / (anklePixelY - headPixelY)
 *
 *   Note: (a + b) == person bounding-box pixel height — always positive.
 *
 * Stability gate: five consecutive readings within ±15 % of the median
 * must accumulate before the LUT is updated, so a single-frame
 * mis-detection cannot poison the engine.
 *
 * Self-healing: after each calibration, the engine watches for subsequent
 * detections to validate: if the new H_c would drift more than 20 % from
 * the last committed value, it re-enters the accumulation phase.
 */
public class DracoAIDEngine {

    private static final String TAG = "DracoAIDEngine";

    private static final String COCO_PERSON = "person";

    private static final float  PERSON_HEIGHT_M   = 1.70f;
    private static final float  HC_MIN_M          = 0.40f;
    private static final float  HC_MAX_M          = 2.50f;
    private static final int    STABILITY_WINDOW  = 5;
    private static final float  STABILITY_BAND    = 0.15f;
    private static final float  REDRIFT_THRESHOLD = 0.20f;
    private static final float  MIN_BOX_FRACTION  = 0.20f;
    private static final long   RECAL_INTERVAL_MS = 30_000L;

    private final FiducialLUT lut;
    private final HardwareHAL hal;

    private final List<Float> hcBuffer = new ArrayList<>(STABILITY_WINDOW + 2);
    private float  committedHc    = -1f;
    private long   lastCalTime    = 0L;
    private int    frameWidth     = 640;
    private int    frameHeight    = 480;

    public DracoAIDEngine(FiducialLUT lut, HardwareHAL hal) {
        this.lut = lut;
        this.hal = hal;

        // Re-apply any previously committed H_c from persistent storage
        // so the LUT is physics-based on first frame even before the
        // first person is seen.
        float stored = hal.loadStoredHc();
        if (stored > 0f) {
            committedHc = stored;
            Log.d(TAG, "Restored H_c from storage: " + stored + " m");
        }
    }

    /**
     * Called once per inference frame. Iterates the YOLO detections,
     * extracts the best "person" candidate, and attempts an H_c solve.
     * Everything is intentionally lightweight — no allocation on the
     * hot path beyond the list scan itself.
     *
     * @param detections Full YOLO detection list for this frame.
     * @param frameW     Current preview frame width in pixels.
     * @param frameH     Current preview frame height in pixels.
     */
    public void processFrame(List<Detection> detections,
                             int frameW, int frameH) {
        this.frameWidth  = frameW;
        this.frameHeight = frameH;

        Detection person = bestPerson(detections, frameH);
        if (person == null) return;

        float hcEstimate = solveHc(person, frameH);
        if (hcEstimate < 0f) return;

        long now = System.currentTimeMillis();

        // If already calibrated, check for drift.
        if (committedHc > 0f) {
            float drift = Math.abs(hcEstimate - committedHc) / committedHc;
            if (drift > REDRIFT_THRESHOLD
                    && (now - lastCalTime) > RECAL_INTERVAL_MS) {
                // Something changed (user handed phone to shorter/taller
                // person, or is sitting). Enter re-accumulation.
                hcBuffer.clear();
                Log.d(TAG, "Drift " + String.format("%.0f", drift * 100)
                        + "% — re-accumulating H_c");
            } else if (drift <= REDRIFT_THRESHOLD) {
                // Consistent with existing commit — no action needed.
                return;
            }
        }

        hcBuffer.add(hcEstimate);

        if (hcBuffer.size() >= STABILITY_WINDOW) {
            float median = median(hcBuffer);
            if (isStable(hcBuffer, median)) {
                commit(median, frameH);
            }
            // Always trim to window size to prevent unbounded growth.
            while (hcBuffer.size() > STABILITY_WINDOW) {
                hcBuffer.remove(0);
            }
        }
    }

    /**
     * Returns the last committed H_c (metres), or –1 if not yet solved.
     * LocatorActivity shows a "calibrating…" overlay until this is ≥ 0.
     */
    public float getCommittedHc() {
        return committedHc;
    }

    /**
     * True once the LUT has been populated with a physics-derived table
     * from at least one stable H_c solve or a restored stored value.
     */
    public boolean isCalibrated() {
        return committedHc > 0f;
    }

    // ---------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------

    /**
     * Finds the largest "person" detection whose bounding box is tall
     * enough to give a useful H_c estimate (at least MIN_BOX_FRACTION
     * of frame height). Returns null if no qualifying person is found.
     */
    private Detection bestPerson(List<Detection> dets, int fH) {
        Detection best = null;
        float     bestH = 0f;
        for (Detection d : dets) {
            if (!COCO_PERSON.equalsIgnoreCase(d.label)) continue;
            float boxFraction = d.box.bottom - d.box.top;
            if (boxFraction < MIN_BOX_FRACTION) continue;
            if (boxFraction > bestH) {
                bestH = boxFraction;
                best  = d;
            }
        }
        return best;
    }

    /**
     * Solves camera height H_c from a person detection.
     *
     *   H_c = PERSON_HEIGHT_M * a / (a + b)
     *
     * where  a = anklePixelY − horizonRow   (pixels of ankle below horizon)
     *        b = horizonRow  − headPixelY   (pixels of head above horizon)
     *
     * (a + b) == bbox pixel height, which is always positive.
     * We only require a > 0 (ankle strictly below horizon) so the
     * formula gives a meaningful height in [HC_MIN_M, HC_MAX_M].
     *
     * Returns –1 on any validation failure.
     */
    private float solveHc(Detection person, int fH) {
        float horizonRow  = fH / 2.0f;
        float headPixelY  = person.box.top    * fH;
        float anklePixelY = person.box.bottom * fH;

        float a = anklePixelY - horizonRow;
        float b = horizonRow  - headPixelY;

        // bbox pixel height — denominator — must be positive and non-zero.
        float bboxPixelH = anklePixelY - headPixelY;
        if (bboxPixelH <= 0f) return -1f;

        // Ankle must be below horizon (a > 0) for the geometry to hold.
        if (a <= 0f) return -1f;

        float hc = PERSON_HEIGHT_M * a / bboxPixelH;
        if (hc < HC_MIN_M || hc > HC_MAX_M) return -1f;

        return hc;
    }

    private float median(List<Float> vals) {
        List<Float> sorted = new ArrayList<>(vals);
        java.util.Collections.sort(sorted);
        int n = sorted.size();
        return (n % 2 == 0)
                ? (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2f
                : sorted.get(n / 2);
    }

    private boolean isStable(List<Float> vals, float med) {
        for (float v : vals) {
            if (Math.abs(v - med) / med > STABILITY_BAND) return false;
        }
        return true;
    }

    /**
     * Commits a new H_c: regenerates the FiducialLUT dynamic table,
     * persists the value, and clears the accumulation buffer.
     */
    private void commit(float hc, int fH) {
        committedHc = hc;
        lastCalTime = System.currentTimeMillis();
        hcBuffer.clear();

        float focalPx = hal.getFocalLengthPx(frameWidth);
        lut.generateDynamicTable(hc, focalPx, fH);
        hal.storeHc(hc);

        Log.i(TAG, "DracoAID committed H_c = "
                + String.format("%.2f", hc) + " m  "
                + "focal=" + String.format("%.1f", focalPx) + "px  "
                + "LUT points=" + lut.getPointCount());
    }
}
