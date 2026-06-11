package com.drakosanctis.auriga;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
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
import java.util.regex.Pattern;

/* OpenClaw-style skill engine — all non-navigation commands go here first */

/**
 * AurigaVoiceEngine — Android voice navigation layer.
 *
 * Wraps {@link SpeechRecognizer} + {@link TextToSpeech} into a single
 * helper that:
 *   - Checks for first-run assistant name setup
 *   - Listens for the wake phrase "[name] AURIGA"
 *   - Routes spoken commands to activities, drawer, and system actions
 *     using rich natural-language synonym sets so users don't have to
 *     remember exact phrases
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
    private boolean ttsReady  = false;
    private boolean listening  = false;
    private boolean systemMuted = false;

    /* OpenClaw-style skill engine: handles all non-navigation commands */
    private AurigaSkillEngine skillEngine;

    /* AurigaMind — on-device LLM final fallback */
    private KnowledgeCache knowledgeCache;
    private MindEngine     mindEngine;   // null until model is loaded (or no model)

    /** Last utterance spoken — used by "say again" command. */
    private String         lastUtterance = "";
    /** TTS speech rate — persisted across commands (0.5 – 2.0). */
    private float          speechRate    = 1.05f;
    private static final float RATE_STEP = 0.20f;
    private static final float RATE_MIN  = 0.50f;
    private static final float RATE_MAX  = 2.00f;
    /** Emergency SOS — lazily created on first "SOS" command or long-press. */
    private SosManager     sosMgr;

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
            // Pause the always-on wake service so both SpeechRecognizers
            // don't fight over the single microphone.
            AurigaVoiceService.stopListening(activity);

            listening = true;
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
            // Silence the "mic-on" ding for the window between startListening()
            // and onReadyForSpeech() — same guard used by AurigaVoiceService.
            muteSystem();
            recognizer.startListening(intent);
            if (listener != null) listener.onListeningStarted();
        } catch (Throwable t) {
            unmuteSystem();
            listening = false;
            AurigaVoiceService.startListening(activity); // restore wake service
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
        if (text != null && !text.isEmpty()) lastUtterance = text;
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null,
                "auriga_voice_" + System.currentTimeMillis());
    }

    /** Activate emergency SOS (same as saying "SOS"). */
    public void activateSos() {
        if (sosMgr == null) sosMgr = new SosManager(activity, tts);
        sosMgr.activate();
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
        try {
            if (recognizer != null) { recognizer.destroy(); recognizer = null; }
        } catch (Throwable ignored) {}
    }

    /** Call from {@code onDestroy} to release all resources. */
    public void shutdown() {
        unmuteSystem(); // always clean up before releasing resources
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
        if (mindEngine != null) { mindEngine.close(); mindEngine = null; }
    }

    // ── System audio mute helpers ────────────────────────────────────
    // Suppress the SpeechRecognizer "mic-on" ding for the short window
    // between startListening() and onReadyForSpeech(). Min SDK is 24
    // so ADJUST_MUTE / ADJUST_UNMUTE are always available.

    private void muteSystem() {
        if (systemMuted) return;
        try {
            AudioManager am = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.adjustStreamVolume(AudioManager.STREAM_SYSTEM,
                        AudioManager.ADJUST_MUTE, 0);
                systemMuted = true;
            }
        } catch (Throwable ignored) {}
    }

    private void unmuteSystem() {
        if (!systemMuted) return;
        try {
            AudioManager am = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.adjustStreamVolume(AudioManager.STREAM_SYSTEM,
                        AudioManager.ADJUST_UNMUTE, 0);
                systemMuted = false;
            }
        } catch (Throwable ignored) {}
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
                /* Wire skill engine once TTS is ready */
                if (skillEngine == null) {
                    skillEngine = new AurigaSkillEngine(activity, tts);
                } else {
                    skillEngine.updateTts(tts);
                }
                /* Boot AurigaMind — KnowledgeCache warms up immediately;
                   MindEngine loads the LLM model in the background (5–30 s).
                   Both are null-safe — the dispatch chain handles missing model. */
                if (knowledgeCache == null) {
                    knowledgeCache = new KnowledgeCache(activity);
                    knowledgeCache.warmUp();
                }
                final TextToSpeech ttsRef = tts;
                MindEngine.createAsync(activity, knowledgeCache, ttsRef,
                        engine -> mindEngine = engine);

                /* Attach TTS to the Qwen downloader so it can speak progress.
                   ModelDownloadManager is started in AurigaApplication.onCreate
                   before TTS is ready, so we wire TTS here once it's ready. */
                if (AurigaApplication.modelDownloadManager != null) {
                    AurigaApplication.modelDownloadManager.setTts(ttsRef);
                    // Hot-reload: when any model finishes downloading while the
                    // app is running, automatically initialise MindEngine without
                    // requiring the user to restart. The DownloadListener is kept
                    // alive for the duration of the AurigaVoiceEngine instance.
                    AurigaApplication.modelDownloadManager.registerListener(
                            new ModelDownloadManager.DownloadListener() {
                        @Override
                        public void onProgress(ModelDownloadManager.ModelId model,
                                               int percentDone) { /* progress spoken by mgr */ }

                        @Override
                        public void onStateChanged(ModelDownloadManager.ModelId model,
                                                   ModelDownloadManager.ModelState newState) {
                            if (newState == ModelDownloadManager.ModelState.READY
                                    && mindEngine == null) {
                                // A model just became ready — bootstrap MindEngine
                                // so the next spoken question uses it immediately.
                                MindEngine.createAsync(activity, knowledgeCache, ttsRef,
                                        engine -> {
                                            mindEngine = engine;
                                            if (engine != null) {
                                                ttsRef.speak(
                                                    "AI assistant is now ready. "
                                                    + "You can ask me anything.",
                                                    android.speech.tts.TextToSpeech.QUEUE_ADD,
                                                    null, "mind_ready");
                                            }
                                        });
                            }
                        }
                    });
                } else if (!ModelDownloadManager.isQwenLargeReady(activity)) {
                    /* Rare path: Application.onCreate ran without network.
                       Start the download now that we're past init and online. */
                    if (ModelDownloadManager.isOnline(activity)) {
                        ModelDownloadManager mgr = new ModelDownloadManager(activity);
                        mgr.setTts(ttsRef);
                        AurigaApplication.modelDownloadManager = mgr;
                        mgr.ensureQwenLargeDownloaded();
                    }
                }
            }
        });
    }

    // ── RecognitionListener ─────────────────────────────────────────

    @Override public void onReadyForSpeech(Bundle params) {
        // Recognizer is fully initialised — safe to lift the ding suppression.
        unmuteSystem();
    }
    @Override public void onBeginningOfSpeech() {}
    @Override public void onRmsChanged(float rmsdB) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEndOfSpeech() {}

    @Override
    public void onError(int error) {
        // Always unmute so we don't leave STREAM_SYSTEM permanently silenced
        // if the recognizer bails out before onReadyForSpeech fires.
        unmuteSystem();
        listening = false;
        mainHandler.post(() -> {
            try {
                if (recognizer != null) { recognizer.destroy(); recognizer = null; }
            } catch (Throwable ignored) {}
            initRecognizer();
            AurigaVoiceService.startListening(activity);
        });
        if (listener != null) listener.onListeningStopped();
    }

    @Override
    public void onResults(Bundle results) {
        unmuteSystem(); // safety net — should already be clear
        listening = false;
        if (listener != null) listener.onListeningStopped();
        AurigaVoiceService.startListening(activity);
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
    //
    // Design principle: every command accepts a broad set of natural-
    // language paraphrases. Users shouldn't need to remember exact
    // phrases. We lean on three tiers:
    //   1. Prefix/stem matches (e.g. any sentence containing "locator")
    //   2. Semantic aliases (e.g. "find objects" = locator)
    //   3. Intent phrases (e.g. "I want to read" = reader)

    private void routeCommand(String text) {
        String name = getAssistantName(activity).toLowerCase(Locale.US).trim();

        // Persist user input to memory store
        AurigaMemoryStore.store(activity, "user", text, "voice");
        // Extract profile facts from what the user said
        AurigaMemoryStore.extractAndSaveProfile(activity, text);

        // Quality signals — user correcting or confirming the last answer
        if (text.matches(".*\\b(yes,? exactly|that'?s? (right|correct|perfect)|exactly right|correct)\\b.*")) {
            AurigaMemoryStore.markLastQuality(activity, 1);
        } else if (text.matches(".*\\b(that'?s? wrong|that is wrong|wrong answer|incorrect|no that'?s? not right)\\b.*")) {
            AurigaMemoryStore.markLastQuality(activity, -1);
        }

        // Strip wake prefix so "Nova Auriga open locator" still works.
        String cmd = text;
        if (cmd.startsWith(name)) {
            cmd = cmd.substring(name.length())
                    .replaceFirst("^\\s*auriga\\s*", "").trim();
        } else if (cmd.startsWith("hey auriga") || cmd.startsWith("ok auriga")
                || cmd.startsWith("hello auriga") || cmd.startsWith("yo auriga")) {
            cmd = cmd.replaceFirst("^(hey|ok|hello|yo)\\s+auriga\\s*", "").trim();
        } else if (cmd.startsWith("auriga")) {
            cmd = cmd.substring("auriga".length()).trim();
        }

        // ── Wake-only (no further command) ─────────────────────────
        if (cmd.isEmpty()) {
            speak("Auriga ready. What can I do for you?");
            return;
        }

        // ── Object Locator ─────────────────────────────────────────
        if (matches(cmd,
                "open locator", "start locator", "launch locator", "go to locator",
                "object locator", "start detecting", "detect objects", "find objects",
                "find things", "scan my surroundings", "what's around me",
                "what is around me", "what's in front of me", "show me what's around",
                "navigate mode", "start navigation", "spatial view", "scan area",
                "look around", "identify objects", "object detection", "detection mode")) {
            speak("Opening Object Locator");
            safeStart(LocatorActivity.class);

        // ── DrakoVoice Reader ──────────────────────────────────────
        } else if (matches(cmd,
                "open reader", "start reader", "launch reader", "go to reader",
                "drakovoice reader", "drakovoice", "read text", "read mode",
                "scan text", "read this", "what does this say", "read this sign",
                "read that", "i want to read", "i need to read", "reading mode",
                "ocr mode", "text recognition", "read something", "scan sign",
                "read a document", "help me read", "reading", "document reader")) {
            speak("Opening DrakoVoice Reader");
            safeStart(ReaderActivity.class);

        // ── Targets manager ────────────────────────────────────────
        } else if (matches(cmd,
                "open targets", "start targets", "go to targets", "object targets",
                "manage targets", "set targets", "edit targets", "my targets",
                "track objects", "track something", "what am i tracking",
                "what objects am i tracking", "add target", "remove target",
                "configure targets", "tracking list", "watch list")) {
            speak("Opening Object Targets");
            safeStart(TargetsActivity.class);

        // ── Calibration Walk ───────────────────────────────────────
        } else if (matches(cmd,
                "open calibration", "calibration walk", "start calibration",
                "calibrate", "do calibration", "run calibration",
                "improve accuracy", "improve distance", "setup calibration",
                "recalibrate", "calibration library", "calibration setup",
                "ten point calibration", "10 point calibration",
                "distance calibration", "set up accuracy")) {
            speak("Opening Calibration Walk");
            safeStart(CalibrationWalkActivity.class);

        // ── Send Feedback ──────────────────────────────────────────
        } else if (matches(cmd,
                "send feedback", "open feedback", "give feedback",
                "report a bug", "report bug", "report an issue", "report issue",
                "report a problem", "submit feedback", "share feedback",
                "i found a bug", "something is wrong", "something's wrong",
                "suggest an idea", "suggest idea", "submit a suggestion",
                "feedback form", "contact support", "file a report", "report")) {
            speak("Opening Feedback");
            safeStart(FeedbackActivity.class);

        // ── About ──────────────────────────────────────────────────
        } else if (matches(cmd,
                "open about", "about auriga", "about this app", "about the app",
                "who made this", "who built this", "who created this",
                "tell me about auriga", "what is auriga", "app info",
                "application info", "show credits", "about")) {
            speak("Opening About");
            safeStart(AboutActivity.class);

        // ── Help & Tips ────────────────────────────────────────────
        } else if (matches(cmd,
                "open help", "show help", "help tips", "help me",
                "i need help", "how do i use this", "how does this work",
                "usage guide", "user guide", "tips", "tips and tricks",
                "getting started", "beginner guide", "tutorial", "help")) {
            speak("Opening Help");
            safeStart(HelpActivity.class);

        // ── Support Centre ─────────────────────────────────────────
        } else if (matches(cmd,
                "open support", "support centre", "contact us", "get support",
                "technical support", "customer support", "need support", "support")) {
            speak("Opening Support Centre");
            safeStart(SupportActivity.class);

        // ── Open menu / drawer ─────────────────────────────────────
        } else if (matches(cmd,
                "open menu", "show menu", "open drawer", "show drawer",
                "navigation menu", "open navigation", "open navigation drawer",
                "main menu", "app menu", "expand menu", "pull out menu",
                "open sidebar", "sidebar", "menu please")) {
            speak("Opening menu");
            if (listener != null) listener.onOpenDrawer();

        // ── Close menu / drawer ────────────────────────────────────
        } else if (matches(cmd,
                "close menu", "hide menu", "close drawer", "hide drawer",
                "dismiss menu", "collapse menu", "shut the menu",
                "close navigation", "close sidebar")) {
            speak("Closing menu");
            if (listener != null) listener.onCloseDrawer();

        // ── Go back ────────────────────────────────────────────────
        } else if (matches(cmd,
                "go back", "back", "previous screen", "previous page",
                "return", "go to previous", "navigate back", "back button",
                "press back", "take me back")) {
            speak("Going back");
            if (listener != null) listener.onGoBack();

        // ── Describe this page ─────────────────────────────────────
        } else if (matches(cmd,
                "read this page", "describe", "describe this page",
                "where am i", "what is this", "what is this screen",
                "what's on screen", "what am i looking at", "read page",
                "tell me about this page", "what page is this",
                "summarise this page", "summarize this page",
                "current page", "page description", "what screen am i on")) {
            if (listener != null) listener.onDescribePage();

        // ── Voice nav controls ─────────────────────────────────────
        } else if (matches(cmd,
                "stop listening", "stop", "cancel", "never mind", "nevermind",
                "quiet", "be quiet", "shh", "silence", "that's enough",
                "that is enough", "done", "exit voice", "pause voice")) {
            speakQuiet("Stopped.");

        } else if (matches(cmd,
                "mute voice", "mute navigation", "voice off", "turn off voice",
                "disable voice", "disable voice navigation", "silence mode")) {
            setVoiceNavEnabled(activity, false);
            speak("Voice navigation muted. Long-press anywhere to re-enable.");

        } else if (matches(cmd,
                "enable voice", "unmute voice", "unmute navigation", "voice on",
                "turn on voice", "enable voice navigation", "activate voice")) {
            setVoiceNavEnabled(activity, true);
            speak("Voice navigation enabled. Say a command.");

        // ── Rename assistant ───────────────────────────────────────
        } else if (matches(cmd,
                "change name", "rename", "rename assistant", "new name",
                "change assistant name", "set my name", "call you something",
                "give you a name", "i want to rename you")) {
            speak("Opening voice setup.");
            safeStart(VoiceSetupActivity.class);

        // ── What can you do? ───────────────────────────────────────
        } else if (matches(cmd,
                "what can you do", "list commands", "help commands",
                "what are your commands", "available commands",
                "show commands", "command list", "what do you know",
                "what commands", "what can i say")) {
            speak("I can open the locator, reader, targets, Color Sense, calibration, " +
                    "feedback, about, help, and support. I can describe the current scene, " +
                    "repeat the last thing I said, change speech speed, check or download " +
                    "AI models, and activate an emergency SOS. Say any of these naturally " +
                    "— you do not need exact words.");

        // ── Say again ──────────────────────────────────────────────────
        } else if (matches(cmd,
                "say again", "say that again", "repeat that", "repeat",
                "what did you say", "i missed that", "come again",
                "pardon", "sorry what", "one more time", "play that again")) {
            if (lastUtterance.isEmpty()) {
                speak("Nothing has been said yet.");
            } else {
                tts.speak(lastUtterance, TextToSpeech.QUEUE_FLUSH, null,
                        "auriga_repeat_" + System.currentTimeMillis());
            }

        // ── TTS speech rate — slower ───────────────────────────────────
        } else if (matches(cmd,
                "speak slower", "slow down", "slower", "talk slower",
                "speak more slowly", "slow speech", "reduce speed",
                "too fast", "speak slower please")) {
            speechRate = Math.max(RATE_MIN, speechRate - RATE_STEP);
            if (ttsReady && tts != null) tts.setSpeechRate(speechRate);
            speak("Slower. " + Math.round(speechRate * 100) + " percent.");

        // ── TTS speech rate — faster ───────────────────────────────────
        } else if (matches(cmd,
                "speak faster", "speed up", "faster", "talk faster",
                "speak more quickly", "fast speech", "increase speed",
                "too slow", "speak faster please")) {
            speechRate = Math.min(RATE_MAX, speechRate + RATE_STEP);
            if (ttsReady && tts != null) tts.setSpeechRate(speechRate);
            speak("Faster. " + Math.round(speechRate * 100) + " percent.");

        // ── Describe current scene (relays to host Activity) ──────────
        } else if (matches(cmd,
                "describe what you see", "describe the scene", "scene summary",
                "full scan", "scene description", "what objects are visible",
                "list everything", "tell me everything", "what can you see",
                "full description", "what are you seeing", "show me everything",
                "what's in the frame", "describe everything")) {
            if (listener != null) listener.onDescribePage();

        // ── Color Sense ────────────────────────────────────────────────
        } else if (matches(cmd,
                "color sense", "open color sense", "colour sense",
                "identify color", "what color is this", "what colour is this",
                "detect color", "detect colour", "color identification",
                "what color", "what colour", "colour detect")) {
            speak("Opening Color Sense.");
            safeStart(ColorSenseActivity.class);

        // ── AI model — download small ──────────────────────────────────
        } else if (matches(cmd,
                "download AI", "download the AI", "install AI", "get the AI",
                "download small model", "get small model", "download small AI",
                "small model", "get AI assistant", "install assistant", "get AI")) {
            ModelDownloadManager mgr = new ModelDownloadManager(activity);
            ModelDownloadManager.ModelState st =
                    mgr.getState(ModelDownloadManager.ModelId.QWEN_SMALL);
            if (st == ModelDownloadManager.ModelState.READY) {
                speak("The small AI model is already installed and ready.");
            } else if (st == ModelDownloadManager.ModelState.DOWNLOADING) {
                speak("Download already in progress. "
                        + mgr.getProgressPercent(ModelDownloadManager.ModelId.QWEN_SMALL)
                        + " percent complete.");
            } else {
                speak("Starting small AI model download.");
                mgr.ensureQwenSmallDownloaded();
            }

        // ── AI model — download large ──────────────────────────────────
        } else if (matches(cmd,
                "download large model", "download large AI", "get large model",
                "get big model", "download big AI", "large model",
                "download full AI", "install full model", "get large AI")) {
            ModelDownloadManager mgr = new ModelDownloadManager(activity);
            ModelDownloadManager.ModelState st =
                    mgr.getState(ModelDownloadManager.ModelId.QWEN_LARGE);
            if (st == ModelDownloadManager.ModelState.READY) {
                speak("The large AI model is already installed and ready.");
            } else if (st == ModelDownloadManager.ModelState.DOWNLOADING) {
                speak("Download already in progress. "
                        + mgr.getProgressPercent(ModelDownloadManager.ModelId.QWEN_LARGE)
                        + " percent complete.");
            } else {
                speak("Starting large AI model download. This is about 1.3 gigabytes "
                        + "so please use Wi-Fi.");
                mgr.ensureQwenLargeDownloaded();
            }

        // ── AI model — status ──────────────────────────────────────────
        } else if (matches(cmd,
                "AI status", "model status", "AI model status",
                "is AI ready", "is the AI ready", "AI ready",
                "download status", "model progress", "check AI",
                "is my AI downloaded", "check AI status")) {
            ModelDownloadManager mgr = new ModelDownloadManager(activity);
            String small = modelStateLabel(mgr, ModelDownloadManager.ModelId.QWEN_SMALL);
            String large = modelStateLabel(mgr, ModelDownloadManager.ModelId.QWEN_LARGE);
            speak("Small AI: " + small + ". Large AI: " + large + ".");

        // ── Emergency SOS ──────────────────────────────────────────────
        } else if (matches(cmd,
                "sos", "send sos", "emergency", "call emergency",
                "call emergency services", "call for help",
                "i need emergency help", "help emergency",
                "call 999", "call 911", "call 112",
                "i'm in danger", "i am in danger")) {
            if (sosMgr == null) sosMgr = new SosManager(activity, tts);
            sosMgr.activate();

        // ── Cancel SOS ─────────────────────────────────────────────────
        } else if (matches(cmd,
                "cancel sos", "abort sos", "stop sos",
                "abort emergency", "cancel emergency", "stop emergency",
                "sos cancel", "never mind emergency")) {
            if (sosMgr != null && sosMgr.isActive()) {
                sosMgr.cancel();
            } else {
                speak("No SOS countdown is active.");
            }

        // ── OpenClaw-style skill engine (timers, alarms, weather, etc.) ──
        } else if (skillEngine != null && skillEngine.dispatch(cmd)) {
            /* skill engine handled it and will speak the reply itself */

        // ── Conversational Q&A — three-tier fallback ──────────────────
        //   Tier 1: AurigaKnowledge rule-based KB  (instant, fully offline)
        //   Tier 2: MindEngine on-device LLM        (2–10 s, offline after first load)
        //           grounded by KnowledgeCache      (weather/news/Wikipedia)
        //   Tier 3: AurigaKnowledge.fallback()      (always-available safety net)
        } else if (!cmd.isEmpty()) {
            String kbAnswer = AurigaKnowledge.answer(cmd);
            if (kbAnswer != null) {
                // Rule-based KB matched — fast path
                speak(kbAnswer);
                AurigaMemoryStore.store(activity, "assistant", kbAnswer, "voice");
            } else if (mindEngine != null) {
                // LLM is loaded — stream the answer via MindEngine
                // (MindEngine speaks directly; we just store the query for memory)
                AurigaMemoryStore.store(activity, "assistant", "[MindEngine responding]", "voice");
                mindEngine.ask(cmd, null);
            } else {
                // No LLM yet (still loading or no model file) — rule-based fallback
                // but try KnowledgeCache context first for weather/news accuracy
                String ctxStr = knowledgeCache != null ? knowledgeCache.getContext(cmd) : "";
                String reply  = !ctxStr.isEmpty() ? ctxStr : AurigaKnowledge.fallback(cmd);
                speak(reply);
                AurigaMemoryStore.store(activity, "assistant", reply, "voice");
            }
        }
    }

    /** Human-readable model state for the AI status voice command. */
    private static String modelStateLabel(ModelDownloadManager mgr,
                                          ModelDownloadManager.ModelId id) {
        ModelDownloadManager.ModelState st = mgr.getState(id);
        if (st == ModelDownloadManager.ModelState.READY)        return "ready";
        if (st == ModelDownloadManager.ModelState.DOWNLOADING)
            return "downloading, " + mgr.getProgressPercent(id) + " percent";
        return "not downloaded";
    }

    private static boolean matches(String text, String... phrases) {
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
