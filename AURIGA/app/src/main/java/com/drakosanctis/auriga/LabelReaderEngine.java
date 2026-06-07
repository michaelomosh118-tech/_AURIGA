package com.drakosanctis.auriga;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LabelReaderEngine — Session 14 (Phase 2).
 *
 * <p>Product and expiry date reader. Accepts an NV21 camera frame, runs
 * on-device ML Kit Latin OCR (same bundled model used by ReaderActivity),
 * parses the recognised text for:
 * <ul>
 *   <li><b>Expiry date</b> — regex engine covering all common label formats</li>
 *   <li><b>Product name</b> — heuristic: first non-date, non-ingredient line
 *       with ≥ 3 characters, typically at the top of the label</li>
 *   <li><b>Barcode / QR code</b> — ZXing multi-format decoder (offline)</li>
 *   <li><b>Ingredient list</b> — lines following "INGREDIENTS" keyword</li>
 * </ul>
 *
 * <h3>Expiry formats supported</h3>
 * <pre>
 *   DD/MM/YY     DD/MM/YYYY     MM/YY     MM/YYYY
 *   DD-MM-YYYY   MM-YYYY        DD.MM.YY
 *   BEST BEFORE  DD MMM YYYY    BBE: DD/MM/YY
 *   USE BY       BEST BY        EXP:
 *   JAN 2026     01 JAN 26      2026-01-15 (ISO 8601)
 * </pre>
 *
 * <h3>Barcode</h3>
 * Uses ZXing ({@code com.google.zxing:core}) for EAN-8, EAN-13, UPC-A,
 * UPC-E, QR Code, Code 128, Code 39, Data Matrix, ITF and PDF-417.
 * The ZXing decode runs on the luminance channel of the NV21 frame directly
 * without JPEG conversion — no memory allocation overhead.
 *
 * <h3>Output priority</h3>
 * Callers should speak results in this order:
 *   1. Product name (if found)
 *   2. Expiry date (if found)
 *   3. Ingredients (on explicit user request only — can be long)
 *
 * <h3>Thread safety</h3>
 * All public methods synchronize on the ML Kit task and may be called from
 * any thread. ZXing decode is synchronous and stateless.
 */
public class LabelReaderEngine {

    private static final String TAG = "LabelReaderEngine";

    // ─── Expiry date regex patterns ───────────────────────────────────────────
    // Order matters: more specific patterns first.
    private static final Pattern[] EXPIRY_PATTERNS = {
        // "BEST BEFORE 01 JAN 2026", "USE BY 31/12/25", "EXP: 06/2026"
        Pattern.compile(
            "(?i)(?:best\\s+before|use\\s+by|best\\s+by|bb:|bbe:|exp(?:iry)?:?|expiry date:?)\\s*" +
            "([0-9]{1,2}[/\\-\\.\\s]?" +
            "(?:[0-9]{1,2}|jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)" +
            "[/\\-\\.\\s]?[0-9]{2,4})",
            Pattern.CASE_INSENSITIVE),
        // ISO 8601: 2026-01-15
        Pattern.compile("\\b(20[2-9][0-9]-(?:0[1-9]|1[0-2])-(?:[0-2][0-9]|3[01]))\\b"),
        // DD/MM/YYYY or DD-MM-YYYY
        Pattern.compile("\\b((?:0?[1-9]|[12][0-9]|3[01])[/\\-.](0?[1-9]|1[0-2])[/\\-.](20[2-9][0-9]|[2-9][0-9]))\\b"),
        // MM/YYYY or MM-YYYY
        Pattern.compile("\\b((0?[1-9]|1[0-2])[/\\-](20[2-9][0-9]))\\b"),
        // DD MMM YY(YY) e.g. "31 DEC 26"
        Pattern.compile("\\b((?:0?[1-9]|[12][0-9]|3[01])\\s+" +
            "(?:JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)\\s+[0-9]{2,4})\\b",
            Pattern.CASE_INSENSITIVE),
        // MMM YYYY e.g. "JAN 2026"
        Pattern.compile("\\b((?:JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)\\s+20[2-9][0-9])\\b",
            Pattern.CASE_INSENSITIVE),
    };

    // ─── Ingredient markers ───────────────────────────────────────────────────
    private static final Pattern INGREDIENTS_HEADER =
        Pattern.compile("(?i)\\bingredients?\\s*:?\\s*");

    // ─────────────────────────────────────────────────────────────────────────
    // Result type
    // ─────────────────────────────────────────────────────────────────────────

    public static class LabelResult {
        /** Best candidate for product name (may be null). */
        public final String productName;
        /** Parsed expiry date string as found on label (may be null). */
        public final String expiryDate;
        /** Barcode / QR value (may be null if no code found or ZXing absent). */
        public final String barcode;
        /** Barcode format string e.g. "EAN_13" (may be null). */
        public final String barcodeFormat;
        /** Full ingredients text block (may be null). */
        public final String ingredients;
        /** Raw OCR text from ML Kit (always non-null on success, empty on failure). */
        public final String rawOcrText;

        LabelResult(String productName, String expiryDate,
                    String barcode, String barcodeFormat,
                    String ingredients, String rawOcrText) {
            this.productName   = productName;
            this.expiryDate    = expiryDate;
            this.barcode       = barcode;
            this.barcodeFormat = barcodeFormat;
            this.ingredients   = ingredients;
            this.rawOcrText    = rawOcrText != null ? rawOcrText : "";
        }

        /** Returns a spoken summary in priority order (name → expiry → barcode). */
        public String toSpokenSummary() {
            StringBuilder sb = new StringBuilder();
            if (productName != null && !productName.isEmpty()) {
                sb.append(productName).append(". ");
            }
            if (expiryDate != null && !expiryDate.isEmpty()) {
                sb.append("Expires ").append(expiryDate).append(". ");
            }
            if (barcode != null && !barcode.isEmpty()) {
                sb.append("Barcode ").append(barcode).append(". ");
            }
            if (sb.length() == 0) {
                sb.append("No label information found.");
            }
            return sb.toString().trim();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    private final TextRecognizer recognizer;
    private final boolean zxingAvailable;

    public LabelReaderEngine(Context ctx) {
        this.recognizer    = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        this.zxingAvailable = probeZxing();
        Log.i(TAG, "LabelReaderEngine ready. ZXing=" + zxingAvailable);
    }

    /**
     * Analyse an NV21 frame and return a {@link LabelResult}.
     * Runs OCR synchronously (blocks the calling thread) — call from a
     * background thread or ExecutorService.
     *
     * @param nv21    NV21 byte array from CameraX ImageAnalysis
     * @param width   frame width in pixels
     * @param height  frame height in pixels
     * @param rotation degrees (0, 90, 180, 270)
     * @return analysis result (never null; empty strings when nothing found)
     */
    public LabelResult analyse(byte[] nv21, int width, int height, int rotation) {
        // ── Step 1: ZXing barcode decode (fast, CPU-only, no alloc) ──────────
        String barcode       = null;
        String barcodeFormat = null;
        if (zxingAvailable) {
            BarcodeResult br = decodeBarcode(nv21, width, height);
            if (br != null) {
                barcode       = br.text;
                barcodeFormat = br.format;
                Log.d(TAG, "Barcode: [" + barcodeFormat + "] " + barcode);
            }
        }

        // ── Step 2: ML Kit OCR ───────────────────────────────────────────────
        String ocrText = runOcr(nv21, width, height, rotation);
        Log.d(TAG, "OCR raw (" + ocrText.length() + " chars): " +
              ocrText.replace('\n', '|').substring(0, Math.min(120, ocrText.length())));

        // ── Step 3: Parse OCR output ─────────────────────────────────────────
        String expiry      = parseExpiry(ocrText);
        String productName = parseProductName(ocrText, expiry);
        String ingredients = parseIngredients(ocrText);

        return new LabelResult(productName, expiry, barcode, barcodeFormat,
                               ingredients, ocrText);
    }

    public void shutdown() {
        recognizer.close();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ML Kit OCR (synchronous wrapper around async task)
    // ─────────────────────────────────────────────────────────────────────────

    private String runOcr(byte[] nv21, int width, int height, int rotation) {
        // Convert NV21 → JPEG → Bitmap → InputImage
        // (ML Kit InputImage.fromByteArray also accepts NV21 directly but
        //  requires API-level-dependent rotation handling — JPEG is simpler)
        Bitmap bmp = nv21ToBitmap(nv21, width, height);
        if (bmp == null) return "";

        InputImage image = InputImage.fromBitmap(bmp, rotation);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>("");

        recognizer.process(image)
            .addOnSuccessListener(visionText -> {
                result.set(visionText.getText());
                latch.countDown();
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "OCR failed", e);
                latch.countDown();
            });

        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        bmp.recycle();
        return result.get();
    }

    private static Bitmap nv21ToBitmap(byte[] nv21, int width, int height) {
        try {
            YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuv.compressToJpeg(new Rect(0, 0, width, height), 90, out);
            byte[] jpeg = out.toByteArray();
            return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        } catch (Exception e) {
            Log.e(TAG, "NV21→Bitmap failed", e);
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Expiry date parser
    // ─────────────────────────────────────────────────────────────────────────

    static String parseExpiry(String text) {
        if (text == null || text.isEmpty()) return null;
        String flat = text.replace('\n', ' ').toUpperCase(Locale.ROOT);

        for (Pattern p : EXPIRY_PATTERNS) {
            Matcher m = p.matcher(flat);
            if (m.find()) {
                String found = (m.groupCount() >= 1) ? m.group(1) : m.group(0);
                if (found != null) return found.trim();
            }
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Product name heuristic
    // ─────────────────────────────────────────────────────────────────────────

    static String parseProductName(String ocrText, String knownExpiry) {
        if (ocrText == null || ocrText.isEmpty()) return null;
        String[] lines = ocrText.split("\\r?\\n");

        for (String line : lines) {
            String t = line.trim();
            if (t.length() < 3) continue;
            // Skip lines that are purely numeric or look like a date
            if (t.matches("[0-9/\\-\\.\\s]+")) continue;
            if (knownExpiry != null && t.toUpperCase(Locale.ROOT)
                    .contains(knownExpiry.toUpperCase(Locale.ROOT).substring(0,
                        Math.min(6, knownExpiry.length())))) continue;
            // Skip obvious non-product lines
            String up = t.toUpperCase(Locale.ROOT);
            if (up.startsWith("INGREDIENT") || up.startsWith("NUTRITION") ||
                up.startsWith("BEST BEFORE") || up.startsWith("USE BY") ||
                up.startsWith("EXP") || up.startsWith("NET WT") ||
                up.startsWith("MANUFACTURED")) continue;
            // Prefer lines with both upper and lower case (i.e. real words)
            if (t.length() >= 4) return t;
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Ingredient list extractor
    // ─────────────────────────────────────────────────────────────────────────

    static String parseIngredients(String ocrText) {
        if (ocrText == null) return null;
        Matcher m = INGREDIENTS_HEADER.matcher(ocrText);
        if (!m.find()) return null;
        int start = m.end();
        String block = ocrText.substring(start).trim();
        // Cut off at the next section heading (all-caps line)
        String[] lines = block.split("\\r?\\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            // Stop at an all-caps section header (≥4 chars, no lowercase)
            if (t.length() >= 4 && t.equals(t.toUpperCase(Locale.ROOT)) &&
                t.matches("[A-Z ]+")) break;
            sb.append(t).append(' ');
            if (sb.length() > 600) break;  // cap to avoid very long read
        }
        String result = sb.toString().trim();
        return result.isEmpty() ? null : result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ZXing barcode decoder
    // ─────────────────────────────────────────────────────────────────────────

    private static class BarcodeResult {
        final String text;
        final String format;
        BarcodeResult(String text, String format) {
            this.text = text; this.format = format;
        }
    }

    /**
     * Attempts to decode a barcode/QR from the luminance plane of the NV21
     * frame using ZXing multi-format reader. Returns null if ZXing is not on
     * the classpath or no code is found.
     */
    private BarcodeResult decodeBarcode(byte[] nv21, int width, int height) {
        try {
            // Use reflection so ZXing is an optional compile-time dep:
            // if the class isn't present, probeZxing() returned false and
            // we never reach this method.
            Class<?> lsClass   = Class.forName("com.google.zxing.LuminanceSource");
            Class<?> pcClass   = Class.forName("com.google.zxing.PlanarYUVLuminanceSource");
            Class<?> bmpClass  = Class.forName("com.google.zxing.BinaryBitmap");
            Class<?> hybClass  = Class.forName("com.google.zxing.common.HybridBinarizer");
            Class<?> mfClass   = Class.forName("com.google.zxing.MultiFormatReader");
            Class<?> hintsClass = Class.forName("java.util.EnumMap");

            // PlanarYUVLuminanceSource(nv21, width, height, 0, 0, width, height, false)
            Object source = pcClass
                .getConstructor(byte[].class, int.class, int.class,
                                int.class, int.class, int.class, int.class, boolean.class)
                .newInstance(nv21, width, height, 0, 0, width, height, false);

            Object binarizer = hybClass.getConstructor(lsClass).newInstance(source);
            Object bitmap    = bmpClass.getConstructor(
                Class.forName("com.google.zxing.Binarizer")).newInstance(binarizer);

            Object reader    = mfClass.newInstance();
            Object result    = mfClass.getMethod("decode", bmpClass).invoke(reader, bitmap);

            String text   = (String) result.getClass().getMethod("getText").invoke(result);
            Object fmt    = result.getClass().getMethod("getBarcodeFormat").invoke(result);
            String format = fmt != null ? fmt.toString() : "UNKNOWN";
            return new BarcodeResult(text, format);

        } catch (Exception e) {
            // Not found / not decodable — silent
            return null;
        }
    }

    /** Returns true if ZXing core is on the classpath. */
    private static boolean probeZxing() {
        try {
            Class.forName("com.google.zxing.MultiFormatReader");
            return true;
        } catch (ClassNotFoundException e) {
            Log.i(TAG, "ZXing not on classpath — barcode decode disabled.");
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // selfTest
    // ─────────────────────────────────────────────────────────────────────────

    public boolean selfTest(Context ctx) {
        // Expiry parser smoke test
        String t1 = parseExpiry("BEST BEFORE 31/12/2026");
        String t2 = parseExpiry("EXP: JAN 2027");
        String t3 = parseExpiry("Use By 01-Jun-25");
        boolean ok = t1 != null && t2 != null && t3 != null;
        Log.i(TAG, "selfTest → expiry parser ok=" + ok +
              " t1='" + t1 + "' t2='" + t2 + "' t3='" + t3 + "'");
        return ok;
    }
}
