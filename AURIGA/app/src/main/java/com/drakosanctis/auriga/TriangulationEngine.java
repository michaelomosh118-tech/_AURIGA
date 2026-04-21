package com.drakosanctis.auriga;

/**
 * TriangulationEngine: The mathematical core of DrakoSanctis.
 * Performs TruePath(TM) (Ground Distance), SkyShield(TM) (Suspended Height),
 * and Bearing (Horizontal Angle) calculations.
 *
 * Bearing is now derived from each detection's actual centerX rather
 * than the frame midline; row lookup applies pitch correction from the
 * HardwareHAL accelerometer so handheld tilt no longer biases distance;
 * and a width-vs-row sanity check penalises confidence on frames where
 * the two independent estimators disagree by more than 25%, giving the
 * low-pass smoother a signal to reject the reading.
 */
public class TriangulationEngine {

    private final FiducialLUT lut;
    private final HardwareHAL hal;
    private int frameWidth = 640;
    private int frameHeight = 480;

    // Agreement band between width-derived and row-derived distance.
    // Outside this band, the reading's confidence is cut so the
    // smoother ignores the frame rather than integrating a bad sample.
    private static final float SANITY_BAND = 0.25f;

    public TriangulationEngine(FiducialLUT lut, HardwareHAL hal) {
        this.lut = lut;
        this.hal = hal;
    }

    public void setFrameSize(int width, int height) {
        this.frameWidth = width;
        this.frameHeight = height;
    }

    /**
     * SpatialOutput: Holds the final calculated metrics for an object.
     *
     * sanityScore is 1.0 when the row-based and width-based distance
     * estimators agree, and drops toward 0 as they diverge. The main
     * loop multiplies this into the detector's confidence before
     * passing the reading to the smoother so disagreeing frames are
     * effectively discarded without special-casing in the caller.
     */
    public static class SpatialOutput {
        public float distanceM;
        public float heightM;
        public float bearingDeg;
        public String signature; // WALL / POLE / OBJECT
        public float sanityScore;

        SpatialOutput(float d, float h, float b, String s, float sanity) {
            this.distanceM = d;
            this.heightM = h;
            this.bearingDeg = b;
            this.signature = s;
            this.sanityScore = sanity;
        }
    }

    /**
     * calculateGroundDistance: TruePath(TM) Logic.
     * Row-based LUT lookup is primary; width-based is the cross-check.
     */
    public SpatialOutput calculateGroundDistance(int baseY, int centerX, float observedWidthPx) {
        if (baseY == -1) return null;

        float focalLengthPx = hal.getFocalLengthPx(frameWidth);
        float pitchRad = hal.getPitchRadians();
        float distanceM =
                lut.getDistanceFromRowWithPitch(baseY, pitchRad, focalLengthPx);

        float deltaX = centerX - (frameWidth / 2.0f);
        float bearingDeg = (float) Math.toDegrees(Math.atan(deltaX / focalLengthPx));

        // Cross-check: width-based distance should roughly agree.
        float sanity = 1.0f;
        if (observedWidthPx > 0f) {
            float widthDistance = lut.getDistanceFromWidth(observedWidthPx);
            if (widthDistance > 0f && distanceM > 0f) {
                float rel = Math.abs(widthDistance - distanceM) / distanceM;
                if (rel > SANITY_BAND) {
                    // Linearly fade sanity to 0 as disagreement grows
                    // from 25% to 100%.
                    sanity = Math.max(0.0f, 1f - (rel - SANITY_BAND) / 0.75f);
                }
            }
        }

        float pixelWidthOf20cm = lut.getPixelWidthAtDistance(distanceM);
        float objectWidthM = (pixelWidthOf20cm > 0f)
                ? (observedWidthPx / pixelWidthOf20cm) * 0.20f
                : 0f;
        String signature = classify(objectWidthM);

        return new SpatialOutput(distanceM, 0.0f, bearingDeg, signature, sanity);
    }

    /**
     * calculateSuspendedHeight: SkyShield(TM) Logic.
     */
    public SpatialOutput calculateSuspendedHeight(int hangY, int centerX) {
        if (hangY == -1) return null;

        float focalLengthPx = hal.getFocalLengthPx(frameWidth);
        float pitchRad = hal.getPitchRadians();

        int virtualGroundY = hangY + 50;
        float distHorizM =
                lut.getDistanceFromRowWithPitch(virtualGroundY, pitchRad, focalLengthPx);

        float deltaX = centerX - (frameWidth / 2.0f);
        float bearingDeg = (float) Math.toDegrees(Math.atan(deltaX / focalLengthPx));

        float deltaY = (frameHeight / 2.0f) - hangY;
        float thetaRad = (float) Math.atan(deltaY / focalLengthPx);
        float heightM = (float) (distHorizM * Math.tan(thetaRad));

        return new SpatialOutput(distHorizM, heightM, bearingDeg, "OVERHANG", 1.0f);
    }

    private String classify(float widthM) {
        if (widthM > 1.5f) return "WALL";
        if (widthM < 0.3f) return "POLE";
        return "OBJECT";
    }
}
