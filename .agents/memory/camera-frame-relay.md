---
name: Camera FrameRelay Architecture
description: Why AurigaCoreService no longer binds CameraX and how frames now flow from LocatorActivity via FrameRelay singleton.
---

## Rule
`AurigaCoreService` must never call `ProcessCameraProvider.bindToLifecycle()`. Frames arrive via `FrameRelay.get()`.

## Why
On Android 12+ (SDK 34/35, Samsung SM-A057F) the camera HAL throws `SecurityException: Attempt to use camera from a different process than original client` when two components both call `unbindAll()` + `bindToLifecycle()`. CameraX session-drain callbacks fire on the service's `analysisPool` thread, which doesn't match the original client PID — crash.

## How to apply
- `LocatorActivity.onResume()` → `FrameRelay.get().markSourceActive()`
- `LocatorActivity.onPause()` → `FrameRelay.get().markSourceInactive()`
- `LocatorActivity.YoloAnalyzer.analyze()` → `FrameRelay.get().publishBitmap(bmp, 0)` before `bmp.recycle()`
- `AurigaCoreService.onCreate()` → `FrameRelay.get().addListener(this::processFrame)`
- `AurigaCoreService.onDestroy()` → `FrameRelay.get().removeListener(frameListener)`
- `processFrame(byte[], int, int, int)` — signature changed; no longer takes `ImageProxy`
- `FrameRelay.publishBitmap()` does ARGB→NV21 conversion (BT.601 coefficients, ~2–5 ms for 640×480)

New file: `AURIGA/app/src/main/java/com/drakosanctis/auriga/FrameRelay.java`
