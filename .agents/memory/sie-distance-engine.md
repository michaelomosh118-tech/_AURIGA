---
name: SIE Distance Engine
description: Architecture of the Sensory Independent Engine — DracoAID auto-calibration, virtual fiducial LUT, and LocatorActivity wiring
---

## The pipeline (LocatorActivity → SIE)

1. **YoloAnalyzer** runs YOLO on each frame, captures `fW`/`fH`.
2. **DracoAIDEngine.processFrame()** watches detections for "person" (COCO class 0). Once 5 stable readings accumulate it commits H_c and calls `FiducialLUT.generateDynamicTable()`.
3. **TriangulationEngine.computeGroundDistance / computeSuspendedHeight** is called with denormalised pixel coords from the Detection bbox.
4. **announceTarget()** speaks: `"{Label}, {distance}, {clock position}, {height}."` and fires a `ToneGenerator.TONE_PROP_BEEP2` when |bearing| < 5°.

## H_c solve math

```
a = anklePixelY - horizonRow   (ankle below horizon)
b = horizonRow  - headPixelY   (head above horizon)
H_c = 1.70 * a / (a + b)      (denominator = bbox pixel height, always > 0)
```

Valid range: 0.40 m – 2.50 m. Stability gate: 5 readings within ±15 % of median.

## FiducialLUT.generateDynamicTable()

```
pixelRow   = horizonRow + focalPx * H_c / D
pixelWidth = 0.20 * focalPx / D
```
16 distance samples 0.3 m → 10 m. Replaces synthetic defaults on first DracoAID commit.

## Persistence

`HardwareHAL.storeHc()` / `loadStoredHc()` — SharedPreferences file `auriga_hardware_hal`, key `draco_aid_hc_metres`. LUT is regenerated from stored H_c on `onCreate()` before first inference frame.

## SkyShield threshold

`basePixelY < frameHeight * 0.42` → suspended object → `calculateSuspendedHeight()`. Otherwise TruePath ground distance.

## Output format

`"Chair, 2 metres, 10 o'clock, waist height."` Clock positions: 10/11/12/1/2 o'clock. Height zones: floor / ankle / knee / waist / chest / head / overhead.

**Why:** Old `REFERENCE_CONSTANT / (normH * 100)` formula had no physical grounding — same constant for a cup and a truck. DracoAID derives the LUT from camera geometry, making distance proportional to real-world geometry without any physical markers or accelerometer dependency.
