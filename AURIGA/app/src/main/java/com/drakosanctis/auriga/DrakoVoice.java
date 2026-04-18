package com.drakosanctis.auriga;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import java.util.Locale;

/**
 * DrakoVoice: The "Persona" of the Auriga Ecosystem.
 * Provides inclusive voice assistance for the visually impaired.
 * No reliance on external APIs; uses local Android TTS.
 */
public class DrakoVoice {

    private TextToSpeech tts;
    private boolean isReady = false;

    public DrakoVoice(Context context) {
        tts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS && tts != null) {
                tts.setLanguage(Locale.US);
                isReady = true;
            }
        });
    }

    /**
     * speak: Announces obstacle data in a clear, accessible voice.
     * Example: "Wall, 1.2 meters, 10 degrees left."
     */
    public void speak(String signature, float distanceM, float bearingDeg) {
        if (!isReady || !AurigaConfig.hasDrakoVoice()) return;

        String bearingStr = (bearingDeg < 0) ? "left" : "right";
        String announcement = String.format(Locale.US, "%s, %.1f meters, %.0f degrees %s",
                signature, distanceM, Math.abs(bearingDeg), bearingStr);
        
        tts.speak(announcement, TextToSpeech.QUEUE_FLUSH, null, "drako_voice_id");
    }

    /**
     * announceAlert: Priority warning for overhangs or critical collisions.
     */
    public void announceAlert(String alertType, float distanceM) {
        if (!isReady || !AurigaConfig.hasSkyShield()) return;

        String announcement = String.format(Locale.US, "Warning, %s at %.1f meters",
                alertType, distanceM);
        
        tts.speak(announcement, TextToSpeech.QUEUE_ADD, null, "drako_alert_id");
    }

    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }
}
