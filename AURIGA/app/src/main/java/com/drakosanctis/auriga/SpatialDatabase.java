package com.drakosanctis.auriga;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * SpatialDatabase — Session 15 (Phase 3).
 *
 * <p>Offline SQLite store for the SpatialMemory™ route recording and replay
 * module. Two tables:
 *
 * <h3>Schema</h3>
 * <pre>
 *   routes(
 *     id          INTEGER PRIMARY KEY AUTOINCREMENT,
 *     name        TEXT    UNIQUE NOT NULL,
 *     created_at  INTEGER NOT NULL     -- Unix millis
 *   )
 *
 *   landmarks(
 *     id          INTEGER PRIMARY KEY AUTOINCREMENT,
 *     route_id    INTEGER NOT NULL REFERENCES routes(id) ON DELETE CASCADE,
 *     description TEXT    NOT NULL,    -- e.g. "glass door on left"
 *     step_offset INTEGER NOT NULL,    -- steps since previous landmark
 *     seq_order   INTEGER NOT NULL     -- 0-based position within route
 *   )
 * </pre>
 *
 * <h3>Capacity</h3>
 * Default Android SQLite page size (4KB) comfortably stores 10,000 landmarks
 * across 500 routes in under 5MB — well within the spec.
 *
 * <h3>Thread safety</h3>
 * All public methods may be called from any thread; they acquire the
 * SQLiteOpenHelper lock internally.
 */
public class SpatialDatabase extends SQLiteOpenHelper {

    private static final String TAG     = "SpatialDatabase";
    private static final String DB_NAME = "spatial_memory.db";
    private static final int    VERSION = 1;

    // Table / column names
    public static final String T_ROUTES    = "routes";
    public static final String T_LANDMARKS = "landmarks";

    public static final String C_ID          = "id";
    public static final String C_NAME        = "name";
    public static final String C_CREATED_AT  = "created_at";
    public static final String C_ROUTE_ID    = "route_id";
    public static final String C_DESCRIPTION = "description";
    public static final String C_STEP_OFFSET = "step_offset";
    public static final String C_SEQ_ORDER   = "seq_order";

    // ─────────────────────────────────────────────────────────────────────────
    // Singleton
    // ─────────────────────────────────────────────────────────────────────────

    private static volatile SpatialDatabase sInstance;

    public static SpatialDatabase getInstance(Context ctx) {
        if (sInstance == null) {
            synchronized (SpatialDatabase.class) {
                if (sInstance == null) {
                    sInstance = new SpatialDatabase(ctx.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private SpatialDatabase(Context ctx) {
        super(ctx, DB_NAME, null, VERSION);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SQLiteOpenHelper lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("PRAGMA foreign_keys = ON");

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS " + T_ROUTES + " (" +
            C_ID         + " INTEGER PRIMARY KEY AUTOINCREMENT," +
            C_NAME       + " TEXT UNIQUE NOT NULL," +
            C_CREATED_AT + " INTEGER NOT NULL)"
        );

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS " + T_LANDMARKS + " (" +
            C_ID          + " INTEGER PRIMARY KEY AUTOINCREMENT," +
            C_ROUTE_ID    + " INTEGER NOT NULL REFERENCES " + T_ROUTES + "(" + C_ID + ") ON DELETE CASCADE," +
            C_DESCRIPTION + " TEXT NOT NULL," +
            C_STEP_OFFSET + " INTEGER NOT NULL," +
            C_SEQ_ORDER   + " INTEGER NOT NULL)"
        );

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_lm_route ON " +
                   T_LANDMARKS + " (" + C_ROUTE_ID + ", " + C_SEQ_ORDER + ")");

        Log.i(TAG, "SpatialDatabase created.");
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        if (!db.isReadOnly()) db.execSQL("PRAGMA foreign_keys = ON");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + T_LANDMARKS);
        db.execSQL("DROP TABLE IF EXISTS " + T_ROUTES);
        onCreate(db);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Value types
    // ─────────────────────────────────────────────────────────────────────────

    public static class Route {
        public final long   id;
        public final String name;
        public final long   createdAt;
        Route(long id, String name, long createdAt) {
            this.id = id; this.name = name; this.createdAt = createdAt;
        }
    }

    public static class Landmark {
        public final long   id;
        public final long   routeId;
        public final String description;
        public final int    stepOffset;
        public final int    seqOrder;
        Landmark(long id, long routeId, String description, int stepOffset, int seqOrder) {
            this.id = id; this.routeId = routeId; this.description = description;
            this.stepOffset = stepOffset; this.seqOrder = seqOrder;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Route CRUD
    // ─────────────────────────────────────────────────────────────────────────

    /** Insert a new route. Returns the new row ID, or -1 on conflict. */
    public long insertRoute(String name) {
        ContentValues cv = new ContentValues(2);
        cv.put(C_NAME, name);
        cv.put(C_CREATED_AT, System.currentTimeMillis());
        try {
            return getWritableDatabase().insertOrThrow(T_ROUTES, null, cv);
        } catch (Exception e) {
            Log.e(TAG, "insertRoute failed: " + e.getMessage());
            return -1L;
        }
    }

    /** Look up a route by name. Returns null if not found. */
    public Route findRoute(String name) {
        try (Cursor c = getReadableDatabase().query(
                T_ROUTES, null, C_NAME + "=?", new String[]{ name },
                null, null, null, "1")) {
            if (!c.moveToFirst()) return null;
            return routeFromCursor(c);
        }
    }

    /** Look up a route by ID. */
    public Route findRouteById(long id) {
        try (Cursor c = getReadableDatabase().query(
                T_ROUTES, null, C_ID + "=?",
                new String[]{ String.valueOf(id) }, null, null, null, "1")) {
            if (!c.moveToFirst()) return null;
            return routeFromCursor(c);
        }
    }

    /** All route names, sorted by creation date descending (newest first). */
    public List<String> getAllRouteNames() {
        List<String> names = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(
                T_ROUTES, new String[]{ C_NAME },
                null, null, null, null, C_CREATED_AT + " DESC")) {
            while (c.moveToNext()) names.add(c.getString(0));
        }
        return names;
    }

    /** All Route objects. */
    public List<Route> getAllRoutes() {
        List<Route> routes = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(
                T_ROUTES, null, null, null, null, null, C_CREATED_AT + " DESC")) {
            while (c.moveToNext()) routes.add(routeFromCursor(c));
        }
        return routes;
    }

    /**
     * Delete a route and all its landmarks (ON DELETE CASCADE handles landmarks).
     * Returns true if the route existed and was deleted.
     */
    public boolean deleteRoute(String name) {
        int rows = getWritableDatabase().delete(T_ROUTES, C_NAME + "=?",
                                                new String[]{ name });
        return rows > 0;
    }

    public int routeCount() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + T_ROUTES, null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Landmark CRUD
    // ─────────────────────────────────────────────────────────────────────────

    /** Append a landmark to the given route. Returns the new row ID. */
    public long insertLandmark(long routeId, String description,
                               int stepOffset, int seqOrder) {
        ContentValues cv = new ContentValues(4);
        cv.put(C_ROUTE_ID,    routeId);
        cv.put(C_DESCRIPTION, description);
        cv.put(C_STEP_OFFSET, stepOffset);
        cv.put(C_SEQ_ORDER,   seqOrder);
        return getWritableDatabase().insertOrThrow(T_LANDMARKS, null, cv);
    }

    /**
     * Load all landmarks for a route in sequence order.
     * Returns an empty list if the route has no landmarks.
     */
    public List<Landmark> getLandmarks(long routeId) {
        List<Landmark> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(
                T_LANDMARKS, null,
                C_ROUTE_ID + "=?", new String[]{ String.valueOf(routeId) },
                null, null, C_SEQ_ORDER + " ASC")) {
            while (c.moveToNext()) result.add(landmarkFromCursor(c));
        }
        return result;
    }

    /** Delete all landmarks for a route (used when overwriting a recording). */
    public int clearLandmarks(long routeId) {
        return getWritableDatabase().delete(T_LANDMARKS,
                C_ROUTE_ID + "=?", new String[]{ String.valueOf(routeId) });
    }

    public int landmarkCount() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + T_LANDMARKS, null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cursor helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static Route routeFromCursor(Cursor c) {
        return new Route(
            c.getLong(c.getColumnIndexOrThrow(C_ID)),
            c.getString(c.getColumnIndexOrThrow(C_NAME)),
            c.getLong(c.getColumnIndexOrThrow(C_CREATED_AT))
        );
    }

    private static Landmark landmarkFromCursor(Cursor c) {
        return new Landmark(
            c.getLong(c.getColumnIndexOrThrow(C_ID)),
            c.getLong(c.getColumnIndexOrThrow(C_ROUTE_ID)),
            c.getString(c.getColumnIndexOrThrow(C_DESCRIPTION)),
            c.getInt(c.getColumnIndexOrThrow(C_STEP_OFFSET)),
            c.getInt(c.getColumnIndexOrThrow(C_SEQ_ORDER))
        );
    }
}
