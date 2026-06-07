package com.drakosanctis.auriga;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * FaceDatabase — Session 12 (Phase 2).
 *
 * <p>Offline SQLite store for the FaceVault™ face recognition module.
 * Stores 128-dimensional float embeddings alongside the enrolled person's
 * name. Embeddings are serialised as raw {@code BLOB} (512 bytes = 128×4).
 *
 * <h3>Privacy</h3>
 * Only numerical embeddings are stored — not reconstructable to images.
 * The database lives in the app's private storage, never backed up to
 * cloud by default ({@code android:allowBackup="false"} on the application
 * manifest covers this at the OS level).
 *
 * <h3>Schema</h3>
 * <pre>
 *   faces(
 *     id         INTEGER PRIMARY KEY,
 *     name       TEXT    NOT NULL,
 *     embedding  BLOB    NOT NULL    -- 128×float32 = 512 bytes
 *   )
 * </pre>
 */
public class FaceDatabase extends SQLiteOpenHelper {

    private static final String TAG     = "FaceDatabase";
    private static final String DB_NAME = "facevault.db";
    private static final int    VERSION = 1;

    private static final String TABLE       = "faces";
    private static final String COL_ID      = "id";
    private static final String COL_NAME    = "name";
    private static final String COL_EMBED   = "embedding";

    public static final int EMBEDDING_DIM = 128;

    // ─────────────────────────────────────────────────────────────────────────
    // Singleton
    // ─────────────────────────────────────────────────────────────────────────

    private static volatile FaceDatabase sInstance;

    public static FaceDatabase getInstance(Context ctx) {
        if (sInstance == null) {
            synchronized (FaceDatabase.class) {
                if (sInstance == null) {
                    sInstance = new FaceDatabase(ctx.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private FaceDatabase(Context ctx) {
        super(ctx, DB_NAME, null, VERSION);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SQLiteOpenHelper lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS " + TABLE + " (" +
            COL_ID    + " INTEGER PRIMARY KEY AUTOINCREMENT," +
            COL_NAME  + " TEXT NOT NULL," +
            COL_EMBED + " BLOB NOT NULL)"
        );
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_faces_name ON " + TABLE + " (" + COL_NAME + ")");
        Log.i(TAG, "FaceDatabase created.");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public record type
    // ─────────────────────────────────────────────────────────────────────────

    public static class FaceRecord {
        public final long   id;
        public final String name;
        public final float[] embedding;

        FaceRecord(long id, String name, float[] embedding) {
            this.id        = id;
            this.name      = name;
            this.embedding = embedding;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CRUD
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Insert or replace the embedding for {@code name}.
     * If the person is already enrolled, their old entry is removed first.
     */
    public synchronized long upsert(String name, float[] embedding) {
        if (embedding.length != EMBEDDING_DIM) {
            throw new IllegalArgumentException(
                "Embedding must be " + EMBEDDING_DIM + "-dimensional, got " + embedding.length);
        }
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE, COL_NAME + "=?", new String[]{ name });
        ContentValues cv = new ContentValues(2);
        cv.put(COL_NAME,  name);
        cv.put(COL_EMBED, floatsToBytes(embedding));
        return db.insertOrThrow(TABLE, null, cv);
    }

    /** Delete all embeddings for the given name. */
    public synchronized boolean delete(String name) {
        int rows = getWritableDatabase().delete(TABLE, COL_NAME + "=?",
                                                new String[]{ name });
        return rows > 0;
    }

    /** Fetch all enrolled face records for matching. */
    public List<FaceRecord> loadAll() {
        List<FaceRecord> records = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(
                TABLE, null, null, null, null, null, COL_NAME)) {
            while (c.moveToNext()) {
                long   id   = c.getLong(c.getColumnIndexOrThrow(COL_ID));
                String name = c.getString(c.getColumnIndexOrThrow(COL_NAME));
                byte[] blob = c.getBlob(c.getColumnIndexOrThrow(COL_EMBED));
                records.add(new FaceRecord(id, name, bytesToFloats(blob)));
            }
        } catch (Exception e) {
            Log.e(TAG, "loadAll failed", e);
        }
        return records;
    }

    /** Return just the enrolled names (for UI display). */
    public List<String> getNames() {
        List<String> names = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(
                TABLE, new String[]{ COL_NAME }, null, null, COL_NAME, null, COL_NAME)) {
            while (c.moveToNext()) {
                names.add(c.getString(0));
            }
        } catch (Exception e) {
            Log.e(TAG, "getNames failed", e);
        }
        return names;
    }

    /** Number of enrolled people. */
    public int count() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(DISTINCT " + COL_NAME + ") FROM " + TABLE, null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Serialisation helpers
    // ─────────────────────────────────────────────────────────────────────────

    public static byte[] floatsToBytes(float[] floats) {
        ByteBuffer bb = ByteBuffer.allocate(floats.length * 4);
        for (float f : floats) bb.putFloat(f);
        return bb.array();
    }

    public static float[] bytesToFloats(byte[] bytes) {
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        float[] floats = new float[bytes.length / 4];
        for (int i = 0; i < floats.length; i++) floats[i] = bb.getFloat();
        return floats;
    }
}
