package com.drakosanctis.auriga;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

/**
 * SonarManager: The "AuraTextures™" audio engine.
 * Provides stereo, proximity-scaled beep feedback.
 * Beeps speed up as you approach, providing an intuitive "sound-feel" map.
 */
public class SonarManager {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isPlaying = false;
    private float currentDistance = -1.0f;
    private float currentBearing = 0.0f;
    private String currentSignature = "OBJECT";

    private static final int SAMPLE_RATE = 44100;
    private AudioTrack audioTrack;

    public SonarManager() {
        initAudioTrack();
    }

    /**
     * updateSpatialData: Updates the feedback loop with new distance and bearing.
     */
    public void updateSpatialData(float distanceM, float bearingDeg, String signature) {
        this.currentDistance = distanceM;
        this.currentBearing = bearingDeg;
        this.currentSignature = signature;
        
        if (!isPlaying && distanceM > 0) {
            startLoop();
        }
    }

    private void startLoop() {
        isPlaying = true;
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (currentDistance <= 0 || !isPlaying) {
                    isPlaying = false;
                    return;
                }

                playTone();

                // Proximity-scaled interval: beeps speed up as distance decreases.
                // Min 160ms, max 4.0s (at 10m)
                long interval = (long) Math.max(160, currentDistance * 420);
                handler.postDelayed(this, interval);
            }
        });
    }

    /**
     * playTone: Generates a sine wave tone with stereo panning.
     * AuraTextures™: Signature-based frequency (Wall vs Pole).
     */
    private void playTone() {
        if (!AurigaConfig.hasAuraTextures()) return;

        int freq = 1000; // Base freq for OBJECT
        if ("WALL".equals(currentSignature)) freq = 600;  // Broad, low sound
        if ("POLE".equals(currentSignature)) freq = 1400; // Sharp, high sound
        if ("OVERHANG".equals(currentSignature)) freq = 1700; // Urgent alert

        int duration = 100; // ms
        int numSamples = (int) (duration * SAMPLE_RATE / 1000);
        double[] sample = new double[numSamples];
        short[] generatedSnd = new short[2 * numSamples]; // *2 for stereo

        // Stereo panning: pan = (bearing / 32) -> -1.0 to 1.0
        float pan = currentBearing / 32.0f;
        float leftVol = Math.max(0.0f, 1.0f - pan);
        float rightVol = Math.max(0.0f, 1.0f + pan);

        for (int i = 0; i < numSamples; ++i) {
            sample[i] = Math.sin(2 * Math.PI * i / (SAMPLE_RATE / (double) freq));
        }

        int idx = 0;
        for (final double dVal : sample) {
            // Left channel
            generatedSnd[idx++] = (short) (dVal * 32767 * leftVol);
            // Right channel
            generatedSnd[idx++] = (short) (dVal * 32767 * rightVol);
        }

        audioTrack.write(generatedSnd, 0, generatedSnd.length);
        if (audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
            audioTrack.play();
        }
    }

    private void initAudioTrack() {
        int minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        
        // Fix 5: Use MODE_STREAM for real-time generated audio
        audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize, AudioTrack.MODE_STREAM);
    }

    public void stop() {
        isPlaying = false;
        if (audioTrack != null) {
            audioTrack.release();
        }
    }
}
