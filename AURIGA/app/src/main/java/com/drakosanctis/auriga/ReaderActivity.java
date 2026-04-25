package com.drakosanctis.auriga;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.Image;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DrakoVoice Reader — OrCam-style page reader.
 *
 * <p>Behaviour summary:
 * <ul>
 *   <li>Live camera preview with a centred framing reticle.</li>
 *   <li>Tap the capture FAB to snapshot the current frame, run on-device
 *       Latin OCR (Google ML Kit, bundled model — works offline) and
 *       have the recognised text spoken via {@link TextToSpeech}.</li>
 *   <li>AUTO toggle: every {@link #AUTO_READ_INTERVAL_MS} the activity
 *       captures and reads automatically, mirroring OrCam's
 *       "automatic reading after N seconds" mode.</li>
 *   <li>Playback controls: ⏮ / PLAY-PAUSE / ⏭ navigate paragraphs of
 *       the most recent capture.</li>
 * </ul>
 *
 * <p>Permissions: {@link Manifest.permission#CAMERA} is already declared
 * in the manifest; this activity handles the runtime request itself so a
 * user who taps READ from the HUD before granting camera at install time
 * gets a sensible prompt instead of a black screen.
 */
public class ReaderActivity extends AppCompatActivity {

    private static final String TAG = "ReaderActivity";
    private static final int REQ_CAMERA = 4711;
    private static final long AUTO_READ_INTERVAL_MS = 6000L;
    private static final String UTTERANCE_ID = "auriga_reader_paragraph";

    private PreviewView previewView;
    private TextView readerState;
    private TextView readerText;
    private Button captureBtn;
    private Button playBtn;
    private Button autoToggle;

    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;
    private TextRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean autoMode = false;
    private boolean isSpeaking = false;
    private final List<String> paragraphs = new ArrayList<>();
    private int currentParagraph = 0;
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable autoTick = new Runnable() {
        @Override public void run() {
            if (autoMode && !isSpeaking) {
                takeShotAndRead();
            }
            if (autoMode) {
                mainHandler.postDelayed(this, AUTO_READ_INTERVAL_MS);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reader);

        previewView = findViewById(R.id.reader_preview);
        readerState = findViewById(R.id.reader_state);
        readerText = findViewById(R.id.reader_text);
        captureBtn = findViewById(R.id.reader_capture);
        playBtn = findViewById(R.id.reader_play);
        autoToggle = findViewById(R.id.reader_auto_toggle);
        Button closeBtn = findViewById(R.id.reader_close);
        Button prevBtn = findViewById(R.id.reader_prev);
        Button nextBtn = findViewById(R.id.reader_next);

        cameraExecutor = Executors.newSingleThreadExecutor();
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        // Lazy TTS init — first speak() is held until onInit returns.
        tts = new TextToSpeech(getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS) {
                int res = tts.setLanguage(Locale.getDefault());
                if (res == TextToSpeech.LANG_MISSING_DATA
                        || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.US);
                }
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) {
                        runOnUiThread(() -> {
                            isSpeaking = true;
                            playBtn.setText("PAUSE");
                            readerState.setText("READING");
                        });
                    }
                    @Override public void onDone(String utteranceId) {
                        runOnUiThread(() -> {
                            isSpeaking = false;
                            playBtn.setText("PLAY");
                            readerState.setText(autoMode ? "AUTO" : "POINT & READ");
                            // Auto-advance through paragraphs on a single capture.
                            if (currentParagraph < paragraphs.size() - 1) {
                                currentParagraph++;
                                speakCurrent();
                            }
                        });
                    }
                    @Override public void onError(String utteranceId) {
                        runOnUiThread(() -> {
                            isSpeaking = false;
                            playBtn.setText("PLAY");
                        });
                    }
                });
                ttsReady = true;
            } else {
                Log.w(TAG, "TextToSpeech init failed: " + status);
            }
        });

        captureBtn.setOnClickListener(v -> takeShotAndRead());
        closeBtn.setOnClickListener(v -> finish());
        playBtn.setOnClickListener(v -> togglePlayback());
        prevBtn.setOnClickListener(v -> stepParagraph(-1));
        nextBtn.setOnClickListener(v -> stepParagraph(+1));
        autoToggle.setOnClickListener(v -> toggleAutoMode());

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_CAMERA) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "Camera permission required for Reader",
                        Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                CameraSelector selector = CameraSelector.DEFAULT_BACK_CAMERA;
                provider.unbindAll();
                provider.bindToLifecycle(this, selector, preview, imageCapture);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera bind failed", e);
                Toast.makeText(this, "Camera unavailable: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void takeShotAndRead() {
        if (imageCapture == null) {
            Toast.makeText(this, "Camera not ready yet", Toast.LENGTH_SHORT).show();
            return;
        }
        readerState.setText("ANALYZING…");
        imageCapture.takePicture(cameraExecutor, new ImageCapture.OnImageCapturedCallback() {
            @OptIn(markerClass = ExperimentalGetImage.class)
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy proxy) {
                Image media = proxy.getImage();
                if (media == null) {
                    proxy.close();
                    return;
                }
                int rotation = proxy.getImageInfo().getRotationDegrees();
                InputImage img = InputImage.fromMediaImage(media, rotation);
                recognizer.process(img)
                        .addOnSuccessListener(result -> {
                            proxy.close();
                            handleRecognized(result);
                        })
                        .addOnFailureListener(e -> {
                            proxy.close();
                            runOnUiThread(() -> {
                                readerState.setText("NO TEXT");
                                Toast.makeText(ReaderActivity.this,
                                        "OCR failed: " + e.getMessage(),
                                        Toast.LENGTH_SHORT).show();
                            });
                        });
            }
            @Override
            public void onError(@NonNull ImageCaptureException ex) {
                runOnUiThread(() -> {
                    readerState.setText("CAPTURE ERROR");
                    Toast.makeText(ReaderActivity.this,
                            "Capture error: " + ex.getMessage(),
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void handleRecognized(Text result) {
        String full = result.getText();
        runOnUiThread(() -> {
            if (full == null || full.trim().isEmpty()) {
                readerState.setText("NO TEXT");
                readerText.setText("No text detected. Try moving closer or improving lighting.");
                return;
            }
            paragraphs.clear();
            for (Text.TextBlock block : result.getTextBlocks()) {
                String t = block.getText();
                if (t != null && !t.trim().isEmpty()) {
                    paragraphs.add(t.replace('\n', ' ').trim());
                }
            }
            if (paragraphs.isEmpty()) {
                paragraphs.add(full.replace('\n', ' ').trim());
            }
            currentParagraph = 0;
            readerText.setText(full);
            readerState.setText("READING");
            speakCurrent();
        });
    }

    private void speakCurrent() {
        if (!ttsReady || tts == null || paragraphs.isEmpty()) return;
        if (currentParagraph < 0) currentParagraph = 0;
        if (currentParagraph >= paragraphs.size()) currentParagraph = paragraphs.size() - 1;
        HashMap<String, String> params = new HashMap<>();
        params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UTTERANCE_ID);
        tts.speak(paragraphs.get(currentParagraph), TextToSpeech.QUEUE_FLUSH, params);
    }

    private void togglePlayback() {
        if (tts == null) return;
        if (tts.isSpeaking()) {
            tts.stop();
            isSpeaking = false;
            playBtn.setText("PLAY");
            readerState.setText(autoMode ? "AUTO" : "POINT & READ");
        } else if (!paragraphs.isEmpty()) {
            speakCurrent();
        } else {
            takeShotAndRead();
        }
    }

    private void stepParagraph(int delta) {
        if (paragraphs.isEmpty()) return;
        currentParagraph = Math.max(0, Math.min(paragraphs.size() - 1, currentParagraph + delta));
        speakCurrent();
    }

    private void toggleAutoMode() {
        autoMode = !autoMode;
        autoToggle.setActivated(autoMode);
        autoToggle.setContentDescription(autoMode
                ? "Auto-read on. Double tap to stop automatic page reading."
                : "Auto-read off. Double tap to enable automatic page reading.");
        readerState.setText(autoMode ? "AUTO" : "POINT & READ");
        mainHandler.removeCallbacks(autoTick);
        if (autoMode) {
            mainHandler.postDelayed(autoTick, AUTO_READ_INTERVAL_MS);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(autoTick);
        if (tts != null && tts.isSpeaking()) tts.stop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (autoMode) mainHandler.postDelayed(autoTick, AUTO_READ_INTERVAL_MS);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacks(autoTick);
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (recognizer != null) recognizer.close();
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }
}
