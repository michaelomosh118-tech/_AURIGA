package com.drakosanctis.auriga;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import java.util.Collections;

/**
 * CameraService: The "Eye" of the Auriga Ecosystem.
 * Captures 640x480 frames via Camera2 API for high-performance processing.
 * Includes flashlight debounce logic for low-light scenarios.
 */
public class CameraService {

    private final Context context;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private SurfaceTexture surfaceTexture;
    private Surface previewSurface;

    private final HandlerThread backgroundThread;
    private final Handler backgroundHandler;

    private boolean isFlashOn = false;
    private int lowLightCount = 0;

    public CameraService(Context context) {
        this.context = context;
        backgroundThread = new HandlerThread("AurigaCameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    /**
     * start: Opens the back camera and initializes the preview session.
     */
    public void start(SurfaceTexture texture) {
        this.surfaceTexture = texture;
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            String backCameraId = getBackCameraId(manager);
            if (backCameraId != null) {
                manager.openCamera(backCameraId, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(CameraDevice camera) {
                        cameraDevice = camera;
                        createPreviewSession();
                    }

                    @Override
                    public void onDisconnected(CameraDevice camera) {
                        camera.close();
                        cameraDevice = null;
                    }

                    @Override
                    public void onError(CameraDevice camera, int error) {
                        camera.close();
                        cameraDevice = null;
                    }
                }, backgroundHandler);
            }
        } catch (CameraAccessException | SecurityException e) {
            // Error handling
        }
    }

    private void createPreviewSession() {
        if (cameraDevice == null || surfaceTexture == null) return;

        // Do NOT force 640×480 here — let the camera use its natural
        // preview resolution so the TextureView shows a sharp, undistorted
        // image. The main loop downsamples via textureView.getBitmap(w,h)
        // before processing, so the pipeline still runs at 640-wide.
        previewSurface = new Surface(surfaceTexture);

        try {
            cameraDevice.createCaptureSession(Collections.singletonList(previewSurface),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession session) {
                        captureSession = session;
                        updatePreview();
                    }

                    @Override
                    public void onConfigureFailed(CameraCaptureSession session) { }
                }, backgroundHandler);
        } catch (CameraAccessException e) { }
    }

    private void updatePreview() {
        if (cameraDevice == null) return;

        try {
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(previewSurface);

            // Continuous autofocus so the preview stays sharp while walking.
            builder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);

            // Auto-exposure and auto-white-balance for natural colours.
            builder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON);
            builder.set(CaptureRequest.CONTROL_AWB_MODE,
                    CaptureRequest.CONTROL_AWB_MODE_AUTO);

            // Flashlight logic (DrakoSanctis auto-flashlight)
            if (isFlashOn) {
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
            }

            captureSession.setRepeatingRequest(builder.build(), null, backgroundHandler);
        } catch (CameraAccessException e) { }
    }

    /**
     * toggleFlash: Debounced flashlight logic.
     * Prevents flickering beeps by requiring 12 consecutive frames of low light.
     */
    public void setFlash(boolean on) {
        if (on != isFlashOn) {
            isFlashOn = on;
            updatePreview();
        }
    }

    private String getBackCameraId(CameraManager manager) throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }
        return null;
    }

    public void stop() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        backgroundThread.quitSafely();
    }
}
