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
Drop **one** of the following files into this directory to enable it.
Neither is committed to the repo (`*.bin` is gitignored).

## Format note

The old MediaPipe `.bin` flatbuffer format is no longer produced by
current tooling. Current releases use `.tflite` format, which
`tasks-genai:0.10.14` (the version wired in `build.gradle`) accepts
identically via `setModelPath()`. **Download the `.tflite` file and
rename it to the `.bin` filename MindEngine expects** — MediaPipe
reads the file by content, not by extension.

---

## Option A — Gemma 2 2B IT  (q8, ~2.52 GB, preferred)

Richer, more conversational answers. Requires a device with ≥4 GB RAM.

**Prerequisites:** a Hugging Face account and accepted Gemma licence at
<https://huggingface.co/litert-community/Gemma2-2B-IT>

```bash
# Install the HuggingFace CLI if you don't have it
pip install huggingface_hub

# Log in (creates ~/.cache/huggingface/token)
huggingface-cli login

# Download (2.52 GB — use a metered connection carefully)
huggingface-cli download litert-community/Gemma2-2B-IT \
  Gemma2-2B-IT_multi-prefill-seq_q8_ekv1280.tflite \
  --local-dir .

# Rename to the filename MindEngine expects
mv Gemma2-2B-IT_multi-prefill-seq_q8_ekv1280.tflite \
   AURIGA/app/src/main/assets/gemma2b_q4.bin
```

**Verified file** (as of 2025-06-08, from HuggingFace LFS metadata):

| Field    | Value |
|----------|-------|
| Filename | `Gemma2-2B-IT_multi-prefill-seq_q8_ekv1280.tflite` |
| Size     | 2,709,032,880 bytes (2,583.5 MB) |
| SHA-256  | `29ff136fd298e611296e10e9b511c86f42d1291b5b8bfc18c42178e733b679a9` |

Verify after download:

```bash
# Linux / macOS
sha256sum Gemma2-2B-IT_multi-prefill-seq_q8_ekv1280.tflite
# expected: 29ff136fd298e611296e10e9b511c86f42d1291b5b8bfc18c42178e733b679a9
```

---

## Option B — Qwen 2.5 0.5B  (q8, ~519 MB, fast)

Lighter model; good for Q&A on mid-range devices. Apache-2.0 licence —
**no login required**.

```bash
# Install the HuggingFace CLI if you don't have it
pip install huggingface_hub

# Download (~519 MB)
huggingface-cli download litert-community/Qwen2.5-0.5B-Instruct \
  Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.tflite \
  --local-dir .

# Rename to the filename MindEngine expects
mv Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.tflite \
   AURIGA/app/src/main/assets/qwen2_5_0_5b_q8.bin
```

**Verified file** (as of 2025-06-08, from HuggingFace LFS metadata):

| Field    | Value |
|----------|-------|
| Filename | `Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.tflite` |
| Size     | 544,011,416 bytes (518.8 MB) |
| SHA-256  | `54806eb754fe80fe6ed42d055ea56099ae0a273a52bda6437290cc00c501000b` |

Verify after download:

```bash
# Linux / macOS
sha256sum Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.tflite
# expected: 54806eb754fe80fe6ed42d055ea56099ae0a273a52bda6437290cc00c501000b
```

Direct download URL (no auth needed, verified HTTP 200):

```
https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.tflite
```

---

## MediaPipe AAR status

**Already active.** As of the current `app/build.gradle`, line 308 reads:

```groovy
implementation 'com.google.mediapipe:tasks-genai:0.10.14'
```

No action needed — the dependency is live. `MindEngine` loads
`LlmInference` via reflection at runtime, so the APK compiles and runs
even when no model file is present (graceful degradation below).

## Graceful degradation

If neither model file is present, `MindEngine.tryCreate()` returns null
and `AurigaVoiceEngine` stays on the three-tier rule-based fallback:

  1. `AurigaKnowledge` rule-based KB  (instant, always available)
  2. `KnowledgeCache` context string   (weather/news from SQLite, online sync)
  3. `AurigaKnowledge.fallback()`      (safe "I don't know" response)

So the APK builds, installs, and ships without any model file.
The LLM is a progressive enhancement.

## Deprecation notice

As of 2025, Google has deprecated the Android and iOS implementations
of the MediaPipe LLM Inference API in favour of
[LiteRT-LM](https://ai.google.dev/edge/litert). The `tasks-genai:0.10.14`
dependency still functions, but future projects should target LiteRT-LM.
The model files listed above (`litert-community` on Hugging Face) are
compatible with both APIs.
