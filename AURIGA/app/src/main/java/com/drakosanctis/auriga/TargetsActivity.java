package com.drakosanctis.auriga;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * TargetsActivity: HUD-themed picker that mirrors the web Object Locator's
 * target list. The user picks which classes Auriga's per-frame ML Kit
 * object detector should treat as "alarming" -- only matching detections
 * trigger haptic pulses + DrakoVoice readouts on the live HUD.
 *
 * UX:
 *   - Top toggle: "ANY OBJECT" wildcard (default ON for new installs).
 *     When ON, every prominent detection triggers feedback regardless of
 *     class. Selecting any individual category implicitly turns this OFF.
 *   - Below: 5 chip-style toggles for the ML Kit bundled-classifier
 *     labels (Fashion good / Home good / Food / Place / Plant). These
 *     are the actual labels the offline classifier emits, so what the
 *     user picks is exactly what gets matched against incoming
 *     detections in {@link MainActivity}.
 *   - Honest subtitle clarifies the offline trade-off vs the web
 *     locator's heavier 80-class COCO-SSD model -- no false promises.
 *   - Persists immediately to {@link TargetStore} on every toggle so
 *     "back" / kill / swipe-away always saves the user's intent.
 */
public class TargetsActivity extends Activity {

    private final Map<String, Button> chips = new LinkedHashMap<>();
    private Button anyChip;
    private Set<String> selection;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_targets);

        selection = new LinkedHashSet<>(TargetStore.read(this));

        TextView title = findViewById(R.id.targets_title);
        TextView subtitle = findViewById(R.id.targets_subtitle);
        TextView sectionLabel = findViewById(R.id.targets_section_categories);
        LinearLayout chipContainer = findViewById(R.id.targets_chip_container);
        Button clearBtn = findViewById(R.id.targets_clear);
        Button doneBtn = findViewById(R.id.targets_done);
        anyChip = findViewById(R.id.targets_any_chip);

        if (title != null) title.setText("OBJECT TARGETS");
        if (subtitle != null) subtitle.setText(
                "Pick what AurigaNavi should react to. Only matching detections "
                        + "buzz the haptics and speak via DrakoVoice. Runs fully "
                        + "offline using ML Kit's bundled detector (5 broad "
                        + "categories). The web Object Locator uses 80 COCO "
                        + "classes -- different model, same idea.");
        if (sectionLabel != null) sectionLabel.setText("ML KIT CATEGORIES");

        if (anyChip != null) {
            anyChip.setText("ANY OBJECT  ·  match every detection");
            anyChip.setOnClickListener(v -> {
                selection.clear();
                selection.add(TargetStore.CATEGORY_ANY);
                persistAndRefresh();
            });
        }

        if (chipContainer != null) {
            for (String cat : TargetStore.CATEGORIES) {
                Button chip = buildChip(cat);
                chips.put(cat, chip);
                chipContainer.addView(chip);
                chip.setOnClickListener(v -> toggleCategory(cat));
            }
        }

        if (clearBtn != null) {
            clearBtn.setText("CLEAR ALL");
            clearBtn.setOnClickListener(v -> {
                selection.clear();
                selection.add(TargetStore.CATEGORY_ANY);
                persistAndRefresh();
                Toast.makeText(this, "Reset to ANY OBJECT", Toast.LENGTH_SHORT).show();
            });
        }
        if (doneBtn != null) {
            doneBtn.setText("DONE");
            doneBtn.setOnClickListener(v -> finish());
        }

        refreshChipStates();
    }

    private void toggleCategory(String cat) {
        // Picking any specific category means we're no longer "any".
        selection.remove(TargetStore.CATEGORY_ANY);
        if (selection.contains(cat)) {
            selection.remove(cat);
        } else {
            selection.add(cat);
        }
        // Empty after a deselect -> normalise back to ANY so feedback is
        // never silently turned off without the user's knowledge.
        if (selection.isEmpty()) {
            selection.add(TargetStore.CATEGORY_ANY);
        }
        persistAndRefresh();
    }

    private void persistAndRefresh() {
        TargetStore.write(this, selection);
        refreshChipStates();
    }

    private void refreshChipStates() {
        boolean anySelected = selection.contains(TargetStore.CATEGORY_ANY);
        if (anyChip != null) styleChip(anyChip, anySelected);
        for (Map.Entry<String, Button> e : chips.entrySet()) {
            styleChip(e.getValue(), !anySelected && selection.contains(e.getKey()));
        }
    }

    private Button buildChip(String label) {
        Button b = new Button(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        b.setAllCaps(true);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setLetterSpacing(0.06f);
        b.setMinHeight(dp(48));
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        return b;
    }

    private void styleChip(Button b, boolean selected) {
        if (b == null) return;
        if (selected) {
            b.setBackgroundResource(R.drawable.target_chip_selected_bg);
            b.setTextColor(Color.parseColor("#001A1F"));
        } else {
            b.setBackgroundResource(R.drawable.target_chip_bg);
            b.setTextColor(Color.parseColor("#9FE9FF"));
        }
        b.setBackgroundTintList((ColorStateList) null);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
