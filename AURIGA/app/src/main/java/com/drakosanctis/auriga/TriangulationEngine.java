package com.drakosanctis.auriga;

/**
 * TriangulationEngine: The mathematical core of DrakoSanctis.
 * Performs TruePath™ (Ground Distance), SkyShield™ (Suspended Height),
 * and Bearing (Horizontal Angle) calculations.
 */
public class TriangulationEngine {

    private final FiducialLUT lut;
    private final HardwareHAL hal;
    private final int frameWidth = 640;
    private final int frameHeight = 480;

    public TriangulationEngine(FiducialLUT lut, HardwareHAL hal) {
        this.lut = lut;
        this.hal = hal;
    }

    /**
     * SpatialOutput: Holds the final calculated metrics for an object.
     */
    public static class SpatialOutput {
        public float distanceM;
        public float heightM;
        public float bearingDeg;
        public String signature; // WALL / POLE / OBJECT

        SpatialOutput(float d, float h, float b, String s) {
            this.distanceM = d;
            this.heightM = h;
            this.bearingDeg = b;
            this.signature = s;
        }
    }

    /**
     * calculateGroundDistance: TruePath™ Logic.
     * Maps base pixel row to real distance using the calibrated LUT.
     */
    public SpatialOutput calculateGroundDistance(int baseY, int centerX, float observedWidthPx) {
        if (baseY == -1) return null;

        // --- STEP 1: TruePath™ Distance ---
        // Fix 3: Use the correct row-to-distance mapping
        float distanceM = lut.getDistanceFromRow(baseY);

        // --- STEP 2: Bearing ---
        float focalLengthPx = hal.getFocalLengthPx(frameWidth);
        float deltaX = centerX - (frameWidth / 2.0f);
        float bearingDeg = (float) Math.toDegrees(Math.atan(deltaX / focalLengthPx));

        // --- STEP 3: Signature (Object Classification) ---
        // Fix 4: Calculate real-world width (meters) correctly
        // RealWidth = (ObservedPixelWidth * Distance) / FocalLength
        // Or using the LUT: RealWidth = (ObservedPixelWidth / PixelWidthOf20cmAtThisDist) * 0.20m
        float pixelWidthOf20cm = lut.getPixelWidthAtDistance(distanceM);
        float objectWidthM = (observedWidthPx / pixelWidthOf20cm) * 0.20f;
        
        String signature = classify(objectWidthM);

        return new SpatialOutput(distanceM, 0.0f, bearingDeg, signature);
    }

    /**
     * calculateSuspendedHeight: SkyShield™ Logic.
     * Projects object base straight down to ground plane for horizontal distance,
     * then uses trigonometry for clearance height.
     */
    public SpatialOutput calculateSuspendedHeight(int hangY, int centerX) {
        if (hangY == -1) return null;

        // --- STEP 1: Horizontal Distance ---
        // Fix 3: Use the correct row-to-distance mapping
        int virtualGroundY = hangY + 50; 
        float distHorizM = lut.getDistanceFromRow(virtualGroundY);

        // --- STEP 2: Bearing ---
        float focalLengthPx = hal.getFocalLengthPx(frameWidth);
        float deltaX = centerX - (frameWidth / 2.0f);
        float bearingDeg = (float) Math.toDegrees(Math.atan(deltaX / focalLengthPx));

        // --- STEP 3: Clearance Height ---
        // h = d * tan(theta), where theta is angle to suspended pixel row
        float deltaY = (frameHeight / 2.0f) - hangY;
        float thetaRad = (float) Math.atan(deltaY / focalLengthPx);
        float heightM = (float) (distHorizM * Math.tan(thetaRad));

        return new SpatialOutput(distHorizM, heightM, bearingDeg, "OVERHANG");
    }

    private String classify(float widthM) {
        if (widthM > 1.5f) return "WALL";
        if (widthM < 0.3f) return "POLE";
        return "OBJECT";
    }
}
