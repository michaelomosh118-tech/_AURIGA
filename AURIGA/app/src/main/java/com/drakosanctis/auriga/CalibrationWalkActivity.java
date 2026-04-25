package com.drakosanctis.auriga;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 10-point Calibration Walk.
 *
 * <p>Steps the user through the canonical AurigaNavi calibration choreography
 * (different distances, angles, indoor + outdoor lighting). Each step shows
 * a fresh title and instruction; tapping CAPTURE advances the dot indicator.
 * Reaching step 10 sets {@code calibration_walk_completed = true} in
 * {@link MainActivity#PREFS_NAME}, which is how Send Feedback knows the
 * device has been baselined.
 *
 * <p>This activity is intentionally <strong>presentational</strong> — it does
 * not drive the optical pipeline directly. The real per-frame calibration
 * happens inside {@link CalibrationManager} during normal HUD use; the walk
 * exists to make sure new users actually perform that motion sequence
 * before submitting accuracy reports.
 */
public class CalibrationWalkActivity extends Activity {

    static final String PREF_WALK_DONE = "calibration_walk_completed";

    /** Title + instructions per step. Length must equal {@link #STEPS}. */
    private static final String[] STEP_TITLES = {
            "Stand 1 m from a flat wall",
            "Step back to 2 m",
            "Step back to 3 m",
            "Tilt the phone 15° downward",
            "Tilt the phone 15° upward",
            "Pan slowly 30° to the left",
            "Pan slowly 30° to the right",
            "Move under bright indoor light",
            "Move under low-light conditions",
            "Final pose: head height, level"
    };
    private static final String[] STEP_INSTRUCTIONS = {
            "Hold the phone vertical at chest height. Centre the wall in the cyan reticle. Hold steady and tap CAPTURE.",
            "Walk back so you are roughly 2 m from the same wall. Re-centre and tap CAPTURE.",
            "Walk back to roughly 3 m. Keep the same vertical orientation, then tap CAPTURE.",
            "Tip the top of the phone toward you about 15° so the camera points slightly down. Hold steady and tap CAPTURE.",
            "Tip the bottom of the phone toward you about 15° so the camera points slightly up. Hold steady and tap CAPTURE.",
            "Without moving your feet, slowly pan the phone 30° to the left. Hold for two seconds and tap CAPTURE.",
            "Pan back across to 30° right of centre. Hold for two seconds and tap CAPTURE.",
            "Move to a brightly-lit area (window or overhead light), point at any flat surface and tap CAPTURE.",
            "Move to a dim area or shadow. Wait one second for exposure to settle, then tap CAPTURE.",
            "Return to your usual carrying pose — phone vertical, at head height, perfectly level — then tap CAPTURE to finish."
    };
    private static final int STEPS = STEP_TITLES.length;

    private TextView counter;
    private TextView title;
    private TextView instructions;
    private LinearLayout dots;
    private Button captureBtn;
    private int currentStep = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_calibration_walk);

        counter = findViewById(R.id.calib_step_counter);
        title = findViewById(R.id.calib_title);
        instructions = findViewById(R.id.calib_instructions);
        dots = findViewById(R.id.calib_dots);
        captureBtn = findViewById(R.id.calib_capture);

        Button back = findViewById(R.id.calib_back);
        if (back != null) back.setOnClickListener(v -> finish());

        buildDots();
        renderStep();

        if (captureBtn != null) {
            captureBtn.setOnClickListener(v -> advance());
        }
    }

    private void buildDots() {
        if (dots == null) return;
        dots.removeAllViews();
        int sizePx = (int) (10 * getResources().getDisplayMetrics().density);
        int marginPx = (int) (4 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < STEPS; i++) {
            View dot = new View(this);
            ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(sizePx, sizePx);
            lp.leftMargin = marginPx;
            lp.rightMargin = marginPx;
            dot.setLayoutParams(lp);
            dot.setBackground(buildDot(false));
            dots.addView(dot);
        }
    }

    private GradientDrawable buildDot(boolean filled) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(filled
                ? getResources().getColor(R.color.auriga_cyan)
                : getResources().getColor(R.color.cyan_glow_15));
        d.setStroke(filled ? 0 : (int) (1 * getResources().getDisplayMetrics().density),
                getResources().getColor(R.color.glass_stroke));
        return d;
    }

    private void renderStep() {
        if (currentStep >= STEPS) currentStep = STEPS - 1;
        if (counter != null) counter.setText("STEP " + (currentStep + 1) + " OF " + STEPS);
        if (title != null) title.setText(STEP_TITLES[currentStep]);
        if (instructions != null) instructions.setText(STEP_INSTRUCTIONS[currentStep]);
        if (dots != null) {
            for (int i = 0; i < dots.getChildCount(); i++) {
                dots.getChildAt(i).setBackground(buildDot(i <= currentStep));
            }
        }
        if (captureBtn != null) {
            captureBtn.setText(currentStep == STEPS - 1 ? "FINISH" : "CAPTURE");
        }
    }

    private void advance() {
        if (currentStep < STEPS - 1) {
            currentStep++;
            renderStep();
            return;
        }
        // Final step: persist the completion flag and bow out.
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putBoolean(PREF_WALK_DONE, true).apply();
        Toast.makeText(this,
                "Calibration walk complete. Send Feedback is now unlocked.",
                Toast.LENGTH_LONG).show();
        finish();
    }
}
