package com.drakosanctis.auriga;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import android.speech.tts.TextToSpeech;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AurigaCoreService — Session 17 (Phase 3 — Integration).
 *
 * <p>The single Android foreground Service that owns and coordinates every
 * Auriga engine. Running as a foreground Service (with a persistent
 * notification) lets the analysis pipeline continue when the user presses
 * home or switches apps, which is essential for a spatial-navigation
 * accessibility tool that must speak hazard warnings at all times.
 *
 * <h3>Module roster (wired in {@link #onCreate})</h3>
 * <ul>
 *   <li>{@link OutputLayer} — TTS + haptic + Braille unified output bus</li>
 *   <li>{@link CommandRouter} — spoken command dispatcher</li>
 *   <li>{@link PassiveHazardEngine} — always-on audio hazard detector</li>
 *   <li>{@link StairSenseEngine} — step-edge detector</li>
 *   <li>{@link TrafficSenseEngine} — vehicle + traffic-light detector</li>
 *   <li>{@link CrossingGuardEngine} — pedestrian-crossing state machine</li>
 *   <li>{@link EmergencySOSEngine} — emergency call / SMS / haptic SOS</li>
 *   <li>{@link ColorSenseEngine} — dominant colour + traffic-light reader</li>
 *   <li>{@link FaceVaultEngine} — offline face recognition</li>
 *   <li>{@link PillGuardEngine} — medication identification</li>
 *   <li>{@link CashLensEngine} — currency denomination identifier</li>
 *   <li>{@link LabelReaderEngine} — product label + expiry OCR + barcode</li>
 *   <li>{@link SpatialMemoryEngine} — route recording and replay</li>
 *   <li>{@link SceneDescriberEngine} — full scene narration</li>
 *   <li>{@link GodsEyeOrchestrator} — tactical multi-track audit</li>
 * </ul>
 *
 * <h3>Frame pipeline ({@link #onStartCommand})</h3>
 * CameraX ImageAnalysis delivers NV21 frames on a dedicated analyser thread.
 * Each frame is gated to a maximum rate of one frame per 100 ms to prevent
 * the CPU from saturating. Four engines ({@link StairSenseEngine},
 * {@link TrafficSenseEngine}, and two zone analysers) run in parallel on a
 * 4-thread executor. Results are merged into a spoken output decision by
 * {@link #mergeAndSpeak}.
 *
 * <h3>CommandRouter skills registered</h3>
 * <pre>
 *   "describe"      → SceneDescriberEngine.describe()
 *   "crossing"      → CrossingGuardEngine.activate()
 *   "stair"         → on-demand stair scan
 *   "navigate"      → SpatialMemoryEngine.startReplay()
 *   "record route"  → SpatialMemoryEngine.startRecording()
 *   "stop route"    → SpatialMemoryEngine.stopRecording()
 *   "identify pill" → PillGuardEngine.identify()
 *   "read label"    → LabelReaderEngine.analyse()
 *   "read cash"     → CashLensEngine.identify()
 *   "find face"     → FaceVaultEngine.identify()
 *   "emergency"     → EmergencySOSEngine.trigger()
 *   "mute"          → OutputLayer.setMuted(true)
 *   "unmute"        → OutputLayer.setMuted(false)
 *   "what colour"   → ColorSenseEngine.analyse()
 *   "stop"          → all modes off
 *   "help"          → list available commands
 * </pre>
 *
 * <h3>Lifecycle</h3>
 * <pre>
 *   onCreate()       — initialise all engines, register skills, start hazard listener
 *   onStartCommand() — promote to foreground, bind CameraX analysis loop
 *   onDestroy()      — stop all engines, release camera, shut down executors
 * </pre>
 *
 * <h3>Required manifest entries</h3>
 * <pre>
 *   &lt;uses-permission android:name="android.permission.CAMERA" /&gt;
 *   &lt;uses-permission android:name="android.permission.RECORD_AUDIO" /&gt;
 *   &lt;uses-permission android:name="android.permission.FOREGROUND_SERVICE" /&gt;
 *   &lt;uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" /&gt;
 *
 *   &lt;service android:name=".AurigaCoreService"
 *            android:foregroundServiceType="camera|microphone"
 *            android:exported="false" /&gt;
 * </pre>
 */
public class AurigaCoreService extends Service {

    private static final String TAG = "AurigaCoreService";

    // Notification channel
    private static final String CHANNEL_ID   = "auriga_core";
    private static final String CHANNEL_NAME = "Auriga Spatial Intelligence";
    private static final int    NOTIF_ID     = 1001;

    // Frame rate gate — process at most one frame every N ms
    private static final long FRAME_GATE_MS = 100L;

    // Parallel analysis pool — 4 threads for stair / traffic / zone / spare
    private ExecutorService analysisPool;

    // ─────────────────────────────────────────────────────────────────────────
    // Engines
    // ─────────────────────────────────────────────────────────────────────────
    private OutputLayer          outputLayer;
    private CommandRouter        commandRouter;
    private PassiveHazardEngine  passiveHazard;
    private StairSenseEngine     stairSense;
    private TrafficSenseEngine   trafficSense;
    private CrossingGuardEngine  crossingGuard;
    private EmergencySOSEngine   emergencySOS;
    private ColorSenseEngine     colorSense;
    private FaceVaultEngine      faceVault;
    private PillGuardEngine      pillGuard;
    private CashLensEngine       cashLens;
    private LabelReaderEngine    labelReader;
    private SpatialMemoryEngine  spatialMemory;
    private SceneDescriberEngine sceneDescriber;
    private GodsEyeOrchestrator  godsEye;
    private YoloDetector         yoloDetector;

    // ─────────────────────────────────────────────────────────────────────────
    // Singleton — allows AurigaVoiceEngine to route camera commands here
    // ─────────────────────────────────────────────────────────────────────────
    public static volatile AurigaCoreService instance;

    // ─────────────────────────────────────────────────────────────────────────
    // Frame state (populated via FrameRelay, not CameraX direct binding)
    // ─────────────────────────────────────────────────────────────────────────
    private final AtomicLong lastFrameMs = new AtomicLong(0L);

    // Latest frame snapshot — updated by processFrame(), read by CommandRouter skills
    private final AtomicReference<FrameSnapshot> latestFrame = new AtomicReference<>();

    // FrameRelay listener — held so it can be removed in onDestroy()
    private AurigaInterfaces.IFrameProvider.FrameListener frameListener;

    // ─────────────────────────────────────────────────────────────────────────
    // LLM / skill-engine tier (TTS-dependent; wired in initTts())
    // ─────────────────────────────────────────────────────────────────────────
    private TextToSpeech      tts;
    private boolean           ttsReady     = false;
    private AurigaSkillEngine skillEngine;
    private KnowledgeCache    knowledgeCache;
    private MindEngine        mindEngine;

    // ─────────────────────────────────────────────────────────────────────────
    // Service lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "onCreate: initialising all engines…");
        instance = this;

        analysisPool = Executors.newFixedThreadPool(4);

        initEngines();
        registerAllSkills();

        // Register as FrameRelay listener — receives frames pushed by LocatorActivity.
        // This replaces the old CameraX direct binding, which caused a SecurityException
        // (camera HAL "different process than original client") on Android 12+ when both
        // the activity and the service tried to own the camera simultaneously.
        frameListener = this::processFrame;
        FrameRelay.get().addListener(frameListener);

        // Start always-on audio hazard classifier (microphone-based, no camera needed)
        passiveHazard.start((hazardType, confidence) -> {
            String msg = "Warning! " + hazardType.name().replace("_", " ").toLowerCase(Locale.ROOT)
                       + " detected.";
            outputLayer.speak(msg, AurigaInterfaces.OutputPriority.EMERGENCY);
            outputLayer.haptic(AurigaInterfaces.HapticPattern.SOS,
                               AurigaInterfaces.HapticZone.ALL);
        });

        // Boot TTS → skill engine → on-device LLM dispatch chain
        initTts();

        Log.i(TAG, "onCreate: engines ready, FrameRelay registered, hazard listener started.");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand: promoting to foreground.");
        startForeground(NOTIF_ID, buildNotification());
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy: shutting down.");
        instance = null;

        // Disconnect from FrameRelay
        if (frameListener != null) {
            FrameRelay.get().removeListener(frameListener);
            frameListener = null;
        }

        // Stop perception engines
        passiveHazard.stop();
        labelReader.shutdown();
        outputLayer.shutdown();

        // Release LLM / skill engine resources
        if (mindEngine != null) { mindEngine.close(); mindEngine = null; }
        if (tts != null)        { tts.shutdown();     tts = null;        }
        ttsReady = false;

        // Shut down analysis pool
        analysisPool.shutdown();
        try {
            if (!analysisPool.awaitTermination(2, TimeUnit.SECONDS)) {
                analysisPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            analysisPool.shutdownNow();
        }

        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ─────────────────────────────────────────────────────────────────────────
    // Engine initialisation
    // ─────────────────────────────────────────────────────────────────────────

    private void initEngines() {
        // OutputLayer and CommandRouter have no external dependencies — init first.
        outputLayer    = new OutputLayer(this);
        commandRouter  = new CommandRouter();

        // Perception engines — no Context or no inter-engine deps.
        passiveHazard  = new PassiveHazardEngine(this);
        stairSense     = new StairSenseEngine();
        trafficSense   = new TrafficSenseEngine();   // stateful, no Context arg
        colorSense     = new ColorSenseEngine();     // stateless

        // CrossingGuard depends on colorSense + trafficSense — init after both.
        crossingGuard  = new CrossingGuardEngine(colorSense, trafficSense, outputLayer);

        // Safety and identification engines.
        emergencySOS   = new EmergencySOSEngine(this, outputLayer);
        faceVault      = new FaceVaultEngine(this);
        pillGuard      = new PillGuardEngine(this);
        cashLens       = new CashLensEngine(this);
        labelReader    = new LabelReaderEngine(this);
        spatialMemory  = new SpatialMemoryEngine(this);
        sceneDescriber = new SceneDescriberEngine(this);
        godsEye        = new GodsEyeOrchestrator();
        yoloDetector   = YoloDetector.tryCreate(this);

        Log.i(TAG, "YoloDetector available: " + (yoloDetector != null));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CommandRouter skill registrations
    // ─────────────────────────────────────────────────────────────────────────

    private void registerAllSkills() {

        // ── Scene description ─────────────────────────────────────────────
        commandRouter.registerSkill("describe", (cmd, arg) -> {
            FrameSnapshot snap = latestFrame.get();
            if (snap == null) return "No camera frame available yet.";
            List<Detection> detections = snap.detections;
            if (detections == null) detections = new ArrayList<>();
            return sceneDescriber.describe(detections, snap.width, snap.height);
        });

        // ── Crossing guard ────────────────────────────────────────────────
        commandRouter.registerSkill("crossing mode", (cmd, arg) -> {
            crossingGuard.activate();
            return "Crossing mode activated. I will let you know when it is safe to cross.";
        });

        commandRouter.registerSkill("stop crossing", (cmd, arg) -> {
            crossingGuard.deactivate();
            return "Crossing mode stopped.";
        });

        // ── Stair scan ────────────────────────────────────────────────────
        commandRouter.registerSkill("stair", (cmd, arg) -> {
            FrameSnapshot snap = latestFrame.get();
            if (snap == null) return "No camera frame available.";
            AurigaInterfaces.StairResult r =
                stairSense.analyse(snap.nv21, snap.width, snap.height);
            if (!r.stairsDetected) return "No stairs detected ahead.";
            return "Stairs detected. " + r.stepCount + " steps, " +
                   r.direction.name().toLowerCase(Locale.ROOT) + ", approximately " +
                   String.format(Locale.ROOT, "%.1f metres", r.distanceMetres) + " ahead.";
        });

        // ── Spatial memory: record route ──────────────────────────────────
        commandRouter.registerSkill("record route", (cmd, arg) -> {
            String routeName = arg != null && !arg.trim().isEmpty() ? arg.trim() : "unnamed";
            spatialMemory.startRecording(routeName);
            return "Recording route: " + routeName + ". Walk the route now. Say 'stop route' when done.";
        });

        commandRouter.registerSkill("stop route", (cmd, arg) -> {
            spatialMemory.stopRecording();
            return "Route recording complete.";
        });

        commandRouter.registerSkill("navigate", (cmd, arg) -> {
            if (arg == null || arg.trim().isEmpty()) {
                List<String> names = spatialMemory.getAllRouteNames();
                if (names.isEmpty()) return "No routes recorded yet.";
                return "Available routes: " + String.join(", ", names);
            }
            spatialMemory.startReplay(arg.trim(), new AurigaInterfaces.ReplayCallback() {
                public void onGuidanceStep(String instr, int step, int total) {
                    outputLayer.speak(instr, AurigaInterfaces.OutputPriority.HIGH);
                }
                public void onRouteComplete(String name) {
                    outputLayer.speak("You have completed route: " + name,
                        AurigaInterfaces.OutputPriority.HIGH);
                }
                public void onRouteError(String reason) {
                    outputLayer.speak("Navigation error: " + reason,
                        AurigaInterfaces.OutputPriority.NORMAL);
                }
            });
            return "Starting navigation to: " + arg.trim();
        });

        // ── Identification skills ─────────────────────────────────────────
        commandRouter.registerSkill("identify pill", (cmd, arg) -> {
            FrameSnapshot snap = latestFrame.get();
            if (snap == null) return "No camera frame available.";
            AurigaInterfaces.PillResult r =
                pillGuard.identify(snap.nv21, snap.width, snap.height);
            if (r.commonName == null)
                return "I could not safely identify this pill. " + r.cautionMessage;
            return r.commonName + ". " + r.cautionMessage;
        });

        commandRouter.registerSkill("read label", (cmd, arg) -> {
            FrameSnapshot snap = latestFrame.get();
            if (snap == null) return "No camera frame available.";
            LabelReaderEngine.LabelResult r =
                labelReader.analyse(snap.nv21, snap.width, snap.height, 0);
            return r.toSpokenSummary();
        });

        commandRouter.registerSkill("read cash", (cmd, arg) -> {
            FrameSnapshot snap = latestFrame.get();
            if (snap == null) return "No camera frame available.";
            AurigaInterfaces.CashResult r =
                cashLens.identify(snap.nv21, snap.width, snap.height);
            if (r.denomination == null) return "I cannot identify this note clearly.";
            return r.denomination;
        });

        commandRouter.registerSkill("find face", (cmd, arg) -> {
            FrameSnapshot snap = latestFrame.get();
            if (snap == null) return "No camera frame available.";
            List<AurigaInterfaces.FaceMatch> matches =
                faceVault.identify(snap.nv21, snap.width, snap.height);
            if (matches.isEmpty()) return "No recognised person in view.";
            AurigaInterfaces.FaceMatch best = matches.get(0);
            String zone = best.bearingDeg < -20f ? "to your left" :
                          best.bearingDeg >  20f ? "to your right" : "ahead of you";
            return best.name + " is " +
                   String.format(Locale.ROOT, "%.1f", best.distanceMetres) +
                   " metres " + zone + ".";
        });

        commandRouter.registerSkill("enrol face", (cmd, arg) -> {
            if (arg == null || arg.trim().isEmpty())
                return "Please say a name after 'enrol face'.";
            outputLayer.speak("I will capture five frames. Hold still.",
                AurigaInterfaces.OutputPriority.NORMAL);
            captureFaceEnrolment(arg.trim());
            return "Face enrolment started for " + arg.trim() + ".";
        });

        // ── Colour reading ────────────────────────────────────────────────
        commandRouter.registerSkill("what colour", (cmd, arg) -> {
            FrameSnapshot snap = latestFrame.get();
            if (snap == null) return "No camera frame available.";
            int cx = snap.width / 4, cy = snap.height / 4;
            int cw = snap.width / 2, ch = snap.height / 2;
            AurigaInterfaces.ColorResult r =
                colorSense.analyse(snap.nv21, snap.width, snap.height, cx, cy, cw, ch);
            return "The colour is " + r.colorName + ".";
        });

        // ── Emergency SOS ─────────────────────────────────────────────────
        commandRouter.registerSkill("emergency", (cmd, arg) -> {
            FrameSnapshot snap = latestFrame.get();
            String envDesc = snap != null && snap.detections != null
                ? sceneDescriber.describe(snap.detections, snap.width, snap.height)
                : "unknown location";
            emergencySOS.trigger(envDesc);
            return "Emergency SOS activated.";
        });

        commandRouter.registerSkill("cancel emergency", (cmd, arg) -> {
            emergencySOS.cancel();
            return "Emergency cancelled.";
        });

        // ── Output control ────────────────────────────────────────────────
        commandRouter.registerSkill("mute", (cmd, arg) -> {
            outputLayer.setMuted(true);
            return null;   // silent confirmation (we're muted)
        });

        commandRouter.registerSkill("unmute", (cmd, arg) -> {
            outputLayer.setMuted(false);
            return "Unmuted.";
        });

        // ── Stop all modes ────────────────────────────────────────────────
        commandRouter.registerSkill("stop", (cmd, arg) -> {
            crossingGuard.deactivate();
            return "All modes stopped.";
        });

        // ── Help ──────────────────────────────────────────────────────────
        commandRouter.registerSkill("help", (cmd, arg) ->
            "Available commands: describe, crossing mode, stair, navigate, " +
            "record route, stop route, identify pill, read label, read cash, " +
            "find face, enrol face, what colour, emergency, mute, unmute, stop."
        );

        // ── Currency switching ────────────────────────────────────────────
        commandRouter.registerSkill("switch currency", (cmd, arg) -> {
            if (arg == null || arg.trim().isEmpty())
                return "Please say a currency code. For example: switch currency to GBP.";
            String iso = arg.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
            cashLens.setCurrency(iso);
            return "Currency set to " + iso + ".";
        });

        Log.i(TAG, "Registered " + commandRouter.getRegisteredTriggers().size() + " skills.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Per-frame analysis pipeline (frames arrive via FrameRelay from LocatorActivity)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called by the {@link FrameRelay} listener on LocatorActivity's analysisExecutor
     * thread. Heavy engine work is immediately offloaded to {@link #analysisPool}.
     */
    private void processFrame(byte[] nv21, int width, int height, int rotation) {
        // ── Frame rate gate: max 1 frame per FRAME_GATE_MS ───────────────
        long now = System.currentTimeMillis();
        if (now - lastFrameMs.get() < FRAME_GATE_MS) return;
        lastFrameMs.set(now);

        if (nv21 == null) return;

        // ── YOLO detections (if model bundled) ───────────────────────────
        List<Detection> detections = null;
        if (yoloDetector != null) {
            Bitmap bmp = nv21ToBitmap(nv21, width, height);
            if (bmp != null) {
                detections = yoloDetector.detect(bmp);
                bmp.recycle();
            }
        }

        // Stash the latest frame for on-demand commands
        latestFrame.set(new FrameSnapshot(nv21, width, height, rotation, detections));

        // ── Parallel analysis (4 futures) ────────────────────────────────
        final byte[] frameCopy    = nv21;     // effectively final for lambdas
        final int    frameWidth   = width;
        final int    frameHeight  = height;
        final List<Detection> dets = detections;

        Future<AurigaInterfaces.StairResult>   futureStair =
            analysisPool.submit(() -> stairSense.analyse(frameCopy, frameWidth, frameHeight));

        Future<AurigaInterfaces.TrafficResult> futureTraffic =
            analysisPool.submit(() -> trafficSense.analyse(frameCopy, frameWidth, frameHeight));

        Future<String> futureCrossing = analysisPool.submit(() -> {
            if (crossingGuard.isActive()) {
                crossingGuard.onFrame(frameCopy, frameWidth, frameHeight);
            }
            return null;
        });

        Future<String> futureGodsEye = analysisPool.submit(() -> {
            if (dets != null && !dets.isEmpty()) {
                for (Detection d : dets) {
                    godsEye.onVectorReceived(detectionToJson(d));
                }
            }
            return null;
        });

        // ── Collect results (best-effort — log and continue on timeout) ──
        AurigaInterfaces.StairResult   stairResult   = null;
        AurigaInterfaces.TrafficResult trafficResult = null;

        try { stairResult   = futureStair.get(80, TimeUnit.MILLISECONDS); }
        catch (Exception ignored) {}

        try { trafficResult = futureTraffic.get(80, TimeUnit.MILLISECONDS); }
        catch (Exception ignored) {}

        try { futureCrossing.get(80, TimeUnit.MILLISECONDS); }
        catch (Exception ignored) {}

        try { futureGodsEye.get(80, TimeUnit.MILLISECONDS); }
        catch (Exception ignored) {}

        // ── Merge results → spoken output (priority ordered) ─────────────
        mergeAndSpeak(stairResult, trafficResult, dets);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Output priority merger
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Priority order (highest first):
     * 1. Traffic vehicle approaching with short TTC
     * 2. Stairs detected
     * 3. Traffic light state change
     * (All other passive narration is suppressed during the 100ms frame loop —
     *  it is only triggered by explicit voice commands via CommandRouter.)
     */
    private void mergeAndSpeak(AurigaInterfaces.StairResult stair,
                                AurigaInterfaces.TrafficResult traffic,
                                List<Detection> detections) {
        // Vehicle approaching — HIGH priority
        if (traffic != null && traffic.vehicleApproaching && traffic.ttcSeconds < 3.0f) {
            String zone = traffic.approachZone == AurigaInterfaces.Zone.LEFT  ? "from the left" :
                          traffic.approachZone == AurigaInterfaces.Zone.RIGHT ? "from the right" :
                          "ahead";
            outputLayer.speak("Vehicle approaching " + zone + "!",
                AurigaInterfaces.OutputPriority.HIGH);
            outputLayer.haptic(AurigaInterfaces.HapticPattern.FAST_PULSE,
                               AurigaInterfaces.HapticZone.ALL);
            return;
        }

        // Traffic light state — NORMAL priority (voiced once per state change)
        if (traffic != null &&
            traffic.lightState != AurigaInterfaces.TrafficLightState.UNKNOWN) {
            String lightMsg = null;
            switch (traffic.lightState) {
                case GREEN: lightMsg = "Traffic light is green.";  break;
                case RED:   lightMsg = "Traffic light is red.";    break;
                case AMBER: lightMsg = "Traffic light is amber.";  break;
                default: break;
            }
            if (lightMsg != null) {
                outputLayer.speak(lightMsg, AurigaInterfaces.OutputPriority.NORMAL);
            }
        }

        // Stairs detected — HIGH priority
        if (stair != null && stair.stairsDetected) {
            String direction = stair.direction == AurigaInterfaces.StairDirection.ASCENDING
                ? "going up" : "going down";
            String msg = "Stairs " + direction + ", " + stair.stepCount + " steps, " +
                         String.format(Locale.ROOT, "%.1f metres ahead.", stair.distanceMetres);
            outputLayer.speak(msg, AurigaInterfaces.OutputPriority.HIGH);
            outputLayer.haptic(AurigaInterfaces.HapticPattern.STAIR_WARN,
                               AurigaInterfaces.HapticZone.CENTER);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Face enrolment helper (captures 5 frames asynchronously)
    // ─────────────────────────────────────────────────────────────────────────

    private void captureFaceEnrolment(String personName) {
        analysisPool.submit(() -> {
            byte[][] frames  = new byte[5][];
            int capturedW = 640, capturedH = 480;
            for (int i = 0; i < 5; i++) {
                FrameSnapshot snap = latestFrame.get();
                if (snap != null && snap.nv21 != null) {
                    frames[i] = snap.nv21;
                    capturedW  = snap.width;
                    capturedH  = snap.height;
                }
                try { Thread.sleep(400); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            // Require at least one valid frame before enrolment
            boolean hasFrame = false;
            for (byte[] f : frames) { if (f != null) { hasFrame = true; break; } }
            if (!hasFrame) {
                outputLayer.speak(
                    "Face enrolment failed: no camera frame available. "
                    + "Please open the locator screen first.",
                    AurigaInterfaces.OutputPriority.NORMAL);
                return;
            }

            boolean ok = faceVault.enrol(personName, frames, capturedW, capturedH);
            String msg = ok
                ? personName + " enrolled successfully."
                : "Face enrolment failed. Please try again in better lighting.";
            outputLayer.speak(msg, AurigaInterfaces.OutputPriority.NORMAL);
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Foreground notification
    // ─────────────────────────────────────────────────────────────────────────

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Auriga spatial intelligence is running");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }

        Intent launchIntent = getPackageManager()
            .getLaunchIntentForPackage(getPackageName());
        PendingIntent pi = PendingIntent.getActivity(
            this, 0, launchIntent != null ? launchIntent : new Intent(),
            PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Auriga is active")
            .setContentText("Spatial intelligence running in background")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(pi)
            .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Conversion utilities
    // ─────────────────────────────────────────────────────────────────────────

    /** Convert NV21 bytes → Bitmap for YoloDetector. */
    private static Bitmap nv21ToBitmap(byte[] nv21, int width, int height) {
        try {
            YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuv.compressToJpeg(new Rect(0, 0, width, height), 85, out);
            byte[] jpeg = out.toByteArray();
            return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        } catch (Exception e) {
            return null;
        }
    }

    /** Simple JSON serialisation for GodsEyeOrchestrator. */
    private static String detectionToJson(Detection d) {
        return String.format(Locale.ROOT,
            "{\"label\":\"%s\",\"conf\":%.2f,\"cx\":%.3f,\"cy\":%.3f,\"area\":%.4f}",
            d.label, d.confidence, d.centerX(), d.centerY(), d.area());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Frame snapshot record
    // ─────────────────────────────────────────────────────────────────────────

    private static class FrameSnapshot {
        final byte[]          nv21;
        final int             width;
        final int             height;
        final int             rotation;
        final List<Detection> detections;

        FrameSnapshot(byte[] nv21, int width, int height, int rotation,
                      List<Detection> detections) {
            this.nv21       = nv21;
            this.width      = width;
            this.height     = height;
            this.rotation   = rotation;
            this.detections = detections;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public command dispatch (called by AurigaVoiceService / AurigaButlerService)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Full 5-tier voice command dispatch. Called by AurigaVoiceService /
     * AurigaButlerService or any component that wants the complete chain.
     *
     * <ol>
     *   <li>CommandRouter — camera-dependent skills (describe, stair, face, pill…)</li>
     *   <li>AurigaSkillEngine — timers, alarms, weather, compass…</li>
     *   <li>AurigaKnowledge — rule-based offline KB (instant)</li>
     *   <li>MindEngine — on-device Qwen LLM (streams via its own TTS)</li>
     *   <li>KnowledgeCache context / AurigaKnowledge.fallback() safety net</li>
     * </ol>
     */
    public void onVoiceCommand(String spokenText) {
        if (spokenText == null || spokenText.trim().isEmpty()) return;
        String cmd = spokenText.trim().toLowerCase(Locale.ROOT);

        // Tier 1: camera-dependent skills
        if (tryDispatchCameraCommand(cmd)) return;

        // Tier 2: skill engine (timers, alarms, weather, etc.)
        if (skillEngine != null && skillEngine.dispatch(cmd)) return;

        // Tier 3: rule-based KB (instant, fully offline)
        String kbAnswer = AurigaKnowledge.answer(cmd);
        if (kbAnswer != null) {
            outputLayer.speak(kbAnswer, AurigaInterfaces.OutputPriority.NORMAL);
            AurigaMemoryStore.store(this, "assistant", kbAnswer, "voice");
            return;
        }

        // Tier 4: on-device LLM
        if (mindEngine != null) {
            AurigaMemoryStore.store(this, "assistant", "[MindEngine responding]", "voice");
            mindEngine.ask(cmd, null);
            return;
        }

        // Tier 5: context from KnowledgeCache or AurigaKnowledge.fallback()
        String ctx   = knowledgeCache != null ? knowledgeCache.getContext(cmd) : "";
        String reply = !ctx.isEmpty() ? ctx : AurigaKnowledge.fallback(cmd);
        outputLayer.speak(reply, AurigaInterfaces.OutputPriority.NORMAL);
        AurigaMemoryStore.store(this, "assistant", reply, "voice");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LLM / skill engine bootstrap
    // ─────────────────────────────────────────────────────────────────────────

    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            ttsReady = (status == TextToSpeech.SUCCESS);
            if (!ttsReady || tts == null) {
                Log.w(TAG, "TTS init failed — skill engine and LLM will be unavailable.");
                return;
            }
            tts.setLanguage(java.util.Locale.getDefault());
            skillEngine    = new AurigaSkillEngine(this, tts);
            knowledgeCache = new KnowledgeCache(this);
            knowledgeCache.warmUp();
            MindEngine.createAsync(this, knowledgeCache, tts,
                    engine -> mindEngine = engine);

            // Hot-reload: if a Qwen model finishes downloading while the service is live,
            // bootstrap MindEngine immediately so the next command uses it without a restart.
            if (AurigaApplication.modelDownloadManager != null) {
                AurigaApplication.modelDownloadManager.setTts(tts);
                AurigaApplication.modelDownloadManager.registerListener(
                        new ModelDownloadManager.DownloadListener() {
                    @Override public void onProgress(
                            ModelDownloadManager.ModelId model, int pct) {}
                    @Override public void onStateChanged(
                            ModelDownloadManager.ModelId model,
                            ModelDownloadManager.ModelState state) {
                        if (state == ModelDownloadManager.ModelState.READY
                                && mindEngine == null) {
                            MindEngine.createAsync(AurigaCoreService.this,
                                    knowledgeCache, tts,
                                    engine -> mindEngine = engine);
                        }
                    }
                });
            }
            Log.i(TAG, "TTS ready — skill engine + LLM chain initialised.");
        });
    }

    /**
     * Try to route {@code cmd} through the CommandRouter (camera-dependent skills only).
     *
     * @return {@code true} if a skill matched and the response has been spoken;
     *         {@code false} if no skill matched and the caller should try the next tier.
     */
    public boolean tryDispatchCameraCommand(String cmd) {
        String response = commandRouter.dispatch(cmd);
        if (response != null && !response.isEmpty()) {
            outputLayer.speak(response, AurigaInterfaces.OutputPriority.HIGH);
            return true;
        }
        return false;
    }

    /**
     * Returns the wired OutputLayer so activities can request speech output
     * without starting their own TTS instance.
     */
    public AurigaInterfaces.IOutputLayer getOutputLayer() { return outputLayer; }
}
