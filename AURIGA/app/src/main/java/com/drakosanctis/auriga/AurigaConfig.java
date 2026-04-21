package com.drakosanctis.auriga;

/**
 * AurigaConfig: The central control panel for the DrakoSanctis Auriga Ecosystem.
 * This class manages the feature flags, license tiers, and product variants
 * that determine the behavior of the engine across different hardware and platforms.
 */
public class AurigaConfig {

    // --- PRODUCT VARIANTS ---
    public enum Product {
        NAVI,        // The Charioteer (Accessibility)
        SENTINEL,    // The Watchman (Safety/Fall Detection)
        AERO,        // The Winged One (Drones/Robotics)
        INDUSTRIAL   // The Builder (Warehouse/Logistics)
    }

    /**
     * CURRENT_PRODUCT is driven by the active Gradle product flavor via the
     * {@code AURIGA_PRODUCT} BuildConfig field (see app/build.gradle). Each
     * flavor produces its own APK with its own applicationId, app name, and
     * accent color, so all four sub-apps can be installed side by side.
     */
    public static Product CURRENT_PRODUCT = resolveProduct();

    private static Product resolveProduct() {
        try {
            return Product.valueOf(BuildConfig.AURIGA_PRODUCT);
        } catch (IllegalArgumentException | NullPointerException e) {
            return Product.NAVI;
        }
    }

    // --- LICENSE TIERS ---
    public enum Tier {
        FREE,        // Basic ground-level navigation
        PREMIUM,     // Unlocks TruePath, SkyShield, AuraTextures
        DEVELOPER,   // Full SDK access for 1 target
        COMMERCIAL   // Enterprise deployment + SLA
    }
    public static Tier CURRENT_TIER = Tier.FREE;

    // --- FEATURE FLAGS ---

    /**
     * TruePath: Sub-20cm distance triangulation.
     * Unlocked by: Premium, Developer, Commercial.
     */
    public static boolean hasTruePath() {
        return CURRENT_TIER != Tier.FREE;
    }

    /**
     * SkyShield: Overhang and aerial obstacle detection.
     * Unlocked by: Premium, Developer, Commercial.
     */
    public static boolean hasSkyShield() {
        return CURRENT_TIER != Tier.FREE;
    }

    /**
     * AuraTextures: Material-specific acoustic signatures.
     * Unlocked by: Premium, Developer, Commercial.
     */
    public static boolean hasAuraTextures() {
        return CURRENT_TIER != Tier.FREE;
    }

    /**
     * GhostAnchor: Visual SLAM stabilization.
     * Unlocked by: Premium, Developer, Commercial.
     */
    public static boolean hasGhostAnchor() {
        return CURRENT_TIER != Tier.FREE;
    }

    /**
     * DrakoVoice: Inclusive voice assistance.
     * Available to all tiers.
     */
    public static boolean hasDrakoVoice() {
        return true; // Core brand feature
    }

    /**
     * IndustrialAlert: Specialized warehouse safety logic.
     * Unlocked by: Industrial Product variant.
     */
    public static boolean hasIndustrialAlert() {
        return CURRENT_PRODUCT == Product.INDUSTRIAL;
    }

    /**
     * FallDetection: Specialized Sentinel health logic.
     * Unlocked by: Sentinel Product variant.
     */
    public static boolean hasFallDetection() {
        return CURRENT_PRODUCT == Product.SENTINEL;
    }

    // --- PHASE 1-7 VARIANT FEATURE GATES ---
    //
    // These gates decide which Phase-1+ modules are wired into a given
    // product's APK. They are driven purely by CURRENT_PRODUCT so the
    // same core engine can ship as four specialised sub-apps (Navi full
    // suite, Sentinel security focus, Aero mobility focus, Industrial
    // scanning focus) without parallel code forks. Each Phase checks
    // the relevant flag before instantiating its own activity /
    // detector / download manager, so unused features drop out of the
    // build path entirely and do not consume RAM, model weights, or
    // permissions on variants that do not need them.

    /**
     * TargetLocator: Object-Locator-style "find my thing" narration.
     * User selects COCO classes; the camera pipeline narrates bearing
     * and distance for every instance on screen. Ships in Navi (full
     * suite) and Aero (mobility); Industrial gets the detector later
     * as a barcode-aid; Sentinel does not need a live object finder.
     */
    public static boolean hasTargetLocator() {
        return CURRENT_PRODUCT == Product.NAVI
                || CURRENT_PRODUCT == Product.AERO;
    }

    /**
     * OCR: camera-over-text reading via PaddleOCR + Piper TTS.
     * Navi uses it for signage / menus / labels; Industrial uses it
     * for package labels, work orders, and shipping barcodes. Sentinel
     * and Aero do not ship an OCR surface.
     */
    public static boolean hasOCR() {
        return CURRENT_PRODUCT == Product.NAVI
                || CURRENT_PRODUCT == Product.INDUSTRIAL;
    }

    /**
     * BookReader: EPUB / PDF / scanned-page voice reading.
     * Navi only. Security, mobility, and warehouse variants do not
     * bundle the parser or the extra voices; keeps their APKs small.
     */
    public static boolean hasBookReader() {
        return CURRENT_PRODUCT == Product.NAVI;
    }

    /**
     * SceneDescription: template-based + optional Moondream-2 caption.
     * Navi only today; may extend to Sentinel for "what is the camera
     * seeing at the front door" in a future phase.
     */
    public static boolean hasSceneDescription() {
        return CURRENT_PRODUCT == Product.NAVI;
    }

    /**
     * FaceRecognition: local InsightFace ArcFace gallery.
     * Sentinel is the primary target (security / known-visitor
     * recognition). Navi also ships it for "who is in front of me"
     * accessibility prompts. No uploads; gallery stays on-device.
     */
    public static boolean hasFaceRecognition() {
        return CURRENT_PRODUCT == Product.NAVI
                || CURRENT_PRODUCT == Product.SENTINEL;
    }

    /**
     * BarcodeScan: ZXing + Open Food Facts mirror.
     * Industrial flavor's core scanning loop; Navi also ships it for
     * grocery / product identification in the reading surface.
     */
    public static boolean hasBarcodeScan() {
        return CURRENT_PRODUCT == Product.NAVI
                || CURRENT_PRODUCT == Product.INDUSTRIAL;
    }

    // --- BRANDING ---
    public static final String COMPANY_NAME = "DrakoSanctis";
    public static final String TAGLINE = "See Beyond Sight";
    public static final String VERSION = "1.0.0";
}
