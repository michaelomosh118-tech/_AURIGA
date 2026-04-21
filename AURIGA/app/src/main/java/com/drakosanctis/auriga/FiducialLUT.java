package com.drakosanctis.auriga;

import android.content.Context;
import android.content.res.AssetManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * FiducialLUT: The "Ground Ruler" of the TruePath(TM) engine.
 * Stores the relationship between an object's distance (meters), its
 * apparent pixel width in the camera frame, and the pixel row at which
 * its base appears. Interpolates with Fritsch-Carlson monotone cubic
 * Hermite splines so the curve passes exactly through each calibration
 * anchor without overshoot, which keeps distance readings stable even
 * between sparse anchors.
 *
 * Two separate query paths are supported:
 *   - getDistanceFromWidth(pixelWidth): used during calibration when the
 *     fiducial is a known 20cm square and its observed width fixes the
 *     distance.
 *   - getDistanceFromRow(pixelRow) / getDistanceFromRowWithPitch(...):
 *     the runtime TruePath path. Row is a proxy for ground distance
 *     given a known camera height and pitch. The pitch-aware variant
 *     applies a horizon offset so handheld tilt does not bias readings.
 *
 * Calibration data can be loaded in two ways:
 *   1. The legacy in-class defaults (synthetic, calibrated against a
 *      640x480 reference frame) are used until something else is loaded.
 *   2. loadProfile(list) replaces the anchor set with a device-specific
 *      profile downloaded from the public calibration-profile mirror,
 *      or captured on-device by the user running the 10-point walk.
 */
public class FiducialLUT {

    public static class TrainingPoint {
        public final float distanceM;
        public final float pixelWidth;
        public final float pixelRow;

        public TrainingPoint(float d, float w, float r) {
            this.distanceM = d;
            this.pixelWidth = w;
            this.pixelRow = r;
        }
    }

    // Volatile reference so loadProfile() can atomically swap the anchor
    // set from the background calibration-fetch thread while the main
    // navigation loop reads the same field on every frame. ArrayList is
    // not thread-safe, so we never mutate the referenced list after it
    // is published: addPoint() and loadProfile() both build a fresh list
    // and publish it in a single volatile write. Readers capture the
    // reference locally (see interpolate()) so a concurrent swap can't
    // split a read across two different lists.
    private volatile List<TrainingPoint> points = new ArrayList<>();
    private volatile boolean usingTrainedProfile = false;
    // Human-readable tag describing where the currently-loaded profile
    // came from. Surfaced in the diagnostic overlay so a field
    // operator can tell at a glance whether the LUT is running off
    // synthetic defaults, the bundled SM-A057F asset, a network
    // contribution, or an on-device 10-point calibration. Values are
    // kept short so the overlay stays inside the mono-spaced column.
    //   "synthetic" : class-constructor defaults (640x480 reference)
    //   "asset"     : bundled APK asset (assets/default_profile_*.json)
    //   "network"   : downloaded from calibration-library mirror
    //   "on-device" : captured via the 10-point calibration walk
    private volatile String profileSource = "synthetic";

    public FiducialLUT() {
        // Synthetic defaults calibrated against a 640x480 reference frame.
        // These will be wildly wrong on any specific phone that has not
        // yet been calibrated or loaded a profile, which is why the main
        // loop surfaces a toast encouraging profile loading.
        // Format: Distance (m), Pixel Width (of 20cm), Pixel Row (Y)
        addPoint(0.5f, 800.0f, 460.0f);
        addPoint(1.0f, 400.0f, 400.0f);
        addPoint(1.5f, 266.0f, 360.0f);
        addPoint(2.0f, 200.0f, 330.0f);
        addPoint(2.5f, 160.0f, 310.0f);
        addPoint(3.0f, 133.0f, 295.0f);
        addPoint(3.5f, 114.0f, 285.0f);
        addPoint(4.0f, 100.0f, 275.0f);
        addPoint(4.5f, 89.0f, 268.0f);
        addPoint(5.0f, 80.0f, 262.0f);
    }

    public void addPoint(float distanceM, float pixelWidth, float pixelRow) {
        List<TrainingPoint> next = new ArrayList<>(points);
        next.add(new TrainingPoint(distanceM, pixelWidth, pixelRow));
        points = next;
    }

    /**
     * Replace the current anchor set with a downloaded or captured
     * profile. Points need not arrive sorted; each query path sorts
     * internally by the relevant axis. After this call returns,
     * isUsingTrainedProfile() becomes true so the HUD can surface the
     * "using device-specific calibration" state.
     */
    public void loadProfile(List<TrainingPoint> profile) {
        loadProfile(profile, "on-device");
    }

    /**
     * Overload that also records where the profile came from so the
     * diagnostic overlay can show e.g. {@code profile : asset (10 pts)}
     * instead of just "trained". Passing a null or empty source falls
     * back to "on-device" which matches the pre-overload behavior.
     */
    public void loadProfile(List<TrainingPoint> profile, String source) {
        if (profile == null) return;
        // Sanitize first: filter non-finite values, sort by distanceM,
        // and drop entries whose distance / pixelWidth / pixelRow would
        // duplicate the previous anchor on any query axis. Duplicate
        // axis values cause hermite() to divide by zero and poison the
        // smoother with NaN -- a permanently broken readout for a
        // navigation aid used by blind users.
        List<TrainingPoint> cleaned = new ArrayList<>(profile.size());
        List<TrainingPoint> sorted = new ArrayList<>(profile);
        Collections.sort(sorted, new Comparator<TrainingPoint>() {
            @Override public int compare(TrainingPoint a, TrainingPoint b) {
                return Float.compare(a.distanceM, b.distanceM);
            }
        });
        TrainingPoint prev = null;
        for (TrainingPoint p : sorted) {
            if (p == null) continue;
            if (Float.isNaN(p.distanceM) || Float.isInfinite(p.distanceM)
                    || Float.isNaN(p.pixelWidth) || Float.isInfinite(p.pixelWidth)
                    || Float.isNaN(p.pixelRow) || Float.isInfinite(p.pixelRow)) {
                continue;
            }
            if (prev != null) {
                // Query axes need strictly monotonic x. distance is
                // already strictly increasing after the sort+dedupe,
                // and width/row must be strictly decreasing for the
                // row-decreasing and width-decreasing axes.
                if (p.distanceM <= prev.distanceM) continue;
                if (p.pixelWidth >= prev.pixelWidth) continue;
                if (p.pixelRow >= prev.pixelRow) continue;
            }
            cleaned.add(p);
            prev = p;
        }
        if (cleaned.size() < 2) return;
        // Defensive copy + atomic publish: the main loop is reading
        // `points` concurrently. One volatile write gives readers
        // either the old list in full or the new list in full, never
        // a half-cleared view.
        points = cleaned;
        usingTrainedProfile = true;
        if (source != null && !source.trim().isEmpty()) {
            profileSource = source.trim();
        } else {
            profileSource = "on-device";
        }
    }

    public boolean isUsingTrainedProfile() {
        return usingTrainedProfile;
    }

    /**
     * Diagnostic label for which path populated the current anchor
     * set. Never null. See {@link #profileSource} for the full set
     * of values.
     */
    public String getProfileSource() {
        return profileSource;
    }

    public int getPointCount() {
        return points.size();
    }

    /**
     * Bundled-asset fallback profile loader. Boot order is:
     *   1. synthetic constructor defaults (640x480 reference);
     *   2. bundled asset {@code default_profile_<Build.MODEL>.json} if
     *      present (written pre-release once a device is calibrated
     *      in-house);
     *   3. remotely-fetched profile from the calibration-library
     *      mirror, which overwrites 2 when it arrives;
     *   4. on-device 10-point calibration by the user, which overwrites
     *      3 via {@link #loadProfile(List)}.
     *
     * Schema matches the network profile: a JSON array of objects with
     * a {@code calibration_points} array of {@code distanceM,
     * pixelWidth, pixelRow} entries. Multi-entry arrays are supported
     * so one asset can serve several related models (e.g., SM-A057F
     * and SM-A057M).
     *
     * Returns true iff a usable profile was parsed and loaded.
     */
    public boolean loadFromAssetJson(Context ctx, String assetPath,
                                     String manufacturer, String model) {
        if (ctx == null || assetPath == null) return false;
        AssetManager assets = ctx.getAssets();
        if (assets == null) return false;
        String body;
        try (InputStream in = assets.open(assetPath);
             BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            body = sb.toString();
        } catch (Throwable t) {
            // Asset not present is the common path on non-calibrated
            // devices; do not treat that as an error.
            return false;
        }
        List<TrainingPoint> matched = parseProfileJson(body, manufacturer, model);
        if (matched == null || matched.size() < 2) return false;
        loadProfile(matched, "asset");
        return true;
    }

    /**
     * Shared JSON parser used by both the bundled-asset loader above
     * and {@code MainActivity.fetchCalibrationProfileAsync}. The
     * network fetch has its own copy today (pre-refactor); this one
     * lives on the LUT so non-UI callers (tests, background services)
     * do not need to depend on the MainActivity.
     */
    public static List<TrainingPoint> parseProfileJson(
            String json, String manufacturer, String model) {
        try {
            JSONArray arr = new JSONArray(json);
            String wantedManu = manufacturer == null ? "" : manufacturer.trim();
            String wantedModel = model == null ? "" : model.trim();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject row = arr.getJSONObject(i);
                if (!row.optBoolean("approved", false)) continue;
                String rowManu = row.optString("manufacturer", "").trim();
                String rowModel = row.optString("model", "").trim();
                boolean manuMatches = rowManu.isEmpty()
                        || wantedManu.equalsIgnoreCase(rowManu);
                boolean modelMatches = wantedModel.equalsIgnoreCase(rowModel);
                if (!(manuMatches && modelMatches)) continue;
                JSONArray pts = row.optJSONArray("calibration_points");
                if (pts == null) continue;
                List<TrainingPoint> out = new ArrayList<>(pts.length());
                for (int j = 0; j < pts.length(); j++) {
                    JSONObject p = pts.getJSONObject(j);
                    out.add(new TrainingPoint(
                            (float) p.getDouble("distanceM"),
                            (float) p.getDouble("pixelWidth"),
                            (float) p.getDouble("pixelRow")));
                }
                return out;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    public void persist() {
        // Implementation for persisting the data (e.g., to SharedPreferences or a file).
    }

    // ---------------------------------------------------------------
    // Query paths
    // ---------------------------------------------------------------

    /**
     * getDistanceFromWidth: Maps an observed pixel width to a real-world
     * distance in meters. Pixel width is monotonically DECREASING with
     * distance; the interpolator handles that by negating the axis so
     * the Hermite spline still receives strictly increasing x.
     */
    public float getDistanceFromWidth(float pixelWidth) {
        if (points.size() < 2) return -1.0f;
        return interpolate(pixelWidth, Axis.WIDTH_DECREASING);
    }

    /**
     * getDistanceFromRow: Maps an observed pixel row (Y) to distance.
     * Row is DECREASING as distance increases (target further away =
     * higher in frame = smaller Y). Uses the same axis-negation trick
     * as getDistanceFromWidth.
     *
     * This path does NOT apply any pitch correction. Callers that know
     * the current device pitch should use getDistanceFromRowWithPitch.
     */
    public float getDistanceFromRow(float pixelRow) {
        if (points.size() < 2) return -1.0f;
        return interpolate(pixelRow, Axis.ROW_DECREASING);
    }

    /**
     * getDistanceFromRowWithPitch: Same as getDistanceFromRow but shifts
     * the query row by focalPx * tan(pitch) so the LUT is consulted at
     * the row that WOULD have been observed had the device been held
     * level. Positive pitchRad = device tilted nose-down (which moves
     * the horizon row visually downward, so targets appear at LARGER
     * row values than they would at level). We subtract the shift so
     * the corrected row maps back onto the level-held calibration data.
     *
     * focalPx must match the current preview resolution, not the
     * calibration reference resolution; HardwareHAL.getFocalLengthPx()
     * recomputes per frame.
     */
    public float getDistanceFromRowWithPitch(
            float pixelRow, float pitchRad, float focalPx) {
        if (points.size() < 2) return -1.0f;
        float shift = focalPx * (float) Math.tan(pitchRad);
        return interpolate(pixelRow - shift, Axis.ROW_DECREASING);
    }

    /**
     * getPixelWidthAtDistance: Returns how many pixels wide a 20cm
     * object would be at the given distance. Used by the classifier to
     * derive real object width from an observed width, and by the
     * width-vs-row sanity check in TriangulationEngine to reject bad
     * frames.
     */
    public float getPixelWidthAtDistance(float distanceM) {
        if (points.size() < 2) return -1.0f;
        return interpolate(distanceM, Axis.DISTANCE_TO_WIDTH);
    }

    // ---------------------------------------------------------------
    // Interpolation core (Fritsch-Carlson monotone cubic Hermite)
    // ---------------------------------------------------------------

    private enum Axis {
        WIDTH_DECREASING,    // query = pixelWidth,  output = distanceM
        ROW_DECREASING,      // query = pixelRow,    output = distanceM
        DISTANCE_TO_WIDTH    // query = distanceM,   output = pixelWidth
    }

    private float interpolate(float query, Axis axis) {
        // Build two float arrays (xs, ys) where xs is strictly
        // increasing. For DECREASING axes (width, row), we store -axisValue
        // as x so the spline still operates on increasing x; query is
        // likewise negated below.
        // Snapshot the volatile reference once up front so a concurrent
        // loadProfile() on the fetch thread can't swap the list out
        // mid-loop.
        List<TrainingPoint> snapshot = points;
        int n = snapshot.size();
        float[] xs = new float[n];
        float[] ys = new float[n];
        for (int i = 0; i < n; i++) {
            TrainingPoint p = snapshot.get(i);
            switch (axis) {
                case WIDTH_DECREASING:
                    xs[i] = -p.pixelWidth;
                    ys[i] = p.distanceM;
                    break;
                case ROW_DECREASING:
                    xs[i] = -p.pixelRow;
                    ys[i] = p.distanceM;
                    break;
                case DISTANCE_TO_WIDTH:
                    xs[i] = p.distanceM;
                    ys[i] = p.pixelWidth;
                    break;
            }
        }
        sortByX(xs, ys);
        float qx;
        switch (axis) {
            case WIDTH_DECREASING:
            case ROW_DECREASING:
                qx = -query;
                break;
            default:
                qx = query;
        }
        return hermite(xs, ys, qx);
    }

    /**
     * Fritsch-Carlson monotone cubic Hermite interpolation.
     * Guarantees the returned value lies between the bracketing y
     * anchors, so overshoot (which the previous linear interp at least
     * avoided but which naive cubics introduce) cannot happen. For a
     * query outside [xs[0], xs[n-1]], linearly extrapolates from the
     * nearest segment's slope so the engine still returns SOMETHING
     * reasonable rather than -1 at the range edges.
     */
    private static float hermite(float[] xs, float[] ys, float qx) {
        int n = xs.length;
        // Belt-and-suspenders: loadProfile() already dedupes so adjacent
        // xs are strictly increasing, but if a caller bypasses that path
        // (e.g. addPoint() with a duplicate) we still guard every
        // division so NaN can never enter the smoother.
        if (qx <= xs[0]) {
            float dx = xs[1] - xs[0];
            if (dx == 0f) return ys[0];
            return ys[0] + ((ys[1] - ys[0]) / dx) * (qx - xs[0]);
        }
        if (qx >= xs[n - 1]) {
            float dx = xs[n - 1] - xs[n - 2];
            if (dx == 0f) return ys[n - 1];
            return ys[n - 1] + ((ys[n - 1] - ys[n - 2]) / dx) * (qx - xs[n - 1]);
        }

        // Secant slopes; flat segments for duplicate xs so tangent
        // averaging below does not produce NaN.
        float[] d = new float[n - 1];
        for (int i = 0; i < n - 1; i++) {
            float dx = xs[i + 1] - xs[i];
            d[i] = (dx == 0f) ? 0f : (ys[i + 1] - ys[i]) / dx;
        }

        // Initial tangents: averaged secants, with endpoints = single secant
        float[] m = new float[n];
        m[0] = d[0];
        m[n - 1] = d[n - 2];
        for (int i = 1; i < n - 1; i++) {
            m[i] = (d[i - 1] + d[i]) * 0.5f;
        }

        // Enforce monotonicity per Fritsch-Carlson
        for (int i = 0; i < n - 1; i++) {
            if (d[i] == 0f) {
                m[i] = 0f;
                m[i + 1] = 0f;
                continue;
            }
            float a = m[i] / d[i];
            float b = m[i + 1] / d[i];
            float sumSq = a * a + b * b;
            if (sumSq > 9f) {
                float tau = 3f / (float) Math.sqrt(sumSq);
                m[i] = tau * a * d[i];
                m[i + 1] = tau * b * d[i];
            }
        }

        // Find bracketing segment
        int k = 0;
        for (int i = 0; i < n - 1; i++) {
            if (qx >= xs[i] && qx <= xs[i + 1]) {
                k = i;
                break;
            }
        }

        float h = xs[k + 1] - xs[k];
        if (h == 0f) return ys[k];
        float t = (qx - xs[k]) / h;
        float t2 = t * t;
        float t3 = t2 * t;
        float h00 = 2f * t3 - 3f * t2 + 1f;
        float h10 = t3 - 2f * t2 + t;
        float h01 = -2f * t3 + 3f * t2;
        float h11 = t3 - t2;
        return h00 * ys[k]
             + h10 * h * m[k]
             + h01 * ys[k + 1]
             + h11 * h * m[k + 1];
    }

    private static void sortByX(float[] xs, float[] ys) {
        int n = xs.length;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        final float[] xsRef = xs;
        java.util.Arrays.sort(idx, new Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) {
                return Float.compare(xsRef[a], xsRef[b]);
            }
        });
        float[] xOut = new float[n];
        float[] yOut = new float[n];
        for (int i = 0; i < n; i++) {
            xOut[i] = xs[idx[i]];
            yOut[i] = ys[idx[i]];
        }
        System.arraycopy(xOut, 0, xs, 0, n);
        System.arraycopy(yOut, 0, ys, 0, n);
    }
}
