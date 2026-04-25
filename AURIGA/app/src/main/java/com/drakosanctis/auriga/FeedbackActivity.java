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

/**
 * Send Feedback (D1 = C with mailto fallback). Auto-attaches the device
 * profile, app version, last diagnostic snapshot and selected category.
 *
 * <p>Submit is gated by the calibration-walk flag — if the user has not
 * completed the walk, the activity hard-redirects them to
 * {@link CalibrationWalkActivity} instead of accepting the submission.
 * This is the D3 default the agent recommended and prevents accuracy
 * complaints from un-baselined devices.
 *
 * <p>The Submit-Cancel-Toast UX runs on the main thread; the actual HTTP
 * POST is offloaded to {@link FeedbackSubmitter}.
 */
public class FeedbackActivity extends Activity {

    public static final String EXTRA_DIAGNOSTIC = "extra_diagnostic_snapshot";
    public static final String EXTRA_PROFILE = "extra_profile_label";

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

        Button submit = findViewById(R.id.fb_submit);
        if (submit != null) {
            submit.setOnClickListener(v -> doSubmit(submit));
        }
    }

    private void doSubmit(Button submit) {
        EditText messageBox = findViewById(R.id.fb_message);
        EditText emailBox = findViewById(R.id.fb_email);
        RadioGroup catGroup = findViewById(R.id.fb_category);
        String message = messageBox != null ? messageBox.getText().toString().trim() : "";
        String email = emailBox != null ? emailBox.getText().toString().trim() : "";
        if (message.isEmpty()) {
            Toast.makeText(this, "Please describe the issue first.", Toast.LENGTH_SHORT).show();
            return;
        }
        String category = "other";
        if (catGroup != null) {
            int id = catGroup.getCheckedRadioButtonId();
            if (id == R.id.fb_cat_bug) category = "bug";
            else if (id == R.id.fb_cat_accuracy) category = "accuracy";
            else if (id == R.id.fb_cat_idea) category = "idea";
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
        submitter.submit(this, payload, (ok, detail, usedFallback) -> {
            submit.setEnabled(true);
            submit.setText("SUBMIT");
            Toast.makeText(this, detail,
                    ok ? Toast.LENGTH_LONG : Toast.LENGTH_LONG).show();
            if (ok && !usedFallback) finish();
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
