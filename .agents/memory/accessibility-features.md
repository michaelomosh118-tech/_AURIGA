---
name: Accessibility Feature Set
description: ProximityEarconManager, ColorSenseActivity, SosManager, 8 new voice commands — competitor gaps each one closes.
---

## Features built

### ProximityEarconManager (new class)
- `ToneGenerator(STREAM_MUSIC, 60)` — no external assets needed.
- `update(float distM)` called every YOLO frame from LocatorActivity's mainHandler.post() block.
- 5 distance bands → 60 / 130 / 280 / 500 / 800 ms beep intervals (> 4 m = silence).
- `silence()` + `setEnabled()` + `release()` lifecycle methods.
- **Why:** Lookout users report "TTS fatigue" after ~20 min; earcons solve this — brain habituates far more slowly to non-verbal tones.

### ColorSenseActivity (new class)
- Extends `ComponentActivity` (required for CameraX `bindToLifecycle`; safe — no AppCompat theme needed).
- `OUTPUT_IMAGE_FORMAT_RGBA_8888` — straightforward ByteBuffer→Bitmap, no YUV conversion needed (CameraX 1.3.4+).
- Center 50px-radius crop → average RGB → `Color.RGBToHSV()` → 20 colour names + saturation/brightness prefix.
- Tap-to-identify + AUTO mode (2.5 s interval).
- **Why:** Colour ID is a paid feature in Envision; missing from Lookout entirely.

### SosManager (new class)
- `activate()` → 5-second TTS countdown → `ACTION_CALL "112"`, falls back to `ACTION_DIAL` if CALL_PHONE not granted.
- `cancel()` aborts the countdown; `isActive()` for routeCommand gating.
- Triggered by: voice "SOS"/"emergency" OR long-press mic FAB in LocatorActivity.
- **Why:** No competitor (Seeing AI, Lookout, Envision, OrCam) provides emergency calling.

### AurigaVoiceEngine voice commands added (8 new routes)
- **say again** — replays `lastUtterance` field (set in `speak()`)
- **slow down / speed up** — adjusts `speechRate` ± 0.20f, clamped 0.5–2.0, calls `tts.setSpeechRate()`
- **describe what you see** — calls `listener.onDescribePage()` → LocatorActivity reads `recentDetections`
- **color sense** — `safeStart(ColorSenseActivity.class)`
- **download AI / download large AI** — `ModelDownloadManager.ensureQwenSmall/LargeDownloaded()` with state check
- **AI status** — reads both model states via `modelStateLabel()` helper
- **SOS / emergency** — lazily creates `SosManager(activity, tts)`, calls `activate()`
- **cancel SOS** — `sosMgr.cancel()` with isActive() guard

### LocatorActivity integration
- `recentDetections` volatile field updated every frame in `mainHandler.post()`.
- `onDescribePage()` rewritten: reads `recentDetections`, builds natural sentence with bearings.
- `onPause()` unregisters light sensor + silences earcon (battery-safe).
- LightSensor: `TYPE_LIGHT < 8 lux` → `speakQuiet("Low light...")` at most once per 60 s.
- Mic FAB long-press → `voiceEngine.activateSos()`.
- Color Sense drawer row wired: `nav_color_sense` → `safeStart(ColorSenseActivity.class)`.

## Key constraints
- All activities: must extend `Activity` or `ComponentActivity`, NOT `AppCompatActivity` (theme crash).
- `ModelDownloadManager.ModelState` enum values: `NOT_DOWNLOADED`, `DOWNLOADING`, `READY`.
- CALL_PHONE declared in manifest; ACTION_DIAL fallback if runtime permission absent.
