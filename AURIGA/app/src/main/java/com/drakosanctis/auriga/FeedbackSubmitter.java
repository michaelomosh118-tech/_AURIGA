package com.drakosanctis.auriga;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
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
 * Posts feedback payloads to the configured Netlify Function endpoint.
 *
 * Pipeline (in order):
 *   1. POST the payload as JSON. On 2xx, parse the returned ticket ID
 *      and report success.
 *   2. On a "permanent" failure (4xx that isn't a rate-limit/timeout)
 *      report the failure verbatim — the user gets the actual server
 *      validation message and we DON'T enqueue (it would just fail
 *      again).
 *   3. On a network failure or 5xx, persist the payload to
 *      SharedPreferences and report `queued=true` so the activity can
 *      tell the user "saved offline — will send when you're back".
 *   4. Whenever {@link #flushQueue(Context)} runs (called on app start,
 *      on a successful submit, and after submitting a fresh payload)
 *      we replay any pending payloads in FIFO order, dropping each one
 *      on a successful POST.
 *   5. As a last resort the activity may invoke
 *      {@link #buildMailtoIntent(JSONObject)} to give the user the
 *      escape-hatch mailto: composer (now wired to the project Gmail
 *      via {@link BuildConfig#FEEDBACK_MAILTO}).
 *
 * The queue is intentionally tiny — SharedPreferences holds at most
 * {@link #MAX_QUEUED} pending submissions to avoid runaway disk use if
 * the endpoint is permanently down. Anything beyond that is silently
 * dropped (the user has already been told the submission was queued
 * the first time, and they have the mailto fallback).
 */
public final class FeedbackSubmitter {

    private static final String TAG = "FeedbackSubmitter";
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 12000;

    private static final String QUEUE_PREFS = "auriga_feedback_queue";
    private static final String QUEUE_KEY   = "pending_payloads";
    private static final int    MAX_QUEUED  = 32;

    public static final class Result {
        public final boolean ok;
        public final boolean queued;
        public final boolean usedFallback;
        public final String  ticketId;
        public final String  detail;
        Result(boolean ok, boolean queued, boolean usedFallback,
               String ticketId, String detail) {
            this.ok = ok;
            this.queued = queued;
            this.usedFallback = usedFallback;
            this.ticketId = ticketId;
            this.detail = detail;
        }
    }

    public interface Callback {
        /** Invoked on the main looper. */
        void onResult(Result r);
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public void submit(Context ctx, JSONObject payload, Callback cb) {
        final Context appCtx = ctx.getApplicationContext();
        io.execute(() -> {
            String endpoint = BuildConfig.FEEDBACK_ENDPOINT;
            if (endpoint == null || endpoint.trim().isEmpty()) {
                main.post(() -> cb.onResult(new Result(false, false, false, null,
                        "No feedback endpoint configured.")));
                return;
            }
            try {
                Response r = postJson(endpoint, payload);
                if (r.code >= 200 && r.code < 300) {
                    String ticketId = extractTicketId(r.body);
                    // Opportunistically replay anything that was queued
                    // earlier — we know the network is working right now.
                    flushQueue(appCtx);
                    main.post(() -> cb.onResult(new Result(true, false, false,
                            ticketId,
                            "Submitted — ticket " + (ticketId == null ? "(unassigned)" : ticketId))));
                    return;
                }
                if (isPermanentFailure(r.code)) {
                    String serverMsg = extractError(r.body);
                    main.post(() -> cb.onResult(new Result(false, false, false, null,
                            "Rejected: " + (serverMsg == null ? ("HTTP " + r.code) : serverMsg))));
                    return;
                }
                // 5xx / 408 / 429 — transient, queue and report.
                Log.w(TAG, "Endpoint responded HTTP " + r.code + ", queueing for retry");
                int queued = enqueue(appCtx, payload);
                main.post(() -> cb.onResult(new Result(false, true, false, null,
                        "Saved offline — will send when the server is reachable. (" + queued + " in queue)")));
            } catch (Throwable t) {
                Log.w(TAG, "Endpoint POST failed, queueing for retry", t);
                int queued = enqueue(appCtx, payload);
                main.post(() -> cb.onResult(new Result(false, true, false, null,
                        "Saved offline — will send when you're back online. (" + queued + " in queue)")));
            }
        });
    }

    /**
     * Try to send everything currently sitting in the persistent queue.
     * Safe to call on any thread; runs the network I/O on the IO executor.
     * Should be called at app start (e.g. from the activity that hosts
     * the feedback button) and whenever connectivity changes.
     */
    public void flushQueue(final Context ctx) {
        final Context appCtx = ctx.getApplicationContext();
        io.execute(() -> {
            if (!hasNetwork(appCtx)) return;
            JSONArray pending = loadQueue(appCtx);
            if (pending.length() == 0) return;
            JSONArray remaining = new JSONArray();
            String endpoint = BuildConfig.FEEDBACK_ENDPOINT;
            for (int i = 0; i < pending.length(); i++) {
                JSONObject p = pending.optJSONObject(i);
                if (p == null) continue;
                try {
                    Response r = postJson(endpoint, p);
                    if (r.code >= 200 && r.code < 300) {
                        Log.i(TAG, "Flushed queued payload (HTTP " + r.code + ")");
                        continue; // drop on success
                    }
                    if (isPermanentFailure(r.code)) {
                        Log.w(TAG, "Dropping queued payload, server rejected it: " + r.code);
                        continue; // drop on permanent failure too
                    }
                    remaining.put(p);
                } catch (Throwable t) {
                    // Network died mid-flush — keep this and everything after.
                    remaining.put(p);
                    for (int j = i + 1; j < pending.length(); j++) {
                        JSONObject q = pending.optJSONObject(j);
                        if (q != null) remaining.put(q);
                    }
                    break;
                }
            }
            saveQueue(appCtx, remaining);
        });
    }

    public int pendingCount(Context ctx) {
        return loadQueue(ctx.getApplicationContext()).length();
    }

    // ──────────────────────────────────────────────────────────────────
    // HTTP
    // ──────────────────────────────────────────────────────────────────
    private static class Response {
        final int code;
        final String body;
        Response(int code, String body) { this.code = code; this.body = body; }
    }

    private Response postJson(String endpoint, JSONObject payload) throws Exception {
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
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    code >= 400 ? conn.getErrorStream() : conn.getInputStream(),
                    "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            } catch (Throwable ignored) { /* body is best-effort */ }
            return new Response(code, sb.toString());
        } finally {
            conn.disconnect();
        }
    }

    private static boolean isPermanentFailure(int code) {
        // 4xx is generally a client-side problem we can't fix by retrying,
        // EXCEPT 408 (timeout) and 429 (rate-limit), which we treat as
        // transient.
        return code >= 400 && code < 500 && code != 408 && code != 429;
    }

    private static String extractTicketId(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(body);
            String t = o.optString("ticketId", null);
            return (t == null || t.isEmpty()) ? null : t;
        } catch (Throwable ignored) { return null; }
    }

    private static String extractError(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(body);
            String e = o.optString("error", null);
            return (e == null || e.isEmpty()) ? null : e;
        } catch (Throwable ignored) { return null; }
    }

    private static boolean hasNetwork(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Throwable t) { return false; }
    }

    // ──────────────────────────────────────────────────────────────────
    // Persistent queue (SharedPreferences-backed JSONArray of payloads)
    // ──────────────────────────────────────────────────────────────────
    private synchronized int enqueue(Context ctx, JSONObject payload) {
        JSONArray arr = loadQueue(ctx);
        // Drop oldest if we'd exceed the cap.
        while (arr.length() >= MAX_QUEUED) {
            JSONArray trimmed = new JSONArray();
            for (int i = 1; i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p != null) trimmed.put(p);
            }
            arr = trimmed;
        }
        arr.put(payload);
        saveQueue(ctx, arr);
        return arr.length();
    }

    private synchronized JSONArray loadQueue(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(QUEUE_PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(QUEUE_KEY, "[]");
        try { return new JSONArray(raw); }
        catch (Throwable t) { return new JSONArray(); }
    }

    private synchronized void saveQueue(Context ctx, JSONArray arr) {
        SharedPreferences prefs = ctx.getSharedPreferences(QUEUE_PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(QUEUE_KEY, arr.toString()).apply();
    }

    // ──────────────────────────────────────────────────────────────────
    // Mailto fallback (now defaults to the project Gmail)
    // ──────────────────────────────────────────────────────────────────
    public Intent buildMailtoIntent(JSONObject payload) throws Exception {
        String category = safeStr(payload, "category", "other").toUpperCase();
        String subject = "[AURIGA · " + category + "] "
                + safeStr(payload, "message", "").replaceAll("\\s+", " ");
        if (subject.length() > 100) subject = subject.substring(0, 100) + "…";
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
