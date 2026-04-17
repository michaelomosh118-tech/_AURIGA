package com.drakosanctis.auriga;

import android.graphics.Bitmap;
import java.util.ArrayList;
import java.util.List;

/**
 * CalibrationManager: The training mode state machine.
 * Guides the user through the 10-point TruePath™ calibration.
 * IDLE -> DETECTING -> CONFIRMED -> SAVING -> COMPLETE
 */
public class CalibrationManager {

    public enum State { IDLE, DETECTING, CONFIRMED, SAVING, COMPLETE }
    private State currentState = State.IDLE;

    private final FiducialLUT lut;
    private final ColorSquareDetector detector;
    private final List<Float> targetDistances = new ArrayList<>();
    private int currentPointIndex = 0;

    public CalibrationManager(FiducialLUT lut, ColorSquareDetector detector) {
        this.lut = lut;
        this.detector = detector;
        
        // Define the 10 training points for TruePath™ (0.5m to 5.0m)
        for (float d = 0.5f; d <= 5.0f; d += 0.5f) {
            targetDistances.add(d);
        }
    }

    /**
     * processFrame: Scans the training square and updates the UI state.
     */
    public ColorSquareDetector.DetectionResult processFrame(Bitmap frame) {
        if (currentState != State.DETECTING) return null;

        ColorSquareDetector.DetectionResult result = detector.detect(frame);
        if (result.confidence > 0.8f) {
            // Found a stable lock on the 20cm square
            return result;
        }
        return null;
    }

    /**
     * capturePoint: Saves the current pixel width and row for the target distance.
     */
    public void capturePoint(float pixelWidth, float pixelRow) {
        float distanceM = targetDistances.get(currentPointIndex);
        lut.addPoint(distanceM, pixelWidth, pixelRow);
        
        currentPointIndex++;
        if (currentPointIndex >= targetDistances.size()) {
            currentState = State.COMPLETE;
            lut.persist(); // Finalize and save to SharedPreferences
        } else {
            currentState = State.IDLE;
        }
    }

    public void setState(State state) { this.currentState = state; }
    public State getState() { return currentState; }
    public float getNextDistance() { return targetDistances.get(currentPointIndex); }
    public int getProgress() { return currentPointIndex; }
    public int getTotalPoints() { return targetDistances.size(); }
}
