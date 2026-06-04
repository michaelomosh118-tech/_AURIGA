package com.drakosanctis.auriga;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;

/**
 * SerpentineGestureDetector — detects the Auriga activation gesture.
 *
 * Pattern (mirrors the web PWA version):
 *   1. Touch begins in the LEFT EDGE zone (left 30% of screen width).
 *   2. Finger moves DOWN at least {@link #MIN_CURVE_DP} dp.
 *   3. Finger reverses and moves UP at least {@link #MIN_CURVE_DP} dp.
 *   4. Lift off anywhere in the CENTRE zone (middle 40% of screen width).
 *
 * The gesture must complete within {@link #MAX_GESTURE_MS} ms.
 * All touch events are passed through — the detector never consumes them,
 * so taps/clicks on underlying views are not blocked.
 *
 * Usage:
 *   SerpentineGestureDetector sgd = new SerpentineGestureDetector(ctx, () ->
 *       voiceEngine.startListening());
 *   sgd.attach(rootView);   // overlays an OnTouchListener on the root
 */
public class SerpentineGestureDetector {

    /** Minimum vertical excursion required in each leg of the S-curve (dp). */
    private static final float MIN_CURVE_DP   = 60f;
    /** Maximum wall-clock time allowed for the whole gesture (ms). */
    private static final long  MAX_GESTURE_MS = 2500L;

    public interface Callback {
        void onSerpentineDetected();
    }

    private final Callback callback;
    private final float    density;
    private final int      screenWidth;

    // Per-gesture state
    private boolean tracking      = false;
    private float   startX;
    private float   startY;
    private float   maxY;
    private float   minYAfterMax;
    private boolean wentDown      = false;
    private boolean wentBackUp    = false;
    private long    gestureStart  = 0L;

    public SerpentineGestureDetector(Context context, Callback callback) {
        this.callback   = callback;
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        this.density    = dm.density;
        this.screenWidth = dm.widthPixels;
    }

    /**
     * Attaches the detector to {@code view} by wrapping (not replacing) its
     * existing OnTouchListener. Returns the newly set listener so callers
     * can chain further touches if needed.
     */
    public View.OnTouchListener attach(View view) {
        final View.OnTouchListener prev = null;
        View.OnTouchListener l = (v, event) -> {
            onTouch(event);
            return false;
        };
        view.setOnTouchListener(l);
        return l;
    }

    /** Feed raw MotionEvents here if attaching to the view is not possible. */
    public void onTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                onDown(event);
                break;
            case MotionEvent.ACTION_MOVE:
                onMove(event);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                onUp(event);
                break;
        }
    }

    // ── Internal handlers ─────────────────────────────────────────

    private void onDown(MotionEvent e) {
        if (e.getX() > screenWidth * 0.30f) {
            tracking = false;
            return;
        }
        tracking     = true;
        startX       = e.getX();
        startY       = e.getY();
        maxY         = startY;
        minYAfterMax = startY;
        wentDown     = false;
        wentBackUp   = false;
        gestureStart = e.getEventTime();
    }

    private void onMove(MotionEvent e) {
        if (!tracking) return;
        if (e.getEventTime() - gestureStart > MAX_GESTURE_MS) {
            tracking = false;
            return;
        }
        float y        = e.getY();
        float minCurve = MIN_CURVE_DP * density;

        if (y > maxY) maxY = y;

        if (!wentDown && (maxY - startY) >= minCurve) {
            wentDown = true;
        }
        if (wentDown) {
            if (y < minYAfterMax) minYAfterMax = y;
            if (!wentBackUp && (maxY - minYAfterMax) >= minCurve) {
                wentBackUp = true;
            }
        }
    }

    private void onUp(MotionEvent e) {
        if (!tracking) return;
        tracking = false;

        float x          = e.getX();
        boolean inCentre = x >= screenWidth * 0.30f && x <= screenWidth * 0.70f;

        if (wentDown && wentBackUp && inCentre) {
            if (callback != null) callback.onSerpentineDetected();
        }
    }
}
