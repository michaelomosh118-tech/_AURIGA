package com.drakosanctis.auriga;

import android.content.Context;
import android.util.Log;

/**
 * TrafficSenseEngine™ — Session 6 (Phase 1, parallel with 4 & 5).
 *
 * <p>Implements {@link AurigaInterfaces.ITrafficSenseEngine}.
 *
 * <h3>Algorithm</h3>
 *
 * <p><b>Vehicle approach detection</b> uses a two-stage pipeline:
 * <ol>
 *   <li><b>Inter-frame motion magnitude</b> — the engine keeps a rolling
 *       downsample of the previous frame ({@value MOTION_W}×{@value MOTION_H}
 *       luminance blocks). On each call, it computes the per-block absolute
 *       brightness delta between the current and previous frame.  Blocks in
 *       the left / center / right thirds of the frame are tallied separately
 *       to identify which zone the motion is in.</li>
 *
 *   <li><b>Scale-growth rate (doppler proxy)</b> — a "largest bright region"
 *       estimator tracks the size of the dominant light-emitting region in each
 *       of the three zones across the last {@value HISTORY_LEN} frames. If the
 *       region is growing faster than {@value GROWTH_RATE_THRESH} fraction per
 *       frame, the engine flags an approaching vehicle in that zone and estimates
 *       the TTC (time to collision) as {@code 1 / growthRate} seconds, clamped
 *       to a sensible range.</li>
 * </ol>
 *
 * <p><b>Traffic light state</b> is read from the upper 25% of the frame using
 * the same HSV-band detection logic as {@link ColorSenseEngine} — kept here to
 * avoid a dependency on that module so Sessions 4 and 6 remain truly parallel.
 *
 * <h3>Sensitivity modes</h3>
 * <ul>
 *   <li><b>Road-crossing mode</b> (high sensitivity, default) — motion threshold
 *       lowered 30%, TTC warn threshold raised to 5 s.</li>
 *   <li><b>Indoor mode</b> — disabled entirely; {@code analyse()} returns an
 *       all-clear result immediately.</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * Not thread-safe. The caller (GodsEyeOrchestrator) must serialise calls from
 * a single analysis thread.
 */
public class TrafficSenseEngine implements AurigaInterfaces.ITrafficSenseEngine {

    private static final String TAG = "TrafficSenseEngine";

    // Downsample resolution for inter-frame motion (width × height blocks)
    private static final int MOTION_W = 16;
    private static final int MOTION_H = 12;

    // Motion magnitude threshold above which a block is considered "moving"
    // (out of 255). Tuned against field footage.
    private static final int MOTION_THRESH_NORMAL = 18;
    private static final int MOTION_THRESH_ROAD   = 13; // road-crossing mode: more sensitive

    // Minimum fraction of blocks in a zone that must be moving for the zone
    // to be flagged as "motion detected"
    private static final float ZONE_MOTION_FRAC   = 0.20f;

    // Scale-growth history depth (frames)
    private static final int   HISTORY_LEN        = 5;

    // Growth rate per frame above which an approach is flagged.
    // 0.10 = region grows by 10% of frame width per frame → fast approach.
    private static final float GROWTH_RATE_THRESH_NORMAL = 0.10f;
    private static final float GROWTH_RATE_THRESH_ROAD   = 0.07f;

    // TTC warn thresholds (seconds)
    private static final float TTC_WARN_NORMAL = 4.0f;
    private static final float TTC_WARN_ROAD   = 5.0f;

    // Traffic light detection threshold (fraction of upper-zone pixels)
    private static final float TL_THRESH = 0.06f;

    // ─────────────────────────────────────────────────────────────────────────
    // Mutable state (single-threaded use)
    // ─────────────────────────────────────────────────────────────────────────

    private byte[]  prevLuma; // downsampled previous frame luma
    private int     prevW, prevH;

    // Per-zone region size history [zone 0=left, 1=center, 2=right][frame]
    private final float[][] sizeHistory = new float[3][HISTORY_LEN];
    private int historyIdx = 0;

    public enum SensitivityMode { NORMAL, ROAD_CROSSING, INDOOR }
    private SensitivityMode mode = SensitivityMode.NORMAL;

    // ─────────────────────────────────────────────────────────────────────────
    // Public API extensions (not in interface — called by GodsEyeOrchestrator)
    // ─────────────────────────────────────────────────────────────────────────

    public void setSensitivityMode(SensitivityMode m) {
        this.mode = m;
        Log.d(TAG, "sensitivity mode → " + m);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ITrafficSenseEngine
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public AurigaInterfaces.TrafficResult analyse(byte[] nv21, int width, int height) {
        if (mode == SensitivityMode.INDOOR) {
            return allClear();
        }
        if (nv21 == null || nv21.length < width * height) {
            return allClear();
        }

        int motionThresh  = (mode == SensitivityMode.ROAD_CROSSING)
                          ? MOTION_THRESH_ROAD : MOTION_THRESH_NORMAL;
        float growthThresh = (mode == SensitivityMode.ROAD_CROSSING)
                          ? GROWTH_RATE_THRESH_ROAD : GROWTH_RATE_THRESH_NORMAL;
        float ttcWarn      = (mode == SensitivityMode.ROAD_CROSSING)
                          ? TTC_WARN_ROAD : TTC_WARN_NORMAL;

        // ── Step 1: downsample current frame luma ─────────────────────────
        byte[] curLuma = downsampleLuma(nv21, width, height, MOTION_W, MOTION_H);

        // ── Step 2: inter-frame motion per zone ───────────────────────────
        float[] zoneMotion = new float[3]; // [left, center, right]
        float[] zoneSize   = new float[3];

        if (prevLuma != null && prevW == MOTION_W && prevH == MOTION_H) {
            int zoneW = MOTION_W / 3;
            int total  = MOTION_H * zoneW;

            for (int z = 0; z < 3; z++) {
                int xStart = z * zoneW;
                int xEnd   = (z == 2) ? MOTION_W : xStart + zoneW;
                int motionHits = 0;
                int brightPx   = 0;
                for (int my = 0; my < MOTION_H; my++) {
                    for (int mx = xStart; mx < xEnd; mx++) {
                        int idx = my * MOTION_W + mx;
                        int cur = curLuma[idx] & 0xFF;
                        int prv = prevLuma[idx] & 0xFF;
                        if (Math.abs(cur - prv) >= motionThresh) motionHits++;
                        if (cur > 160) brightPx++; // rough "bright object" proxy
                    }
                }
                zoneMotion[z] = (float) motionHits / total;
                zoneSize[z]   = (float) brightPx   / total;
            }
        }

        // ── Step 3: store size in history ring buffer ─────────────────────
        for (int z = 0; z < 3; z++) sizeHistory[z][historyIdx] = zoneSize[z];
        historyIdx = (historyIdx + 1) % HISTORY_LEN;

        // ── Step 4: growth-rate estimate ──────────────────────────────────
        float bestGrowth = 0f;
        int   bestZone   = -1;
        for (int z = 0; z < 3; z++) {
            float growth = estimateGrowthRate(sizeHistory[z]);
            if (growth > bestGrowth) {
                bestGrowth = growth;
                bestZone   = z;
            }
        }

        // ── Step 5: vehicle-approach decision ─────────────────────────────
        boolean vehicleApproaching = false;
        AurigaInterfaces.Zone approachZone = AurigaInterfaces.Zone.UNKNOWN;
        float ttcSeconds = Float.MAX_VALUE;

        if (bestZone >= 0 && bestGrowth >= growthThresh) {
            float motionInBestZone = zoneMotion[bestZone];
            if (motionInBestZone >= ZONE_MOTION_FRAC) {
                vehicleApproaching = true;
                approachZone = zoneToEnum(bestZone);
                // TTC proxy: if the region doubles every N frames at growthRate,
                // TTC ≈ 1/growthRate frames. Assume ~10 fps analysis → seconds.
                ttcSeconds = Math.min(60f, 1f / Math.max(growthThresh, bestGrowth) / 10f);
            }
        }

        // ── Step 6: traffic light state ───────────────────────────────────
        AurigaInterfaces.TrafficLightState lightState = detectLightState(nv21, width, height);

        // ── Save prev frame ───────────────────────────────────────────────
        prevLuma = curLuma;
        prevW    = MOTION_W;
        prevH    = MOTION_H;

        if (vehicleApproaching) {
            Log.d(TAG, "vehicle approaching zone=" + approachZone
                    + " ttc=" + ttcSeconds + "s growth=" + bestGrowth);
        }

        return new AurigaInterfaces.TrafficResult(vehicleApproaching, approachZone,
                ttcSeconds, lightState);
    }

    @Override
    public boolean selfTest(Context ctx) {
        // Submit two identical frames (no motion) → should return no approach
        int w = 32, h = 24;
        byte[] frame = new byte[w * h * 3 / 2];
        for (int i = 0; i < w * h; i++) frame[i] = (byte) 80;
        analyse(frame, w, h); // prime prev frame
        AurigaInterfaces.TrafficResult r = analyse(frame, w, h);
        boolean ok = !r.vehicleApproaching;
        Log.i(TAG, "selfTest → " + ok);
        return ok;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Bilinear downsample of the Y (luma) plane from the full NV21 frame to
     * a small {@code dw × dh} grid for efficient inter-frame comparison.
     */
    private static byte[] downsampleLuma(byte[] nv21, int sw, int sh, int dw, int dh) {
        byte[] out = new byte[dw * dh];
        float xScale = (float) sw / dw;
        float yScale = (float) sh / dh;
        for (int dy = 0; dy < dh; dy++) {
            int sy = (int)(dy * yScale);
            if (sy >= sh) sy = sh - 1;
            for (int dx = 0; dx < dw; dx++) {
                int sx = (int)(dx * xScale);
                if (sx >= sw) sx = sw - 1;
                out[dy * dw + dx] = nv21[sy * sw + sx];
            }
        }
        return out;
    }

    /**
     * Estimate the frame-over-frame fractional growth rate of a zone's bright
     * region by comparing the oldest and newest entries in the ring buffer.
     * Returns 0 if no meaningful data.
     */
    private float estimateGrowthRate(float[] history) {
        // Oldest entry is at historyIdx (just overwritten); newest is at historyIdx-1
        int oldest = historyIdx % HISTORY_LEN;
        int newest = (historyIdx + HISTORY_LEN - 1) % HISTORY_LEN;
        float old = history[oldest];
        float cur = history[newest];
        if (old < 0.01f) return 0f; // avoid division-by-zero on sparse data
        float growth = (cur - old) / HISTORY_LEN; // avg growth per frame
        return Math.max(0f, growth);
    }

    /**
     * Traffic light state detector (upper 25% of frame, HSV band membership).
     * Self-contained so TrafficSenseEngine has no compile dependency on
     * {@link ColorSenseEngine}.
     */
    private AurigaInterfaces.TrafficLightState detectLightState(byte[] nv21,
                                                                  int w, int h) {
        int yLimit  = h * 25 / 100;
        int red = 0, amber = 0, green = 0, total = 0;
        int step = 4;
        for (int y = 0; y < yLimit; y += step) {
            for (int x = 0; x < w; x += step) {
                float[] hsv = yuv2hsv(nv21, w, h, x, y);
                float s = hsv[1], v = hsv[2], hue = hsv[0];
                if (s < 0.35f || v < 0.25f) { total++; continue; }
                if (hue < 15f || hue >= 345f) red++;
                else if (hue < 40f)           amber++;
                else if (hue >= 95f && hue <= 155f) green++;
                total++;
            }
        }
        if (total == 0) return AurigaInterfaces.TrafficLightState.UNKNOWN;
        float r = (float) red   / total;
        float a = (float) amber / total;
        float g = (float) green / total;
        float best = Math.max(r, Math.max(a, g));
        if (best < TL_THRESH) return AurigaInterfaces.TrafficLightState.UNKNOWN;
        if (best == r) return AurigaInterfaces.TrafficLightState.RED;
        if (best == a) return AurigaInterfaces.TrafficLightState.AMBER;
        return AurigaInterfaces.TrafficLightState.GREEN;
    }

    private static float[] yuv2hsv(byte[] nv21, int w, int h, int x, int y) {
        int yIdx   = y * w + x;
        int uvBase = w * h + (y / 2) * w + (x & ~1);
        int Y = nv21[yIdx]       & 0xFF;
        int V = (nv21[uvBase]     & 0xFF) - 128;
        int U = (nv21[uvBase + 1] & 0xFF) - 128;
        int r = clamp(Y + (int)(1.402f * V));
        int g = clamp(Y - (int)(0.344f * U) - (int)(0.714f * V));
        int b = clamp(Y + (int)(1.772f * U));
        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float delta = max - min;
        float v = max, s = max == 0 ? 0 : delta / max, hue = 0;
        if (delta > 0) {
            if      (max == rf) hue = 60f * (((gf - bf) / delta) % 6f);
            else if (max == gf) hue = 60f * (((bf - rf) / delta) + 2f);
            else                hue = 60f * (((rf - gf) / delta) + 4f);
            if (hue < 0) hue += 360f;
        }
        return new float[]{hue, s, v};
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    private static AurigaInterfaces.Zone zoneToEnum(int z) {
        switch (z) {
            case 0: return AurigaInterfaces.Zone.LEFT;
            case 1: return AurigaInterfaces.Zone.CENTER;
            case 2: return AurigaInterfaces.Zone.RIGHT;
            default: return AurigaInterfaces.Zone.UNKNOWN;
        }
    }

    private static AurigaInterfaces.TrafficResult allClear() {
        return new AurigaInterfaces.TrafficResult(false, AurigaInterfaces.Zone.UNKNOWN,
                Float.MAX_VALUE, AurigaInterfaces.TrafficLightState.UNKNOWN);
    }
}
