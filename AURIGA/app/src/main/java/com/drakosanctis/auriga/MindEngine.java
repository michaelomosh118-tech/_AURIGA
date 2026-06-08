package com.drakosanctis.auriga;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MindEngine — on-device LLM backbone for the Auriga personal assistant.
 *
 * Wraps the MediaPipe LLM Inference API around a quantised Gemma 2B or
 * Qwen 2.5 0.5B model. Supports streaming so TTS begins speaking after the
 * FIRST complete sentence — the user hears word 1 of the answer within ~2 s
 * even if full generation takes 8–10 s.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * Required model files (drop into app/src/main/assets/ — gitignored for size):
 *
 *   gemma2b_q4.bin           Gemma 2 2B Q4 (~1.5 GB)   preferred; richer answers
 *   qwen2_5_0_5b_q8.bin      Qwen 2.5 0.5B Q8 (~400 MB) fast; good for Q&A
 *
 * Engine tries gemma2b first, then qwen. If neither is present, tryCreate()
 * returns null and AurigaVoiceEngine stays on the rule-based fallback.
 * ─────────────────────────────────────────────────────────────────────────
 *
 * MediaPipe AAR — compile-time OPTIONAL (reflection-based loading):
 *   MindEngine loads LlmInference reflectively so the project compiles without
 *   the AAR. To enable the LLM, add to build.gradle:
 *
 *       implementation 'com.google.mediapipe:tasks-genai:0.10.14'
 *
 *   Without the AAR, the engine falls back to KnowledgeCache context passthrough
 *   — still factual for weather / news queries.
 *
 * Prompt formats:
 *   Gemma : <start_of_turn>user\n{sys}{ctx}{q}<end_of_turn>\n<start_of_turn>model\n
 *   Qwen  : <|im_start|>system\n{sys}<|im_end|>\n<|im_start|>user\n{ctx}{q}<|im_end|>\n<|im_start|>assistant\n
 */
public class MindEngine {

    private static final String TAG = "MindEngine";

    static final String MODEL_GEMMA = "gemma2b_q4.bin";
    static final String MODEL_QWEN  = "qwen2_5_0_5b_q8.bin";

    private static final int MAX_TOKENS = 150; // ~100 words — comfortable TTS length

    private static final String SYSTEM_PROMPT =
        "You are Auriga, a helpful voice assistant for blind and low-vision users. "
      + "Answer in plain spoken English only. No markdown, no lists, no bullet points. "
      + "Keep answers under 80 words. Be direct and accurate. "
      + "Say so briefly if you do not know. "
      + "Current real-world data: ";

    // ── Fields ────────────────────────────────────────────────────────

    private final Context         ctx;
    private final KnowledgeCache  knowledge;
    private final TextToSpeech    tts;
    private final Handler         main  = new Handler(Looper.getMainLooper());
    private final ExecutorService exec  = Executors.newSingleThreadExecutor();
    private final AtomicBoolean   busy  = new AtomicBoolean(false);

    private Object   llm       = null; // LlmInference (reflective)
    private String   modelType = "";   // "gemma" or "qwen"
    private boolean  ready     = false;
    private Future<?>  current = null;

    // ── Factory ───────────────────────────────────────────────────────

    /**
     * Creates a MindEngine asynchronously. Model loading takes 5–30 s for
     * Gemma 2B on first run (cache population). {@code onReady} fires on a
     * background thread; engine is null if no model is available.
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
        if (assetExists(MODEL_GEMMA) && tryLoadMediaPipe(MODEL_GEMMA, "gemma")) return;
        if (assetExists(MODEL_QWEN)  && tryLoadMediaPipe(MODEL_QWEN,  "qwen"))  return;
        Log.i(TAG, "MindEngine: no model in assets. Drop " + MODEL_GEMMA
                + " or " + MODEL_QWEN + " into app/src/main/assets/.");
    }

    private boolean tryLoadMediaPipe(String modelFile, String type) {
        try {
            String path = copyAssetToCache(modelFile);
            if (path == null) return false;

            String pkg    = "com.google.mediapipe.tasks.genai.llminference.LlmInference";
            Class<?> bCls = Class.forName(pkg + "$LlmInferenceOptions$Builder");
            Object   b    = bCls.getDeclaredConstructor().newInstance();
            bCls.getMethod("setModelPath", String.class).invoke(b, path);
            bCls.getMethod("setMaxTokens",  int.class).invoke(b, MAX_TOKENS);
            Object options = bCls.getMethod("build").invoke(b);

            Class<?> llmCls = Class.forName(pkg);
            for (java.lang.reflect.Method m : llmCls.getMethods()) {
                if ("createFromOptions".equals(m.getName()) && m.getParameterCount() == 2) {
                    llm = m.invoke(null, ctx, options);
                    break;
                }
            }
            if (llm == null) return false;
            modelType = type;
            ready     = true;
            Log.i(TAG, "MindEngine ready: " + modelFile);
            return true;
        } catch (ClassNotFoundException cnf) {
            Log.i(TAG, "MediaPipe AAR absent — add 'implementation "
                    + "com.google.mediapipe:tasks-genai:0.10.14' to build.gradle");
        } catch (Throwable t) {
            Log.w(TAG, "tryLoadMediaPipe(" + modelFile + "): " + t.getMessage());
        }
        return false;
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
     *
     * @param query      Raw spoken text from the user.
     * @param onComplete Runnable called on main thread when done (may be null).
     */
    public void ask(String query, Runnable onComplete) {
        if (busy.getAndSet(true) && current != null) current.cancel(true);

        current = exec.submit(() -> {
            try {
                String contextStr = knowledge != null ? knowledge.getContext(query) : "";

                if (!ready) {
                    if (!contextStr.isEmpty()) speakMain(contextStr);
                    else speakMain(AurigaKnowledge.fallback(query));
                    if (onComplete != null) main.post(onComplete);
                    return;
                }

                // Short acknowledgement so the user knows we heard them
                speakMain("Just a moment.");

                String prompt = buildPrompt(query, contextStr);
                Log.d(TAG, "MindEngine prompt length=" + prompt.length());
                generateAndStream(prompt, onComplete);

            } catch (Throwable t) {
                Log.e(TAG, "MindEngine.ask", t);
                speakMain("Sorry, I had trouble with that.");
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
        }
    }

    // ── Generation ────────────────────────────────────────────────────

    private void generateAndStream(String prompt, Runnable onComplete) {
        try {
            tryGenerateAsync(prompt, onComplete);
        } catch (Throwable t) {
            // Async path unavailable — try synchronous fallback
            try {
                String result = (String) llm.getClass()
                        .getMethod("generateResponse", String.class)
                        .invoke(llm, prompt);
                if (result != null && !result.isEmpty()) speakMain(clean(result));
            } catch (Throwable t2) {
                speakMain(AurigaKnowledge.fallback(prompt));
            }
            if (onComplete != null) main.post(onComplete);
        }
    }

    /**
     * Streaming via LlmInference.generateAsync().
     * Each completed sentence is flushed to TTS with QUEUE_ADD — the user
     * hears a seamless continuous voice stream, not bursts.
     */
    private void tryGenerateAsync(String prompt, Runnable onComplete) throws Exception {
        StringBuilder buf = new StringBuilder();

        String fqn = "com.google.mediapipe.tasks.genai.llminference"
                   + ".LlmInference$LlmInferenceResultListener";
        Class<?> listenerClass = Class.forName(fqn);

        Object listener = Proxy.newProxyInstance(
            listenerClass.getClassLoader(),
            new Class[]{listenerClass},
            (proxy, method, args) -> {
                if ("onResult".equals(method.getName()) && args != null && args.length >= 2) {
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
                } else if ("onError".equals(method.getName())) {
                    Log.e(TAG, "LLM error callback");
                    speakMain("I encountered an error. Please try again.");
                    if (onComplete != null) main.post(onComplete);
                }
                return null;
            }
        );

        llm.getClass()
           .getMethod("generateAsync", String.class, listenerClass)
           .invoke(llm, prompt, listener);
    }

    /**
     * Flush all complete sentences from the accumulation buffer to TTS.
     * A sentence ends with . ! ? followed by a space or newline.
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
        String ctx = context.isEmpty() ? "" : context + " ";
        if ("gemma".equals(modelType)) {
            return "<start_of_turn>user\n"
                 + SYSTEM_PROMPT + ctx + query
                 + "<end_of_turn>\n<start_of_turn>model\n";
        }
        // Qwen / generic ChatML
        return "<|im_start|>system\n" + SYSTEM_PROMPT + "<|im_end|>\n"
             + "<|im_start|>user\n" + ctx + query + "<|im_end|>\n"
             + "<|im_start|>assistant\n";
    }

    // ── TTS helpers ───────────────────────────────────────────────────

    private void speakMain(String text) {
        if (tts == null || text == null || text.isEmpty()) return;
        main.post(() -> tts.speak(text, TextToSpeech.QUEUE_FLUSH, null,
                "mind_" + System.currentTimeMillis()));
    }

    private void speakQueued(String text) {
        if (tts == null || text == null || text.isEmpty()) return;
        main.post(() -> tts.speak(text, TextToSpeech.QUEUE_ADD, null,
                "mind_q_" + System.currentTimeMillis()));
    }

    // ── Utilities ─────────────────────────────────────────────────────

    /** Strip model control tokens and markdown before speaking. */
    private static String clean(String s) {
        if (s == null) return "";
        return s.replace("<end_of_turn>", "")
                .replace("<|im_end|>", "")
                .replace("**", "").replace("*", "").replace("#", "")
                .replaceAll("\\s{2,}", " ").trim();
    }

    private boolean assetExists(String name) {
        try { ctx.getAssets().openFd(name).close(); return true; }
        catch (Throwable t) { return false; }
    }

    /**
     * Copies an asset to the internal cache dir so MediaPipe can mmap it
     * (MediaPipe needs a real file path, not an AssetFileDescriptor).
     * Re-uses existing file if size matches to avoid re-copying on every launch.
     */
    private String copyAssetToCache(String name) {
        try {
            AssetFileDescriptor afd  = ctx.getAssets().openFd(name);
            long                size = afd.getDeclaredLength();
            File                out  = new File(ctx.getCacheDir(), name);
            if (out.exists() && out.length() == size) { afd.close(); return out.getAbsolutePath(); }
            Log.i(TAG, "Copying " + name + " → cache (" + (size / 1024 / 1024) + " MB)…");
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
