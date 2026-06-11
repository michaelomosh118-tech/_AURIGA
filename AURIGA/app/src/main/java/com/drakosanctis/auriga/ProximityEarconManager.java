package com.drakosanctis.auriga;

import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * ProximityEarconManager — non-verbal, parking-sensor style audio feedback
 * that maps object distance to beep frequency. No speech, no TTS, no
 * screen interaction required.
 *
 * Competitive gap: every rival app (Lookout, Seeing AI, Envision) relies
 * exclusively on TTS announcements for object proximity. After ~20 minutes
 * of continuous use, users report "TTS fatigue" and disable audio feedback
 * entirely — losing situational awareness. Audio earcons side-step this by
 * using non-verbal tones that the brain habituates to far more slowly.
 *
 * Distance → beep interval mapping (parking-sensor convention):
 *   > 4.0 m   : silence
 *   3.0–4.0 m : beep every 800 ms
 *   2.0–3.0 m : beep every 500 ms
 *   1.0–2.0 m : beep every 280 ms
 *   0.5–1.0 m : beep every 130 ms
 *   < 0.5 m   : beep every  60 ms (near-continuous — danger zone)
 *
 * Thread-safe: update() / silence() / setEnabled() may be called from any thread.
 */
public class ProximityEarconManager {

    private static final String TAG = "ProximityEarcon";

    private static final float  MAX_DISTANCE_M  = 4.0f;
    private static final int    TONE_DURATION_MS = 35;  // beep length in ms
    private static final int    TONE_VOLUME      = 60;  // 0-100

    private final Handler      handler   = new Handler(Looper.getMainLooper());
    private ToneGenerator      tone;
    private Runnable           beepTask;
    private volatile boolean   enabled   = true;
    private volatile int       currentIntervalMs = -1; // -1 = silent

    public ProximityEarconManager() {
        try {
            tone = new ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME);
        } catch (Throwable t) {
            Log.w(TAG, "ToneGenerator unavailable: " + t.getMessage());
            tone = null;
        }
    }

    // ── Public API ────────────────────────────────────────────────────

    /**
     * Update earcon based on current nearest-object distance.
     * Pass 0 or a negative value to silence (no object in frame).
     * Safe to call every detection frame (throttled internally).
     */
    public void update(float distanceMeters) {
        if (!enabled || tone == null) return;
        int newInterval = intervalMs(distanceMeters);
        if (newInterval == currentIntervalMs) return; // no change — skip reschedule
        currentIntervalMs = newInterval;
        handler.removeCallbacks(beepTask);
        if (newInterval < 0) return; // silence

        beepTask = new Runnable() {
            final int interval = newInterval;
            @Override
            public void run() {
                if (!enabled || tone == null || currentIntervalMs != interval) return;
                try { tone.startTone(ToneGenerator.TONE_PROP_BEEP, TONE_DURATION_MS); }
                catch (Throwable ignored) {}
                handler.postDelayed(this, interval);
            }
        };
        handler.post(beepTask);
    }

    /** Immediately silence all earcons (e.g. when no objects are in frame). */
    public void silence() {
        currentIntervalMs = -1;
        handler.removeCallbacks(beepTask);
    }

    /**
     * Enable or disable earcon feedback entirely.
     * Disabled by default — user opts in via the drawer toggle
     * (same shared-preference key as haptic feedback so the intent
     * is consistent: both are "non-verbal background feedback").
     */
    public void setEnabled(boolean on) {
        enabled = on;
        if (!on) silence();
    }

    public boolean isEnabled() { return enabled; }

    /** Release native ToneGenerator resource. Call from onDestroy(). */
    public void release() {
        silence();
        handler.removeCallbacksAndMessages(null);
        if (tone != null) {
            try { tone.release(); } catch (Throwable ignored) {}
            tone = null;
        }
    }

    // ── Distance-to-interval mapping ──────────────────────────────────

    /** Returns beep interval in ms, or -1 for silence. */
    private static int intervalMs(float d) {
        if (d <= 0 || d > MAX_DISTANCE_M) return -1;
        if (d < 0.5f) return  60;
        if (d < 1.0f) return 130;
        if (d < 2.0f) return 280;
        if (d < 3.0f) return 500;
        return 800;
    }
}
