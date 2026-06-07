package com.drakosanctis.auriga;

import android.content.Context;

/**
 * AurigaInterfaces — single source of truth for all module contracts.
 *
 * Every engine in the Auriga ecosystem implements one of the inner interfaces
 * declared here. No implementations live in this file — only interface,
 * class, and enum declarations. This ensures all modules can be compiled
 * independently and wired together via AurigaCoreService without circular
 * dependencies.
 *
 * Build order: this file must exist before any module implementation.
 * See AURIGA/docs/AURIGA_FULL_BUILD_BLUEPRINT.md Section 8.
 */
public class AurigaInterfaces {

    // ─────────────────────────────────────────────────────────────────────────
    // SHARED ENUMS & VALUE TYPES
    // ─────────────────────────────────────────────────────────────────────────

    public enum Zone {
        LEFT, CENTER, RIGHT, UNKNOWN
    }

    public enum StairDirection {
        ASCENDING, DESCENDING, UNKNOWN
    }

    public enum TrafficLightState {
        RED, AMBER, GREEN, UNKNOWN
    }

    public enum OutputPriority {
        BACKGROUND,
        NORMAL,
        HIGH,
        EMERGENCY
    }

    public enum HapticPattern {
        SLOW_PULSE,
        FAST_PULSE,
        SINGLE,
        SOS,
        STAIR_WARN,
        OBSTACLE_NEAR,
        COMMAND_ACCEPTED,
        COMMAND_REJECTED
    }

    public enum HapticZone {
        LEFT, CENTER, RIGHT, ALL
    }

    public enum HazardType {
        SMOKE_ALARM,
        CO_ALARM,
        DOG_BARK_AGGRESSIVE,
        CAR_HORN,
        GLASS_BREAK,
        GUNSHOT,
        UNKNOWN
    }

    public enum ButlerActionCode {
        AURIGA_NAVIGATE,
        AURIGA_READ_LABEL,
        AURIGA_IDENTIFY_PILL,
        AURIGA_DESCRIBE_SCENE,
        AURIGA_IDENTIFY_FACE,
        AURIGA_READ_CASH,
        AURIGA_CROSSING_MODE,
        AURIGA_STAIR_MODE,
        AURIGA_TARGETS,
        AURIGA_CALIBRATE,
        AURIGA_SOS,
        SYSTEM_OPEN_APP,
        SYSTEM_GO_HOME,
        SYSTEM_GO_BACK,
        SYSTEM_RECENT_APPS,
        SYSTEM_NOTIFICATIONS,
        SYSTEM_BATTERY,
        SYSTEM_WIFI_STATUS,
        SYSTEM_BRIGHTNESS_UP,
        SYSTEM_BRIGHTNESS_DOWN,
        SYSTEM_VOLUME_UP,
        SYSTEM_VOLUME_DOWN,
        SYSTEM_MUTE,
        SYSTEM_TORCH,
        SYSTEM_LOCK_SCREEN,
        INFO_TIME,
        INFO_DATE,
        INFO_DAY,
        INFO_BATTERY_PERCENT,
        INFO_STORAGE,
        INFO_SIGNAL,
        COMM_CALL,
        COMM_SEND_SMS,
        COMM_READ_MESSAGES,
        COMM_ANSWER_CALL,
        COMM_REJECT_CALL,
        COMM_LAST_CALLER,
        MEDIA_PLAY,
        MEDIA_PAUSE,
        MEDIA_NEXT,
        MEDIA_PREVIOUS,
        MEDIA_STOP,
        HELP_LIST_COMMANDS,
        HELP_TUTORIAL,
        HELP_WHAT_CAN_YOU_DO,
        HELP_FEATURE_TIPS,
        UNKNOWN
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RESULT VALUE CLASSES
    // ─────────────────────────────────────────────────────────────────────────

    public static class ZoneMap {
        public Zone safeZone;
        public float leftEdgeDensity;
        public float centerEdgeDensity;
        public float rightEdgeDensity;

        public ZoneMap(Zone safeZone,
                       float leftEdgeDensity,
                       float centerEdgeDensity,
                       float rightEdgeDensity) {
            this.safeZone          = safeZone;
            this.leftEdgeDensity   = leftEdgeDensity;
            this.centerEdgeDensity = centerEdgeDensity;
            this.rightEdgeDensity  = rightEdgeDensity;
        }
    }

    public static class StairResult {
        public boolean stairsDetected;
        public int stepCount;
        public StairDirection direction;
        public float distanceMetres;

        public StairResult(boolean stairsDetected, int stepCount,
                           StairDirection direction, float distanceMetres) {
            this.stairsDetected = stairsDetected;
            this.stepCount      = stepCount;
            this.direction      = direction;
            this.distanceMetres = distanceMetres;
        }
    }

    public static class TrafficResult {
        public boolean vehicleApproaching;
        public Zone    approachZone;
        public float   ttcSeconds;
        public TrafficLightState lightState;

        public TrafficResult(boolean vehicleApproaching, Zone approachZone,
                             float ttcSeconds, TrafficLightState lightState) {
            this.vehicleApproaching = vehicleApproaching;
            this.approachZone       = approachZone;
            this.ttcSeconds         = ttcSeconds;
            this.lightState         = lightState;
        }
    }

    public static class FaceMatch {
        public String name;
        public float  similarity;
        public float  bearingDeg;
        public float  distanceMetres;

        public FaceMatch(String name, float similarity,
                         float bearingDeg, float distanceMetres) {
            this.name           = name;
            this.similarity     = similarity;
            this.bearingDeg     = bearingDeg;
            this.distanceMetres = distanceMetres;
        }
    }

    public static class PillResult {
        public String  commonName;
        public String  imprint;
        public String  shape;
        public String  color;
        public float   confidence;
        public boolean safeToReport;
        public String  cautionMessage;

        public PillResult(String commonName, String imprint, String shape,
                          String color, float confidence, boolean safeToReport,
                          String cautionMessage) {
            this.commonName     = commonName;
            this.imprint        = imprint;
            this.shape          = shape;
            this.color          = color;
            this.confidence     = confidence;
            this.safeToReport   = safeToReport;
            this.cautionMessage = cautionMessage;
        }
    }

    public static class CashResult {
        public String denomination;
        public String isoCode;
        public float  confidence;

        public CashResult(String denomination, String isoCode, float confidence) {
            this.denomination = denomination;
            this.isoCode      = isoCode;
            this.confidence   = confidence;
        }
    }

    public static class ColorResult {
        public String          colorName;
        public float           hue;
        public float           saturation;
        public TrafficLightState lightState;

        public ColorResult(String colorName, float hue,
                           float saturation, TrafficLightState lightState) {
            this.colorName  = colorName;
            this.hue        = hue;
            this.saturation = saturation;
            this.lightState = lightState;
        }
    }

    public static class LandmarkMatch {
        public String routeName;
        public String landmarkDescription;
        public float  confidence;
        public int    stepOffset;

        public LandmarkMatch(String routeName, String landmarkDescription,
                             float confidence, int stepOffset) {
            this.routeName            = routeName;
            this.landmarkDescription  = landmarkDescription;
            this.confidence           = confidence;
            this.stepOffset           = stepOffset;
        }
    }

    public static class CalibrationProfile {
        public String manufacturer;
        public String model;
        public float  fovHorizontalDeg;
        public float  fovVerticalDeg;
        public float  cameraOffsetXMm;
        public float  cameraOffsetYMm;
        public String source;

        public CalibrationProfile(String manufacturer, String model,
                                  float fovH, float fovV,
                                  float offsetX, float offsetY,
                                  String source) {
            this.manufacturer    = manufacturer;
            this.model           = model;
            this.fovHorizontalDeg = fovH;
            this.fovVerticalDeg  = fovV;
            this.cameraOffsetXMm = offsetX;
            this.cameraOffsetYMm = offsetY;
            this.source          = source;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CALLBACK INTERFACES
    // ─────────────────────────────────────────────────────────────────────────

    public interface HazardCallback {
        void onHazardDetected(HazardType type, float confidence);
    }

    public interface ReplayCallback {
        void onGuidanceStep(String instruction, int stepNumber, int totalSteps);
        void onRouteComplete(String routeName);
        void onRouteError(String reason);
    }

    public interface SkillHandler {
        String handle(String fullCommand, String argument);
    }

    public interface ButlerActionHandler {
        String execute(String argument, Context context);
    }

    public interface CalibrationAlignmentCallback {
        void onAlignmentReached(int poseIndex, float[] sensorValues);
        void onWalkComplete(CalibrationProfile derivedProfile);
        void onDirectionHint(String hint);
    }

    public interface TutorialStepCallback {
        void onStepComplete(int chapterIndex, int stepIndex);
        void onChapterComplete(int chapterIndex, String chapterName);
        void onTutorialComplete();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MODULE INTERFACES
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * IFrameProvider — abstracts the camera frame source so any module can
     * receive NV21 byte arrays without depending on CameraX directly.
     */
    public interface IFrameProvider {
        void addFrameListener(FrameListener listener);
        void removeFrameListener(FrameListener listener);

        interface FrameListener {
            void onFrame(byte[] nv21, int width, int height, int rotation);
        }
    }

    /**
     * IZoneAnalyser — splits the camera frame into left/center/right zones
     * and returns an edge-density map used for path-finding.
     */
    public interface IZoneAnalyser {
        ZoneMap analyse(byte[] nv21, int width, int height);
    }

    /**
     * IDepthProxy — provides a distance estimate to the nearest obstacle
     * without requiring a depth sensor, using monocular geometry heuristics.
     */
    public interface IDepthProxy {
        float estimateObstacleDistanceMetres(byte[] nv21, int width, int height);
    }

    /**
     * IStairSenseEngine — detects step edges in the camera frame.
     * Spec: Section 6.1.2 of the blueprint.
     */
    public interface IStairSenseEngine {
        StairResult analyse(byte[] nv21, int width, int height);
        boolean selfTest(Context ctx);
    }

    /**
     * ITrafficSenseEngine — detects approaching vehicles and traffic light state.
     * Spec: Section 6.1.3 of the blueprint.
     */
    public interface ITrafficSenseEngine {
        TrafficResult analyse(byte[] nv21, int width, int height);
        boolean selfTest(Context ctx);
    }

    /**
     * IColorSenseEngine — identifies dominant color and traffic light state.
     * Spec: Section 6.2.5 of the blueprint.
     */
    public interface IColorSenseEngine {
        ColorResult analyse(byte[] nv21, int width, int height, int reticleX,
                            int reticleY, int reticleW, int reticleH);
        boolean selfTest(Context ctx);
    }

    /**
     * IFaceVaultEngine — enrolls and identifies people by face, fully offline.
     * Spec: Section 6.2.2 of the blueprint.
     */
    public interface IFaceVaultEngine {
        boolean enrol(String name, byte[][] nv21Frames, int width, int height);
        java.util.List<FaceMatch> identify(byte[] nv21, int width, int height);
        boolean forget(String name);
        java.util.List<String> getEnrolledNames();
        boolean selfTest(Context ctx);
    }

    /**
     * IPillGuardEngine — identifies medication by shape, color, and imprint.
     * Spec: Section 6.2.1 of the blueprint.
     */
    public interface IPillGuardEngine {
        PillResult identify(byte[] nv21, int width, int height);
        boolean selfTest(Context ctx);
    }

    /**
     * ICashLensEngine — identifies banknote denomination by currency.
     * Spec: Section 6.2.3 of the blueprint.
     */
    public interface ICashLensEngine {
        CashResult identify(byte[] nv21, int width, int height);
        void setCurrency(String isoCode);
        String getCurrentCurrency();
        boolean selfTest(Context ctx);
    }

    /**
     * ISpatialMemoryEngine — records and replays named routes using landmark sequences.
     * Spec: Section 6.1.4 of the blueprint.
     */
    public interface ISpatialMemoryEngine {
        void startRecording(String routeName);
        void addLandmark(String description, int stepsSinceLastLandmark);
        void stopRecording();
        void startReplay(String routeName, ReplayCallback callback);
        LandmarkMatch matchCurrentScene(String sceneDescription);
        java.util.List<String> getAllRouteNames();
        boolean deleteRoute(String routeName);
        boolean selfTest(Context ctx);
    }

    /**
     * IOutputLayer — unified output abstraction for TTS, haptic, and Braille.
     * Spec: Section 8 of the blueprint.
     * Priority queue: EMERGENCY always interrupts; BACKGROUND is queued and may be dropped.
     */
    public interface IOutputLayer {
        void speak(String text, OutputPriority priority);
        void haptic(HapticPattern pattern, HapticZone zone);
        void braille(String text);
        void playEarcon(int earconId);
        void setMuted(boolean muted);
        boolean isMuted();
        void shutdown();
        boolean selfTest(Context ctx);
    }

    /**
     * IEmergencySOSEngine — triggers emergency sequence: speech → call → SMS → haptic SOS.
     * Spec: Section 6.3.1 of the blueprint.
     */
    public interface IEmergencySOSEngine {
        void trigger(String environmentDescription);
        void setContact(String phoneNumber, String contactName);
        String getContactName();
        boolean isActive();
        void cancel();
        boolean selfTest(Context ctx);
    }

    /**
     * IPassiveHazardEngine — always-on audio classifier for environmental hazards.
     * Spec: Section 6.3.2 of the blueprint.
     */
    public interface IPassiveHazardEngine {
        void start(HazardCallback callback);
        void stop();
        boolean isRunning();
        boolean selfTest(Context ctx);
    }

    /**
     * ICommandRouter — maps spoken trigger phrases to registered skill handlers.
     * Spec: Section 8 of the blueprint.
     */
    public interface ICommandRouter {
        String dispatch(String spokenCommand);
        void registerSkill(String triggerPhrase, SkillHandler handler);
        void unregisterSkill(String triggerPhrase);
        java.util.List<String> getRegisteredTriggers();
        boolean selfTest(Context ctx);
    }

    /**
     * IButlerService — AurigaButler™ system-wide voice command executor.
     * Spec: Section 6.5.3 of the blueprint.
     */
    public interface IButlerService {
        void handleCommand(String spokenText, Context context);
        void speakFeatureTip();
        void setTipsEnabled(boolean enabled);
        boolean areTipsEnabled();
    }

    /**
     * ISmartCalibrationEngine — auto-applies calibration from device library or geometry walk.
     * Spec: Section 6.0.4 of the blueprint.
     */
    public interface ISmartCalibrationEngine {
        boolean tryAutoCalibrate(Context ctx);
        void startAudioWalk(Context ctx, android.speech.tts.TextToSpeech tts,
                            CalibrationAlignmentCallback callback);
        CalibrationProfile getAppliedProfile(Context ctx);
    }

    /**
     * ITutorialEngine — 13-chapter voice-guided tutorial.
     * Spec: Section 6.5.3 (AurigaTutorialEngine) of the blueprint.
     */
    public interface ITutorialEngine {
        java.util.List<TutorialChapter> getChapters();
        boolean isChapterDone(Context ctx, String chapterName);
        void markChapterDone(Context ctx, String chapterName);
        TutorialChapter getNextIncompleteChapter(Context ctx);

        class TutorialChapter {
            public final String   name;
            public final String   title;
            public final String[] steps;
            public final String[] advancePhrases;

            public TutorialChapter(String name, String title,
                                   String[] steps, String[] advancePhrases) {
                this.name           = name;
                this.title          = title;
                this.steps          = steps;
                this.advancePhrases = advancePhrases;
            }
        }
    }
}
