package com.drakosanctis.auriga;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ModelDownloadManager — downloads Qwen 2.5 models to the app's private
 * files directory so they can be loaded by MindEngine without being bundled
 * in the APK (which saved ~1.3 GB of install size).
 *
 * Both models are downloaded on first use over any network connection.
 * MindEngine already checks both APK assets AND FilesDir so once a
 * download finishes the engine picks it up on the next voice query with
 * no APK update required.
 *
 * Storage paths:
 *   getFilesDir()/models/qwen2_5_0_5b_q8.bin   ~519 MB
 *   getFilesDir()/models/qwen2_5_1_5b_q8.bin   ~800 MB
 *
 * Retry policy: up to 3 attempts, 10-second back-off. Partial files are
 * kept and resumed via HTTP Range on the next attempt/launch.
 */
public class ModelDownloadManager {

    private static final String TAG = "ModelDownloadMgr";

    // ── Model identifiers ─────────────────────────────────────────────

    public enum ModelId {
        QWEN_SMALL,
        QWEN_LARGE
    }

    public enum ModelState {
        NOT_DOWNLOADED,
        DOWNLOADING,
        READY
    }

    /**
     * Implement this to receive live progress and state updates on the
     * main thread. Register via {@link #registerListener} and unregister
     * via {@link #unregisterListener}.
     */
    public interface DownloadListener {
        /** Called at least once per 1% of progress change; 0–100. */
        void onProgress(ModelId model, int percentDone);
        /** Called whenever a model transitions between NOT_DOWNLOADED,
         *  DOWNLOADING, and READY. */
        void onStateChanged(ModelId model, ModelState newState);
    }

    // ── Model constants ───────────────────────────────────────────────

    public static final String MODEL_DIR = "models";

    public static final String QWEN_SMALL_FILENAME = "qwen2_5_0_5b_q8.bin";
    private static final String QWEN_SMALL_URL =
        "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/"
        + "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.tflite";
    private static final long QWEN_SMALL_MIN_BYTES = 400_000_000L; // 400 MB floor
    private static final long QWEN_SMALL_EXPECTED_BYTES = 519_000_000L;

    public static final String QWEN_LARGE_FILENAME = "qwen2_5_1_5b_q8.bin";
    private static final String QWEN_LARGE_URL =
        "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/"
        + "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.tflite";
    private static final long QWEN_LARGE_MIN_BYTES = 600_000_000L; // 600 MB floor
    private static final long QWEN_LARGE_EXPECTED_BYTES = 800_000_000L;

    private static final int MAX_RETRIES       = 3;
    private static final int RETRY_DELAY_MS    = 10_000;
    private static final int CONNECT_TIMEOUT   = 30_000;
    private static final int READ_TIMEOUT      = 60_000;
    private static final int BUFFER_SIZE       = 128 * 1024; // 128 KB

    // ── SharedPreferences keys ────────────────────────────────────────

    private static final String PREFS_NAME                  = "auriga_model_prefs";
    private static final String PREF_QWEN_SMALL_DONE        = "qwen_small_download_complete";
    private static final String PREF_QWEN_SMALL_BYTES       = "qwen_small_downloaded_bytes";
    private static final String PREF_QWEN_LARGE_DONE        = "qwen_large_download_complete";
    private static final String PREF_QWEN_LARGE_BYTES       = "qwen_large_downloaded_bytes";

    // ── Instance state ────────────────────────────────────────────────

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newFixedThreadPool(2);
    private final AtomicBoolean runningSmall = new AtomicBoolean(false);
    private final AtomicBoolean runningLarge = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<DownloadListener> listeners = new CopyOnWriteArrayList<>();

    private volatile TextToSpeech tts;

    public ModelDownloadManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** Attach a TTS instance so progress can be spoken. Optional. */
    public void setTts(TextToSpeech tts) {
        this.tts = tts;
    }

    // ── Listener management ───────────────────────────────────────────

    public void registerListener(DownloadListener l) {
        if (l != null) listeners.addIfAbsent(l);
    }

    public void unregisterListener(DownloadListener l) {
        listeners.remove(l);
    }

    // ── Public query API ──────────────────────────────────────────────

    /** File path where Qwen 0.5B will be stored / is stored. */
    public static File qwenSmallFilesPath(Context ctx) {
        return new File(new File(ctx.getFilesDir(), MODEL_DIR), QWEN_SMALL_FILENAME);
    }

    /** File path where Qwen 1.5B will be stored / is stored. */
    public static File qwenLargeFilesPath(Context ctx) {
        return new File(new File(ctx.getFilesDir(), MODEL_DIR), QWEN_LARGE_FILENAME);
    }

    /** True if Qwen 0.5B has been fully downloaded and verified. */
    public static boolean isQwenSmallReady(Context ctx) {
        File f = qwenSmallFilesPath(ctx);
        return f.exists() && f.length() >= QWEN_SMALL_MIN_BYTES;
    }

    /** True if Qwen 1.5B has been fully downloaded and verified. */
    public static boolean isQwenLargeReady(Context ctx) {
        File f = qwenLargeFilesPath(ctx);
        return f.exists() && f.length() >= QWEN_LARGE_MIN_BYTES;
    }

    /** Returns the current state of a model. */
    public ModelState getState(ModelId id) {
        switch (id) {
            case QWEN_SMALL:
                if (isQwenSmallReady(ctx)) return ModelState.READY;
                if (runningSmall.get())    return ModelState.DOWNLOADING;
                return ModelState.NOT_DOWNLOADED;
            case QWEN_LARGE:
                if (isQwenLargeReady(ctx)) return ModelState.READY;
                if (runningLarge.get())    return ModelState.DOWNLOADING;
                return ModelState.NOT_DOWNLOADED;
            default:
                return ModelState.NOT_DOWNLOADED;
        }
    }

    /** Returns download progress 0–100, or 0 if not started. */
    public int getProgressPercent(ModelId id) {
        switch (id) {
            case QWEN_SMALL: {
                if (isQwenSmallReady(ctx)) return 100;
                long bytes = getPrefs().getLong(PREF_QWEN_SMALL_BYTES, 0);
                if (bytes <= 0) return 0;
                return (int) Math.min(99, bytes * 100 / QWEN_SMALL_EXPECTED_BYTES);
            }
            case QWEN_LARGE: {
                if (isQwenLargeReady(ctx)) return 100;
                long bytes = getPrefs().getLong(PREF_QWEN_LARGE_BYTES, 0);
                if (bytes <= 0) return 0;
                return (int) Math.min(99, bytes * 100 / QWEN_LARGE_EXPECTED_BYTES);
            }
            default:
                return 0;
        }
    }

    /** True if either model is currently downloading. */
    public boolean isDownloading() {
        return runningSmall.get() || runningLarge.get();
    }

    // ── Trigger methods ───────────────────────────────────────────────

    /**
     * Start Qwen 0.5B download if not already present or in flight.
     * Safe to call multiple times.
     */
    public void ensureQwenSmallDownloaded() {
        if (isQwenSmallReady(ctx)) {
            Log.d(TAG, "Qwen 0.5B already present");
            return;
        }
        if (runningSmall.getAndSet(true)) {
            Log.d(TAG, "Qwen 0.5B download already in progress");
            return;
        }
        notifyState(ModelId.QWEN_SMALL, ModelState.DOWNLOADING);
        exec.submit(() -> downloadWithRetry(ModelId.QWEN_SMALL));
    }

    /**
     * Start Qwen 1.5B download if not already present or in flight.
     * Safe to call multiple times.
     */
    public void ensureQwenLargeDownloaded() {
        if (isQwenLargeReady(ctx)) {
            Log.d(TAG, "Qwen 1.5B already present");
            return;
        }
        if (runningLarge.getAndSet(true)) {
            Log.d(TAG, "Qwen 1.5B download already in progress");
            return;
        }
        notifyState(ModelId.QWEN_LARGE, ModelState.DOWNLOADING);
        exec.submit(() -> downloadWithRetry(ModelId.QWEN_LARGE));
    }

    /** Cancel any in-progress downloads. Partial files are kept for resuming. */
    public void cancel() {
        runningSmall.set(false);
        runningLarge.set(false);
    }

    // ── Download internals ────────────────────────────────────────────

    private void downloadWithRetry(ModelId id) {
        boolean isSmall = id == ModelId.QWEN_SMALL;
        AtomicBoolean running = isSmall ? runningSmall : runningLarge;
        String label = isSmall ? "Qwen 0.5B" : "Qwen 1.5B";
        String url   = isSmall ? QWEN_SMALL_URL   : QWEN_LARGE_URL;
        String fname = isSmall ? QWEN_SMALL_FILENAME : QWEN_LARGE_FILENAME;
        long minBytes = isSmall ? QWEN_SMALL_MIN_BYTES : QWEN_LARGE_MIN_BYTES;
        long estMb   = isSmall ? 519 : 800;
        String prefBytes = isSmall ? PREF_QWEN_SMALL_BYTES : PREF_QWEN_LARGE_BYTES;
        String prefDone  = isSmall ? PREF_QWEN_SMALL_DONE  : PREF_QWEN_LARGE_DONE;

        for (int attempt = 1; attempt <= MAX_RETRIES && running.get(); attempt++) {
            Log.i(TAG, label + " download attempt " + attempt + "/" + MAX_RETRIES);
            try {
                boolean done = downloadFile(id, url, fname, minBytes, estMb, prefBytes, running);
                if (done) {
                    getPrefs().edit().putBoolean(prefDone, true).apply();
                    Log.i(TAG, label + " download complete");
                    notifyState(id, ModelState.READY);
                    notifyProgress(id, 100);
                    speakDeferred(label + " AI model ready. You can now use the AI assistant.");
                    running.set(false);
                    return;
                }
            } catch (Throwable t) {
                Log.w(TAG, label + " attempt " + attempt + " failed: " + t.getMessage());
            }
            if (attempt < MAX_RETRIES && running.get()) {
                try { Thread.sleep(RETRY_DELAY_MS); } catch (InterruptedException ie) { break; }
            }
        }

        Log.w(TAG, label + " download failed after " + MAX_RETRIES + " attempts. Will retry next launch.");
        running.set(false);
        notifyState(id, ModelState.NOT_DOWNLOADED);
    }

    private boolean downloadFile(ModelId id, String urlStr, String filename,
                                  long minBytes, long estMb,
                                  String prefBytes, AtomicBoolean running) throws Exception {
        File dir = new File(ctx.getFilesDir(), MODEL_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "Cannot create model dir: " + dir.getAbsolutePath());
            return false;
        }
        File dest = new File(dir, filename);
        long existingBytes = dest.exists() ? dest.length() : 0L;

        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestProperty("User-Agent", "Auriga/1.0 (Android; VI assistant)");
        conn.setInstanceFollowRedirects(true);
        if (existingBytes > 0) {
            conn.setRequestProperty("Range", "bytes=" + existingBytes + "-");
        }

        int code = conn.getResponseCode();
        boolean resume = (code == 206);
        if (code != 200 && code != 206) {
            Log.w(TAG, filename + " HTTP " + code);
            conn.disconnect();
            return false;
        }

        long contentLength = conn.getContentLengthLong();
        if (contentLength <= 0) contentLength = estMb * 1_000_000L;
        long totalBytes = resume ? contentLength + existingBytes : contentLength;

        long downloaded = existingBytes;
        int lastNotifiedPct = resume ? (int)(existingBytes * 100 / totalBytes) : 0;
        int lastSpokenMilestone = lastNotifiedPct - (lastNotifiedPct % 25);

        if (existingBytes == 0) {
            speakDeferred("Downloading " + (id == ModelId.QWEN_SMALL ? "Qwen small" : "Qwen large")
                    + " AI model. About " + estMb + " megabytes.");
        }

        byte[] buf = new byte[BUFFER_SIZE];
        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(dest, resume)) {

            int n;
            while ((n = in.read(buf)) != -1 && running.get()) {
                out.write(buf, 0, n);
                downloaded += n;

                int pct = (int) Math.min(99, downloaded * 100 / totalBytes);
                if (pct != lastNotifiedPct) {
                    lastNotifiedPct = pct;
                    getPrefs().edit().putLong(prefBytes, downloaded).apply();
                    notifyProgress(id, pct);
                }

                int milestone = (pct / 25) * 25;
                if (milestone > lastSpokenMilestone && milestone < 100) {
                    lastSpokenMilestone = milestone;
                    speakDeferred("AI model download " + milestone + "% complete.");
                }
            }
        } finally {
            conn.disconnect();
        }

        if (!running.get()) {
            Log.i(TAG, filename + " download cancelled at " + downloaded + " bytes");
            return false;
        }

        long finalSize = dest.length();
        if (finalSize >= minBytes) {
            Log.i(TAG, filename + " verified: " + finalSize + " bytes");
            return true;
        }
        Log.w(TAG, filename + " incomplete: " + finalSize + " bytes");
        return false;
    }

    // ── Notification helpers ──────────────────────────────────────────

    private void notifyProgress(ModelId id, int pct) {
        main.post(() -> {
            for (DownloadListener l : listeners) {
                try { l.onProgress(id, pct); } catch (Throwable ignored) {}
            }
        });
    }

    private void notifyState(ModelId id, ModelState state) {
        main.post(() -> {
            for (DownloadListener l : listeners) {
                try { l.onStateChanged(id, state); } catch (Throwable ignored) {}
            }
        });
    }

    private void speakDeferred(String text) {
        main.post(() -> {
            TextToSpeech t = tts;
            if (t != null) {
                t.speak(text, TextToSpeech.QUEUE_ADD, null,
                        "mdm_" + System.currentTimeMillis());
            }
        });
    }

    // ── Utilities ─────────────────────────────────────────────────────

    private SharedPreferences getPrefs() {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** True if the device has any active network connection. */
    public static boolean isOnline(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager)
                ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return nc != null
                && (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
    }
}
