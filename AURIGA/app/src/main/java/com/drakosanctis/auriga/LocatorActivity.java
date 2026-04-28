package com.drakosanctis.auriga;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native, on-device replacement for {@link LocatorWebActivity}.
 *
 * <p>This is Phase 1 of the scaling strategy ("Move Target Locator
 * from WebView → native"): it runs YOLOv8n through TensorFlow Lite
 * on the device, paints bounding boxes via {@link LocatorOverlayView},
 * and announces the prominent in-frame target through Android's
 * built-in {@link TextToSpeech} engine. No JS bridge, no WebView,
 * no third-party cloud.
 *
 * <h3>Frame loop</h3>
 *
 * <ul>
 *   <li>{@link androidx.camera.core.Preview} drives the visible
 *       viewport at native preview rate.</li>
 *   <li>A separate {@link ImageAnalysis} use-case throttled to
 *       {@link #ANALYSIS_INTERVAL_MS} pulls one YUV frame at a
 *       time, converts to ARGB via {@link ImageProxy#toBitmap()},
 *       rotates to upright, and feeds it to {@link YoloDetector}.</li>
 *   <li>The activity picks the detection closest to the centre of
 *       the frame whose label is in {@link TargetStore}'s active
 *       set, then -- subject to a debounce -- speaks
 *       {{label}}, {{bearing}}, {{rough distance proxy}}.</li>
 *   <li>Box list + chosen target are pushed back to the overlay
 *       on the UI thread.</li>
 * </ul>
 *
 * <h3>Graceful degradation</h3>
 *
 * <p>If the bundled YOLO model is missing (no {@code .tflite} in
 * {@code assets/}), {@link YoloDetector#tryCreate(Context)} returns
 * null, the camera pipeline never starts, and the user sees a
 * "model not bundled" panel with a one-tap fallback to the legacy
 * {@link LocatorWebActivity}. That keeps the APK installable even
 * before the model file lands in CI.
 *
 * <p>The drawer wiring, mute toggles, voice/haptic preference
 * persistence, calibration-walk gate and back-key behaviour all
 * mirror {@link LocatorWebActivity} so users see no functional
 * regression at the menu level.
 */
public class LocatorActivity extends ComponentActivity {

    private static final String TAG = "LocatorActivity";
    private static final int CAMERA_PERMISSION_REQUEST = 1702;

    /** Same SharedPreferences keys the WebView locator used. */
    private static final String PREF_LOCATOR_VOICE = "locator_web_voice_enabled";
    private static final String PREF_LOCATOR_HAPTIC = "locator_web_haptic_enabled";

    /** One inference per ~333 ms (≈3 fps) -- plenty for a guidance
     *  HUD and keeps battery + thermal load reasonable. */
    private static final long ANALYSIS_INTERVAL_MS = 333L;

    /** Don't speak the same label more often than this. */
    private static final long SPEECH_COOLDOWN_MS = 2200L;

    private DrawerLayout drawerLayout;
    private PreviewView previewView;
    private LocatorOverlayView overlayView;
    private LinearLayout modelMissingPanel;

    private TextView voiceSub;
    private TextView hapticSub;

    private boolean voiceEnabled = true;
    private boolean hapticEnabled = true;

    private YoloDetector detector;
    private ExecutorService analysisExecutor;
    private TextToSpeech tts;
    private boolean ttsReady = false;

    private HapticManager haptic;

    private long lastSpokenAt = 0L;
    private String lastSpokenLabel = "";
    private long lastAnalysedAt = 0L;

    private Set<String> activeTargets = Collections.emptySet();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences(
                MainActivity.PREFS_NAME, MODE_PRIVATE);
        voiceEnabled = prefs.getBoolean(PREF_LOCATOR_VOICE, true);
        hapticEnabled = prefs.getBoolean(PREF_LOCATOR_HAPTIC, true);

        setContentView(R.layout.activity_locator);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        drawerLayout = findViewById(R.id.drawer_layout);
        previewView = findViewById(R.id.locator_preview);
        overlayView = findViewById(R.id.locator_overlay);
        modelMissingPanel = findViewById(R.id.locator_model_missing);

        wireDrawer();
        wireMenuToggle();
        wireWebViewFallbackButton();

        // Try to load the YOLO model. tryCreate() returns null when
        // no .tflite asset is bundled; we surface that via the
        // amber "model not bundled" panel rather than crashing.
        try {
            detector = YoloDetector.tryCreate(this);
        } catch (Throwable t) {
            Log.e(TAG, "YOLO detector init blew up", t);
            detector = null;
            Toast.makeText(this,
                    "YOLO load error: " + t.getMessage(),
                    Toast.LENGTH_LONG).show();
        }

        if (detector == null) {
            modelMissingPanel.setVisibility(View.VISIBLE);
            overlayView.setModelReady(false);
            overlayView.setStatus("MODEL NOT BUNDLED — TAP MENU FOR FALLBACK");
            return;
        }

        modelMissingPanel.setVisibility(View.GONE);
        overlayView.setModelReady(false);
        overlayView.setStatus("INITIALISING CAMERA…");

        haptic = new HapticManager(this);
        analysisExecutor = Executors.newSingleThreadExecutor();
        initTts();
        activeTargets = TargetStore.read(this);

        ensureCameraPermissionAndStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Pick up changes the user made in TargetsActivity.
        try {
            activeTargets = TargetStore.read(this);
        } catch (Throwable t) {
            Log.w(TAG, "TargetStore reload failed", t);
        }
        refreshFeedbackGate(findViewById(R.id.nav_feedback),
                findViewById(R.id.nav_feedback_hint));
    }

    @Override
    protected void onDestroy() {
        if (analysisExecutor != null) analysisExecutor.shutdown();
        if (detector != null) {
            try { detector.close(); } catch (Throwable ignored) {}
        }
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Throwable ignored) {}
        }
        if (haptic != null) {
            try { haptic.stop(); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }

    // ─── Camera ────────────────────────────────────────────────────

    private void ensureCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != CAMERA_PERMISSION_REQUEST) return;
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            startCamera();
        } else {
            overlayView.setStatus("CAMERA PERMISSION REQUIRED");
            Toast.makeText(this,
                    "Object Locator needs the camera to detect objects.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                bindCameraUseCases(provider);
                overlayView.setModelReady(true);
                overlayView.setStatus("");
            } catch (Throwable t) {
                Log.e(TAG, "Camera bind failed", t);
                overlayView.setStatus("CAMERA UNAVAILABLE: " + t.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases(ProcessCameraProvider provider) {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                // Force RGBA_8888 output so ImageProxy.toBitmap() returns
                // a directly-usable ARGB bitmap. Without this, CameraX
                // hands us YUV_420_888 and toBitmap() does an implicit
                // chroma conversion every frame -- works, but slower
                // and historically the source of subtle colour-channel
                // bugs (red and blue swapped on certain Mali GPUs).
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build();
        analysis.setAnalyzer(analysisExecutor, new YoloAnalyzer());

        CameraSelector selector = CameraSelector.DEFAULT_BACK_CAMERA;
        provider.unbindAll();
        // ComponentActivity is a LifecycleOwner -- CameraX will
        // unbind the analyser automatically when the activity is
        // destroyed, so we don't need to do it manually here.
        provider.bindToLifecycle(this, selector, preview, analysis);
    }

    /**
     * Throttled YOLOv8 analyser. Runs on the
     * {@link #analysisExecutor} (single-threaded by design --
     * {@link YoloDetector} is not re-entrant).
     */
    private final class YoloAnalyzer implements ImageAnalysis.Analyzer {
        @Override
        public void analyze(@NonNull ImageProxy image) {
            try {
                long now = SystemClock.uptimeMillis();
                if (now - lastAnalysedAt < ANALYSIS_INTERVAL_MS) {
                    return;
                }
                lastAnalysedAt = now;

                Bitmap bmp = imageProxyToUprightBitmap(image);
                if (bmp == null) return;

                List<Detection> dets = detector.detect(bmp);
                List<Detection> filtered = filterByTargets(dets);
                Detection target = pickPrimaryTarget(filtered);

                final List<Detection> uiDets = dets;
                final Detection uiTarget = target;
                mainHandler.post(() -> {
                    overlayView.setDetections(uiDets, uiTarget);
                    if (uiTarget != null) {
                        overlayView.setStatus(buildStatusLine(uiTarget));
                        announceTarget(uiTarget);
                    } else if (uiDets.isEmpty()) {
                        overlayView.setStatus("NO TARGETS IN VIEW");
                    } else {
                        overlayView.setStatus(uiDets.size()
                                + " OBJECT" + (uiDets.size() == 1 ? "" : "S")
                                + " — NONE MATCH FILTER");
                    }
                });

                bmp.recycle();
            } catch (Throwable t) {
                Log.e(TAG, "Analyzer failure", t);
            } finally {
                image.close();
            }
        }
    }

    /**
     * Convert a CameraX {@link ImageProxy} to an upright ARGB
     * {@link Bitmap}. Uses the platform helper added in CameraX
     * 1.3+, then applies the rotation reported by the proxy so
     * the model sees a sensor-orientation-corrected frame.
     */
    private static Bitmap imageProxyToUprightBitmap(ImageProxy image) {
        Bitmap raw;
        try {
            raw = image.toBitmap();
        } catch (Throwable t) {
            Log.e(TAG, "ImageProxy.toBitmap() failed", t);
            return null;
        }
        int rot = image.getImageInfo().getRotationDegrees();
        if (rot == 0) return raw;
        Matrix m = new Matrix();
        m.postRotate(rot);
        Bitmap rotated = Bitmap.createBitmap(raw, 0, 0,
                raw.getWidth(), raw.getHeight(), m, true);
        if (rotated != raw) raw.recycle();
        return rotated;
    }

    private List<Detection> filterByTargets(List<Detection> all) {
        if (all.isEmpty()) return all;
        if (activeTargets == null
                || activeTargets.isEmpty()
                || activeTargets.contains(TargetStore.CATEGORY_ANY)) {
            return all;
        }
        ArrayList<Detection> kept = new ArrayList<>();
        for (Detection d : all) {
            if (TargetStore.matches(activeTargets, d.label)) {
                kept.add(d);
            }
        }
        return kept;
    }

    /**
     * Pick the most "centred and prominent" detection. Score is
     * {@code area * (1 - distFromCentre)} so big boxes near the
     * crosshair beat small distant ones, but a small object
     * dead-centre still wins over a large object at the edge.
     */
    private Detection pickPrimaryTarget(List<Detection> dets) {
        Detection best = null;
        float bestScore = -1f;
        for (Detection d : dets) {
            float dx = d.centerX() - 0.5f;
            float dy = d.centerY() - 0.5f;
            float distFromCentre = (float) Math.sqrt(dx * dx + dy * dy);
            float score = d.area() * (1f - Math.min(0.99f, distFromCentre));
            if (score > bestScore) {
                bestScore = score;
                best = d;
            }
        }
        return best;
    }

    private static String buildStatusLine(Detection d) {
        String bearing = bearingFor(d.centerX());
        return d.label.toUpperCase()
                + " · " + bearing
                + " · " + Math.round(d.confidence * 100) + "%";
    }

    private static String bearingFor(float normX) {
        if (normX < 0.33f) return "LEFT";
        if (normX > 0.67f) return "RIGHT";
        return "CENTRE";
    }

    /**
     * Announce the target via TTS, with a per-label cooldown so
     * the same chair doesn't get re-spoken every 333 ms. Also
     * fires a short haptic pulse if haptic is enabled.
     */
    private void announceTarget(Detection d) {
        if (hapticEnabled && haptic != null) {
            try { haptic.pulse(0.7f); } catch (Throwable ignored) {}
        }
        if (!voiceEnabled || !ttsReady || tts == null) return;

        long now = SystemClock.uptimeMillis();
        boolean sameAsLast = d.label.equalsIgnoreCase(lastSpokenLabel);
        if (sameAsLast && now - lastSpokenAt < SPEECH_COOLDOWN_MS) return;
        if (!sameAsLast && now - lastSpokenAt < 700L) return;

        String utterance = String.format(Locale.US,
                "%s, %s",
                d.label, bearingFor(d.centerX()).toLowerCase(Locale.US));
        tts.speak(utterance, TextToSpeech.QUEUE_FLUSH, null,
                "auriga_locator_" + now);
        lastSpokenAt = now;
        lastSpokenLabel = d.label;
    }

    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            ttsReady = (status == TextToSpeech.SUCCESS);
            if (ttsReady && tts != null) {
                tts.setLanguage(Locale.getDefault());
                tts.setSpeechRate(1.05f);
            } else {
                Log.w(TAG, "TTS init failed: " + status);
            }
        });
    }

    // ─── Drawer wiring (mirrors LocatorWebActivity) ───────────────

    private void wireMenuToggle() {
        Button menu = findViewById(R.id.menu_toggle);
        if (menu != null) {
            menu.setOnClickListener(v -> {
                if (drawerLayout != null) {
                    drawerLayout.openDrawer(Gravity.START);
                }
            });
        }
    }

    private void wireWebViewFallbackButton() {
        Button fallback = findViewById(R.id.locator_open_webview);
        if (fallback != null) {
            fallback.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(this, LocatorWebActivity.class));
                } catch (Throwable t) {
                    Toast.makeText(this,
                            "WebView locator unavailable: " + t.getMessage(),
                            Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    private void wireDrawer() {
        // ── NAVIGATE ──────────────────────────────────────────────
        View navHome = findViewById(R.id.nav_home);
        if (navHome != null) navHome.setOnClickListener(v -> closeDrawer());

        View navReader = findViewById(R.id.nav_reader);
        if (navReader != null) navReader.setOnClickListener(v -> {
            closeDrawer();
            safeStart(ReaderActivity.class, "DrakoVoice Reader");
        });

        View navTargets = findViewById(R.id.nav_targets);
        if (navTargets != null) navTargets.setOnClickListener(v -> {
            closeDrawer();
            safeStart(TargetsActivity.class, "Targets");
        });

        View navAbout = findViewById(R.id.nav_about);
        if (navAbout != null) navAbout.setOnClickListener(v -> {
            closeDrawer();
            safeStart(AboutActivity.class, "About");
        });

        // ── SETUP ─────────────────────────────────────────────────
        View navCalibrate = findViewById(R.id.nav_calibrate);
        if (navCalibrate != null) navCalibrate.setOnClickListener(v -> {
            closeDrawer();
            safeStart(CalibrationWalkActivity.class, "Calibration Walk");
        });

        View navFeedback = findViewById(R.id.nav_feedback);
        if (navFeedback != null) navFeedback.setOnClickListener(v -> {
            closeDrawer();
            safeStart(FeedbackActivity.class, "Feedback");
        });
        refreshFeedbackGate(navFeedback, findViewById(R.id.nav_feedback_hint));

        // ── VOICE / HAPTIC TOGGLES ───────────────────────────────
        View navVoice = findViewById(R.id.nav_voice_locator);
        voiceSub = findViewById(R.id.nav_voice_locator_sub);
        if (navVoice != null) {
            navVoice.setOnClickListener(v -> {
                voiceEnabled = !voiceEnabled;
                getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
                        .edit().putBoolean(PREF_LOCATOR_VOICE, voiceEnabled).apply();
                if (!voiceEnabled && tts != null) {
                    try { tts.stop(); } catch (Throwable ignored) {}
                }
                refreshMuteLabels();
                Toast.makeText(this,
                        voiceEnabled ? "Voice ON" : "Voice MUTED",
                        Toast.LENGTH_SHORT).show();
            });
        }

        View navHaptic = findViewById(R.id.nav_haptic_locator);
        hapticSub = findViewById(R.id.nav_haptic_locator_sub);
        if (navHaptic != null) {
            navHaptic.setOnClickListener(v -> {
                hapticEnabled = !hapticEnabled;
                getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
                        .edit().putBoolean(PREF_LOCATOR_HAPTIC, hapticEnabled).apply();
                refreshMuteLabels();
                Toast.makeText(this,
                        hapticEnabled ? "Haptic ON" : "Haptic MUTED",
                        Toast.LENGTH_SHORT).show();
            });
        }
        refreshMuteLabels();

        // ── SUPPORT / CONTRIBUTE ─────────────────────────────────
        View navHelp = findViewById(R.id.nav_help);
        if (navHelp != null) navHelp.setOnClickListener(v -> {
            closeDrawer();
            safeStart(HelpActivity.class, "Help");
        });

        View navSupport = findViewById(R.id.nav_support);
        if (navSupport != null) navSupport.setOnClickListener(v -> {
            closeDrawer();
            safeStart(SupportActivity.class, "Support");
        });

        View navContributeCalibration = findViewById(R.id.nav_contribute_calibration);
        if (navContributeCalibration != null) {
            navContributeCalibration.setOnClickListener(v -> {
                closeDrawer();
                safeStart(ContributeActivity.class, "Contribute");
            });
        }

        View navContributeSdk = findViewById(R.id.nav_contribute_sdk);
        if (navContributeSdk != null) {
            navContributeSdk.setOnClickListener(v -> {
                closeDrawer();
                safeStart(ContributeActivity.class, "Contribute");
            });
        }
    }

    private void refreshMuteLabels() {
        if (voiceSub != null) {
            voiceSub.setText(voiceEnabled ? "ON · tap to mute" : "MUTED · tap to enable");
        }
        if (hapticSub != null) {
            hapticSub.setText(hapticEnabled ? "ON · tap to mute" : "MUTED · tap to enable");
        }
    }

    private void refreshFeedbackGate(View feedbackRow, View hint) {
        try {
            SharedPreferences prefs = getSharedPreferences(
                    MainActivity.PREFS_NAME, MODE_PRIVATE);
            boolean walkDone = prefs.getBoolean(
                    CalibrationWalkActivity.PREF_WALK_DONE, false);
            if (hint != null) hint.setVisibility(walkDone ? View.GONE : View.VISIBLE);
            if (feedbackRow != null) feedbackRow.setAlpha(walkDone ? 1f : 0.55f);
        } catch (Throwable ignored) { /* row stays tappable either way */ }
    }

    private void closeDrawer() {
        if (drawerLayout != null && drawerLayout.isDrawerOpen(Gravity.START)) {
            drawerLayout.closeDrawer(Gravity.START);
        }
    }

    private void safeStart(Class<?> target, String label) {
        try {
            startActivity(new Intent(this, target));
        } catch (Throwable t) {
            Toast.makeText(this,
                    label + " unavailable: " + t.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout != null && drawerLayout.isDrawerOpen(Gravity.START)) {
            drawerLayout.closeDrawer(Gravity.START);
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK
                && drawerLayout != null
                && drawerLayout.isDrawerOpen(Gravity.START)) {
            drawerLayout.closeDrawer(Gravity.START);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

}
