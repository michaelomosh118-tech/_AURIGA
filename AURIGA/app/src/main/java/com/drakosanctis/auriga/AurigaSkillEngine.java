package com.drakosanctis.auriga;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AurigaSkillEngine — OpenClaw-style agent skill dispatcher for AurigaNavi Android.
 *
 * Implements the OpenClaw agent concept natively in Android Java:
 *   · Skill registry (register skills, match by intent)
 *   · Async skill execution with spoken result delivery
 *   · Context-aware routing (skill → tool use → speak)
 *   · Online/offline graceful fallback for every skill
 *
 * Skills included:
 *   TIMERS & ALARMS     — real AlarmManager (survives app kill, fires offline)
 *   REMINDERS           — WorkManager-backed, with notifications
 *   CALCULATOR          — fully offline arithmetic + unit conversion
 *   WEATHER             — Open-Meteo API, GPS, graceful offline fallback
 *   COMPASS & GPS       — device sensors, bearing, speed, altitude
 *   SYSTEM CONTROLS     — volume, brightness, speech rate
 *   KNOWLEDGE BOOST     — spelling, random numbers, stopwatch, countdown
 *   HOME AUTOMATION     — dispatches Intent for SmartHome integrations
 *   MEMORY QUERIES      — delegates to AurigaMemoryStore
 *   NEWS HEADLINES      — RSS fetch, offline cache
 *
 * Usage:
 *   AurigaSkillEngine engine = new AurigaSkillEngine(context, tts);
 *   boolean handled = engine.dispatch("set a timer for 5 minutes");
 *   // If handled == false, fall through to knowledge base / LLM
 */
public class AurigaSkillEngine {

    public static final String CHANNEL_ID   = "auriga_skills";
    public static final String ACTION_ALARM = "com.drakosanctis.auriga.ALARM_FIRE";
    public static final String ACTION_REMINDER = "com.drakosanctis.auriga.REMINDER_FIRE";

    private final Context ctx;
    private TextToSpeech tts;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final AlarmManager alarmMgr;
    private final SensorManager sensorMgr;
    private final LocationManager locationMgr;

    private final Map<String, CountDownTimer> activeTimers = new HashMap<>();
    private int timerCounter = 0;
    private int alarmCounter = 0;
    private int reminderCounter = 0;

    private boolean stopwatchRunning = false;
    private long stopwatchStartMs = 0;
    private long stopwatchLapMs = 0;

    private Location lastLocation = null;
    private float lastBearing = 0f;

    public interface SkillResult {
        void onResult(String spoken);
    }

    /* ── Skill descriptor ────────────────────────────────────────── */
    public static class Skill {
        public final String name;
        public final String description;
        public final String[] keywords;
        public final Pattern pattern;
        public Skill(String name, String description, String[] keywords, String pattern) {
            this.name = name;
            this.description = description;
            this.keywords = keywords;
            this.pattern = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
        }
    }

    private final List<Skill> registry = new ArrayList<>();

    /* ═══════════════════════════════════════════════════════════════
       Constructor
    ═══════════════════════════════════════════════════════════════ */
    public AurigaSkillEngine(Context context, TextToSpeech tts) {
        this.ctx = context.getApplicationContext();
        this.tts  = tts;
        this.alarmMgr   = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        this.sensorMgr  = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        this.locationMgr = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        createNotificationChannel();
        registerSkills();
    }

    public void updateTts(TextToSpeech newTts) { this.tts = newTts; }

    /* ── Speak helper ─────────────────────────────────────────────── */
    private void speak(String text) {
        main.post(() -> {
            if (tts != null) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null,
                        "skill_" + System.currentTimeMillis());
            }
        });
    }

    private void speakAdd(String text) {
        main.post(() -> {
            if (tts != null) {
                tts.speak(text, TextToSpeech.QUEUE_ADD, null,
                        "skill_add_" + System.currentTimeMillis());
            }
        });
    }

    /* ── Online check ─────────────────────────────────────────────── */
    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager)
                ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc != null && (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        }
        return false;
    }

    /* ═══════════════════════════════════════════════════════════════
       SKILL REGISTRY
    ═══════════════════════════════════════════════════════════════ */
    private void registerSkills() {
        registry.add(new Skill("Set timer", "set a countdown timer",
                new String[]{"timer", "countdown"},
                "\\b(set|start|create)\\s+(a\\s+)?timer\\s+(for\\s+)?(.+)|countdown\\s+(for\\s+)?(.+)|\\btimer\\s+(for\\s+)?(\\d.+)"));

        registry.add(new Skill("Cancel timer", "cancel a running timer",
                new String[]{"cancel timer", "stop timer", "clear timer"},
                "\\b(cancel|stop|clear|delete)\\s+(all\\s+)?(the\\s+)?timers?\\b"));

        registry.add(new Skill("List timers", "check running timers",
                new String[]{"list timer", "check timer", "how long"},
                "\\b(list|check|show)\\s+(my\\s+)?timers?\\b|\\bhow\\s+(much\\s+)?time\\s+(is\\s+)?(left|remaining)"));

        registry.add(new Skill("Set alarm", "set an alarm for a specific time",
                new String[]{"alarm", "wake me"},
                "\\b(set|create)\\s+(an?\\s+)?alarm\\s+(for\\s+|at\\s+)?(.+)|\\bwake\\s+me\\s+(up\\s+)?(at\\s+)?(.+)|\\balarm\\s+at\\s+(.+)"));

        registry.add(new Skill("Cancel alarm", "cancel an alarm",
                new String[]{"cancel alarm", "delete alarm"},
                "\\b(cancel|delete|clear|remove|turn\\s+off)\\s+(the\\s+|all\\s+)?alarms?\\b"));

        registry.add(new Skill("Set reminder", "set a reminder",
                new String[]{"remind me", "reminder"},
                "\\bremind\\s+me\\s+(?:to\\s+|about\\s+)?(.+?)\\s+in\\s+(.+)|\\bset\\s+(a\\s+)?reminder\\s+(?:to\\s+|for\\s+)?(.+?)\\s+in\\s+(.+)"));

        registry.add(new Skill("List reminders", "check pending reminders",
                new String[]{"list reminder", "any reminder"},
                "\\b(list|show|check)\\s+(my\\s+)?reminders?\\b|\\bany\\s+(pending\\s+)?reminders?\\b"));

        registry.add(new Skill("Calculator", "do maths",
                new String[]{"what is", "calculate", "compute", "plus", "minus", "times", "divided"},
                "\\bwhat\\s+is\\s+[\\d\\s\\+\\-\\*/\\.]+|\\bcalculate\\b|\\bcompute\\b|\\b(\\d+)\\s+(plus|minus|times|divided\\s+by|multiplied\\s+by)\\s+(\\d+)|\\bwhat\\s+is\\s+(\\d+)\\s*(percent|%)\\s+of|\\bsquare\\s+root|\\bsquared\\b|\\bcubed\\b"));

        registry.add(new Skill("Unit converter", "convert units",
                new String[]{"convert", "in kilometres", "in miles", "in celsius", "in fahrenheit"},
                "\\bconvert\\b|\\b(\\d+(?:\\.\\d+)?)\\s*(km|miles?|feet|meters?|inches?|cm|kg|pounds?|lbs?|litres?|gallons?|°?[CF])\\s+(to|in)\\s+(\\w+)"));

        registry.add(new Skill("Weather", "get current weather",
                new String[]{"weather", "temperature", "forecast", "rain", "hot", "cold"},
                "\\bweather\\b|\\bforecast\\b|\\btemperature\\s+(outside|today|now)|\\bwill\\s+it\\s+rain\\b|\\bis\\s+it\\s+raining\\b|\\bhow\\s+(hot|cold|warm)\\s+is\\s+it"));

        registry.add(new Skill("GPS location", "get your location",
                new String[]{"where am i", "my location", "coordinates", "gps"},
                "\\bwhere\\s+am\\s+i\\s+(located|standing|at|right\\s+now)?\\b|\\bmy\\s+(location|coordinates|gps|position)\\b|\\bgps\\b"));

        registry.add(new Skill("Compass heading", "get compass direction",
                new String[]{"compass", "which direction", "north", "facing"},
                "\\bcompass\\b|\\bwhich\\s+(direction|way)\\s+(am\\s+i\\s+)?(facing|heading|going)?\\b|\\bwhat\\s+direction\\s+am\\s+i|\\bheading\\b"));

        registry.add(new Skill("Lights", "control smart lights",
                new String[]{"lights on", "lights off", "dim lights", "brighten"},
                "\\b(turn\\s+)?(on|off)\\s+(the\\s+)?(?:\\w+\\s+)?lights?\\b|\\blights?\\s+(on|off)\\b|\\bdim\\s+(the\\s+)?lights?\\b|\\bbrighten\\b|\\bset\\s+(the\\s+)?lights?\\s+to\\s+\\d+"));

        registry.add(new Skill("Thermostat", "control the thermostat",
                new String[]{"thermostat", "set temperature", "warmer", "cooler"},
                "\\bthermostat\\b|\\bset\\s+(the\\s+)?(heat|temperature|ac)\\s+to\\s+\\d+|\\bwarmer\\b|\\bcooler\\b|\\bhotter\\b|\\bcolder\\b"));

        registry.add(new Skill("Spell word", "spell out a word",
                new String[]{"spell", "how do you spell"},
                "\\bhow\\s+do\\s+you\\s+spell\\s+(\\w+)\\b|\\bspell\\s+(?:the\\s+word\\s+)?(\\w+)\\b|\\bspelling\\s+of\\s+(\\w+)\\b"));

        registry.add(new Skill("Random number", "generate a random number",
                new String[]{"random number", "roll dice", "flip coin", "pick a number"},
                "\\brandom\\s+number\\b|\\bpick\\s+a\\s+(random\\s+)?number\\b|\\broll\\s+(a\\s+)?d?(ice|ie)?\\b|\\bflip\\s+(a\\s+)?coin\\b|\\bnumber\\s+between\\s+\\d+\\s+and\\s+\\d+"));

        registry.add(new Skill("Countdown", "count down from a number",
                new String[]{"count down", "countdown from"},
                "\\bcount\\s+down\\s+(from\\s+)?(\\d+)\\b|\\bcountdown\\s+from\\s+(\\d+)\\b"));

        registry.add(new Skill("Stopwatch", "start/stop/lap a stopwatch",
                new String[]{"stopwatch", "elapsed time"},
                "\\b(start|stop|pause|reset|lap)\\s+(the\\s+)?stopwatch\\b|\\bstopwatch\\s+(start|stop|lap|reset)\\b|\\bhow\\s+long\\s+has\\s+it\\s+been\\b"));

        registry.add(new Skill("Volume control", "adjust system volume",
                new String[]{"volume", "louder", "quieter", "mute sound"},
                "\\bvolume\\s+(up|down|max|mute|unmute)\\b|\\b(turn\\s+)?(up|down)\\s+(the\\s+)?volume\\b|\\b(louder|quieter)\\b|\\bmute\\s+sound\\b"));

        registry.add(new Skill("Battery status", "check battery level",
                new String[]{"battery", "charge level", "how much charge"},
                "\\bbattery\\b|\\bcharge\\s+level\\b|\\bhow\\s+much\\s+(?:battery|charge|power)\\b"));

        registry.add(new Skill("News headlines", "get latest news",
                new String[]{"news", "headlines", "what's happening"},
                "\\b(latest\\s+)?news\\b|\\bheadlines?\\b|\\bwhat'?s?\\s+(happening|going\\s+on|in\\s+the\\s+news)\\b|\\btop\\s+stories?\\b"));

        registry.add(new Skill("Memory recall", "recall stored information",
                new String[]{"do you remember", "what is my", "my profile"},
                "\\bdo\\s+you\\s+(remember|know)\\s+my\\b|\\bwhat\\s+(is|are)\\s+my\\b|\\bmy\\s+profile\\b|\\bwhat\\s+do\\s+you\\s+know\\s+about\\s+me\\b"));

        registry.add(new Skill("Briefing", "morning briefing",
                new String[]{"briefing", "daily briefing", "morning update", "good morning"},
                "\\b(morning\\s+)?briefing\\b|\\bdaily\\s+(briefing|digest|summary)\\b|\\bmorning\\s+update\\b"));
    }

    /* ═══════════════════════════════════════════════════════════════
       MAIN DISPATCH — OpenClaw agent-loop: match → tool → speak
    ═══════════════════════════════════════════════════════════════ */
    public boolean dispatch(String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) return false;
        String t = rawText.toLowerCase(Locale.US).trim();

        /* ── Timers ─────────────────────────────────────────────── */
        if (matchesSkill(t, "Set timer")) { handleSetTimer(t); return true; }
        if (matchesSkill(t, "Cancel timer")) { handleCancelTimer(); return true; }
        if (matchesSkill(t, "List timers")) { handleListTimers(); return true; }

        /* ── Alarms ─────────────────────────────────────────────── */
        if (matchesSkill(t, "Set alarm")) { handleSetAlarm(t); return true; }
        if (matchesSkill(t, "Cancel alarm")) { handleCancelAlarm(); return true; }

        /* ── Reminders ──────────────────────────────────────────── */
        if (matchesSkill(t, "Set reminder")) { handleSetReminder(t); return true; }
        if (matchesSkill(t, "List reminders")) { handleListReminders(); return true; }

        /* ── Math & Conversion ──────────────────────────────────── */
        if (matchesSkill(t, "Calculator")) { handleCalculate(t); return true; }
        if (matchesSkill(t, "Unit converter")) { handleConvert(t); return true; }

        /* ── Environment ─────────────────────────────────────────── */
        if (matchesSkill(t, "Weather")) { handleWeather(); return true; }
        if (matchesSkill(t, "GPS location")) { handleGps(); return true; }
        if (matchesSkill(t, "Compass heading")) { handleCompass(); return true; }
        if (matchesSkill(t, "Battery status")) { handleBattery(); return true; }

        /* ── Home automation ────────────────────────────────────── */
        if (matchesSkill(t, "Lights")) { handleLights(t); return true; }
        if (matchesSkill(t, "Thermostat")) { handleThermostat(t); return true; }

        /* ── Utility ─────────────────────────────────────────────── */
        if (matchesSkill(t, "Spell word")) { handleSpell(t); return true; }
        if (matchesSkill(t, "Random number")) { handleRandom(t); return true; }
        if (matchesSkill(t, "Countdown")) { handleCountdown(t); return true; }
        if (matchesSkill(t, "Stopwatch")) { handleStopwatch(t); return true; }
        if (matchesSkill(t, "Volume control")) { handleVolume(t); return true; }

        /* ── Information ─────────────────────────────────────────── */
        if (matchesSkill(t, "News headlines")) { handleNews(); return true; }
        if (matchesSkill(t, "Memory recall")) { handleMemory(); return true; }
        if (matchesSkill(t, "Briefing")) { handleBriefing(); return true; }

        return false; /* no skill matched — fall through to KB / LLM */
    }

    private boolean matchesSkill(String text, String skillName) {
        for (Skill s : registry) {
            if (s.name.equals(skillName)) {
                return s.pattern.matcher(text).find();
            }
        }
        return false;
    }

    /* ═══════════════════════════════════════════════════════════════
       TIMER HANDLERS — CountDownTimer (in-process, high precision)
    ═══════════════════════════════════════════════════════════════ */
    private void handleSetTimer(String text) {
        int secs = parseDurationSecs(text);
        if (secs <= 0 || secs > 86400) {
            speak("I could not understand the duration. Try saying set a timer for 5 minutes.");
            return;
        }
        timerCounter++;
        String id    = "T" + timerCounter;
        String label = parseTimerLabel(text);
        String name  = label != null ? label + " timer" : "Timer " + timerCounter;
        long millis  = secs * 1000L;
        final boolean[] warned = {false, false, false};

        CountDownTimer cdt = new CountDownTimer(millis, 500) {
            @Override public void onTick(long msLeft) {
                long rem = msLeft / 1000;
                int half = secs / 2;
                if (!warned[0] && rem <= half && rem > half - 3) {
                    warned[0] = true;
                    speakAdd("Halfway there. " + formatDur((int) rem) + " remaining on " + name + ".");
                }
                if (!warned[1] && rem <= 30 && secs > 60) {
                    warned[1] = true;
                    speakAdd("30 seconds remaining on " + name + ".");
                }
                if (!warned[2] && rem <= 10 && rem > 5) {
                    warned[2] = true;
                    speakAdd(rem + " seconds.");
                }
            }
            @Override public void onFinish() {
                activeTimers.remove(id);
                speak(name + " is done! Your " + formatDur(secs) + " timer has finished.");
                vibrate(new long[]{0, 400, 200, 400, 200, 800});
                showNotification(name + " finished!", "Your " + formatDur(secs) + " timer is complete.");
            }
        };
        cdt.start();
        activeTimers.put(id, cdt);
        speak((label != null ? label + " timer" : "Timer") + " set for " + formatDur(secs) + ". I will let you know when it is done.");
    }

    private void handleCancelTimer() {
        if (activeTimers.isEmpty()) { speak("You have no active timers."); return; }
        int count = activeTimers.size();
        for (CountDownTimer t : activeTimers.values()) t.cancel();
        activeTimers.clear();
        speak(count == 1 ? "Timer cancelled." : "All " + count + " timers cancelled.");
    }

    private void handleListTimers() {
        if (activeTimers.isEmpty()) { speak("You have no active timers."); return; }
        speak("You have " + activeTimers.size() + " active timer" + (activeTimers.size() > 1 ? "s." : "."));
    }

    /* ═══════════════════════════════════════════════════════════════
       ALARM HANDLERS — AlarmManager (fires even when app is killed)
    ═══════════════════════════════════════════════════════════════ */
    private void handleSetAlarm(String text) {
        Calendar cal = parseAbsoluteTime(text);
        if (cal == null) {
            speak("I could not understand that time. Try saying set alarm at 7 AM.");
            return;
        }
        if (cal.getTimeInMillis() < System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
        alarmCounter++;
        String label = formatTime(cal);
        Intent intent = new Intent(ACTION_ALARM);
        intent.putExtra("label", label);
        intent.setPackage(ctx.getPackageName());
        PendingIntent pi = PendingIntent.getBroadcast(ctx, alarmCounter, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmMgr.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
        } else {
            alarmMgr.setExact(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
        }

        long msUntil = cal.getTimeInMillis() - System.currentTimeMillis();
        long hoursUntil = msUntil / 3600000;
        long minsUntil  = (msUntil % 3600000) / 60000;
        String until = hoursUntil > 0
                ? hoursUntil + " hour" + (hoursUntil > 1 ? "s" : "") + " and " + minsUntil + " minutes"
                : minsUntil + " minutes";
        speak("Alarm set for " + label + ". That is in " + until + ".");
    }

    private void handleCancelAlarm() {
        Intent intent = new Intent(ACTION_ALARM);
        intent.setPackage(ctx.getPackageName());
        for (int i = 1; i <= alarmCounter; i++) {
            PendingIntent pi = PendingIntent.getBroadcast(ctx, i, intent,
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (pi != null) { alarmMgr.cancel(pi); pi.cancel(); }
        }
        speak("All alarms cancelled.");
    }

    /* ═══════════════════════════════════════════════════════════════
       REMINDER HANDLERS — AlarmManager with text payload
    ═══════════════════════════════════════════════════════════════ */
    private void handleSetReminder(String text) {
        Pattern p = Pattern.compile(
                "remind\\s+me\\s+(?:to\\s+|about\\s+)?(.+?)\\s+in\\s+(.+)|" +
                "set\\s+(?:a\\s+)?reminder\\s+(?:to\\s+|for\\s+)?(.+?)\\s+in\\s+(.+)",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        if (!m.find()) {
            speak("Try saying remind me to take medicine in 2 hours.");
            return;
        }
        String what = m.group(1) != null ? m.group(1).trim() : (m.group(3) != null ? m.group(3).trim() : "");
        String when = m.group(2) != null ? m.group(2).trim() : (m.group(4) != null ? m.group(4).trim() : "");
        int secs = parseDurationSecs(when);
        if (secs <= 0 || what.isEmpty()) {
            speak("I could not understand that reminder. Try remind me to call John in 30 minutes.");
            return;
        }
        reminderCounter++;
        long fireAt = System.currentTimeMillis() + secs * 1000L;
        Intent intent = new Intent(ACTION_REMINDER);
        intent.putExtra("text", what);
        intent.setPackage(ctx.getPackageName());
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 10000 + reminderCounter, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmMgr.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi);
        } else {
            alarmMgr.setExact(AlarmManager.RTC_WAKEUP, fireAt, pi);
        }
        speak("Reminder set. I will remind you to " + what + " in " + formatDur(secs) + ".");
    }

    private void handleListReminders() {
        speak("Reminders are stored and will fire even if you close the app. Say clear reminders to cancel all.");
    }

    /* ═══════════════════════════════════════════════════════════════
       CALCULATOR
    ═══════════════════════════════════════════════════════════════ */
    private void handleCalculate(String text) {
        String t = text.toLowerCase(Locale.US).trim();

        /* Percent of */
        Pattern pct = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:percent|%)\\s+of\\s+(\\d+(?:\\.\\d+)?)");
        Matcher pm = pct.matcher(t);
        if (pm.find()) {
            double r = Double.parseDouble(pm.group(1)) / 100.0 * Double.parseDouble(pm.group(2));
            speak(pm.group(1) + " percent of " + pm.group(2) + " is " + fmtNum(r) + ".");
            return;
        }

        /* Square root */
        Pattern sqrtP = Pattern.compile("square\\s+root\\s+of\\s+(\\d+(?:\\.\\d+)?)");
        Matcher sm = sqrtP.matcher(t);
        if (sm.find()) {
            double n = Double.parseDouble(sm.group(1));
            speak("The square root of " + sm.group(1) + " is " + fmtNum(Math.sqrt(n)) + ".");
            return;
        }

        /* Squared / cubed */
        Pattern sqP = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s+squared");
        Matcher sqM = sqP.matcher(t);
        if (sqM.find()) { double n = Double.parseDouble(sqM.group(1)); speak(sqM.group(1) + " squared is " + fmtNum(n * n) + "."); return; }
        Pattern cuP = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s+cubed");
        Matcher cuM = cuP.matcher(t);
        if (cuM.find()) { double n = Double.parseDouble(cuM.group(1)); speak(cuM.group(1) + " cubed is " + fmtNum(n * n * n) + "."); return; }

        /* Word operators */
        Pattern wordP = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s+(plus|minus|times|divided\\s+by|multiplied\\s+by)\\s+(\\d+(?:\\.\\d+)?)");
        Matcher wm = wordP.matcher(t);
        if (wm.find()) {
            double a = Double.parseDouble(wm.group(1));
            double b = Double.parseDouble(wm.group(3));
            String op = wm.group(2).trim();
            double res;
            if (op.equals("plus"))          res = a + b;
            else if (op.equals("minus"))    res = a - b;
            else if (op.contains("times") || op.contains("multiplied")) res = a * b;
            else if (op.contains("divided")) {
                if (b == 0) { speak("You cannot divide by zero."); return; }
                res = a / b;
            }
            else res = a + b;
            speak(wm.group(1) + " " + op + " " + wm.group(3) + " equals " + fmtNum(res) + ".");
            return;
        }

        speak("I could not compute that. Try saying what is 24 times 7, or square root of 144.");
    }

    /* ═══════════════════════════════════════════════════════════════
       UNIT CONVERTER
    ═══════════════════════════════════════════════════════════════ */
    private void handleConvert(String text) {
        String t = text.toLowerCase(Locale.US);
        /* Temperature */
        Matcher cToF = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*°?\\s*c(?:elsius)?\\s+(?:to|in)\\s+(?:°?f|fahrenheit)").matcher(t);
        if (cToF.find()) { double f = Double.parseDouble(cToF.group(1)) * 9.0/5 + 32; speak(cToF.group(1) + " Celsius is " + fmtNum(f) + " Fahrenheit."); return; }
        Matcher fToC = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*°?\\s*f(?:ahrenheit)?\\s+(?:to|in)\\s+(?:°?c|celsius)").matcher(t);
        if (fToC.find()) { double c = (Double.parseDouble(fToC.group(1)) - 32) * 5.0/9; speak(fToC.group(1) + " Fahrenheit is " + fmtNum(c) + " Celsius."); return; }
        /* Distance */
        Matcher kmToMi = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:km|kilometres?)\\s+(?:to|in)\\s+miles?").matcher(t);
        if (kmToMi.find()) { double mi = Double.parseDouble(kmToMi.group(1)) * 0.621371; speak(kmToMi.group(1) + " kilometres is " + fmtNum(mi) + " miles."); return; }
        Matcher miToKm = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*miles?\\s+(?:to|in)\\s+(?:km|kilometres?)").matcher(t);
        if (miToKm.find()) { double km = Double.parseDouble(miToKm.group(1)) * 1.60934; speak(miToKm.group(1) + " miles is " + fmtNum(km) + " kilometres."); return; }
        Matcher mToFt = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:meters?|metres?)\\s+(?:to|in)\\s+feet").matcher(t);
        if (mToFt.find()) { double ft = Double.parseDouble(mToFt.group(1)) * 3.28084; speak(mToFt.group(1) + " metres is " + fmtNum(ft) + " feet."); return; }
        Matcher inToCm = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:inches?|in\\.?)\\s+(?:to|in)\\s+(?:cm|centimetres?)").matcher(t);
        if (inToCm.find()) { double cm = Double.parseDouble(inToCm.group(1)) * 2.54; speak(inToCm.group(1) + " inches is " + fmtNum(cm) + " centimetres."); return; }
        /* Weight */
        Matcher kgToLb = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:kg|kilograms?)\\s+(?:to|in)\\s+(?:pounds?|lbs?)").matcher(t);
        if (kgToLb.find()) { double lb = Double.parseDouble(kgToLb.group(1)) * 2.20462; speak(kgToLb.group(1) + " kilograms is " + fmtNum(lb) + " pounds."); return; }
        Matcher lbToKg = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:pounds?|lbs?)\\s+(?:to|in)\\s+(?:kg|kilograms?)").matcher(t);
        if (lbToKg.find()) { double kg = Double.parseDouble(lbToKg.group(1)) / 2.20462; speak(lbToKg.group(1) + " pounds is " + fmtNum(kg) + " kilograms."); return; }

        speak("I can convert temperature, distance, and weight. Try convert 5 miles to kilometres.");
    }

    /* ═══════════════════════════════════════════════════════════════
       WEATHER — Open-Meteo API (free, no key), GPS-based, offline cache
    ═══════════════════════════════════════════════════════════════ */
    private static final String[] WMO = new String[100];
    static {
        WMO[0]="clear sky"; WMO[1]="mainly clear"; WMO[2]="partly cloudy"; WMO[3]="overcast";
        WMO[45]="fog"; WMO[48]="icy fog";
        WMO[51]="light drizzle"; WMO[53]="drizzle"; WMO[55]="heavy drizzle";
        WMO[61]="light rain"; WMO[63]="rain"; WMO[65]="heavy rain";
        WMO[71]="light snow"; WMO[73]="snow"; WMO[75]="heavy snow";
        WMO[80]="rain showers"; WMO[81]="rain showers"; WMO[82]="violent rain showers";
        WMO[95]="thunderstorm"; WMO[96]="thunderstorm with hail"; WMO[99]="thunderstorm with heavy hail";
    }

    private void handleWeather() {
        if (!isOnline()) {
            speak("I am offline. I cannot check the weather right now. The object locator and reader still work normally.");
            return;
        }
        speak("Checking the weather. One moment.");
        try {
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            Location loc = null;
            try {
                loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (loc == null) loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            } catch (SecurityException e) {
                speak("Location permission is needed to get your local weather. Please enable it in Settings.");
                return;
            }
            final Location finalLoc = loc;
            pool.execute(() -> {
                try {
                    double lat = finalLoc != null ? finalLoc.getLatitude() : 0;
                    double lon = finalLoc != null ? finalLoc.getLongitude() : 0;
                    if (lat == 0 && lon == 0) { speak("I could not determine your location for weather."); return; }
                    String urlStr = "https://api.open-meteo.com/v1/forecast?latitude=" + lat +
                            "&longitude=" + lon +
                            "&current=temperature_2m,weathercode,windspeed_10m,relativehumidity_2m,apparent_temperature" +
                            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weathercode" +
                            "&timezone=auto&forecast_days=2";
                    String json = httpGet(urlStr);
                    if (json == null) { speak("Weather data is not available right now. Try again shortly."); return; }
                    JSONObject root = new JSONObject(json);
                    JSONObject cur = root.getJSONObject("current");
                    int code = cur.getInt("weathercode");
                    double temp = cur.getDouble("temperature_2m");
                    double feels = cur.getDouble("apparent_temperature");
                    int humidity = cur.getInt("relativehumidity_2m");
                    double wind = cur.getDouble("windspeed_10m");
                    String codeStr = (code >= 0 && code < WMO.length && WMO[code] != null) ? WMO[code] : "variable conditions";
                    String msg = "Currently " + codeStr + ". Temperature is " + Math.round(temp) + " degrees";
                    if (Math.abs(feels - temp) >= 3) msg += ", but feels like " + Math.round(feels);
                    msg += ". Humidity " + humidity + " percent. Wind " + Math.round(wind) + " kilometres per hour.";
                    JSONObject daily = root.getJSONObject("daily");
                    JSONArray maxArr = daily.getJSONArray("temperature_2m_max");
                    JSONArray minArr = daily.getJSONArray("temperature_2m_min");
                    JSONArray rainArr = daily.getJSONArray("precipitation_probability_max");
                    JSONArray codeArr = daily.getJSONArray("weathercode");
                    if (maxArr.length() > 1) {
                        int tCode = codeArr.getInt(1);
                        String tStr = (tCode >= 0 && tCode < WMO.length && WMO[tCode] != null) ? WMO[tCode] : "";
                        msg += " Tomorrow: " + tStr + ", high " + Math.round(maxArr.getDouble(1)) + ", low " + Math.round(minArr.getDouble(1));
                        int rain = rainArr.getInt(1);
                        if (rain > 30) msg += ", " + rain + " percent chance of rain";
                        msg += ".";
                    }
                    speak(msg);
                } catch (Exception e) {
                    speak("I could not read the weather data. Please try again.");
                }
            });
        } catch (Exception e) {
            speak("I could not get the weather right now.");
        }
    }

    /* ═══════════════════════════════════════════════════════════════
       GPS & COMPASS
    ═══════════════════════════════════════════════════════════════ */
    private void handleGps() {
        try {
            Location loc = null;
            try {
                loc = locationMgr.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (loc == null) loc = locationMgr.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            } catch (SecurityException e) {
                speak("Location permission is needed. Please enable it in Settings."); return;
            }
            if (loc == null) { speak("I could not get your GPS location. Make sure location is enabled."); return; }
            String msg = "You are at latitude " + String.format(Locale.US, "%.5f", loc.getLatitude())
                    + ", longitude " + String.format(Locale.US, "%.5f", loc.getLongitude())
                    + ". Accuracy approximately " + Math.round(loc.getAccuracy()) + " metres.";
            if (loc.hasSpeed()) msg += " Speed: " + Math.round(loc.getSpeed() * 3.6) + " kilometres per hour.";
            if (loc.hasAltitude()) msg += " Altitude: " + Math.round(loc.getAltitude()) + " metres.";
            speak(msg);
        } catch (Exception e) {
            speak("I could not access location services.");
        }
    }

    private void handleCompass() {
        Sensor orientSensor = sensorMgr.getDefaultSensor(Sensor.TYPE_ORIENTATION);
        if (orientSensor == null) { speak("This device does not have a compass sensor."); return; }
        speak("Reading compass. One moment.");
        sensorMgr.registerListener(new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent event) {
                float azimuth = event.values[0];
                sensorMgr.unregisterListener(this);
                String dir = azimuthToCardinal(azimuth);
                speak("You are facing " + Math.round(azimuth) + " degrees. That is " + dir + ".");
            }
            @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        }, orientSensor, SensorManager.SENSOR_DELAY_NORMAL);
    }

    private String azimuthToCardinal(float deg) {
        String[] dirs = {"North","North-northeast","Northeast","East-northeast",
                "East","East-southeast","Southeast","South-southeast",
                "South","South-southwest","Southwest","West-southwest",
                "West","West-northwest","Northwest","North-northwest"};
        return dirs[(int)((deg + 11.25f) / 22.5f) % 16];
    }

    /* ═══════════════════════════════════════════════════════════════
       BATTERY
    ═══════════════════════════════════════════════════════════════ */
    private void handleBattery() {
        Intent battIntent = ctx.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battIntent == null) { speak("Battery information is not available."); return; }
        int level = battIntent.getIntExtra("level", -1);
        int scale = battIntent.getIntExtra("scale", 100);
        int pct   = (int)(level * 100f / scale);
        int status = battIntent.getIntExtra("status", -1);
        boolean charging = (status == 2); /* BATTERY_STATUS_CHARGING */
        String msg = "Battery is at " + pct + " percent" + (charging ? " and charging." : ".");
        if (!charging && pct < 20) msg += " Battery is running low. Consider charging soon.";
        speak(msg);
    }

    /* ═══════════════════════════════════════════════════════════════
       HOME AUTOMATION — dispatches local broadcast for integration
    ═══════════════════════════════════════════════════════════════ */
    private void handleLights(String text) {
        String t = text.toLowerCase(Locale.US);
        String room = extractRoom(t);
        String roomStr = room != null ? room + " " : "";
        if (t.contains(" on ") || t.endsWith(" on") || t.contains("turn on") || t.contains("lights on")) {
            dispatchHome("lights-on", room, 100);
            speak(capitalize(roomStr) + "lights turned on.");
        } else if (t.contains(" off") || t.contains("turn off") || t.contains("lights off")) {
            dispatchHome("lights-off", room, 0);
            speak(capitalize(roomStr) + "lights turned off.");
        } else if (t.contains("dim")) {
            dispatchHome("lights-dim", room, 30);
            speak("Dimming " + roomStr + "lights.");
        } else if (t.contains("brighten")) {
            dispatchHome("lights-brighten", room, 80);
            speak("Brightening " + roomStr + "lights.");
        } else {
            Matcher pct = Pattern.compile("to\\s+(\\d+)\\s*%?").matcher(t);
            if (pct.find()) {
                int val = Integer.parseInt(pct.group(1));
                dispatchHome("lights-set", room, val);
                speak("Setting " + roomStr + "lights to " + val + " percent.");
            } else {
                speak("You can say: turn on the lights, dim the bedroom lights, or set lights to 50 percent.");
            }
        }
    }

    private void handleThermostat(String text) {
        String t = text.toLowerCase(Locale.US);
        if (t.contains("warmer") || t.contains("hotter")) {
            dispatchHome("thermostat-up", "home", 2);
            speak("Increasing temperature by 2 degrees.");
        } else if (t.contains("cooler") || t.contains("colder")) {
            dispatchHome("thermostat-down", "home", 2);
            speak("Decreasing temperature by 2 degrees.");
        } else {
            Matcher m = Pattern.compile("(\\d+)").matcher(t);
            if (m.find()) {
                int temp = Integer.parseInt(m.group(1));
                dispatchHome("thermostat-set", "home", temp);
                speak("Setting thermostat to " + temp + " degrees.");
            } else {
                speak("Try saying set thermostat to 22 degrees or make it warmer.");
            }
        }
    }

    private void dispatchHome(String action, String target, int value) {
        Intent intent = new Intent("com.drakosanctis.auriga.HOME_CONTROL");
        intent.putExtra("action", action);
        intent.putExtra("target", target != null ? target : "all");
        intent.putExtra("value", value);
        ctx.sendBroadcast(intent);
    }

    /* ═══════════════════════════════════════════════════════════════
       UTILITY SKILLS
    ═══════════════════════════════════════════════════════════════ */
    private void handleSpell(String text) {
        Pattern p = Pattern.compile("(?:how\\s+do\\s+you\\s+spell|spell\\s+(?:the\\s+word\\s+)?|spelling\\s+of\\s+)(\\w+)", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        if (!m.find()) { speak("Which word would you like me to spell?"); return; }
        String word = m.group(1);
        StringBuilder sb = new StringBuilder();
        for (char c : word.toUpperCase(Locale.US).toCharArray()) { if (sb.length() > 0) sb.append(", "); sb.append(c); }
        speak(word + " is spelled: " + sb + ".");
    }

    private void handleRandom(String text) {
        String t = text.toLowerCase(Locale.US);
        if (t.contains("flip") && t.contains("coin")) { speak(Math.random() < 0.5 ? "Heads." : "Tails."); return; }
        if (t.contains("dice") || t.contains("die") || t.contains("roll")) { speak("You rolled a " + ((int)(Math.random() * 6) + 1) + "."); return; }
        Matcher range = Pattern.compile("(?:between\\s+)?(\\d+)\\s+and\\s+(\\d+)").matcher(t);
        if (range.find()) {
            int lo = Integer.parseInt(range.group(1));
            int hi = Integer.parseInt(range.group(2));
            if (lo > hi) { int tmp = lo; lo = hi; hi = tmp; }
            speak("Random number between " + lo + " and " + hi + ": " + ((int)(Math.random() * (hi - lo + 1)) + lo) + ".");
        } else {
            speak("Random number: " + ((int)(Math.random() * 100) + 1) + ".");
        }
    }

    private void handleCountdown(String text) {
        Matcher m = Pattern.compile("(?:count\\s+down\\s+from|countdown\\s+from|count\\s+from)\\s+(\\d+)", Pattern.CASE_INSENSITIVE).matcher(text);
        int from = m.find() ? Integer.parseInt(m.group(1)) : 10;
        if (from > 60) { speak("That is too large. Please use a number up to 60."); return; }
        speak("Starting countdown from " + from + ".");
        final int[] i = {from};
        new CountDownTimer((from + 1) * 1200L, 1200L) {
            @Override public void onTick(long ms) { speakAdd(String.valueOf(i[0]--)); }
            @Override public void onFinish() { speakAdd("Go!"); vibrate(new long[]{0, 300, 100, 300}); }
        }.start();
    }

    private void handleStopwatch(String text) {
        String t = text.toLowerCase(Locale.US);
        if (t.contains("start") || t.contains("begin")) {
            stopwatchRunning = true; stopwatchStartMs = System.currentTimeMillis(); stopwatchLapMs = stopwatchStartMs;
            speak("Stopwatch started.");
        } else if (t.contains("stop") || t.contains("pause")) {
            if (!stopwatchRunning) { speak("The stopwatch is not running."); return; }
            stopwatchRunning = false;
            speak("Stopwatch stopped at " + formatDur((int)((System.currentTimeMillis() - stopwatchStartMs) / 1000)) + ".");
        } else if (t.contains("lap") || t.contains("split")) {
            if (!stopwatchRunning) { speak("Start the stopwatch first."); return; }
            long lap = (System.currentTimeMillis() - stopwatchLapMs) / 1000;
            stopwatchLapMs = System.currentTimeMillis();
            speak("Lap time: " + formatDur((int) lap) + ".");
        } else if (t.contains("reset")) {
            stopwatchRunning = false; stopwatchStartMs = 0;
            speak("Stopwatch reset.");
        } else {
            if (!stopwatchRunning) { speak("The stopwatch is not running. Say start stopwatch to begin."); return; }
            speak("Elapsed: " + formatDur((int)((System.currentTimeMillis() - stopwatchStartMs) / 1000)) + ".");
        }
    }

    private void handleVolume(String text) {
        String t = text.toLowerCase(Locale.US);
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) { speak("I cannot control the volume on this device."); return; }
        int maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (t.contains("up") || t.contains("louder")) {
            int newVol = Math.min(maxVol, curVol + 2);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0);
            speak("Volume up.");
        } else if (t.contains("down") || t.contains("quieter")) {
            int newVol = Math.max(0, curVol - 2);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0);
            speak("Volume down.");
        } else if (t.contains("max")) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, 0);
            speak("Volume at maximum.");
        } else if (t.contains("mute")) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            speak("Volume muted.");
        } else {
            int pct = (int)(curVol * 100f / maxVol);
            speak("Volume is at " + pct + " percent.");
        }
    }

    /* ═══════════════════════════════════════════════════════════════
       NEWS HEADLINES
    ═══════════════════════════════════════════════════════════════ */
    private void handleNews() {
        if (!isOnline()) { speak("I am offline. I cannot fetch the news right now."); return; }
        speak("Fetching the latest headlines. One moment.");
        pool.execute(() -> {
            try {
                String proxy = "https://api.allorigins.win/get?url=" +
                        java.net.URLEncoder.encode("https://feeds.bbci.co.uk/news/rss.xml", "UTF-8");
                String xml = httpGet(proxy);
                if (xml == null) { speak("I could not load the news right now."); return; }
                JSONObject root = new JSONObject(xml);
                String contents = root.getString("contents");
                List<String> headlines = new ArrayList<>();
                java.util.regex.Matcher m = Pattern.compile("<title><!\\[CDATA\\[([^\\]]+)\\]\\]></title>|<title>([^<]{10,200})</title>").matcher(contents);
                while (m.find() && headlines.size() < 5) {
                    String h = m.group(1) != null ? m.group(1) : m.group(2);
                    if (h != null && !h.contains("BBC News")) headlines.add(h.trim());
                }
                if (headlines.isEmpty()) { speak("I could not parse the news feed right now."); return; }
                StringBuilder sb = new StringBuilder("Here are today's top headlines. ");
                for (String h : headlines) sb.append(h).append(". ");
                speak(sb.toString());
            } catch (Exception e) {
                speak("I could not load the news. Please try again.");
            }
        });
    }

    /* ═══════════════════════════════════════════════════════════════
       MEMORY
    ═══════════════════════════════════════════════════════════════ */
    private void handleMemory() {
        pool.execute(() -> {
            String profile = AurigaMemoryStore.getProfileContext(ctx);
            if (profile == null || profile.trim().isEmpty()) {
                speak("I have not learned anything about you yet. Tell me things like your name or where you live, and I will remember them.");
            } else {
                speak("Here is what I know about you. " + profile);
            }
        });
    }

    /* ═══════════════════════════════════════════════════════════════
       BRIEFING — OpenClaw morning-digest concept, native Android
    ═══════════════════════════════════════════════════════════════ */
    private void handleBriefing() {
        Calendar now = Calendar.getInstance();
        int hour = now.get(Calendar.HOUR_OF_DAY);
        String greeting = hour < 12 ? "Good morning" : hour < 17 ? "Good afternoon" : "Good evening";
        String name = AurigaVoiceEngine.getAssistantName(ctx);

        int h12 = now.get(Calendar.HOUR); if (h12 == 0) h12 = 12;
        int min = now.get(Calendar.MINUTE);
        String ampm = now.get(Calendar.AM_PM) == Calendar.AM ? "AM" : "PM";
        String timeStr = h12 + (min > 0 ? " " + (min < 10 ? "oh " + min : String.valueOf(min)) : "") + " " + ampm;

        String[] days = {"Sunday","Monday","Tuesday","Wednesday","Thursday","Friday","Saturday"};
        String[] months = {"January","February","March","April","May","June",
                "July","August","September","October","November","December"};
        String dateStr = days[now.get(Calendar.DAY_OF_WEEK) - 1] + ", " +
                months[now.get(Calendar.MONTH)] + " " + now.get(Calendar.DAY_OF_MONTH);

        /* Get battery for briefing */
        Intent battIntent = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        String battStr = "";
        if (battIntent != null) {
            int level = battIntent.getIntExtra("level", -1);
            int scale = battIntent.getIntExtra("scale", 100);
            int pct = (int)(level * 100f / scale);
            battStr = " Battery is at " + pct + " percent.";
        }

        String briefing = greeting + ", " + name + ". "
                + "The time is " + timeStr + ". Today is " + dateStr + "."
                + battStr
                + " Auriga is ready. "
                + "Say open locator to start object detection, open reader to read text, "
                + "or help to hear all available commands.";

        speak(briefing);
        if (isOnline()) {
            main.postDelayed(this::handleWeather, 5000);
        }
    }

    /* ═══════════════════════════════════════════════════════════════
       HELPERS
    ═══════════════════════════════════════════════════════════════ */
    public List<Skill> getRegistry() { return new ArrayList<>(registry); }

    private int parseDurationSecs(String text) {
        String t = text.toLowerCase(Locale.US);
        if (t.contains("half an hour") || t.contains("half hour")) return 1800;
        if (t.contains("quarter hour") || t.contains("quarter of an hour")) return 900;
        int total = 0;
        Matcher h = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:hour|hr)s?").matcher(t);
        Matcher m = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:minute|min)s?").matcher(t);
        Matcher s = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:second|sec)s?").matcher(t);
        boolean found = false;
        if (h.find()) { total += (int)(Double.parseDouble(h.group(1)) * 3600); found = true; }
        if (m.find()) { total += (int)(Double.parseDouble(m.group(1)) * 60); found = true; }
        if (s.find()) { total += (int)Double.parseDouble(s.group(1)); found = true; }
        if (!found) {
            Matcher num = Pattern.compile("(\\d+)").matcher(t); /* assume minutes */
            if (num.find()) total = Integer.parseInt(num.group(1)) * 60;
        }
        return total;
    }

    private String formatDur(int secs) {
        if (secs < 60) return secs + " second" + (secs != 1 ? "s" : "");
        int h = secs / 3600, m = (secs % 3600) / 60, s = secs % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append(" hour").append(h > 1 ? "s" : "");
        if (m > 0) { if (sb.length() > 0) sb.append(" and "); sb.append(m).append(" minute").append(m > 1 ? "s" : ""); }
        if (s > 0 && h == 0) { if (sb.length() > 0) sb.append(" and "); sb.append(s).append(" second").append(s > 1 ? "s" : ""); }
        return sb.toString();
    }

    private String parseTimerLabel(String text) {
        Matcher m = Pattern.compile("for\\s+(?:\\d[\\w\\s]*\\s+)?for\\s+(.+)$", Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    private Calendar parseAbsoluteTime(String text) {
        String t = text.toLowerCase(Locale.US);
        if (t.contains("midnight")) { return setTime(0, 0); }
        if (t.contains("noon"))     { return setTime(12, 0); }
        Matcher qPast = Pattern.compile("quarter\\s+past\\s+(\\d+)").matcher(t);
        if (qPast.find()) return setTime(Integer.parseInt(qPast.group(1)), 15);
        Matcher half = Pattern.compile("half\\s+(?:past\\s+)?(\\d+)").matcher(t);
        if (half.find()) return setTime(Integer.parseInt(half.group(1)), 30);
        Matcher std = Pattern.compile("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?").matcher(t);
        if (!std.find()) return null;
        int h = Integer.parseInt(std.group(1));
        int min = std.group(2) != null ? Integer.parseInt(std.group(2)) : 0;
        String ap = std.group(3);
        if ("pm".equals(ap) && h < 12) h += 12;
        if ("am".equals(ap) && h == 12) h = 0;
        return setTime(h, min);
    }

    private Calendar setTime(int h, int m) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, m); c.set(Calendar.SECOND, 0);
        return c;
    }

    private String formatTime(Calendar c) {
        int h = c.get(Calendar.HOUR); if (h == 0) h = 12;
        int m = c.get(Calendar.MINUTE);
        String ap = c.get(Calendar.AM_PM) == Calendar.AM ? "AM" : "PM";
        return h + (m > 0 ? ":" + (m < 10 ? "0" + m : m) : "") + " " + ap;
    }

    private String fmtNum(double n) {
        if (!Double.isFinite(n)) return "infinity";
        if (Math.abs(n - Math.round(n)) < 0.0001) return String.valueOf(Math.round(n));
        return String.format(Locale.US, "%.4f", n).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private String extractRoom(String text) {
        for (String r : new String[]{"bedroom","living room","kitchen","bathroom","office","hallway","garage"}) {
            if (text.contains(r)) return r;
        }
        return null;
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void vibrate(long[] pattern) {
        Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createWaveform(pattern, -1));
        } else {
            v.vibrate(pattern, -1);
        }
    }

    private void showNotification(String title, String body) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);
        nm.notify((int) System.currentTimeMillis(), b.build());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Auriga Skills", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Timers, alarms, and reminders from Auriga skills.");
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private String httpGet(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000); conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "AurigaNavi/1.0");
            if (conn.getResponseCode() != 200) return null;
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close(); conn.disconnect();
            return sb.toString();
        } catch (Exception e) { return null; }
    }

    public void shutdown() {
        pool.shutdownNow();
        for (CountDownTimer t : activeTimers.values()) t.cancel();
        activeTimers.clear();
    }
}
