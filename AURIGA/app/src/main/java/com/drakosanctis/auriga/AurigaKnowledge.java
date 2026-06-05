package com.drakosanctis.auriga;

import java.util.Calendar;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * AurigaKnowledge — fully on-device conversational knowledge base.
 *
 * When AurigaVoiceEngine receives a voice query that isn't a navigation
 * command, it calls {@link #answer(String)} before giving up. This class
 * covers two layers:
 *
 *   1. Auriga-specific facts (features, how-to, accessibility tips).
 *   2. General conversational Q&A (definitions, time, greetings, etc.)
 *      written as plain spoken English, optimised for TTS — no markdown.
 *
 * All answers are ≤ ~40 words so they stay comfortable at the default
 * TTS speech rate. Returns {@code null} when nothing matches, letting
 * the caller give its own fallback.
 */
public final class AurigaKnowledge {

    private AurigaKnowledge() {}

    /**
     * Query the knowledge base.
     *
     * @param rawText  the user's spoken input (will be lower-cased internally)
     * @return a spoken-English answer string, or {@code null} if no match
     */
    public static String answer(String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) return null;
        String t = rawText.toLowerCase(Locale.US).trim();

        // ── Greetings ────────────────────────────────────────────────
        if (contains(t, "hello", "hi there", "good morning", "good afternoon",
                "good evening", "hey there")) {
            int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            String g = hour < 12 ? "Good morning" : hour < 17 ? "Good afternoon" : "Good evening";
            return g + ". I am Auriga, your spatial assistant. How can I help you?";
        }

        if (contains(t, "how are you", "are you ok", "you doing")) {
            return "I am running well, thank you. What can I help you with?";
        }

        if (contains(t, "thank", "thanks")) {
            return "Of course. Is there anything else you need?";
        }

        // ── About Auriga ─────────────────────────────────────────────
        if (contains(t, "what is auriga", "what are you", "tell me about auriga",
                "tell me about yourself", "who are you")) {
            return "Auriga is a spatial intelligence platform for blind and low-vision users. " +
                "It detects objects around you, reads printed text aloud, and responds to voice commands — all on-device.";
        }

        if (contains(t, "who made", "who built", "who created", "drakosanctis")) {
            return "Auriga was built by DrakoSanctis, focused on making spatial awareness accessible to blind and low-vision users.";
        }

        if (contains(t, "can you work offline", "offline mode", "no internet", "without internet")) {
            return "Yes. Auriga works fully offline. The Object Locator, DrakoVoice Reader, and voice navigation all run entirely on your device.";
        }

        // ── Time and date ────────────────────────────────────────────
        if (contains(t, "what time", "current time", "the time")) {
            Calendar now = Calendar.getInstance();
            int h = now.get(Calendar.HOUR);
            if (h == 0) h = 12;
            int m = now.get(Calendar.MINUTE);
            String ampm = now.get(Calendar.AM_PM) == Calendar.AM ? "AM" : "PM";
            String mStr = m == 0 ? "" : (m < 10 ? " oh " + m : " " + m);
            return "The time is " + h + mStr + " " + ampm + ".";
        }

        if (contains(t, "what day", "what date", "today's date", "the date")) {
            Calendar now = Calendar.getInstance();
            String[] days = {"Sunday","Monday","Tuesday","Wednesday","Thursday","Friday","Saturday"};
            String[] months = {"January","February","March","April","May","June",
                               "July","August","September","October","November","December"};
            return "Today is " + days[now.get(Calendar.DAY_OF_WEEK) - 1] + ", " +
                months[now.get(Calendar.MONTH)] + " " + now.get(Calendar.DAY_OF_MONTH) +
                ", " + now.get(Calendar.YEAR) + ".";
        }

        // ── Object Locator help ──────────────────────────────────────
        if (contains(t, "how do i use the locator", "how does the locator work",
                "locator help", "object locator help", "how to use locator")) {
            return "The Object Locator uses your camera and AI to detect objects in real time. " +
                "It tells you what is nearby, how far away it is, and whether it is left, right, or ahead.";
        }

        if (contains(t, "what is bearing", "what does bearing mean", "bearing mean")) {
            return "Bearing is the horizontal direction to an object. " +
                "Zero means straight ahead. Positive numbers are degrees to your right, negative to your left.";
        }

        if (contains(t, "what is distance", "how does distance work", "how far")) {
            return "Distance is measured in metres from your camera. " +
                "The locator estimates it from the size of the object in the frame, using your calibration profile.";
        }

        if (contains(t, "what is a target", "what are targets", "targets mean")) {
            return "Targets are the object types you want the locator to watch for, like chair, cup, or person. " +
                "Only those objects trigger an announcement. Say open targets to manage your list.";
        }

        if (contains(t, "how do i calibrate", "what is calibration", "calibration mean")) {
            return "Calibration teaches Auriga how your specific camera measures distances. " +
                "Go to Calibration Library and select your phone model for better distance accuracy.";
        }

        // ── Reader help ──────────────────────────────────────────────
        if (contains(t, "how do i use the reader", "reader help", "how to read text",
                "how to use drakovoice", "how does the reader work")) {
            return "Point your camera at any text and tap the capture button. " +
                "In Auto mode, it captures continuously as you move. You can tap any word to start reading from that point.";
        }

        // ── Voice navigation help ────────────────────────────────────
        if (contains(t, "how do i use voice", "voice navigation help", "how to activate voice",
                "how to wake you up", "wake word")) {
            return "Say your assistant name followed by Auriga to wake me, like Nova Auriga. " +
                "Or long-press anywhere on the screen to start listening instantly.";
        }

        // ── What can you do ──────────────────────────────────────────
        if (contains(t, "what can you do", "list commands", "available commands",
                "what are your commands", "what do you know", "what can i say")) {
            return "I can open the locator, reader, targets, calibration, feedback, about, help, and support. " +
                "I can open or close the menu, go back, describe the screen, answer questions, and give daily accessibility tips.";
        }

        // ── General world-knowledge — common objects a VI user might ask about ──
        if (matchesPattern(t, "what is (a |an )?cup")) {
            return "A cup is a small open container, usually cylindrical, used for drinking. " +
                "It typically has a handle on one side and is about 10 centimetres tall.";
        }
        if (matchesPattern(t, "what is (a |an )?chair")) {
            return "A chair is a seat with a back support, designed for one person. " +
                "Most chairs have four legs and are about 45 centimetres from the floor to the seat.";
        }
        if (matchesPattern(t, "what is (a |an )?door")) {
            return "A door is a hinged or sliding panel that opens and closes an entrance. " +
                "It usually has a handle or knob on one side at about waist height.";
        }
        if (matchesPattern(t, "what is (a |an )?bottle")) {
            return "A bottle is a narrow-necked container for liquids, usually taller than it is wide. " +
                "It has a sealed cap at the top.";
        }
        if (matchesPattern(t, "what is (a |an )?book")) {
            return "A book is a bound collection of printed pages. " +
                "It is rectangular, usually flat, and can be read by touch with braille editions.";
        }
        if (matchesPattern(t, "what is (a |an )?phone|what is (a |an )?mobile")) {
            return "A mobile phone is a handheld communication device. " +
                "Modern smartphones have a flat touchscreen on one side and a camera on the other.";
        }
        if (matchesPattern(t, "what is (a |an )?person|who is (a |an )?person")) {
            return "A person is a human being. The locator detects people as the class labelled person " +
                "and will announce their direction and distance to you.";
        }
        if (matchesPattern(t, "what is (a |an )?car|what is (a |an )?vehicle")) {
            return "A car is a four-wheeled motor vehicle. It is roughly 4 metres long and 1.5 metres tall " +
                "and makes an engine sound when running.";
        }
        if (matchesPattern(t, "what is (a |an )?dog|what is (a |an )?cat")) {
            return "That is a small to medium animal — a pet. " +
                "The locator can detect dogs and cats and will announce their direction.";
        }

        // ── Measurements and units ───────────────────────────────────
        if (contains(t, "how long is a metre", "what is a metre", "how far is a meter")) {
            return "One metre is about the distance from your shoulder to your opposite outstretched hand, " +
                "or roughly three adult footsteps.";
        }
        if (contains(t, "how long is a centimetre", "what is a centimetre")) {
            return "One centimetre is about the width of your little fingernail. " +
                "There are 100 centimetres in a metre.";
        }

        // ── Accessibility context ────────────────────────────────────
        if (contains(t, "what is echolocation")) {
            return "Echolocation is the technique of using sound reflections to sense your environment. " +
                "Some blind people use tongue clicks or tapping sounds to detect walls, doorways, and objects.";
        }
        if (contains(t, "what is a white cane", "what is the cane")) {
            return "A white cane is a mobility tool used by blind and low-vision people. " +
                "It is swept side to side while walking to detect obstacles and drop-offs on the ground ahead.";
        }
        if (contains(t, "what is braille")) {
            return "Braille is a tactile writing system made up of raised dots arranged in cells. " +
                "Each cell represents a letter, number, or punctuation mark and is read by touch.";
        }

        // ── Connectivity / tech basics ───────────────────────────────
        if (contains(t, "what is wifi", "what is wi-fi")) {
            return "Wi-Fi is a wireless internet connection technology. " +
                "When your phone is connected to Wi-Fi, it can access the internet without using mobile data.";
        }
        if (contains(t, "what is bluetooth")) {
            return "Bluetooth is a short-range wireless technology for connecting devices. " +
                "It is commonly used for headphones, speakers, and hearing aids.";
        }
        if (contains(t, "what is ai", "what is artificial intelligence")) {
            return "Artificial intelligence is software that learns from data to perform tasks that normally " +
                "require human intelligence, like recognising objects, understanding speech, or reading text.";
        }

        // ── No match ─────────────────────────────────────────────────
        return null;
    }

    /**
     * Builds an offline fallback when {@link #answer} returns null.
     * Always returns a non-null, speakable string.
     */
    public static String fallback(String text) {
        String t = text != null ? text.toLowerCase(Locale.US).trim() : "";
        if (matchesPattern(t, "what is|what are|define|explain|tell me about")) {
            return "I do not have that answer stored on-device. Connect to the internet " +
                "for broader questions. Say help to hear what I can do offline.";
        }
        if (matchesPattern(t, "how do|how can|how to|how does")) {
            return "That is a good question but I do not have that answer offline. " +
                "Say open help for Auriga usage tips.";
        }
        return "I did not quite get that. Try: open locator, read this, open menu, " +
            "or say what can you do to hear available commands.";
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static boolean contains(String text, String... phrases) {
        for (String p : phrases) {
            if (text.contains(p)) return true;
        }
        return false;
    }

    private static boolean matchesPattern(String text, String regex) {
        try {
            return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text).find();
        } catch (Exception e) {
            return false;
        }
    }
}
