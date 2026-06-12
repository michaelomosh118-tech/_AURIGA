package com.drakosanctis.auriga;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ModelDownloadManager — manifest-driven, multi-mirror model downloader.
 *
 * Download pipeline per model:
 *   1. Fetch manifest.json from GitHub (primary) or HuggingFace (fallback).
 *   2. Iterate mirrors in priority order; attempt each until one succeeds.
 *   3. Resume partial downloads via HTTP Range.
 *   4. After the file reaches minimum size, verify SHA-256 against manifest.
 *   5. If verification fails, delete the file and retry from next mirror.
 *   6. On success, mark READY in SharedPreferences.
 *
 * Mirror priority (defined in manifest.json):
 *   1. GitHub Releases (github.com/michaelomosh118-tech/AurigaModels)
 *   2. Hugging Face litert-community (original source)
 *
 * Models covered:
 *   - Qwen 2.5 0.5B q8  (~519 MB)   qwen2_5_0_5b_q8.bin
 *   - Qwen 2.5 1.5B q8  (~800 MB)   qwen2_5_1_5b_q8.bin
 *
 * Storage:  getFilesDir()/models/<filename>
 */
public class ModelDownloadManager {

    private static final String TAG = "ModelDownloadMgr";

    // ── Model identifiers ─────────────────────────────────────────────

    public enum ModelId { QWEN_SMALL, QWEN_LARGE }
    public enum ModelState { NOT_DOWNLOADED, DOWNLOADING, VERIFYING, READY }

    public interface DownloadListener {
        void onProgress(ModelId model, int percentDone);
        void onStateChanged(ModelId model, ModelState newState);
    }

    // ── Manifest location ─────────────────────────────────────────────

    /**
     * Primary manifest — GitHub Releases raw JSON.
     * This is the canonical record of all mirrors, sizes, and SHA-256 hashes.
     */
    private static final String MANIFEST_URL_PRIMARY =
        "https://github.com/michaelomosh118-tech/AurigaModels/releases/latest/download/manifest.json";

    /** Fallback manifest on HuggingFace (in case GitHub is unreachable). */
    private static final String MANIFEST_URL_FALLBACK =
        "https://huggingface.co/datasets/michaelomosh118-tech/AurigaModels/resolve/main/manifest.json";

    // ── Hard-coded fallback URLs (used if manifest fetch itself fails) ─
    // These are the original Hugging Face direct-download URLs that were
    // present before the manifest system was introduced. They ensure the
    // downloader still works even if both manifest endpoints are down.

    private static final String QWEN_SMALL_FALLBACK_URL =
        "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/"
        + "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.tflite";

    private static final String QWEN_LARGE_FALLBACK_URL =
        "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/"
        + "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.tflite";

    // ── Model constants ───────────────────────────────────────────────

    public static final String MODEL_DIR            = "models";
    public static final String QWEN_SMALL_FILENAME  = "qwen2_5_0_5b_q8.bin";
    public static final String QWEN_LARGE_FILENAME  = "qwen2_5_1_5b_q8.bin";

    private static final long QWEN_SMALL_MIN_BYTES  = 400_000_000L;
    private static final long QWEN_SMALL_EST_BYTES  = 519_000_000L;
    private static final long QWEN_LARGE_MIN_BYTES  = 600_000_000L;
    private static final long QWEN_LARGE_EST_BYTES  = 800_000_000L;

    private static final int MAX_RETRIES       = 3;
    private static final int RETRY_DELAY_MS    = 10_000;
    private static final int CONNECT_TIMEOUT   = 30_000;
    private static final int READ_TIMEOUT      = 60_000;
    private static final int BUFFER_SIZE       = 128 * 1024;

    // ── SharedPreferences keys ────────────────────────────────────────

    private static final String PREFS_NAME               = "auriga_model_prefs";
    private static final String PREF_QWEN_SMALL_DONE     = "qwen_small_download_complete";
    private static final String PREF_QWEN_SMALL_BYTES    = "qwen_small_downloaded_bytes";
    private static final String PREF_QWEN_LARGE_DONE     = "qwen_large_download_complete";
    private static final String PREF_QWEN_LARGE_BYTES    = "qwen_large_downloaded_bytes";
    private static final String PREF_MANIFEST_CACHE      = "model_manifest_json";
    private static final String PREF_MANIFEST_FETCHED_AT = "model_manifest_fetched_at_ms";

    /** Re-fetch manifest at most once every 24 hours. */
    private static final long MANIFEST_TTL_MS = 24 * 60 * 60 * 1_000L;

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

    public void setTts(TextToSpeech tts) { this.tts = tts; }

    // ── Listener management ───────────────────────────────────────────

    public void registerListener(DownloadListener l) {
        if (l != null) listeners.addIfAbsent(l);
    }
    public void unregisterListener(DownloadListener l) {
        listeners.remove(l);
    }

    // ── Public query API ──────────────────────────────────────────────

    public static File qwenSmallFilesPath(Context ctx) {
        return new File(new File(ctx.getFilesDir(), MODEL_DIR), QWEN_SMALL_FILENAME);
    }
    public static File qwenLargeFilesPath(Context ctx) {
        return new File(new File(ctx.getFilesDir(), MODEL_DIR), QWEN_LARGE_FILENAME);
    }

    public static boolean isQwenSmallReady(Context ctx) {
        File f = qwenSmallFilesPath(ctx);
        return f.exists() && f.length() >= QWEN_SMALL_MIN_BYTES;
    }
    public static boolean isQwenLargeReady(Context ctx) {
        File f = qwenLargeFilesPath(ctx);
        return f.exists() && f.length() >= QWEN_LARGE_MIN_BYTES;
    }

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

    public int getProgressPercent(ModelId id) {
        switch (id) {
            case QWEN_SMALL: {
                if (isQwenSmallReady(ctx)) return 100;
                long bytes = getPrefs().getLong(PREF_QWEN_SMALL_BYTES, 0);
                return bytes <= 0 ? 0 : (int) Math.min(99, bytes * 100 / QWEN_SMALL_EST_BYTES);
            }
            case QWEN_LARGE: {
                if (isQwenLargeReady(ctx)) return 100;
                long bytes = getPrefs().getLong(PREF_QWEN_LARGE_BYTES, 0);
                return bytes <= 0 ? 0 : (int) Math.min(99, bytes * 100 / QWEN_LARGE_EST_BYTES);
            }
            default: return 0;
        }
    }

    public boolean isDownloading() {
        return runningSmall.get() || runningLarge.get();
    }

    // ── Trigger methods ───────────────────────────────────────────────

    public void ensureQwenSmallDownloaded() {
        if (isQwenSmallReady(ctx)) { Log.d(TAG, "Qwen 0.5B already present"); return; }
        if (runningSmall.getAndSet(true)) { Log.d(TAG, "Qwen 0.5B already in flight"); return; }
        notifyState(ModelId.QWEN_SMALL, ModelState.DOWNLOADING);
        exec.submit(() -> downloadWithRetry(ModelId.QWEN_SMALL));
    }

    public void ensureQwenLargeDownloaded() {
        if (isQwenLargeReady(ctx)) { Log.d(TAG, "Qwen 1.5B already present"); return; }
        if (runningLarge.getAndSet(true)) { Log.d(TAG, "Qwen 1.5B already in flight"); return; }
        notifyState(ModelId.QWEN_LARGE, ModelState.DOWNLOADING);
        exec.submit(() -> downloadWithRetry(ModelId.QWEN_LARGE));
    }

    public void cancel() {
        runningSmall.set(false);
        runningLarge.set(false);
    }

    public void cancelDownload(ModelId id) {
        if (id == ModelId.QWEN_SMALL) runningSmall.set(false);
        else                          runningLarge.set(false);
    }

    public void deleteModel(ModelId id) {
        boolean small = id == ModelId.QWEN_SMALL;
        File target   = small ? qwenSmallFilesPath(ctx) : qwenLargeFilesPath(ctx);
        if (target.exists()) {
            boolean deleted = target.delete();
            Log.d(TAG, "deleteModel " + id + ": deleted=" + deleted);
        }
        String prefDone  = small ? PREF_QWEN_SMALL_DONE  : PREF_QWEN_LARGE_DONE;
        String prefBytes = small ? PREF_QWEN_SMALL_BYTES  : PREF_QWEN_LARGE_BYTES;
        getPrefs().edit().remove(prefDone).remove(prefBytes).apply();
        notifyState(id, ModelState.NOT_DOWNLOADED);
        notifyProgress(id, 0);
    }

    // ── Manifest ──────────────────────────────────────────────────────

    /**
     * Returns a parsed manifest JSON object.
     * Uses a 24-hour SharedPreferences cache to avoid hammering GitHub on every launch.
     * Falls back to hard-coded HuggingFace URLs if the manifest cannot be fetched.
     */
    private JSONObject fetchManifest() {
        SharedPreferences prefs = getPrefs();
        long lastFetch = prefs.getLong(PREF_MANIFEST_FETCHED_AT, 0);
        String cached  = prefs.getString(PREF_MANIFEST_CACHE, null);

        if (cached != null && (System.currentTimeMillis() - lastFetch) < MANIFEST_TTL_MS) {
            try {
                Log.d(TAG, "Using cached manifest.");
                return new JSONObject(cached);
            } catch (Throwable ignored) {}
        }

        for (String manifestUrl : new String[]{MANIFEST_URL_PRIMARY, MANIFEST_URL_FALLBACK}) {
            try {
                String json = httpGetString(manifestUrl);
                if (json != null && !json.isEmpty()) {
                    JSONObject obj = new JSONObject(json);
                    prefs.edit()
                         .putString(PREF_MANIFEST_CACHE, json)
                         .putLong(PREF_MANIFEST_FETCHED_AT, System.currentTimeMillis())
                         .apply();
                    Log.i(TAG, "Manifest fetched from: " + manifestUrl);
                    return obj;
                }
            } catch (Throwable t) {
                Log.w(TAG, "Manifest fetch failed (" + manifestUrl + "): " + t.getMessage());
            }
        }
        Log.w(TAG, "All manifest sources failed — using hard-coded fallback URLs.");
        return null;
    }

    /**
     * Returns an ordered array of download URLs for the given model,
     * sourced from the manifest. GitHub Releases comes first (manifest priority order).
     * If the manifest is unavailable the hard-coded HuggingFace URLs are used.
     */
    private String[] getMirrorUrls(ModelId id) {
        JSONObject manifest = fetchManifest();
        if (manifest != null) {
            try {
                String key = (id == ModelId.QWEN_SMALL) ? "qwen_small" : "qwen_large";
                JSONObject modelObj = manifest.getJSONObject("models").getJSONObject(key);
                JSONArray mirrors = modelObj.getJSONArray("mirrors");
                String[] urls = new String[mirrors.length()];
                for (int i = 0; i < mirrors.length(); i++) {
                    urls[i] = mirrors.getJSONObject(i).getString("url");
                }
                return urls;
            } catch (Throwable t) {
                Log.w(TAG, "Manifest parse error: " + t.getMessage());
            }
        }
        return (id == ModelId.QWEN_SMALL)
                ? new String[]{QWEN_SMALL_FALLBACK_URL}
                : new String[]{QWEN_LARGE_FALLBACK_URL};
    }

    /**
     * Returns the expected SHA-256 hex string for the given model from the manifest.
     * Returns null if unavailable (verification will be skipped — size check only).
     */
    private String getExpectedSha256(ModelId id) {
        JSONObject manifest = fetchManifest();
        if (manifest == null) return null;
        try {
            String key = (id == ModelId.QWEN_SMALL) ? "qwen_small" : "qwen_large";
            return manifest.getJSONObject("models")
                           .getJSONObject(key)
                           .getString("sha256");
        } catch (Throwable t) {
            return null;
        }
    }

    // ── Download internals ────────────────────────────────────────────

    private void downloadWithRetry(ModelId id) {
        boolean small  = id == ModelId.QWEN_SMALL;
        AtomicBoolean running = small ? runningSmall : runningLarge;
        String  label  = small ? "Qwen 0.5B" : "Qwen 1.5B";
        String  fname  = small ? QWEN_SMALL_FILENAME : QWEN_LARGE_FILENAME;
        long    minBytes = small ? QWEN_SMALL_MIN_BYTES : QWEN_LARGE_MIN_BYTES;
        long    estMb  = small ? 519 : 800;
        String  prefBytes = small ? PREF_QWEN_SMALL_BYTES : PREF_QWEN_LARGE_BYTES;
        String  prefDone  = small ? PREF_QWEN_SMALL_DONE  : PREF_QWEN_LARGE_DONE;

        String[] mirrors = getMirrorUrls(id);
        Log.i(TAG, label + " mirrors available: " + mirrors.length);

        for (int attempt = 1; attempt <= MAX_RETRIES && running.get(); attempt++) {
            // Cycle through mirrors on each retry
            String url = mirrors[(attempt - 1) % mirrors.length];
            Log.i(TAG, label + " attempt " + attempt + "/" + MAX_RETRIES
                    + " from: " + url);
            try {
                boolean done = downloadFile(id, url, fname, minBytes, estMb, prefBytes, running);
                if (done) {
                    File dest = new File(new File(ctx.getFilesDir(), MODEL_DIR), fname);

                    // ── SHA-256 integrity check ───────────────────────
                    String expected = getExpectedSha256(id);
                    if (expected != null && !expected.isEmpty()) {
                        notifyState(id, ModelState.VERIFYING);
                        Log.i(TAG, label + " verifying SHA-256…");
                        String actual = sha256Hex(dest);
                        if (!expected.equalsIgnoreCase(actual)) {
                            Log.e(TAG, label + " SHA-256 MISMATCH!"
                                    + "\n  expected=" + expected
                                    + "\n  actual  =" + actual);
                            speakDeferred(label + " model is corrupted. Re-downloading.");
                            boolean deleted = dest.delete();
                            Log.w(TAG, "Deleted corrupted file: " + deleted);
                            getPrefs().edit().remove(prefBytes).apply();
                            notifyState(id, ModelState.DOWNLOADING);
                            // Don't mark done — fall through to next attempt
                            if (attempt < MAX_RETRIES && running.get()) {
                                try { Thread.sleep(RETRY_DELAY_MS); }
                                catch (InterruptedException ie) { break; }
                            }
                            continue;
                        }
                        Log.i(TAG, label + " SHA-256 OK: " + actual);
                    } else {
                        Log.w(TAG, label + " no SHA-256 in manifest — skipping hash check.");
                    }

                    getPrefs().edit().putBoolean(prefDone, true).apply();
                    Log.i(TAG, label + " download + verify complete.");
                    notifyState(id, ModelState.READY);
                    notifyProgress(id, 100);
                    speakDeferred(label + " AI model ready. You can now use the AI assistant.");
                    running.set(false);
                    return;
                }
            } catch (Throwable t) {
                Log.w(TAG, label + " attempt " + attempt + " threw: " + t.getMessage());
            }

            if (attempt < MAX_RETRIES && running.get()) {
                try { Thread.sleep(RETRY_DELAY_MS); }
                catch (InterruptedException ie) { break; }
            }
        }

        Log.w(TAG, label + " failed after " + MAX_RETRIES + " attempts.");
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
            Log.w(TAG, filename + " HTTP " + code + " from " + urlStr);
            conn.disconnect();
            return false;
        }

        long contentLength = conn.getContentLengthLong();
        if (contentLength <= 0) contentLength = estMb * 1_000_000L;
        long totalBytes  = resume ? contentLength + existingBytes : contentLength;
        long downloaded  = existingBytes;
        int lastPct      = resume ? (int)(existingBytes * 100 / totalBytes) : 0;
        int lastMilestone = lastPct - (lastPct % 25);

        if (existingBytes == 0) {
            speakDeferred("Downloading "
                    + (id == ModelId.QWEN_SMALL ? "Qwen small" : "Qwen large")
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
                if (pct != lastPct) {
                    lastPct = pct;
                    getPrefs().edit().putLong(prefBytes, downloaded).apply();
                    notifyProgress(id, pct);
                }
                int milestone = (pct / 25) * 25;
                if (milestone > lastMilestone && milestone < 100) {
                    lastMilestone = milestone;
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
            Log.i(TAG, filename + " size check OK: " + finalSize + " bytes");
            return true;
        }
        Log.w(TAG, filename + " incomplete: " + finalSize + "/" + minBytes + " bytes");
        return false;
    }

    // ── SHA-256 ───────────────────────────────────────────────────────

    /**
     * Computes the SHA-256 hex digest of a file.
     * Reads in 256 KB chunks to avoid exhausting heap on large model files.
     */
    private static String sha256Hex(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[256 * 1024];
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            int n;
            while ((n = fis.read(buf)) != -1) {
                digest.update(buf, 0, n);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // ── HTTP utility ──────────────────────────────────────────────────

    /** Fetch a small JSON document as a String (manifest, <256 KB). */
    private String httpGetString(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestProperty("User-Agent", "Auriga/1.0 (Android; VI assistant)");
        conn.setInstanceFollowRedirects(true);
        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "httpGetString HTTP " + code + " for " + urlStr);
                return null;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            try (InputStream in = conn.getInputStream()) {
                int n;
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8");
        } finally {
            conn.disconnect();
        }
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
