package com.drakosanctis.auriga;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.util.SizeF;

/**
 * HardwareHAL: Hardware Abstraction Layer for DrakoSanctis.
 * Normalizes camera characteristics (FOV, Focal Length) so the
 * TruePath(TM) engine works identically on any Android device.
 *
 * Additionally exposes a device PITCH reading (radians) derived from
 * the gravity-dominated accelerometer. Positive pitch = device tilted
 * nose-down (typical for a user scanning the ground in front of them).
 * The row-to-distance LUT lookup uses this to shift the horizon row,
 * removing the single largest source of ground-plane distance bias.
 */
public class HardwareHAL implements SensorEventListener {

    private final Context context;
    private float hfovDegrees = 68.0f; // Default baseline (e.g., Galaxy A05s)
    private float focalLengthPx = -1.0f;
    private int frameWidth = 640;      // Baseline preview resolution
    private int frameHeight = 480;

    // Accelerometer-derived pitch, low-pass smoothed so the horizon shift
    // in FiducialLUT does not chase per-frame gravity jitter (which is
    // ~0.3 m/s^2 on handheld devices and would otherwise add ~1-2 deg
    // of bearing noise).
    private SensorManager sensorManager;
    private Sensor accelerometer;
    // volatile so the render-loop thread's getPitchRadians() sees writes
    // from the sensor callback thread without a data race. Without this,
    // the JIT on the render thread can cache pitchInitialized=false and
    // silently disable pitch correction entirely.
    private volatile float smoothedPitchRad = 0.0f;
    private volatile boolean pitchInitialized = false;
    private static final float PITCH_ALPHA = 0.15f;

    public HardwareHAL(Context context) {
        this.context = context;
        detectCameraCharacteristics();
        startPitchSensor();
    }

    /**
     * detectCameraCharacteristics: Queries the Camera2 API for actual HFOV.
     * Prevents the "hardcoded FOV" error found in previous versions.
     */
    private void detectCameraCharacteristics() {
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            String backCameraId = getBackCameraId(manager);
            if (backCameraId != null) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(backCameraId);

                // Get physical sensor size and focal length
                SizeF sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                float[] focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);

                if (sensorSize != null && focalLengths != null && focalLengths.length > 0) {
                    float focalLength = focalLengths[0];
                    // HFOV = 2 * atan(sensorWidth / (2 * focalLength))
                    hfovDegrees = (float) Math.toDegrees(2 * Math.atan(sensorSize.getWidth() / (2 * focalLength)));
                }
            }
        } catch (CameraAccessException | SecurityException e) {
            // Fallback to default baseline
        }
    }

    /**
     * Register for accelerometer updates so getPitchRadians() returns
     * a live value. Any failure here leaves smoothedPitchRad at 0 and
     * pitchInitialized at false, which FiducialLUT treats as "no pitch
     * correction" -- the engine silently degrades to the old behavior
     * instead of crashing on devices without an accelerometer (there
     * are none in practice, but defensive).
     */
    private void startPitchSensor() {
        try {
            sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager == null) return;
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accelerometer == null) return;
            sensorManager.registerListener(
                    this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        } catch (Throwable ignored) {
            // Graceful degradation: pitch stays 0, row lookup falls back
            // to the level-hold assumption.
        }
    }

    public void stopPitchSensor() {
        try {
            if (sensorManager != null) sensorManager.unregisterListener(this);
        } catch (Throwable ignored) { }
    }

    /**
     * SensorEventListener callback. Accelerometer delivers m/s^2 along
     * device axes. Under gravity only (device mostly still), the vector
     * magnitude is ~9.81 and its direction tells us orientation:
     *   pitch = atan2(-y, sqrt(x^2 + z^2))
     *
     * We low-pass smooth so brief motion transients (walking, hand
     * tremor) do not translate directly into horizon-row jitter.
     */
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;
        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];
        // Baseline posture is the phone held UPRIGHT with the camera
        // pointing forward (the posture calibration was captured in).
        // In that posture Android accelerometer reads (x~=0, y~=+9.81,
        // z~=0) because the reaction force points up along device-y.
        // Tilting the top of the phone forward ("nose-down", camera
        // now looking at the ground) rotates +y toward +z. atan2(z, y)
        // therefore returns 0 at baseline and increases positively as
        // the user tilts downward, matching the sign convention
        // FiducialLUT.getDistanceFromRowWithPitch expects.
        float rawPitch = (float) Math.atan2(z, y);
        if (!pitchInitialized) {
            smoothedPitchRad = rawPitch;
            pitchInitialized = true;
        } else {
            smoothedPitchRad =
                    PITCH_ALPHA * rawPitch + (1f - PITCH_ALPHA) * smoothedPitchRad;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    /**
     * calculateFocalLengthPx: Crucial for bearing and triangulation.
     * f_px = (width / 2) / tan(HFOV / 2)
     */
    public float getFocalLengthPx(int currentFrameWidth) {
        this.frameWidth = currentFrameWidth;
        float hfovRad = (float) Math.toRadians(hfovDegrees);
        focalLengthPx = (float) ((currentFrameWidth / 2.0) / Math.tan(hfovRad / 2.0));
        return focalLengthPx;
    }

    public float getHFOV() {
        return hfovDegrees;
    }

    public float getScalingFactor() {
        // Normalizes any phone against a 60-degree baseline
        return hfovDegrees / 60.0f;
    }

    /**
     * Current smoothed pitch in radians. 0 = device held perfectly
     * level (screen facing up or forward); positive = nose-down.
     * Returns 0 until the first accelerometer sample arrives, which
     * is the safe default (pitch-aware lookup degrades to classic
     * row-lookup behavior).
     */
    public float getPitchRadians() {
        return pitchInitialized ? smoothedPitchRad : 0.0f;
    }

    /**
     * Reports whether the accelerometer was successfully registered
     * at construction. Used by the diagnostic overlay to distinguish
     * "pitch is genuinely 0 because the phone is level" from "pitch
     * is reading 0 because the sensor failed to register and
     * horizon-shift correction is silently disabled".
     */
    public boolean hasAccelerometer() {
        return accelerometer != null;
    }

    /**
     * Reports whether at least one accelerometer sample has been
     * received. False here + hasAccelerometer()=true is the "sensor
     * registered but hasn't delivered a sample yet" transient state
     * visible on the overlay for <=200 ms after onCreate.
     */
    public boolean isPitchInitialized() {
        return pitchInitialized;
    }

    private String getBackCameraId(CameraManager manager) throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }
        return null;
    }
}
