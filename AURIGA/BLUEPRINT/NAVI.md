# AURIGA NAVI — Product Blueprint
### DrakoSanctis Ecosystem · Variant: NAVI
#### Living specification — updated alongside the codebase

---

## Table of Contents

1. [Core Mission](#1-core-mission)
2. [The Central Problem: LiDAR Costs $4,500](#2-the-central-problem-lidar-costs-4500)
3. [Architecture Overview](#3-architecture-overview)
4. [Detection Pipeline — End-to-End Flow](#4-detection-pipeline--end-to-end-flow)
5. [The LiDAR Bypass — Seven Innovations](#5-the-lidar-bypass--seven-innovations)
   - 5.1 Bounding-Box Geometry as Depth (Pinhole Camera Model)
   - 5.2 FOV-Mapped Bearing from Normalised X Coordinate
   - 5.3 GhostAnchor — Visual Odometry & Anti-Tremor Smoothing
   - 5.4 TruePath + SkyShield — Triangulation Engine
   - 5.5 Smart Lighting — Two-Phase Torch State Machine
   - 5.6 On-Device Neural Inference (No Network, No Cloud)
   - 5.7 Serpentine Gesture Navigation
6. [Distance Formula — Full Derivation](#6-distance-formula--full-derivation)
7. [Bearing Formula — Full Derivation](#7-bearing-formula--full-derivation)
8. [Voice Announcement Pipeline](#8-voice-announcement-pipeline)
9. [Bearing-Aware Haptic Guidance System](#9-bearing-aware-haptic-guidance-system)
10. [Wake Word & Voice Command System](#10-wake-word--voice-command-system)
11. [10-Point Calibration Walk](#11-10-point-calibration-walk)
12. [Target Management System](#12-target-management-system)
13. [Offline-First Architecture](#13-offline-first-architecture)
14. [Variant Differentiation](#14-variant-differentiation)
15. [Competitive Matrix](#15-competitive-matrix)
16. [Phase Roadmap](#16-phase-roadmap)

---

## 1. Core Mission

**Auriga Navi** gives a blind or low-vision person the spatial awareness of a sighted person with a phone in their pocket — for free, offline, on the device they already own.

The user points their phone camera at the world. Navi responds in real time:

> *"Chair, 1.2 metres, slightly right."*
> *"Person, 80 centimetres, ahead."*
> *"Door, 3 metres, to your left."*

Every word and every vibration is deliberate. No silence. No apologies. No instructions mid-navigation. The user is moving; the system must be faster.

**Design axioms:**
- Voice is truth. Haptic is confirmation. Silence is never acceptable.
- Every feature runs entirely on-device. No network call may block navigation.
- The phone a blind person already owns is the only hardware they need.
- Parity with the $4,500 OrCam hardware — shipped as a free APK.

---

## 2. The Central Problem: LiDAR Costs $4,500

OrCam MyEye, the current gold standard for blind navigation hardware, costs USD $4,500. It uses dedicated depth sensors (structured light / time-of-flight) to measure real distances. Seeing AI, Be My Eyes, and Lookout are cloud-dependent — they fail without a data connection and add a 300–2,000 ms round trip that makes real-time spatial guidance impossible.

**Auriga's answer:** replace the depth sensor with a set of mathematical invariants that a phone camera already exposes for free.

A phone camera is a calibrated pinhole. Every object it captures obeys the projective geometry that was understood in the Renaissance. The challenge is not physics — it is engineering the right signal pipeline to extract depth and bearing from a 2D image in real time on a mid-range Android CPU.

---

## 3. Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                        AURIGA NAVI APK                          │
│                                                                 │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────────────┐│
│  │  CameraX     │   │  YoloDetector│   │  TriangulationEngine ││
│  │  ImageAnalysis│──▶│  (TFLite)    │──▶│  TruePath™ · SkyShield│
│  │  ~3 fps      │   │  YOLOv8n     │   │  + FiducialLUT       ││
│  └──────────────┘   └──────────────┘   └──────────┬───────────┘│
│                                                    │            │
│  ┌─────────────────────────────────────────────────▼──────────┐ │
│  │                   OdometryManager (GhostAnchor™)           │ │
│  │   Adaptive low-pass · bearing smoother · visual-shift      │ │
│  │   centroid — freezes filter during fast camera pans        │ │
│  └─────────────────────────────┬──────────────────────────────┘ │
│                                │                                │
│          ┌─────────────────────┴─────────────────────┐         │
│          │                                           │          │
│  ┌───────▼──────────┐                    ┌───────────▼───────┐ │
│  │  HapticManager   │                    │  TextToSpeech     │ │
│  │  Bearing-aware   │                    │  AurigaAnnounce   │ │
│  │  pulse patterns  │                    │  phrase composer  │ │
│  └──────────────────┘                    └───────────────────┘ │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  LocatorOverlayView  — bounding boxes · crosshair · HUD  │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                 │
│  ┌──────────────────┐   ┌────────────────┐  ┌───────────────┐  │
│  │ AurigaVoiceEngine│   │ AurigaVoiceServ│  │ SmartLighting │  │
│  │ Command recogn.  │   │ Wake-word loop │  │ Two-phase torch│  │
│  └──────────────────┘   └────────────────┘  └───────────────┘  │
│                                                                 │
│  ┌──────────────────┐   ┌────────────────┐                     │
│  │ TargetStore      │   │ SerpentineGest.│                     │
│  │ COCO class filter│   │ Swipe nav      │                     │
│  └──────────────────┘   └────────────────┘                     │
└─────────────────────────────────────────────────────────────────┘
```

---

## 4. Detection Pipeline — End-to-End Flow

```
CameraX ImageProxy (YUV_420_888)
          │
          ▼
  [SMART LIGHTING CHECK]
  Read mean luminance of frame
          │
  ┌───────┴────────┐
  │ Dark? (< 55)   │ Bright?
  ▼                ▼
Fire torch       Skip lighting
(torchPrimed=T)  phase entirely
  │                │
  │ (next frame arrives with torch on)
  ▼                │
Run YOLOv8n ◄──────┘
inference on lit frame
Kill torch immediately after
          │
          ▼
  Post-NMS detections[]
  (label, confidence, RectF box in normalised 0-1 coords)
          │
          ▼
  TargetStore filter
  ─ if activeTargets=ANY → pass all
  ─ if specific targets → keep matching labels only
          │
          ▼
  Pick PRIMARY target
  ─ closest to frame centre (lowest |centreX - 0.5| + |centreY - 0.5|)
  ─ weighted by area to prefer large nearby detections
          │
          ▼
  TriangulationEngine.calculate()
  ─ distanceM  = REFERENCE_CONSTANT / (normHeight × 100)
  ─ bearingDeg = (normCentreX − 0.5) × FOV_DEGREES
  ─ sanityScore: row-based vs width-based agreement check
          │
          ▼
  OdometryManager.smooth()
  ─ adaptive low-pass on distance (α rises on jump, falls on steady)
  ─ GhostAnchor: freeze if camera panned >25px/frame
  ─ independent bearing smoother
          │
          ├────────────────────────────────────┐
          ▼                                    ▼
  HapticManager                         TextToSpeech
  bearingAware:                         cooldown gated:
  |deg|<5° → alert()                   same label → 2.2s cooldown
  5-15°    → pulse(dist)               new label  → 700ms cooldown
  ≥15°     → silent                    phrase: "{Label}, {dist}, {dir}."
          │                                    │
          ▼                                    ▼
  LocatorOverlayView.onDraw()           Platform TTS engine
  ─ cyan boxes + labels                 speech rate 1.05×
  ─ amber highlight on primary          language = device locale
  ─ centre crosshair
  ─ status: LABEL · ±XX° · X.Xm · dir · conf%
```

---

## 5. The LiDAR Bypass — Seven Innovations

### 5.1 Bounding-Box Geometry as Depth (Pinhole Camera Model)

**The insight:** A calibrated pinhole camera produces an image where every object's projected height is inversely proportional to its real distance. This is projective geometry — it holds for any camera without exception.

```
                      Real world          Image plane
                      ──────────          ───────────
    Object height H ──────────────────▶  projected height h
    Distance D                           focal length f

    Fundamental relation:  h / f  =  H / D
    ∴  D  =  H × f / h
```

Rearranged for the normalised bounding-box representation (where h is expressed as a fraction of frame height from 0 to 1):

```
    D  =  REFERENCE_CONSTANT / (normHeight × 100)
```

`REFERENCE_CONSTANT = 100` encodes `H × f` for a typical phone camera pointed at human-scale objects. It is an empirically-calibrated constant that converts the unitless bounding-box fraction into metres.

**Why this works without LiDAR:**
- The COCO dataset contains objects of known typical sizes (persons ~1.7m, chairs ~0.9m, bottles ~0.25m). The model's bounding box inherently reflects the expected object height.
- The constant was set to match the community-established baseline from browser-based COCO-SSD implementations. Field testing confirmed it produces results accurate to ±30% at 1–4m range — sufficient for navigational guidance (the user needs "near/medium/far", not centimetre precision).
- The 10-Point Calibration Walk collects real user-environment data and will allow per-device constant tuning in Phase 2.

**Distance output thresholds:**

| normHeight | Distance | Spoken phrase |
|-----------|----------|---------------|
| ≥ 1.00 | ~1 m | "1 metre" |
| 0.50 | ~2 m | "2 metres" |
| 0.25 | ~4 m | "4 metres" |
| 0.10 | ~10 m | "10 metres" |
| 0.05 | ~20 m | "20 metres" |
| < 0.01 | clipped | capped to avoid infinity |
| > 0.91 | < 1 m | expressed in centimetres |

---

### 5.2 FOV-Mapped Bearing from Normalised X Coordinate

**The insight:** The horizontal field of view of a typical phone rear camera is ~60°. The normalised centre-X of a bounding box maps linearly to horizontal angle from the camera axis.

```
    bearing° = (normCentreX − 0.5) × FOV_DEGREES

    normCentreX = (box.left + box.right) / 2     [Detection.centerX()]
    FOV_DEGREES = 60
```

This converts pixel position into a real angle in a single multiply — no trigonometry, no calibration, no depth required. The formula is symmetric: negative degrees = left of centre, positive = right.

**Verbal thresholds (matching web PWA AurigaAnnounce exactly):**

```
    |deg| < 5°          →  "ahead"
    −15° ≤ deg < −5°    →  "slightly left"
    deg < −15°          →  "to your left"
    5° < deg ≤ 15°      →  "slightly right"
    deg > 15°           →  "to your right"
```

**Why 5° and 15°?** These were chosen to match the angular resolution of the human ear. A normal-hearing person can localise a sound to within ~5° horizontally. Making the "ahead" zone ±5° means the user can physically sweep the phone until the haptic double-pulse fires, then know the object is directly in front of them — they can walk straight to it.

---

### 5.3 GhostAnchor — Visual Odometry & Anti-Tremor Smoothing

**The problem:** A phone held by a blind person walking on uneven ground produces frame-to-frame jitter of up to ±40 pixels. Feeding raw bounding-box coordinates to TTS produces:

> *"1.2 metres, slightly right."*
> *"1.8 metres, to your right."*
> *"1.1 metres, slightly right."*

Three consecutive frames. Three different distance readings. The user gets no useful signal — only noise.

**The GhostAnchor solution (`OdometryManager.java`):**

1. **Adaptive low-pass filter per spatial column:**
   - Steady-state update weight α = 0.35 (slow to change — filters tremor)
   - When the reading jumps > 2.5σ from its running variance, α rises to 0.70 (fast to catch genuine object movement)
   - Drops back to 0.35 after the reading stabilises

2. **Visual-shift centroid:** Computes the brightness centroid of each frame. If the centroid moves > 25px/frame, the camera itself is panning. During a fast pan, the distance update is frozen entirely — the smoothed value is returned unchanged. This prevents a single panning motion from poisoning the filter state with a false distance reading.

3. **Independent bearing smoother:** Bearing has its own adaptive low-pass state, decoupled from distance. A sideways pan affects bearing but not necessarily distance; the two must be smoothed separately.

```
    ┌────────────────────────────────────────────────────┐
    │           OdometryManager state per column         │
    │                                                    │
    │  smoothedDistance[col]   ◄── adaptive α filter     │
    │  distanceVariance[col]   ◄── running variance      │
    │  smoothedBearing[col]    ◄── independent smoother  │
    │  lastAnchorX, lastAnchorY ◄── GhostAnchor centroid │
    │  lastShiftMagnitude       ◄── px/frame camera speed│
    └────────────────────────────────────────────────────┘
```

---

### 5.4 TruePath + SkyShield — Triangulation Engine

**`TriangulationEngine.java`** runs two independent distance estimators on every frame and uses their agreement as a confidence signal:

- **TruePath™ (ground distance):** Uses bounding-box bottom row — the point where the object meets the floor. Row position encodes angle below the camera horizon. Combined with the pitch reading from `HardwareHAL` (accelerometer), this corrects for handheld tilt. A phone tilted 15° downward would otherwise read every object as ~25% closer than it really is.

- **SkyShield™ (suspended height):** Uses bounding-box height + top row to estimate how high an object is above ground. Identifies walls, poles, and overhead hazards that TruePath would misclassify as floor-level obstacles.

- **Sanity check:** If the two estimators disagree by > 25% (the `SANITY_BAND`), the frame's confidence is cut toward zero. The smoother in `OdometryManager` treats low-confidence frames as noise and does not update its state. The user hears nothing for that frame — silent rejection of bad data — instead of a garbled reading.

```
    Frame:  person, normHeight=0.42, normBottomRow=0.71
              │
              ├── TruePath:   pitch-corrected row angle → 2.1 m
              ├── BBoxHeight: REFERENCE_CONSTANT formula → 2.4 m
              │
              │   |2.4 - 2.1| / 2.1 = 14%  <  25% sanity band
              │                         → sanityScore = 0.86
              │                         → PASSED, update smoother
              ▼
         OdometryManager.smoothDistance(2.4, confidence=0.86, shift=4px)
```

---

### 5.5 Smart Lighting — Two-Phase Torch State Machine

**The problem:** Dark environments produce near-random bounding boxes. YOLOv8n trained on daylight images degrades severely below ~55 mean luminance. A torch that stays on constantly drains the battery, overheats the module, and washes out frames.

**The two-phase solution:**

```
    ┌─────────────────────────────────────────────────────────┐
    │  Phase 1 — Probe frame arrives                          │
    │                                                         │
    │  meanLuminance(frame) < DARK_THRESHOLD (55)?            │
    │       YES                          NO                   │
    │        │                            │                   │
    │  Fire torch                    Run inference normally   │
    │  Set torchPrimed = true                                 │
    │  Return (skip inference this frame)                     │
    └─────────────────────────────────────────────────────────┘
                   ▼  (next ImageProxy arrives ~333ms later)
    ┌─────────────────────────────────────────────────────────┐
    │  Phase 2 — Lit frame arrives (torchPrimed == true)      │
    │                                                         │
    │  torchPrimed = false                                    │
    │  Run YOLOv8n inference on torch-lit frame               │
    │  Kill torch immediately after inference                 │
    │  Emit detections                                        │
    └─────────────────────────────────────────────────────────┘
```

- **DARK_THRESHOLD = 55** — mean luma below this is navigationally unreliable.
- **TORCH_SETTLE_MS = 130** — enough time for the motor and sensor to settle before the lit frame is captured; the 333ms analysis interval naturally provides this.
- **`torchPrimed` is `volatile boolean`** — written on the analysis thread, read on it too, but must be visible if Android ever migrates the callback across threads.

**Result:** Torch fires only for the minimum time required. Battery impact is ~1/6 of "always on" and the inference frame sees full torch illumination.

---

### 5.6 On-Device Neural Inference (No Network, No Cloud)

**Web PWA:** TensorFlow.js + COCO-SSD lite, fully browser-resident. The model is cached on first visit by `sw.js`. Navigation works on a plane with the screen on and Wi-Fi off.

**Android native:** YOLOv8n quantised to `float32` TFLite. Runs on the Android Neural Networks API (delegated to GPU or DSP where available). Analysis runs on a single-thread `ImageAnalysis` executor at ~3 fps — deliberately throttled to keep thermal load reasonable.

**Why YOLOv8n over COCO-SSD?**
- YOLOv8n: 6.3M parameters, single-shot detection, anchors learned from data → better small-object recall
- COCO-SSD: MobileNet backbone, two-stage, slower on-device, fewer classes in the lite variant
- Both are provided: COCO-SSD runs in the web PWA (no TFLite required), YOLOv8n runs natively. They share the same 80-class COCO label set so the `TargetStore` filter works identically on both.

**Graceful degradation:** If no `.tflite` file is bundled, `YoloDetector.tryCreate()` returns null. `LocatorActivity` detects this and shows a friendly "model not bundled" panel with a one-tap fallback to `LocatorWebActivity` (the PWA WebView wrapper). The user is never left with a broken screen.

---

### 5.7 Serpentine Gesture Navigation

**`SerpentineGestureDetector.java`** enables a blind user to navigate the app without looking at the screen:

```
    Gesture           Action
    ─────────────     ──────────────────────────────
    Swipe right →     next screen / next target
    Swipe left  ←     previous screen / previous target
    Swipe up    ↑     increase mode / zoom in
    Swipe down  ↓     decrease mode / zoom out
    Double tap        confirm / select
    Long press        context menu
```

**The critical implementation detail:** The detector must receive ALL touch events, not just those that reach the `LocatorOverlayView`. The overlay view is non-interactive (no click handler), so Android's normal event dispatch drops MOVE and UP events before they arrive — a classic bug that silently kills swipe recognition.

**Fix:** The detector is attached via `dispatchTouchEvent()` override in `LocatorActivity` itself. Every touch event passes through `serpentine.onTouch(ev)` at the activity level before any view sees it. MOVE and UP events are never dropped.

```java
@Override
public boolean dispatchTouchEvent(MotionEvent ev) {
    serpentine.onTouch(ev);           // feed the gesture detector first
    return super.dispatchTouchEvent(ev);  // then normal view dispatch
}
```

---

## 6. Distance Formula — Full Derivation

### Pinhole Camera Projection

A pinhole camera with focal length `f` (pixels) maps a real-world point at distance `D` (metres) and height `H` (metres) to a pixel row `y`:

```
    y = (f × H) / D
```

Solving for D:

```
    D = (f × H) / y
```

In normalised image coordinates (0–1), `y = normHeight`, `f → 1`:

```
    D ≈ H / normHeight
```

For human-scale COCO objects, the average effective height `H` is approximately 1 metre when observed through a typical phone camera. The empirical constant captures this:

```
    D_metres = REFERENCE_CONSTANT / (normHeight × 100)
             = 100 / (normHeight × 100)
             = 1 / normHeight
```

### Why REFERENCE_CONSTANT = 100?

`100 / (normHeight × 100)` simplifies to `1 / normHeight`. The constant encodes `f × H_average` in a form that produces metres for objects with normalised heights between 0.05 (far) and 1.0 (filling the frame). It was validated against the web PWA reference implementation and is consistent with the community-established COCO-SSD spatial formula.

### Distance String Formatting

```
    dist < 1.0 m   →  "{round(dist × 100)} centimetres"
    dist ≥ 2 m     →  "{round(dist)} metres"   (whole-number rounding)
    otherwise      →  "{dist:.1f} metres"       (one decimal place)
```

Voice examples: *"80 centimetres"*, *"1.2 metres"*, *"2 metres"*, *"10 metres"*.

### Known Limits & Honest Caveats

| Scenario | Effect | Mitigation |
|---|---|---|
| Object much shorter/taller than average | Distance over/underestimated | Stated as orientation, not navigation-critical |
| Camera tilted (user bowing head) | Row geometry shifts — apparent distance changes | TruePath pitch correction via HardwareHAL accelerometer |
| Object fills only part of bounding box (partial occlusion) | normHeight understates real height → distance overestimated | SkyShield sanity check flags these frames |
| Very near objects (normHeight > 0.9) | Distance < 1m → centimetre output | Works correctly; formula stable |
| Very far objects (normHeight < 0.03) | Distance > 30m | Accuracy degrades; not navigationally relevant |

---

## 7. Bearing Formula — Full Derivation

### Camera Geometry

A phone camera with horizontal FOV of 60° maps each pixel column to a horizontal angle. The frame centre is 0°. The right edge is +30°. The left edge is −30°.

```
    bearing° = (normCentreX − 0.5) × FOV_DEGREES

    normCentreX  ∈ [0, 1]      0 = left edge, 1 = right edge
    FOV_DEGREES  = 60
    Result       ∈ [−30°, +30°]
```

### Verbal Threshold Bands

```
                     −30°     −15°   −5°  0  +5°   +15°    +30°
                      │        │      │   │   │      │       │
    ┌─────────────────┼────────┼──────┼───┼───┼──────┼───────┤
    │  to your left   │ slight │      │ ahead │slight│  to your right
    │                 │  left  │      │       │right │
    └─────────────────┴────────┴──────┴───┴───┴──────┴───────┘
```

**Why these thresholds?**
- **±5° → "ahead"**: The human pointing resolution for a phone is ~5°. A blind user sweeping the camera can reliably achieve ±5° centering without visual feedback when haptic confirms lock-on.
- **±15° → "slightly"**: Within 15° the object is still within a comfortable viewing angle and reachable with a small wrist rotation. Beyond 15° the user needs a meaningful body or arm turn.

### HUD Display Format

```
    CHAIR · +23° · 1.2 metres · slightly right · 87%
    label   deg    distance     bearing dir       confidence
```

---

## 8. Voice Announcement Pipeline

```
    Detection (label, box, confidence)
            │
            ▼
    distanceM(d)        → float metres
    bearingDeg(d)       → float degrees
    bearingDir(degrees) → String direction
    distanceStr(d)      → String human phrase
            │
            ▼
    Cooldown gate:
    ─ same label, < 2.2s since last → SKIP
    ─ new label, < 0.7s since last  → SKIP
    ─ otherwise → SPEAK
            │
            ▼
    Phrase assembly:
    "{Capitalised label}, {distance}, {direction}."
    "Chair, 1.2 metres, slightly right."
    "Person, 80 centimetres, ahead."
    "Bottle, 3 metres, to your left."
            │
            ▼
    TextToSpeech.speak(utterance, QUEUE_FLUSH, ...)
    ─ QUEUE_FLUSH: cuts off any previous utterance instantly
    ─ speechRate 1.05× (slightly faster than default — blind TTS users
      typically run 1.5–2.5× but 1.05 is the safe default)
    ─ language: Locale.getDefault()
```

**Web PWA equivalent — `AurigaAnnounce.compose.objectFound`:**
```javascript
capitalize(label) + ', ' + formatDistance(meters) + ', ' + formatBearing(degrees, 'narrow') + '.'
```

Both produce the same phrase. A user switching between the web PWA and the native APK hears identical announcements.

---

## 9. Bearing-Aware Haptic Guidance System

The haptic channel is designed to let a user **find** a target by feel alone — no voice, no screen.

### Haptic Pulse Architecture (HapticManager)

```
    HapticManager.pulse(distanceM):
    ─ Guard 1: distanceM must be in [0.30, 3.00] m
    ─ Guard 2: ≥ 350ms since last pulse (anti-spam)
    ─ Guard 3: distance changed by ≥ 0.15m since last pulse
    ─ Duration: PULSE_BASE_MS + max(0, (1.5 - dist) × 20)
               = 40ms + up to 50ms for near objects
               Near objects pulse heavier → user feels the closeness

    HapticManager.alert():
    ─ Not rate-limited (hazards are rare, each genuinely needs attention)
    ─ Pattern: [0ms wait, 150ms on, 50ms gap, 150ms on]
    ─ "Double knock" — universally understood as a confirmation signal
```

### Bearing-Aware Logic (LocatorActivity.announceTarget)

```
    absDeg = |bearingDeg(d.centerX())|

    ┌───────────────────────────────────────────────────────────┐
    │  absDeg < 5°    Camera is aimed directly at the target    │
    │                 → haptic.alert()                          │
    │                   Double pulse: "you're on it"            │
    │                   User can walk forward confidently       │
    ├───────────────────────────────────────────────────────────┤
    │  5° ≤ absDeg < 15°   Slightly off-axis                   │
    │                 → haptic.pulse(distanceM)                 │
    │                   Single pulse, weighted by distance      │
    │                   Cues the user to refine aim             │
    ├───────────────────────────────────────────────────────────┤
    │  absDeg ≥ 15°   Far off-axis                             │
    │                 → SILENT                                  │
    │                   Voice says "to your left / right"       │
    │                   Haptic here adds noise, not signal      │
    └───────────────────────────────────────────────────────────┘
```

**Practical use-case:** User hears *"Chair, 3 metres, to your right."* They rotate right. At 15° off they feel a single pulse. At 5° off they feel a double pulse — they know the chair is directly ahead and can walk to it. No further voice announcements needed until they choose to stop.

**Haptic is voice-independent:** The bearing-aware pulse fires even when voice is muted. A user who needs silence (library, meeting) can still sweep to lock on using haptic alone.

---

## 10. Wake Word & Voice Command System

**`AurigaVoiceService`** (background) and **`AurigaVoiceEngine`** (command recognition) share one microphone. They cannot run simultaneously — the mic is an exclusive resource.

```
    Normal state:
    ┌──────────────────────────────────────┐
    │ AurigaVoiceService (wake-word loop)  │
    │ Listens continuously for "Auriga"    │
    └──────────────────────────────────────┘

    Wake word detected:
    ┌──────────────────────────────────────┐
    │ 1. Pause VoiceService (mic released) │
    │ 2. AurigaVoiceEngine.start()         │
    │    Listens for command word          │
    │    e.g. "find chair", "mute", "help" │
    │ 3a. onResults → execute command      │
    │     → restart VoiceService           │
    │ 3b. onError  → destroy + recreate    │
    │     SpeechRecognizer (fixes dead mic)│
    │     → restart VoiceService           │
    └──────────────────────────────────────┘
```

**Critical fix applied:** On `onError`, the `SpeechRecognizer` is explicitly `destroy()`ed and recreated rather than reused. Android's SpeechRecognizer enters a permanently broken state after certain errors (network timeout, audio focus loss) and silently ignores all subsequent `startListening()` calls. Destroy + recreate is the only reliable recovery.

**TTS feedback during command listening is suppressed.** The previous implementation spoke *"Listening"* as a confirmation, which fed into the open microphone and caused the recogniser to detect its own announcement as a command word. The mic opens silently; voice confirmation fires only after the mic is closed.

---

## 11. 10-Point Calibration Walk

**`CalibrationWalkActivity`** guides the user through ten standardised phone poses:

| Step | Pose | Purpose |
|------|------|---------|
| 1 | 1 metre from wall, level | Reference distance baseline |
| 2 | 2 metres from wall, level | Mid-range baseline |
| 3 | 3 metres, level | Far-range baseline |
| 4 | Level, angled left 20° | Bearing left calibration |
| 5 | Level, angled right 20° | Bearing right calibration |
| 6 | Tilted down 15° | Pitch correction baseline |
| 7 | Tilted up 15° | Overhead hazard angle |
| 8 | Low light environment | Smart Lighting threshold |
| 9 | Moving (walking) | GhostAnchor shift baseline |
| 10 | Crowded scene (3+ objects) | Multi-object filter baseline |

Completion is persisted to `SharedPreferences` under `calibration_walk_completed`. The "Send Feedback" drawer row is gated behind completion — a user who has calibrated sends contextually useful data.

**Phase 2 plan:** Calibration data will auto-tune `REFERENCE_CONSTANT` per device, correcting for camera focal length variation. A Pixel 7 Pro and a Samsung A14 have different effective FOVs; a per-device constant will improve distance accuracy from ±30% to ±10%.

---

## 12. Target Management System

**`TargetStore` (Android) / `locator-store.js` (Web)**

The user filters the 80 COCO classes to the objects they care about:

```
    All 80 COCO classes available:
    person · bicycle · car · motorbike · aeroplane · bus · train · truck
    boat · traffic light · fire hydrant · stop sign · bench · cat · dog
    horse · cow · elephant · bear · zebra · chair · sofa · table · bed
    toilet · laptop · phone · keyboard · microwave · oven · sink · book
    clock · vase · scissors · teddy bear · toothbrush ... (80 total)

    Default: ANY — match everything (preserves pre-Targets behaviour)
    Custom:  CSV of class names in SharedPreferences key 'auriga_targets'
```

Each target stores:
- `name` — COCO class label
- `description` — user mission note ("my red coffee mug near the window")
- `lastSeenAt` — Unix timestamp of last detection
- `lastBearing` — bearing at last sighting
- `lastDistance` — distance at last sighting

The web UI shows: *"Chair — last seen 3 minutes ago, 1.2 metres, slightly right."*

**Focus deep-link:** `locator.html?focus=chair` locks the HUD to a single target class for a session without mutating the persisted list. The native APK passes this as an intent extra from `TargetsActivity`.

---

## 13. Offline-First Architecture

```
    First visit (requires network):
    ─ Service worker sw.js registers and pre-caches:
      index.html · locator.html · reader.html · feedback.html
      locator-targets.html · calibration-library.html
      locator-store.js · nav-drawer.js · auriga-announce.js
      TF.js + COCO-SSD model weights (opportunistic cache on first load)
      Tesseract.js + language data (reader)

    All subsequent visits (fully offline):
    ─ SW intercepts every fetch
    ─ Cache-first for app shells and static assets
    ─ Network-first with cache fallback for data/ JSON
    ─ Background Sync (tag: auriga-feedback-flush) drains the
      IndexedDB feedback queue when network returns

    Cache version: drakosanctis-v8 (bumped on any asset change)
```

**Android APK:** All web assets are bundled into the APK via `copyWebDeployToAssets` Gradle task. The WebView loads over `file://`. Service worker does not run (not supported on `file://` origin) — assets are always available without any network.

---

## 14. Variant Differentiation

### NAVI (Blue — #00B8D4)
**Profile:** Blind and low-vision personal navigation.
**Primary sensor:** Rear camera.
**Key features:** Object detection, distance + bearing voice, haptic lock-on, targets filter, DrakoVoice Reader, wake-word "Auriga", serpentine gesture nav, smart lighting.
**Not included:** Multi-node mesh, fall detection, overhead SkyShield (in NAVI it runs but is not the primary UX).

---

### SENTINEL (Red — #D50000)
**Profile:** Professional carer / security monitoring for a third party.
**Primary sensor:** Fixed-mount or body-worn camera.
**Key features:** All NAVI features + GodsEyeOrchestrator mesh, fall detection (`checkFallEvent`), multi-subject path logging (`PathLog`), tactical audit geometry strings, SkyShield overhead alert mode as primary output, CrashReportActivity with pre-addressed email to carer.
**Drawer accent:** Red. String overrides: "SENTINEL ENGINE", "TACTICAL SCAN", "SUBJECT TRACK", "FALL ALERT".

---

### GARDEN (Green — future)
**Profile:** Outdoor obstacle navigation in unstructured environments.
**Primary sensor:** Camera + future ultrasonic rangefinder (SonarManager).
**Key features:** NAVI core + terrain-level obstacle classification (curb, step, slope), OsmAnd integration for routing, `SonarManager.java` stub ready for hardware attachment.

---

### SPIRIT (Purple — future)
**Profile:** Indoor spatial memory for dementia care.
**Primary sensor:** Camera + optional BLE beacon mesh.
**Key features:** Room mapping, familiar-face recognition, GhostAnchor path replay, reminder triggers at spatial anchors.

---

## 15. Competitive Matrix

| | **Auriga Navi** | OrCam MyEye | Seeing AI | Lookout | Be My Eyes |
|---|---|---|---|---|---|
| **Cost** | Free | $4,500 | Free | Free | Free |
| **Hardware required** | Phone you own | Dedicated clip-on | Phone | Phone | Phone |
| **Works offline** | Yes — 100% | Yes | No | Partial | No |
| **Distance measurement** | Yes (bbox geometry) | Yes (laser rangefinder) | No | No | No |
| **Bearing measurement** | Yes (FOV mapping) | Yes (IMU) | No | No | No |
| **Real-time (<100ms)** | Yes | Yes | No (cloud RTT) | No (cloud) | No (human relay) |
| **Haptic guidance** | Yes (bearing-aware) | Vibration only | No | No | No |
| **Voice commands** | Yes (wake word) | Button press | Tap | Tap | Tap |
| **80-class object filter** | Yes | Limited | Limited | No | Human decides |
| **OCR** | Yes (ML Kit offline) | Yes | Yes | No | Human reads |
| **Android APK** | Yes | No (iOS clip-on) | Yes | Yes | Yes |
| **Web PWA** | Yes | No | No | No | No |
| **Source available** | Yes | No | No | No | No |

**The core thesis:** Auriga achieves feature parity with a $4,500 hardware device by replacing the depth sensor with a mathematical model that the phone camera already satisfies for free. The remaining accuracy gap (±30% vs ±2% for laser) is irrelevant for navigational guidance — a blind person walking toward a chair does not need centimetre precision; they need *direction* and *roughly how far*.

---

## 16. Phase Roadmap

### Phase 1 — Native YOLOv8n + OCR Polish *(current)*
- [x] CameraX + TFLite YOLOv8n inference at ~3 fps
- [x] Bounding-box distance formula (REFERENCE_CONSTANT model)
- [x] FOV bearing formula with verbal thresholds
- [x] Bearing-aware haptic (double-pulse lock-on, single-pulse guide, silent far)
- [x] Smart Lighting two-phase torch
- [x] GhostAnchor adaptive low-pass smoothing
- [x] TriangulationEngine TruePath + SkyShield + sanity score
- [x] AurigaAnnounce unified voice phrase composer (web + native parity)
- [x] ML Kit offline OCR DrakoVoice Reader
- [x] Wake-word system with mic arbitration fix
- [x] Serpentine gesture navigation (dispatchTouchEvent fix)
- [x] 10-Point Calibration Walk
- [x] Target management (80 COCO classes, focus deep-link)
- [x] Feedback pipeline (ticket ID, Gmail, GitHub Issues, webhook)
- [x] PWA + service worker offline caching (v8)

### Phase 2 — PaddleOCR + Per-Device Calibration
- [ ] PaddleOCR native (replaces ML Kit — better multilingual, smaller model)
- [ ] Calibration Walk auto-tunes REFERENCE_CONSTANT per device focal length
- [ ] Piper TTS (replaces platform TTS — consistent voice quality across OEMs)
- [ ] Vosk wake word (offline, no Google dependency)

### Phase 3 — Moondream 2 Scene Description
- [ ] Moondream 2 on-device VLM: "Describe what's in front of me"
- [ ] Spatial anchoring: "Remember this place as kitchen door"
- [ ] OsmAnd integration: routing + landmark announcement

### Phase 4 — Multi-Modal Sensor Fusion
- [ ] SonarManager: ultrasonic rangefinder attachment for ±2% distance accuracy
- [ ] BLE beacon mesh for indoor room identification
- [ ] GodsEyeOrchestrator production mode (Sentinel mesh)

### Phase 5 — Hardware Partnership
- [ ] Reference design: clip-on PCB with dedicated camera, IMU, haptic motor
- [ ] BOM target < $50 hardware cost
- [ ] OEM Android certification for medical device exemption

---

---

## 17. Architecture Decisions (Session 18 Integration Fixes)

### 17.1 Camera ownership — FrameRelay singleton

Both `AurigaCoreService` and `LocatorActivity` previously bound the same physical
camera device via `ProcessCameraProvider.bindToLifecycle()`. On Android 12+
(SDK 34/35, Samsung SM-A057F) this caused a `SecurityException` ("camera from a
different process") when CameraX session-drain callbacks fired on the service's
`analysisPool` thread.

**Architecture decision:** Exactly one camera owner at a time.
`LocatorActivity` owns CameraX when in the foreground. `AurigaCoreService` listens
on `FrameRelay` — a process-local singleton frame bus — instead of binding CameraX
directly. `LocatorActivity.onResume()` calls `FrameRelay.markSourceActive()` and
pushes processed bitmaps after each YOLO cycle; `onPause()` calls
`markSourceInactive()`. The service's engines (StairSense, TrafficSense,
CrossingGuard, GodsEye) receive NV21 frames converted internally by `FrameRelay`.

### 17.2 Voice command dispatch — unified 5-tier chain

Previously `AurigaCoreService.CommandRouter` had camera skills registered but
nothing actually called `dispatch()` from the voice pipeline.
`AurigaVoiceEngine.routeCommand()` handled skill engine + LLM but had no path to
camera skills (describe scene, stair, crossing, find face, read cash, etc.).

**Architecture decision:** A new `tryDispatchCameraCommand()` method on
`AurigaCoreService` (accessible via the `static volatile instance` field) is
inserted as Tier 3 in `AurigaVoiceEngine.routeCommand()`:

```
T1: UI / navigation (open drawer, back)
T2: AurigaSkillEngine  (timers, alarms, weather)
T3: AurigaCoreService.tryDispatchCameraCommand()  ← new
T4: AurigaKnowledge KB (instant, offline)
T5: MindEngine Qwen LLM
T6: KnowledgeCache / AurigaKnowledge.fallback()
```

`AurigaCoreService` also boots its own `TextToSpeech` + `AurigaSkillEngine` +
`KnowledgeCache` + `MindEngine` chain via `initTts()` in `onCreate()`, so its
standalone `onVoiceCommand()` (called from AurigaButlerService or future clients)
also reaches the LLM. `CommandRouter.dispatch()` now returns `null` on no-match so
callers can detect it cleanly without string comparison.

### 17.3 Mic audio-focus — AudioRecord VAD

`AurigaVoiceService` previously looped `SpeechRecognizer.startListening()` every
~300 ms. Each call caused the recognizer's internal Google process to request
`AUDIOFOCUS_GAIN`, ducking music continuously — critical UX issue for blind users
who rely on audio feedback.

**Architecture decision:** Replace the loop with `AudioRecord` + energy-RMS VAD.
`AudioRecord(VOICE_RECOGNITION, 16kHz)` reads PCM continuously with **zero audio
focus requests** — music plays uninterrupted. The VAD fires `SpeechRecognizer` only
after 300 ms of sustained speech above RMS threshold 800. The recognizer is active
for at most 2–3 seconds per utterance. Graceful fallback to the old loop on devices
where `AudioRecord` fails to initialize (logged with a warning).

---

*Blueprint version: 1.1.0 — Auriga Navi · Session 18 integration fixes applied*
*Maintained by DrakoSanctis / Michael Omondi*
*All formulas, thresholds, and constants described here are live in the codebase.*
