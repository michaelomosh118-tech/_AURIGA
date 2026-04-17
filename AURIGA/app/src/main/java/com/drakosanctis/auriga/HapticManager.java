package com.drakosanctis.auriga;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * HapticManager: The "Physical Touch" of the Auriga Ecosystem.
 * Provides vibration patterns for different obstacle types.
 * Helps visually impaired users "feel" the environment.
 */
public class HapticManager {

    private final Vibrator vibrator;

    public HapticManager(Context context) {
        this.vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }

    /**
     * pulse: Single vibration pulse for navigation.
     * Duration = min(100, dist * 25ms)
     */
    public void pulse(float distanceM) {
        if (vibrator == null || !vibrator.hasVibrator()) return;

        long duration = (long) Math.max(100, distanceM * 25);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(duration);
        }
    }

    /**
     * alert: Double pulse for critical hazards (SkyShield™).
     */
    public void alert() {
        if (vibrator == null || !vibrator.hasVibrator()) return;

        long[] pattern = {0, 150, 50, 150}; // pulse, wait, pulse
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
