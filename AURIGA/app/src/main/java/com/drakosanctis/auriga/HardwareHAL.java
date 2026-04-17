package com.drakosanctis.auriga;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.util.SizeF;

/**
 * HardwareHAL: Hardware Abstraction Layer for DrakoSanctis.
 * Normalizes camera characteristics (FOV, Focal Length) so the
 * TruePath™ engine works identically on any Android device.
 */
public class HardwareHAL {

    private final Context context;
    private float hfovDegrees = 68.0f; // Default baseline (e.g., Galaxy A05s)
    private float focalLengthPx = -1.0f;
    private int frameWidth = 640;      // Baseline preview resolution
    private int frameHeight = 480;

    public HardwareHAL(Context context) {
        this.context = context;
        detectCameraCharacteristics();
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
