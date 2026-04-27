package com.drakosanctis.auriga;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Thin SharedPreferences wrapper that persists which Object-Locator
 * categories the user wants Auriga to react to. Mirrors the web app's
 * {@code auriga-locator-targets} localStorage entry conceptually -- the
 * APK uses Google ML Kit's bundled object detector which classifies
 * detections into 5 broad offline categories (Fashion good, Home good,
 * Food, Place, Plant), so the picker exposes those plus a dedicated
 * "ANY OBJECT" wildcard.
 *
 * Storage shape: a simple comma-separated string under
 * {@code auriga_targets} inside the existing {@link MainActivity#PREFS_NAME}
 * SharedPreferences file. CSV instead of a Set keeps the on-disk format
 * trivially inspectable by adb shell run-as -- useful for QA.
 *
 * Default behaviour (no entry yet): "ANY OBJECT" ON. This preserves the
 * pre-Targets behaviour where every detection could trigger haptic +
 * voice. The user must explicitly narrow the list to opt in to filtering.
 */
public final class TargetStore {

    /** SharedPreferences key. Lives inside {@link MainActivity#PREFS_NAME}. */
    public static final String PREF_KEY = "auriga_targets";

    /** Wildcard sentinel meaning "trigger on any prominent detection". */
    public static final String CATEGORY_ANY = "_any_";

    /**
     * The 5 ML Kit bundled-classifier labels. Surfaced verbatim so the
     * filter check can do a case-insensitive equality against
     * {@code DetectedObject.Label.getText()} without remapping.
     */
    public static final String[] CATEGORIES = new String[] {
            "Fashion good",
            "Home good",
            "Food",
            "Place",
            "Plant"
    };

    private TargetStore() {}

    /**
     * Read the current selection. Returns a fresh, mutable set; callers
     * may add / remove without affecting persisted state. Empty set is
     * never returned -- a missing entry is normalised to ANY.
     */
    public static Set<String> read(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(
                MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String csv = prefs.getString(PREF_KEY, null);
        Set<String> out = new LinkedHashSet<>();
        if (csv == null || csv.trim().isEmpty()) {
            out.add(CATEGORY_ANY);
            return out;
        }
        for (String token : csv.split(",")) {
            String t = token.trim();
            if (!t.isEmpty()) out.add(t);
        }
        if (out.isEmpty()) out.add(CATEGORY_ANY);
        return out;
    }

    /** Persist the selection. Empty set is normalised to ANY. */
    public static void write(Context ctx, Set<String> selection) {
        SharedPreferences prefs = ctx.getSharedPreferences(
                MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> normalised = (selection == null || selection.isEmpty())
                ? new HashSet<>(Arrays.asList(CATEGORY_ANY))
                : selection;
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String s : normalised) {
            if (s == null || s.trim().isEmpty()) continue;
            if (!first) sb.append(',');
            sb.append(s.trim());
            first = false;
        }
        prefs.edit().putString(PREF_KEY, sb.toString()).apply();
    }

    /**
     * Returns true if a detection labelled {@code label} should trigger
     * Auriga's haptic + voice feedback under the current selection.
     * Wildcard ANY matches everything (including unclassified detections
     * where label is null/empty). Otherwise we require a case-insensitive
     * equality match against one of the persisted categories.
     */
    public static boolean matches(Set<String> selection, String label) {
        if (selection == null || selection.isEmpty()) return true;
        if (selection.contains(CATEGORY_ANY)) return true;
        if (label == null) return false;
        for (String chosen : selection) {
            if (chosen != null && chosen.equalsIgnoreCase(label)) return true;
        }
        return false;
    }
}
