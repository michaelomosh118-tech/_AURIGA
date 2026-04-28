package com.drakosanctis.auriga;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Log;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * On-device YOLOv8n object detector backed by a TensorFlow Lite
 * interpreter. The model is bundled as an asset (see
 * {@code app/src/main/assets/MODEL_README.md} for the exact path
 * conventions) and runs entirely offline -- no network round-trips,
 * no Play Services dependency, no third-party cloud.
 *
 * <h3>Model contract</h3>
 *
 * <p>Standard Ultralytics YOLOv8n TFLite export:
 * <ul>
 *   <li>Input tensor: {@code [1, 640, 640, 3]} -- either fp32 in
 *       [0,1] or int8 quantised. The detector inspects
 *       {@link Tensor#dataType()} at load time and adapts the
 *       pre-processor accordingly.</li>
 *   <li>Output tensor: {@code [1, 84, 8400]} -- 8400 anchors, each
 *       carrying {@code [cx, cy, w, h, score_class_0, ..., score_class_79]}
 *       in 640x640 input space, with class scores already
 *       sigmoid-applied.</li>
 * </ul>
 *
 * <h3>Pipeline</h3>
 *
 * <ol>
 *   <li>{@link #detect(Bitmap)} letterbox-resizes the camera frame
 *       to 640x640, preserving aspect ratio with a neutral 114-grey
 *       pad so YOLO's training distribution matches.</li>
 *   <li>The pre-processed tensor is fed straight into the
 *       interpreter on the calling thread (typically the CameraX
 *       analyser executor).</li>
 *   <li>Output is transposed in-place to anchor-major
 *       {@code [8400, 84]}, the best class is picked per anchor,
 *       low-confidence anchors are dropped, and class-aware
 *       Non-Maximum Suppression is run with IoU threshold 0.45.</li>
 *   <li>Surviving boxes are remapped from 640x640 input coords
 *       back to the original frame, then normalised to [0,1] so
 *       the {@link LocatorOverlayView} can paint them without
 *       knowing anything about the analyser's letterbox.</li>
 * </ol>
 *
 * <h3>Lifecycle</h3>
 *
 * <p>Construct with {@link #tryCreate(Context)} -- it returns
 * {@code null} if no model file is bundled, so callers can fall
 * back to the WebView locator without an exception bubbling up.
 * Call {@link #close()} from the host activity's
 * {@code onDestroy()} to release the native interpreter handle
 * and the COCO label list.
 *
 * <h3>Thread safety</h3>
 *
 * <p>{@link Interpreter#run(Object, Object)} is single-threaded.
 * Call {@link #detect(Bitmap)} from one thread at a time -- the
 * CameraX {@code ImageAnalysis} executor satisfies that by
 * construction.
 */
public final class YoloDetector implements AutoCloseable {

    private static final String TAG = "YoloDetector";

    /** Square input edge expected by the standard YOLOv8 export. */
    public static final int INPUT_SIZE = 640;

    /** Discard anchors below this raw class score. */
    private static final float SCORE_THRESHOLD = 0.30f;

    /** IoU threshold for class-aware NMS. */
    private static final float IOU_THRESHOLD = 0.45f;

    /** Cap returned detections so the overlay/voice loop stays light. */
    private static final int MAX_DETECTIONS = 20;

    /** Asset filenames probed in order. First hit wins. */
    private static final String[] MODEL_CANDIDATES = new String[] {
            "yolov8n_float32.tflite",
            "yolov8n.tflite",
            "yolov8n_int8.tflite"
    };

    /** Asset filename for the COCO class labels (one label per line). */
    private static final String LABELS_ASSET = "coco_labels.txt";

    private final Interpreter interpreter;
    private final List<String> labels;
    private final boolean inputIsQuantised;
    private final float inputScale;
    private final int inputZeroPoint;
    private final boolean outputIsQuantised;
    private final float outputScale;
    private final int outputZeroPoint;
    private final int numClasses;
    private final int numAnchors;

    /** Reusable input buffer -- avoid per-frame allocation. */
    private final ByteBuffer inputBuffer;

    /** Reusable output buffer holding [1, 84, 8400] floats post-dequant. */
    private final float[][][] outputBuffer;

    /** Reusable raw output for quantised models. */
    private final byte[][][] outputBufferQuant;

    /** Reusable letterbox bitmap to dodge per-frame Bitmap.create churn. */
    private final Bitmap letterboxBitmap;
    private final Canvas letterboxCanvas;
    private final Paint letterboxPaint;
    private final int[] pixelBuffer;

    private YoloDetector(Interpreter interpreter, List<String> labels) {
        this.interpreter = interpreter;
        this.labels = labels;

        Tensor inT = interpreter.getInputTensor(0);
        this.inputIsQuantised = inT.dataType() == DataType.UINT8
                || inT.dataType() == DataType.INT8;
        this.inputScale = inT.quantizationParams().getScale();
        this.inputZeroPoint = inT.quantizationParams().getZeroPoint();

        Tensor outT = interpreter.getOutputTensor(0);
        this.outputIsQuantised = outT.dataType() == DataType.UINT8
                || outT.dataType() == DataType.INT8;
        this.outputScale = outT.quantizationParams().getScale();
        this.outputZeroPoint = outT.quantizationParams().getZeroPoint();

        int[] outShape = outT.shape(); // [1, 84, 8400] (or [1, 8400, 84] in some exports)
        // Ultralytics standard export: [1, 4 + numClasses, numAnchors]
        // We assume the larger of the two trailing dims is anchors and
        // the smaller is the channel axis. That covers both layouts.
        if (outShape.length != 3) {
            throw new IllegalStateException("Unexpected output rank: "
                    + outShape.length);
        }
        int dimA = outShape[1];
        int dimB = outShape[2];
        this.numClasses = Math.min(dimA, dimB) - 4;
        this.numAnchors = Math.max(dimA, dimB);

        // Allocate input buffer: 640*640*3 bytes (quantised) or *4 (float)
        int bytesPerChannel = inputIsQuantised ? 1 : 4;
        this.inputBuffer = ByteBuffer
                .allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * bytesPerChannel)
                .order(ByteOrder.nativeOrder());

        if (outputIsQuantised) {
            this.outputBuffer = null;
            this.outputBufferQuant = new byte[1][outShape[1]][outShape[2]];
        } else {
            this.outputBuffer = new float[1][outShape[1]][outShape[2]];
            this.outputBufferQuant = null;
        }

        this.letterboxBitmap = Bitmap.createBitmap(
                INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888);
        this.letterboxCanvas = new Canvas(letterboxBitmap);
        this.letterboxPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        this.pixelBuffer = new int[INPUT_SIZE * INPUT_SIZE];

        Log.i(TAG, "YoloDetector ready: "
                + "inputDtype=" + inT.dataType()
                + " outputDtype=" + outT.dataType()
                + " numClasses=" + numClasses
                + " numAnchors=" + numAnchors
                + " labels=" + labels.size());
    }

    /**
     * Try to construct a detector. Returns {@code null} if the
     * model asset isn't bundled so the caller can fall back to the
     * legacy WebView locator with a friendly message instead of a
     * crash.
     *
     * @throws RuntimeException only on genuinely broken model files
     *         (corrupt header, mismatched op set, etc.). A missing
     *         file is signalled with {@code null}.
     */
    public static YoloDetector tryCreate(Context ctx) {
        AssetManager am = ctx.getAssets();
        String found = null;
        for (String candidate : MODEL_CANDIDATES) {
            try {
                AssetFileDescriptor fd = am.openFd(candidate);
                fd.close();
                found = candidate;
                break;
            } catch (IOException ignored) { /* not present, try next */ }
        }
        if (found == null) {
            Log.w(TAG, "No bundled YOLO model found in assets/. "
                    + "Tried: " + String.join(",", MODEL_CANDIDATES));
            return null;
        }

        try {
            MappedByteBuffer modelBuffer = loadModelFile(am, found);
            Interpreter.Options opts = new Interpreter.Options()
                    .setNumThreads(Math.max(2,
                            Runtime.getRuntime().availableProcessors() / 2));
            Interpreter interpreter = new Interpreter(modelBuffer, opts);
            List<String> labels = loadLabels(am);
            return new YoloDetector(interpreter, labels);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to initialise TFLite interpreter for " + found, t);
            throw new RuntimeException("YOLO model load failed: "
                    + t.getMessage(), t);
        }
    }

    /**
     * Run a single forward pass on the provided ARGB bitmap and
     * return the post-NMS detections, sorted by descending
     * confidence. Returns an empty list (never null) if nothing
     * survives the score threshold.
     */
    public List<Detection> detect(Bitmap frame) {
        if (frame == null || frame.isRecycled()) {
            return Collections.emptyList();
        }

        // ── 1. Letterbox-resize into the reusable 640x640 bitmap ──
        float scale = Math.min(
                (float) INPUT_SIZE / frame.getWidth(),
                (float) INPUT_SIZE / frame.getHeight());
        int scaledW = Math.round(frame.getWidth() * scale);
        int scaledH = Math.round(frame.getHeight() * scale);
        int padX = (INPUT_SIZE - scaledW) / 2;
        int padY = (INPUT_SIZE - scaledH) / 2;

        letterboxCanvas.drawColor(Color.rgb(114, 114, 114));
        RectF dst = new RectF(padX, padY, padX + scaledW, padY + scaledH);
        letterboxCanvas.drawBitmap(frame, null, dst, letterboxPaint);

        // ── 2. Pack pixels into the input buffer ──
        letterboxBitmap.getPixels(pixelBuffer, 0, INPUT_SIZE,
                0, 0, INPUT_SIZE, INPUT_SIZE);
        inputBuffer.rewind();
        if (inputIsQuantised) {
            for (int p : pixelBuffer) {
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = p & 0xFF;
                inputBuffer.put((byte) r);
                inputBuffer.put((byte) g);
                inputBuffer.put((byte) b);
            }
        } else {
            for (int p : pixelBuffer) {
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = p & 0xFF;
                inputBuffer.putFloat(r / 255f);
                inputBuffer.putFloat(g / 255f);
                inputBuffer.putFloat(b / 255f);
            }
        }

        // ── 3. Forward pass ──
        Object out = outputIsQuantised ? outputBufferQuant : outputBuffer;
        try {
            interpreter.run(inputBuffer, out);
        } catch (Throwable t) {
            Log.e(TAG, "TFLite forward pass failed", t);
            return Collections.emptyList();
        }

        // ── 4. Parse the [1, 84, 8400] (or [1, 8400, 84]) tensor ──
        // Ultralytics standard layout is channel-major: dim1=84,
        // dim2=8400. We support both by checking shape.
        int[] shape = interpreter.getOutputTensor(0).shape();
        boolean channelMajor = shape[1] < shape[2];

        ArrayList<Detection> raw = new ArrayList<>();
        for (int i = 0; i < numAnchors; i++) {
            float cx, cy, w, h;
            int bestClass = -1;
            float bestScore = 0f;

            if (channelMajor) {
                cx = readOutput(0, i, 0);
                cy = readOutput(1, i, 1);
                w  = readOutput(2, i, 2);
                h  = readOutput(3, i, 3);
                for (int c = 0; c < numClasses; c++) {
                    float s = readOutput(4 + c, i, 4 + c);
                    if (s > bestScore) {
                        bestScore = s;
                        bestClass = c;
                    }
                }
            } else {
                cx = readOutput(i, 0, 0);
                cy = readOutput(i, 1, 1);
                w  = readOutput(i, 2, 2);
                h  = readOutput(i, 3, 3);
                for (int c = 0; c < numClasses; c++) {
                    float s = readOutput(i, 4 + c, 4 + c);
                    if (s > bestScore) {
                        bestScore = s;
                        bestClass = c;
                    }
                }
            }

            if (bestScore < SCORE_THRESHOLD || bestClass < 0) continue;

            // Ultralytics YOLOv8 TFLite exports emit cx,cy,w,h as
            // NORMALISED [0,1] coordinates of the 640x640 input
            // tensor -- NOT as raw 640-pixel values. Multiply by
            // INPUT_SIZE to lift back into input pixel space so the
            // letterbox-undo arithmetic below is in the right units.
            // (This was a silent killer in the first cut: boxes were
            // collapsing to a single pixel near the origin and the
            // overlay looked permanently empty.)
            float leftPx   = (cx - w * 0.5f) * INPUT_SIZE;
            float topPx    = (cy - h * 0.5f) * INPUT_SIZE;
            float rightPx  = (cx + w * 0.5f) * INPUT_SIZE;
            float bottomPx = (cy + h * 0.5f) * INPUT_SIZE;

            // Undo letterbox: subtract pad, divide by scale, then
            // normalise to original frame dims so the overlay can
            // paint without knowing about our 640x640 letterbox.
            float origW = frame.getWidth();
            float origH = frame.getHeight();
            float l = clamp((leftPx   - padX) / scale / origW, 0f, 1f);
            float t = clamp((topPx    - padY) / scale / origH, 0f, 1f);
            float r = clamp((rightPx  - padX) / scale / origW, 0f, 1f);
            float b = clamp((bottomPx - padY) / scale / origH, 0f, 1f);
            if (r <= l || b <= t) continue;

            String label = (bestClass < labels.size())
                    ? labels.get(bestClass)
                    : ("class_" + bestClass);
            raw.add(new Detection(label, bestClass, bestScore,
                    new RectF(l, t, r, b)));
        }

        return nonMaxSuppression(raw);
    }

    /**
     * Read one element of the output tensor, dequantising on the
     * fly when the model is int8/uint8. The two indexing variants
     * cover both channel-major ([1,84,8400]) and anchor-major
     * ([1,8400,84]) exports.
     */
    private float readOutput(int dimA, int dimB, int unused) {
        if (outputIsQuantised) {
            int raw = outputBufferQuant[0][dimA][dimB] & 0xFF;
            return (raw - outputZeroPoint) * outputScale;
        }
        return outputBuffer[0][dimA][dimB];
    }

    /**
     * Class-aware Non-Maximum Suppression. Within each class, sort
     * by descending confidence and greedily drop boxes whose IoU
     * with the current pick exceeds {@link #IOU_THRESHOLD}. Cap the
     * survivor count at {@link #MAX_DETECTIONS} so the voice loop
     * never gets a torrent of items to read.
     */
    private static List<Detection> nonMaxSuppression(List<Detection> dets) {
        if (dets.isEmpty()) return dets;

        Collections.sort(dets, new Comparator<Detection>() {
            @Override
            public int compare(Detection a, Detection b) {
                return Float.compare(b.confidence, a.confidence);
            }
        });

        ArrayList<Detection> kept = new ArrayList<>();
        boolean[] suppressed = new boolean[dets.size()];
        for (int i = 0; i < dets.size(); i++) {
            if (suppressed[i]) continue;
            Detection a = dets.get(i);
            kept.add(a);
            if (kept.size() >= MAX_DETECTIONS) break;
            for (int j = i + 1; j < dets.size(); j++) {
                if (suppressed[j]) continue;
                Detection b = dets.get(j);
                if (b.classId != a.classId) continue;
                if (iou(a.box, b.box) > IOU_THRESHOLD) {
                    suppressed[j] = true;
                }
            }
        }
        return kept;
    }

    /** Intersection-over-union for two normalised boxes. */
    private static float iou(RectF a, RectF b) {
        float interLeft   = Math.max(a.left,   b.left);
        float interTop    = Math.max(a.top,    b.top);
        float interRight  = Math.min(a.right,  b.right);
        float interBottom = Math.min(a.bottom, b.bottom);
        float interW = Math.max(0f, interRight  - interLeft);
        float interH = Math.max(0f, interBottom - interTop);
        float inter = interW * interH;
        if (inter <= 0f) return 0f;
        float areaA = (a.right - a.left) * (a.bottom - a.top);
        float areaB = (b.right - b.left) * (b.bottom - b.top);
        float union = areaA + areaB - inter;
        return union <= 0f ? 0f : inter / union;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Memory-map a model file out of the APK assets. */
    private static MappedByteBuffer loadModelFile(AssetManager am, String name)
            throws IOException {
        AssetFileDescriptor fd = am.openFd(name);
        try (FileInputStream is = new FileInputStream(fd.getFileDescriptor())) {
            FileChannel ch = is.getChannel();
            return ch.map(FileChannel.MapMode.READ_ONLY,
                    fd.getStartOffset(), fd.getDeclaredLength());
        } finally {
            fd.close();
        }
    }

    /** Read coco_labels.txt -- one COCO class per line. */
    private static List<String> loadLabels(AssetManager am) throws IOException {
        ArrayList<String> out = new ArrayList<>(80);
        try (InputStream is = am.open(LABELS_ASSET);
             BufferedReader r = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (!t.isEmpty()) out.add(t);
            }
        }
        return out;
    }

    @Override
    public void close() {
        try { interpreter.close(); } catch (Throwable ignored) {}
    }
}
