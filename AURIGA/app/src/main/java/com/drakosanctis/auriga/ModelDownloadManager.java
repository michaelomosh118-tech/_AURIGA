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
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ModelDownloadManager — silently downloads Qwen 2.5 1.5B to the app's
 * private files directory as a fallback when the CI bundle is unavailable.
 *
 * Normal path:
 *   Both Qwen models are bundled in APK assets by the CI build pipeline
 *   (no auth token required — Apache-2.0 licence). This manager is only
 *   triggered when the large model is absent from assets, providing a
 *   graceful recovery without requiring an APK update.
 *
 * Storage:
 *   {@code getFilesDir()/models/qwen2_5_1_5b_q4.bin}
 *   ~800 MB, stored in the app's private internal storage (no
 *   READ/WRITE_EXTERNAL_STORAGE permission needed on any Android version).
 *
 * MindEngine awareness:
 *   MindEngine.tryCreate() checks both the APK asset path AND this
 *   FilesDir path, so once the download completes the engine loads
 *   Qwen 1.5B automatically on the next voice query.
 *
 * Progress reporting:
 *   Download progress is reported by voice (TTS) in 25% increments
 *   and written to SharedPreferences so the UI can surface it if needed.
 *
 * Retry policy:
 *   Up to 3 attempts, 10-second back-off between retries.
 *   A partially-downloaded file is kept and resumed via byte-range
 *   if the server supports Range requests.
 */
public class ModelDownloadManager {

    private static final String TAG = "ModelDownloadMgr";

    public static final String MODEL_DIR           = "models";
    public static final String QWEN_LARGE_FILENAME = "qwen2_5_1_5b_q4.bin";

    private static final String QWEN_LARGE_URL =
        "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/"
        + "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q4_ekv1280.tflite";

    // Approximate expected size — used for progress calculation and
    // completion verification. Qwen 2.5 1.5B q4 ≈ 800 MB.
    private static final long   QWEN_LARGE_MIN_BYTES = 600_000_000L; // 600 MB floor
    private static final int    MAX_RETRIES          = 3;
    private static final int    RETRY_DELAY_MS       = 10_000;
    private static final int    CONNECT_TIMEOUT_MS   = 30_000;
    private static final int    READ_TIMEOUT_MS      = 60_000;
    private static final int    BUFFER_SIZE          = 128 * 1024; // 128 KB

    private static final String PREFS_NAME               = "auriga_model_prefs";
    private static final String PREF_QWEN_LARGE_DONE     = "qwen_large_download_complete";
    private static final String PREF_QWEN_LARGE_BYTES    = "qwen_large_downloaded_bytes";
    private static final String PREF_QWEN_LARGE_ATTEMPTS = "qwen_large_download_attempts";

    private final Context         ctx;
    private final Handler         main = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final AtomicBoolean   running = new AtomicBoolean(false);

    private volatile TextToSpeech tts; // may be null — always null-checked

    public ModelDownloadManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** Attach a TTS instance so progress can be spoken. Optional. */
    public void setTts(TextToSpeech tts) {
        this.tts = tts;
    }

    // ── Public API ────────────────────────────────────────────────────

    /**
     * Returns the File path where Qwen 1.5B will be / is stored.
     * Used by MindEngine to check for a completed download.
     */
    public static File qwenLargeFilesPath(Context ctx) {
        File dir = new File(ctx.getFilesDir(), MODEL_DIR);
        return new File(dir, QWEN_LARGE_FILENAME);
    }

    /** True if Qwen 1.5B has been fully downloaded and is ready to use. */
    public static boolean isQwenLargeReady(Context ctx) {
        File f = qwenLargeFilesPath(ctx);
        return f.exists() && f.length() >= QWEN_LARGE_MIN_BYTES;
    }

    /**
     * Check if a download is needed and start it if so.
     * Safe to call multiple times — no-ops if already running or complete.
     */
    public void ensureQwenLargeDownloaded() {
        if (isQwenLargeReady(ctx)) {
            Log.d(TAG, "Qwen 1.5B already present — no download needed");
            return;
        }
        if (running.getAndSet(true)) {
            Log.d(TAG, "Qwen 1.5B download already in progress");
            return;
        }
        exec.submit(this::downloadWithRetry);
    }

    /** Cancel any in-progress download. The partial file is kept for resuming. */
    public void cancel() {
        running.set(false);
    }

    // ── Download logic ────────────────────────────────────────────────

    private void downloadWithRetry() {
        int attempts = getPrefs().getInt(PREF_QWEN_LARGE_ATTEMPTS, 0);

        for (int attempt = 1; attempt <= MAX_RETRIES && running.get(); attempt++) {
            setPrefs(PREF_QWEN_LARGE_ATTEMPTS, attempts + attempt);
            Log.i(TAG, "Qwen 1.5B download attempt " + attempt + "/" + MAX_RETRIES);

            try {
                boolean done = downloadQwenLarge();
                if (done) {
                    getPrefs().edit().putBoolean(PREF_QWEN_LARGE_DONE, true).apply();
                    Log.i(TAG, "Qwen 1.5B download complete");
                    speakDeferred("Qwen AI model ready. I can now answer more complex questions.");
                    running.set(false);
                    return;
                }
            } catch (Throwable t) {
                Log.w(TAG, "Qwen 1.5B download attempt " + attempt + " failed: " + t.getMessage());
            }

            if (attempt < MAX_RETRIES && running.get()) {
                try { Thread.sleep(RETRY_DELAY_MS); } catch (InterruptedException ie) { break; }
            }
        }

        Log.w(TAG, "Qwen 1.5B download failed after " + MAX_RETRIES + " attempts. Will retry next launch.");
        running.set(false);
    }

    /**
     * Downloads Qwen 1.5B to {@code getFilesDir()/models/qwen2_5_1_5b_q4.bin}.
     * Supports HTTP Range resume if the file is partially present.
     * @return true if the file is complete and valid.
     */
    private boolean downloadQwenLarge() throws Exception {
        File dir  = new File(ctx.getFilesDir(), MODEL_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "Cannot create model dir: " + dir.getAbsolutePath());
            return false;
        }
        File dest = new File(dir, QWEN_LARGE_FILENAME);
        long existingBytes = dest.exists() ? dest.length() : 0L;

        Log.i(TAG, "Qwen 1.5B: resuming from byte " + existingBytes);

        HttpURLConnection conn = (HttpURLConnection) new URL(QWEN_LARGE_URL).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "Auriga/1.0 (Android; VI assistant)");
        conn.setInstanceFollowRedirects(true);

        if (existingBytes > 0) {
            conn.setRequestProperty("Range", "bytes=" + existingBytes + "-");
        }

        int code = conn.getResponseCode();
        boolean resume = (code == 206);
        if (code != 200 && code != 206) {
            Log.w(TAG, "Qwen 1.5B download server returned HTTP " + code);
            conn.disconnect();
            return false;
        }

        long totalBytes = conn.getContentLengthLong();
        if (totalBytes <= 0) totalBytes = 800_000_000L; // fallback estimate
        long totalWithResume = resume ? totalBytes + existingBytes : totalBytes;

        long downloaded = existingBytes;
        int lastSpokenPct = resume ? (int)(existingBytes * 100 / totalWithResume) : 0;

        if (existingBytes == 0) {
            speakDeferred("Downloading Qwen AI model. This is about 800 megabytes "
                    + "and will take a few minutes. Qwen small is available immediately.");
        }

        byte[] buf = new byte[BUFFER_SIZE];
        try (InputStream  in  = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(dest, resume)) {

            int n;
            while ((n = in.read(buf)) != -1 && running.get()) {
                out.write(buf, 0, n);
                downloaded += n;

                int pct = (int)(downloaded * 100 / totalWithResume);
                saveProgress(downloaded);

                int milestone = (pct / 25) * 25;
                if (milestone > lastSpokenPct && milestone < 100) {
                    lastSpokenPct = milestone;
                    speakDeferred("Qwen download " + milestone + "% complete.");
                }
            }
        } finally {
            conn.disconnect();
        }

        if (!running.get()) {
            Log.i(TAG, "Qwen 1.5B download cancelled at " + downloaded + " bytes");
            return false;
        }

        long finalSize = dest.length();
        if (finalSize >= QWEN_LARGE_MIN_BYTES) {
            Log.i(TAG, "Qwen 1.5B download verified: " + finalSize + " bytes");
            return true;
        } else {
            Log.w(TAG, "Qwen 1.5B file incomplete: " + finalSize + " bytes");
            return false;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private void speakDeferred(String text) {
        main.post(() -> {
            TextToSpeech t = tts;
            if (t != null) {
                t.speak(text, TextToSpeech.QUEUE_ADD, null,
                        "mdm_" + System.currentTimeMillis());
            }
        });
    }

    private SharedPreferences getPrefs() {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void setPrefs(String key, int value) {
        getPrefs().edit().putInt(key, value).apply();
    }

    private void saveProgress(long bytes) {
        getPrefs().edit().putLong(PREF_QWEN_LARGE_BYTES, bytes).apply();
    }

    /** Returns download progress 0–100, or -1 if not started. */
    public int getQwenLargeProgressPercent() {
        if (isQwenLargeReady(ctx)) return 100;
        long bytes = getPrefs().getLong(PREF_QWEN_LARGE_BYTES, -1);
        if (bytes < 0) return -1;
        return (int)(bytes * 100 / 800_000_000L);
    }

    /** True if a download is currently in progress. */
    public boolean isDownloading() { return running.get(); }

    /** Check network availability. */
    public static boolean isOnline(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager)
                ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return nc != null && (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
    }
}
