package com.drakosanctis.auriga;

import android.content.SharedPreferences;
import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * HelpActivity: short user guide reachable from the hamburger drawer.
 *
 * <p>0.4e adds a "Was this page helpful?" thumbs row at the bottom. The
 * vote is persisted in {@link MainActivity#PREFS_NAME} so we can roll up
 * a per-page satisfaction signal without having to push the user through
 * the full Send Feedback flow. Once a vote has been recorded, the row is
 * replaced with a "Thanks — recorded" acknowledgement so the user knows
 * the tap landed and is not re-prompted on every visit.
 */
public class HelpActivity extends Activity {

    /** Persisted vote: 0 = none yet, 1 = helpful, -1 = not helpful. */
    static final String PREF_HELP_VOTE = "help_page_vote";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_help);

        Button back = findViewById(R.id.help_back);
        if (back != null) back.setOnClickListener(v -> finish());

        wireFeedbackRow();
    }

    private void wireFeedbackRow() {
        SharedPreferences prefs =
                getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        int existing = prefs.getInt(PREF_HELP_VOTE, 0);

        TextView prompt = findViewById(R.id.help_feedback_prompt);
        LinearLayout row = findViewById(R.id.help_feedback_row);
        Button up = findViewById(R.id.help_thumbs_up);
        Button down = findViewById(R.id.help_thumbs_down);

        // Already voted -> swap the row for an acknowledgement so we do
        // not nag, but leave the prompt visible as the section header.
        if (existing != 0) {
            applyAcknowledged(prompt, row, existing);
            return;
        }

        if (up != null) {
            up.setOnClickListener(v -> {
                prefs.edit().putInt(PREF_HELP_VOTE, 1).apply();
                applyAcknowledged(prompt, row, 1);
                Toast.makeText(this,
                        "Thanks — glad this helped.",
                        Toast.LENGTH_SHORT).show();
            });
        }
        if (down != null) {
            down.setOnClickListener(v -> {
                prefs.edit().putInt(PREF_HELP_VOTE, -1).apply();
                applyAcknowledged(prompt, row, -1);
                Toast.makeText(this,
                        "Noted — open Send Feedback to tell us what's missing.",
                        Toast.LENGTH_LONG).show();
            });
        }
    }

    private void applyAcknowledged(TextView prompt, LinearLayout row, int vote) {
        if (row != null) row.setVisibility(View.GONE);
        if (prompt != null) {
            prompt.setText(vote > 0
                    ? "Thanks — recorded as helpful."
                    : "Thanks — recorded as not helpful.");
        }
    }
}
