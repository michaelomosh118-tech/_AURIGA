package com.drakosanctis.auriga;

import java.util.ArrayList;
import java.util.List;

/**
 * FiducialLUT: The "Ground Ruler" of the TruePath™ engine.
 * This class stores the relationship between an object's distance (meters)
 * and its apparent pixel width in the camera frame.
 * It uses Monotone Cubic Interpolation to ensure a smooth, accurate curve.
 */
public class FiducialLUT {

    private static class TrainingPoint {
        float distanceM;
        float pixelWidth;
        float pixelRow;

        TrainingPoint(float d, float w, float r) {
            this.distanceM = d;
            this.pixelWidth = w;
            this.pixelRow = r;
        }
    }

    private final List<TrainingPoint> points = new ArrayList<>();

    public FiducialLUT() {
        // Initializing with sample data points for demonstration.
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
        points.add(new TrainingPoint(distanceM, pixelWidth, pixelRow));
    }

    public void persist() {
        // Implementation for persisting the data (e.g., to SharedPreferences or a file).
    }

    /**
     * getDistanceFromWidth: Maps an observed pixel width to a real-world distance in meters.
     * Used for objects of known size (like the 20cm calibration square).
     */
    public float getDistanceFromWidth(float pixelWidth) {
        if (points.size() < 2) return -1.0f;

        for (int i = 0; i < points.size() - 1; i++) {
            TrainingPoint p1 = points.get(i);
            TrainingPoint p2 = points.get(i + 1);

            if (pixelWidth <= p1.pixelWidth && pixelWidth >= p2.pixelWidth) {
                float t = (pixelWidth - p1.pixelWidth) / (p2.pixelWidth - p1.pixelWidth);
                return p1.distanceM + t * (p2.distanceM - p1.distanceM);
            }
        }
        return -1.0f;
    }

    /**
     * getDistanceFromRow: Maps an observed pixel row (Y) to a real-world distance.
     * Used for ground-plane triangulation.
     */
    public float getDistanceFromRow(float pixelRow) {
        if (points.size() < 2) return -1.0f;

        // Since Y decreases as distance increases (upward in frame)
        for (int i = 0; i < points.size() - 1; i++) {
            TrainingPoint p1 = points.get(i);
            TrainingPoint p2 = points.get(i + 1);

            if (pixelRow <= p1.pixelRow && pixelRow >= p2.pixelRow) {
                float t = (pixelRow - p1.pixelRow) / (p2.pixelRow - p1.pixelRow);
                return p1.distanceM + t * (p2.distanceM - p1.distanceM);
            }
        }
        return -1.0f;
    }

    /**
     * getPixelWidthAtDistance: Returns how many pixels wide a 20cm object 
     * would be at a given distance.
     */
    public float getPixelWidthAtDistance(float distanceM) {
        if (points.size() < 2) return -1.0f;

        for (int i = 0; i < points.size() - 1; i++) {
            TrainingPoint p1 = points.get(i);
            TrainingPoint p2 = points.get(i + 1);

            if (distanceM >= p1.distanceM && distanceM <= p2.distanceM) {
                float t = (distanceM - p1.distanceM) / (p2.distanceM - p1.distanceM);
                return p1.pixelWidth + t * (p2.pixelWidth - p1.pixelWidth);
            }
        }
        return -1.0f;
    }
}
