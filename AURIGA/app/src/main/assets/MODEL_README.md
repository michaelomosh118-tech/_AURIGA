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
back to `yolov8n.tflite`. Either works -- it inspects the input
tensor's data type at load time and adapts.

If neither file is present the activity boots into a friendly
"model not bundled" screen with a one-tap button that hands off to
`LocatorWebActivity` (the legacy WebView locator), so the APK still
ships and runs even without the model.

## How to obtain the model

The official Ultralytics export is the easiest path:

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

Pre-quantised community builds also work -- any model that takes
`[1, 640, 640, 3]` input and emits `[1, 84, 8400]` YOLOv8 output
will run.

## Class labels

The 80 COCO class names are already committed at
`app/src/main/assets/coco_labels.txt`. They map 1:1 to the channel
order of the standard Ultralytics export.

## Why this file is gitignored

`*.tflite` is added to `.gitignore` to keep the repo small. CI
release builds resolve the model from a bucket (or a pre-staged
runner cache) at build time -- see the build script in
`.github/workflows/` for the exact path.

---

# AurigaMind — on-device LLM models

`MindEngine` powers the Auriga personal assistant (Alexa/Siri-style Q&A).
Drop **one** of the following files into this directory to enable it.
Neither is committed to the repo (`*.bin` is gitignored).

## Option A — Gemma 2B Q4  (~1.5 GB, preferred)
Richer, more conversational answers. Requires a device with ≥4 GB RAM
(Snapdragon 778G / Dimensity 1200 or better recommended).

```bash
# Using the MediaPipe model conversion CLI:
pip install mediapipe

# Download Gemma 2 2B IT from https://www.kaggle.com/models/google/gemma-2
# Then convert to MediaPipe flatbuffer format:
python -m mediapipe.tasks.python.genai.converter.convert_checkpoint \
  --backend=cpu \
  --logtostderr \
  --input_ckpt=gemma2_2b_it \
  --dtype=q4 \
  --output_dir=. \
  --output_name=gemma2b_q4.bin

cp gemma2b_q4.bin AURIGA/app/src/main/assets/gemma2b_q4.bin
```

Alternatively, grab a pre-converted `.bin` from the
[MediaPipe LLM Inference guide](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android).

## Option B — Qwen 2.5 0.5B Q8  (~400 MB, fast)
Lighter model; good for weather/news/factual Q&A on mid-range devices.

```bash
# From HuggingFace: Qwen/Qwen2.5-0.5B-Instruct
pip install mediapipe

python -m mediapipe.tasks.python.genai.converter.convert_checkpoint \
  --backend=cpu \
  --logtostderr \
  --input_ckpt=Qwen/Qwen2.5-0.5B-Instruct \
  --dtype=q8 \
  --output_dir=. \
  --output_name=qwen2_5_0_5b_q8.bin

cp qwen2_5_0_5b_q8.bin AURIGA/app/src/main/assets/qwen2_5_0_5b_q8.bin
```

## Enabling the MediaPipe AAR (required for LLM)

`MindEngine` loads `LlmInference` via reflection so the project compiles
without the AAR. To actually run the LLM, uncomment this line in
`app/build.gradle` under `dependencies {}`:

```groovy
// implementation 'com.google.mediapipe:tasks-genai:0.10.14'
```

## Graceful degradation

If neither model file is present, `MindEngine.tryCreate()` returns null
and `AurigaVoiceEngine` stays on the three-tier rule-based fallback:

  1. `AurigaKnowledge` rule-based KB  (instant, always available)
  2. `KnowledgeCache` context string   (weather/news from SQLite, online sync)
  3. `AurigaKnowledge.fallback()`      (safe "I don't know" response)

So the APK builds, installs, and ships without any model file.
The LLM is a progressive enhancement.
