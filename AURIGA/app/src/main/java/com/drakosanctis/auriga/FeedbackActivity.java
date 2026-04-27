package com.drakosanctis.auriga;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.regex.Pattern;

/**
 * Send Feedback. Auto-attaches the device profile, app version, last
 * diagnostic snapshot and selected category, then POSTs the JSON to
 * the Netlify function (which sends a Gmail notification, files a
 * GitHub issue, and emails the user a ticket-ID acknowledgement).
 *
 * <p>Submit is gated by the calibration-walk flag — if the user has not
 * completed the walk, the activity hard-redirects them to
 * {@link CalibrationWalkActivity} instead of accepting the submission.
 *
 * <p>Email is required when the category is "bug" or "support" (the
 * "Need help" radio). Idea + Other accept anonymous submissions but the
 * UI warns that we can't reply or send a ticket reference without one.
 */
public class FeedbackActivity extends Activity {

    public static final String EXTRA_DIAGNOSTIC = "extra_diagnostic_snapshot";
    public static final String EXTRA_PROFILE = "extra_profile_label";

    private static final Pattern EMAIL_RE =
            Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final FeedbackSubmitter submitter = new FeedbackSubmitter();
    private String diagSnapshot = "";
    private String profileLabel = "(unknown)";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // D3 gate: refuse Submit access until the calibration walk is done.
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        boolean walkDone = prefs.getBoolean(CalibrationWalkActivity.PREF_WALK_DONE, false);
        if (!walkDone) {
            Toast.makeText(this,
                    "Run the 10-point calibration walk first — it only takes a minute.",
                    Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, CalibrationWalkActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_feedback);

        Intent in = getIntent();
        if (in != null) {
            diagSnapshot = safeIntentString(in, EXTRA_DIAGNOSTIC);
            profileLabel = safeIntentString(in, EXTRA_PROFILE);
            if (profileLabel == null || profileLabel.isEmpty()) profileLabel = "(unknown)";
        }

        Button back = findViewById(R.id.fb_back);
        if (back != null) back.setOnClickListener(v -> finish());

        TextView attached = findViewById(R.id.fb_attached_summary);
        if (attached != null) {
            attached.setText(buildAttachedSummary());
        }

        // Update the email label as the category changes so the user
        // sees "(required)" vs "(optional)" without having to submit
        // and bounce off a validation error.
        RadioGroup catGroup = findViewById(R.id.fb_category);
        if (catGroup != null) {
            catGroup.setOnCheckedChangeListener((g, id) -> updateEmailLabel());
        }
        updateEmailLabel();

        Button submit = findViewById(R.id.fb_submit);
        if (submit != null) {
            submit.setOnClickListener(v -> doSubmit(submit));
        }

        // Opportunistically flush anything that's been queued from a
        // previous offline attempt the moment this screen opens — gives
        // the user a "your earlier submission went through" moment.
        submitter.flushQueue(this);
    }

    private void updateEmailLabel() {
        TextView label = findViewById(R.id.fb_email_label);
        if (label == null) return;
        String cat = currentCategory();
        boolean required = "bug".equals(cat) || "support".equals(cat);
        label.setText(required
                ? "Reply-to email (required)"
                : "Reply-to email (optional, but you won't get a ticket reference)");
    }

    private String currentCategory() {
        RadioGroup catGroup = findViewById(R.id.fb_category);
        if (catGroup == null) return "other";
        int id = catGroup.getCheckedRadioButtonId();
        if (id == R.id.fb_cat_bug)      return "bug";
        if (id == R.id.fb_cat_accuracy) return "accuracy";
        if (id == R.id.fb_cat_support)  return "support";
        if (id == R.id.fb_cat_idea)     return "idea";
        return "other";
    }

    private void doSubmit(Button submit) {
        EditText messageBox = findViewById(R.id.fb_message);
        EditText emailBox = findViewById(R.id.fb_email);
        String message = messageBox != null ? messageBox.getText().toString().trim() : "";
        String email = emailBox != null ? emailBox.getText().toString().trim() : "";
        if (message.isEmpty() || message.length() < 5) {
            Toast.makeText(this, "Please describe the issue (at least a few words).",
                    Toast.LENGTH_SHORT).show();
            if (messageBox != null) messageBox.requestFocus();
            return;
        }
        String category = currentCategory();
        boolean emailRequired = "bug".equals(category) || "support".equals(category);
        if (emailRequired && email.isEmpty()) {
            Toast.makeText(this,
                    "A reply-to email is required for " + category + " reports.",
                    Toast.LENGTH_LONG).show();
            if (emailBox != null) emailBox.requestFocus();
            return;
        }
        if (!email.isEmpty() && !EMAIL_RE.matcher(email).matches()) {
            Toast.makeText(this,
                    "That email address looks malformed — please double-check.",
                    Toast.LENGTH_LONG).show();
            if (emailBox != null) emailBox.requestFocus();
            return;
        }

        JSONObject payload = new JSONObject();
        try {
            payload.put("message", message);
            payload.put("email", email);
            payload.put("category", category);
            payload.put("product", BuildConfig.AURIGA_PRODUCT);
            payload.put("version", versionLabel());
            payload.put("device", Build.MANUFACTURER + " " + Build.MODEL
                    + " · Android " + Build.VERSION.RELEASE
                    + " (API " + Build.VERSION.SDK_INT + ")");
            payload.put("profile", profileLabel);
            payload.put("diagnostic", diagSnapshot == null ? "" : diagSnapshot);
            payload.put("ts", System.currentTimeMillis());
        } catch (Throwable t) {
            Toast.makeText(this,
                    "Failed to build payload: " + t.getMessage(),
                    Toast.LENGTH_LONG).show();
            return;
        }

        submit.setEnabled(false);
        submit.setText("SENDING…");
        submitter.submit(this, payload, (r) -> {
            submit.setEnabled(true);
            submit.setText("SUBMIT");
            if (r.ok) {
                String ticket = r.ticketId == null ? "(no ticket)" : r.ticketId;
                Toast.makeText(this, "Submitted — ticket " + ticket
                                + (email.isEmpty() ? "" : "\nConfirmation sent to " + email),
                        Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            if (r.queued) {
                Toast.makeText(this, r.detail, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            // Permanent failure — offer the mailto escape hatch instead
            // of leaving the user with nothing.
            Toast.makeText(this, r.detail + "  Opening your email app instead.",
                    Toast.LENGTH_LONG).show();
            try {
                Intent fallback = submitter.buildMailtoIntent(payload);
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(fallback);
            } catch (Throwable t) {
                Toast.makeText(this, "Couldn't open mail app: " + t.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private String buildAttachedSummary() {
        StringBuilder s = new StringBuilder();
        s.append("Product:    ").append(BuildConfig.AURIGA_PRODUCT).append('\n');
        s.append("Version:    ").append(versionLabel()).append('\n');
        s.append("Device:     ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        s.append("Android:    ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        s.append("Profile:    ").append(profileLabel).append('\n');
        s.append("Diagnostic: ");
        if (diagSnapshot == null || diagSnapshot.trim().isEmpty()) {
            s.append("(none captured yet)");
        } else {
            s.append('\n').append(diagSnapshot.trim());
        }
        int q = submitter.pendingCount(this);
        if (q > 0) {
            s.append("\nPending offline queue: ").append(q).append(" submission(s) waiting to send.");
        }
        return s.toString();
    }

    private String versionLabel() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName + " (" + info.versionCode + ")";
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String safeIntentString(Intent in, String key) {
        try {
            String v = in.getStringExtra(key);
            return v == null ? "" : v;
        } catch (Throwable t) {
            return "";
        }
    }
}
