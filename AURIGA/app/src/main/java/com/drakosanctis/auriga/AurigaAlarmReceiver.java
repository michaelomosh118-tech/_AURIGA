package com.drakosanctis.auriga;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.util.Locale;

/**
 * AurigaAlarmReceiver — handles AlarmManager broadcasts for alarms and reminders.
 *
 * Registered in AndroidManifest.xml for:
 *   - com.drakosanctis.auriga.ALARM_FIRE    (from AurigaSkillEngine.handleSetAlarm)
 *   - com.drakosanctis.auriga.REMINDER_FIRE (from AurigaSkillEngine.handleSetReminder)
 *
 * Fires even when the app is killed. Speaks the alert via a fresh TTS instance,
 * shows a notification, and vibrates.
 *
 * Add to AndroidManifest.xml inside <application>:
 *   <receiver android:name=".AurigaAlarmReceiver" android:exported="false">
 *     <intent-filter>
 *       <action android:name="com.drakosanctis.auriga.ALARM_FIRE"/>
 *       <action android:name="com.drakosanctis.auriga.REMINDER_FIRE"/>
 *     </intent-filter>
 *   </receiver>
 */
public class AurigaAlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null) return;

        String label = intent.getStringExtra("label");
        String text  = intent.getStringExtra("text");

        String spokenText;
        String notifTitle;
        String notifBody;

        if (AurigaSkillEngine.ACTION_ALARM.equals(action)) {
            spokenText  = label != null ? "Alarm! It is " + label + ". Wake up!" : "Alarm! Time to wake up!";
            notifTitle  = "⏰ Alarm";
            notifBody   = label != null ? "It is " + label : "Your alarm is ringing.";
        } else {
            spokenText  = text != null ? "Reminder: " + text + "." : "Reminder!";
            notifTitle  = "🔔 Reminder";
            notifBody   = text != null ? text : "Your reminder is due.";
        }

        vibrate(context);
        showNotification(context, notifTitle, notifBody);
        speakWithNewTts(context, spokenText);
    }

    private void speakWithNewTts(Context context, String text) {
        TextToSpeech[] ttsHolder = {null};
        ttsHolder[0] = new TextToSpeech(context.getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS && ttsHolder[0] != null) {
                ttsHolder[0].setLanguage(Locale.getDefault());
                ttsHolder[0].setSpeechRate(0.95f);
                ttsHolder[0].speak(text, TextToSpeech.QUEUE_FLUSH, null, "alarm_speak");
                /* Shut down TTS after 10 seconds to release resources */
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    try { if (ttsHolder[0] != null) { ttsHolder[0].shutdown(); ttsHolder[0] = null; } }
                    catch (Exception ignored) {}
                }, 10000);
            }
        });
    }

    private void vibrate(Context context) {
        Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) return;
        long[] pattern = {0, 500, 200, 500, 200, 800};
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createWaveform(pattern, -1));
        } else {
            v.vibrate(pattern, -1);
        }
    }

    private void showNotification(Context context, String title, String body) {
        try {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(
                    context, AurigaSkillEngine.CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setAutoCancel(true)
                    .setVibrate(new long[]{0, 500, 200, 500});
            NotificationManagerCompat.from(context).notify(
                    (int) System.currentTimeMillis(), builder.build());
        } catch (Exception ignored) {}
    }
}
