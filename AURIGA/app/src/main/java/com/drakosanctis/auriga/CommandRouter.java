package com.drakosanctis.auriga;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * CommandRouter — fuzzy voice command dispatcher for the Auriga ecosystem.
 *
 * <p>Implements {@link AurigaInterfaces.ICommandRouter}. Receives a raw spoken
 * command string (from {@link AurigaVoiceEngine} or {@link AurigaVoiceService})
 * and routes it to the best-matching registered {@link AurigaInterfaces.SkillHandler}.
 *
 * <h3>Matching algorithm (two-pass)</h3>
 * <ol>
 *   <li><b>Containment pass</b> — if the normalised command contains the entire
 *       normalised trigger phrase as a substring, the skill is a candidate with
 *       score 1.0.</li>
 *   <li><b>Word-overlap pass</b> — Jaccard-style word overlap between command
 *       tokens and trigger tokens. A skill must score ≥ {@value MIN_OVERLAP}
 *       to be a candidate.</li>
 * </ol>
 * The highest-scoring candidate wins. Ties are broken in favour of the skill
 * registered first (more specific before more general).
 *
 * <h3>Argument extraction</h3>
 * After the trigger phrase is located in the command, everything after it is
 * passed as the {@code argument} parameter to the {@link AurigaInterfaces.SkillHandler}.
 * Example: command = "call mum", trigger = "call", argument = "mum".
 *
 * <h3>Thread safety</h3>
 * Skills are stored in a {@link CopyOnWriteArrayList}; {@code dispatch()} and
 * {@code registerSkill()} are safe to call from any thread.
 *
 * <h3>Fallback response</h3>
 * If no skill matches, {@link #dispatch(String)} returns a friendly spoken
 * response listing examples of valid commands, rather than returning {@code null}.
 */
public class CommandRouter implements AurigaInterfaces.ICommandRouter {

    private static final String TAG = "CommandRouter";

    /**
     * Minimum Jaccard word-overlap score [0..1] for a skill to be considered
     * a candidate match. Tuned empirically: 0.25 catches "navigate to the shop"
     * against trigger "navigate" while rejecting accidental overlaps like "not"
     * against "note".
     */
    private static final float MIN_OVERLAP = 0.25f;

    // Words that carry no semantic meaning and are excluded from overlap scoring
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            "a", "an", "the", "to", "for", "of", "and", "or", "in", "on",
            "at", "by", "with", "please", "can", "you", "i", "my", "me",
            "is", "are", "was", "be", "do", "now", "just", "hey", "ok",
            "okay", "auriga"
    ));

    // ─────────────────────────────────────────────────────────────────────────
    // Internal skill entry
    // ─────────────────────────────────────────────────────────────────────────

    private static final class SkillEntry {
        final String                         triggerPhrase;
        final String                         normalised;   // lowercase, punctuation-stripped
        final String[]                       words;        // meaningful words in trigger
        final AurigaInterfaces.SkillHandler  handler;

        SkillEntry(String trigger, AurigaInterfaces.SkillHandler handler) {
            this.triggerPhrase = trigger;
            this.normalised    = normalise(trigger);
            this.words         = meaningfulWords(this.normalised);
            this.handler       = handler;
        }

        @Override public String toString() {
            return "SkillEntry{\"" + triggerPhrase + "\"}";
        }
    }

    private final CopyOnWriteArrayList<SkillEntry> skills = new CopyOnWriteArrayList<>();

    // ─────────────────────────────────────────────────────────────────────────
    // ICommandRouter — register / unregister
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void registerSkill(String triggerPhrase, AurigaInterfaces.SkillHandler handler) {
        if (triggerPhrase == null || triggerPhrase.trim().isEmpty()) {
            Log.w(TAG, "registerSkill: empty trigger ignored");
            return;
        }
        if (handler == null) {
            Log.w(TAG, "registerSkill: null handler for '" + triggerPhrase + "'");
            return;
        }
        // Replace any existing entry for the same trigger (case-insensitive)
        String norm = normalise(triggerPhrase);
        for (int i = 0; i < skills.size(); i++) {
            if (skills.get(i).normalised.equals(norm)) {
                skills.set(i, new SkillEntry(triggerPhrase, handler));
                Log.d(TAG, "registerSkill: updated '" + triggerPhrase + "'");
                return;
            }
        }
        skills.add(new SkillEntry(triggerPhrase, handler));
        Log.d(TAG, "registerSkill: added '" + triggerPhrase + "' (total=" + skills.size() + ")");
    }

    @Override
    public void unregisterSkill(String triggerPhrase) {
        if (triggerPhrase == null) return;
        String norm = normalise(triggerPhrase);
        skills.removeIf(e -> e.normalised.equals(norm));
        Log.d(TAG, "unregisterSkill: '" + triggerPhrase + "'");
    }

    @Override
    public List<String> getRegisteredTriggers() {
        List<String> triggers = new ArrayList<>(skills.size());
        for (SkillEntry e : skills) triggers.add(e.triggerPhrase);
        return Collections.unmodifiableList(triggers);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ICommandRouter — dispatch
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Route a spoken command to the best-matching registered skill.
     *
     * @param spokenCommand Raw transcript from the speech recogniser.
     * @return The spoken response string from the winning skill handler, or a
     *         friendly "I don't know that command" fallback message.
     */
    @Override
    public String dispatch(String spokenCommand) {
        if (spokenCommand == null || spokenCommand.trim().isEmpty()) {
            return "I didn't catch that. Please try again.";
        }

        String normCmd  = normalise(spokenCommand);
        String[] cmdWords = meaningfulWords(normCmd);

        Log.d(TAG, "dispatch: '" + spokenCommand + "' → normalised='" + normCmd + "'");

        SkillEntry best  = null;
        float      bestScore = -1f;

        for (SkillEntry entry : skills) {
            float score = score(normCmd, cmdWords, entry);
            if (score > bestScore) {
                bestScore = score;
                best      = entry;
            }
        }

        if (best != null && bestScore >= MIN_OVERLAP) {
            String argument = extractArgument(normCmd, best.normalised);
            Log.i(TAG, "dispatch → '" + best.triggerPhrase
                    + "' (score=" + bestScore + ") arg='" + argument + "'");
            try {
                String response = best.handler.handle(spokenCommand, argument);
                return response != null ? response : "";
            } catch (Throwable t) {
                Log.e(TAG, "skill handler threw for '" + best.triggerPhrase + "'", t);
                return "There was a problem running that command.";
            }
        }

        // No match
        Log.d(TAG, "dispatch: no match (bestScore=" + bestScore + ")");
        return buildNoMatchResponse();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ICommandRouter — selfTest
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public boolean selfTest(Context ctx) {
        // Register a temporary skill, dispatch a test command, verify it fires
        final boolean[] fired = {false};
        registerSkill("__selftest__", (cmd, arg) -> {
            fired[0] = true;
            return "ok";
        });
        String result = dispatch("__selftest__ probe");
        unregisterSkill("__selftest__");

        boolean ok = fired[0] && "ok".equals(result);
        Log.i(TAG, "selfTest → " + ok + " (skills=" + skills.size() + ")");
        return ok;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Matching helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Score how well {@code normCmd} matches a {@link SkillEntry}.
     *
     * <p>Two-pass scoring:
     * <ol>
     *   <li>Containment: trigger phrase is a substring of the command → 1.0</li>
     *   <li>Jaccard word overlap between meaningful words → [0, 1)</li>
     * </ol>
     */
    private static float score(String normCmd, String[] cmdWords, SkillEntry entry) {
        // Pass 1 — exact containment
        if (normCmd.contains(entry.normalised)) return 1.0f;
        // Also check word-boundary containment (prevent "not" matching "note")
        if (containsAsWord(normCmd, entry.normalised)) return 0.95f;

        // Pass 2 — Jaccard word overlap
        if (cmdWords.length == 0 || entry.words.length == 0) return 0f;
        int intersection = 0;
        Set<String> cmdSet = new HashSet<>(Arrays.asList(cmdWords));
        for (String w : entry.words) {
            if (cmdSet.contains(w)) intersection++;
        }
        int union = cmdSet.size() + entry.words.length - intersection;
        return union > 0 ? (float) intersection / union : 0f;
    }

    /**
     * Check that {@code phrase} appears in {@code text} surrounded by word
     * boundaries (space, start, or end of string) to avoid partial-word hits.
     */
    private static boolean containsAsWord(String text, String phrase) {
        int idx = text.indexOf(phrase);
        if (idx < 0) return false;
        boolean startOk = idx == 0 || text.charAt(idx - 1) == ' ';
        int end = idx + phrase.length();
        boolean endOk = end >= text.length() || text.charAt(end) == ' ';
        return startOk && endOk;
    }

    /**
     * Extract the part of the command that comes after the trigger phrase.
     * Returns an empty string if the trigger is not locatable.
     */
    private static String extractArgument(String normCmd, String normTrigger) {
        int idx = normCmd.indexOf(normTrigger);
        if (idx < 0) return "";
        String after = normCmd.substring(idx + normTrigger.length()).trim();
        return after;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Text normalisation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Lowercase, strip punctuation (keeping spaces and alphanumerics), then
     * collapse multiple spaces. Result is suitable for substring and word-
     * overlap matching.
     */
    static String normalise(String raw) {
        if (raw == null) return "";
        return raw.toLowerCase(Locale.US)
                  .replaceAll("[^a-z0-9 ]", " ")
                  .replaceAll("\\s+", " ")
                  .trim();
    }

    /**
     * Split a normalised string into tokens, excluding stop words and single
     * characters. Returns an empty array if no meaningful words remain.
     */
    static String[] meaningfulWords(String normalised) {
        if (normalised.isEmpty()) return new String[0];
        String[] tokens = normalised.split(" ");
        List<String> words = new ArrayList<>(tokens.length);
        for (String t : tokens) {
            if (t.length() > 1 && !STOP_WORDS.contains(t)) words.add(t);
        }
        return words.toArray(new String[0]);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fallback response
    // ─────────────────────────────────────────────────────────────────────────

    private String buildNoMatchResponse() {
        StringBuilder sb = new StringBuilder(
                "I don't know that command. ");
        List<String> triggers = getRegisteredTriggers();
        if (!triggers.isEmpty()) {
            sb.append("Try saying: ");
            int max = Math.min(3, triggers.size());
            for (int i = 0; i < max; i++) {
                if (i > 0) sb.append(", or ");
                sb.append(triggers.get(i));
            }
            sb.append(". Say 'help' for the full list.");
        } else {
            sb.append("No commands are registered yet.");
        }
        return sb.toString();
    }
}
