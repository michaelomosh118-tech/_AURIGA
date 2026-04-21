package com.drakosanctis.auriga;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * ColorSquareDetector: The "Eye" of the training system.
 * Uses HSV color range detection to find the 20cm training square.
 * This ensures high-performance detection on any phone without ML.
 */
public class ColorSquareDetector {

    private final float targetHue;       // E.g., 0 for Red, 120 for Green
    private final float hueTolerance;     // E.g., 15 degrees
    private final float minSaturation;    // E.g., 0.5f (50%)
    private final float minValue;         // E.g., 0.5f (50%)

    public ColorSquareDetector(float hue, float tolerance) {
        this.targetHue = hue;
        this.hueTolerance = tolerance;
        this.minSaturation = 0.5f;
        this.minValue = 0.5f;
    }

    /**
     * DetectionResult: Holds the findings of a scan.
     *
     * baseY is the bottom edge of the matching bounding box (i.e. the
     * ground-contact row for a target resting on the floor). The
     * TriangulationEngine's row-based LUT lookup is calibrated against
     * baseY rows, so callers must pass this value rather than centerY
     * for the TruePath ground-distance path.
     */
    public static class DetectionResult {
        public float pixelWidth;
        public float centerX;
        public float centerY;
        public float baseY;
        public float confidence; // 0.0 to 1.0

        DetectionResult(float w, float x, float y, float baseY, float c) {
            this.pixelWidth = w;
            this.centerX = x;
            this.centerY = y;
            this.baseY = baseY;
            this.confidence = c;
        }
    }

    /**
     * detect: Scans a camera frame for the training square.
     * Uses a fast column-row sampling logic to find the bounding box.
     */
    public DetectionResult detect(Bitmap frame) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        
        int minX = width, maxX = 0, minY = height, maxY = 0;
        int matchCount = 0;
        int sampleStep = 4; // Skip pixels for speed on low-end chips

        float[] hsv = new float[3];

        for (int x = 0; x < width; x += sampleStep) {
            for (int y = 0; y < height; y += sampleStep) {
                int pixel = frame.getPixel(x, y);
                Color.colorToHSV(pixel, hsv);

                if (isTargetColor(hsv)) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                    matchCount++;
                }
            }
        }

        if (matchCount < 50) { // Too few pixels to be the 20cm square
            return new DetectionResult(0, 0, 0, 0, 0);
        }

        float detectedWidth = maxX - minX;
        float detectedHeight = maxY - minY;
        
        // Check if the shape is roughly a square (aspect ratio ~1.0)
        float aspectRatio = detectedWidth / detectedHeight;
        float confidence = (aspectRatio > 0.7f && aspectRatio < 1.3f) ? 1.0f : 0.5f;

        return new DetectionResult(
                detectedWidth,
                (minX + maxX) / 2.0f,
                (minY + maxY) / 2.0f,
                maxY,
                confidence);
    }

    private boolean isTargetColor(float[] hsv) {
        float hueDiff = Math.abs(hsv[0] - targetHue);
        if (hueDiff > 180) hueDiff = 360 - hueDiff;

        return hueDiff <= hueTolerance && hsv[1] >= minSaturation && hsv[2] >= minValue;
    }
}
