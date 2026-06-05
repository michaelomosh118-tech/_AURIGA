package com.drakosanctis.auriga;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AurigaMemoryStore — on-device conversation history + user profile.
 *
 * Uses SQLite (no network, no API key, fully offline). Runs all DB
 * operations on a background thread so callers never block the UI.
 *
 * Two tables:
 *   conversations  — every Q&A turn (role, text, page, ts, quality)
 *   profile        — user facts extracted from natural speech
 *
 * Quality signals (-1 = wrong, 0 = neutral, 1 = good) are used to
 * rank context retrieval so the most helpful past exchanges bubble up.
 *
 * Usage:
 *   AurigaMemoryStore.store(ctx, "user", userText);
 *   AurigaMemoryStore.store(ctx, "assistant", replyText);
 *   AurigaMemoryStore.getContext(ctx, query, 5, ctx -> speak(ctx));
 *   AurigaMemoryStore.getProfileContext(ctx, p -> speak(p));
 */
public final class AurigaMemoryStore {

    private static final String DB_NAME      = "auriga_memory";
    private static final int    DB_VERSION   = 1;
    private static final int    MAX_ROWS     = 2000;

    private static final String T_CONV = "conversations";
    private static final String T_PROF = "profile";

    private static final ExecutorService BG = Executors.newSingleThreadExecutor();

    /* ── DB helper ─────────────────────────────────────────────── */

    private static class MemoryDB extends SQLiteOpenHelper {
        MemoryDB(Context ctx) { super(ctx.getApplicationContext(), DB_NAME, null, DB_VERSION); }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + T_CONV + " (" +
                "id      INTEGER PRIMARY KEY AUTOINCREMENT," +
                "role    TEXT NOT NULL," +
                "text    TEXT NOT NULL," +
                "page    TEXT," +
                "ts      INTEGER NOT NULL," +
                "quality INTEGER DEFAULT 0" +
            ")");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_conv_ts ON " + T_CONV + "(ts)");
            db.execSQL("CREATE TABLE IF NOT EXISTS " + T_PROF + " (" +
                "key        TEXT PRIMARY KEY," +
                "value      TEXT NOT NULL," +
                "updated_at INTEGER NOT NULL" +
            ")");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
            db.execSQL("DROP TABLE IF EXISTS " + T_CONV);
            db.execSQL("DROP TABLE IF EXISTS " + T_PROF);
            onCreate(db);
        }
    }

    private static volatile MemoryDB instance;

    private static MemoryDB db(Context ctx) {
        if (instance == null) {
            synchronized (AurigaMemoryStore.class) {
                if (instance == null) instance = new MemoryDB(ctx);
            }
        }
        return instance;
    }

    /* ── Store a conversation turn ─────────────────────────────── */

    public interface Callback<T> { void onResult(T result); }

    /** Store one turn asynchronously. */
    public static void store(Context ctx, String role, String text) {
        store(ctx, role, text, null);
    }

    public static void store(Context ctx, String role, String text, String page) {
        if (text == null || text.trim().isEmpty()) return;
        final String safeText = text.trim().length() > 400
            ? text.trim().substring(0, 400) : text.trim();
        final String safePage = page != null ? page : "app";
        BG.execute(() -> {
            try {
                SQLiteDatabase d = db(ctx).getWritableDatabase();
                ContentValues cv = new ContentValues();
                cv.put("role",    role);
                cv.put("text",    safeText);
                cv.put("page",    safePage);
                cv.put("ts",      System.currentTimeMillis());
                cv.put("quality", 0);
                d.insertOrThrow(T_CONV, null, cv);
                enforceMaxRows(d);
            } catch (Exception ignored) {}
        });
    }

    private static void enforceMaxRows(SQLiteDatabase d) {
        try {
            Cursor c = d.rawQuery("SELECT COUNT(*) FROM " + T_CONV, null);
            if (c.moveToFirst() && c.getInt(0) > MAX_ROWS) {
                d.execSQL(
                    "DELETE FROM " + T_CONV +
                    " WHERE id IN (SELECT id FROM " + T_CONV +
                    " ORDER BY ts ASC LIMIT 100)");
            }
            c.close();
        } catch (Exception ignored) {}
    }

    /* ── Mark the last assistant turn with a quality signal ─────── */

    public static void markLastQuality(Context ctx, int signal) {
        BG.execute(() -> {
            try {
                SQLiteDatabase d = db(ctx).getWritableDatabase();
                Cursor c = d.rawQuery(
                    "SELECT id FROM " + T_CONV +
                    " WHERE role='assistant' ORDER BY ts DESC LIMIT 1", null);
                if (c.moveToFirst()) {
                    int id = c.getInt(0);
                    ContentValues cv = new ContentValues();
                    cv.put("quality", signal);
                    d.update(T_CONV, cv, "id=?", new String[]{String.valueOf(id)});
                }
                c.close();
            } catch (Exception ignored) {}
        });
    }

    /* ── Retrieve context as a formatted string for LLM prompt ─── */

    /**
     * Returns up to {@code n} relevant past turns formatted as:
     *   User: what is a cup?
     *   Auriga: A cup is a small open container…
     */
    public static void getContext(Context ctx, String query, int n, Callback<String> cb) {
        BG.execute(() -> {
            try {
                SQLiteDatabase d = db(ctx).getReadableDatabase();
                /* Fetch recent turns; scoring by keyword overlap happens in-memory */
                Cursor c = d.rawQuery(
                    "SELECT role, text, quality FROM " + T_CONV +
                    " ORDER BY ts DESC LIMIT 80", null);

                List<String[]> rows = new ArrayList<>();
                while (c.moveToNext()) {
                    rows.add(new String[]{
                        c.getString(0), c.getString(1), c.getString(2)
                    });
                }
                c.close();

                /* Score by keyword overlap + quality */
                String[] queryWords = query != null
                    ? query.toLowerCase(Locale.US).split("\\s+") : new String[0];

                rows.sort((a, b) -> {
                    int sa = scoreRow(a[1], queryWords, a[2]);
                    int sb = scoreRow(b[1], queryWords, b[2]);
                    return Integer.compare(sb, sa);
                });

                /* Take top-n and re-sort by insertion order (we lost ts, use reverse of desc) */
                List<String[]> top = rows.subList(0, Math.min(n, rows.size()));

                StringBuilder sb2 = new StringBuilder();
                for (String[] row : top) {
                    sb2.append("assistant".equals(row[0]) ? "Auriga" : "User")
                       .append(": ").append(row[1]).append('\n');
                }
                cb.onResult(sb2.toString().trim());
            } catch (Exception e) {
                cb.onResult("");
            }
        });
    }

    private static int scoreRow(String text, String[] queryWords, String qualityStr) {
        int score = 0;
        String lower = text.toLowerCase(Locale.US);
        for (String w : queryWords) {
            if (w.length() > 3 && lower.contains(w)) score += 2;
        }
        try { score += Integer.parseInt(qualityStr); } catch (Exception ignored) {}
        return score;
    }

    /* ── Profile extraction ────────────────────────────────────── */

    private static final Object[][] PROFILE_RULES = {
        { "my name is ([a-z][a-z\\s]{1,30})",                    "name",       true },
        { "(?:i live in|i'm from|i am from) ([a-z][a-z\\s]{1,40})", "location", true },
        { "i am (\\d{1,3}) years? old",                          "age",        true },
        { "i work(?:ed)? as (?:a |an )?([a-z][a-z\\s]{1,30})",  "occupation", true },
        { "i have (?:a )?guide dog(?: named ([a-z]+))?",         "guide_dog",  false },
        { "my (?:guide )?dog(?:'s name)? is ([a-z]+)",           "dog_name",   true },
        { "i have (?:a )?white cane",                            "uses_cane",  false },
        { "i prefer (?:short|brief|quick) answers?",             "pref_length", false },
        { "i prefer (?:detailed|long|full) answers?",            "pref_length_long", false },
        { "call me ([a-z][a-z]{1,20})",                          "name",       true },
        { "my (?:phone|device) is (?:a |an )?([a-z][a-z\\s\\d]{1,30})", "device", true }
    };

    public static void extractAndSaveProfile(Context ctx, String text) {
        if (text == null || text.trim().isEmpty()) return;
        final String lower = text.toLowerCase(Locale.US);
        BG.execute(() -> {
            try {
                SQLiteDatabase d = db(ctx).getWritableDatabase();
                for (Object[] rule : PROFILE_RULES) {
                    String regex = (String) rule[0];
                    String key   = (String) rule[1];
                    boolean extract = (Boolean) rule[2];
                    try {
                        Pattern p = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
                        Matcher m = p.matcher(lower);
                        if (m.find()) {
                            String val;
                            if (extract) {
                                val = m.groupCount() >= 1 && m.group(1) != null
                                    ? m.group(1).trim() : null;
                            } else {
                                val = key.contains("pref_length_long") ? "detailed" :
                                      key.contains("pref_length") ? "short" : "true";
                                key = key.replace("_long", "");
                            }
                            if (val != null && !val.isEmpty()) {
                                ContentValues cv = new ContentValues();
                                cv.put("key",        key);
                                cv.put("value",      val);
                                cv.put("updated_at", System.currentTimeMillis());
                                d.insertWithOnConflict(T_PROF, null, cv,
                                    SQLiteDatabase.CONFLICT_REPLACE);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        });
    }

    /* ── Build profile context string ─────────────────────────── */

    public static void getProfileContext(Context ctx, Callback<String> cb) {
        BG.execute(() -> {
            try {
                SQLiteDatabase d = db(ctx).getReadableDatabase();
                Cursor c = d.rawQuery("SELECT key, value FROM " + T_PROF, null);
                List<String> parts = new ArrayList<>();
                while (c.moveToNext()) {
                    String key = c.getString(0);
                    String val = c.getString(1);
                    switch (key) {
                        case "name":        parts.add("The user's name is " + val); break;
                        case "location":    parts.add("They live in " + val); break;
                        case "age":         parts.add("They are " + val + " years old"); break;
                        case "occupation":  parts.add("They work as a " + val); break;
                        case "guide_dog":   parts.add("They use a guide dog"); break;
                        case "dog_name":    parts.add("Their guide dog is named " + val); break;
                        case "uses_cane":   parts.add("They use a white cane"); break;
                        case "pref_length": parts.add("They prefer " + val + " answers"); break;
                        case "device":      parts.add("Their device is a " + val); break;
                    }
                }
                c.close();
                cb.onResult(parts.isEmpty() ? "" : String.join(". ", parts) + ".");
            } catch (Exception e) {
                cb.onResult("");
            }
        });
    }

    /* ── Stats ─────────────────────────────────────────────────── */

    public static void getStats(Context ctx, Callback<String> cb) {
        BG.execute(() -> {
            try {
                SQLiteDatabase d = db(ctx).getReadableDatabase();
                Cursor c1 = d.rawQuery("SELECT COUNT(*) FROM " + T_CONV, null);
                int convCount = c1.moveToFirst() ? c1.getInt(0) : 0;
                c1.close();
                Cursor c2 = d.rawQuery("SELECT COUNT(*) FROM " + T_PROF, null);
                int profCount = c2.moveToFirst() ? c2.getInt(0) : 0;
                c2.close();
                cb.onResult(convCount + " conversations, " + profCount + " profile facts stored.");
            } catch (Exception e) {
                cb.onResult("Memory stats unavailable.");
            }
        });
    }

    /* ── Clear all ─────────────────────────────────────────────── */

    public static void clear(Context ctx) {
        BG.execute(() -> {
            try {
                SQLiteDatabase d = db(ctx).getWritableDatabase();
                d.execSQL("DELETE FROM " + T_CONV);
                d.execSQL("DELETE FROM " + T_PROF);
            } catch (Exception ignored) {}
        });
    }
}
