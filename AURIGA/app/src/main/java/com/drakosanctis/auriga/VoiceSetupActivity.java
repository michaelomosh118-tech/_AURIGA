package com.drakosanctis.auriga;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * VoiceSetupActivity — first-run assistant naming screen.
 *
 * Lets the user pick a name for their voice assistant so the wake phrase
 * becomes "[name], AURIGA". Persists the chosen name and a setup-done flag
 * to SharedPreferences. Speaks the intro text for screen-reader users.
 *
 * Launch it (for free) any time, e.g. from a drawer "Voice Setup" row or
 * from a "change name" voice command. If the user taps Skip, the default
 * name "Auriga" is stored and the setup-done flag is set so the prompt
 * never reappears automatically.
 */
public class VoiceSetupActivity extends Activity {

    private static final int REQUEST_CODE = 5001;

    private EditText nameInput;
    private TextView wakeExample;
    private Button   confirmBtn;
    private TextToSpeech tts;
    private boolean ttsReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice_setup);

        nameInput   = findViewById(R.id.voice_name_input);
        wakeExample = findViewById(R.id.voice_wake_example);
        confirmBtn  = findViewById(R.id.voice_setup_confirm);
        Button skipBtn = findViewById(R.id.voice_setup_skip);

        // Pre-fill if the user is renaming an existing assistant.
        String existing = AurigaVoiceEngine.getAssistantName(this);
        if (AurigaVoiceEngine.isSetupDone(this)
                && !existing.equalsIgnoreCase("Auriga")) {
            nameInput.setText(existing);
            nameInput.setSelection(existing.length());
        }

        nameInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String preview = s.toString().trim().isEmpty()
                        ? "Nova" : capitalise(s.toString().trim());
                wakeExample.setText("\"" + preview + ", AURIGA\"");
            }
        });

        confirmBtn.setOnClickListener(v -> confirm());
        if (skipBtn != null) skipBtn.setOnClickListener(v -> skip());

        initTts();
    }

    private void confirm() {
        String raw  = nameInput.getText().toString().trim();
        if (raw.isEmpty() || raw.length() > 24) {
            Toast.makeText(this, "Name must be 1–24 characters.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!raw.matches("[\\p{L}0-9 ]+")) {
            Toast.makeText(this, "Letters, numbers and spaces only.", Toast.LENGTH_SHORT).show();
            return;
        }
        String name = capitalise(raw);
        saveName(name);
        speak("Voice navigation activated. Say " + name + " Auriga to wake me.");
        Intent result = new Intent();
        result.putExtra("voice_name", name);
        setResult(RESULT_OK, result);
        finish();
    }

    private void skip() {
        SharedPreferences prefs =
                getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        if (!prefs.getBoolean(AurigaVoiceEngine.PREF_VOICE_SETUP_DONE, false)) {
            prefs.edit()
                    .putString(AurigaVoiceEngine.PREF_VOICE_NAME, "Auriga")
                    .putBoolean(AurigaVoiceEngine.PREF_VOICE_SETUP_DONE, true)
                    .apply();
        }
        setResult(RESULT_CANCELED);
        finish();
    }

    private void saveName(String name) {
        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(AurigaVoiceEngine.PREF_VOICE_NAME, name)
                .putBoolean(AurigaVoiceEngine.PREF_VOICE_SETUP_DONE, true)
                .apply();
    }

    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0))
                + s.substring(1).toLowerCase(Locale.US);
    }

    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            ttsReady = (status == TextToSpeech.SUCCESS);
            if (ttsReady && tts != null) tts.setLanguage(Locale.getDefault());
        });
    }

    private void speak(String text) {
        if (ttsReady && tts != null)
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voice_setup");
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }

    /** Helper so callers can get the right request code for startActivityForResult. */
    public static int requestCode() { return REQUEST_CODE; }
}
