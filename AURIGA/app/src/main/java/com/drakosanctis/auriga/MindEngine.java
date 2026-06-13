package com.drakosanctis.auriga;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;

import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MindEngine — on-device LLM backbone for the Auriga personal assistant.
 *
 * Architecture: load-on-demand with configurable inactivity offload and
 * automatic reload.
 *   - Model is loaded the first time ask() is called and the caller signals
 *     the intent requires LLM reasoning.
 *   - After each response the inactivity timer is reset. If no further request
 *     arrives within the configured timeout the model is offloaded and GC is
 *     suggested, freeing RAM for vision/nav engines.
 *   - On the next ask() after an offload, the model is transparently reloaded
 *     from disk so the user never sees a permanent "model not loaded" state.
 *   - On devices with ≥8 GB RAM the model stays resident (timer still resets but
 *     offload is skipped) for instant responses throughout a conversation.
 *
 * Exception policy:
 *   - DEBUG builds: exceptions are re-thrown so the crash lands in Logcat with a
 *     full stack trace — this is intentional during development.
 *   - RELEASE builds: exceptions are swallowed gracefully; the AI fails with a
 *     spoken message while navigation continues unaffected.
 *
 * Required model files (downloaded by ModelDownloadManager to FilesDir):
 *   qwen2_5_1_5b_q8.bin     Qwen 2.5 1.5B q8 (~800 MB)  primary; richer answers
 *   qwen2_5_0_5b_q8.bin     Qwen 2.5 0.5B q8 (~519 MB)  fallback for low-RAM
 */
public class MindEngine {

    private static final String TAG = "MindEngine";

    static final String MODEL_QWEN_LARGE = "qwen2_5_1_5b_q8.bin";
    static final String MODEL_QWEN       = "qwen2_5_0_5b_q8.bin";

    private static final int MAX_TOKENS = 150;

    /**
     * Minimum total RAM (MB) required to attempt the Qwen 1.5B model.
     * Qwen 1.5B q8 expands to ~2.5 GB; allow 500 MB headroom.
     */
    private static final long QWEN_LARGE_MIN_RAM_MB  = 3_000;

    /**
     * Devices with this much RAM or more keep the model resident in memory
     * (load-on-demand still applies for the first call, but the inactivity
     * offload is suppressed so responses are always instant).
     */
    private static final long KEEP_RESIDENT_RAM_MB   = 8_000;

    /** Default inactivity timeout before offloading (seconds). User-configurable. */
    public static final int DEFAULT_OFFLOAD_TIMEOUT_SEC = 120;

    /** SharedPreferences key for the user-configured timeout. */
    public static final String PREF_OFFLOAD_TIMEOUT = "mind_offload_timeout_sec";
    public static final String PREFS_NAME           = "auriga_prefs";

    private static final String SYSTEM_PROMPT =
        "You are Auriga, a helpful voice assistant for blind and low-vision users. "
      + "Answer in plain spoken English only. No markdown, no lists, no bullet points. "
      + "Keep answers under 80 words. Be direct and accurate. "
      + "Say so briefly if you do not know. "
      + "Current real-world data: ";

    private static final String MP_PKG =
            "com.google.mediapipe.tasks.genai.llminference";

    // ── Shared static executor — one thread pool for ALL MindEngine instances ──
    // Using a static executor prevents the executor-leak where every createAsync()
    // call previously spun up a brand-new single-thread executor and never shut it down.
    private static final ExecutorService MODEL_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "MindEngine-worker");
                t.setDaemon(true);
                return t;
            });

    private static final ScheduledExecutorService OFFLOAD_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "MindEngine-offload");
                t.setDaemon(true);
                return t;
            });

    // ── Fields ────────────────────────────────────────────────────────

    private final Context         ctx;
    private final KnowledgeCache  knowledge;
    private final TextToSpeech    tts;
    private final Handler         main  = new Handler(Looper.getMainLooper());
    private final AtomicBoolean   busy  = new AtomicBoolean(false);

    private Object            llm       = null;
    private String            modelType = "";
    private boolean           ready     = false;
    private boolean           hasModel  = false;
    private Future<?>         current   = null;
    private ScheduledFuture<?>  offloadTimer = null;

    // ── Factory ───────────────────────────────────────────────────────

    /**
     * Creates a MindEngine asynchronously on the shared background thread.
     * onReady fires on the background thread — marshal to main if UI updates needed.
     * Engine is null if no model is available or loading failed.
     */
    public static void createAsync(Context ctx, KnowledgeCache knowledge,
                                    TextToSpeech tts, ReadyCallback onReady) {
        MODEL_EXECUTOR.submit(() -> {
            MindEngine e = tryCreate(ctx, knowledge, tts);
            onReady.onReady(e);
        });
    }

    /** Synchronous — call only from a background thread. */
    public static MindEngine tryCreate(Context ctx, KnowledgeCache knowledge,
                                        TextToSpeech tts) {
        MindEngine e = new MindEngine(ctx, knowledge, tts);
        e.init();
        return (e.ready || e.hasModel) ? e : null;
    }

    private MindEngine(Context ctx, KnowledgeCache knowledge, TextToSpeech tts) {
        this.ctx       = ctx.getApplicationContext();
        this.knowledge = knowledge;
        this.tts       = tts;
    }

    // ── Init ──────────────────────────────────────────────────────────

    private void init() {
        long ramMb = totalRamMb();
        Log.i(TAG, "MindEngine.init() — device RAM: " + ramMb + " MB");

        if (ramMb >= QWEN_LARGE_MIN_RAM_MB
                && ModelDownloadManager.isQwenLargeReady(ctx)) {
            File f = ModelDownloadManager.qwenLargeFilesPath(ctx);
            logModelDiagnostics(f);
            if (tryLoadFromFile(f, "qwen_large")) { hasModel = true; return; }
        } else if (ramMb < QWEN_LARGE_MIN_RAM_MB) {
            Log.i(TAG, "Skipping Qwen 1.5B — device RAM " + ramMb
                    + " MB (need " + QWEN_LARGE_MIN_RAM_MB + " MB). Trying 0.5B.");
        }

        if (ModelDownloadManager.isQwenSmallReady(ctx)) {
            File f = ModelDownloadManager.qwenSmallFilesPath(ctx);
            logModelDiagnostics(f);
            if (tryLoadFromFile(f, "qwen")) { hasModel = true; return; }
        }

        Log.i(TAG, "MindEngine: no model available. Say 'download AI' to get started.");
        speakMainNow("AI assistant is not available. Say download A I to get started.");
    }

    /**
     * Reload the model from disk after an inactivity offload.
     * Called on the MODEL_EXECUTOR thread when ask() detects the model was
     * offloaded but files are still on disk.
     *
     * @return true if the model was reloaded successfully
     */
    private boolean reload() {
        Log.i(TAG, "Reloading model after offload…");
        speakMainNow("Reloading AI model. One moment.");
        init();
        return ready;
    }

    /**
     * Emit diagnostic log lines before attempting to load a file model.
     * These are critical for diagnosing format/size/corruption failures in Logcat.
     */
    private void logModelDiagnostics(File f) {
        Log.i(TAG, "Model path   = " + f.getAbsolutePath());
        Log.i(TAG, "Model size   = " + f.length() + " bytes ("
                + (f.length() / 1_000_000) + " MB)");
        Log.i(TAG, "Model exists = " + f.exists());
        Log.i(TAG, "Model read   = " + f.canRead());
    }

    // ── Load helpers ──────────────────────────────────────────────────

    private boolean tryLoadFromFile(File modelFile, String type) {
        speakMainNow("Loading AI model. This may take up to 30 seconds.");
        try {
            return loadMediaPipe(modelFile.getAbsolutePath(), type, modelFile.getName());
        } catch (Throwable t) {
            return handleLoadFailure(t, "file: " + modelFile.getName());
        }
    }

    /**
     * Centralised failure handler.
     *
     * DEBUG: full stack trace is re-thrown so you see the exact line in Logcat.
     * RELEASE: spoken degradation message; navigation keeps running.
     */
    private boolean handleLoadFailure(Throwable t, String source) {
        Log.e(TAG, "MODEL LOAD FAILURE — source=" + source
                + " | type=" + t.getClass().getSimpleName()
                + " | message=" + t.getMessage(), t);

        if (BuildConfig.DEBUG) {
            throw new RuntimeException("MindEngine load failed (" + source + "): "
                    + t.getMessage(), t);
        }

        speakMainNow("AI assistant failed to load. "
                + "Navigation and vision features are unaffected.");
        return false;
    }

    // ── MediaPipe bootstrap ───────────────────────────────────────────

    private boolean loadMediaPipe(String absolutePath, String type, String logName)
            throws Exception {

        Log.d(TAG, "loadMediaPipe → path=" + absolutePath + " type=" + type);

        // ── Step 1: options builder ────────────────────────────────────
        Object builder = null;
        for (String optsCn : new String[]{
                MP_PKG + ".LlmInferenceOptions",
                MP_PKG + ".LlmInference$LlmInferenceOptions"}) {
            try {
                Class<?> optsCls = Class.forName(optsCn);
                builder = optsCls.getMethod("builder").invoke(null);
                Log.d(TAG, "Options class: " + optsCn);
                break;
            } catch (ClassNotFoundException ignored) {}
        }
        if (builder == null) {
            throw new IllegalStateException(
                "Cannot find LlmInferenceOptions class — "
                + "check 'implementation com.google.mediapipe:tasks-genai:0.10.35'");
        }

        // ── Step 2: configure ─────────────────────────────────────────
        Class<?> bCls = builder.getClass();
        bCls.getMethod("setModelPath", String.class).invoke(builder, absolutePath);

        boolean tokenLimitSet = false;
        for (String setter : new String[]{"setMaxTokens", "setMaxNewTokens"}) {
            try {
                bCls.getMethod(setter, int.class).invoke(builder, MAX_TOKENS);
                tokenLimitSet = true;
                break;
            } catch (NoSuchMethodException ignored) {}
        }
        if (!tokenLimitSet) {
            Log.w(TAG, "setMaxTokens/setMaxNewTokens not found — using model default");
        }

        Object options = bCls.getMethod("build").invoke(builder);

        // ── Step 3: create engine ─────────────────────────────────────
        Class<?> llmCls = Class.forName(MP_PKG + ".LlmInference");
        Object engine = null;
        for (Method m : llmCls.getMethods()) {
            if ("createFromOptions".equals(m.getName()) && m.getParameterCount() == 2) {
                engine = m.invoke(null, ctx, options);
                break;
            }
        }
        if (engine == null) {
            throw new IllegalStateException(
                "createFromOptions returned null for " + logName
                + " — model format may be incompatible with tasks-genai:0.10.35");
        }

        llm       = engine;
        modelType = type;
        ready     = true;
        Log.i(TAG, "MindEngine ready: " + logName + " (" + type + ")");
        speakMainNow("AI assistant ready. You can ask me anything.");
        scheduleOffload();
        return true;
    }

    // ── Inactivity offload ────────────────────────────────────────────

    /**
     * Schedule automatic model offload after user-configured idle timeout.
     * Skipped on high-RAM devices (≥8 GB) where keeping the model resident is safe.
     */
    private void scheduleOffload() {
        cancelOffloadTimer();
        if (totalRamMb() >= KEEP_RESIDENT_RAM_MB) {
            Log.d(TAG, "High-RAM device — model will stay resident.");
            return;
        }
        int timeoutSec = getOffloadTimeoutSec();
        Log.d(TAG, "Offload timer armed: " + timeoutSec + "s");
        offloadTimer = OFFLOAD_SCHEDULER.schedule(() -> {
            Log.i(TAG, "MindEngine idle for " + timeoutSec + "s — offloading model.");
            main.post(() -> speakMainNow("AI assistant sleeping to save memory."));
            offloadModel();
        }, timeoutSec, TimeUnit.SECONDS);
    }

    private void cancelOffloadTimer() {
        if (offloadTimer != null && !offloadTimer.isDone()) {
            offloadTimer.cancel(false);
        }
        offloadTimer = null;
    }

    /** Unload the native model, release MediaPipe resources, and suggest GC. */
    private void offloadModel() {
        if (llm != null) {
            try { llm.getClass().getMethod("close").invoke(llm); }
            catch (Throwable ignored) {}
            llm   = null;
            ready = false;
            Log.i(TAG, "Model offloaded. Suggesting GC.");
            System.gc();
        }
    }

    private int getOffloadTimeoutSec() {
        try {
            SharedPreferences prefs =
                    ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            int v = prefs.getInt(PREF_OFFLOAD_TIMEOUT, DEFAULT_OFFLOAD_TIMEOUT_SEC);
            return (v > 0) ? v : DEFAULT_OFFLOAD_TIMEOUT_SEC;
        } catch (Throwable t) {
            return DEFAULT_OFFLOAD_TIMEOUT_SEC;
        }
    }

    // ── Public API ────────────────────────────────────────────────────

    /**
     * Answer the user's spoken query.
     * Resets the inactivity timer on every call so a live conversation keeps
     * the model warm for at least one more timeout period.
     */
    public void ask(String query, Runnable onComplete) {
        if (busy.getAndSet(true) && current != null) current.cancel(true);

        // Re-arm the offload timer — user is active
        scheduleOffload();

        current = MODEL_EXECUTOR.submit(() -> {
            try {
                String contextStr = knowledge != null ? knowledge.getContext(query) : "";

                // If the model was offloaded, transparently reload from disk
                if (!ready && hasModel) {
                    reload();
                }

                if (!ready) {
                    if (!contextStr.isEmpty()) speakMain(contextStr);
                    else speakMain(AurigaKnowledge.fallback(query));
                    if (onComplete != null) main.post(onComplete);
                    return;
                }

                speakMain("Just a moment.");
                String prompt = buildPrompt(query, contextStr);
                Log.d(TAG, "MindEngine prompt length=" + prompt.length());
                generateAndStream(prompt, onComplete);

            } catch (Throwable t) {
                Log.e(TAG, "MindEngine.ask exception", t);
                speakMain("Sorry, I had trouble with that. Please try again.");
                if (onComplete != null) main.post(onComplete);
            } finally {
                busy.set(false);
            }
        });
    }

    /** Cancel generation and silence TTS. */
    public void cancel() {
        if (current != null) current.cancel(true);
        busy.set(false);
        if (tts != null) try { tts.stop(); } catch (Throwable ignored) {}
    }

    public boolean isBusy() { return busy.get(); }

    public boolean isReady() { return ready; }

    /** True if a model file is on disk (even if currently offloaded). */
    public boolean hasModelOnDisk() { return hasModel; }

    public void close() {
        cancel();
        cancelOffloadTimer();
        offloadModel();
    }

    // ── Generation ────────────────────────────────────────────────────

    private void generateAndStream(String prompt, Runnable onComplete) {
        boolean streamStarted = false;
        try {
            streamStarted = tryGenerateAsync(prompt, onComplete);
        } catch (Throwable t) {
            Log.w(TAG, "Async generation unavailable (" + t.getMessage() + "), using sync fallback");
        }
        if (streamStarted) return;

        try {
            String result = null;
            for (String methodName : new String[]{"generateResponse", "generateResult"}) {
                try {
                    result = (String) llm.getClass()
                            .getMethod(methodName, String.class)
                            .invoke(llm, prompt);
                    break;
                } catch (NoSuchMethodException ignored) {}
            }
            if (result != null && !result.isEmpty()) speakMain(clean(result));
            else speakMain(AurigaKnowledge.fallback(prompt));
        } catch (Throwable t2) {
            Log.e(TAG, "Sync generation also failed", t2);
            speakMain("I had trouble answering that. Please try again.");
        }
        if (onComplete != null) main.post(onComplete);
    }

    private boolean tryGenerateAsync(String prompt, Runnable onComplete) throws Exception {
        Method asyncMethod = null;
        for (Method m : llm.getClass().getMethods()) {
            String n = m.getName();
            if ((n.equals("generateResponseAsync") || n.equals("generateAsync"))
                    && m.getParameterCount() == 2) {
                asyncMethod = m;
                break;
            }
        }
        if (asyncMethod == null) return false;

        Class<?> listenerInterface = null;
        for (String cn : new String[]{
                MP_PKG + ".LlmInference$LlmInferenceResultListener",
                "com.google.mediapipe.tasks.core.experimental.ProgressListener",
                MP_PKG + ".LlmInferenceResultListener"}) {
            try {
                listenerInterface = Class.forName(cn);
                break;
            } catch (ClassNotFoundException ignored) {}
        }
        if (listenerInterface == null) return false;

        final StringBuilder buf = new StringBuilder();
        final Class<?> finalListenerInterface = listenerInterface;

        Object listener = Proxy.newProxyInstance(
            finalListenerInterface.getClassLoader(),
            new Class[]{finalListenerInterface},
            (proxy, method, args) -> {
                String mName = method.getName();
                if (("onResult".equals(mName) || "run".equals(mName))
                        && args != null && args.length >= 2) {
                    String  token  = (String) args[0];
                    boolean isDone = Boolean.TRUE.equals(args[1]);
                    if (token != null) buf.append(token);
                    flushSentences(buf);
                    if (isDone) {
                        String tail = clean(buf.toString().trim());
                        if (!tail.isEmpty()) speakQueued(tail);
                        buf.setLength(0);
                        if (onComplete != null) main.post(onComplete);
                    }
                } else if ("onError".equals(mName)) {
                    String errMsg = (args != null && args.length > 0 && args[0] != null)
                            ? args[0].toString() : "unknown";
                    Log.e(TAG, "LLM error callback: " + errMsg);
                    speakMain("I encountered an error. Please try again.");
                    if (onComplete != null) main.post(onComplete);
                }
                return null;
            }
        );

        asyncMethod.invoke(llm, prompt, listener);
        return true;
    }

    private void flushSentences(StringBuilder buf) {
        while (true) {
            String s = buf.toString();
            int end = -1;
            for (int i = 0; i < s.length() - 1; i++) {
                char c = s.charAt(i);
                if ((c == '.' || c == '!' || c == '?')
                        && (s.charAt(i + 1) == ' ' || s.charAt(i + 1) == '\n')) {
                    end = i;
                    break;
                }
            }
            if (end < 0) break;
            String sentence = clean(s.substring(0, end + 1).trim());
            buf.delete(0, end + 2);
            if (!sentence.isEmpty()) speakQueued(sentence);
        }
    }

    // ── Prompt builder ────────────────────────────────────────────────

    private String buildPrompt(String query, String context) {
        String ctxStr = context.isEmpty() ? "" : context + " ";
        return "<|im_start|>system\n" + SYSTEM_PROMPT + "<|im_end|>\n"
             + "<|im_start|>user\n" + ctxStr + query + "<|im_end|>\n"
             + "<|im_start|>assistant\n";
    }

    // ── TTS helpers ───────────────────────────────────────────────────

    private void speakMain(String text) {
        if (tts == null || text == null || text.isEmpty()) return;
        main.post(() -> tts.speak(text, TextToSpeech.QUEUE_FLUSH, null,
                "mind_" + System.currentTimeMillis()));
    }

    private void speakMainNow(String text) {
        if (tts == null || text == null || text.isEmpty()) return;
        main.post(() -> tts.speak(text, TextToSpeech.QUEUE_ADD, null,
                "mind_diag_" + System.currentTimeMillis()));
    }

    private void speakQueued(String text) {
        if (tts == null || text == null || text.isEmpty()) return;
        main.post(() -> tts.speak(text, TextToSpeech.QUEUE_ADD, null,
                "mind_q_" + System.currentTimeMillis()));
    }

    // ── Utilities ─────────────────────────────────────────────────────

    private long totalRamMb() {
        try {
            ActivityManager am = (ActivityManager)
                    ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return 0;
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return mi.totalMem / (1024 * 1024);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace("<end_of_turn>", "")
                .replace("<|im_end|>", "")
                .replace("<|im_start|>", "")
                .replace("**", "").replace("*", "").replace("#", "")
                .replaceAll("\\s{2,}", " ").trim();
    }

    /**
     * Quick MediaPipe probe — validates that a model file can be opened
     * by MediaPipe before marking it READY. Returns true if the model
     * can at least be constructed; false if the file is corrupt or
     * incompatible with tasks-genai:0.10.35.
     */
    public static boolean probeModel(Context ctx, File modelFile) {
        if (!modelFile.exists() || !modelFile.canRead()) return false;
        try {
            Class<?> optsCls = null;
            for (String cn : new String[]{
                    MP_PKG + ".LlmInferenceOptions",
                    MP_PKG + ".LlmInference$LlmInferenceOptions"}) {
                try { optsCls = Class.forName(cn); break; }
                catch (ClassNotFoundException ignored) {}
            }
            if (optsCls == null) return false;
            Object builder = optsCls.getMethod("builder").invoke(null);
            builder.getClass().getMethod("setModelPath", String.class)
                    .invoke(builder, modelFile.getAbsolutePath());
            try {
                builder.getClass().getMethod("setMaxTokens", int.class)
                        .invoke(builder, 1);
            } catch (NoSuchMethodException ignored) {
                try {
                    builder.getClass().getMethod("setMaxNewTokens", int.class)
                            .invoke(builder, 1);
                } catch (NoSuchMethodException ig2) {}
            }
            Object options = builder.getClass().getMethod("build").invoke(builder);
            Class<?> llmCls = Class.forName(MP_PKG + ".LlmInference");
            Object engine = null;
            for (Method m : llmCls.getMethods()) {
                if ("createFromOptions".equals(m.getName()) && m.getParameterCount() == 2) {
                    engine = m.invoke(null, ctx.getApplicationContext(), options);
                    break;
                }
            }
            if (engine != null) {
                try { engine.getClass().getMethod("close").invoke(engine); }
                catch (Throwable ignored) {}
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "probeModel failed for " + modelFile.getName() + ": " + t.getMessage());
        }
        return false;
    }

    // ── Callback ──────────────────────────────────────────────────────

    public interface ReadyCallback {
        /** Called on the shared MODEL_EXECUTOR thread. engine is null if unavailable. */
        void onReady(MindEngine engine);
    }
}
