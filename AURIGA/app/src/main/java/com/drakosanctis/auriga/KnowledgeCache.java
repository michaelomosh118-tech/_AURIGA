package com.drakosanctis.auriga;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * KnowledgeCache — local SQLite store that keeps current-world data fresh.
 *
 * Three data feeds, each with its own TTL:
 *
 *   WEATHER  — Open-Meteo (free, no API key). TTL 30 min.
 *              Speaks: "Weather: partly cloudy. 22°C (72°F). Wind 14 km/h."
 *
 *   NEWS     — Google News RSS top 5 headlines. TTL 1 hour.
 *              Speaks: "Today's top headlines: ..."
 *
 *   WIKI     — Wikipedia REST summary per topic. TTL 7 days.
 *              On-demand; fetched asynchronously when a matching query arrives.
 *
 * MindEngine calls {@link #getContext(String)} to get a compact grounding
 * string prepended to the LLM prompt — turning "What's the weather?" into
 * a factual answer rather than a hallucination.
 *
 * All network I/O runs on a single background thread and never blocks the
 * main or YOLO inference threads.
 */
public class KnowledgeCache {

    private static final String TAG = "KnowledgeCache";

    private static final long TTL_WEATHER_MS = 30L * 60 * 1000;
    private static final long TTL_NEWS_MS    = 60L * 60 * 1000;
    // Wiki TTL only matters for forced re-fetch; on-demand fetch fills cache first time.
    private static final long TTL_WIKI_MS    = 7L * 24 * 3600 * 1000;

    private static final String WEATHER_URL =
            "https://api.open-meteo.com/v1/forecast"
          + "?current=temperature_2m,weathercode,windspeed_10m"
          + "&latitude=%s&longitude=%s";

    private static final String WIKI_URL =
            "https://en.wikipedia.org/api/rest_v1/page/summary/%s";

    private static final String NEWS_RSS =
            "https://news.google.com/rss?hl=en-US&gl=US&ceid=US:en";

    // WMO weather code → spoken phrase (common subset)
    private static final int[]    WMO_CODES   = {  0,  1,  2,  3, 45, 51, 53, 55, 61, 63, 65, 71, 73, 75, 80, 81, 82, 95 };
    private static final String[] WMO_PHRASES = {
        "clear sky","mainly clear","partly cloudy","overcast",
        "fog","light drizzle","moderate drizzle","heavy drizzle",
        "slight rain","moderate rain","heavy rain",
        "slight snow","moderate snow","heavy snow",
        "slight showers","moderate showers","heavy showers","thunderstorm"
    };

    private final DBHelper        db;
    private final Context         ctx;
    private final ExecutorService net = Executors.newSingleThreadExecutor();

    // GPS coords — updated by HardwareHAL / AurigaSkillEngine whenever location changes.
    private volatile double lastLat = 0.0;
    private volatile double lastLon = 0.0;

    public KnowledgeCache(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.db  = new DBHelper(this.ctx);
    }

    /** Update the stored GPS coordinates used for weather queries. */
    public void updateLocation(double lat, double lon) {
        this.lastLat = lat;
        this.lastLon = lon;
    }

    // ── Public API ──────────────────────────────────────────────────

    /**
     * Returns a compact grounding string for the given query. Never blocks —
     * returns whatever is currently cached and triggers a background refresh
     * if the data is stale.
     */
    public String getContext(String query) {
        if (query == null) return "";
        String q = query.toLowerCase(Locale.US);
        StringBuilder sb = new StringBuilder();

        // Weather
        if (containsAny(q, "weather","temperature","rain","hot","cold",
                            "warm","forecast","wind","sunny","cloudy","degrees")) {
            String w = getCached("weather", "current");
            if (w != null) sb.append(w).append(" ");
            maybeRefreshWeather();
        }

        // News
        if (containsAny(q, "news","headlines","happening","latest",
                            "current events","what's going on","top stories")) {
            String n = getCached("news", "top");
            if (n != null) sb.append(n).append(" ");
            maybeRefreshNews();
        }

        // Wikipedia — on-demand per topic
        String topic = extractWikiTopic(q);
        if (topic != null) {
            String w = getCached("wiki", topic);
            if (w != null) sb.append(w).append(" ");
            else fetchWikiAsync(topic);   // cache miss — fetch now for next ask
        }

        return sb.toString().trim();
    }

    /**
     * Eagerly warm up weather and news caches on app start.
     * No-op if data is fresh or device is offline.
     */
    public void warmUp() {
        maybeRefreshWeather();
        maybeRefreshNews();
    }

    // ── Refresh triggers ────────────────────────────────────────────

    private void maybeRefreshWeather() {
        if (!isOnline()) return;
        if (cacheAge("weather", "current") > TTL_WEATHER_MS) net.submit(this::fetchWeather);
    }

    private void maybeRefreshNews() {
        if (!isOnline()) return;
        if (cacheAge("news", "top") > TTL_NEWS_MS) net.submit(this::fetchNews);
    }

    private void fetchWikiAsync(String topic) {
        if (!isOnline()) return;
        net.submit(() -> fetchWiki(topic));
    }

    // ── Weather ─────────────────────────────────────────────────────

    private void fetchWeather() {
        try {
            if (lastLat == 0.0 && lastLon == 0.0) { Log.d(TAG, "Weather: no GPS fix yet"); return; }
            String raw = httpGet(String.format(Locale.US, WEATHER_URL, lastLat, lastLon), 6000);
            if (raw == null) return;
            JSONObject root    = new JSONObject(raw);
            JSONObject current = root.getJSONObject("current");
            double tempC = current.getDouble("temperature_2m");
            int    code  = current.getInt("weathercode");
            double wind  = current.getDouble("windspeed_10m");
            double tempF = tempC * 9.0 / 5.0 + 32.0;
            String summary = String.format(Locale.US,
                    "Weather: %s. %.0f degrees Celsius, %.0f Fahrenheit. Wind %.0f kilometres per hour.",
                    wmoPhrase(code), tempC, tempF, wind);
            putCache("weather", "current", summary);
            Log.d(TAG, "Weather cached: " + summary);
        } catch (Throwable t) { Log.w(TAG, "fetchWeather: " + t.getMessage()); }
    }

    private String wmoPhrase(int code) {
        for (int i = 0; i < WMO_CODES.length; i++) if (WMO_CODES[i] == code) return WMO_PHRASES[i];
        return "mixed conditions";
    }

    // ── News ────────────────────────────────────────────────────────

    private void fetchNews() {
        try {
            String raw = httpGet(NEWS_RSS, 8000);
            if (raw == null) return;
            List<String> headlines = new ArrayList<>();
            int idx = 0;
            while (headlines.size() < 5) {
                int start = raw.indexOf("<title>", idx);
                if (start < 0) break;
                int end = raw.indexOf("</title>", start);
                if (end < 0) break;
                String t = raw.substring(start + 7, end)
                        .replace("<![CDATA[", "").replace("]]>", "")
                        .replace("&amp;", "&").replace("&quot;", "\"").trim();
                if (!t.isEmpty() && !t.equalsIgnoreCase("Google News")) headlines.add(t);
                idx = end + 8;
            }
            if (headlines.isEmpty()) return;
            StringBuilder sb = new StringBuilder("Today's top headlines: ");
            for (int i = 0; i < headlines.size(); i++) {
                if (i > 0) sb.append(". ");
                sb.append(headlines.get(i));
            }
            sb.append(".");
            putCache("news", "top", sb.toString());
            Log.d(TAG, "News cached: " + headlines.size() + " headlines");
        } catch (Throwable t) { Log.w(TAG, "fetchNews: " + t.getMessage()); }
    }

    // ── Wikipedia ───────────────────────────────────────────────────

    private void fetchWiki(String topic) {
        try {
            String encoded = URLEncoder.encode(topic, "UTF-8").replace("+", "_");
            String raw = httpGet(String.format(WIKI_URL, encoded), 8000);
            if (raw == null) return;
            JSONObject obj = new JSONObject(raw);
            if (!obj.has("extract")) return;
            String extract = obj.getString("extract");
            // Truncate to ~500 chars — enough context, small enough for prompt window
            if (extract.length() > 500) {
                int cut = extract.lastIndexOf(". ", 500);
                extract = cut > 50 ? extract.substring(0, cut + 1) : extract.substring(0, 500);
            }
            putCache("wiki", topic, extract);
            Log.d(TAG, "Wiki cached: '" + topic + "' (" + extract.length() + " chars)");
        } catch (Throwable t) { Log.w(TAG, "fetchWiki '" + topic + "': " + t.getMessage()); }
    }

    // ── Topic extraction ────────────────────────────────────────────

    /**
     * Heuristically extracts a Wikipedia-searchable topic from the user query.
     * Returns null for conversational queries that are better handled by the LLM.
     */
    private static String extractWikiTopic(String q) {
        String[] prefixes = {
            "what is ","what are ","who is ","who are ","who was ","who were ",
            "tell me about ","explain ","define ","describe ","how does ","how do "
        };
        for (String p : prefixes) {
            if (q.startsWith(p)) {
                String topic = q.substring(p.length()).replaceAll("[?!.]$", "").trim();
                // 1–5 words only — longer phrases are usually conversational
                if (topic.split("\\s+").length <= 5 && topic.length() > 2) return topic;
            }
        }
        return null;
    }

    // ── SQLite helpers ──────────────────────────────────────────────

    private void putCache(String category, String key, String value) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("category", category);
            cv.put("key",      key);
            cv.put("value",    value);
            cv.put("ts",       System.currentTimeMillis());
            db.getWritableDatabase().insertWithOnConflict(
                    "knowledge_cache", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Throwable t) { Log.w(TAG, "putCache: " + t.getMessage()); }
    }

    private String getCached(String category, String key) {
        try (Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT value FROM knowledge_cache WHERE category=? AND key=?",
                new String[]{category, key})) {
            if (c.moveToFirst()) return c.getString(0);
        } catch (Throwable ignored) {}
        return null;
    }

    /** Returns age in ms, or Long.MAX_VALUE if the entry is not in the cache. */
    private long cacheAge(String category, String key) {
        try (Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT ts FROM knowledge_cache WHERE category=? AND key=?",
                new String[]{category, key})) {
            if (c.moveToFirst()) return System.currentTimeMillis() - c.getLong(0);
        } catch (Throwable ignored) {}
        return Long.MAX_VALUE;
    }

    // ── Network ─────────────────────────────────────────────────────

    private String httpGet(String urlStr, int timeoutMs) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("User-Agent", "Auriga/1.0 (Android; VI assistant)");
            if (conn.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) { Log.d(TAG, "httpGet: " + t.getMessage()); return null; }
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager)
                ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc != null && (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        }
        return false;
    }

    private static boolean containsAny(String text, String... tokens) {
        for (String t : tokens) if (text.contains(t)) return true;
        return false;
    }

    // ── SQLite schema ────────────────────────────────────────────────

    private static class DBHelper extends SQLiteOpenHelper {
        DBHelper(Context ctx) { super(ctx, "auriga_knowledge.db", null, 2); }

        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS knowledge_cache ("
              + "  category TEXT NOT NULL,"
              + "  key      TEXT NOT NULL,"
              + "  value    TEXT NOT NULL,"
              + "  ts       INTEGER NOT NULL,"
              + "  PRIMARY KEY (category, key))");
        }

        @Override public void onUpgrade(SQLiteDatabase db, int o, int n) {
            db.execSQL("DROP TABLE IF EXISTS knowledge_cache");
            onCreate(db);
        }
    }
}
