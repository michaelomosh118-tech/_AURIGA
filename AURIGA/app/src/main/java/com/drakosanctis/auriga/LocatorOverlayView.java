package com.drakosanctis.auriga;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.Collections;
import java.util.List;

/**
 * Transparent overlay that paints {@link Detection} bounding boxes,
 * class labels, a centre crosshair and a status strip on top of the
 * CameraX {@code PreviewView}.
 *
 * <p>The view consumes <em>normalised</em> detections (0..1 in
 * original camera-frame space) so it never has to know about the
 * detector's letterbox padding or input resolution. Painting is
 * cheap and double-buffered through the standard {@link View} draw
 * cycle -- {@link #setDetections(List)} just stores the list and
 * calls {@link #postInvalidate()}.
 *
 * <p>The active <strong>target</strong> (the detection the activity
 * has chosen to speak about) is highlighted in amber instead of the
 * default cyan, making it visually obvious which box drives the
 * voice readout.
 */
public final class LocatorOverlayView extends View {

    private static final int CYAN   = Color.parseColor("#00B8D4");
    private static final int AMBER  = Color.parseColor("#FFB300");
    private static final int SHADOW = 0x99000000;
    private static final int LABEL_BG = 0xCC000000;

    private final Paint boxPaint;
    private final Paint targetBoxPaint;
    private final Paint labelTextPaint;
    private final Paint labelBgPaint;
    private final Paint crosshairPaint;
    private final Paint statusBgPaint;
    private final Paint statusTextPaint;

    private List<Detection> detections = Collections.emptyList();
    private Detection target = null;
    private String statusLine = "";
    private boolean modelReady = false;

    private final Rect tmpTextBounds = new Rect();

    public LocatorOverlayView(Context ctx) { this(ctx, null); }
    public LocatorOverlayView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        // We deliberately do NOT call setLayerType(LAYER_TYPE_HARDWARE).
        // Paint.setShadowLayer() is silently ignored on hardware-layer
        // canvases for API < 28 and has been observed to throw on a
        // handful of older Mali GPU drivers. The default layer type
        // (HARDWARE on modern Android, SOFTWARE on older devices)
        // already does the right thing for an overlay this lightweight.

        float density = getResources().getDisplayMetrics().density;

        boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(2f * density);
        boxPaint.setColor(CYAN);

        targetBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        targetBoxPaint.setStyle(Paint.Style.STROKE);
        targetBoxPaint.setStrokeWidth(3f * density);
        targetBoxPaint.setColor(AMBER);

        labelTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelTextPaint.setColor(Color.WHITE);
        labelTextPaint.setTextSize(13f * density);
        labelTextPaint.setShadowLayer(2f, 0f, 1f, SHADOW);

        labelBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelBgPaint.setColor(LABEL_BG);
        labelBgPaint.setStyle(Paint.Style.FILL);

        crosshairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        crosshairPaint.setColor(CYAN);
        crosshairPaint.setStrokeWidth(1.5f * density);
        crosshairPaint.setStyle(Paint.Style.STROKE);
        crosshairPaint.setAlpha(160);

        statusBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        statusBgPaint.setColor(0xCC000000);

        statusTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        statusTextPaint.setColor(CYAN);
        statusTextPaint.setTextSize(13f * density);
        statusTextPaint.setShadowLayer(4f, 0f, 0f, CYAN);
    }

    /**
     * Update the box list. Pass an empty list to clear the overlay.
     * The {@code target} is highlighted in amber and may be null
     * (no current speech target).
     */
    public void setDetections(List<Detection> dets, Detection target) {
        this.detections = (dets == null) ? Collections.<Detection>emptyList() : dets;
        this.target = target;
        postInvalidate();
    }

    /** Update the bottom status strip. Pass empty string to hide it. */
    public void setStatus(String status) {
        this.statusLine = status == null ? "" : status;
        postInvalidate();
    }

    /**
     * Tells the overlay whether the YOLO model finished loading.
     * Until then we paint an amber "MODEL LOADING…" banner instead
     * of an empty viewport so the user sees the camera is alive
     * but inference hasn't started yet.
     */
    public void setModelReady(boolean ready) {
        this.modelReady = ready;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        // ── Centre crosshair (always visible -- helps the user aim) ──
        float cx = w * 0.5f;
        float cy = h * 0.5f;
        float crossLen = Math.min(w, h) * 0.04f;
        canvas.drawLine(cx - crossLen, cy, cx + crossLen, cy, crosshairPaint);
        canvas.drawLine(cx, cy - crossLen, cx, cy + crossLen, crosshairPaint);

        // ── Boxes + labels ──
        for (Detection d : detections) {
            boolean isTarget = (d == target);
            Paint stroke = isTarget ? targetBoxPaint : boxPaint;
            float l = d.box.left   * w;
            float t = d.box.top    * h;
            float r = d.box.right  * w;
            float b = d.box.bottom * h;
            canvas.drawRect(l, t, r, b, stroke);

            String label = d.label.toUpperCase()
                    + " " + Math.round(d.confidence * 100) + "%";
            labelTextPaint.getTextBounds(label, 0, label.length(), tmpTextBounds);
            float padX = 6f, padY = 4f;
            float labelW = tmpTextBounds.width() + padX * 2;
            float labelH = tmpTextBounds.height() + padY * 2;
            float labelTop = Math.max(0, t - labelH);
            canvas.drawRect(l, labelTop, l + labelW, labelTop + labelH,
                    labelBgPaint);
            canvas.drawText(label, l + padX,
                    labelTop + labelH - padY,
                    labelTextPaint);
        }

        // ── Status strip ──
        if (!modelReady) {
            drawStatus(canvas, "MODEL LOADING — POINT CAMERA AT WORKSPACE", AMBER);
        } else if (!statusLine.isEmpty()) {
            drawStatus(canvas, statusLine, CYAN);
        }
    }

    private void drawStatus(Canvas canvas, String text, int color) {
        int w = getWidth();
        int h = getHeight();
        statusTextPaint.setColor(color);
        statusTextPaint.setShadowLayer(6f, 0f, 0f, color);
        statusTextPaint.getTextBounds(text, 0, text.length(), tmpTextBounds);
        float padX = 14f, padY = 10f;
        float density = getResources().getDisplayMetrics().density;
        float boxH = tmpTextBounds.height() + padY * 2;
        float top = h - boxH - 16f * density;
        canvas.drawRect(0, top, w, top + boxH, statusBgPaint);
        canvas.drawText(text,
                (w - tmpTextBounds.width()) * 0.5f,
                top + boxH - padY,
                statusTextPaint);
    }
}
