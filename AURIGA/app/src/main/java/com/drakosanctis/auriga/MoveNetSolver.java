package com.drakosanctis.auriga;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.util.Log;
import org.tensorflow.lite.Interpreter;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * MoveNetSolver — sub-pixel H_c derivation from human pose keypoints.
 *
 * Uses MoveNet Lightning (movenet_lightning.tflite) to locate the nose
 * (keypoint 0) and ankles (keypoints 15 & 16), then applies the same
 * camera-height formula used by DracoAIDEngine's YOLO bbox path:
 *
 *   H_c = 1.70 × (anklePixelY − horizonRow)
 *                ──────────────────────────────
 *                (anklePixelY − headPixelY)
 *
 * Because MoveNet returns sub-pixel keypoint coordinates (not coarse
 * bbox corners), this solver typically converges in 1–2 frames versus
 * the 5-frame stability gate the YOLO bbox path requires.
 *
 * ──────────────────────────────────────────────────────────────────
 * Model contract
 *   File    : assets/movenet_lightning.tflite   (~12 MB)
 *   Input   : [1, 192, 192, 3]  float32 RGB, range [0, 255]
 *   Output  : [1,   1, 17, 3]   float32 (y_norm, x_norm, score) per kp
 * ──────────────────────────────────────────────────────────────────
 *
 * Graceful degradation: if the model file is absent, tryCreate() returns
 * null and LocatorActivity continues with YOLO-only DracoAID calibration.
 *
 * Keypoint index reference (COCO 17-point layout):
 *   0 nose · 1 left_eye · 2 right_eye · 3 left_ear · 4 right_ear
 *   5 left_shoulder · 6 right_shoulder · 7 left_elbow · 8 right_elbow
 *   9 left_wrist · 10 right_wrist · 11 left_hip · 12 right_hip
 *   13 left_knee · 14 right_knee · 15 left_ankle · 16 right_ankle
 */
public class MoveNetSolver {

    private static final String TAG        = "MoveNetSolver";
    private static final String MODEL_FILE = "movenet_lightning.tflite";

    /** Resolution expected by MoveNet Lightning. */
    private static final int INPUT_SIZE = 192;

    /**
     * Minimum per-keypoint confidence to trust the reading.
     * MoveNet Lightning median accuracy at 0.3 is ~82 % on COCO val2017.
     */
    private static final float MIN_SCORE = 0.30f;

    /**
     * High-confidence threshold. Readings at or above this level are
     * committed to the DracoAID buffer as fast-path calibrations —
     * skipping the full 5-sample stability gate.
     */
    public static final float FAST_PATH_SCORE = 0.70f;

    // COCO keypoint indices used for H_c
    private static final int KP_NOSE        = 0;
    private static final int KP_LEFT_ANKLE  = 15;
    private static final int KP_RIGHT_ANKLE = 16;

    // Valid physical range for H_c in metres (adult/child standing range)
    private static final float HC_MIN = 0.40f;
    private static final float HC_MAX = 2.50f;

    // The person must span at least 10 % of frame height to be usable.
    private static final float MIN_SPAN_FRACTION = 0.10f;

    private final Interpreter interpreter;
    private final ByteBuffer  inputBuffer;

    // Output tensor: [batch=1][instance=1][17 keypoints][3 values]
    private final float[][][][] outputBuffer = new float[1][1][17][3];

    // ─── Factory ──────────────────────────────────────────────────────

    /**
     * Returns a ready solver, or null if the model file is not bundled.
     * Call on the background thread — Interpreter construction reads the file.
     */
    public static MoveNetSolver tryCreate(Context ctx) {
        try {
            MappedByteBuffer model = loadModelFile(ctx);
            MoveNetSolver solver = new MoveNetSolver(model);
            Log.i(TAG, "MoveNet Lightning loaded — fast H_c path active");
            return solver;
        } catch (Throwable t) {
            Log.i(TAG, "movenet_lightning.tflite not found; YOLO-only calibration active. "
                    + "(" + t.getMessage() + ")");
            return null;
        }
    }

    private MoveNetSolver(MappedByteBuffer model) {
        Interpreter.Options opts = new Interpreter.Options();
        opts.setNumThreads(2);           // spare the main / YOLO threads
        interpreter = new Interpreter(model, opts);

        // float32 × 3 channels × 192 × 192
        inputBuffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4);
        inputBuffer.order(ByteOrder.nativeOrder());
    }

    // ─── Core solver ──────────────────────────────────────────────────

    /**
     * Run MoveNet on {@code bmp} and derive H_c.
     *
     * @param bmp     Full-resolution camera frame (any size — we resize internally).
     * @param frameH  Original frame height in pixels.
     * @return        {@link Result} with hcMetres + confidence, or null if the pose
     *                is absent / too small / physically implausible.
     */
    public Result solve(Bitmap bmp, int frameH) {
        Bitmap scaled = Bitmap.createScaledBitmap(bmp, INPUT_SIZE, INPUT_SIZE, false);
        fillInputBuffer(scaled);
        if (scaled != bmp) scaled.recycle();

        inputBuffer.rewind();
        interpreter.run(inputBuffer, outputBuffer);

        return extractHc(outputBuffer[0][0], frameH);
    }

    /**
     * Fills the float32 input buffer with raw [0, 255] RGB channel values.
     * MoveNet Lightning (float variant) expects exactly this range.
     */
    private void fillInputBuffer(Bitmap bmp) {
        inputBuffer.rewind();
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);
        for (int px : pixels) {
            inputBuffer.putFloat((px >> 16) & 0xFF); // R
            inputBuffer.putFloat((px >>  8) & 0xFF); // G
            inputBuffer.putFloat( px        & 0xFF); // B
        }
    }

    /**
     * Extracts head / ankle keypoints from the MoveNet output tensor and
     * computes H_c using the same camera-geometry formula as DracoAIDEngine.
     *
     * @param kps    [17][3] array — each row is [y_norm, x_norm, score].
     * @param frameH Original frame height for denormalisation.
     */
    private Result extractHc(float[][] kps, int frameH) {
        float noseScore  = kps[KP_NOSE][2];
        float lAScore    = kps[KP_LEFT_ANKLE][2];
        float rAScore    = kps[KP_RIGHT_ANKLE][2];

        if (noseScore < MIN_SCORE) return null;

        // Choose ankle(s) — prefer average of both when both confident.
        float ankleYNorm;
        float ankleScore;
        if (lAScore >= MIN_SCORE && rAScore >= MIN_SCORE) {
            ankleYNorm = (kps[KP_LEFT_ANKLE][0] + kps[KP_RIGHT_ANKLE][0]) / 2f;
            ankleScore = (lAScore + rAScore) / 2f;
        } else if (lAScore >= MIN_SCORE) {
            ankleYNorm = kps[KP_LEFT_ANKLE][0];
            ankleScore = lAScore;
        } else if (rAScore >= MIN_SCORE) {
            ankleYNorm = kps[KP_RIGHT_ANKLE][0];
            ankleScore = rAScore;
        } else {
            return null; // no reliable ankle — can't solve H_c
        }

        // Denormalise to pixel rows in the original frame.
        float headPixelY  = kps[KP_NOSE][0] * frameH;
        float anklePixelY = ankleYNorm       * frameH;
        float horizonRow  = frameH * 0.50f;

        float span = anklePixelY - headPixelY;
        if (span < frameH * MIN_SPAN_FRACTION) return null; // person too small

        float hc = 1.70f * (anklePixelY - horizonRow) / span;
        if (hc < HC_MIN || hc > HC_MAX) return null;

        // Composite confidence = geometric mean of nose and ankle scores
        // so a shaky ankle penalises the overall reading more than the nose.
        float confidence = (float) Math.sqrt(noseScore * ankleScore);
        return new Result(hc, confidence);
    }

    // ─── Lifecycle ────────────────────────────────────────────────────

    public void close() {
        try { interpreter.close(); } catch (Throwable ignored) {}
    }

    // ─── Result ───────────────────────────────────────────────────────

    /**
     * Immutable result from a single MoveNet solve pass.
     *
     * confidence is the geometric mean of the nose and ankle MoveNet scores (0–1).
     * Readings ≥ FAST_PATH_SCORE bypass the DracoAID stability gate and are
     * committed directly to the FiducialLUT.
     */
    public static class Result {
        public final float hcMetres;
        public final float confidence;

        Result(float hc, float conf) {
            this.hcMetres   = hc;
            this.confidence = conf;
        }

        @Override
        public String toString() {
            return String.format("MoveNet H_c=%.2fm conf=%.2f", hcMetres, confidence);
        }
    }

    // ─── Asset loader ─────────────────────────────────────────────────

    private static MappedByteBuffer loadModelFile(Context ctx) throws IOException {
        AssetFileDescriptor afd = ctx.getAssets().openFd(MODEL_FILE);
        try (FileInputStream fis = new FileInputStream(afd.getFileDescriptor())) {
            return fis.getChannel().map(
                    FileChannel.MapMode.READ_ONLY,
                    afd.getStartOffset(),
                    afd.getDeclaredLength());
        }
    }
}
