package com.drakosanctis.auriga;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Posts feedback payloads to the configured Netlify Function endpoint
 * (D1 = C). Falls back to launching the system mail composer
 * (D1-fallback = mailto:) so a submission still succeeds when the device
 * is offline or the function is unreachable.
 *
 * <p>Endpoint URL is read from {@link BuildConfig#FEEDBACK_ENDPOINT} so the
 * production hostname can be set per-environment without recompiling Java.
 * Fallback recipient is read from {@link BuildConfig#FEEDBACK_MAILTO}.
 */
public final class FeedbackSubmitter {

    private static final String TAG = "FeedbackSubmitter";
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 12000;

    public interface Callback {
        /** Invoked on the main looper. */
        void onResult(boolean ok, String detail, boolean usedFallback);
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    public void submit(Context ctx, JSONObject payload, Callback cb) {
        io.execute(() -> {
            String endpoint = BuildConfig.FEEDBACK_ENDPOINT;
            if (endpoint != null && !endpoint.trim().isEmpty()) {
                try {
                    int code = postJson(endpoint, payload);
                    if (code >= 200 && code < 300) {
                        main.post(() -> cb.onResult(true,
                                "Submitted (HTTP " + code + ")", false));
                        return;
                    }
                    Log.w(TAG, "Endpoint responded HTTP " + code + ", falling back to mailto");
                } catch (Throwable t) {
                    Log.w(TAG, "Endpoint POST failed, falling back to mailto", t);
                }
            }
            // ── Fallback path ────────────────────────────────────────
            try {
                Intent mail = buildMailtoIntent(payload);
                mail.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(mail);
                main.post(() -> cb.onResult(true,
                        "Endpoint unreachable — opened your email app instead.", true));
            } catch (Throwable t) {
                Log.e(TAG, "mailto fallback failed", t);
                main.post(() -> cb.onResult(false,
                        "Submission failed: " + t.getMessage(), true));
            }
        });
    }

    private int postJson(String endpoint, JSONObject payload) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent",
                    "AurigaNavi/" + BuildConfig.AURIGA_PRODUCT + " Android");
            byte[] body = payload.toString().getBytes("UTF-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            int code = conn.getResponseCode();
            // Drain the body so the connection can be pooled.
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    code >= 400 ? conn.getErrorStream() : conn.getInputStream(),
                    "UTF-8"))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                Log.d(TAG, "Submit response: " + sb);
            } catch (Throwable ignored) { /* body is best-effort */ }
            return code;
        } finally {
            conn.disconnect();
        }
    }

    private Intent buildMailtoIntent(JSONObject payload) throws Exception {
        String subject = "AurigaNavi feedback — "
                + safeStr(payload, "category", "other");
        StringBuilder body = new StringBuilder();
        body.append(safeStr(payload, "message", "")).append("\n\n");
        body.append("— Auto-attached metadata —\n");
        body.append("Product: ").append(safeStr(payload, "product", "")).append('\n');
        body.append("Version: ").append(safeStr(payload, "version", "")).append('\n');
        body.append("Device: ").append(safeStr(payload, "device", "")).append('\n');
        body.append("Profile: ").append(safeStr(payload, "profile", "")).append('\n');
        body.append("Diagnostic snapshot:\n");
        body.append(safeStr(payload, "diagnostic", "(none)"));
        Uri uri = Uri.parse("mailto:" + BuildConfig.FEEDBACK_MAILTO
                + "?subject=" + URLEncoder.encode(subject, "UTF-8")
                + "&body=" + URLEncoder.encode(body.toString(), "UTF-8"));
        return new Intent(Intent.ACTION_SENDTO, uri);
    }

    private static String safeStr(JSONObject o, String key, String fallback) {
        try { return o.optString(key, fallback); } catch (Throwable t) { return fallback; }
    }
}
