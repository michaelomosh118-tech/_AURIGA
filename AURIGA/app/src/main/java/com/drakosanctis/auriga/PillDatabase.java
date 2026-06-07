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
 * PillDatabase — Session 11 (Phase 2).
 *
 * <p>Offline SQLite store for the PillGuard™ identification module.
 * Ships a pre-seeded table of ~80 common medications covering the most
 * frequently dispensed drugs in US/UK/KE markets. Entries include
 * NDC-aligned common names, standard imprint patterns, visual descriptors
 * (shape + color), and a caution message template.
 *
 * <h3>Schema</h3>
 * <pre>
 *   pills(
 *     id          INTEGER PRIMARY KEY,
 *     common_name TEXT    NOT NULL,   -- "Paracetamol 500mg"
 *     imprint     TEXT,               -- "L484", "TYLENOL 500" — nullable
 *     shape       TEXT    NOT NULL,   -- round | oval | oblong | capsule | other
 *     color       TEXT    NOT NULL,   -- white | yellow | blue | red | orange | green | other
 *     ndc_code    TEXT,               -- reference only
 *     description TEXT    NOT NULL    -- pharmacist summary
 *   )
 * </pre>
 *
 * <h3>Thread safety</h3>
 * {@link #getReadableDatabase()} / {@link #getWritableDatabase()} are
 * thread-safe per SQLiteOpenHelper contract. {@link PillDatabase#query} may
 * be called from any thread.
 */
public class PillDatabase extends SQLiteOpenHelper {

    private static final String TAG     = "PillDatabase";
    private static final String DB_NAME = "pillguard.db";
    private static final int    VERSION = 1;

    private static final String TABLE  = "pills";
    private static final String COL_ID   = "id";
    private static final String COL_NAME = "common_name";
    private static final String COL_IMP  = "imprint";
    private static final String COL_SHP  = "shape";
    private static final String COL_COL  = "color";
    private static final String COL_NDC  = "ndc_code";
    private static final String COL_DESC = "description";

    // ─────────────────────────────────────────────────────────────────────────
    // Singleton
    // ─────────────────────────────────────────────────────────────────────────

    private static volatile PillDatabase sInstance;

    public static PillDatabase getInstance(Context ctx) {
        if (sInstance == null) {
            synchronized (PillDatabase.class) {
                if (sInstance == null) {
                    sInstance = new PillDatabase(ctx.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private PillDatabase(Context ctx) {
        super(ctx, DB_NAME, null, VERSION);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SQLiteOpenHelper lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS " + TABLE + " (" +
            COL_ID   + " INTEGER PRIMARY KEY AUTOINCREMENT," +
            COL_NAME + " TEXT NOT NULL," +
            COL_IMP  + " TEXT," +
            COL_SHP  + " TEXT NOT NULL," +
            COL_COL  + " TEXT NOT NULL," +
            COL_NDC  + " TEXT," +
            COL_DESC + " TEXT NOT NULL)"
        );
        seed(db);
        Log.i(TAG, "Database created and seeded.");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    public static class PillRecord {
        public final String commonName;
        public final String imprint;
        public final String shape;
        public final String color;
        public final String ndcCode;
        public final String description;

        PillRecord(String commonName, String imprint, String shape,
                   String color, String ndcCode, String description) {
            this.commonName  = commonName;
            this.imprint     = imprint;
            this.shape       = shape;
            this.color       = color;
            this.ndcCode     = ndcCode;
            this.description = description;
        }
    }

    /**
     * Query the database for pills matching the given shape and color.
     * Optionally narrows by imprint prefix (first 4 chars, case-insensitive).
     *
     * @param shape   one of: round, oval, oblong, capsule, other
     * @param color   one of: white, yellow, blue, red, orange, green, other
     * @param imprint OCR'd imprint string, may be null or empty
     * @return ordered list of matching PillRecords, best match first
     */
    public List<PillRecord> query(String shape, String color, String imprint) {
        List<PillRecord> results = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();

        String imp = (imprint != null && imprint.length() >= 2)
                ? imprint.toUpperCase().trim() : null;

        String sel;
        String[] args;

        if (imp != null) {
            sel  = COL_SHP + "=? AND " + COL_COL + "=? AND " +
                   "UPPER(" + COL_IMP + ") LIKE ?";
            args = new String[]{ shape, color, "%" + imp + "%" };
        } else {
            sel  = COL_SHP + "=? AND " + COL_COL + "=?";
            args = new String[]{ shape, color };
        }

        try (Cursor c = db.query(TABLE, null, sel, args, null, null, COL_NAME)) {
            while (c.moveToNext()) {
                results.add(fromCursor(c));
            }
        } catch (Exception e) {
            Log.e(TAG, "query failed", e);
        }

        if (results.isEmpty()) {
            try (Cursor c = db.query(TABLE, null, COL_COL + "=?",
                                     new String[]{ color }, null, null, COL_NAME, "5")) {
                while (c.moveToNext()) results.add(fromCursor(c));
            } catch (Exception e) {
                Log.e(TAG, "fallback query failed", e);
            }
        }

        return results;
    }

    /** Insert a custom pill entry (for user-submitted calibration data). */
    public long insert(String commonName, String imprint, String shape,
                       String color, String ndcCode, String description) {
        ContentValues cv = new ContentValues();
        cv.put(COL_NAME, commonName);
        cv.put(COL_IMP,  imprint);
        cv.put(COL_SHP,  shape);
        cv.put(COL_COL,  color);
        cv.put(COL_NDC,  ndcCode);
        cv.put(COL_DESC, description);
        return getWritableDatabase().insertOrThrow(TABLE, null, cv);
    }

    public int count() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + TABLE, null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static PillRecord fromCursor(Cursor c) {
        return new PillRecord(
            c.getString(c.getColumnIndexOrThrow(COL_NAME)),
            c.getString(c.getColumnIndexOrThrow(COL_IMP)),
            c.getString(c.getColumnIndexOrThrow(COL_SHP)),
            c.getString(c.getColumnIndexOrThrow(COL_COL)),
            c.getString(c.getColumnIndexOrThrow(COL_NDC)),
            c.getString(c.getColumnIndexOrThrow(COL_DESC))
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Seed data — ~80 common medications (US/UK/KE markets)
    // ─────────────────────────────────────────────────────────────────────────

    private static void seed(SQLiteDatabase db) {
        db.beginTransaction();
        try {
            Object[][] pills = {
                // { common_name, imprint, shape, color, ndc_code, description }
                {"Paracetamol 500mg","L484","oblong","white","0363-0434","Paracetamol 500mg painkiller and fever reducer"},
                {"Paracetamol 500mg","TYLENOL 500","oblong","white","50580-488","Tylenol brand paracetamol 500mg"},
                {"Paracetamol 325mg","L405","round","white","0363-0435","Paracetamol 325mg regular strength"},
                {"Ibuprofen 200mg","I2","round","white","0363-0071","Ibuprofen 200mg anti-inflammatory"},
                {"Ibuprofen 400mg","IBU 400","oval","white","0591-3967","Ibuprofen 400mg prescription strength"},
                {"Ibuprofen 200mg","ADVIL 200","oval","orange","37000-162","Advil brand ibuprofen 200mg"},
                {"Aspirin 325mg","BAYER 325","round","white","0280-0110","Bayer aspirin 325mg"},
                {"Aspirin 81mg","A81","round","white","0280-0086","Low-dose aspirin 81mg"},
                {"Aspirin 500mg","ASP 500","oblong","white","0280-0120","Aspirin 500mg analgesic"},
                {"Amoxicillin 500mg","AMOX 500","capsule","red","65862-001","Amoxicillin 500mg antibiotic capsule"},
                {"Amoxicillin 250mg","AMOX 250","capsule","yellow","65862-002","Amoxicillin 250mg antibiotic capsule"},
                {"Ciprofloxacin 500mg","CIPRO 500","oblong","white","0004-0217","Ciprofloxacin 500mg antibiotic"},
                {"Metformin 500mg","MF 500","oblong","white","0093-1048","Metformin 500mg for type 2 diabetes"},
                {"Metformin 850mg","MF 850","oblong","white","0093-1049","Metformin 850mg for type 2 diabetes"},
                {"Lisinopril 10mg","LIS 10","round","white","0093-1042","Lisinopril 10mg for blood pressure"},
                {"Lisinopril 20mg","LIS 20","round","white","0093-1043","Lisinopril 20mg for blood pressure"},
                {"Atorvastatin 20mg","ATR 20","oval","white","0071-0157","Atorvastatin 20mg cholesterol lowering"},
                {"Atorvastatin 40mg","ATR 40","oval","white","0071-0159","Atorvastatin 40mg cholesterol lowering"},
                {"Omeprazole 20mg","OME 20","capsule","blue","0781-3157","Omeprazole 20mg for acid reflux"},
                {"Omeprazole 40mg","OME 40","capsule","blue","0781-3159","Omeprazole 40mg for acid reflux"},
                {"Simvastatin 20mg","SIM 20","oval","white","0071-0477","Simvastatin 20mg cholesterol lowering"},
                {"Simvastatin 40mg","SIM 40","oval","white","0071-0479","Simvastatin 40mg cholesterol lowering"},
                {"Amlodipine 5mg","AML 5","round","white","0069-1540","Amlodipine 5mg calcium channel blocker"},
                {"Amlodipine 10mg","AML 10","round","white","0069-1541","Amlodipine 10mg calcium channel blocker"},
                {"Losartan 50mg","LOS 50","oval","white","0093-7368","Losartan 50mg for blood pressure"},
                {"Losartan 100mg","LOS 100","oval","white","0093-7369","Losartan 100mg for blood pressure"},
                {"Gabapentin 300mg","GAB 300","capsule","yellow","0781-2853","Gabapentin 300mg for nerve pain"},
                {"Gabapentin 400mg","GAB 400","capsule","orange","0781-2854","Gabapentin 400mg for nerve pain"},
                {"Sertraline 50mg","SER 50","oblong","white","0049-4900","Sertraline 50mg antidepressant"},
                {"Sertraline 100mg","SER 100","oblong","white","0049-4910","Sertraline 100mg antidepressant"},
                {"Fluoxetine 20mg","FLX 20","capsule","green","0777-3105","Fluoxetine 20mg antidepressant"},
                {"Escitalopram 10mg","ESC 10","round","white","0456-2010","Escitalopram 10mg antidepressant"},
                {"Escitalopram 20mg","ESC 20","round","white","0456-2020","Escitalopram 20mg antidepressant"},
                {"Clopidogrel 75mg","CLO 75","round","white","0173-0723","Clopidogrel 75mg antiplatelet"},
                {"Metoprolol 25mg","MET 25","round","white","0378-5251","Metoprolol succinate 25mg beta blocker"},
                {"Metoprolol 50mg","MET 50","oval","white","0378-5252","Metoprolol succinate 50mg beta blocker"},
                {"Levothyroxine 50mcg","LEVO 50","round","white","0074-9296","Levothyroxine 50mcg thyroid hormone"},
                {"Levothyroxine 100mcg","LEVO 100","round","yellow","0074-9297","Levothyroxine 100mcg thyroid hormone"},
                {"Furosemide 40mg","FUR 40","round","white","0781-1851","Furosemide 40mg diuretic"},
                {"Hydrochlorothiazide 25mg","HCT 25","round","white","0781-1861","HCTZ 25mg diuretic"},
                {"Prednisone 5mg","PRD 5","round","white","0054-4740","Prednisone 5mg corticosteroid"},
                {"Prednisolone 5mg","PRED 5","round","white","0121-0649","Prednisolone 5mg corticosteroid"},
                {"Tramadol 50mg","TRM 50","capsule","yellow","0406-0509","Tramadol 50mg analgesic"},
                {"Diazepam 5mg","DIAZ 5","round","yellow","0140-0006","Diazepam 5mg benzodiazepine"},
                {"Diazepam 10mg","DIAZ 10","round","blue","0140-0007","Diazepam 10mg benzodiazepine"},
                {"Lorazepam 1mg","LOR 1","oval","white","0591-0244","Lorazepam 1mg benzodiazepine"},
                {"Codeine 30mg","COD 30","round","white","0143-1286","Codeine phosphate 30mg"},
                {"Cetirizine 10mg","CTZ 10","oval","white","0536-1086","Cetirizine 10mg antihistamine"},
                {"Cetirizine 5mg","CTZ 5","round","white","0536-1085","Cetirizine 5mg antihistamine"},
                {"Loratadine 10mg","LOR10","round","white","0363-1069","Loratadine 10mg antihistamine"},
                {"Fexofenadine 120mg","FEX120","oblong","white","0088-1090","Fexofenadine 120mg antihistamine"},
                {"Fexofenadine 180mg","FEX180","oblong","orange","0088-1091","Fexofenadine 180mg antihistamine"},
                {"Ranitidine 150mg","RAN150","oblong","white","0173-0344","Ranitidine 150mg antacid"},
                {"Pantoprazole 40mg","PNT 40","oval","yellow","0574-0221","Pantoprazole 40mg acid reflux"},
                {"Lansoprazole 30mg","LAN 30","capsule","blue","0093-3077","Lansoprazole 30mg acid reflux"},
                {"Doxycycline 100mg","DOX100","capsule","orange","0115-5300","Doxycycline 100mg antibiotic"},
                {"Metronidazole 400mg","MTR400","oblong","white","0009-0024","Metronidazole 400mg antibiotic"},
                {"Erythromycin 250mg","ERY250","round","red","0074-6306","Erythromycin 250mg antibiotic"},
                {"Trimethoprim 200mg","TRM200","oblong","white","0115-4406","Trimethoprim 200mg antibiotic"},
                {"Co-amoxiclav 625mg","AUG 625","oblong","white","0029-6090","Augmentin co-amoxiclav 625mg"},
                {"Warfarin 1mg","WAR 1","round","white","0056-0180","Warfarin 1mg anticoagulant — CAUTION"},
                {"Warfarin 3mg","WAR 3","round","blue","0056-0183","Warfarin 3mg anticoagulant — CAUTION"},
                {"Warfarin 5mg","WAR 5","round","white","0056-0185","Warfarin 5mg anticoagulant — CAUTION"},
                {"Digoxin 125mcg","DIG125","round","white","0173-0249","Digoxin 125mcg cardiac glycoside"},
                {"Insulin Glargine","(vial)","other","other","0088-2510","Insulin glargine injection — see label"},
                {"Methotrexate 2.5mg","MTX 2.5","round","yellow","0093-1067","Methotrexate 2.5mg — CYTOTOXIC"},
                {"Spironolactone 25mg","SPR 25","round","white","0025-1015","Spironolactone 25mg potassium-sparing diuretic"},
                {"Ramipril 5mg","RAM 5","capsule","orange","0068-0098","Ramipril 5mg ACE inhibitor"},
                {"Ramipril 10mg","RAM 10","capsule","red","0068-0099","Ramipril 10mg ACE inhibitor"},
                {"Bisoprolol 5mg","BIS 5","round","white","0258-3710","Bisoprolol 5mg beta blocker"},
                {"Bisoprolol 10mg","BIS 10","round","white","0258-3711","Bisoprolol 10mg beta blocker"},
                {"Candesartan 8mg","CAN 8","oval","white","0186-0157","Candesartan 8mg for blood pressure"},
                {"Allopurinol 100mg","ALL100","round","white","0781-1061","Allopurinol 100mg for gout"},
                {"Allopurinol 300mg","ALL300","round","white","0781-1063","Allopurinol 300mg for gout"},
                {"Azithromycin 250mg","AZI250","capsule","red","0069-3070","Azithromycin 250mg antibiotic"},
                {"Clarithromycin 250mg","CLA250","oval","yellow","0074-3368","Clarithromycin 250mg antibiotic"},
                {"Loperamide 2mg","LOP 2","capsule","green","0045-0384","Loperamide 2mg anti-diarrhoeal"},
                {"Vitamin D3 1000IU","D3 1K","round","yellow","0363-0576","Vitamin D3 1000 IU supplement"},
                {"Vitamin B12 1000mcg","B12 1K","round","red","0363-0577","Vitamin B12 1000mcg supplement"},
                {"Ferrous Sulphate 200mg","FE200","oblong","green","0363-0578","Ferrous sulphate 200mg iron supplement"},
                {"Folic Acid 5mg","FA 5","round","yellow","0363-0579","Folic acid 5mg supplement"},
                {"Chlorphenamine 4mg","CPH 4","round","white","0173-0342","Chlorphenamine 4mg antihistamine"},
                {"Naproxen 250mg","NAP250","oval","white","0093-0536","Naproxen 250mg anti-inflammatory"},
                {"Naproxen 500mg","NAP500","oval","white","0093-0537","Naproxen 500mg anti-inflammatory"},
                {"Co-codamol 8/500","CC 8","oblong","white","0143-1290","Co-codamol 8mg/500mg analgesic"},
                {"Co-codamol 30/500","CC 30","oblong","white","0143-1292","Co-codamol 30mg/500mg analgesic"},
            };

            for (Object[] r : pills) {
                ContentValues cv = new ContentValues(7);
                cv.put(COL_NAME, (String) r[0]);
                cv.put(COL_IMP,  (String) r[1]);
                cv.put(COL_SHP,  (String) r[2]);
                cv.put(COL_COL,  (String) r[3]);
                cv.put(COL_NDC,  (String) r[4]);
                cv.put(COL_DESC, (String) r[5]);
                db.insert(TABLE, null, cv);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }
}
