package com.drakosanctis.auriga;

import android.content.Context;
import android.util.Log;

/**
 * StairSenseEngine™ — Session 5 (Phase 1, parallel with 4 & 6).
 *
 * <p>Implements {@link AurigaInterfaces.IStairSenseEngine}.
 *
 * <h3>Algorithm</h3>
 * <p>Works entirely on the NV21 luminance (Y) plane — no RGB conversion needed.
 *
 * <ol>
 *   <li><b>Horizontal-edge scanning</b> — divides the lower 65% of the frame
 *       into {@value BAND_COUNT} horizontal bands. Inside each band, a column
 *       of pixels at the centre of the frame is scanned vertically. Transitions
 *       where adjacent-pixel brightness changes by ≥ {@value EDGE_THRESHOLD}
 *       counts score as a horizontal-edge hit in that band.</li>
 *
 *   <li><b>Step counting</b> — each band that scores above
 *       {@value EDGE_HIT_THRESHOLD} is labelled a "step edge". Consecutive
 *       empty bands between step edges are counted as the riser spacing.
 *       Multiple qualifying edges → estimated step count.</li>
 *
 *   <li><b>Direction classification</b> (ascending / descending)</b> — edge
 *       density increases toward the top of the scan region for ascending
 *       stairs (vanishing point) and toward the bottom for descending
 *       (treads converge toward the viewer). A weighted density comparison
 *       between the upper and lower halves of the detection window decides
 *       direction.</li>
 *
 *   <li><b>Distance estimate</b> — the row of the first (lowest on screen)
 *       detected step edge is converted to metres using the same ratio-based
 *       heuristic as {@link TriangulationEngine}: a step edge at the frame
 *       midline corresponds to roughly 1.5 m; at the bottom edge, roughly
 *       0.5 m. Linear interpolation between these anchors gives a coarse
 *       but useful distance.</li>
 * </ol>
 *
 * <h3>NV21 luminance access</h3>
 * {@code Y = nv21[y * width + x] & 0xFF} — no chroma planes needed.
 *
 * <h3>Limitations</h3>
 * This module is a geometry-heuristic classifier. It works well in
 * well-lit indoor and outdoor stair environments. Low-light scenes
 * return {@code stairsDetected = false} (the Y plane is too flat to
 * find step edges). A TFLite stair-detection model (future Phase 2+)
 * will replace this for difficult conditions.
 */
public class StairSenseEngine implements AurigaInterfaces.IStairSenseEngine {

    private static final String TAG = "StairSenseEngine";

    // Number of horizontal scan bands in the lower 65% of the frame
    private static final int   BAND_COUNT          = 20;
    // Brightness delta required to count as a horizontal edge in a band
    private static final int   EDGE_THRESHOLD      = 28;
    // Fraction of sampled pixels in a band that must be edge-hits for
    // the band to count as a stair tread edge
    private static final float EDGE_HIT_THRESHOLD  = 0.25f;
    // Minimum step edges required before we report stairs detected
    private static final int   MIN_STEP_EDGES      = 2;
    // Number of pixels per column sampled horizontally for edge scoring
    private static final int   HORIZ_SAMPLE_COUNT  = 20;
    // Minimum inter-band gap (number of empty bands) between two step edges
    private static final int   MIN_RISER_GAP       = 1;

    // Distance anchors: bottom of scan region ≈ 0.5 m, top ≈ 2.5 m
    private static final float DIST_BOTTOM_M = 0.5f;
    private static final float DIST_TOP_M    = 2.5f;

    // ─────────────────────────────────────────────────────────────────────────
    // IStairSenseEngine
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public AurigaInterfaces.StairResult analyse(byte[] nv21, int width, int height) {
        if (nv21 == null || nv21.length < width * height) {
            return noStairs();
        }

        // Scan region: lower 65% of the frame
        int scanTop    = height * 35 / 100;
        int scanBottom = height - 4;
        int scanHeight = scanBottom - scanTop;
        if (scanHeight <= 0) return noStairs();

        int    bandHeight = Math.max(1, scanHeight / BAND_COUNT);
        boolean[] edgeBand = new boolean[BAND_COUNT];
        int    firstEdgeBand = -1; // lowest on screen (highest band index)
        int    edgeCount  = 0;

        // For direction: sum edge hits in upper vs lower halves of scan
        float upperScore = 0, lowerScore = 0;

        for (int b = 0; b < BAND_COUNT; b++) {
            int bandY = scanTop + b * bandHeight + bandHeight / 2;
            if (bandY >= height) break;

            float score = horizontalEdgeScore(nv21, width, height, bandY);

            if (b < BAND_COUNT / 2) upperScore += score;
            else                    lowerScore += score;

            if (score >= EDGE_HIT_THRESHOLD) {
                edgeBand[b] = true;
                edgeCount++;
                if (firstEdgeBand < 0) firstEdgeBand = b;
            }
        }

        // Count qualifying step edges (separated by at least MIN_RISER_GAP empty bands)
        int stepEdges = countStepEdges(edgeBand, MIN_RISER_GAP);

        if (stepEdges < MIN_STEP_EDGES) return noStairs();

        // Direction: more edges in upper half of scan = ascending (tread lines
        // recede upward); more in lower half = descending (treads close to viewer)
        AurigaInterfaces.StairDirection direction;
        if (upperScore > lowerScore * 1.4f) {
            direction = AurigaInterfaces.StairDirection.ASCENDING;
        } else if (lowerScore > upperScore * 1.4f) {
            direction = AurigaInterfaces.StairDirection.DESCENDING;
        } else {
            direction = AurigaInterfaces.StairDirection.UNKNOWN;
        }

        // Distance estimate from first (topmost on screen) step edge row
        float distM = rowToDistance(
                scanTop + firstEdgeBand * bandHeight, height, scanTop, scanBottom);

        Log.d(TAG, "stairs detected: steps≈" + stepEdges
                + " dir=" + direction + " dist=" + distM + "m");

        return new AurigaInterfaces.StairResult(true, stepEdges, direction, distM);
    }

    @Override
    public boolean selfTest(Context ctx) {
        // Build a synthetic NV21 with 3 horizontal bright bands (simulated steps)
        int w = 64, h = 64;
        byte[] nv21 = new byte[w * h * 3 / 2];
        // Fill Y plane with mid-grey (128)
        for (int i = 0; i < w * h; i++) nv21[i] = (byte) 100;
        // Add bright horizontal bands at 3 stair-like positions
        for (int row : new int[]{42, 48, 54}) {
            for (int x = 0; x < w; x++) nv21[row * w + x] = (byte) 200;
        }
        AurigaInterfaces.StairResult r = analyse(nv21, w, h);
        boolean ok = r.stairsDetected && r.stepCount >= 2;
        Log.i(TAG, "selfTest → " + ok + " (steps=" + r.stepCount + ")");
        return ok;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Score the horizontal edge density at row {@code bandY} by sampling
     * {@value HORIZ_SAMPLE_COUNT} evenly-spaced columns and computing the
     * fraction of adjacent vertical pixel pairs that exceed {@value EDGE_THRESHOLD}.
     */
    private float horizontalEdgeScore(byte[] nv21, int width, int height, int bandY) {
        if (bandY <= 0 || bandY >= height - 1) return 0f;
        int hits  = 0;
        int step  = Math.max(1, width / HORIZ_SAMPLE_COUNT);
        int count = 0;
        for (int x = step / 2; x < width; x += step) {
            int yAbove = nv21[(bandY - 1) * width + x] & 0xFF;
            int yBelow = nv21[(bandY + 1) * width + x] & 0xFF;
            if (Math.abs(yAbove - yBelow) >= EDGE_THRESHOLD) hits++;
            count++;
        }
        return count > 0 ? (float) hits / count : 0f;
    }

    /**
     * Count the number of step edges in the edge-band boolean array, where
     * two edges must be separated by at least {@code minGap} empty bands to
     * be counted as distinct steps.
     */
    private static int countStepEdges(boolean[] edgeBand, int minGap) {
        int steps = 0;
        int gapSince = minGap + 1; // start "already past the gap"
        for (boolean e : edgeBand) {
            if (e && gapSince >= minGap) {
                steps++;
                gapSince = 0;
            } else if (!e) {
                gapSince++;
            }
        }
        return steps;
    }

    /**
     * Linear interpolation between the distance anchors. A step edge in the
     * upper portion of the scan region is farther away.
     */
    private static float rowToDistance(int row, int frameH, int scanTop, int scanBottom) {
        float t = (float)(row - scanTop) / Math.max(1, scanBottom - scanTop);
        // t=0 → top of scan (far) → DIST_TOP_M; t=1 → bottom (near) → DIST_BOTTOM_M
        return DIST_TOP_M - t * (DIST_TOP_M - DIST_BOTTOM_M);
    }

    private static AurigaInterfaces.StairResult noStairs() {
        return new AurigaInterfaces.StairResult(false, 0,
                AurigaInterfaces.StairDirection.UNKNOWN, 0f);
    }
}
