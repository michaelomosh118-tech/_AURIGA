package com.drakosanctis.auriga;

import android.graphics.RectF;

/**
 * Single object detection produced by {@link YoloDetector}.
 *
 * <p>Coordinates are stored in <em>normalised image space</em> (0..1)
 * relative to the original camera frame, NOT the 640x640 model input.
 * That keeps {@link LocatorOverlayView} agnostic of the analyser's
 * letterbox padding -- it just multiplies by its own width/height.
 *
 * <p>Immutable by convention so the analysis thread can hand the
 * list directly to the UI thread without copying.
 */
public final class Detection {

    /** Human-readable COCO class label, e.g. "person", "chair". */
    public final String label;

    /** Class confidence in [0,1] from YOLOv8's per-class score. */
    public final float confidence;

    /** Bounding box in normalised image coords (left/top/right/bottom). */
    public final RectF box;

    /** Index into the COCO labels file (0..79). */
    public final int classId;

    public Detection(String label, int classId, float confidence, RectF box) {
        this.label = label;
        this.classId = classId;
        this.confidence = confidence;
        this.box = box;
    }

    /** Box centre X in normalised coords. */
    public float centerX() { return (box.left + box.right) * 0.5f; }

    /** Box centre Y in normalised coords. */
    public float centerY() { return (box.top + box.bottom) * 0.5f; }

    /** Box area in normalised coords -- used as a coarse "closeness" cue. */
    public float area() {
        return Math.max(0f, box.right - box.left)
             * Math.max(0f, box.bottom - box.top);
    }
}
