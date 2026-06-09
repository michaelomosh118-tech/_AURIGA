# Auriga native YOLOv8n model bundle

`LocatorActivity` runs the Object Locator entirely on-device using a
quantised YOLOv8n TFLite model. The model is **not** committed to the
repo (it ships as a binary blob in the APK and would bloat the git
history), so it must be dropped into this directory before the first
build.

## Required file

```
app/src/main/assets/yolov8n_float32.tflite     (~12 MB, fp32)
                  -- OR --
app/src/main/assets/yolov8n.tflite             (~6 MB, int8 quantised)
```

`YoloDetector` looks for `yolov8n_float32.tflite` first, then falls
back to `yolov8n.tflite`. Either works — it inspects the input
tensor's data type at load time and adapts.

If neither file is present the activity boots into a friendly
"model not bundled" screen with a one-tap button that hands off to
`LocatorWebActivity` (the legacy WebView locator), so the APK still
ships and runs even without the model.

## How to obtain the model

Export directly from Ultralytics (produces the exact tensor shape
`[1, 640, 640, 3]` → `[1, 84, 8400]` the detector expects):

```bash
pip install ultralytics
yolo export model=yolov8n.pt format=tflite imgsz=640
# Produces yolov8n_saved_model/yolov8n_float32.tflite
cp yolov8n_saved_model/yolov8n_float32.tflite \
   AURIGA/app/src/main/assets/yolov8n_float32.tflite
```

For the smaller int8 variant:

```bash
yolo export model=yolov8n.pt format=tflite imgsz=640 int8=True
cp yolov8n_saved_model/yolov8n_int8.tflite \
   AURIGA/app/src/main/assets/yolov8n.tflite
```

Any model that takes `[1, 640, 640, 3]` input and emits `[1, 84, 8400]`
YOLOv8 output will run.

## Class labels

The 80 COCO class names are already committed at
`app/src/main/assets/coco_labels.txt`. They map 1:1 to the channel
order of the standard Ultralytics export.

## Why this file is gitignored

`*.tflite` is added to `.gitignore` to keep the repo small. CI
release builds resolve the model from a bucket (or a pre-staged
runner cache) at build time — see the build script in
`.github/workflows/` for the exact path.

---

# AurigaMind — on-device LLM models

`MindEngine` powers the Auriga personal assistant (Alexa/Siri-style Q&A).

**Bundle both files** — MindEngine tries Gemma first and falls back to
Qwen automatically. On devices with less than 3,500 MB total RAM the
engine skips Gemma entirely (OOM guard) and loads Qwen instead.
Neither file is committed to the repo (`*.bin` is gitignored).

---

## Format

The old `.bin` flatbuffer format is no longer produced by current
tooling. Current model releases from the `litert-community` Hugging
Face organisation use `.tflite` format, which
`tasks-genai:0.10.35` (the version in `build.gradle`) accepts
identically via `setModelPath()`. The files are downloaded as `.tflite`
and **renamed to `.bin`** when placed in assets — MediaPipe reads by
content, not by extension. The `noCompress 'bin'` rule in
`build.gradle` ensures AAPT stores them uncompressed so MediaPipe can
`mmap()` them at runtime.

---

## Model A — Qwen 2.5 0.5B IT  (q8, 519 MB)  ← fallback / low-RAM devices

Apache-2.0 licence — **no login required.**

**This file is already present in the repo** (downloaded into
`app/src/main/assets/qwen2_5_0_5b_q8.bin` by the project setup).

To re-download manually:

```bash
curl -L \
  "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.tflite" \
  -o AURIGA/app/src/main/assets/qwen2_5_0_5b_q8.bin
```

Verify after download:

```bash
sha256sum AURIGA/app/src/main/assets/qwen2_5_0_5b_q8.bin
# expected: 54806eb754fe80fe6ed42d055ea56099ae0a273a52bda6437290cc00c501000b
# size:     544,011,416 bytes
```

---

## Model B — Gemma 2 2B IT  (q8, 2.52 GB)  ← primary / flagship devices

Gemma licence — **requires a Hugging Face account.**

### Step 1 — Accept the licence

Visit <https://huggingface.co/litert-community/Gemma2-2B-IT> while
logged in and click **"Acknowledge licensed"**. This is a one-time step
per HF account.

### Step 2 — Download

**Option A — huggingface-cli (recommended):**

```bash
pip install huggingface_hub
huggingface-cli login        # enter your HF token when prompted

huggingface-cli download litert-community/Gemma2-2B-IT \
  Gemma2-2B-IT_multi-prefill-seq_q8_ekv1280.tflite \
  --local-dir /tmp/gemma

mv /tmp/gemma/Gemma2-2B-IT_multi-prefill-seq_q8_ekv1280.tflite \
   AURIGA/app/src/main/assets/gemma2b_q4.bin
```

**Option B — curl with token:**

```bash
# Replace <YOUR_HF_TOKEN> with a token that has read access
curl -L -H "Authorization: Bearer <YOUR_HF_TOKEN>" \
  "https://huggingface.co/litert-community/Gemma2-2B-IT/resolve/main/Gemma2-2B-IT_multi-prefill-seq_q8_ekv1280.tflite" \
  -o AURIGA/app/src/main/assets/gemma2b_q4.bin
```

### Step 3 — Verify

```bash
sha256sum AURIGA/app/src/main/assets/gemma2b_q4.bin
# expected: 29ff136fd298e611296e10e9b511c86f42d1291b5b8bfc18c42178e733b679a9
# size:     2,709,032,880 bytes
```

---

## MediaPipe AAR status

**Already active.** `app/build.gradle` line 316:

```groovy
implementation 'com.google.mediapipe:tasks-genai:0.10.35'
```

No action needed. MindEngine loads `LlmInference` via reflection so
the APK compiles and ships even without any model file present.

---

## Device compatibility

| Device class | Total RAM | Model loaded | Notes |
|---|---|---|---|
| Flagship (Pixel 8, S24, etc.) | ≥ 6 GB | Gemma 2B → Qwen fallback | ~10 tok/s decode |
| Mid-range (Pixel 6a, A54, etc.) | 4–5 GB | Gemma 2B → Qwen fallback | ~7 tok/s decode |
| Budget / low-RAM | < 3.5 GB | Qwen 0.5B only | ~30 tok/s decode |

RAM thresholds are enforced automatically at runtime — no user configuration needed.

---

## Graceful degradation

If neither model file is present, `MindEngine.tryCreate()` returns null
and `AurigaVoiceEngine` stays on the three-tier rule-based fallback:

1. `AurigaKnowledge` rule-based KB  (instant, always available)
2. `KnowledgeCache` context string   (weather/news from SQLite, online sync)
3. `AurigaKnowledge.fallback()`      (safe "I don't know" response)

The APK builds, installs, and ships without any model file.
The LLM is a progressive enhancement.

---

## Google Colab notebook (model download + verification)

A ready-to-run Colab notebook is provided at
`scripts/download_models.ipynb`. It downloads both models, verifies
SHA-256 checksums, and produces a zip you can copy to your build
machine. Open it at:

```
https://colab.research.google.com/github/<your-repo>/blob/main/scripts/download_models.ipynb
```

---

## Deprecation notice

As of 2025, Google deprecated the Android/iOS MediaPipe LLM Inference
API in favour of [LiteRT-LM](https://ai.google.dev/edge/litert).
`tasks-genai:0.10.35` still functions and the `litert-community`
model files are compatible with both APIs. Migration to LiteRT-LM
is recommended for new projects but is not required for Auriga now.
