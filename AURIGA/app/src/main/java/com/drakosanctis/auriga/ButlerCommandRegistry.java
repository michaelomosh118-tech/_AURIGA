package com.drakosanctis.auriga;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ButlerCommandRegistry — all voice commands Auriga Butler understands.
 *
 * Each ButlerCommand has:
 *  - A category (for "what can you do" listing)
 *  - One or more trigger phrases (any substring match fires it)
 *  - An ActionCode enum that AurigaButlerService executes
 *
 * Feature tips are built into each command so the Butler can proactively
 * tell the user about capabilities they have not tried yet.
 */
public class ButlerCommandRegistry {

    public enum ActionCode {
        // ── Navigation / Auriga ──────────────────────────────────────
        AURIGA_NAVIGATE,
        AURIGA_READ_LABEL,
        AURIGA_IDENTIFY_PILL,
        AURIGA_DESCRIBE_SCENE,
        AURIGA_IDENTIFY_FACE,
        AURIGA_READ_CASH,
        AURIGA_CROSSING_MODE,
        AURIGA_STAIR_MODE,
        AURIGA_TARGETS,
        AURIGA_CALIBRATE,
        AURIGA_SOS,
        // ── System ───────────────────────────────────────────────────
        SYSTEM_OPEN_APP,
        SYSTEM_GO_HOME,
        SYSTEM_GO_BACK,
        SYSTEM_RECENT_APPS,
        SYSTEM_NOTIFICATIONS,
        SYSTEM_BATTERY,
        SYSTEM_WIFI_STATUS,
        SYSTEM_BRIGHTNESS_UP,
        SYSTEM_BRIGHTNESS_DOWN,
        SYSTEM_VOLUME_UP,
        SYSTEM_VOLUME_DOWN,
        SYSTEM_MUTE,
        SYSTEM_TORCH,
        SYSTEM_SCREENSHOT,
        SYSTEM_LOCK_SCREEN,
        // ── Time / Info ───────────────────────────────────────────────
        INFO_TIME,
        INFO_DATE,
        INFO_DAY,
        INFO_BATTERY_PERCENT,
        INFO_STORAGE,
        INFO_SIGNAL,
        // ── Communication ────────────────────────────────────────────
        COMM_CALL,
        COMM_SEND_SMS,
        COMM_READ_MESSAGES,
        COMM_ANSWER_CALL,
        COMM_REJECT_CALL,
        COMM_LAST_CALLER,
        // ── Media ─────────────────────────────────────────────────────
        MEDIA_PLAY,
        MEDIA_PAUSE,
        MEDIA_NEXT,
        MEDIA_PREVIOUS,
        MEDIA_STOP,
        // ── Help / Tutorial ───────────────────────────────────────────
        HELP_LIST_COMMANDS,
        HELP_TUTORIAL,
        HELP_WHAT_CAN_YOU_DO,
        HELP_FEATURE_TIPS,
        UNKNOWN
    }

    public static class ButlerCommand {
        public final String category;
        public final String[] triggers;
        public final ActionCode action;
        public final String description;
        public final String featureTip;

        ButlerCommand(String category, ActionCode action,
                      String description, String featureTip, String... triggers) {
            this.category    = category;
            this.action      = action;
            this.description = description;
            this.featureTip  = featureTip;
            this.triggers    = triggers;
        }
    }

    private final List<ButlerCommand> commands = new ArrayList<>();

    public ButlerCommandRegistry() {
        register();
    }

    private void register() {

        // ── Auriga features ─────────────────────────────────────────
        add("Auriga Navigation", ActionCode.AURIGA_NAVIGATE,
                "Start the object navigation camera",
                "I can guide you through any space by naming objects, their distance, and direction.",
                "navigate", "start navigation", "what's ahead", "what is ahead", "start camera");

        add("Auriga Navigation", ActionCode.AURIGA_READ_LABEL,
                "Read a label or product text",
                "Point me at any label, package, or sign and I will read it aloud.",
                "read label", "read this", "what does this say", "read the text", "scan label");

        add("Auriga Navigation", ActionCode.AURIGA_IDENTIFY_PILL,
                "Identify a pill or tablet",
                "I can identify medications by shape, color, and imprint. Always verify with your pharmacist.",
                "identify pill", "what pill", "scan pill", "check medication", "identify tablet");

        add("Auriga Navigation", ActionCode.AURIGA_DESCRIBE_SCENE,
                "Describe the scene in front of you",
                "I will describe everything in view in natural language.",
                "describe scene", "what do you see", "describe this", "what's around me", "scene description");

        add("Auriga Navigation", ActionCode.AURIGA_IDENTIFY_FACE,
                "Identify a person by face",
                "I can remember faces. Say enrol face to save someone's name.",
                "who is this", "identify person", "who is in front", "identify face", "who is here");

        add("Auriga Navigation", ActionCode.AURIGA_READ_CASH,
                "Identify a banknote or coin",
                "I can identify cash in most major currencies. Say set currency to switch.",
                "identify cash", "what note", "how much is this", "read money", "identify money", "what banknote");

        add("Auriga Navigation", ActionCode.AURIGA_CROSSING_MODE,
                "Start pedestrian crossing mode",
                "Crossing mode watches for traffic and traffic lights and tells you when it is safe.",
                "crossing mode", "help me cross", "is it safe to cross", "crossing guard");

        add("Auriga Navigation", ActionCode.AURIGA_STAIR_MODE,
                "Detect stairs ahead",
                "I watch for stairs, count the steps, and tell you whether they go up or down.",
                "detect stairs", "are there stairs", "stair mode", "check for stairs");

        add("Auriga Features", ActionCode.AURIGA_TARGETS,
                "Open object targets",
                "You can set targets so I only alert you for specific objects — like chairs or cups.",
                "open targets", "set targets", "my targets", "manage targets");

        add("Auriga Features", ActionCode.AURIGA_CALIBRATE,
                "Run device calibration",
                "Calibration improves distance accuracy for your specific phone model.",
                "calibrate", "run calibration", "start calibration");

        add("Auriga Features", ActionCode.AURIGA_SOS,
                "Trigger emergency SOS",
                "Say emergency SOS in any emergency and I will call your contact and send your location.",
                "emergency", "sos", "help me", "emergency sos", "call for help");

        // ── System ──────────────────────────────────────────────────
        add("System", ActionCode.SYSTEM_GO_HOME,
                "Go to the home screen",
                "You can return home at any time without touching the screen.",
                "go home", "home screen", "press home", "take me home");

        add("System", ActionCode.SYSTEM_GO_BACK,
                "Go back",
                "I can press the back button for you on any screen.",
                "go back", "press back", "back button", "previous screen");

        add("System", ActionCode.SYSTEM_RECENT_APPS,
                "Show recent apps",
                "Say recent apps to switch between your open applications.",
                "recent apps", "show recent", "app switcher", "open apps");

        add("System", ActionCode.SYSTEM_OPEN_APP,
                "Open any installed app by name",
                "Say open followed by any app name — like open WhatsApp or open Maps.",
                "open ", "launch ", "start app");

        add("System", ActionCode.SYSTEM_NOTIFICATIONS,
                "Read notifications",
                "I can pull down the notification shade and read your notifications.",
                "notifications", "read notifications", "check notifications", "any messages");

        add("System", ActionCode.SYSTEM_TORCH,
                "Toggle the flashlight",
                "The torch can help others see you in low light even while I am navigating.",
                "torch", "flashlight", "turn on light", "toggle flashlight", "turn off torch");

        add("System", ActionCode.SYSTEM_VOLUME_UP,
                "Turn volume up",
                null,
                "volume up", "louder", "increase volume", "turn it up");

        add("System", ActionCode.SYSTEM_VOLUME_DOWN,
                "Turn volume down",
                null,
                "volume down", "quieter", "decrease volume", "turn it down");

        add("System", ActionCode.SYSTEM_MUTE,
                "Mute or unmute",
                null,
                "mute", "unmute", "silent mode", "turn off sound");

        add("System", ActionCode.SYSTEM_BATTERY,
                "Check battery level",
                null,
                "battery", "how much battery", "battery level", "how charged");

        add("System", ActionCode.SYSTEM_LOCK_SCREEN,
                "Lock the screen",
                null,
                "lock screen", "lock the phone", "lock phone", "sleep screen");

        // ── Info ────────────────────────────────────────────────────
        add("Information", ActionCode.INFO_TIME,
                "Tell the current time",
                null,
                "what time", "what's the time", "time please", "current time");

        add("Information", ActionCode.INFO_DATE,
                "Tell today's date",
                null,
                "what date", "what's the date", "today's date", "what day is it");

        add("Information", ActionCode.INFO_DAY,
                "Tell the day of the week",
                null,
                "what day", "which day");

        add("Information", ActionCode.INFO_BATTERY_PERCENT,
                "Report battery percentage",
                null,
                "battery percent", "how much charge");

        add("Information", ActionCode.INFO_SIGNAL,
                "Report network signal strength",
                "I can tell you your signal strength and whether you are on Wi-Fi or mobile data.",
                "signal strength", "network signal", "am i connected", "do i have signal");

        // ── Communication ───────────────────────────────────────────
        add("Communication", ActionCode.COMM_CALL,
                "Call a contact by name",
                "Say call followed by any name in your contacts.",
                "call ", "ring ", "phone ");

        add("Communication", ActionCode.COMM_SEND_SMS,
                "Send a text message",
                "Say send message to followed by a contact name, then dictate your message.",
                "send message", "send text", "text ", "message ");

        add("Communication", ActionCode.COMM_READ_MESSAGES,
                "Read latest messages",
                "I can read your most recent texts and notifications aloud.",
                "read messages", "read texts", "any texts", "read my messages");

        add("Communication", ActionCode.COMM_ANSWER_CALL,
                "Answer an incoming call",
                "When your phone is ringing, say answer to pick up hands-free.",
                "answer", "answer call", "pick up");

        add("Communication", ActionCode.COMM_REJECT_CALL,
                "Reject an incoming call",
                null,
                "reject", "decline", "ignore call", "reject call");

        add("Communication", ActionCode.COMM_LAST_CALLER,
                "Who last called",
                null,
                "who called", "last caller", "missed call", "who rang");

        // ── Media ────────────────────────────────────────────────────
        add("Media", ActionCode.MEDIA_PLAY,
                "Play music",
                null,
                "play music", "play song", "play", "resume music");

        add("Media", ActionCode.MEDIA_PAUSE,
                "Pause music",
                null,
                "pause music", "pause song", "stop music");

        add("Media", ActionCode.MEDIA_NEXT,
                "Next track",
                null,
                "next song", "next track", "skip song", "skip");

        add("Media", ActionCode.MEDIA_PREVIOUS,
                "Previous track",
                null,
                "previous song", "previous track", "go back song");

        // ── Help ─────────────────────────────────────────────────────
        add("Help", ActionCode.HELP_LIST_COMMANDS,
                "List available commands",
                null,
                "list commands", "commands", "what commands", "command list");

        add("Help", ActionCode.HELP_WHAT_CAN_YOU_DO,
                "Describe what the butler can do",
                null,
                "what can you do", "your capabilities", "what do you do", "help");

        add("Help", ActionCode.HELP_TUTORIAL,
                "Start the voice tutorial",
                "The tutorial walks you through every Auriga feature, step by step, using only your voice.",
                "tutorial", "start tutorial", "how do i use", "show me how", "teach me");

        add("Help", ActionCode.HELP_FEATURE_TIPS,
                "Get a tip about a feature you haven't tried",
                null,
                "tip", "feature tip", "what else can you do", "surprise me");
    }

    private void add(String category, ActionCode action, String description,
                     String featureTip, String... triggers) {
        commands.add(new ButlerCommand(category, action, description, featureTip, triggers));
    }

    /**
     * Match a spoken phrase to the best command.
     * Returns a match if any trigger keyword is contained in the phrase.
     * More specific triggers (longer) win over shorter ones.
     */
    public ButlerCommand match(String spoken) {
        if (spoken == null || spoken.isEmpty()) return null;
        String lower = spoken.toLowerCase().trim();
        ButlerCommand best = null;
        int bestLen = 0;
        for (ButlerCommand cmd : commands) {
            for (String trigger : cmd.triggers) {
                if (lower.contains(trigger) && trigger.length() > bestLen) {
                    best    = cmd;
                    bestLen = trigger.length();
                }
            }
        }
        return best;
    }

    /**
     * Extract the argument after a trigger — e.g. "call Michael" → "Michael".
     */
    public String extractArg(String spoken, ButlerCommand cmd) {
        if (cmd == null || spoken == null) return "";
        String lower = spoken.toLowerCase().trim();
        for (String trigger : cmd.triggers) {
            int idx = lower.indexOf(trigger);
            if (idx >= 0) {
                String after = spoken.substring(idx + trigger.length()).trim();
                if (!after.isEmpty()) return after;
            }
        }
        return "";
    }

    /** Return all commands in a readable spoken summary per category. */
    public String buildHelpText() {
        StringBuilder sb = new StringBuilder("Here is what I can do. ");
        String lastCat = "";
        for (ButlerCommand cmd : commands) {
            if (!cmd.category.equals(lastCat)) {
                sb.append(cmd.category).append(": ");
                lastCat = cmd.category;
            }
            sb.append(cmd.description).append(". ");
        }
        return sb.toString();
    }

    /** Return a random unused feature tip. */
    public String randomFeatureTip() {
        List<String> tips = new ArrayList<>();
        for (ButlerCommand cmd : commands) {
            if (cmd.featureTip != null) tips.add(cmd.featureTip);
        }
        if (tips.isEmpty()) return "Say help to hear everything I can do.";
        return tips.get((int)(Math.random() * tips.size()));
    }

    public List<ButlerCommand> getCommands() { return commands; }
}
