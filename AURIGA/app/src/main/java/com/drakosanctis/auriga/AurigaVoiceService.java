package com.drakosanctis.auriga;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
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
 */
public class AurigaVoiceService extends Service implements RecognitionListener {

    private static final String CHANNEL_ID = "auriga_voice_channel";
    private static final int    NOTIF_ID   = 8801;

    private SpeechRecognizer recognizer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running      = false;
    private int     restartDelay = 500;

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
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,
                    Locale.getDefault());
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            recognizer.startListening(intent);
        } catch (Throwable t) {
            scheduleRestart(2000);
        }
    }

    private void scheduleRestart(int delayMs) {
        if (!running) return;
        handler.postDelayed(this::startCycle, delayMs);
    }

    // ── RecognitionListener ───────────────────────────────────────

    @Override public void onReadyForSpeech(Bundle params) { restartDelay = 500; }
    @Override public void onBeginningOfSpeech() {}
    @Override public void onRmsChanged(float rmsdB) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEndOfSpeech() {}

    @Override
    public void onError(int error) {
        scheduleRestart(restartDelay);
        restartDelay = Math.min(restartDelay * 2, 8000);
    }

    @Override
    public void onResults(Bundle results) {
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
