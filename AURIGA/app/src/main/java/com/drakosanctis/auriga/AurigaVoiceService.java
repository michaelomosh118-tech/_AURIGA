package com.drakosanctis.auriga;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.Locale;

/**
 * AurigaVoiceService — always-on foreground service for wake word detection.
 *
 * Keeps a {@link SpeechRecognizer} running in the background (even with
 * the screen off) and broadcasts {@link AurigaVoiceEngine#ACTION_WAKE_WORD}
 * whenever it hears the wake phrase "[name] AURIGA".
 *
 * Any activity that wants to respond to the wake word should register a
 * {@link android.content.BroadcastReceiver} for that action and call
 * {@link AurigaVoiceEngine#startListening()} in response.
 *
 * Start / stop with the static helpers:
 *   AurigaVoiceService.startListening(context);
 *   AurigaVoiceService.stopListening(context);
 *
 * MIC BEEP FIX: Android's SpeechRecognizer plays a system "ding" on every
 * startListening() call. We silence STREAM_SYSTEM for the ~300 ms window
 * between the call and onReadyForSpeech() so the user never hears it.
 */
public class AurigaVoiceService extends Service implements RecognitionListener {

    private static final String CHANNEL_ID = "auriga_voice_channel";
    private static final int    NOTIF_ID   = 8801;

    private SpeechRecognizer recognizer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running      = false;
    private int     restartDelay = 500;
    private boolean systemMuted  = false;

    // ── Static helpers ────────────────────────────────────────────

    public static void startListening(Context ctx) {
        Intent i = new Intent(ctx, AurigaVoiceService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stopListening(Context ctx) {
        ctx.stopService(new Intent(ctx, AurigaVoiceService.class));
    }

    // ── Service lifecycle ─────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification());
        initRecognizer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            running = true;
            startCycle();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        unmuteSystem(); // always clean up before we die
        if (recognizer != null) {
            try { recognizer.cancel(); recognizer.destroy(); }
            catch (Throwable ignored) {}
            recognizer = null;
        }
        super.onDestroy();
    }

    // ── SpeechRecognizer loop ─────────────────────────────────────

    private void initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return;
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(this);
        } catch (Throwable t) {
            recognizer = null;
        }
    }

    private void startCycle() {
        if (!running || recognizer == null) return;
        try {
            // Silence the "mic on" ding BEFORE we start the recognizer.
            // We unmute in onReadyForSpeech() once the recognizer is
            // actually ready, so the window is as short as possible.
            muteSystem();

            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,
                    Locale.getDefault());
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            // Prefer on-device recognition where available — avoids the
            // Google-server round-trip tone on some OEM firmwares.
            intent.putExtra("android.speech.extra.PREFER_OFFLINE", true);
            recognizer.startListening(intent);
        } catch (Throwable t) {
            unmuteSystem();
            scheduleRestart(2000);
        }
    }

    private void scheduleRestart(int delayMs) {
        if (!running) return;
        handler.postDelayed(this::startCycle, delayMs);
    }

    // ── System audio mute helpers ─────────────────────────────────
    // API 23+ (min SDK 24 here, so always safe).

    private void muteSystem() {
        if (systemMuted) return;
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.adjustStreamVolume(AudioManager.STREAM_SYSTEM,
                        AudioManager.ADJUST_MUTE, 0);
                systemMuted = true;
            }
        } catch (Throwable ignored) {}
    }

    private void unmuteSystem() {
        if (!systemMuted) return;
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.adjustStreamVolume(AudioManager.STREAM_SYSTEM,
                        AudioManager.ADJUST_UNMUTE, 0);
                systemMuted = false;
            }
        } catch (Throwable ignored) {}
    }

    // ── RecognitionListener ───────────────────────────────────────

    @Override
    public void onReadyForSpeech(Bundle params) {
        // Recognizer is fully initialised and listening — safe to unmute.
        unmuteSystem();
        restartDelay = 500;
    }

    @Override public void onBeginningOfSpeech() {}
    @Override public void onRmsChanged(float rmsdB) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEndOfSpeech() {}

    @Override
    public void onError(int error) {
        // Always unmute on any error path so we don't leave the device
        // silently muted if the recognizer fails before onReadyForSpeech.
        unmuteSystem();
        scheduleRestart(restartDelay);
        restartDelay = Math.min(restartDelay * 2, 8000);
    }

    @Override
    public void onResults(Bundle results) {
        unmuteSystem(); // safety net — should already be unmuted
        restartDelay = 500;
        ArrayList<String> matches =
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches != null) {
            for (String m : matches) {
                if (isWakePhrase(m)) {
                    sendWakeBroadcast();
                    break;
                }
            }
        }
        scheduleRestart(300);
    }

    @Override public void onPartialResults(Bundle partialResults) {}
    @Override public void onEvent(int eventType, Bundle params) {}

    // ── Wake phrase detection ─────────────────────────────────────

    private boolean isWakePhrase(String text) {
        String t    = text.toLowerCase(Locale.US).trim();
        String name = AurigaVoiceEngine.getAssistantName(this)
                .toLowerCase(Locale.US).trim();
        return t.contains(name + " auriga")
                || t.startsWith("hey auriga")
                || t.startsWith("ok auriga")
                || t.startsWith("hello auriga")
                || t.startsWith("yo auriga")
                || t.equals("auriga");
    }

    private void sendWakeBroadcast() {
        Intent broadcast = new Intent(AurigaVoiceEngine.ACTION_WAKE_WORD);
        broadcast.setPackage(getPackageName());
        sendBroadcast(broadcast);
    }

    // ── Persistent notification ───────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "Auriga Voice — Wake Word",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Listening for your Auriga wake phrase.");
            ch.setSound(null, null);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent tap = new Intent(this, LocatorActivity.class);
        tap.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, tap,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            //noinspection deprecation
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle("Auriga Voice Active")
                .setContentText("Listening for your wake phrase…")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }
}
