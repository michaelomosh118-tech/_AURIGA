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
