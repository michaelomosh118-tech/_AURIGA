package com.drakosanctis.auriga;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import android.Manifest;

/**
 * MainActivity: The "Orchestrator" of the Auriga Ecosystem.
 * Connects the 12 core classes into a high-performance feedback loop.
 * Handles the "Futuristic HUD" UI and mode toggles.
 */
public class MainActivity extends Activity implements TextureView.SurfaceTextureListener {

    private static final int PERMISSION_REQUEST_CODE = 1001;

    // --- Core Engine Layers ---
    private AurigaConfig config;
    private FiducialLUT lut;
    private HardwareHAL hal;
    private LicenseManager licenseManager;
    private ColorSquareDetector detector;
    private CalibrationManager calibrationManager;
    private TriangulationEngine engine;
    private ImageProcessor processor;
    private OdometryManager odometry;
    private DrakoVoice voice;
    private SonarManager sonar;
    private HapticManager haptic;
    private CameraService camera;

    // --- UI Components ---
    private TextureView textureView;
    private TextView distanceText, bearingText, signatureText, alertBanner, modeLabel;
    private View radarView;

    private static final String TAG = "AurigaMain";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Each step is wrapped individually so a single bad initializer (e.g.
        // TextToSpeech failing on a device without an en-US language pack,
        // or a missing drawable resource during layout inflation) does not
        // kill the whole app on launch. Anything that throws is logged via
        // AurigaApplication's crash handler and surfaced as a Toast so users
        // on Samsung/Knox devices can read the failure without adb.
        // Structural UI: if these throw there is no viable HUD, so bail out
        // after the Toast fires rather than wiring SurfaceTextureListener
        // callbacks that will crash later against partially-constructed
        // engine state.
        if (!initStep("setContentView", () -> setContentView(R.layout.activity_main))) return;
        if (!initStep("initUI",          this::initUI))                                return;

        // Engine layers. Order matters: calibrationManager depends on lut +
        // detector, engine depends on lut + hal, applyVariantStyling depends
        // on calibrationManager. Each runs best-effort so a single failure
        // does not cascade — e.g. if DrakoVoice's TextToSpeech init throws
        // because the device has no en-US language pack, the HUD still comes
        // up and the voice layer is simply inert.
        initStep("lut",                () -> lut = new FiducialLUT());
        initStep("hal",                () -> hal = new HardwareHAL(this));
        initStep("licenseManager",     () -> licenseManager = new LicenseManager(this));
        initStep("detector",           () -> detector = new ColorSquareDetector(120.0f, 15.0f));
        // calibrationManager + engine take lut/detector/hal as constructor
        // args but those collaborators just store the refs without a null
        // check. Precondition-assert the deps inside the lambda so a failed
        // lut/detector/hal step short-circuits here with an accurate Toast
        // ("calibrationManager" failed because "lut was null") instead of
        // constructing an object with null internals that NPEs on the first
        // frame in the render loop.
        initStep("calibrationManager", () -> {
            requireDep("lut", lut);
            requireDep("detector", detector);
            calibrationManager = new CalibrationManager(lut, detector);
        });
        initStep("engine", () -> {
            requireDep("lut", lut);
            requireDep("hal", hal);
            engine = new TriangulationEngine(lut, hal);
        });
        initStep("processor",          () -> processor = new ImageProcessor());
        initStep("odometry",           () -> odometry = new OdometryManager());
        initStep("voice",              () -> voice = new DrakoVoice(this));
        initStep("sonar",              () -> sonar = new SonarManager());
        initStep("haptic",             () -> haptic = new HapticManager(this));
        initStep("camera",             () -> camera = new CameraService(this));

        // Variant styling reads calibrationManager.getState(), so must run
        // AFTER the engine-layer block above. Previously this ran before the
        // engine block and NPE'd on every launch — which was the real cause
        // of the A07 "instant crash on tap" the user reported.
        initStep("applyVariantStyling", this::applyVariantStyling);

        // Security Heartbeat Check
        initStep("validateLicense", () -> {
            if (licenseManager != null) {
                licenseManager.validateLicense(BuildConfig.DEFAULT_LICENSE);
            }
        });

        // Request Permissions for Google Play Compliance
        initStep("checkPermissions", this::checkPermissions);
    }

    /**
     * Throws IllegalStateException if a required init-time collaborator is
     * null. Used by composite initSteps (calibrationManager, engine) whose
     * downstream classes do not null-check their constructor args.
     */
    private static void requireDep(String name, Object dep) {
        if (dep == null) {
            throw new IllegalStateException(
                    "Missing dependency: " + name + " was null (its initStep failed earlier).");
        }
    }

    /**
     * Runs an init step and swallows any Throwable, logging the failure via
     * the crash-logger pipeline and showing a Toast so the user knows what
     * failed without having to pull logcat off the phone. Returns true iff
     * the step completed cleanly. Callers that must short-circuit the rest
     * of onCreate on failure (e.g. setContentView) check the return value.
     */
    private boolean initStep(String name, Runnable step) {
        try {
            step.run();
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Init step '" + name + "' failed", t);
            Toast.makeText(
                    this,
                    "Auriga init failed at: " + name + "\n" + t.getClass().getSimpleName()
                            + ": " + String.valueOf(t.getMessage()),
                    Toast.LENGTH_LONG
            ).show();
            // Persist a timestamped crash report to the app's external files
            // directory without terminating the process, so the user can
            // copy it off the phone via Samsung My Files.
            AurigaApplication.logNonFatal(t, "initStep:" + name);
            return false;
        }
    }

    private void checkPermissions() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, restart camera if needed
            }
        }
    }

    private void initUI() {
        textureView = findViewById(R.id.camera_preview);
        textureView.setSurfaceTextureListener(this);
        
        modeLabel = findViewById(R.id.mode_label);
        distanceText = findViewById(R.id.distance_readout);
        bearingText = findViewById(R.id.bearing_readout);
        signatureText = findViewById(R.id.object_signature);
        alertBanner = findViewById(R.id.alert_banner);
        radarView = findViewById(R.id.radar_sweep);
    }

    /**
     * applyVariantStyling: Specializes the HUD UI for the current Auriga product.
     * Toggles HUD overlays and labels dynamically based on the ecosystem constellation.
     */
    private void applyVariantStyling() {
        String variantName = "AURIGA " + AurigaConfig.CURRENT_PRODUCT.name();
        // Null-safe state read: if the calibration manager failed to init we
        // still want the HUD to render, just with the default SPATIAL ENGINE
        // label instead of aborting styling entirely.
        boolean detecting = calibrationManager != null
                && calibrationManager.getState() == CalibrationManager.State.DETECTING;
        String modeDesc = detecting ? "CALIBRATION" : "SPATIAL ENGINE";

        modeLabel.setText(variantName + " // " + modeDesc);

        // Variant-specific UI tweaks
        switch (AurigaConfig.CURRENT_PRODUCT) {
            case SENTINEL:
                // Sentinel "God's Eye" mode uses a higher contrast HUD
                signatureText.setTextColor(getColor(android.R.color.holo_red_dark));
                break;
            case AERO:
                // Aero mode emphasizes height and bearing for drones
                radarView.setScaleX(1.5f);
                radarView.setScaleY(1.5f);
                break;
            case INDUSTRIAL:
                // Industrial mode uses a caution/warning color scheme
                modeLabel.setTextColor(getColor(android.R.color.holo_orange_dark));
                break;
            case NAVI:
            default:
                // Navi uses the classic cyan/cobalt blue HUD
                break;
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        // Any engine layer may be null if its initStep failed; start the
        // camera + main loop only when the full dependency chain is intact.
        // Otherwise we'd NPE inside the render loop (calibrationManager /
        // processor / engine / odometry / etc.) on every frame and spam
        // the crash log long after the user already got the initStep Toast.
        if (!coreEnginesReady()) {
            Log.w(TAG, "Surface ready but one or more engine layers were not constructed; skipping main loop.");
            return;
        }
        camera.start(surface);
        startMainLoop();
    }

    /**
     * True iff every field touched by the hot navigation loop is non-null.
     * If any of these failed to construct during onCreate we refuse to start
     * the loop at all; the user has already been shown a Toast naming the
     * failing init step, and a partially-functional HUD would just produce
     * secondary NPEs that obscure the original failure.
     */
    private boolean coreEnginesReady() {
        return camera != null
                && calibrationManager != null
                && processor != null
                && odometry != null
                && engine != null
                && sonar != null
                && haptic != null
                && voice != null;
    }

    private void startMainLoop() {
        // Main high-performance feedback loop (30+ FPS)
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (!isFinishing()) {
                    // Fix 6: getBitmap() must be called from the UI thread
                    final Bitmap[] frameHolder = new Bitmap[1];
                    runOnUiThread(() -> {
                        if (textureView.isAvailable()) {
                            frameHolder[0] = textureView.getBitmap(640, 480);
                        }
                    });

                    // Wait for bitmap (simple sync for now)
                    try { Thread.sleep(16); } catch (InterruptedException e) { }
                    Bitmap frame = frameHolder[0];
                    if (frame == null) continue;

                    if (calibrationManager.getState() == CalibrationManager.State.DETECTING) {
                        // --- CALIBRATION MODE ---
                        final ColorSquareDetector.DetectionResult res = calibrationManager.processFrame(frame);
                        runOnUiThread(() -> updateCalibrationUI(res));
                    } else {
                        // --- NAVIGATION MODE ---
                        processNavigationFrame(frame);
                    }
                    
                    try { Thread.sleep(17); } catch (InterruptedException e) { }
                }
            }
        }).start();
    }

    private void processNavigationFrame(Bitmap frame) {
        // 1. Scan for obstacles (3-column)
        ImageProcessor.ObstacleData[] obstacles = processor.scanFrame(frame);
        
        // 2. GhostAnchor™ Stabilization
        float[] shift = odometry.calculateVisualShift(frame);
        
        // 3. Triangulation Math (TruePath™ & SkyShield™)
        // Center column results for primary feedback
        // Fix 3 & 4: Pass observed width for classification
        final TriangulationEngine.SpatialOutput groundRes = engine.calculateGroundDistance(
                obstacles[1].baseY, 320, obstacles[1].baseWidthPx);
        final TriangulationEngine.SpatialOutput hangRes = engine.calculateSuspendedHeight(obstacles[1].hangY, 320);

        // 4. Feedback & HUD Update
        runOnUiThread(() -> {
            if (groundRes != null) {
                // Fix 9: specify column (1 for center)
                float dist = odometry.smoothDistance(1, groundRes.distanceM);
                updateHUD(dist, groundRes.bearingDeg, groundRes.signature);
                sonar.updateSpatialData(dist, groundRes.bearingDeg, groundRes.signature);
                haptic.pulse(dist);
                
                // Voice announcement logic (debounced)
                if (dist < 1.0f) voice.speak(groundRes.signature, dist, groundRes.bearingDeg);
            }
            
            if (hangRes != null && hangRes.heightM < 2.0f) {
                alertBanner.setVisibility(View.VISIBLE);
                alertBanner.setText("⚠ OVERHANG " + String.format("%.1f", hangRes.distanceM) + "m");
                haptic.alert();
                voice.announceAlert("overhang", hangRes.distanceM);
            } else {
                alertBanner.setVisibility(View.GONE);
            }
        });
    }

    private void updateHUD(float dist, float bearing, String sig) {
        distanceText.setText(String.format("%.1f", dist) + "m");
        bearingText.setText(String.format("%.0f", Math.abs(bearing)) + "° " + (bearing < 0 ? "LEFT" : "RIGHT"));
        signatureText.setText(sig);
    }

    private void updateCalibrationUI(ColorSquareDetector.DetectionResult result) {
        // Update reticle and status on the calibration screen
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) { }
    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        // Fires during teardown regardless of init outcome; skip cleanly if
        // camera's initStep never produced an instance.
        if (camera != null) camera.stop();
        return true;
    }
    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) { }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Any of these can be null if their initStep failed; onDestroy must
        // not turn a degraded launch into a teardown crash.
        if (voice != null)  voice.shutdown();
        if (sonar != null)  sonar.stop();
        if (haptic != null) haptic.stop();
    }
}
