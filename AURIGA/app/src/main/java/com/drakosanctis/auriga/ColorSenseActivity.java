package com.drakosanctis.auriga;

import androidx.activity.ComponentActivity;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ColorSenseActivity — point-and-identify color detection.
 *
 * Competitive gap: color identification is a paid feature in Envision
 * and missing entirely from Lookout and OrCam. For blind and low-vision
 * users, knowing the color of clothing, medication bottles, food packaging,
 * and door signs is a daily necessity. This gives them that for free, offline.
 *
 * How it works:
 *   CameraX ImageAnalysis captures a YUV_420_888 frame every 600 ms.
 *   The center 80×80 pixel crop is converted to RGB, averaged to a
 *   single HSV value, and mapped to a human-readable color name.
 *   Tap anywhere (or wait in AUTO mode) to hear the spoken color.
 *
 * Example output: "Vivid blue", "Dark green", "Muted orange", "White",
 *                  "Light grey", "Black"
 */
public class ColorSenseActivity extends ComponentActivity {

    private static final String TAG              = "ColorSense";
    private static final int    SAMPLE_RADIUS_PX = 50;
    private static final long   AUTO_INTERVAL_MS = 2500;

    private PreviewView         preview;
    private TextView            colorLabel;
    private TextView            confidenceLabel;
    private Button              btnBack;
    private Button              btnAuto;

    private TextToSpeech        tts;
    private boolean             ttsReady = false;
    private boolean             autoMode = false;

    private final Handler       main     = new Handler(Looper.getMainLooper());
    private ExecutorService     exec;
    private volatile Bitmap     latestFrame;

    private String lastSpokenColor = "";

    // ── Lifecycle ─────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_color_sense);

        preview         = findViewById(R.id.color_preview);
        colorLabel      = findViewById(R.id.color_label);
        confidenceLabel = findViewById(R.id.color_sub_label);
        btnBack         = findViewById(R.id.btn_color_back);
        btnAuto         = findViewById(R.id.btn_color_auto);

        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        if (btnAuto != null) btnAuto.setOnClickListener(v -> {
            autoMode = !autoMode;
            btnAuto.setText(autoMode ? "AUTO  ON" : "AUTO  OFF");
            if (autoMode) scheduleAutoCapture();
            else          main.removeCallbacks(autoCaptureTask);
        });

        // Tap anywhere on the preview to identify color
        if (preview != null) {
            preview.setOnClickListener(v -> captureAndIdentify());
        }

        exec = Executors.newSingleThreadExecutor();

        tts = new TextToSpeech(this, status -> {
            ttsReady = (status == TextToSpeech.SUCCESS);
            if (ttsReady && tts != null) {
                tts.setLanguage(Locale.getDefault());
                tts.setSpeechRate(1.0f);
                speakWelcome();
            }
        });

        startCamera();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (autoMode) scheduleAutoCapture();
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(autoCaptureTask);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        if (exec != null) exec.shutdown();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (latestFrame != null) { latestFrame.recycle(); latestFrame = null; }
    }

    // ── Camera ────────────────────────────────────────────────────────

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider cp = future.get();
                bindCamera(cp);
            } catch (Throwable t) {
                Log.e(TAG, "Camera provider failed", t);
                Toast.makeText(this, "Camera unavailable", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCamera(ProcessCameraProvider cp) {
        Preview prev = new Preview.Builder().build();
        if (preview != null) prev.setSurfaceProvider(preview.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(480, 360))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build();
        analysis.setAnalyzer(exec, this::analyzeFrame);

        cp.unbindAll();
        try {
            cp.bindToLifecycle((LifecycleOwner) this,
                    CameraSelector.DEFAULT_BACK_CAMERA, prev, analysis);
        } catch (Throwable t) {
            Log.e(TAG, "bindToLifecycle failed", t);
        }
    }

    private void analyzeFrame(@NonNull ImageProxy image) {
        try {
            // Convert RGBA_8888 plane to Bitmap
            ImageProxy.PlaneProxy plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int w = image.getWidth();
            int h = image.getHeight();
            int rowStride  = plane.getRowStride();
            int pixStride  = plane.getPixelStride();

            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            // Row-by-row copy respecting stride
            byte[] rowBytes = new byte[rowStride];
            int[] rowPixels = new int[w];
            for (int row = 0; row < h; row++) {
                buffer.position(row * rowStride);
                buffer.get(rowBytes, 0, Math.min(rowStride, buffer.remaining()));
                for (int col = 0; col < w; col++) {
                    int base = col * pixStride;
                    int r = rowBytes[base]     & 0xFF;
                    int g = rowBytes[base + 1] & 0xFF;
                    int b = rowBytes[base + 2] & 0xFF;
                    rowPixels[col] = Color.rgb(r, g, b);
                }
                bmp.setPixels(rowPixels, 0, w, 0, row, w, 1);
            }

            Bitmap old = latestFrame;
            latestFrame = bmp;
            if (old != null) old.recycle();
        } catch (Throwable t) {
            Log.w(TAG, "Frame analysis failed", t);
        } finally {
            image.close();
        }
    }

    // ── Color identification ───────────────────────────────────────────

    private void captureAndIdentify() {
        Bitmap bmp = latestFrame;
        if (bmp == null) { speak("Camera not ready yet."); return; }

        String color = identifyColor(bmp);
        String colorUpper = color.substring(0, 1).toUpperCase(Locale.US)
                          + color.substring(1);

        main.post(() -> {
            if (colorLabel      != null) colorLabel.setText(colorUpper);
            if (confidenceLabel != null) confidenceLabel.setText("Center region");
        });

        if (!color.equals(lastSpokenColor)) {
            speak(colorUpper);
            lastSpokenColor = color;
        } else {
            speak(colorUpper + ". Same as before.");
        }
    }

    private final Runnable autoCaptureTask = new Runnable() {
        @Override
        public void run() {
            if (!autoMode) return;
            captureAndIdentify();
            main.postDelayed(this, AUTO_INTERVAL_MS);
        }
    };

    private void scheduleAutoCapture() {
        main.removeCallbacks(autoCaptureTask);
        main.postDelayed(autoCaptureTask, AUTO_INTERVAL_MS);
    }

    /**
     * Identify the dominant color of the centre crop of {@code bmp}.
     * Returns a human-readable, speakable color name string.
     */
    private String identifyColor(Bitmap bmp) {
        int cx = bmp.getWidth()  / 2;
        int cy = bmp.getHeight() / 2;
        int r  = Math.min(SAMPLE_RADIUS_PX,
                  Math.min(cx - 1, cy - 1));

        long sumR = 0, sumG = 0, sumB = 0;
        int count = 0;
        for (int y = cy - r; y <= cy + r; y++) {
            for (int x = cx - r; x <= cx + r; x++) {
                int px = bmp.getPixel(x, y);
                sumR += (px >> 16) & 0xFF;
                sumG += (px >>  8) & 0xFF;
                sumB +=  px        & 0xFF;
                count++;
            }
        }
        if (count == 0) return "unknown";

        int avgR = (int)(sumR / count);
        int avgG = (int)(sumG / count);
        int avgB = (int)(sumB / count);

        float[] hsv = new float[3];
        Color.RGBToHSV(avgR, avgG, avgB, hsv);
        float hue = hsv[0]; // 0–360
        float sat = hsv[1]; // 0–1  (0 = grey, 1 = vivid)
        float val = hsv[2]; // 0–1  (0 = black, 1 = white)

        // ── Achromatic (grey/white/black) ─────────────────────────
        if (val < 0.12f)                 return "black";
        if (val > 0.90f && sat < 0.10f) return "white";
        if (sat < 0.18f) {
            if (val < 0.35f) return "very dark grey";
            if (val < 0.55f) return "dark grey";
            if (val < 0.75f) return "grey";
            return "light grey";
        }

        // ── Brown / tan (warm low-saturation) ─────────────────────
        if (hue >= 18 && hue < 38 && sat < 0.55f && val < 0.60f)
            return val < 0.35f ? "dark brown" : "brown";

        // ── Saturation prefix ─────────────────────────────────────
        String satPfx = sat > 0.75f ? "vivid "
                      : sat < 0.30f ? "muted " : "";

        // ── Brightness prefix ─────────────────────────────────────
        String valPfx = val < 0.30f ? "very dark "
                      : val < 0.50f ? "dark "
                      : val > 0.90f ? "very bright "
                      : val > 0.75f ? "bright "
                      : "";
        // Combine (avoid double-prefix collisions)
        String prefix = satPfx.isEmpty() ? valPfx : satPfx;

        // ── Hue-to-name ───────────────────────────────────────────
        String name;
        if      (hue <  12 || hue >= 348) name = "red";
        else if (hue <  25)               name = "red-orange";
        else if (hue <  40)               name = "orange";
        else if (hue <  58)               name = "yellow";
        else if (hue <  80)               name = "yellow-green";
        else if (hue < 150)               name = "green";
        else if (hue < 190)               name = "teal";
        else if (hue < 210)               name = "cyan";
        else if (hue < 255)               name = "blue";
        else if (hue < 275)               name = "indigo";
        else if (hue < 300)               name = "purple";
        else if (hue < 330)               name = "magenta";
        else                              name = "pink";

        return prefix + name;
    }

    // ── TTS helpers ───────────────────────────────────────────────────

    private void speakWelcome() {
        speak("Color Sense ready. Point the camera at any object and tap the screen to identify its color.");
    }

    private void speak(String text) {
        if (!ttsReady || tts == null || text == null || text.isEmpty()) return;
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null,
                "cs_" + System.currentTimeMillis());
    }
}
