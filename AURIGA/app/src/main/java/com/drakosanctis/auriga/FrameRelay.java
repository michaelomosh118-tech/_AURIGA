package com.drakosanctis.auriga;

import android.graphics.Bitmap;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * FrameRelay — singleton frame bus between the camera owner (LocatorActivity)
 * and all frame consumers (AurigaCoreService engines).
 *
 * Design rationale:
 *   Previously AurigaCoreService bound CameraX with its own LifecycleRegistry,
 *   while LocatorActivity independently bound the same physical camera. On
 *   Android 12+ (and strictly enforced on SDK 35 / Samsung devices) the camera
 *   HAL throws SecurityException: "Attempt to use camera from a different process
 *   than original client" when a session drain callback fires on the service's
 *   thread pool after the activity originally opened the device.
 *
 *   Fix: exactly one owner at a time. LocatorActivity owns the camera when in
 *   the foreground; it pushes processed frames through this relay. AurigaCoreService
 *   registers as a listener and receives frames without ever touching CameraX.
 *
 * Thread-safety:
 *   listeners is a CopyOnWriteArrayList — safe to add/remove from any thread.
 *   publishFrame() delivers callbacks synchronously on the calling thread
 *   (LocatorActivity's single analysisExecutor). Consumers must be fast or
 *   immediately hand off to their own thread pool.
 */
public final class FrameRelay {

    private static final FrameRelay INSTANCE = new FrameRelay();

    public static FrameRelay get() { return INSTANCE; }

    private final CopyOnWriteArrayList<AurigaInterfaces.IFrameProvider.FrameListener> listeners =
            new CopyOnWriteArrayList<>();

    /** True while LocatorActivity is in the foreground pushing frames. */
    private volatile boolean activeSource = false;

    private FrameRelay() {}

    public void markSourceActive()   { activeSource = true;  }
    public void markSourceInactive() { activeSource = false; }
    public boolean hasActiveSource() { return activeSource;  }

    public void addListener(AurigaInterfaces.IFrameProvider.FrameListener l) {
        if (l != null) listeners.addIfAbsent(l);
    }

    public void removeListener(AurigaInterfaces.IFrameProvider.FrameListener l) {
        listeners.remove(l);
    }

    /**
     * Publish a raw NV21 frame to all listeners.
     * LocatorActivity calls this after each YOLO inference cycle.
     */
    public void publishFrame(byte[] nv21, int width, int height, int rotation) {
        if (nv21 == null || listeners.isEmpty()) return;
        for (AurigaInterfaces.IFrameProvider.FrameListener l : listeners) {
            try { l.onFrame(nv21, width, height, rotation); }
            catch (Throwable ignored) {}
        }
    }

    /**
     * Convert an ARGB Bitmap to NV21 bytes then publish.
     * Used by LocatorActivity whose CameraX pipeline is configured for
     * OUTPUT_IMAGE_FORMAT_RGBA_8888 (faster colour rendering on Mali GPUs).
     */
    public void publishBitmap(Bitmap bmp, int rotation) {
        if (bmp == null || bmp.isRecycled() || listeners.isEmpty()) return;
        byte[] nv21 = bitmapToNv21(bmp);
        if (nv21 != null) publishFrame(nv21, bmp.getWidth(), bmp.getHeight(), rotation);
    }

    // ── Conversion ────────────────────────────────────────────────────────────

    /**
     * Convert ARGB_8888 Bitmap → NV21 byte array.
     * Uses the standard YUV BT.601 coefficients. Runs in ~2–5 ms for 640×480.
     */
    private static byte[] bitmapToNv21(Bitmap bmp) {
        try {
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            int[] argb = new int[w * h];
            bmp.getPixels(argb, 0, w, 0, 0, w, h);

            byte[] nv21 = new byte[w * h * 3 / 2];
            int yIndex = 0;
            int uvIndex = w * h;

            for (int j = 0; j < h; j++) {
                for (int i = 0; i < w; i++) {
                    int px = argb[j * w + i];
                    int r = (px >> 16) & 0xFF;
                    int g = (px >>  8) & 0xFF;
                    int b =  px        & 0xFF;

                    // BT.601 luma
                    int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                    nv21[yIndex++] = (byte) Math.max(0, Math.min(255, y));

                    // Chroma — written for every 2×2 block (NV21: V then U)
                    if (j % 2 == 0 && i % 2 == 0 && uvIndex + 1 < nv21.length) {
                        int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                        int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                        nv21[uvIndex++] = (byte) Math.max(0, Math.min(255, v));
                        nv21[uvIndex++] = (byte) Math.max(0, Math.min(255, u));
                    }
                }
            }
            return nv21;
        } catch (Throwable t) {
            return null;
        }
    }
}
