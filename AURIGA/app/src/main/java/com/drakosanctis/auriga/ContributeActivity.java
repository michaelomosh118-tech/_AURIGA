package com.drakosanctis.auriga;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

/**
 * ContributeActivity — Drawer "Contribute" destination.
 *
 * Explains the calibration-profile submission flow and the
 * SDK / partner interest path. The Submit Calibration button
 * launches the FeedbackActivity with category pre-set to
 * "calibration"; the SDK button routes to "idea".
 */
public class ContributeActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_contribute);

        Button back = findViewById(R.id.contribute_back);
        if (back != null) {
            back.setOnClickListener(v -> finish());
        }

        Button submitCalibration = findViewById(R.id.btn_submit_calibration);
        if (submitCalibration != null) {
            submitCalibration.setOnClickListener(v -> launchFeedback("calibration",
                    "Calibration Profile Submission\n\nDevice: " + android.os.Build.MODEL
                    + "\nManufacturer: " + android.os.Build.MANUFACTURER
                    + "\n\n[Auriga will attach the anchor triplets automatically]"));
        }

        Button sdkInterest = findViewById(R.id.btn_sdk_interest);
        if (sdkInterest != null) {
            sdkInterest.setOnClickListener(v -> launchFeedback("idea",
                    "SDK / Partner Interest\n\nPlatform: \nCamera module: \nHFOV: \nIntended use case: "));
        }
    }

    private void launchFeedback(String category, String prefill) {
        try {
            Intent it = new Intent(this, FeedbackActivity.class);
            it.putExtra(FeedbackActivity.EXTRA_DIAGNOSTIC, "");
            it.putExtra(FeedbackActivity.EXTRA_PROFILE, "contribute:" + category);
            it.putExtra("prefill_message", prefill);
            it.putExtra("prefill_category", category);
            startActivity(it);
        } catch (Throwable t) {
            Toast.makeText(this,
                    "Feedback unavailable: " + t.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        finish();
    }
}
