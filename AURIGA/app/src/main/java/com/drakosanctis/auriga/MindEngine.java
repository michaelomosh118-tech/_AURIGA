package com.drakosanctis.auriga;

import android.app.ActivityManager;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MindEngine — on-device LLM backbone for the Auriga personal assistant.
 *
 * Wraps the MediaPipe LLM Inference API around a quantised Qwen 2.5 1.5B
 * (primary) or Qwen 2.5 0.5B (low-RAM fallback) model. Supports streaming
 * so TTS begins speaking after the FIRST complete sentence — the user hears
 * word 1 of the answer within ~2 s even if full generation takes 8–10 s.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * Required model files (downloaded by ModelDownloadManager to FilesDir):
 *
 *   qwen2_5_1_5b_q8.bin     Qwen 2.5 1.5B q8 (~800 MB)  primary; richer answers
 *   qwen2_5_0_5b_q8.bin     Qwen 2.5 0.5B q8 (~519 MB)  fallback for low-RAM
 *
 * Engine tries qwen_large first (skipped on devices with <3 GB total RAM),
 * then qwen_small. If neither is present, tryCreate() returns null and
 * AurigaVoiceEngine stays on the rule-based fallback.
 * ─────────────────────────────────────────────────────────────────────────
 *
 * MediaPipe AAR declared in build.gradle:
 *   implementation 'com.google.mediapipe:tasks-genai:0.10.35'
 *
 * The engine uses reflection only for the listener proxy (Proxy.newProxyInstance)
 * so the streaming callback compiles against the interface regardless of which
 * exact nested-class name MediaPipe used in a given version.
 *
 * Prompt format (both models use ChatML):
 *   <|im_start|>system\n{sys}<|im_end|>\n<|im_start|>user\n{ctx}{q}<|im_end|>\n<|im_start|>assistant\n
 */
public class MindEngine {

    private static final String TAG = "MindEngine";

    static final String MODEL_QWEN_LARGE = "qwen2_5_1_5b_q8.bin";
    static final String MODEL_QWEN       = "qwen2_5_0_5b_q8.bin";

    private static final int MAX_TOKENS = 150;

    /**
     * Minimum total RAM (MB) required to attempt the Qwen 1.5B model.
     * Devices below this threshold skip straight to Qwen 0.5B to avoid OOM.
     * Qwen 1.5B q8 expands to ~2.5 GB in RAM; allow 500 MB headroom.
     */
    private static final long QWEN_LARGE_MIN_RAM_MB = 3_000;

    private static final String SYSTEM_PROMPT =
        "You are Auriga, a helpful voice assistant for blind and low-vision users. "
      + "Answer in plain spoken English only. No markdown, no lists, no bullet points. "
      + "Keep answers under 80 words. Be direct and accurate. "
      + "Say so briefly if you do not know. "
      + "Current real-world data: ";

    // MediaPipe package — base path for all class lookups
    private static final String MP_PKG =
            "com.google.mediapipe.tasks.genai.llminference";

    // ── Fields ────────────────────────────────────────────────────────

    private final Context         ctx;
    private final KnowledgeCache  knowledge;
    private final TextToSpeech    tts;
    private final Handler         main  = new Handler(Looper.getMainLooper());
    private final ExecutorService exec  = Executors.newSingleThreadExecutor();
    private final AtomicBoolean   busy  = new AtomicBoolean(false);

    /** The live LlmInference instance (Object to stay reflection-agnostic). */
    private Object   llm       = null;
    private String   modelType = "";
    private boolean  ready     = false;
    private Future<?>  current = null;

    // ── Factory ───────────────────────────────────────────────────────

    /**
     * Creates a MindEngine asynchronously on a background thread.
     * {@code onReady} fires on the same background thread — caller must
     * marshal to main if UI updates are needed. Engine is null if no model
     * is available or loading failed.
     */
    public static void createAsync(Context ctx, KnowledgeCache knowledge,
                                    TextToSpeech tts, ReadyCallback onReady) {
        Executors.newSingleThreadExecutor().submit(() -> {
            MindEngine e = tryCreate(ctx, knowledge, tts);
            onReady.onReady(e);
        });
    }

    /** Synchronous — call only from a background thread. */
    public static MindEngine tryCreate(Context ctx, KnowledgeCache knowledge,
                                        TextToSpeech tts) {
        MindEngine e = new MindEngine(ctx, knowledge, tts);
        e.init();
        return e.ready ? e : null;
    }

    private MindEngine(Context ctx, KnowledgeCache knowledge, TextToSpeech tts) {
        this.ctx       = ctx.getApplicationContext();
        this.knowledge = knowledge;
        this.tts       = tts;
    }

    // ── Init ──────────────────────────────────────────────────────────

    private void init() {
        // ── Qwen 2.5 1.5B (q8, ~800 MB) ─────────────────────────────
        // Skip on low-RAM devices (need ≥3 GB to avoid OOM).
        long ramMb = totalRamMb();
        if (ramMb >= QWEN_LARGE_MIN_RAM_MB) {
            // 1. APK assets (CI-bundled sideload build)
            if (assetExists(MODEL_QWEN_LARGE)) {
                if (tryLoadFromAsset(MODEL_QWEN_LARGE, "qwen_large")) return;
            }
            // 2. FilesDir (downloaded by ModelDownloadManager)
            if (ModelDownloadManager.isQwenLargeReady(ctx)) {
                File f = ModelDownloadManager.qwenLargeFilesPath(ctx);
                if (tryLoadFromFile(f, "qwen_large")) return;
            }
        } else {
            Log.i(TAG, "MindEngine: skipping Qwen 1.5B — device RAM " + ramMb
                    + " MB (need " + QWEN_LARGE_MIN_RAM_MB + " MB). Trying 0.5B.");
        }

        // ── Qwen 2.5 0.5B (q8, ~519 MB) — compact, runs on all devices ──
        // 1. APK assets
        if (assetExists(MODEL_QWEN)) {
            if (tryLoadFromAsset(MODEL_QWEN, "qwen")) return;
        }
        // 2. FilesDir
        if (ModelDownloadManager.isQwenSmallReady(ctx)) {
            File f = ModelDownloadManager.qwenSmallFilesPath(ctx);
            if (tryLoadFromFile(f, "qwen")) return;
        }

        // Nothing loaded — tell the user why via spoken diagnostic
        Log.i(TAG, "MindEngine: no model available. Say 'download AI' in the drawer.");
        speakMainNow("AI assistant is not available. Say download A I to get started.");
    }

    // ── Load helpers ──────────────────────────────────────────────────

    /**
     * Copies an APK asset to cache dir and loads it into MediaPipe.
     * MediaPipe requires a real filesystem path — it cannot mmap an AssetFD.
     */
    private boolean tryLoadFromAsset(String assetName, String type) {
        speakMainNow("Loading AI model. This may take up to 30 seconds.");
        try {
            String path = copyAssetToCache(assetName);
            if (path == null) {
                speakAndLog(TAG, "Asset copy failed for " + assetName);
                return false;
            }
            return loadMediaPipe(path, type, assetName);
        } catch (Throwable t) {
            speakAndLog(TAG, "Failed to load bundled AI model: " + t.getMessage());
            return false;
        }
    }

    /**
     * Loads a model from a FilesDir file written by {@link ModelDownloadManager}.
     */
    private boolean tryLoadFromFile(File modelFile, String type) {
        speakMainNow("Loading AI model. This may take up to 30 seconds.");
        try {
            return loadMediaPipe(modelFile.getAbsolutePath(), type, modelFile.getName());
        } catch (Throwable t) {
            // Spoken diagnostic so the user knows what went wrong without logcat
            speakAndLog(TAG, "AI model failed to load: " + t.getMessage()
                    + ". Try re-downloading.");
            return false;
        }
    }

    // ── MediaPipe bootstrap ───────────────────────────────────────────

    /**
     * Loads a model at {@code absolutePath} via the MediaPipe LlmInference API.
     *
     * Reflection is used ONLY for the options/builder step so the code stays
     * compatible with MediaPipe versions that changed the class nesting
     * (LlmInference$LlmInferenceOptions  vs  top-level LlmInferenceOptions).
     * The API surface is otherwise stable across 0.10.x.
     *
     * @throws Exception any reflection / MediaPipe error — caller speaks + logs it
     */
    private boolean loadMediaPipe(String absolutePath, String type, String logName)
            throws Exception {

        // ── Step 1: obtain the options builder ────────────────────────
        //
        // MediaPipe tasks-genai changed the nesting of LlmInferenceOptions:
        //   ≤ 0.10.14:  LlmInference.LlmInferenceOptions  (nested static class)
        //               accessed via  Class.forName(pkg + ".LlmInference$LlmInferenceOptions")
        //   ≥ 0.10.22:  LlmInferenceOptions  (top-level class in the same package)
        //               accessed via  Class.forName(pkg + ".LlmInferenceOptions")
        //
        // In BOTH cases the builder is obtained via the static factory:
        //   LlmInferenceOptions.builder()
        // NOT via new Builder() — the Builder constructor is package-private / auto-value.
        //
        // We try top-level first (0.10.22+ / 0.10.35 target), then fall back.

        Object builder = null;
        for (String optsCn : new String[]{
                MP_PKG + ".LlmInferenceOptions",
                MP_PKG + ".LlmInference$LlmInferenceOptions"}) {
            try {
                Class<?> optsCls = Class.forName(optsCn);
                builder = optsCls.getMethod("builder").invoke(null);
                Log.d(TAG, "Options class: " + optsCn);
                break;
            } catch (ClassNotFoundException ignored) {
                // Try next candidate
            }
        }
        if (builder == null) {
            throw new IllegalStateException(
                "Cannot find LlmInferenceOptions class — "
                + "check 'implementation com.google.mediapipe:tasks-genai:0.10.35' "
                + "in build.gradle");
        }

        // ── Step 2: configure the builder ────────────────────────────
        Class<?> bCls = builder.getClass();

        bCls.getMethod("setModelPath", String.class).invoke(builder, absolutePath);

        // setMaxTokens was renamed setMaxNewTokens in some pre-release builds.
        // Try both; if neither exists, fall through with the model's default.
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

        // ── Step 3: create the LlmInference engine ───────────────────
        Class<?> llmCls = Class.forName(MP_PKG + ".LlmInference");
        Object engine = null;
        for (Method m : llmCls.getMethods()) {
            if ("createFromOptions".equals(m.getName())
                    && m.getParameterCount() == 2) {
                engine = m.invoke(null, ctx, options);
                break;
            }
        }
        if (engine == null) {
            throw new IllegalStateException(
                "createFromOptions returned null for " + logName);
        }

        llm       = engine;
        modelType = type;
        ready     = true;
        Log.i(TAG, "MindEngine ready: " + logName + " (" + type + ")");
        speakMainNow("AI assistant ready. You can ask me anything.");
        return true;
    }

    // ── Public API ────────────────────────────────────────────────────

    /**
     * Answer the user's spoken query.
     *
     * Pipeline:
     *   1. KnowledgeCache grounds the prompt with real-world data.
     *   2. LLM streams tokens; each completed sentence is piped to TTS
     *      immediately via QUEUE_ADD — continuous voice, no waiting.
     *   3. Falls back to KnowledgeCache context or AurigaKnowledge.fallback()
     *      when no model is loaded.
     */
    public void ask(String query, Runnable onComplete) {
        if (busy.getAndSet(true) && current != null) current.cancel(true);

        current = exec.submit(() -> {
            try {
                String contextStr = knowledge != null ? knowledge.getContext(query) : "";

                if (!ready) {
                    // Model not loaded — speak best available offline answer
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
                Log.e(TAG, "MindEngine.ask", t);
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

    public void close() {
        cancel();
        exec.shutdown();
        if (llm != null) {
            try { llm.getClass().getMethod("close").invoke(llm); }
            catch (Throwable ignored) {}
            llm   = null;
            ready = false;
        }
    }

    // ── Generation ────────────────────────────────────────────────────

    private void generateAndStream(String prompt, Runnable onComplete) {
        // Try streaming (generateResponseAsync or generateAsync)
        boolean streamStarted = false;
        try {
            streamStarted = tryGenerateAsync(prompt, onComplete);
        } catch (Throwable t) {
            Log.w(TAG, "Async generation unavailable (" + t.getMessage() + "), using sync fallback");
        }
        if (streamStarted) return;

        // Sync fallback — generateResponse / generateResult
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

    /**
     * Streaming via generateResponseAsync / generateAsync.
     *
     * MediaPipe changed the method name and listener interface across versions:
     *   0.10.x early : generateAsync(String, LlmInferenceResultListener)
     *   0.10.x later : generateResponseAsync(String, ProgressListener<String>)
     *
     * We probe for both method names and both listener class names via reflection.
     * The actual invocation uses a Proxy so we don't need to import the interface.
     *
     * @return true if the async call was dispatched successfully
     */
    private boolean tryGenerateAsync(String prompt, Runnable onComplete) throws Exception {
        // Find the async generate method — try both names
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

        // Find the listener interface class — try both known names
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

        // The listener method may be "onResult(String, boolean)" or "run(String, boolean)"
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

    /**
     * Flush all complete sentences from the accumulation buffer to TTS.
     * A sentence ends with . ! ? followed by a space or end-of-string.
     */
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

    /** Post a QUEUE_FLUSH speak to the main thread. */
    private void speakMain(String text) {
        if (tts == null || text == null || text.isEmpty()) return;
        main.post(() -> tts.speak(text, TextToSpeech.QUEUE_FLUSH, null,
                "mind_" + System.currentTimeMillis()));
    }

    /**
     * Speak immediately, even if called from any thread (init / background).
     * Uses QUEUE_ADD so it doesn't interrupt an in-progress announcement.
     */
    private void speakMainNow(String text) {
        if (tts == null || text == null || text.isEmpty()) return;
        main.post(() -> tts.speak(text, TextToSpeech.QUEUE_ADD, null,
                "mind_diag_" + System.currentTimeMillis()));
    }

    /** Speak a sentence in the streaming queue (non-interrupting). */
    private void speakQueued(String text) {
        if (tts == null || text == null || text.isEmpty()) return;
        main.post(() -> tts.speak(text, TextToSpeech.QUEUE_ADD, null,
                "mind_q_" + System.currentTimeMillis()));
    }

    /**
     * Log an error AND speak a short diagnostic so the user knows what went
     * wrong without needing adb logcat.
     */
    private void speakAndLog(String tag, String message) {
        Log.e(tag, message);
        speakMainNow(message);
    }

    // ── Utilities ─────────────────────────────────────────────────────

    private long totalRamMb() {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return 0;
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return mi.totalMem / (1024 * 1024);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Strip model control tokens and markdown before speaking. */
    private static String clean(String s) {
        if (s == null) return "";
        return s.replace("<end_of_turn>", "")
                .replace("<|im_end|>", "")
                .replace("<|im_start|>", "")
                .replace("**", "").replace("*", "").replace("#", "")
                .replaceAll("\\s{2,}", " ").trim();
    }

    private boolean assetExists(String name) {
        try { ctx.getAssets().openFd(name).close(); return true; }
        catch (Throwable t) { return false; }
    }

    /**
     * Copies an asset to the internal cache dir so MediaPipe can mmap it.
     * Re-uses existing file if size matches to avoid re-copying on every launch.
     */
    private String copyAssetToCache(String name) {
        try {
            AssetFileDescriptor afd  = ctx.getAssets().openFd(name);
            long                size = afd.getDeclaredLength();
            File                out  = new File(ctx.getCacheDir(), name);
            if (out.exists() && out.length() == size) {
                afd.close();
                return out.getAbsolutePath();
            }
            Log.i(TAG, "Copying " + name + " to cache (" + (size / 1024 / 1024) + " MB)…");
            try (FileInputStream  fis = new FileInputStream(afd.getFileDescriptor());
                 FileOutputStream fos = new FileOutputStream(out)) {
                fis.getChannel().transferTo(afd.getStartOffset(), size, fos.getChannel());
            }
            afd.close();
            Log.i(TAG, "Model cached: " + out.getAbsolutePath());
            return out.getAbsolutePath();
        } catch (Throwable t) {
            Log.e(TAG, "copyAssetToCache(" + name + "): " + t.getMessage());
            return null;
        }
    }

    // ── Callback ──────────────────────────────────────────────────────

    public interface ReadyCallback {
        /** Called on a background thread. engine is null if unavailable. */
        void onReady(MindEngine engine);
    }
}
