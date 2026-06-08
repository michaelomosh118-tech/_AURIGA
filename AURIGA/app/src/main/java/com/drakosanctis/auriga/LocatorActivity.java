package com.drakosanctis.auriga;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import android.view.MotionEvent;
import androidx.camera.core.Camera;
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
    private static final int MIC_PERMISSION_REQUEST    = 1703;
    private static final int VOICE_SETUP_REQUEST       = 1704;

    /** Same SharedPreferences keys the WebView locator used. */
    private static final String PREF_LOCATOR_VOICE  = "locator_web_voice_enabled";
    private static final String PREF_LOCATOR_HAPTIC = "locator_web_haptic_enabled";
    private static final String PREF_SMART_LIGHT    = "locator_smart_light_enabled";

    /**
     * Average pixel luminance (0–255) below which the analyser considers
     * the scene "dark" and automatically fires a torch flash even when
     * smart-light mode is in AUTO state. Tune lower to flash less often.
     */
    private static final int DARK_THRESHOLD = 55;

    /** How long (ms) to leave the torch on so the sensor stabilises
     *  before we grab the inference frame. */
    private static final long TORCH_SETTLE_MS = 130;

    /** One inference per ~333 ms (≈3 fps) -- plenty for a guidance
     *  HUD and keeps battery + thermal load reasonable. */
    private static final long ANALYSIS_INTERVAL_MS = 333L;

    /** Don't speak the same label more often than this. */
    private static final long SPEECH_COOLDOWN_MS = 2200L;

    // ── Distance / bearing constants ────────────────────────────────────
    /**
     * Horizontal field of view assumed for the rear camera.
     * Matches the web PWA (locator.html FOV_DEGREES = 60).
     * Bearing (°) = (normCentreX − 0.5) × FOV_DEGREES.
     * Positive → right of centre; negative → left.
     */
    private static final float FOV_DEGREES = 60f;

    /**
     * Empirical constant used by the web PWA distance formula:
     *   distance_m = REFERENCE_CONSTANT / (normHeight × 100)
     * Larger bounding-box height → object is closer.
     * Matches locator.html REFERENCE_CONSTANT = 100.
     */
    private static final float REFERENCE_CONSTANT = 100f;

    private DrawerLayout drawerLayout;
    private PreviewView previewView;
    private LocatorOverlayView overlayView;
    private LinearLayout modelMissingPanel;

    private TextView voiceSub;
    private TextView hapticSub;
    private TextView smartLightSub;

    private boolean          smartLightEnabled = false;
    private Camera           cameraRef         = null;
    /**
     * Two-phase torch flag. Set to {@code true} in Phase 1 (dark scene
     * detected, torch fired). Cleared in Phase 2 (next fresh frame
     * captured under torch light, inference runs, torch killed).
     */
    private volatile boolean torchPrimed       = false;

    private boolean voiceEnabled = true;
    private boolean hapticEnabled = true;

    private YoloDetector detector;
    private ExecutorService analysisExecutor;
    private TextToSpeech tts;
    private boolean ttsReady = false;

    private HapticManager haptic;

    // ── SIE (Sensory Independent Engine) ────────────────────────────
    private HardwareHAL           hal;
    private FiducialLUT           lut;
    private TriangulationEngine   triangulation;
    private DracoAIDEngine        dracoAID;

    // Frame dimensions updated each inference cycle so helpers have them.
    private volatile int lastFrameWidth  = 640;
    private volatile int lastFrameHeight = 480;

    // Short confirmation beep played when the camera locks on target.
    private ToneGenerator lockOnBeep;

    private long lastSpokenAt = 0L;
    private String lastSpokenLabel = "";
    private long lastAnalysedAt = 0L;

    private Set<String> activeTargets = Collections.emptySet();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── Voice navigation ───────────────────────────────────────────
    private AurigaVoiceEngine        voiceEngine;
    private SerpentineGestureDetector serpentine;
    private TextView                 voiceTranscript;
    private Button                   voiceMicFab;

    private final BroadcastReceiver wakeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (AurigaVoiceEngine.ACTION_WAKE_WORD.equals(intent.getAction())
                    && voiceEngine != null) {
                voiceEngine.startListening();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences(
                MainActivity.PREFS_NAME, MODE_PRIVATE);
        voiceEnabled      = prefs.getBoolean(PREF_LOCATOR_VOICE,  true);
        hapticEnabled     = prefs.getBoolean(PREF_LOCATOR_HAPTIC, true);
        smartLightEnabled = prefs.getBoolean(PREF_SMART_LIGHT,    false);

        setContentView(R.layout.activity_locator);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        drawerLayout = findViewById(R.id.drawer_layout);
        previewView = findViewById(R.id.locator_preview);
        overlayView = findViewById(R.id.locator_overlay);
        modelMissingPanel = findViewById(R.id.locator_model_missing);

        wireDrawer();
        wireMenuToggle();
        wireWebViewFallbackButton();
        initVoiceNavigation();

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

        // ── SIE init ─────────────────────────────────────────────
        hal          = new HardwareHAL(this);
        lut          = new FiducialLUT();
        triangulation = new TriangulationEngine(lut, hal);
        dracoAID     = new DracoAIDEngine(lut, hal);
        // If DracoAID already has a stored H_c from a previous session,
        // generate the dynamic LUT immediately so the first inference
        // frame gets accurate distances without waiting for a person.
        float storedHc = hal.loadStoredHc();
        if (storedHc > 0f) {
            float focalPx = hal.getFocalLengthPx(lastFrameWidth);
            lut.generateDynamicTable(storedHc, focalPx, lastFrameHeight);
            Log.d(TAG, "SIE: restored dynamic LUT from stored H_c=" + storedHc);
        }
        try {
            lockOnBeep = new ToneGenerator(AudioManager.STREAM_MUSIC, 65);
        } catch (Throwable ignored) { }

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
        if (voiceEngine != null) voiceEngine.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (voiceEngine != null) voiceEngine.onPause();
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
        if (hal != null) {
            try { hal.stopPitchSensor(); } catch (Throwable ignored) {}
        }
        if (lockOnBeep != null) {
            try { lockOnBeep.release(); } catch (Throwable ignored) {}
        }
        if (voiceEngine != null) {
            try { voiceEngine.shutdown(); } catch (Throwable ignored) {}
        }
        try { unregisterReceiver(wakeReceiver); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VOICE_SETUP_REQUEST) {
            // Re-init engine so it picks up the new assistant name.
            if (voiceEngine != null) {
                try { voiceEngine.shutdown(); } catch (Throwable ignored) {}
            }
            voiceEngine = buildVoiceEngine();
        }
    }

    // ─── Voice navigation init ─────────────────────────────────────

    /**
     * Initialises all voice-navigation components:
     *   1. Finds the mic FAB + transcript views from the layout.
     *   2. Wires the mic FAB click and the serpentine gesture to the engine.
     *   3. Registers the wake-word broadcast receiver.
     *   4. Starts the always-on {@link AurigaVoiceService}.
     *   5. If first-run (no name set), launches {@link VoiceSetupActivity}.
     *   6. Requests RECORD_AUDIO permission if not yet granted.
     *
     * Safe to call before the camera has been set up.
     */
    private void initVoiceNavigation() {
        voiceTranscript = findViewById(R.id.voice_transcript);
        voiceMicFab     = findViewById(R.id.voice_mic_fab);

        voiceEngine = buildVoiceEngine();

        // Serpentine gesture — detector is created here; events are fed
        // via dispatchTouchEvent() at the activity level so it always sees
        // all MOVE/UP events regardless of which child view consumes them.
        serpentine = new SerpentineGestureDetector(this,
                () -> { if (voiceEngine != null) voiceEngine.startListening(); });

        // Long-press on the locator frame as a second activation path.
        FrameLayout locatorFrame = findViewById(R.id.locator_frame);
        if (locatorFrame != null) {
            voiceEngine.attachLongPressToView(locatorFrame);
        }

        // Mic FAB tap.
        if (voiceMicFab != null) {
            voiceMicFab.setOnClickListener(v -> {
                if (voiceEngine != null) voiceEngine.startListening();
            });
        }

        // Wake-word broadcast from AurigaVoiceService.
        IntentFilter filter = new IntentFilter(AurigaVoiceEngine.ACTION_WAKE_WORD);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wakeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(wakeReceiver, filter);
        }

        // First-run: launch name-setup if not yet done.
        if (!AurigaVoiceEngine.isSetupDone(this)) {
            //noinspection deprecation
            startActivityForResult(
                    new Intent(this, VoiceSetupActivity.class),
                    VOICE_SETUP_REQUEST);
        }

        // Request RECORD_AUDIO and (on success) start the wake-word service.
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            AurigaVoiceService.startListening(this);
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    MIC_PERMISSION_REQUEST);
        }
    }

    /** Build (or re-build) the AurigaVoiceEngine with a live Listener. */
    private AurigaVoiceEngine buildVoiceEngine() {
        return new AurigaVoiceEngine(this, new AurigaVoiceEngine.Listener() {
            @Override
            public void onListeningStarted() {
                if (voiceMicFab != null)     voiceMicFab.setText("●");
                if (voiceTranscript != null) {
                    voiceTranscript.setText("Listening…");
                    voiceTranscript.setVisibility(View.VISIBLE);
                }
            }
            @Override
            public void onListeningStopped() {
                if (voiceMicFab != null) voiceMicFab.setText("MIC");
                mainHandler.postDelayed(() -> {
                    if (voiceTranscript != null)
                        voiceTranscript.setVisibility(View.GONE);
                }, 1800);
            }
            @Override
            public void onTranscript(String text) {
                if (voiceTranscript != null) {
                    voiceTranscript.setText(text);
                    voiceTranscript.setVisibility(View.VISIBLE);
                }
            }
            @Override
            public void onOpenDrawer()  {
                if (drawerLayout != null) drawerLayout.openDrawer(Gravity.START);
            }
            @Override
            public void onCloseDrawer() { closeDrawer(); }
            @Override
            public void onGoBack()      { onBackPressed(); }
            @Override
            public void onDescribePage() {
                if (voiceEngine != null)
                    voiceEngine.speak("You are on the Object Locator. "
                            + "The camera is scanning for objects matching your targets. "
                            + "Say open menu for navigation options.");
            }
        });
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
        if (requestCode == MIC_PERMISSION_REQUEST) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                AurigaVoiceService.startListening(this);
            }
            return;
        }
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
        cameraRef = provider.bindToLifecycle(this, selector, preview, analysis);
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

                // ── Smart Lighting (two-phase) ────────────────────────
                // Phase 1 (torchPrimed == false):
                //   Probe the current frame brightness. If dark, fire the
                //   torch and return — do NOT run inference on this stale
                //   dark frame. The torch warms up between cycles.
                // Phase 2 (torchPrimed == true):
                //   This frame was captured while the torch was already on.
                //   Run inference on the well-lit frame, then kill the torch.
                // Net result: the inference always sees a lit frame, never
                // the dark frame that triggered the flash.
                if (smartLightEnabled && cameraRef != null) {
                    if (!torchPrimed) {
                        Bitmap probe = imageProxyToUprightBitmap(image);
                        boolean isDark = probe == null
                                || computeAvgBrightness(probe) < DARK_THRESHOLD;
                        if (probe != null) probe.recycle();
                        if (isDark) {
                            try {
                                cameraRef.getCameraControl().enableTorch(true);
                            } catch (Throwable ignored) {}
                            torchPrimed = true;
                            return; // wait for next fresh frame under torch light
                        }
                        // Scene is bright — fall through to normal inference.
                    }
                    // torchPrimed == true: torch has been on since last cycle;
                    // inference below will use this lit frame, then torch off.
                }

                // ── Inference ────────────────────────────────────────
                Bitmap bmp = imageProxyToUprightBitmap(image);
                // If torch was primed, this lit frame is now in memory — kill torch.
                if (torchPrimed) {
                    tryDisableTorch();
                    torchPrimed = false;
                }
                if (bmp == null) return;

                final int fW = bmp.getWidth();
                final int fH = bmp.getHeight();
                lastFrameWidth  = fW;
                lastFrameHeight = fH;

                List<Detection> dets = detector.detect(bmp);

                // ── DracoAID auto-calibration ─────────────────────
                if (dracoAID != null) {
                    dracoAID.processFrame(dets, fW, fH);
                }

                List<Detection> filtered = filterByTargets(dets);
                Detection target = pickPrimaryTarget(filtered);

                // ── SIE spatial solve for the primary target ──────
                final TriangulationEngine.SpatialOutput spatialOut =
                        (target != null && triangulation != null)
                                ? computeSpatialOutput(target, fW, fH)
                                : null;

                final List<Detection> uiDets = dets;
                final Detection uiTarget = target;
                mainHandler.post(() -> {
                    overlayView.setDetections(uiDets, uiTarget);
                    if (uiTarget != null) {
                        overlayView.setStatus(buildStatusLine(uiTarget, spatialOut, fH));
                        announceTarget(uiTarget, spatialOut, fH);
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

    // ─── Smart Lighting helpers ────────────────────────────────────

    /**
     * Samples every 8th pixel of {@code bmp} (R, G, B channels) and
     * returns the average luminance in the range [0, 255]. Cheap enough
     * to run on the analysis thread before the YOLO inference pass.
     */
    private static int computeAvgBrightness(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        long sum = 0;
        int  count = 0;
        for (int y = 0; y < h; y += 8) {
            for (int x = 0; x < w; x += 8) {
                int px = bmp.getPixel(x, y);
                int r = (px >> 16) & 0xFF;
                int g = (px >>  8) & 0xFF;
                int b =  px        & 0xFF;
                // Perceptual luminance (ITU-R BT.601 coefficients)
                sum += (r * 299 + g * 587 + b * 114) / 1000;
                count++;
            }
        }
        return count == 0 ? 255 : (int)(sum / count);
    }

    /** Turn the torch off via CameraX; swallows all exceptions so the
     *  analysis loop is never interrupted by a torch failure. */
    private void tryDisableTorch() {
        try {
            if (cameraRef != null)
                cameraRef.getCameraControl().enableTorch(false);
        } catch (Throwable ignored) {}
    }

    // ─── Camera ────────────────────────────────────────────────────

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

    // ─── SIE spatial solve ────────────────────────────────────────

    /**
     * Run the SIE pipeline for one detection:
     *   • SkyShield: bounding-box bottom in the upper 42 % of the frame
     *     → treat as suspended overhang; use calculateSuspendedHeight.
     *   • TruePath:  everything else → ground-plane triangulation.
     *
     * Falls back to the legacy REFERENCE_CONSTANT formula if the LUT
     * is still running on synthetic defaults (no DracoAID commit yet).
     */
    private TriangulationEngine.SpatialOutput computeSpatialOutput(
            Detection d, int fW, int fH) {
        triangulation.setFrameSize(fW, fH);
        int   baseY  = (int)(d.box.bottom * fH);
        int   centX  = (int)(d.centerX()  * fW);
        float widthPx = (d.box.right - d.box.left) * fW;

        boolean suspended = baseY < fH * 0.42f;
        try {
            if (suspended) {
                return triangulation.calculateSuspendedHeight(baseY, centX);
            } else {
                return triangulation.calculateGroundDistance(baseY, centX, widthPx);
            }
        } catch (Throwable t) {
            Log.w(TAG, "SIE solve failed", t);
            return null;
        }
    }

    /**
     * Estimated height of the object's centre above the floor (metres).
     * For ground objects the engine returns heightM=0 (it only knows the
     * base distance), so we compute real height from the bbox vertical
     * extent and return half of it as the centre height. For overhangs
     * the engine's heightM is the hang height above floor — use directly.
     */
    private float objectCentreHeightM(Detection d,
                                       TriangulationEngine.SpatialOutput out,
                                       int fH) {
        if (out == null) return 0f;
        if ("OVERHANG".equals(out.signature) && out.heightM > 0f) {
            return out.heightM;
        }
        float focalPx = (hal != null) ? hal.getFocalLengthPx(lastFrameWidth) : 0f;
        if (focalPx <= 0f || out.distanceM <= 0f) return 0f;
        float bboxPx = (d.box.bottom - d.box.top) * fH;
        float realH  = (bboxPx / focalPx) * out.distanceM;
        return realH / 2.0f;
    }

    // ─── HUD status line ──────────────────────────────────────────

    /**
     * Status line: LABEL · Xm · CLOCK · HEIGHT · CONF%
     *
     * Uses SpatialOutput when the LUT is calibrated; falls back to
     * the legacy formula so the overlay always shows something useful.
     */
    private String buildStatusLine(Detection d,
                                    TriangulationEngine.SpatialOutput out,
                                    int fH) {
        float dist    = resolveDistance(d, out);
        float bearing = (out != null) ? out.bearingDeg : bearingDeg(d.centerX());
        float centH   = objectCentreHeightM(d, out, fH);
        String clock  = clockPosition(bearing);
        String hDesc  = heightDescription(centH, out);
        String status = d.label.toUpperCase(Locale.US)
                + " · " + distStr(dist)
                + " · " + clock
                + (hDesc.isEmpty() ? "" : " · " + hDesc)
                + " · " + Math.round(d.confidence * 100) + "%";
        if (dracoAID != null && !dracoAID.isCalibrated()) {
            status += " [calibrating…]";
        }
        return status;
    }

    // ─── Bearing helpers ──────────────────────────────────────────

    private static float bearingDeg(float normX) {
        return (normX - 0.5f) * FOV_DEGREES;
    }

    /**
     * Clock-face bearing for TTS.
     *   12 o'clock = dead ahead (±5°)
     *   11 / 1     = slightly off (5°–17°)
     *   10 / 2     = noticeably off (≥17°) within normal camera FOV
     */
    private static String clockPosition(float deg) {
        float ab = Math.abs(deg);
        if (ab < 5f)  return "12 o'clock";
        if (deg < 0f) return ab < 17f ? "11 o'clock" : "10 o'clock";
        else          return ab < 17f ? "1 o'clock"  : "2 o'clock";
    }

    /**
     * Height zone description for TTS.
     * Mapped to the ergonomic zones a standing person would feel:
     *   floor / ankle / knee / waist / chest / head / overhead.
     * Returns empty string for zero / unknown height.
     */
    private static String heightDescription(float hM,
                                             TriangulationEngine.SpatialOutput out) {
        if (out != null && "OVERHANG".equals(out.signature)) {
            return hM > 2.10f ? "overhead" : "head height";
        }
        if (hM <= 0.05f) return "";
        if (hM < 0.30f)  return "floor level";
        if (hM < 0.60f)  return "ankle height";
        if (hM < 0.95f)  return "knee height";
        if (hM < 1.25f)  return "waist height";
        if (hM < 1.58f)  return "chest height";
        if (hM < 1.90f)  return "head height";
        return "overhead";
    }

    /** Distance from SpatialOutput when valid, else legacy formula. */
    private static float resolveDistance(Detection d,
                                          TriangulationEngine.SpatialOutput out) {
        if (out != null && out.distanceM > 0f && out.distanceM < 50f) {
            return out.distanceM;
        }
        float normH = Math.max(d.box.bottom - d.box.top, 0.001f);
        return REFERENCE_CONSTANT / (normH * 100f);
    }

    /** Compact human-readable distance string used in both HUD and TTS. */
    private static String distStr(float dist) {
        if (dist < 1f) {
            return Math.round(dist * 100) + " centimetres";
        }
        long whole = Math.round(dist);
        if (whole >= 2 && Math.abs(dist - whole) <= 0.05f) {
            return whole + " metres";
        }
        return String.format(Locale.US, "%.1f metres", dist);
    }

    // ─── Legacy statics kept for fallback ─────────────────────────

    private static float distanceM(Detection d) {
        float normH = Math.max(d.box.bottom - d.box.top, 0.001f);
        return REFERENCE_CONSTANT / (normH * 100f);
    }

    private static String distanceStr(Detection d) {
        return distStr(distanceM(d));
    }

    // ─── Announce (TTS + haptic + lock-on beep) ───────────────────

    /**
     * Announce the primary target.
     *
     * TTS format: "{Label}, {distance}, {clock position}, {height}."
     *   e.g. "Chair, 2 metres, 10 o'clock, waist height."
     *
     * When the camera is locked on (|bearing| < 5°) a short confirmation
     * beep fires in addition to the double haptic pulse, giving a clear
     * multi-sensory "found it" signal even in noisy environments.
     */
    private void announceTarget(Detection d,
                                  TriangulationEngine.SpatialOutput out,
                                  int fH) {
        float bearing = (out != null) ? out.bearingDeg : bearingDeg(d.centerX());
        float absDeg  = Math.abs(bearing);
        float dist    = resolveDistance(d, out);

        // ── Haptic (always runs, ignores voice-mute) ──────────────
        if (hapticEnabled && haptic != null) {
            try {
                if (absDeg < 5f) {
                    haptic.alert();
                } else if (absDeg < 15f) {
                    haptic.pulse(dist);
                }
            } catch (Throwable ignored) {}
        }

        // ── Lock-on beep (audio, independent of TTS cooldown) ─────
        // Fires a short double-beep the moment the camera centres on
        // the target so the user knows they are aimed correctly without
        // having to wait for the next TTS utterance.
        if (absDeg < 5f && lockOnBeep != null) {
            try {
                lockOnBeep.startTone(ToneGenerator.TONE_PROP_BEEP2, 120);
            } catch (Throwable ignored) {}
        }

        if (!voiceEnabled || !ttsReady || tts == null) return;

        long now = SystemClock.uptimeMillis();
        boolean sameAsLast = d.label.equalsIgnoreCase(lastSpokenLabel);
        if (sameAsLast && now - lastSpokenAt < SPEECH_COOLDOWN_MS) return;
        if (!sameAsLast && now - lastSpokenAt < 700L) return;

        float  centH  = objectCentreHeightM(d, out, fH);
        String clock  = clockPosition(bearing);
        String hDesc  = heightDescription(centH, out);
        String label  = d.label.substring(0, 1).toUpperCase(Locale.US)
                      + d.label.substring(1).toLowerCase(Locale.US);

        String utterance = label + ", " + distStr(dist) + ", " + clock;
        if (!hDesc.isEmpty()) utterance += ", " + hDesc;
        utterance += ".";

        tts.speak(utterance, TextToSpeech.QUEUE_FLUSH, null,
                "auriga_locator_" + now);
        lastSpokenAt    = now;
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

        View navCameraConnect = findViewById(R.id.nav_camera_connect);
        if (navCameraConnect != null) navCameraConnect.setOnClickListener(v -> {
            closeDrawer();
            safeStart(CameraConnectActivity.class, "Camera Connect");
        });

        View navStreamLaptop = findViewById(R.id.nav_stream_laptop);
        if (navStreamLaptop != null) navStreamLaptop.setOnClickListener(v -> {
            closeDrawer();
            safeStart(CameraStreamActivity.class, "Stream to Laptop");
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

        View navSmartLight = findViewById(R.id.nav_smart_light);
        smartLightSub = findViewById(R.id.nav_smart_light_sub);
        if (navSmartLight != null) {
            navSmartLight.setOnClickListener(v -> {
                smartLightEnabled = !smartLightEnabled;
                getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
                        .edit().putBoolean(PREF_SMART_LIGHT, smartLightEnabled).apply();
                refreshMuteLabels();
                Toast.makeText(this,
                        smartLightEnabled
                                ? "Smart Light ON — flashes before each scan"
                                : "Smart Light OFF",
                        Toast.LENGTH_SHORT).show();
                // Make sure torch is off whenever user disables the feature
                if (!smartLightEnabled) tryDisableTorch();
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
        if (smartLightSub != null) {
            smartLightSub.setText(smartLightEnabled
                    ? "ON · flashes before each scan"
                    : "OFF · tap to enable");
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

    /**
     * Feed every touch event to the serpentine gesture detector BEFORE
     * any view processes it. This guarantees the detector always receives
     * ACTION_DOWN, ACTION_MOVE, and ACTION_UP regardless of whether a
     * child view (PreviewView, overlay, FAB, etc.) consumes the event.
     *
     * Previously the detector was attached via {@code setOnTouchListener}
     * on the locator FrameLayout, but a non-clickable FrameLayout drops
     * MOVE/UP events when ACTION_DOWN returns {@code false}, so the S-curve
     * was never recognised. Routing through dispatchTouchEvent fixes this.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (serpentine != null) serpentine.onTouch(ev);
        return super.dispatchTouchEvent(ev);
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
