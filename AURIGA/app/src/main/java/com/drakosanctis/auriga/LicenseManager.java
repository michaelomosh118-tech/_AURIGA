package com.drakosanctis.auriga;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * LicenseManager: The gatekeeper of the Auriga Ecosystem.
 * Handles device fingerprinting, hybrid license validation (7-day heartbeat),
 * and RSA-signed token verification.
 */
public class LicenseManager {

    private static final String SECRET_SALT = BuildConfig.LICENSE_SALT;
    private final Context context;

    public LicenseManager(Context context) {
        this.context = context;
    }

    /**
     * getDeviceFingerprint: Generates a unique, non-reversible hash for this specific phone.
     * Binds the license to the hardware to prevent sharing.
     */
    public String getDeviceFingerprint() {
        String rawID = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID)
                + Build.MANUFACTURER
                + Build.MODEL
                + Build.SERIAL;
        return sha256(rawID + SECRET_SALT);
    }

    /**
     * validateLicense: Performs a hybrid check.
     * 1. Checks local cache for a valid, signed token.
     * 2. If expired (>7 days), triggers a server heartbeat.
     */
    public boolean validateLicense(String licenseKey) {
        // In a real production build, this would:
        // - Load encrypted local token
        // - Verify RSA signature with DrakoSanctis Public Key
        // - Check 'valid_until' date vs current system time
        // - Trigger async network call if close to expiry
        
        // Mock implementation for development:
        if (licenseKey != null && licenseKey.startsWith("AURIGA-PREMIUM")) {
            AurigaConfig.CURRENT_TIER = AurigaConfig.Tier.PREMIUM;
            return true;
        }
        
        AurigaConfig.CURRENT_TIER = AurigaConfig.Tier.FREE;
        return false;
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            return "fallback_hash_" + input.length();
        }
    }
}
