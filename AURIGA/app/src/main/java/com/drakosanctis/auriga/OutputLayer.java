package com.drakosanctis.auriga;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OutputLayer — unified output bus for the entire Auriga ecosystem.
 *
 * <p>Implements {@link AurigaInterfaces.IOutputLayer}. Every module in the ecosystem
 * calls this class instead of touching TTS or the vibrator directly. This gives
 * one place to enforce:
 *
 * <ul>
 *   <li><b>Priority pre-emption</b> — EMERGENCY always flushes; BACKGROUND is
 *       dropped when the queue is congested, so low-value "background" speech
 *       never delays a critical warning.</li>
 *   <li><b>Global mute</b> — {@link #setMuted(boolean)} silences all outputs
 *       except EMERGENCY, which is never suppressed.</li>
 *   <li><b>Haptic patterns</b> — maps the abstract {@link AurigaInterfaces.HapticPattern}
 *       and {@link AurigaInterfaces.HapticZone} enums to concrete vibration
 *       waveforms. Zone differentiation is logged for future satellite-node
 *       routing (ESP32 BLE haptic nodes) even though the phone vibrator is
 *       direction-agnostic.</li>
 *   <li><b>Braille stub</b> — logs the text line for future BrailleBack / USB
 *       Braille display integration; no-op until the hardware is wired.</li>
 * </ul>
 *
 * <h3>Priority rules</h3>
 * <pre>
 *   EMERGENCY → tts.speak(QUEUE_FLUSH); haptic SOS; ignores mute
 *   HIGH      → tts.speak(QUEUE_FLUSH) if current ≤ NORMAL; haptic pulse
 *   NORMAL    → tts.speak(QUEUE_ADD)
 *   BACKGROUND→ tts.speak(QUEUE_ADD) only if pending queue &lt; BACKGROUND_MAX
 * </pre>
 *
 * <h3>Thread safety</h3>
 * All public methods are safe to call from any thread. TTS callbacks arrive on
 * the main thread; vibrator calls are fire-and-forget.
 */
public class OutputLayer implements AurigaInterfaces.IOutputLayer {

    private static final String TAG = "OutputLayer";

    // Max BACKGROUND utterances that may sit in the TTS queue before new
    // ones are dropped.  Prevents a flood of low-priority speech from
    // blocking urgent warnings.
    private static final int BACKGROUND_MAX_QUEUED = 2;

    // ── Priority ordinals (higher = more urgent) ──────────────────────────
    private static final int PRI_BACKGROUND = 0;
    private static final int PRI_NORMAL     = 1;
    private static final int PRI_HIGH       = 2;
    private static final int PRI_EMERGENCY  = 3;

    private final Context      ctx;
    private final Vibrator     vibrator;
    private final AtomicBoolean muted           = new AtomicBoolean(false);
    private final AtomicInteger pendingCount    = new AtomicInteger(0);
    private final AtomicReference<AurigaInterfaces.OutputPriority> currentPriority =
            new AtomicReference<>(AurigaInterfaces.OutputPriority.BACKGROUND);

    private volatile TextToSpeech tts;
    private volatile boolean      ttsReady = false;

    // ─────────────────────────────────────────────────────────────────────────
    // Construction
    // ─────────────────────────────────────────────────────────────────────────

    public OutputLayer(Context context) {
        this.ctx      = context.getApplicationContext();
        this.vibrator = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        initTts();
    }

    private void initTts() {
        tts = new TextToSpeech(ctx, status -> {
            if (status == TextToSpeech.SUCCESS && tts != null) {
                int langResult = tts.setLanguage(Locale.US);
                if (langResult == TextToSpeech.LANG_MISSING_DATA
                        || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.getDefault());
                }
                tts.setSpeechRate(0.95f);
                tts.setPitch(1.0f);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id)  { /* no-op */ }
                    @Override public void onDone(String id)   { onUtteranceDone();  }
                    @Override public void onError(String id)  { onUtteranceDone();  }
                });
                ttsReady = true;
                Log.i(TAG, "TTS ready");
            } else {
                Log.e(TAG, "TTS init failed, status=" + status);
            }
        });
    }

    private void onUtteranceDone() {
        int remaining = pendingCount.decrementAndGet();
        if (remaining <= 0) {
            pendingCount.set(0);
            currentPriority.set(AurigaInterfaces.OutputPriority.BACKGROUND);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IOutputLayer — speak
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void speak(String text, AurigaInterfaces.OutputPriority priority) {
        if (text == null || text.trim().isEmpty()) return;

        boolean isMuted = muted.get();

        // EMERGENCY is never suppressed by mute
        if (isMuted && priority != AurigaInterfaces.OutputPriority.EMERGENCY) return;
        if (!ttsReady || tts == null) {
            Log.w(TAG, "speak() called but TTS not ready: " + text);
            return;
        }

        int ord = ordinal(priority);
        String uid = "auriga_out_" + priority.name() + "_" + System.currentTimeMillis();

        switch (priority) {

            case EMERGENCY:
                // Cancel everything; speak immediately regardless of mute
                tts.stop();
                pendingCount.set(0);
                currentPriority.set(priority);
                pendingCount.incrementAndGet();
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, uid);
                // Mirror emergency announcement with haptic SOS
                vibratePattern(AurigaInterfaces.HapticPattern.SOS,
                               AurigaInterfaces.HapticZone.ALL);
                Log.w(TAG, "EMERGENCY speak: " + text);
                break;

            case HIGH:
                // Flush if current utterance is of lower priority
                if (ordinal(currentPriority.get()) < ord) {
                    tts.stop();
                    pendingCount.set(0);
                    currentPriority.set(priority);
                    pendingCount.incrementAndGet();
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, uid);
                } else {
                    currentPriority.set(priority);
                    pendingCount.incrementAndGet();
                    tts.speak(text, TextToSpeech.QUEUE_ADD, null, uid);
                }
                break;

            case NORMAL:
                currentPriority.compareAndSet(
                        AurigaInterfaces.OutputPriority.BACKGROUND, priority);
                pendingCount.incrementAndGet();
                tts.speak(text, TextToSpeech.QUEUE_ADD, null, uid);
                break;

            case BACKGROUND:
                // Drop if the queue is already congested
                if (pendingCount.get() >= BACKGROUND_MAX_QUEUED) {
                    Log.d(TAG, "BACKGROUND dropped (queue full): " + text);
                    return;
                }
                pendingCount.incrementAndGet();
                tts.speak(text, TextToSpeech.QUEUE_ADD, null, uid);
                break;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IOutputLayer — haptic
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void haptic(AurigaInterfaces.HapticPattern pattern,
                       AurigaInterfaces.HapticZone zone) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        // Zone is logged for future satellite-node routing (BLE haptic vest/glove)
        Log.d(TAG, "haptic: " + pattern + " zone=" + zone);
        vibratePattern(pattern, zone);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IOutputLayer — braille
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void braille(String text) {
        // Stub: log for future BrailleBack / USB Braille-display integration.
        // When a Braille device is paired, this method will write the text
        // as a BRF byte stream to a BluetoothSocket or USB serial port.
        Log.i(TAG, "BRAILLE: " + text);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IOutputLayer — earcon
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void playEarcon(int earconId) {
        // Stub: earcons are short non-speech audio cues (e.g. a subtle tick
        // when the locator acquires a target). Implementation will use a
        // SoundPool loaded from res/raw/ once the audio asset pipeline is added.
        Log.d(TAG, "earcon: id=" + earconId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IOutputLayer — mute / lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void setMuted(boolean m) {
        muted.set(m);
        if (m && ttsReady && tts != null) tts.stop();
        Log.i(TAG, "muted=" + m);
    }

    @Override
    public boolean isMuted() {
        return muted.get();
    }

    @Override
    public void shutdown() {
        ttsReady = false;
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Throwable ignored) {}
            tts = null;
        }
        if (vibrator != null) {
            try { vibrator.cancel(); } catch (Throwable ignored) {}
        }
        Log.i(TAG, "shutdown");
    }

    @Override
    public boolean selfTest(Context c) {
        boolean ok = ttsReady && tts != null
                  && (vibrator == null || vibrator.hasVibrator());
        Log.i(TAG, "selfTest → " + ok);
        return ok;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Vibration pattern lookup
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Maps abstract {@link AurigaInterfaces.HapticPattern} values to concrete
     * vibration waveforms. All durations are in milliseconds.
     *
     * <p>Zone mapping for future satellite nodes:
     * <ul>
     *   <li>LEFT  → left wrist band / left glove</li>
     *   <li>RIGHT → right wrist band / right glove</li>
     *   <li>CENTER / ALL → phone body (current hardware)</li>
     * </ul>
     */
    private void vibratePattern(AurigaInterfaces.HapticPattern pattern,
                                AurigaInterfaces.HapticZone zone) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        try {
            switch (pattern) {

                case SINGLE:
                    // One crisp tap — command acknowledgement, target acquired
                    vibrateSingle(80);
                    break;

                case COMMAND_ACCEPTED:
                    vibrateSingle(30);
                    break;

                case COMMAND_REJECTED:
                    // Double low pulse — "no"
                    vibrateWaveform(new long[]{0, 150, 80, 150}, -1);
                    break;

                case SLOW_PULSE:
                    // Long-range obstacle — steady, reassuring
                    vibrateSingle(200);
                    break;

                case FAST_PULSE:
                    // Close-range obstacle — urgent
                    vibrateSingle(70);
                    break;

                case OBSTACLE_NEAR:
                    // Very close — sharp double tap
                    vibrateWaveform(new long[]{0, 40, 30, 40}, -1);
                    break;

                case STAIR_WARN:
                    // Three-beat rhythm — stair detected
                    vibrateWaveform(new long[]{0, 250, 100, 250, 100, 250}, -1);
                    break;

                case SOS:
                    // International SOS: ···---···
                    vibrateWaveform(new long[]{
                            0,  80, 60,   // · · ·
                               80, 60,
                               80, 120,
                              200, 60,   // — — —
                              200, 60,
                              200, 120,
                               80, 60,   // · · ·
                               80, 60,
                               80
                    }, -1);
                    break;

                default:
                    vibrateSingle(100);
                    break;
            }
        } catch (Throwable t) {
            Log.e(TAG, "vibrate error", t);
        }
    }

    private void vibrateSingle(long ms) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(
                    ms, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            //noinspection deprecation
            vibrator.vibrate(ms);
        }
    }

    private void vibrateWaveform(long[] pattern, int repeat) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, repeat));
        } else {
            //noinspection deprecation
            vibrator.vibrate(pattern, repeat);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static int ordinal(AurigaInterfaces.OutputPriority p) {
        switch (p) {
            case EMERGENCY:  return PRI_EMERGENCY;
            case HIGH:       return PRI_HIGH;
            case NORMAL:     return PRI_NORMAL;
            default:         return PRI_BACKGROUND;
        }
    }
}
