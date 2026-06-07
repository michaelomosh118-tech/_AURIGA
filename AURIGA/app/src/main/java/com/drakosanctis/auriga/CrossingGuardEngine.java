package com.drakosanctis.auriga;

import android.content.Context;
import android.util.Log;

/**
 * CrossingGuardEngine™ — Session 7 (Phase 1).
 *
 * <p>Pedestrian crossing assistant that fuses {@link ColorSenseEngine} (traffic
 * light state) and {@link TrafficSenseEngine} (approaching vehicle detection)
 * to give the user a continuous, spoken safety narrative while waiting at or
 * crossing a road junction.
 *
 * <h3>Activation</h3>
 * <p>The engine is dormant by default. Call {@link #activate()} (typically
 * triggered by the voice command "Auriga, crossing mode"). Call
 * {@link #deactivate()} when the user completes the crossing or says "stop
 * crossing mode". While active, every call to {@link #onFrame} advances the
 * state machine and may produce spoken output via the injected
 * {@link AurigaInterfaces.IOutputLayer}.
 *
 * <h3>State machine</h3>
 * <pre>
 *   INACTIVE
 *     │  activate()
 *     ▼
 *   WAIT_RED ◄──────────────────────────────┐
 *     │  light = GREEN && no vehicles        │ light → RED
 *     ▼                                      │
 *   SAFE_TO_CROSS ─── vehicle detected ──►  ABORT
 *     │  user calls onFrame for N frames
 *     │  (user is assumed to be crossing)
 *     ▼
 *   CROSSING
 *     │  deactivate() or all-clear
 *     ▼
 *   INACTIVE
 * </pre>
 *
 * <h3>Audio outputs (via OutputLayer)</h3>
 * <ul>
 *   <li>RED: "Light is red. Vehicles still moving. Wait." (every {@value ANNOUNCE_INTERVAL_MS} ms)</li>
 *   <li>AMBER: "Light is changing. Stay back." (every tick)</li>
 *   <li>GREEN + no vehicles: "Light is green. No vehicles detected. Safe to cross." (once)</li>
 *   <li>GREEN + vehicle approaching: "Warning — vehicle still moving. Wait." (HIGH priority)</li>
 *   <li>UNKNOWN light: "I cannot confirm the light state. Use your judgement." (once on entry)</li>
 *   <li>Crossing: "Crossing. Keep bearing center." (once)</li>
 *   <li>ABORT (vehicle detected mid-cross): "STOP. Vehicle approaching from left/right." (EMERGENCY)</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * Must be called from a single analysis thread. The {@code OutputLayer} it calls
 * is thread-safe so overlapping TTS calls are handled safely by that layer.
 */
public class CrossingGuardEngine {

    private static final String TAG = "CrossingGuardEngine";

    // Minimum ms between repeated status announcements while waiting at red
    private static final long ANNOUNCE_INTERVAL_MS = 3_500;
    // After this many consecutive GREEN+clear frames the user is flagged as crossing
    private static final int  GREEN_FRAMES_TO_CROSS = 3;
    // Reticle used for colour sampling — full upper-centre strip of frame
    // (passed as fractions; converted to pixels in onFrame)
    private static final float RETICLE_X_FRAC = 0.30f;
    private static final float RETICLE_Y_FRAC = 0.05f;
    private static final float RETICLE_W_FRAC = 0.40f;
    private static final float RETICLE_H_FRAC = 0.25f;

    // ─────────────────────────────────────────────────────────────────────────
    // Dependencies (injected)
    // ─────────────────────────────────────────────────────────────────────────

    private final AurigaInterfaces.IColorSenseEngine   colorEngine;
    private final AurigaInterfaces.ITrafficSenseEngine trafficEngine;
    private final AurigaInterfaces.IOutputLayer        output;

    // ─────────────────────────────────────────────────────────────────────────
    // State
    // ─────────────────────────────────────────────────────────────────────────

    private enum State { INACTIVE, WAIT_RED, WAIT_UNKNOWN, SAFE_TO_CROSS, CROSSING }

    private volatile State state           = State.INACTIVE;
    private long           lastAnnounceMs  = 0;
    private int            greenFrameCount = 0;
    private boolean        crossingSpoken  = false;
    private boolean        unknownSpoken   = false;
    private boolean        safeSpoken      = false;

    // ─────────────────────────────────────────────────────────────────────────
    // Construction
    // ─────────────────────────────────────────────────────────────────────────

    public CrossingGuardEngine(AurigaInterfaces.IColorSenseEngine colorEngine,
                               AurigaInterfaces.ITrafficSenseEngine trafficEngine,
                               AurigaInterfaces.IOutputLayer output) {
        this.colorEngine   = colorEngine;
        this.trafficEngine = trafficEngine;
        this.output        = output;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /** Activate crossing mode. Speaks a confirmation announcement. */
    public void activate() {
        state           = State.WAIT_RED;
        lastAnnounceMs  = 0;
        greenFrameCount = 0;
        crossingSpoken  = false;
        unknownSpoken   = false;
        safeSpoken      = false;
        output.speak("Crossing mode active. I will tell you when it is safe to cross.",
                AurigaInterfaces.OutputPriority.HIGH);
        Log.i(TAG, "activated");
    }

    /** Deactivate crossing mode. Speaks a confirmation. */
    public void deactivate() {
        if (state == State.INACTIVE) return;
        state = State.INACTIVE;
        output.speak("Crossing mode off.", AurigaInterfaces.OutputPriority.NORMAL);
        Log.i(TAG, "deactivated");
    }

    public boolean isActive() {
        return state != State.INACTIVE;
    }

    /**
     * Feed one NV21 frame into the engine. No-op if not active.
     * Should be called at the same cadence as the perception pipeline (~5–10 fps).
     *
     * @param nv21   Raw NV21 camera frame.
     * @param width  Frame width in pixels.
     * @param height Frame height in pixels.
     */
    public void onFrame(byte[] nv21, int width, int height) {
        if (state == State.INACTIVE) return;

        // ── Colour analysis (traffic light) ──────────────────────────────
        int rx = (int)(width  * RETICLE_X_FRAC);
        int ry = (int)(height * RETICLE_Y_FRAC);
        int rw = (int)(width  * RETICLE_W_FRAC);
        int rh = (int)(height * RETICLE_H_FRAC);
        AurigaInterfaces.ColorResult color =
                colorEngine.analyse(nv21, width, height, rx, ry, rw, rh);

        // ── Traffic / vehicle analysis ─────────────────────────────────
        AurigaInterfaces.TrafficResult traffic = trafficEngine.analyse(nv21, width, height);

        AurigaInterfaces.TrafficLightState lightState =
                (color.lightState != AurigaInterfaces.TrafficLightState.UNKNOWN)
                        ? color.lightState : traffic.lightState;

        boolean vehicleClose = traffic.vehicleApproaching && traffic.ttcSeconds < 5f;

        long now = System.currentTimeMillis();

        switch (state) {

            // ── WAIT_RED / WAIT_UNKNOWN ───────────────────────────────────
            case WAIT_RED:
            case WAIT_UNKNOWN:
                handleWaitState(lightState, vehicleClose, traffic, now);
                break;

            // ── SAFE_TO_CROSS ─────────────────────────────────────────────
            case SAFE_TO_CROSS:
                if (vehicleClose) {
                    // Emergency abort — vehicle still moving
                    String zone = zoneWord(traffic.approachZone);
                    output.speak("STOP. Vehicle approaching from " + zone + ". Do not cross.",
                            AurigaInterfaces.OutputPriority.EMERGENCY);
                    output.haptic(AurigaInterfaces.HapticPattern.SOS,
                            AurigaInterfaces.HapticZone.ALL);
                    state          = State.WAIT_RED;
                    safeSpoken     = false;
                    greenFrameCount = 0;
                    Log.w(TAG, "abort → vehicle from " + zone);
                } else if (lightState == AurigaInterfaces.TrafficLightState.RED
                        || lightState == AurigaInterfaces.TrafficLightState.AMBER) {
                    output.speak("Light has changed. Wait.",
                            AurigaInterfaces.OutputPriority.HIGH);
                    output.haptic(AurigaInterfaces.HapticPattern.FAST_PULSE,
                            AurigaInterfaces.HapticZone.ALL);
                    state          = State.WAIT_RED;
                    safeSpoken     = false;
                    greenFrameCount = 0;
                } else {
                    greenFrameCount++;
                    if (greenFrameCount >= GREEN_FRAMES_TO_CROSS && !crossingSpoken) {
                        output.speak("Crossing. Keep bearing center.",
                                AurigaInterfaces.OutputPriority.HIGH);
                        output.haptic(AurigaInterfaces.HapticPattern.SINGLE,
                                AurigaInterfaces.HapticZone.ALL);
                        crossingSpoken = true;
                        state          = State.CROSSING;
                    }
                }
                break;

            // ── CROSSING ──────────────────────────────────────────────────
            case CROSSING:
                if (vehicleClose) {
                    String zone = zoneWord(traffic.approachZone);
                    output.speak("Warning. Vehicle from " + zone + ". Move quickly.",
                            AurigaInterfaces.OutputPriority.EMERGENCY);
                    output.haptic(AurigaInterfaces.HapticPattern.FAST_PULSE,
                            AurigaInterfaces.HapticZone.ALL);
                    Log.w(TAG, "mid-cross vehicle alert from " + zone);
                }
                // Crossing state exits only via deactivate()
                break;

            default:
                break;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void handleWaitState(AurigaInterfaces.TrafficLightState lightState,
                                 boolean vehicleClose,
                                 AurigaInterfaces.TrafficResult traffic,
                                 long now) {
        if (lightState == AurigaInterfaces.TrafficLightState.GREEN && !vehicleClose) {
            // GREEN and clear → transition to SAFE_TO_CROSS
            state          = State.SAFE_TO_CROSS;
            safeSpoken     = false;
            greenFrameCount = 0;
            output.speak("Light is green. No vehicles detected. Safe to cross.",
                    AurigaInterfaces.OutputPriority.HIGH);
            output.haptic(AurigaInterfaces.HapticPattern.SINGLE,
                    AurigaInterfaces.HapticZone.ALL);
            Log.i(TAG, "→ SAFE_TO_CROSS");
            return;
        }

        // Throttle repeated status announcements
        if (now - lastAnnounceMs < ANNOUNCE_INTERVAL_MS) return;
        lastAnnounceMs = now;

        switch (lightState) {
            case RED:
                state = State.WAIT_RED;
                String vehicleNote = vehicleClose
                        ? " Vehicles moving from " + zoneWord(traffic.approachZone) + "."
                        : " Vehicles still moving.";
                output.speak("Light is red." + vehicleNote + " Wait.",
                        AurigaInterfaces.OutputPriority.NORMAL);
                break;

            case AMBER:
                output.speak("Light is changing. Stay back.",
                        AurigaInterfaces.OutputPriority.HIGH);
                output.haptic(AurigaInterfaces.HapticPattern.SLOW_PULSE,
                        AurigaInterfaces.HapticZone.ALL);
                break;

            case GREEN:
                // GREEN but vehicle present
                output.speak("Light is green but a vehicle is still moving from "
                        + zoneWord(traffic.approachZone) + ". Wait.",
                        AurigaInterfaces.OutputPriority.HIGH);
                output.haptic(AurigaInterfaces.HapticPattern.FAST_PULSE,
                        AurigaInterfaces.HapticZone.ALL);
                break;

            case UNKNOWN:
            default:
                state = State.WAIT_UNKNOWN;
                if (!unknownSpoken) {
                    output.speak("I cannot confirm the light state. Use your judgement.",
                            AurigaInterfaces.OutputPriority.NORMAL);
                    unknownSpoken = true;
                }
                break;
        }
    }

    private static String zoneWord(AurigaInterfaces.Zone zone) {
        switch (zone) {
            case LEFT:   return "the left";
            case RIGHT:  return "the right";
            case CENTER: return "ahead";
            default:     return "an unknown direction";
        }
    }
}
