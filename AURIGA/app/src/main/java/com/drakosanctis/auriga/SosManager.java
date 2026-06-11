package com.drakosanctis.auriga;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;

/**
 * SosManager — emergency call countdown triggered by voice ("SOS") or
 * long-press (2.5 s) on the mic FAB.
 *
 * Competitive gap: every rival app (Seeing AI, Lookout, Envision, OrCam)
 * has ZERO built-in emergency functionality. If a blind user falls or gets
 * lost, there is no path to emergency services from within the accessibility
 * app. This closes that gap entirely without requiring sighted assistance.
 *
 * Flow:
 *   1. activate() → speaks countdown from COUNTDOWN_SECS to 1.
 *   2. User says "cancel" or calls cancel() → spoken confirmation, no call.
 *   3. After countdown → ACTION_CALL (requires CALL_PHONE permission).
 *      Falls back to ACTION_DIAL if permission is absent.
 *
 * Thread-safe: activate() / cancel() / isActive() may be called from any thread.
 */
public class SosManager {

    private static final String TAG              = "SosManager";
    private static final int    COUNTDOWN_SECS   = 5;
    private static final String EMERGENCY_NUMBER = "112"; // international standard

    private final Activity      activity;
    private final TextToSpeech  tts;
    private final Handler       main = new Handler(Looper.getMainLooper());

    private volatile boolean active    = false;
    private volatile int     countdown = COUNTDOWN_SECS;
    private Runnable tickTask;

    public SosManager(Activity activity, TextToSpeech tts) {
        this.activity = activity;
        this.tts      = tts;
    }

    // ── Public API ────────────────────────────────────────────────────

    /** Trigger a 5-second spoken countdown before placing an emergency call. */
    public void activate() {
        if (active) {
            speak("SOS is already counting down. Say cancel to abort.");
            return;
        }
        active    = true;
        countdown = COUNTDOWN_SECS;
        Log.i(TAG, "SOS activated — countdown started");
        speak("SOS activated. I will call emergency services in "
                + countdown + " seconds. Say cancel to abort.");
        scheduleTick();
    }

    /** Cancel an in-progress countdown. */
    public void cancel() {
        if (!active) { speak("No SOS is active."); return; }
        active = false;
        if (tickTask != null) main.removeCallbacks(tickTask);
        Log.i(TAG, "SOS cancelled by user");
        speak("SOS cancelled. You are safe.");
    }

    /** True while a countdown is running. Use to intercept "cancel" commands. */
    public boolean isActive() { return active; }

    // ── Internal ──────────────────────────────────────────────────────

    private void scheduleTick() {
        tickTask = () -> {
            if (!active) return;
            countdown--;
            if (countdown > 0) {
                speak(String.valueOf(countdown));
                main.postDelayed(tickTask, 1000);
            } else {
                active = false;
                speak("Calling emergency services now.");
                main.postDelayed(this::placeCall, 600);
            }
        };
        main.postDelayed(tickTask, 1000);
    }

    private void placeCall() {
        try {
            // ACTION_CALL requires CALL_PHONE permission (declared in manifest).
            Intent call = new Intent(Intent.ACTION_CALL,
                    Uri.parse("tel:" + EMERGENCY_NUMBER));
            call.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(call);
            Log.i(TAG, "Emergency call placed to " + EMERGENCY_NUMBER);
        } catch (SecurityException se) {
            // CALL_PHONE not granted — open the dialler pre-filled instead.
            Log.w(TAG, "CALL_PHONE not granted, falling back to ACTION_DIAL");
            speak("Opening dialler for emergency services. Tap the call button.");
            Intent dial = new Intent(Intent.ACTION_DIAL,
                    Uri.parse("tel:" + EMERGENCY_NUMBER));
            dial.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { activity.startActivity(dial); }
            catch (Throwable t) { Log.e(TAG, "Dial also failed", t); }
        } catch (Throwable t) {
            Log.e(TAG, "Emergency call failed", t);
            speak("Could not place call automatically. Please dial 1 1 2 manually.");
        }
    }

    private void speak(String text) {
        if (tts == null || text == null) return;
        main.post(() -> tts.speak(text, TextToSpeech.QUEUE_FLUSH, null,
                "sos_" + System.currentTimeMillis()));
    }
}
