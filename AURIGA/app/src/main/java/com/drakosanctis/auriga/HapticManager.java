package com.drakosanctis.auriga;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * HapticManager: The "Physical Touch" of the Auriga Ecosystem.
 * Provides vibration patterns for different obstacle types.
 *
 * Pulse gating: the navigation loop ticks at 30 FPS, so a naive
 * {@code vibrator.vibrate(100ms)} on every frame produces a continuous
 * buzz that field operators described as "disruptive" (they could not
 * feel individual pulses, only one long vibration). We gate every
 * pulse on three conditions:
 *   1. The distance is in a meaningful range [MIN_PULSE_DIST_M,
 *      MAX_PULSE_DIST_M]; sentinel 0.0 m (no detection) and far-away
 *      readings above ~3 m do not trigger haptics.
 *   2. At least MIN_PULSE_INTERVAL_MS have elapsed since the last
 *      pulse. Stops the 30 FPS spam.
 *   3. The distance has changed by at least MIN_DELTA_M since the last
 *      pulse. Prevents the same reading from re-firing while the user
 *      stands still. Genuine movement (walking, target moving) still
 *      triggers normal pulses because 0.15 m is well below one stride.
 */
public class HapticManager {

    private final Vibrator vibrator;

    // --- Pulse gating constants ---
    // Minimum real-world distance that triggers a pulse. Anything
    // below this is likely a sensor artefact (baseY=0, obstacle
    // occluding the camera) and the user would not benefit from a
    // vibration for a reading that physically cannot happen.
    private static final float MIN_PULSE_DIST_M = 0.30f;
    // Beyond this range the obstacle is not navigationally relevant;
    // pulsing at 5 m away creates noise without value.
    private static final float MAX_PULSE_DIST_M = 3.00f;
    // Minimum time between pulses. 350 ms = about every 10 frames at
    // 30 FPS, which field testing shows is the tightest cadence that
    // still feels like discrete pulses rather than a buzz.
    private static final long  MIN_PULSE_INTERVAL_MS = 350L;
    // Minimum change in distance since the last pulse. Below this we
    // assume the reading is steady-state and do not re-fire. Chosen
    // to be below one normal walking stride so walking still produces
    // continuous feedback.
    private static final float MIN_DELTA_M = 0.15f;
    // Shorter default duration so a genuine pulse feels crisp and the
    // vibrator motor does not overlap the next pulse when the render
    // loop produces two detections in quick succession.
    private static final long  PULSE_BASE_MS = 40L;
    private static final long  PULSE_MAX_MS  = 90L;

    // Last pulse state. volatile because pulse() is called from the
    // render-loop thread but the debounce check must see consistent
    // values across threads if shutdown() ever races against a pending
    // frame.
    private volatile long  lastPulseAtMs = 0L;
    private volatile float lastPulseDistM = Float.NaN;

    public HapticManager(Context context) {
        this.vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }

    /**
     * Emits a short vibration pulse only if all three gating
     * conditions in the class docstring are satisfied. Otherwise a
     * silent no-op. Safe to call on every frame; the debounce logic
     * lives here rather than in MainActivity so any future caller
     * (e.g. Target Locator pulses in Phase 1) gets the same behavior
     * for free.
     */
    public void pulse(float distanceM) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (Float.isNaN(distanceM) || Float.isInfinite(distanceM)) return;
        if (distanceM < MIN_PULSE_DIST_M || distanceM > MAX_PULSE_DIST_M) return;

        long nowMs = SystemClock.uptimeMillis();
        long sinceLast = nowMs - lastPulseAtMs;
        if (sinceLast < MIN_PULSE_INTERVAL_MS) return;

        // After a long idle (>2 s) allow the first pulse even if the
        // distance has not changed -- feels more responsive when the
        // user re-enters the scan zone.
        boolean firstInSession = Float.isNaN(lastPulseDistM) || sinceLast > 2000L;
        if (!firstInSession
                && Math.abs(distanceM - lastPulseDistM) < MIN_DELTA_M) {
            return;
        }

        // Pulse duration is a smooth function of distance: near
        // obstacles pulse slightly longer so they feel "heavier".
        // Clamped so we never issue a pulse long enough to overlap
        // the next scheduled one.
        long duration = (long) Math.min(PULSE_MAX_MS,
                PULSE_BASE_MS + Math.max(0f, (1.5f - distanceM)) * 20f);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(
                    duration, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(duration);
        }

        lastPulseAtMs = nowMs;
        lastPulseDistM = distanceM;
    }

    /**
     * alert: Double pulse for critical hazards (SkyShield(TM)).
     * Not rate-limited because overhang hazards are intrinsically
     * rare and each one genuinely needs attention.
     */
    public void alert() {
        if (vibrator == null || !vibrator.hasVibrator()) return;

        long[] pattern = {0, 150, 50, 150};
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
        } else {
            vibrator.vibrate(pattern, -1);
        }
    }

    public void stop() {
        if (vibrator != null) vibrator.cancel();
    }
}
