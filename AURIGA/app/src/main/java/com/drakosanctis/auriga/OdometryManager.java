package com.drakosanctis.auriga;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * OdometryManager: The "GhostAnchor™" of the engine.
 * Tracks feature points between frames to stabilize distance
 * and bearing against hand tremors and camera movement.
 */
public class OdometryManager {

    private static final float ALPHA = 0.2f; // Low-pass filter weight
    private float[] smoothedDistances = {0.0f, 0.0f, 0.0f}; // 3-column smoothing
    private float lastAnchorX = -1.0f;
    private float lastAnchorY = -1.0f;

    public OdometryManager() { }

    /**
     * smoothDistance: Applies a low-pass filter to raw distance readings for a specific column.
     * Prevents flickering beeps on low-end chips like Helio G85.
     */
    public float smoothDistance(int column, float newDistance) {
        if (column < 0 || column > 2) return newDistance;
        
        if (smoothedDistances[column] == 0.0f) {
            smoothedDistances[column] = newDistance;
        } else {
            smoothedDistances[column] = (newDistance * ALPHA) + (smoothedDistances[column] * (1.0f - ALPHA));
        }
        return smoothedDistances[column];
    }

    /**
     * calculateVisualShift: GhostAnchor™ logic.
     * Fix 9: Uses a "Center of Brightness" (Centroid) instead of single brightest pixel.
     * This prevents locking onto a single lamp and makes SLAM more robust.
     */
    public float[] calculateVisualShift(Bitmap frame) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        int centerX = width / 2;
        int centerY = height / 2;
        int searchRadius = 30;

        float sumX = 0, sumY = 0, sumWeight = 0;

        // Calculate weighted centroid of brightness in center crop
        for (int x = centerX - searchRadius; x < centerX + searchRadius; x++) {
            for (int y = centerY - searchRadius; y < centerY + searchRadius; y++) {
                int pixel = frame.getPixel(x, y);
                int brightness = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3;

                // Only consider points significantly brighter than average
                if (brightness > 100) {
                    float weight = (float) Math.pow(brightness / 255.0, 2);
                    sumX += x * weight;
                    sumY += y * weight;
                    sumWeight += weight;
                }
            }
        }

        float bestX = (sumWeight > 0) ? sumX / sumWeight : centerX;
        float bestY = (sumWeight > 0) ? sumY / sumWeight : centerY;

        float deltaX = 0, deltaY = 0;
        if (lastAnchorX != -1.0f) {
            deltaX = bestX - lastAnchorX;
            deltaY = bestY - lastAnchorY;
        }

        lastAnchorX = bestX;
        lastAnchorY = bestY;

        return new float[]{ deltaX, deltaY };
    }

    public void reset() {
        for (int i = 0; i < 3; i++) smoothedDistances[i] = 0.0f;
        lastAnchorX = -1.0f;
        lastAnchorY = -1.0f;
    }
}
