package com.drakosanctis.auriga;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.Manifest;

import androidx.appcompat.widget.SwitchCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * MainActivity: The "Orchestrator" of the Auriga Ecosystem.
 * Connects the 12 core classes into a high-performance feedback loop.
 * Handles the "Futuristic HUD" UI and mode toggles.
 */
public class MainActivity extends Activity implements TextureView.SurfaceTextureListener {

    private static final int PERMISSION_REQUEST_CODE = 1001;

    // Target bitmap width for the per-frame scan pipeline. Height is
    // computed dynamically from the live TextureView aspect ratio so a
    // 1600x720 preview (Galaxy A07) downsamples to 640x288 instead of
    // being stretched to 640x480. Stretching was the single largest
    // residual bias in v0.1.3-jitter-fix: the LUT row axis was
    // measured in 4:3 space but queried in 20:9 space, doubling rows
    // near the bottom of the frame.
    private static final int DEFAULT_BITMAP_W = 640;
    private static final int DEFAULT_BITMAP_H = 480;

    // Active bitmap size for the current preview. Written once the
    // TextureView reports its first size; read on every frame. volatile
    // so the render-loop thread observes the updated values without a
    // memory fence. Defaults to the legacy 640x480 so callers that
    // never get a size callback keep their old behavior.
    private volatile int activeBitmapW = DEFAULT_BITMAP_W;
    private volatile int activeBitmapH = DEFAULT_BITMAP_H;

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
    private TextView diagnosticOverlay;
    private Button diagToggle;
    private View radarView;

    // Hamburger drawer (0.4e). drawerLayout wraps the entire HUD so the side
    // panel slides in over the live camera; pinDiagToHud + the corresponding
    // SharedPreferences flag control whether the inline DIAG pill in the top
    // bar is shown alongside the drawer entry.
    private DrawerLayout drawerLayout;
    private boolean pinDiagToHud = false;
    static final String PREFS_NAME = "auriga_prefs";
    private static final String PREF_PIN_DIAG = "pin_diag_to_hud";

    // Latest line written to the diagnostic overlay. We mirror it here so
    // FeedbackActivity can attach it to the submission even when the
    // overlay is hidden — feedback should always carry the freshest
    // engine state, not whatever happened to be on screen.
    private volatile String lastDiagnosticSnapshot = "";

    // Diagnostic overlay state. Toggled by tapping the DIAG button in
    // the HUD top bar (explicit affordance; replaces the earlier
    // invisible long-press-on-title gesture). When on,
    // processNavigationFrame publishes per-frame metrics (pitch,
    // GhostAnchor shift, raw LUT row, confidence, sanity, active
    // bitmap size, whether a trained profile is loaded) to the
    // overlay TextView so a field operator can see which layer is
    // flickering without attaching adb.
    private volatile boolean diagnosticVisible = false;

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
        // Bundled fallback profile. Ships inside the APK as
        // assets/default_profile_<Build.MODEL>.json and is loaded BEFORE
        // the network fetch so a device with a known calibration
        // (currently SM-A057F for the dev A07) never runs off the
        // synthetic 640x480 defaults even on first launch / offline.
        // Any approved entry returned by fetchCalibrationProfileAsync
        // will replace the bundled anchors when the network call
        // resolves.
        initStep("bundledProfile",     this::loadBundledCalibrationProfile);
        // Off-main-thread fetch of the shared calibration profile for this
        // phone model. If a match is found, the LUT is replaced with the
        // contributed (distanceM, pixelWidth, pixelRow) anchors so the
        // runtime row lookup stops reading synthetic 640x480 defaults on
        // devices with a non-reference preview resolution. Silent no-op on
        // miss or on any network / parse failure -- the engine continues
        // with whatever profile is already loaded (bundled or synthetic).
        initStep("calibrationProfile", this::fetchCalibrationProfileAsync);
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
            engine.setFrameSize(DEFAULT_BITMAP_W, DEFAULT_BITMAP_H);
        });
        initStep("processor",          () -> {
            processor = new ImageProcessor();
            processor.setFrameSize(DEFAULT_BITMAP_W, DEFAULT_BITMAP_H);
        });
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
        diagnosticOverlay = findViewById(R.id.diagnostic_overlay);
        diagToggle = findViewById(R.id.diag_toggle);
        drawerLayout = findViewById(R.id.drawer_layout);

        // Restore the user's "pin DIAG to HUD" preference so the toggle
        // state survives an app restart. Default is false (DIAG lives in
        // the drawer only) which keeps the HUD chrome minimal for new
        // users; power users can opt back into the inline button.
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        pinDiagToHud = prefs.getBoolean(PREF_PIN_DIAG, false);
        applyDiagPinVisibility();

        // Explicit DIAG button replaces the earlier long-press-on-title
        // gesture, which had no visual affordance and no TalkBack hit
        // target. Standard click toggles the overlay; activated state
        // drives the bordered-vs-filled background defined in
        // res/drawable/diag_toggle_bg.xml.
        if (diagToggle != null) {
            diagToggle.setOnClickListener(v -> toggleDiagnosticOverlay());
        }

        // Reader launcher: opens the OrCam-style ReaderActivity which
        // uses on-device ML Kit text recognition + TextToSpeech to read
        // printed pages aloud. Wired here (not in ReaderActivity) so the
        // HUD button stays consistent with the DIAG affordance.
        Button readerBtn = findViewById(R.id.reader_launch);
        if (readerBtn != null) {
            readerBtn.setOnClickListener(v -> launchReader());
        }

        wireDrawer(prefs);
    }

    /**
     * Wire the hamburger drawer (0.4e): the ≡ pill in the top bar opens
     * it, every drawer row either navigates or toggles state, and the
     * "Pin DIAG to HUD" Switch persists into SharedPreferences so the
     * choice survives app restarts.
     */
    private void wireDrawer(SharedPreferences prefs) {
        Button menuBtn = findViewById(R.id.menu_toggle);
        if (menuBtn != null && drawerLayout != null) {
            menuBtn.setOnClickListener(v -> {
                if (drawerLayout.isDrawerOpen(Gravity.START)) {
                    drawerLayout.closeDrawer(Gravity.START);
                } else {
                    drawerLayout.openDrawer(Gravity.START);
                }
            });
        }

        // Refresh the calibration-walk gate hint every time the drawer
        // opens. Required because users might finish the walk and come
        // straight back here — without this, the amber "complete the
        // walk first" warning would still be shown until next launch.
        if (drawerLayout != null) {
            drawerLayout.addDrawerListener(new androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
                @Override
                public void onDrawerOpened(View drawerView) {
                    refreshFeedbackGateUi(
                            findViewById(R.id.nav_feedback),
                            findViewById(R.id.nav_feedback_hint));
                }
            });
        }

        Button navHome = findViewById(R.id.nav_home);
        if (navHome != null) {
            navHome.setOnClickListener(v -> {
                if (drawerLayout != null) drawerLayout.closeDrawer(Gravity.START);
            });
        }

        Button navReader = findViewById(R.id.nav_reader);
        if (navReader != null) {
            navReader.setOnClickListener(v -> {
                if (drawerLayout != null) drawerLayout.closeDrawer(Gravity.START);
                launchReader();
            });
        }

        Button navAbout = findViewById(R.id.nav_about);
        if (navAbout != null) {
            navAbout.setOnClickListener(v -> {
                if (drawerLayout != null) drawerLayout.closeDrawer(Gravity.START);
                safeStart(AboutActivity.class, "About");
            });
        }

        Button navHelp = findViewById(R.id.nav_help);
        if (navHelp != null) {
            navHelp.setOnClickListener(v -> {
                if (drawerLayout != null) drawerLayout.closeDrawer(Gravity.START);
                safeStart(HelpActivity.class, "Help");
            });
        }

        Button navDiag = findViewById(R.id.nav_diag_toggle);
        if (navDiag != null) {
            // Reflect current state on first show so the row label matches
            // whatever DIAG state we restored at startup.
            navDiag.setText(diagnosticVisible
                    ? "Hide Diagnostic Overlay"
                    : "Show Diagnostic Overlay");
            navDiag.setActivated(diagnosticVisible);
            navDiag.setOnClickListener(v -> {
                toggleDiagnosticOverlay();
                navDiag.setText(diagnosticVisible
                        ? "Hide Diagnostic Overlay"
                        : "Show Diagnostic Overlay");
                navDiag.setActivated(diagnosticVisible);
            });
        }

        SwitchCompat pinSwitch = findViewById(R.id.nav_pin_diag);
        if (pinSwitch != null) {
            pinSwitch.setChecked(pinDiagToHud);
            pinSwitch.setOnCheckedChangeListener((btn, checked) -> {
                pinDiagToHud = checked;
                prefs.edit().putBoolean(PREF_PIN_DIAG, checked).apply();
                applyDiagPinVisibility();
            });
        }

        TextView versionStamp = findViewById(R.id.nav_version_stamp);
        if (versionStamp != null) {
            try {
                android.content.pm.PackageInfo info =
                        getPackageManager().getPackageInfo(getPackageName(), 0);
                versionStamp.setText("© DrakoSanctis · v" + info.versionName);
            } catch (Throwable ignored) {
                // Keep the layout's default text if PackageManager misbehaves.
            }
        }

        // ── Setup section ─────────────────────────────────────────────
        Button navCalibrate = findViewById(R.id.nav_calibrate);
        if (navCalibrate != null) {
            navCalibrate.setOnClickListener(v -> {
                if (drawerLayout != null) drawerLayout.closeDrawer(Gravity.START);
                safeStart(CalibrationWalkActivity.class, "Calibration walk");
            });
        }

        Button navFeedback = findViewById(R.id.nav_feedback);
        TextView feedbackHint = findViewById(R.id.nav_feedback_hint);
        if (navFeedback != null) {
            navFeedback.setOnClickListener(v -> {
                if (drawerLayout != null) drawerLayout.closeDrawer(Gravity.START);
                launchFeedback();
            });
        }
        // Drawer is recreated on every open in spirit (we don't tear it
        // down) so refresh the gate hint each time we wire it up.
        refreshFeedbackGateUi(navFeedback, feedbackHint);
    }

    /**
     * Show or hide the "Complete the calibration walk first" amber hint
     * under the Send Feedback row, and dim the row itself when the gate
     * is closed. Note that we still let the row be tappable — tapping a
     * gated Feedback row launches the walk so the user has a clear
     * forward path instead of just being blocked.
     */
    private void refreshFeedbackGateUi(Button feedbackBtn, TextView hint) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean walkDone = prefs.getBoolean(
                CalibrationWalkActivity.PREF_WALK_DONE, false);
        if (hint != null) hint.setVisibility(walkDone ? View.GONE : View.VISIBLE);
        if (feedbackBtn != null) feedbackBtn.setAlpha(walkDone ? 1f : 0.55f);
    }

    private void launchFeedback() {
        try {
            android.content.Intent it = new android.content.Intent(this, FeedbackActivity.class);
            it.putExtra(FeedbackActivity.EXTRA_DIAGNOSTIC, lastDiagnosticSnapshot);
            it.putExtra(FeedbackActivity.EXTRA_PROFILE, currentProfileLabel());
            startActivity(it);
        } catch (Throwable t) {
            Toast.makeText(this,
                    "Feedback unavailable: " + t.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Best-effort label describing the active calibration profile so the
     * server can correlate accuracy reports across devices using the same
     * profile. We use the BuildConfig product flavor (navi/sentinel/aero/
     * industrial) as a coarse proxy when no finer profile is available.
     */
    private String currentProfileLabel() {
        try {
            return BuildConfig.AURIGA_PRODUCT
                    + (calibrationManager != null ? " · loaded" : " · default");
        } catch (Throwable t) {
            return "(unknown)";
        }
    }

    /** Show/hide the inline DIAG pill in the top bar based on user preference. */
    private void applyDiagPinVisibility() {
        if (diagToggle != null) {
            diagToggle.setVisibility(pinDiagToHud ? View.VISIBLE : View.GONE);
        }
    }

    /** Launch the OrCam-style reader, surfacing failures as a Toast. */
    private void launchReader() {
        try {
            startActivity(new android.content.Intent(this, ReaderActivity.class));
        } catch (Throwable t) {
            Toast.makeText(this,
                    "Reader unavailable: " + t.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void safeStart(Class<?> target, String label) {
        try {
            startActivity(new android.content.Intent(this, target));
        } catch (Throwable t) {
            Toast.makeText(this,
                    label + " unavailable: " + t.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Close the drawer on Back rather than exiting the HUD when it's open,
     * matching standard Material drawer behaviour.
     */
    @Override
    public void onBackPressed() {
        if (drawerLayout != null && drawerLayout.isDrawerOpen(Gravity.START)) {
            drawerLayout.closeDrawer(Gravity.START);
            return;
        }
        super.onBackPressed();
    }

    /**
     * Flip the diagnostic-overlay visibility and announce the new
     * state so visually-impaired devs can tell the tap landed.
     * Drives three things at once so visual + audio + button state
     * stay in lockstep: overlay visibility, DIAG button activated
     * state (background swap to filled cyan), and TalkBack content
     * description so the next read-out matches the new state.
     */
    private void toggleDiagnosticOverlay() {
        diagnosticVisible = !diagnosticVisible;
        if (diagnosticOverlay != null) {
            diagnosticOverlay.setVisibility(
                    diagnosticVisible ? View.VISIBLE : View.GONE);
            if (!diagnosticVisible) diagnosticOverlay.setText("");
        }
        if (diagToggle != null) {
            diagToggle.setActivated(diagnosticVisible);
            diagToggle.setContentDescription(diagnosticVisible
                    ? "Diagnostics on. Double tap to hide overlay."
                    : "Diagnostics off. Double tap to show overlay.");
        }
        Toast.makeText(this,
                diagnosticVisible ? "Diagnostics ON" : "Diagnostics OFF",
                Toast.LENGTH_SHORT).show();
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
        // Derive an aspect-preserving bitmap size the first time we see
        // the TextureView dimensions. Stretching a 20:9 preview into a
        // 4:3 bitmap was the dominant residual distance bias; by
        // locking the bitmap height to the texture's real aspect, rows
        // and the horizon stay where the LUT expects them.
        applyBitmapSizeFromTexture(width, height);
        camera.start(surface);
        startMainLoop();
    }

    /**
     * Chooses a bitmap width+height that matches the TextureView's
     * aspect ratio, capped by DEFAULT_BITMAP_W on the long edge so the
     * per-frame JNI pixel cost stays bounded. Result is published to
     * ImageProcessor and TriangulationEngine so both the 3-column scan
     * and the pinhole bearing math use the same coordinate system.
     *
     * Fallback: if texWidth or texHeight is non-positive (can happen
     * in rare Samsung preview reconfig paths), we keep the current
     * active size rather than propagating a divide-by-zero.
     */
    private void applyBitmapSizeFromTexture(int texWidth, int texHeight) {
        if (texWidth <= 0 || texHeight <= 0) return;
        int targetW;
        int targetH;
        if (texWidth >= texHeight) {
            targetW = DEFAULT_BITMAP_W;
            targetH = Math.max(
                    1, Math.round((float) DEFAULT_BITMAP_W * texHeight / texWidth));
        } else {
            // Portrait preview (rare on TextureView but defensive).
            targetH = DEFAULT_BITMAP_W;
            targetW = Math.max(
                    1, Math.round((float) DEFAULT_BITMAP_W * texWidth / texHeight));
        }
        if (targetW == activeBitmapW && targetH == activeBitmapH) return;
        activeBitmapW = targetW;
        activeBitmapH = targetH;
        if (processor != null) processor.setFrameSize(targetW, targetH);
        if (engine != null)    engine.setFrameSize(targetW, targetH);
        Log.i(TAG, "Bitmap size -> " + targetW + "x" + targetH
                + " (texture " + texWidth + "x" + texHeight + ")");
    }

    /**
     * True iff every field required by the spatial navigation math is
     * non-null. Ancillary feedback layers (sonar / haptic / voice) are
     * NOT in this gate -- per the graceful-degradation contract stated
     * in onCreate, a missing TTS language pack or a broken haptic driver
     * must not kill the HUD. Those callsites are individually null-guarded
     * inside processNavigationFrame instead.
     */
    private boolean coreEnginesReady() {
        return camera != null
                && calibrationManager != null
                && processor != null
                && odometry != null
                && engine != null;
    }

    private void startMainLoop() {
        // Main high-performance feedback loop (30+ FPS)
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (!isFinishing()) {
                    // Fix 6: getBitmap() must be called from the UI thread.
                    // Size is the aspect-preserving active bitmap computed
                    // from the TextureView geometry in onSurfaceTextureAvailable;
                    // re-reading the volatile each iteration picks up any
                    // subsequent surface-size changes without racing.
                    final Bitmap[] frameHolder = new Bitmap[1];
                    final int bmW = activeBitmapW;
                    final int bmH = activeBitmapH;
                    runOnUiThread(() -> {
                        if (textureView.isAvailable()) {
                            frameHolder[0] = textureView.getBitmap(bmW, bmH);
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

    /**
     * Build + post the diagnostic overlay string for this frame. Pitch
     * comes from HardwareHAL's smoothed accelerometer reading, LUT
     * point count + profile flag come from FiducialLUT, and the
     * GhostAnchor shift is the raw centroid delta from
     * {@link OdometryManager#calculateVisualShift}. Rendered into a
     * single multi-line monospace string so field operators can
     * scan-read which layer is flickering.
     *
     * Called only when the toggle is on; the cost is one format +
     * one setText on the UI thread. The obstacles reference is passed
     * through from processNavigationFrame so we do not redundantly
     * re-scan the frame here.
     */
    private void publishDiagnostics(int baseY, int centerX, float widthPx,
                                    float detConfidence, float shiftMag,
                                    TriangulationEngine.SpatialOutput groundRes,
                                    TriangulationEngine.SpatialOutput hangRes) {
        if (diagnosticOverlay == null) return;

        // Pull once per frame; HAL fields are already volatile so
        // reads are cheap. Missing HAL (init failure) drops to 0 so
        // the overlay still renders something.
        float pitchRad = (hal != null) ? hal.getPitchRadians() : 0f;
        float pitchDeg = (float) Math.toDegrees(pitchRad);
        float focalPx  = (hal != null) ? hal.getFocalLengthPx(activeBitmapW) : 0f;

        int lutPoints          = (lut != null) ? lut.getPointCount() : 0;
        String profileSrc      = (lut != null) ? lut.getProfileSource() : "?";
        float rawRowDist       = (lut != null)
                ? lut.getDistanceFromRowWithPitch(baseY, pitchRad, focalPx) : -1f;
        float rawWidthDist     = (lut != null && widthPx > 0f)
                ? lut.getDistanceFromWidth(widthPx) : -1f;

        float groundDist = groundRes != null ? groundRes.distanceM  : Float.NaN;
        float groundBear = groundRes != null ? groundRes.bearingDeg : Float.NaN;
        float groundSane = groundRes != null ? groundRes.sanityScore : 0f;

        // Accel state tells the user whether pitch correction is
        // actually live. "off" = sensor never registered; "warming"
        // = registered but no sample yet (rare, <=200 ms after
        // onCreate); "live" = delivering samples. This was the
        // missing signal in v0.2.0 -- the pitch line showed 0.00 but
        // there was no way to tell if that was "phone held level" or
        // "sensor broken".
        String accelState;
        if (hal == null || !hal.hasAccelerometer()) {
            accelState = "off";
        } else if (!hal.isPitchInitialized()) {
            accelState = "warming";
        } else {
            accelState = "live";
        }

        String msg = String.format(java.util.Locale.US,
                "AURIGA DIAG\n"
                + "bitmap   : %dx%d\n"
                + "pitch    : %6.2f deg (%.4f rad) %s\n"
                + "focalPx  : %7.1f\n"
                + "device   : %s %s\n"
                + "baseY    : %4d   centerX : %4d\n"
                + "widthPx  : %6.1f  conf    : %5.2f\n"
                + "rowDist  : %5.2f m  widthD  : %5.2f m\n"
                + "ground   : %5.2f m @ %+6.1f deg\n"
                + "sanity   : %4.2f   shift   : %5.2f px\n"
                + "profile  : %s (%d pts)",
                activeBitmapW, activeBitmapH,
                pitchDeg, pitchRad, accelState,
                focalPx,
                safeBuildTag(Build.MANUFACTURER), safeBuildTag(Build.MODEL),
                baseY, centerX,
                widthPx, detConfidence,
                rawRowDist, rawWidthDist,
                groundDist, groundBear,
                groundSane, shiftMag,
                profileSrc, lutPoints);

        // Mirror the snapshot regardless of overlay visibility so Send
        // Feedback always carries the freshest engine state.
        lastDiagnosticSnapshot = msg;
        runOnUiThread(() -> {
            if (diagnosticOverlay != null && diagnosticVisible) {
                diagnosticOverlay.setText(msg);
            }
        });
    }

    private void processNavigationFrame(Bitmap frame) {
        // 1. Scan for obstacles (3-column brightness-edge detector).
        ImageProcessor.ObstacleData[] obstacles = processor.scanFrame(frame);

        // 2. Optional color-square detection running CONCURRENTLY with the
        //    3-column scanner. When the color detector returns a high-
        //    confidence box, we swap its (baseY, centerX, pixelWidth) into
        //    the center column so the calibrated LUT is being queried with
        //    a row the LUT was actually trained against. When confidence is
        //    weak, the original 3-column result is used as the fallback.
        if (detector != null) {
            try {
                ColorSquareDetector.DetectionResult colorRes = detector.detect(frame);
                if (colorRes != null && colorRes.confidence > 0.8f
                        && colorRes.pixelWidth > 10f) {
                    obstacles[1].baseY       = (int) colorRes.baseY;
                    obstacles[1].centerX     = colorRes.centerX;
                    obstacles[1].baseWidthPx = colorRes.pixelWidth;
                    obstacles[1].confidence  =
                            Math.max(obstacles[1].confidence, colorRes.confidence);
                }
            } catch (Throwable ignored) {
                // Color detector failures are non-fatal; fall back to the
                // 3-column scan results already in `obstacles`.
            }
        }

        // 3. GhostAnchor(TM) Stabilization: centroid shift between frames.
        //    Shift magnitude feeds the smoother, which freezes its state
        //    when the camera itself is panning hard (otherwise we'd chase
        //    camera motion instead of object motion).
        odometry.calculateVisualShift(frame);
        float shiftMag = odometry.getLastShiftMagnitude();

        // 4. Triangulation Math. Bearing now derives from the actual
        //    centerX of the detected object instead of the hardcoded
        //    frame midline; the pitch-aware FiducialLUT lookup inside
        //    the engine eliminates handheld-tilt bias in distance.
        final int baseY   = obstacles[1].baseY;
        final int hangY   = obstacles[1].hangY;
        final int centerX = (int) obstacles[1].centerX;
        final float widthPx = obstacles[1].baseWidthPx;
        final float detConfidence = obstacles[1].confidence;

        final TriangulationEngine.SpatialOutput groundRes =
                engine.calculateGroundDistance(baseY, centerX, widthPx);
        final TriangulationEngine.SpatialOutput hangRes =
                engine.calculateSuspendedHeight(hangY, centerX);

        // 4.5 Publish per-frame diagnostics if the overlay is enabled.
        // Cheap: only builds the string when the toggle is on, so
        // zero overhead on end-user devices with the overlay hidden.
        if (diagnosticVisible) publishDiagnostics(
                baseY, centerX, widthPx, detConfidence, shiftMag,
                groundRes, hangRes);

        // 5. Feedback & HUD Update. Confidence from the detector is
        //    multiplied into the sanityScore from the engine so a frame
        //    where (a) the edge contrast was weak OR (b) the row-vs-width
        //    cross-check disagreed gets down-weighted before it reaches
        //    the low-pass smoother.
        runOnUiThread(() -> {
            if (groundRes != null) {
                float frameConf = detConfidence * groundRes.sanityScore;
                float dist = odometry.smoothDistance(
                        1, groundRes.distanceM, frameConf, shiftMag);
                float bearing = odometry.smoothBearing(
                        1, groundRes.bearingDeg, frameConf, shiftMag);
                updateHUD(dist, bearing, groundRes.signature);
                if (sonar != null)  sonar.updateSpatialData(dist, bearing, groundRes.signature);
                if (haptic != null) haptic.pulse(dist);

                // Voice announcement logic (debounced)
                if (voice != null && dist < 1.0f && frameConf > 0.5f) {
                    voice.speak(groundRes.signature, dist, bearing);
                }
            }

            if (hangRes != null && hangRes.heightM < 2.0f) {
                alertBanner.setVisibility(View.VISIBLE);
                alertBanner.setText("\u26A0 OVERHANG " + String.format("%.1f", hangRes.distanceM) + "m");
                if (haptic != null) haptic.alert();
                if (voice != null) voice.announceAlert("overhang", hangRes.distanceM);
            } else {
                alertBanner.setVisibility(View.GONE);
            }
        });
    }

    /**
     * Downloads the shared calibration-profile JSON from the public
     * mirror, finds the entry whose (manufacturer, model) matches this
     * device, and replaces the FiducialLUT anchor set. Runs on a
     * background thread so the HUD boot is never blocked by the network.
     *
     * The profile file lives in the public mirror next to the existing
     * calibration_library.json. Schema:
     *   [ { manufacturer, model, codename?, approved,
     *       calibration_points: [ { distanceM, pixelWidth, pixelRow }, ... ] },
     *     ... ]
     * Only entries with approved=true are applied.
     */
    // The calibration library website is deployed at drakosanctis.com and
    // serves data/calibration_profiles.json alongside the
    // data/calibration_library.json the site itself consumes. Falling back
    // to the Netlify preview domain keeps staging / non-production builds
    // working. Both endpoints are plain static JSON; failure is
    // non-blocking and the app reverts to synthetic LUT defaults with a
    // Toast prompting the user to run the 10-point calibration walk.
    private static final String[] CALIBRATION_PROFILE_URLS = {
            "https://drakosanctis.com/data/calibration_profiles.json",
            "https://www.drakosanctis.com/data/calibration_profiles.json",
            "https://auriga-calibration.netlify.app/data/calibration_profiles.json"
    };

    /**
     * Synchronous boot-time load of the bundled profile asset. Runs on
     * the main thread during onCreate so the FiducialLUT has
     * device-matching anchors before the first frame ticks. A missing
     * asset is the common case on non-dev devices and is a silent
     * no-op. Any profile loaded here will be overwritten by the
     * network fetch once it resolves.
     */
    private void loadBundledCalibrationProfile() {
        if (lut == null) return;
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        String manufacturer =
                Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        // Always log the actual Build.MODEL + MANUFACTURER so a user
        // whose profile failed to load can report the exact strings
        // back to us -- v0.2.0 failed silently on the A07 and we had
        // no way to know whether the asset filename, the JSON model
        // field, or the manifest match was the mismatch.
        Log.i(TAG, "Device: manufacturer='" + manufacturer
                + "' model='" + model + "' (Build.DEVICE='" + Build.DEVICE
                + "', Build.PRODUCT='" + Build.PRODUCT + "')");
        if (model.isEmpty()) return;
        String assetPath = "default_profile_" + model + ".json";
        boolean loaded = lut.loadFromAssetJson(
                this, assetPath, manufacturer, model);
        if (loaded) {
            Log.i(TAG, "Loaded bundled calibration profile: " + assetPath);
            return;
        }
        // Fallback #1: retry with the SM-A057F asset for known A07
        // codename variants. Some A07 ROMs report Build.MODEL as the
        // codename rather than the sales model.
        if ("a05s".equalsIgnoreCase(model)
                || "a057f".equalsIgnoreCase(model)) {
            boolean alias = lut.loadFromAssetJson(
                    this, "default_profile_SM-A057F.json", manufacturer, "SM-A057F");
            if (alias) {
                Log.i(TAG, "Loaded SM-A057F profile via codename alias '" + model + "'");
                return;
            }
        }
        Log.w(TAG, "No bundled calibration profile matched "
                + manufacturer + " " + model + " (asset=" + assetPath + ")");
    }

    /**
     * Quick sanitizer for Build.* fields before they are shown on
     * the diagnostic overlay. Android guarantees these are non-null
     * but the overlay is mono-spaced and a rogue Unicode char would
     * break alignment; strip to ASCII printable and cap length.
     */
    private static String safeBuildTag(String s) {
        if (s == null) return "?";
        String t = s.trim();
        if (t.isEmpty()) return "?";
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length() && sb.length() < 24; i++) {
            char c = t.charAt(i);
            if (c >= 0x20 && c < 0x7F) sb.append(c);
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }

    private void fetchCalibrationProfileAsync() {
        final String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        final String manufacturer =
                Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        new Thread(() -> {
            String body = null;
            for (String url : CALIBRATION_PROFILE_URLS) {
                body = tryFetchText(url);
                if (body != null) break;
            }
            if (body == null) {
                Log.w(TAG, "Calibration profile fetch: all endpoints failed");
                return;
            }
            List<FiducialLUT.TrainingPoint> matched =
                    parseProfile(body, manufacturer, model);
            if (matched != null && matched.size() >= 2 && lut != null) {
                lut.loadProfile(matched, "network");
                runOnUiThread(() -> Toast.makeText(
                        MainActivity.this,
                        "Loaded calibration for " + model,
                        Toast.LENGTH_SHORT).show());
            } else {
                runOnUiThread(() -> Toast.makeText(
                        MainActivity.this,
                        "No calibration profile for " + model
                                + " - run 10-point calibration or contribute readings.",
                        Toast.LENGTH_LONG).show());
            }
        }, "auriga-cal-fetch").start();
    }

    private static String tryFetchText(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static List<FiducialLUT.TrainingPoint> parseProfile(
            String json, String manufacturer, String model) {
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject row = arr.getJSONObject(i);
                if (!row.optBoolean("approved", false)) continue;
                String rowManufacturer = row.optString("manufacturer", "").trim();
                String rowModel = row.optString("model", "").trim();
                boolean manuMatches = manufacturer.equalsIgnoreCase(rowManufacturer)
                        || rowManufacturer.isEmpty();
                boolean modelMatches = model.equalsIgnoreCase(rowModel);
                if (!(manuMatches && modelMatches)) continue;
                JSONArray pts = row.optJSONArray("calibration_points");
                if (pts == null) continue;
                List<FiducialLUT.TrainingPoint> out = new ArrayList<>();
                for (int j = 0; j < pts.length(); j++) {
                    JSONObject p = pts.getJSONObject(j);
                    out.add(new FiducialLUT.TrainingPoint(
                            (float) p.getDouble("distanceM"),
                            (float) p.getDouble("pixelWidth"),
                            (float) p.getDouble("pixelRow")));
                }
                return out;
            }
        } catch (Throwable ignored) { }
        return null;
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
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        // Preview can reconfigure mid-session on Samsung after a
        // rotate / camera swap; keep the bitmap target aligned so a
        // resized surface does not reintroduce the aspect-stretch
        // bias. applyBitmapSizeFromTexture is a no-op if the target
        // already matches.
        applyBitmapSizeFromTexture(width, height);
    }
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
        // Sensor listeners leak the Activity (SensorManager -> HardwareHAL
        // -> Context) if we don't unregister. HardwareHAL.stopPitchSensor()
        // is idempotent and null-guards internally.
        if (hal != null) hal.stopPitchSensor();
    }
}
