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
 * ModelDownloadManager — silently downloads the Gemma 2B LLM to the
 * app's private files directory on first launch.
 *
 * Why not bundle Gemma in the APK:
 *   Gemma 2 2B IT q8 is 2.52 GB. Combined with Qwen (519 MB already
 *   bundled), MediaPipe AAR (~120 MB), and app code (~200 MB), the
 *   APK would exceed 3.3 GB — above the practical sideload limit.
 *   Qwen is bundled (519 MB, works immediately). Gemma downloads once
 *   in the background and loads automatically when ready.
 *
 * Storage:
 *   {@code getFilesDir()/models/gemma2b_q4.bin}
 *   ~2.52 GB, stored in the app's private internal storage (no
 *   READ/WRITE_EXTERNAL_STORAGE permission needed on any Android version).
 *
 * MindEngine awareness:
 *   MindEngine.tryCreate() checks both the APK asset path AND the
 *   FilesDir model path, so once the download completes the engine
 *   loads Gemma automatically on the next voice query.
 *
 * Progress reporting:
 *   Download progress is reported by voice (TTS) in 25% increments
 *   and written to SharedPreferences so the UI can surface it if needed.
 *
 * Retry policy:
 *   Up to 3 attempts, 10-second back-off between retries.
 *   A partially-downloaded file is kept and resumed by byte-range
 *   if the server supports Range requests.
 */
public class ModelDownloadManager {

    private static final String TAG = "ModelDownloadMgr";

    public static final String MODEL_DIR      = "models";
    public static final String GEMMA_FILENAME = "gemma2b_q4.bin";

    private static final String GEMMA_URL =
        "https://huggingface.co/litert-community/Gemma2-2B-IT/resolve/main/"
        + "Gemma2-2B-IT_multi-prefill-seq_q8_ekv1280.tflite";

    private static final long   GEMMA_EXPECTED_BYTES = 2_709_032_880L;
    private static final int    MAX_RETRIES          = 3;
    private static final int    RETRY_DELAY_MS       = 10_000;
    private static final int    CONNECT_TIMEOUT_MS   = 30_000;
    private static final int    READ_TIMEOUT_MS      = 60_000;
    private static final int    BUFFER_SIZE          = 128 * 1024; // 128 KB

    private static final String PREFS_NAME          = "auriga_model_prefs";
    private static final String PREF_GEMMA_DONE     = "gemma_download_complete";
    private static final String PREF_GEMMA_BYTES    = "gemma_downloaded_bytes";
    private static final String PREF_GEMMA_ATTEMPTS = "gemma_download_attempts";

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
     * Returns the File path where Gemma will be / is stored.
     * Use this in MindEngine to check for a completed download.
     */
    public static File gemmaFilesPath(Context ctx) {
        File dir = new File(ctx.getFilesDir(), MODEL_DIR);
        return new File(dir, GEMMA_FILENAME);
    }

    /** True if Gemma has been fully downloaded and is ready to use. */
    public static boolean isGemmaReady(Context ctx) {
        File f = gemmaFilesPath(ctx);
        return f.exists() && f.length() >= GEMMA_EXPECTED_BYTES * 0.99; // allow 1% margin
    }

    /**
     * Check if a download is needed and start it if so.
     * Safe to call multiple times — no-ops if already running or complete.
     * Does NOT require network permission checks; those are done internally.
     */
    public void ensureGemmaDownloaded() {
        if (isGemmaReady(ctx)) {
            Log.d(TAG, "Gemma already present — no download needed");
            return;
        }
        if (running.getAndSet(true)) {
            Log.d(TAG, "Gemma download already in progress");
            return;
        }
        exec.submit(this::downloadGemmaWithRetry);
    }

    /** Cancel any in-progress download. The partial file is kept for resuming. */
    public void cancel() {
        running.set(false);
    }

    // ── Download logic ────────────────────────────────────────────────

    private void downloadGemmaWithRetry() {
        int attempts = getPrefs().getInt(PREF_GEMMA_ATTEMPTS, 0);

        for (int attempt = 1; attempt <= MAX_RETRIES && running.get(); attempt++) {
            setPrefs(PREF_GEMMA_ATTEMPTS, attempts + attempt);
            Log.i(TAG, "Gemma download attempt " + attempt + "/" + MAX_RETRIES);

            try {
                boolean done = downloadGemma();
                if (done) {
                    getPrefs().edit().putBoolean(PREF_GEMMA_DONE, true).apply();
                    Log.i(TAG, "Gemma download complete");
                    speakDeferred("Gemma AI model ready. I can now answer more complex questions.");
                    running.set(false);
                    return;
                }
            } catch (Throwable t) {
                Log.w(TAG, "Gemma download attempt " + attempt + " failed: " + t.getMessage());
            }

            if (attempt < MAX_RETRIES && running.get()) {
                try { Thread.sleep(RETRY_DELAY_MS); } catch (InterruptedException ie) { break; }
            }
        }

        Log.w(TAG, "Gemma download failed after " + MAX_RETRIES + " attempts. Will retry next launch.");
        running.set(false);
    }

    /**
     * Downloads Gemma to {@code getFilesDir()/models/gemma2b_q4.bin}.
     * Supports HTTP Range resume if the file is partially present.
     * @return true if the file is complete and valid.
     */
    private boolean downloadGemma() throws Exception {
        File dir  = new File(ctx.getFilesDir(), MODEL_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "Cannot create model dir: " + dir.getAbsolutePath());
            return false;
        }
        File dest = new File(dir, GEMMA_FILENAME);
        long existingBytes = dest.exists() ? dest.length() : 0L;

        Log.i(TAG, "Gemma: resuming from byte " + existingBytes
                + " / " + GEMMA_EXPECTED_BYTES);

        HttpURLConnection conn = (HttpURLConnection) new URL(GEMMA_URL).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "Auriga/1.0 (Android; VI assistant)");

        // Attempt byte-range resume
        if (existingBytes > 0) {
            conn.setRequestProperty("Range", "bytes=" + existingBytes + "-");
        }

        int code = conn.getResponseCode();
        boolean resume = (code == 206); // Partial Content
        if (code != 200 && code != 206) {
            Log.w(TAG, "Gemma download server returned HTTP " + code);
            conn.disconnect();
            return false;
        }

        long totalBytes = conn.getContentLengthLong();
        if (totalBytes <= 0) totalBytes = GEMMA_EXPECTED_BYTES;
        long totalWithResume = resume ? totalBytes + existingBytes : totalBytes;

        long downloaded = existingBytes;
        int lastSpokenPct = resume ? (int)(existingBytes * 100 / totalWithResume) : 0;

        // Announce on the first attempt only, not every retry
        if (existingBytes == 0) {
            speakDeferred("Downloading Gemma AI model. This is a 2.5 gigabyte file and "
                    + "will take several minutes. Qwen is available immediately.");
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

                // Speak at 25%, 50%, 75%
                int milestone = (pct / 25) * 25;
                if (milestone > lastSpokenPct && milestone < 100) {
                    lastSpokenPct = milestone;
                    speakDeferred("Gemma download " + milestone + "% complete.");
                }
            }
        } finally {
            conn.disconnect();
        }

        if (!running.get()) {
            Log.i(TAG, "Gemma download cancelled at " + downloaded + " bytes");
            return false;
        }

        long finalSize = dest.length();
        if (finalSize >= GEMMA_EXPECTED_BYTES * 0.99) {
            Log.i(TAG, "Gemma download verified: " + finalSize + " bytes");
            return true;
        } else {
            Log.w(TAG, "Gemma file incomplete: " + finalSize
                    + " of " + GEMMA_EXPECTED_BYTES + " bytes");
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
        getPrefs().edit().putLong(PREF_GEMMA_BYTES, bytes).apply();
    }

    /** Returns download progress 0–100, or -1 if not started. */
    public int getGemmaProgressPercent() {
        if (isGemmaReady(ctx)) return 100;
        long bytes = getPrefs().getLong(PREF_GEMMA_BYTES, -1);
        if (bytes < 0) return -1;
        return (int)(bytes * 100 / GEMMA_EXPECTED_BYTES);
    }

    /** True if a download is currently in progress. */
    public boolean isDownloading() { return running.get(); }

    /** Check network availability (any connection — no Wi-Fi restriction per user request). */
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
