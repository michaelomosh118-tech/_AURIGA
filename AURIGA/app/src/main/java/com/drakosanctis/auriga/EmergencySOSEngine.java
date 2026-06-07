package com.drakosanctis.auriga;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SmsManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * EmergencySOSEngine™ — Session 8 (Phase 1).
 *
 * <p>Implements {@link AurigaInterfaces.IEmergencySOSEngine}.
 *
 * <h3>Trigger → action sequence</h3>
 * <pre>
 *   trigger(description)
 *     │
 *     ├─ 1. Speak environment description + "Calling [contact]" (EMERGENCY priority)
 *     │
 *     ├─ 2. Dial pre-configured emergency contact (ACTION_CALL intent, no internet)
 *     │        Requires CALL_PHONE permission.
 *     │
 *     ├─ 3. After 2-second delay: send SMS "I need help. [GPS coords]. [description]"
 *     │        Requires SEND_SMS permission.
 *     │        Silently skipped if permission absent or network unavailable.
 *     │
 *     └─ 4. Fire haptic SOS pattern continuously (3 cycles) so the phone can
 *              be heard/felt by a bystander if the call fails.
 * </pre>
 *
 * <h3>Emergency contact storage</h3>
 * Contact number and name are persisted to the {@value PREFS_NAME} shared
 * preferences file under keys {@value KEY_CONTACT_NUMBER} and
 * {@value KEY_CONTACT_NAME}. Auriga's onboarding flow calls
 * {@link #setContact(String, String)}; Butler re-reads it on every trigger.
 *
 * <h3>Cancellation</h3>
 * Calling {@link #cancel()} within 4 seconds of {@link #trigger(String)}
 * speaks "SOS cancelled" and suppresses the SMS dispatch (the phone call
 * cannot be cancelled programmatically once launched by the OS dialer).
 *
 * <h3>Thread safety</h3>
 * {@link #trigger(String)} and {@link #cancel()} are safe to call from any
 * thread. Internal async work runs on the main looper via {@link Handler}.
 */
public class EmergencySOSEngine implements AurigaInterfaces.IEmergencySOSEngine {

    private static final String TAG = "EmergencySOSEngine";

    static final String PREFS_NAME          = "auriga_sos_prefs";
    static final String KEY_CONTACT_NUMBER  = "sos_contact_number";
    static final String KEY_CONTACT_NAME    = "sos_contact_name";

    private static final long   SMS_DELAY_MS    = 2_000L;
    private static final int    HAPTIC_CYCLES   = 3;
    private static final long   HAPTIC_CYCLE_MS = 2_000L;

    private final Context                           appCtx;
    private final AurigaInterfaces.IOutputLayer     output;

    private volatile boolean active    = false;
    private volatile boolean cancelled = false;

    private final Handler handler = new Handler(Looper.getMainLooper());

    // ─────────────────────────────────────────────────────────────────────────
    // Construction
    // ─────────────────────────────────────────────────────────────────────────

    public EmergencySOSEngine(Context context, AurigaInterfaces.IOutputLayer output) {
        this.appCtx = context.getApplicationContext();
        this.output = output;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IEmergencySOSEngine
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Trigger the full SOS sequence.
     *
     * @param environmentDescription A natural-language description of the
     *                               user's environment (from SpatialMemoryEngine
     *                               or a simple last-detection summary). Included
     *                               in the spoken announcement and the SMS body.
     */
    @Override
    public void trigger(String environmentDescription) {
        if (active) {
            Log.w(TAG, "trigger() called while already active — ignoring");
            return;
        }
        active    = true;
        cancelled = false;

        String contactName   = getContactName();
        String contactNumber = getContactNumber();

        Log.w(TAG, "SOS triggered — contact=" + contactName + " env=" + environmentDescription);

        // ── Step 1: Speak ──────────────────────────────────────────────────
        String announcement = buildAnnouncement(environmentDescription, contactName);
        output.speak(announcement, AurigaInterfaces.OutputPriority.EMERGENCY);

        // ── Step 2: Dial ──────────────────────────────────────────────────
        dialContact(contactNumber);

        // ── Step 3: SMS (after brief delay so the call connects first) ────
        handler.postDelayed(() -> {
            if (!cancelled) sendSms(contactNumber, environmentDescription);
        }, SMS_DELAY_MS);

        // ── Step 4: Haptic SOS (3 cycles) ────────────────────────────────
        for (int i = 0; i < HAPTIC_CYCLES; i++) {
            final int cycle = i;
            handler.postDelayed(() -> {
                if (!cancelled) {
                    output.haptic(AurigaInterfaces.HapticPattern.SOS,
                            AurigaInterfaces.HapticZone.ALL);
                }
            }, cycle * HAPTIC_CYCLE_MS);
        }

        // Auto-reset active flag after all steps complete
        handler.postDelayed(() -> active = false, SMS_DELAY_MS + HAPTIC_CYCLES * HAPTIC_CYCLE_MS);
    }

    @Override
    public void setContact(String phoneNumber, String contactName) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            Log.w(TAG, "setContact: empty number ignored");
            return;
        }
        SharedPreferences prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
             .putString(KEY_CONTACT_NUMBER, phoneNumber.trim())
             .putString(KEY_CONTACT_NAME,   contactName != null ? contactName.trim() : "Emergency contact")
             .apply();
        Log.i(TAG, "contact set: " + contactName + " → " + phoneNumber);
    }

    @Override
    public String getContactName() {
        SharedPreferences prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_CONTACT_NAME, "your emergency contact");
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public void cancel() {
        if (!active) return;
        cancelled = true;
        active    = false;
        output.speak("S O S cancelled.", AurigaInterfaces.OutputPriority.HIGH);
        Log.i(TAG, "SOS cancelled by user");
    }

    @Override
    public boolean selfTest(Context ctx) {
        // Verify prefs are writable and contact API works
        setContact("0000000000", "Test Contact");
        boolean ok = "Test Contact".equals(getContactName());
        // Clean up test entry
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
           .edit().remove(KEY_CONTACT_NAME).remove(KEY_CONTACT_NUMBER).apply();
        Log.i(TAG, "selfTest → " + ok);
        return ok;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String getContactNumber() {
        SharedPreferences prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_CONTACT_NUMBER, "");
    }

    private String buildAnnouncement(String env, String contactName) {
        StringBuilder sb = new StringBuilder("Emergency. Calling ");
        sb.append(contactName).append(". ");
        if (env != null && !env.trim().isEmpty()) {
            sb.append("Current environment: ").append(env).append(". ");
        }
        sb.append("Stay calm. Help is on the way.");
        return sb.toString();
    }

    private void dialContact(String number) {
        if (number == null || number.isEmpty()) {
            output.speak("No emergency contact set. Please set up a contact in Auriga settings.",
                    AurigaInterfaces.OutputPriority.EMERGENCY);
            Log.e(TAG, "dial: no contact number configured");
            return;
        }
        if (ContextCompat.checkSelfPermission(appCtx, Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "dial: CALL_PHONE permission not granted");
            output.speak("Cannot call — phone permission not granted. Sending message instead.",
                    AurigaInterfaces.OutputPriority.EMERGENCY);
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_CALL,
                    Uri.parse("tel:" + Uri.encode(number)));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appCtx.startActivity(intent);
            Log.i(TAG, "dialing: " + number);
        } catch (Throwable t) {
            Log.e(TAG, "dial failed", t);
        }
    }

    private void sendSms(String number, String env) {
        if (number == null || number.isEmpty()) return;
        if (ContextCompat.checkSelfPermission(appCtx, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "sendSms: SEND_SMS permission not granted");
            return;
        }
        try {
            String gps      = getLastGpsString();
            String body     = buildSmsBody(env, gps);
            SmsManager sms  = SmsManager.getDefault();
            sms.sendTextMessage(number, null, body, null, null);
            Log.i(TAG, "SMS sent to " + number);
        } catch (Throwable t) {
            Log.e(TAG, "sendSms failed", t);
        }
    }

    /** Returns a short GPS string or "location unavailable" without blocking. */
    @SuppressWarnings("MissingPermission")
    private String getLastGpsString() {
        try {
            LocationManager lm = (LocationManager) appCtx.getSystemService(
                    Context.LOCATION_SERVICE);
            if (lm == null) return "location unavailable";
            Location loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (loc == null) loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (loc == null) return "location unavailable";
            return String.format("%.5f,%.5f", loc.getLatitude(), loc.getLongitude());
        } catch (Throwable t) {
            return "location unavailable";
        }
    }

    private static String buildSmsBody(String env, String gps) {
        StringBuilder sb = new StringBuilder("I need help. ");
        if (!"location unavailable".equals(gps)) {
            sb.append("Location: https://maps.google.com/?q=").append(gps).append(". ");
        }
        if (env != null && !env.trim().isEmpty()) {
            String trimmed = env.trim();
            // Keep SMS below 160 chars — truncate environment description if needed
            if (trimmed.length() > 80) trimmed = trimmed.substring(0, 80) + "…";
            sb.append(trimmed).append(". ");
        }
        sb.append("Sent by Auriga.");
        return sb.toString();
    }
}
