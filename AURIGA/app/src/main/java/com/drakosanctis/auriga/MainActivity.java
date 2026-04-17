package com.drakosanctis.auriga;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.view.TextureView;
import android.view.View;
import android.widget.TextView;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize HUD UI
        initUI();
        applyVariantStyling();

        // Initialize Core Engine Layers
        lut = new FiducialLUT();
        hal = new HardwareHAL(this);
        licenseManager = new LicenseManager(this);
        detector = new ColorSquareDetector(120.0f, 15.0f); // Default to Green square
        calibrationManager = new CalibrationManager(lut, detector);
        engine = new TriangulationEngine(lut, hal);
        processor = new ImageProcessor();
        odometry = new OdometryManager();
        voice = new DrakoVoice(this);
        sonar = new SonarManager();
        haptic = new HapticManager(this);
        camera = new CameraService(this);

        // Security Heartbeat Check
        licenseManager.validateLicense(BuildConfig.DEFAULT_LICENSE);

        // Request Permissions for Google Play Compliance
        checkPermissions();
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
        String modeDesc = (calibrationManager.getState() == CalibrationManager.State.DETECTING) 
                ? "CALIBRATION" : "SPATIAL ENGINE";
        
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
        camera.start(surface);
        startMainLoop();
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
        camera.stop();
        return true;
    }
    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) { }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        voice.shutdown();
        sonar.stop();
        haptic.stop();
    }
}
