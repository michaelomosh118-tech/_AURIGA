package com.drakosanctis.auriga;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * ImageProcessor: The "Nerve Center" of the spatial scan.
 * Performs a 3-column scan (Left, Center, Right) for ground
 * and suspended obstacles.
 */
public class ImageProcessor {

    private final int width = 640;
    private final int height = 480;

    public ImageProcessor() { }

    /**
     * ObstacleData: Holds findings for a single column scan.
     *
     * centerX is the horizontal pixel coordinate this column scanned at;
     * callers need this to derive the correct bearing angle instead of
     * assuming every obstacle sits on the frame's vertical midline.
     *
     * confidence is 0 when no obstacle was detected in this column, and
     * a float in (0, 1] otherwise; the main loop uses it to gate the
     * distance smoother so a single low-contrast frame does not poison
     * the low-pass filter.
     */
    public static class ObstacleData {
        public int baseY;        // Where the object meets the floor
        public int hangY;        // Bottom edge of a suspended obstacle
        public float baseWidthPx; // Apparent width of the object's base
        public float centerX;     // Horizontal pixel column this scan used
        public float confidence;  // 0.0 (no detection) .. 1.0 (high contrast)
        public boolean isLowLight;

        ObstacleData() {
            this.baseY = -1;
            this.hangY = -1;
            this.baseWidthPx = 0;
            this.centerX = 0;
            this.confidence = 0.0f;
            this.isLowLight = false;
        }
    }

    /**
     * scanFrame: Performs the 3-column spatial scan.
     * Left (25%), Center (50%), Right (75%)
     */
    public ObstacleData[] scanFrame(Bitmap frame) {
        ObstacleData[] results = new ObstacleData[3];
        
        // --- STEP 1: Lighting Check (5x5 center grid) ---
        boolean isLowLight = isLowLight(frame);

        // --- STEP 2: Column Scans ---
        int[] cols = { (int)(width * 0.25), (int)(width * 0.50), (int)(width * 0.75) };

        for (int i = 0; i < 3; i++) {
            results[i] = scanColumn(frame, cols[i], isLowLight);
            results[i].centerX = cols[i];
            results[i].isLowLight = isLowLight;
        }

        return results;
    }

    private ObstacleData scanColumn(Bitmap frame, int x, boolean isLowLight) {
        ObstacleData data = new ObstacleData();
        int horizonY = (int)(height * 0.5); // Simplified horizon
        
        // Scan up from bottom for ground obstacle (TruePath™)
        int lastBrightness = -1;
        int sensitivity = 40;
        // Confidence proxy: how sharply the ground→obstacle brightness
        // edge crossed the sensitivity threshold. A contrast of exactly
        // `sensitivity` maps to 0, a saturated 255-step edge maps to 1.
        // This lets the main loop reject weak edges without needing a
        // full classifier.
        float edgeContrast = 0.0f;

        for (int y = height - 10; y > horizonY; y--) {
            int pixel = frame.getPixel(x, y);
            int brightness = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3;

            if (lastBrightness != -1) {
                int delta = Math.abs(brightness - lastBrightness);
                if (delta > sensitivity) {
                    data.baseY = y;
                    // Fix: Calculate apparent width at base for classification
                    data.baseWidthPx = calculateWidthAtRow(frame, x, y);
                    edgeContrast = Math.min(1.0f,
                            (delta - sensitivity) / (float)(255 - sensitivity));
                    break;
                }
            }
            lastBrightness = brightness;
        }
        // Low-light frames get a confidence penalty because edge detection
        // on dim frames is dominated by sensor noise, not real contrast.
        // isLowLight comes pre-computed from scanFrame() so we don't pay
        // the 25-getPixel JNI cost three times per frame.
        float lightPenalty = isLowLight ? 0.5f : 1.0f;
        // Unrealistic widths (detector latched onto a tiny speckle or
        // half the frame) drop confidence further so the smoother can
        // discard the reading.
        float widthSanity = (data.baseWidthPx > 15 && data.baseWidthPx < 400) ? 1.0f : 0.5f;
        data.confidence = (data.baseY >= 0)
                ? Math.max(0.1f, edgeContrast * lightPenalty * widthSanity)
                : 0.0f;

        // Scan down from top for suspended obstacle (SkyShield™)
        lastBrightness = -1;
        for (int y = 10; y < horizonY; y++) {
            int pixel = frame.getPixel(x, y);
            int brightness = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3;

            if (lastBrightness != -1) {
                if (Math.abs(brightness - lastBrightness) > sensitivity) {
                    data.hangY = y;
                    break;
                }
            }
            lastBrightness = brightness;
        }

        return data;
    }

    private float calculateWidthAtRow(Bitmap frame, int x, int y) {
        int sensitivity = 30;
        int lastBrightness = (Color.red(frame.getPixel(x, y)) + Color.green(frame.getPixel(x, y)) + Color.blue(frame.getPixel(x, y))) / 3;
        
        // Scan Left
        int leftEdge = x;
        for (int i = x - 1; i > 0 && i > x - 100; i--) {
            int pixel = frame.getPixel(i, y);
            int brightness = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3;
            if (Math.abs(brightness - lastBrightness) > sensitivity) {
                leftEdge = i;
                break;
            }
        }
        
        // Scan Right
        int rightEdge = x;
        for (int i = x + 1; i < width && i < x + 100; i++) {
            int pixel = frame.getPixel(i, y);
            int brightness = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3;
            if (Math.abs(brightness - lastBrightness) > sensitivity) {
                rightEdge = i;
                break;
            }
        }
        
        return (rightEdge - leftEdge);
    }

    private boolean isLowLight(Bitmap frame) {
        int centerX = width / 2;
        int centerY = height / 2;
        int totalBrightness = 0;
        int count = 0;

        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                int pixel = frame.getPixel(centerX + dx, centerY + dy);
                totalBrightness += (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3;
                count++;
            }
        }
        return (totalBrightness / count) < 50;
    }
}
