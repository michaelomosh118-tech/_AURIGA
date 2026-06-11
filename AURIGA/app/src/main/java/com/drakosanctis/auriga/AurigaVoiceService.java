package com.drakosanctis.auriga;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;
import java.util.Locale;

/**
 * AurigaVoiceService — always-on foreground service for wake-word detection.
 *
 * <h3>Architecture (AudioRecord VAD + gated SpeechRecognizer)</h3>
 *
 * <p><b>Problem with the old approach:</b> Calling {@link SpeechRecognizer#startListening}
 * in a tight loop causes the recognizer's internal Google process to request
 * {@link AudioManager#AUDIOFOCUS_GAIN} on every ~300 ms cycle. This ducks music
 * and media playback continuously — a disruptive experience for blind users who
 * rely on audio.
 *
 * <p><b>Fix:</b> Replace the always-on SpeechRecognizer loop with an
 * {@link AudioRecord} + energy-RMS Voice Activity Detector (VAD). AudioRecord
 * reads PCM directly from the microphone without requesting audio focus at all —
 * music plays uninterrupted. Only when the VAD confirms sustained speech (≥ 300 ms
 * above the RMS threshold) is a one-shot {@link SpeechRecognizer} fired. The
 * recognizer is active for at most 2–3 seconds per utterance, then the VAD loop
 * resumes silently.
 *
 * <p>Start / stop with the static helpers:
 * <pre>
 *   AurigaVoiceService.startListening(context);
 *   AurigaVoiceService.stopListening(context);
 * </pre>
 */
public class AurigaVoiceService extends Service implements RecognitionListener {

    private static final String TAG = "AurigaVoiceService";

    private static final String CHANNEL_ID = "auriga_voice_channel";
    private static final int    NOTIF_ID   = 8801;

    // ── VAD tuning constants ──────────────────────────────────────────────────
    private static final int   SAMPLE_RATE          = 16000;            // Hz
    private static final int   CHUNK_FRAMES         = SAMPLE_RATE / 10; // 100 ms
    private static final float VAD_RMS_THRESHOLD    = 800f;  // 0–32768 scale; tune for environment
    private static final int   SPEECH_TRIGGER_CHUNKS = 3;    // 3 × 100 ms = 300 ms of sustained speech
    private static final long  RECOGNIZER_COOLDOWN_MS = 2000L; // minimum gap between recognizer invocations

    // ── State ─────────────────────────────────────────────────────────────────
    private final Handler  handler       = new Handler(Looper.getMainLooper());
    private AudioRecord    audioRecord;
    private Thread         vadThread;
    private volatile boolean vadRunning   = false;
    private volatile boolean recognizerBusy = false;
    private long           lastRecognitionAt = 0L;
    private SpeechRecognizer recognizer;
    private boolean        systemMuted   = false;

    // ── Static helpers ────────────────────────────────────────────────────────

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

    // ── Service lifecycle ─────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!vadRunning) {
            startVadLoop();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        // Signal VAD thread to exit
        vadRunning = false;

        // Release AudioRecord immediately so the thread unblocks from read()
        AudioRecord ar = audioRecord;
        if (ar != null) {
            try {
                ar.stop();
                ar.release();
            } catch (Throwable ignored) {}
            audioRecord = null;
        }

        // Wait briefly for the VAD thread to finish
        if (vadThread != null) {
            try { vadThread.join(500); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            vadThread = null;
        }

        handler.removeCallbacksAndMessages(null);
        unmuteSystem();

        if (recognizer != null) {
            try { recognizer.cancel(); recognizer.destroy(); }
            catch (Throwable ignored) {}
            recognizer = null;
        }
        super.onDestroy();
    }

    // ── VAD loop ──────────────────────────────────────────────────────────────

    private void startVadLoop() {
        int minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "AudioRecord.getMinBufferSize() failed — falling back to SpeechRecognizer loop.");
            fallbackToRecognizerLoop();
            return;
        }
        int bufSize = Math.max(minBuf, CHUNK_FRAMES * 2);

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize);
        } catch (Throwable t) {
            Log.e(TAG, "AudioRecord constructor failed", t);
            fallbackToRecognizerLoop();
            return;
        }

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized — falling back.");
            audioRecord.release();
            audioRecord = null;
            fallbackToRecognizerLoop();
            return;
        }

        try {
            audioRecord.startRecording();
        } catch (Throwable t) {
            Log.e(TAG, "AudioRecord.startRecording() failed", t);
            audioRecord.release();
            audioRecord = null;
            fallbackToRecognizerLoop();
            return;
        }

        vadRunning = true;
        vadThread = new Thread(this::vadLoop, "auriga-vad");
        vadThread.setDaemon(true);
        vadThread.start();
        Log.i(TAG, "VAD loop started (AudioRecord, no audio-focus requests).");
    }

    private void vadLoop() {
        short[] buf = new short[CHUNK_FRAMES];
        int speechChunks = 0;

        while (vadRunning) {
            AudioRecord ar = audioRecord;
            if (ar == null) break;

            int read;
            try {
                read = ar.read(buf, 0, CHUNK_FRAMES);
            } catch (Throwable t) {
                break; // AudioRecord was released
            }
            if (read <= 0) continue;

            float rms = computeRms(buf, read);

            if (rms > VAD_RMS_THRESHOLD) {
                speechChunks++;
                if (speechChunks >= SPEECH_TRIGGER_CHUNKS && !recognizerBusy) {
                    long now = System.currentTimeMillis();
                    if (now - lastRecognitionAt >= RECOGNIZER_COOLDOWN_MS) {
                        recognizerBusy = true;
                        speechChunks   = 0;
                        handler.post(this::fireRecognizer);
                    }
                }
            } else {
                // Gradually decay the counter so brief noises don't accumulate
                if (speechChunks > 0) speechChunks--;
            }
        }
        Log.i(TAG, "VAD loop exited.");
    }

    private static float computeRms(short[] buf, int count) {
        if (count <= 0) return 0f;
        long sum = 0;
        for (int i = 0; i < count; i++) sum += (long) buf[i] * buf[i];
        return (float) Math.sqrt((double) sum / count);
    }

    // ── Gated SpeechRecognizer ────────────────────────────────────────────────

    private void fireRecognizer() {
        if (!vadRunning) { recognizerBusy = false; return; }
        if (recognizer == null) {
            recognizer = createRecognizer();
            if (recognizer == null) { recognizerBusy = false; return; }
        }
        muteSystem();
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        intent.putExtra("android.speech.extra.PREFER_OFFLINE", true);
        try {
            recognizer.startListening(intent);
        } catch (Throwable t) {
            Log.e(TAG, "startListening failed", t);
            unmuteSystem();
            recognizerBusy = false;
        }
    }

    private SpeechRecognizer createRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return null;
        try {
            SpeechRecognizer sr = SpeechRecognizer.createSpeechRecognizer(this);
            sr.setRecognitionListener(this);
            return sr;
        } catch (Throwable t) {
            Log.e(TAG, "SpeechRecognizer creation failed", t);
            return null;
        }
    }

    /**
     * Fallback for devices that do not support AudioRecord with VOICE_RECOGNITION
     * (rare; some emulators). Degrades gracefully to the original SpeechRecognizer
     * loop, accepting the music-ducking side-effect on those devices.
     */
    private void fallbackToRecognizerLoop() {
        Log.w(TAG, "Falling back to SpeechRecognizer loop (music may be ducked on this device).");
        recognizer = createRecognizer();
        if (recognizer == null) return;
        vadRunning = true; // keep service alive
        handler.post(this::startLegacyCycle);
    }

    private void startLegacyCycle() {
        if (!vadRunning || recognizer == null) return;
        muteSystem();
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        intent.putExtra("android.speech.extra.PREFER_OFFLINE", true);
        try {
            recognizer.startListening(intent);
        } catch (Throwable t) {
            unmuteSystem();
            handler.postDelayed(this::startLegacyCycle, 2000);
        }
    }

    // ── RecognitionListener ───────────────────────────────────────────────────

    @Override
    public void onReadyForSpeech(Bundle params) {
        unmuteSystem();
    }

    @Override public void onBeginningOfSpeech() {}
    @Override public void onRmsChanged(float rmsdB) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEndOfSpeech() {}

    @Override
    public void onError(int error) {
        unmuteSystem();
        recognizerBusy = false;
        // Recreate recognizer on non-transient errors
        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) return;
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (Throwable ignored) {}
            recognizer = null;
        }
        if (!vadRunning) return;
        // If we're in fallback (legacy) mode, restart the cycle after a delay
        if (audioRecord == null) {
            handler.postDelayed(this::startLegacyCycle, 1500);
        }
    }

    @Override
    public void onResults(Bundle results) {
        unmuteSystem();
        recognizerBusy = false;
        lastRecognitionAt = System.currentTimeMillis();

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

        // In legacy fallback mode, schedule the next cycle
        if (audioRecord == null && vadRunning) {
            handler.postDelayed(this::startLegacyCycle, 300);
        }
    }

    @Override public void onPartialResults(Bundle partialResults) {}
    @Override public void onEvent(int eventType, Bundle params) {}

    // ── Wake phrase detection ─────────────────────────────────────────────────

    private boolean isWakePhrase(String text) {
        if (text == null) return false;
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

    // ── System audio mute (suppress the SpeechRecognizer "ding") ─────────────

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

    // ── Persistent notification ───────────────────────────────────────────────

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
