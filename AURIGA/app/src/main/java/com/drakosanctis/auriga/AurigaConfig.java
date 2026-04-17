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
    public static Product CURRENT_PRODUCT = Product.NAVI;

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

    // --- BRANDING ---
    public static final String COMPANY_NAME = "DrakoSanctis";
    public static final String TAGLINE = "See Beyond Sight";
    public static final String VERSION = "1.0.0";
}
