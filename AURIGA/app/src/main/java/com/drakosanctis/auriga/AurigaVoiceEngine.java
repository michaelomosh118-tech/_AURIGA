package com.drakosanctis.auriga;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;

/**
 * AurigaVoiceEngine — Android voice navigation layer.
 *
 * Wraps {@link SpeechRecognizer} + {@link TextToSpeech} into a single
 * helper that:
 *   - Checks for first-run assistant name setup
 *   - Listens for the wake phrase "[name] AURIGA"
 *   - Routes spoken commands to activities, drawer, and system actions
 *   - Fires callbacks to the host activity for drawer / back / describe ops
 *
 * Usage:
 *   1. Check {@link #isSetupDone(Context)}; if false, launch
 *      {@link VoiceSetupActivity} for first-run name setup.
 *   2. Create with {@code new AurigaVoiceEngine(activity, listener)}.
 *   3. Call {@link #attachLongPressToView(View)} on the root content view.
 *   4. Call {@link #onResume()}, {@link #onPause()}, {@link #shutdown()}
 *      at the matching activity lifecycle events.
 *   5. Call {@link #startListening()} from the mic FAB or a gesture.
 */
public class AurigaVoiceEngine implements RecognitionListener {

    // ── SharedPreferences keys ──────────────────────────────────────
    public static final String PREF_VOICE_NAME       = "auriga_voice_name";
    public static final String PREF_VOICE_ENABLED    = "auriga_voice_nav_enabled";
    public static final String PREF_VOICE_SETUP_DONE = "auriga_voice_setup_done";

    /** Broadcast action sent when the always-on service hears the wake phrase. */
    public static final String ACTION_WAKE_WORD = "com.drakosanctis.auriga.VOICE_WAKE";

    // ── Callback interface ─────────────────────────────────────────
    public interface Listener {
        void onListeningStarted();
        void onListeningStopped();
        void onTranscript(String text);
        void onOpenDrawer();
        void onCloseDrawer();
        void onGoBack();
        void onDescribePage();
    }

    private final Activity activity;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean listening = false;

    public AurigaVoiceEngine(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        initTts();
        initRecognizer();
    }

    // ── Static helpers ──────────────────────────────────────────────

    public static boolean isSetupDone(Context ctx) {
        return prefs(ctx).getBoolean(PREF_VOICE_SETUP_DONE, false);
    }

    public static String getAssistantName(Context ctx) {
        return prefs(ctx).getString(PREF_VOICE_NAME, "Auriga");
    }

    public static boolean isVoiceNavEnabled(Context ctx) {
        return prefs(ctx).getBoolean(PREF_VOICE_ENABLED, true);
    }

    public static void setVoiceNavEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(PREF_VOICE_ENABLED, enabled).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ── Public API ──────────────────────────────────────────────────

    /**
     * Start a single listen cycle (mic FAB, long-press, wake-broadcast, etc.).
     * No-op if already listening or voice nav is disabled.
     */
    public void startListening() {
        if (listening) return;
        if (!isVoiceNavEnabled(activity)) {
            Toast.makeText(activity,
                    "Voice navigation is muted. Long-press to re-enable.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            Toast.makeText(activity,
                    "Speech recognition not available on this device.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            listening = true;
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            recognizer.startListening(intent);
            if (listener != null) listener.onListeningStarted();
            speakQuiet("Listening");
        } catch (Throwable t) {
            listening = false;
            if (listener != null) listener.onListeningStopped();
        }
    }

    /** Stop the current listen cycle. */
    public void stopListening() {
        try {
            if (recognizer != null) recognizer.stopListening();
        } catch (Throwable ignored) {}
        listening = false;
        if (listener != null) listener.onListeningStopped();
    }

    /** Speak arbitrary text via TTS (flushes any in-flight utterance). */
    public void speak(String text) {
        if (!ttsReady || tts == null) return;
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null,
                "auriga_voice_" + System.currentTimeMillis());
    }

    /**
     * Speak a short status word (QUEUE_ADD) so it doesn't interrupt
     * a running obstacle announcement from the locator TTS.
     */
    public void speakQuiet(String text) {
        if (!ttsReady || tts == null) return;
        tts.speak(text, TextToSpeech.QUEUE_ADD, null,
                "auriga_voice_q_" + System.currentTimeMillis());
    }

    /**
     * Attach a long-press listener to the given root view so that holding
     * the screen for ~700 ms activates voice navigation. Call this in
     * {@code onCreate} on the root content view.
     */
    public void attachLongPressToView(View root) {
        root.setOnLongClickListener(v -> {
            startListening();
            return true;
        });
    }

    /** Call from the host activity's {@code onResume}. */
    public void onResume() {
        if (recognizer == null) {
            initRecognizer();
        }
    }

    /** Call from the host activity's {@code onPause} to free the mic. */
    public void onPause() {
        stopListening();
    }

    /** Call from {@code onDestroy} to release all resources. */
    public void shutdown() {
        try {
            if (recognizer != null) {
                recognizer.cancel();
                recognizer.destroy();
                recognizer = null;
            }
        } catch (Throwable ignored) {}
        try {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
                tts = null;
            }
        } catch (Throwable ignored) {}
        ttsReady = false;
        listening = false;
    }

    // ── Init ────────────────────────────────────────────────────────

    private void initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) return;
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
            recognizer.setRecognitionListener(this);
        } catch (Throwable t) {
            recognizer = null;
        }
    }

    private void initTts() {
        tts = new TextToSpeech(activity, status -> {
            ttsReady = (status == TextToSpeech.SUCCESS);
            if (ttsReady && tts != null) {
                tts.setLanguage(Locale.getDefault());
                tts.setSpeechRate(1.05f);
            }
        });
    }

    // ── RecognitionListener ─────────────────────────────────────────

    @Override public void onReadyForSpeech(Bundle params) {}
    @Override public void onBeginningOfSpeech() {}
    @Override public void onRmsChanged(float rmsdB) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEndOfSpeech() {}

    @Override
    public void onError(int error) {
        listening = false;
        if (listener != null) listener.onListeningStopped();
    }

    @Override
    public void onResults(Bundle results) {
        listening = false;
        if (listener != null) listener.onListeningStopped();
        ArrayList<String> matches =
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches != null && !matches.isEmpty()) {
            String text = matches.get(0);
            if (listener != null) listener.onTranscript(text);
            routeCommand(text.toLowerCase(Locale.US).trim());
        }
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        ArrayList<String> partial =
                partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (partial != null && !partial.isEmpty() && listener != null) {
            listener.onTranscript(partial.get(0));
        }
    }

    @Override public void onEvent(int eventType, Bundle params) {}

    // ── Command routing ─────────────────────────────────────────────

    private void routeCommand(String text) {
        String name = getAssistantName(activity).toLowerCase(Locale.US).trim();
        boolean isWakeOnly = text.equals(name + " auriga")
                || text.equals("auriga")
                || text.equals("hey auriga")
                || text.equals("ok auriga");

        if (contains(text, "open locator", "object locator", "go to locator", "start locator")) {
            speak("Opening Object Locator");
            safeStart(LocatorActivity.class);
        } else if (contains(text, "open reader", "drakovoice reader", "go to reader",
                "start reader", "read mode")) {
            speak("Opening DrakoVoice Reader");
            safeStart(ReaderActivity.class);
        } else if (contains(text, "open targets", "go to targets", "object targets")) {
            speak("Opening Object Targets");
            safeStart(TargetsActivity.class);
        } else if (contains(text, "calibration", "calibrate", "calibration walk")) {
            speak("Opening Calibration Walk");
            safeStart(CalibrationWalkActivity.class);
        } else if (contains(text, "send feedback", "feedback", "report")) {
            speak("Opening Feedback");
            safeStart(FeedbackActivity.class);
        } else if (contains(text, "about auriga", "about the app", "about")) {
            speak("Opening About");
            safeStart(AboutActivity.class);
        } else if (contains(text, "help tips", "help")) {
            speak("Opening Help");
            safeStart(HelpActivity.class);
        } else if (contains(text, "support centre", "support")) {
            speak("Opening Support Centre");
            safeStart(SupportActivity.class);
        } else if (contains(text, "open menu", "open drawer", "show menu", "open navigation")) {
            speak("Opening menu");
            if (listener != null) listener.onOpenDrawer();
        } else if (contains(text, "close menu", "close drawer", "hide menu")) {
            speak("Closing menu");
            if (listener != null) listener.onCloseDrawer();
        } else if (contains(text, "go back", "back")) {
            speak("Going back");
            if (listener != null) listener.onGoBack();
        } else if (contains(text, "read this page", "describe", "where am i",
                "what is this", "read page")) {
            if (listener != null) listener.onDescribePage();
        } else if (contains(text, "stop listening", "stop", "cancel",
                "never mind", "nevermind")) {
            speakQuiet("Stopped");
        } else if (contains(text, "mute voice", "mute navigation", "voice off")) {
            setVoiceNavEnabled(activity, false);
            speak("Voice navigation muted. Long-press to re-enable.");
        } else if (contains(text, "enable voice", "unmute voice",
                "unmute navigation", "voice on")) {
            setVoiceNavEnabled(activity, true);
            speak("Voice navigation enabled.");
        } else if (contains(text, "what can you do", "list commands", "help commands")) {
            speak("I can open locator, reader, targets, calibration, feedback, "
                    + "about, help. I can open or close the menu, go back, "
                    + "or describe this page. Say stop to cancel.");
        } else if (contains(text, "change name", "rename")) {
            speak("Opening voice setup.");
            safeStart(VoiceSetupActivity.class);
        } else if (isWakeOnly) {
            speak("Auriga ready. Say a command.");
        } else if (!text.isEmpty()) {
            speak("I didn't catch that. Say help commands for a list.");
        }
    }

    private static boolean contains(String text, String... phrases) {
        for (String p : phrases) {
            if (text.contains(p)) return true;
        }
        return false;
    }

    private void safeStart(Class<?> target) {
        try {
            activity.startActivity(new Intent(activity, target));
        } catch (Throwable t) {
            Toast.makeText(activity,
                    "Cannot open " + target.getSimpleName(),
                    Toast.LENGTH_SHORT).show();
        }
    }
}
