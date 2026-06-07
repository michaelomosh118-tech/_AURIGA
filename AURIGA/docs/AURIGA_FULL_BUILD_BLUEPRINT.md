# AURIGA ECOSYSTEM — FULL ENGINEERING BLUEPRINT
### Authored: DrakoSanctis · Senior Systems Architecture Document
### Classification: Master Build Reference · Version 1.0
### Target: Offline · Cross-Platform · Financially Independent · VI-First

---

## TABLE OF CONTENTS

1. [Executive Summary](#1-executive-summary)
2. [Market Intelligence & Competitive Autopsy](#2-market-intelligence--competitive-autopsy)
3. [VI Community Pain Point Analysis](#3-vi-community-pain-point-analysis)
4. [Feature Gap Analysis — What No One Has Built Yet](#4-feature-gap-analysis--what-no-one-has-built-yet)
5. [What Already Exists in Auriga](#5-what-already-exists-in-auriga)
6. [Complete Feature Specification — The Full Build](#6-complete-feature-specification--the-full-build)
   - 6.0 Accessibility & Onboarding Layer *(new — must build before all other modules)*
   - 6.1 Core Navigation Layer
   - 6.2 Identification Layer
   - 6.3 Safety Layer
   - 6.4 Connectivity & Wearable Layer
   - 6.5 AI Companion & Butler Layer *(AurigaButler™ added)*
7. [System Architecture](#7-system-architecture)
8. [Module Interface Contracts](#8-module-interface-contracts)
9. [Cross-Platform Deployment Matrix](#9-cross-platform-deployment-matrix)
10. [Offline-First Design Principles](#10-offline-first-design-principles)
11. [Financial Independence Architecture](#11-financial-independence-architecture)
12. [Phased Build Roadmap](#12-phased-build-roadmap)
13. [GitHub Actions CI/CD Pipeline](#13-github-actions-cicd-pipeline)
14. [Hardware Target Specifications](#14-hardware-target-specifications)
15. [Accessibility Standards Compliance](#15-accessibility-standards-compliance)
16. [Testing Strategy](#16-testing-strategy)
17. [Success Metrics & Benchmarks](#17-success-metrics--benchmarks)
18. [Ordered Session Build List](#18-ordered-session-build-list)

---

## 1. EXECUTIVE SUMMARY

Auriga is being built to be the **only assistive spatial intelligence platform a visually impaired person will ever need** — fully offline, free at the core, running on hardware they already own, and extensible to wearable and embedded form factors.

The competitive landscape (Be My Eyes, OrCam, Seeing AI, Google Lookout, Envision) is fractured. Every product solves one or two problems in isolation, requires internet, costs hundreds to thousands of dollars, or locks users into proprietary hardware. Auriga's structural advantage is the integration of all capabilities into a single offline system across phones, PCs, and microcontrollers.

This document is the single source of truth for every engineering decision, every module interface, every build step, and every product milestone from the current state through full production readiness.

**Core promises to the user:**
- Works with no internet, no SIM card, in a basement, in rural Africa, in a blackout
- Costs $0 to run (optional one-time hardware kit)
- Runs on any Android phone from 2019 onward, any Raspberry Pi, any Linux PC, select microcontrollers
- Speaks, vibrates, and thinks faster than any human volunteer can respond
- Remembers the user's environment, preferences, and personal contacts
- Outlasts every competitor on features, latency, and trust

---

## 2. MARKET INTELLIGENCE & COMPETITIVE AUTOPSY

### 2.1 Competitor Analysis Matrix

| Product | Price | Offline? | Spatial Nav | OCR | Face ID | Obstacle Det. | Platform | Fatal Flaw |
|---|---|---|---|---|---|---|---|---|
| **Be My Eyes** | Free | ❌ | ❌ | ❌ | ❌ | ❌ | iOS/Android | Needs internet + stranger to see your home |
| **OrCam MyEye 3** | $3,500–$4,500 | ✅ | ❌ | ✅ | ✅ | ❌ | Proprietary hardware | Price excludes 99% of global VI population |
| **Microsoft Seeing AI** | Free | Partial | ❌ | ✅ | ✅ | ❌ | iOS only | Cloud-dependent; no Android; no navigation |
| **Google Lookout** | Free | ❌ | ❌ | ✅ | ❌ | Partial | Android | No distance, no haptics, needs Google Play Services |
| **Envision AI** | $99/yr | ❌ | ❌ | ✅ | ✅ | ❌ | iOS/Android | Subscription; all inference is cloud |
| **JAWS** | $1,000+ | ✅ | N/A | ✅ | ❌ | ❌ | Windows | Desktop screen reader only; no real-world vision |
| **VoiceOver / TalkBack** | Free (OS) | ✅ | ❌ | ❌ | ❌ | ❌ | iOS/Android | Screen reader only; zero spatial awareness |
| **Aira** | $29–$89/mo | ❌ | ❌ | ❌ | ❌ | ❌ | iOS/Android | Human agent subscription; privacy risk |
| **NuEyes** | $2,000+ | Partial | ❌ | ✅ | ❌ | ❌ | Proprietary AR glasses | Price and fragility |
| **Auriga (current)** | Free | ✅ | ✅ | ✅ | ❌ | ✅ | Android/PWA | Needs face ID, pill ID, indoor nav, PC/Pi port |

### 2.2 Strategic Gaps — Where Every Competitor Fails Simultaneously

The following capabilities are **missing from every competitor product**:

1. **Real-time obstacle distance + direction** (none do this offline with sub-20cm accuracy)
2. **Medicine / pill identification** offline (zero competitors have this)
3. **Indoor room navigation with memory** (no cloud, no BLE beacons required)
4. **Emergency SOS with spoken location description** offline
5. **Approaching vehicle early warning** for road crossing
6. **Stair geometry detection** (step count, rise height, direction)
7. **Wearable / microcontroller form factor** (glasses-mount, cane-mount, haptic belt)
8. **P2P volunteer mode** without a central server (Be My Eyes without Be My Eyes)
9. **Cross-platform single binary** (phone + PC + Pi, same codebase)
10. **SDK for hospitals and NGOs** to build on without licensing fees

### 2.3 Pricing Exploitation Opportunity

The global VI population is estimated at **285 million people** (WHO). 
OrCam serves ~50,000 units/year. That is **0.017% penetration** of the market.
The reason: price. Auriga entering at **$0** with superior offline capability immediately addresses the 99.98% that no commercial product touches.

Secondary revenue model: **hardware kits** (Raspberry Pi pre-configured as a wearable spatial computer) at ~$89 — more than 97% cheaper than OrCam for comparable or better functionality.

---

## 3. VI COMMUNITY PAIN POINT ANALYSIS

### 3.1 Methodology
Analysis sourced from logical inference of documented user complaints across Reddit r/Blind, r/LowVision, Twitter/X #blind #accessibility, AppleVis forums, and NFB community boards, combined with first-principles reasoning about what offline spatial navigation actually requires.

### 3.2 Top Pain Points (Ranked by Frequency × Severity)

#### TIER 1 — Dealbreakers (Users switch or abandon products over these)

**P1: Internet dependency in critical moments**
> *"Be My Eyes is useless when my data runs out or I'm underground."*
- Severity: Life-safety
- Frequency: Universal
- Auriga response: 100% offline operation on all features

**P2: Price exclusion**
> *"OrCam is life-changing technology that I'll never afford."*
- Severity: Access denial
- Frequency: >90% of global VI population
- Auriga response: Free core APK, open source, community-supported

**P3: Privacy violation (strangers see inside your home)**
> *"I can't use Be My Eyes in my bathroom or bedroom."*
- Severity: Dignity / safety
- Frequency: Very high
- Auriga response: All processing local, no video ever leaves the device

**P4: No navigation — only identification**
> *"Seeing AI tells me what's on the label but not whether I'm about to walk into it."*
- Severity: Safety
- Frequency: High
- Auriga response: TruePath™ distance + bearing + obstacle routing

**P5: Volunteer availability gaps**
> *"At 2am there's no volunteer. But I still need to read my medicine label."*
- Severity: Safety / independence
- Frequency: High (night-time, rural, non-English speakers)
- Auriga response: Fully autonomous, no human in the loop

#### TIER 2 — Major Friction (Causes daily frustration)

**P6: Medicine identification gap**
> *"I have 8 pill bottles that feel identical. I have to guess which is which."*
- Gap: No current product identifies pills offline
- Auriga response: PillGuard™ module — OCR + pill database + shape/color confirmation

**P7: No stair warning**
> *"I walked off a kerb three times before I understood the pattern of the area."*
- Gap: No competitor detects step edges or counts stairs
- Auriga response: StairSense™ module — ground-plane geometry break detection

**P8: Can't identify people approaching**
> *"Someone greeted me and I had no idea who they were."*
- Gap: Face recognition requires cloud in all competitors
- Auriga response: FaceVault™ module — on-device face recognition with user-enrolled profiles

**P9: Currency confusion**
> *"I've handed over the wrong notes in shops many times."*
- Gap: No offline currency detector
- Auriga response: CashLens™ module — note denomination detection offline (MobileNet classifier fine-tuned)

**P10: Food label / expiry date reading unreliability**
> *"OCR fails on curved bottles, blurry labels, and tiny print."*
- Gap: Generic OCR performs poorly on product packaging
- Auriga response: LabelReader™ — preprocessed high-contrast OCR pipeline with product database lookup

**P11: No memory of environment**
> *"Every time I go to the same shop I have to re-learn the layout."*
- Gap: No competitor stores spatial memory between sessions
- Auriga response: SpatialMemory™ — lightweight topological map stored locally

**P12: Audio feedback is interruptive and annoying**
> *"The app talks over everything and I can't control the cadence."*
- Gap: Fixed TTS cadence, no user control
- Auriga response: Adaptive speech cadence, priority queue, silent haptic-only mode

**P13: No crossing / vehicle warning**
> *"I nearly got hit twice because no app warns me about approaching cars."*
- Gap: Critical safety gap in all consumer VI apps
- Auriga response: TrafficSense™ — motion + depth proximity for approaching objects

**P14: Single-language TTS**
> *"The app speaks English but I think in Swahili."*
- Gap: Most apps default English only
- Auriga response: Multi-language TTS (Android system voices) + locale-aware command routing

**P15: No wearable option**
> *"I need my hands free. The phone-held form factor means I can't use my cane and the app."*
- Gap: No app runs headless on a wearable processor
- Auriga response: Raspberry Pi Zero 2W headless mode, Bluetooth earpiece output, cane-mount haptic node

#### TIER 3 — Quality of Life (Users wish for these)

**P16: Restaurant menu reading**
**P17: Traffic light state detection**
**P18: Dog / animal proximity warning**
**P19: Smart home integration (offline, local)**
**P20: Route recording and playback ("remember how I got here")**
**P21: Emergency contact auto-call with location description**
**P22: Sleep-mode listening for hazard sounds (smoke alarm, gas alarm)**
**P23: Braille display output via serial**
**P24: Voice-controlled phone dialing / SMS reading**

#### TIER 1 — Critical Onboarding Gaps (Discovered via Blind User UX Audit, 2026-06)

The following gaps were identified by walking through the complete first-run experience from a blind user's perspective. These block adoption before the user ever reaches any feature.

**P25: Keyboard barrier on first screen**
> *"You built an app for blind people and the first thing it makes me do is type."*
- Severity: Critical — kills adoption at install
- Current state: VoiceSetupActivity shows an EditText and expects typing for assistant name
- Auriga response: Replace with SpeechRecognizer-first name capture; EditText kept as fallback only

**P26: TTS and TalkBack speak simultaneously on first launch**
> *"Two voices started at once and I couldn't understand either."*
- Severity: High — confusing and inaccessible
- Current state: VoiceSetupActivity initialises TTS async; TalkBack reads the screen before TTS is ready, then both speak
- Auriga response: Detect AccessibilityManager.isTouchExplorationEnabled(); if TalkBack is running, suppress competing TTS on the setup screen and use AccessibilityEvent announcements instead

**P27: No spoken welcome explaining what the app is or how to use it**
> *"I opened it and had no idea what was happening."*
- Severity: High — user has no orientation context
- Current state: LocatorActivity opens silently; detection starts but is not explained
- Auriga response: One-time boot announcement after TTS ready: spoken welcome + instructions + command list hint

**P28: Drawer menu not reachable by TalkBack swipe**
> *"I swiped through the whole screen and couldn't find the menu."*
- Severity: Critical — all features behind this menu are invisible to blind users
- Current state: Hamburger button is a floating FrameLayout pill; TalkBack sometimes skips floating views
- Auriga response: contentDescription="Menu, double tap to open" + importantForAccessibility="yes" on the pill; also wire long-press anywhere on camera view to open drawer

**P29: Calibration walk requires seeing a pose diagram**
> *"The diagram showed me how to hold the phone but I couldn't see the diagram."*
- Severity: High — bad calibration = inaccurate distance readings for all features
- Current state: CalibrationWalkActivity shows visual poses; no audio description of target orientation
- Auriga response: SmartCalibrationEngine (see Section 6.0.4) — auto-detect device model from Build.MODEL and apply library preset; geometry-based alignment with beep-on-correct-orientation for the walk poses

**P30: Voice commands are not discoverable; users don't know they exist**
> *"Nobody told me I could just talk to it."*
- Severity: High — the app's best accessibility feature is invisible
- Current state: Wake word and long-press commands exist but are not announced
- Auriga response: AurigaButler proactive tip engine (see Section 6.5.3) + spoken reminder on every launch for first 5 uses

---

## 4. FEATURE GAP ANALYSIS — WHAT NO ONE HAS BUILT YET

### 4.1 The "If This Existed" Logic Chain

For each missing feature, the benefit chain:

```
PILL IDENTIFICATION (offline)
  → User correctly identifies medicine every time
  → Zero accidental double-dose or wrong-medication incidents
  → Replaces need for caregiver for this specific task
  → Saves ~30 minutes/day of caregiver time
  → Addresses WHO estimate of 125,000 deaths/year from medication errors

STAIR DETECTION
  → User gets 2-second warning before step edge
  → Fall rate drops significantly
  → Enables independent stair use without cane sweep
  → Extends range of environments user can safely navigate alone

OFFLINE FACE RECOGNITION
  → User knows who is approaching in their home/workspace
  → Eliminates social anxiety of not recognising known people
  → No privacy risk (face data never leaves device)
  → Eliminates dependency on human volunteer for "who is this?"

ROUTE MEMORY
  → User records a walk once; replays guidance on subsequent visits
  → Grocery store, workplace, clinic become navigable without assistance
  → Saves NGO/carer time on route training
  → Works in GPS-denied environments (indoors, underground)

APPROACHING VEHICLE DETECTION
  → Road-crossing safety dramatically improved
  → Works where audible pedestrian signals don't exist
  → Works at night when vehicle sound may be masked by wind/crowd
  → Potentially prevents fatalities

WEARABLE FORM FACTOR
  → Hands-free navigation = can use cane + Auriga simultaneously
  → Reduces phone drop risk (common, expensive for VI users)
  → Enables passive always-on mode (spatial awareness without active use)
  → Enables covert use in social situations
```

### 4.2 Capability Combinations No Competitor Has

These are compound capabilities that emerge only when multiple modules exist in one system:

- **Scene + Navigation**: "There is a door 2 metres ahead slightly left, handle is on the right side"
- **Face + Route**: "John is at the counter, the counter is 4 metres ahead"
- **Pill + Time**: "This is your 8pm metformin, you last took it 12 hours ago"
- **Currency + Commerce**: "This is a 20 pound note, the total shown on the reader is 17.50"
- **Traffic + Crosswalk**: "Pedestrian signal is green, no approaching vehicles, safe to cross"
- **Stair + Route**: "Three steps down, then corridor 8 metres, then your office door on the left"

No competitor can combine any two of these. Auriga will combine all of them offline.

---

## 5. WHAT ALREADY EXISTS IN AURIGA

This section documents confirmed implemented functionality to avoid duplicating work.

### 5.1 Implemented — Android (Java)

| Class | Function | Status |
|---|---|---|
| `TriangulationEngine` | TruePath™ distance/height/bearing with pitch correction | ✅ Complete |
| `FiducialLUT` | Device-specific calibration lookup table | ✅ Complete |
| `HardwareHAL` | Camera + accelerometer abstraction | ✅ Complete |
| `ImageProcessor` | 3-column frame scan, edge detection | ✅ Complete |
| `YoloDetector` | YOLOv8n TFLite object detection | ✅ Complete |
| `ColorSquareDetector` | Color-based target detection | ✅ Complete |
| `OdometryManager` | GhostAnchor™ spatial stabilisation | ✅ Complete |
| `SonarManager` | Audio proximity feedback (AuraTextures™) | ✅ Complete |
| `HapticManager` | Vibration patterns for proximity | ✅ Complete |
| `DrakoVoice` | Custom TTS persona | ✅ Complete |
| `AurigaVoiceEngine` | Wake word + command routing (mic-ding fixed) | ✅ Complete |
| `AurigaVoiceService` | Always-on foreground wake service | ✅ Complete |
| `AurigaSkillEngine` | OpenClaw-style skill dispatcher | ✅ Complete |
| `AurigaMemoryStore` | RAG-style local memory persistence | ✅ Complete |
| `AurigaKnowledge` | On-device knowledge base Q&A | ✅ Complete |
| `CalibrationManager` | Calibration state machine | ✅ Complete |
| `LicenseManager` | RSA-2048 tier management | ✅ Complete |
| `LocatorActivity` | Native YOLO HUD (launcher) | ✅ Complete |
| `LocatorWebActivity` | WebView HUD fallback | ✅ Complete |
| `ReaderActivity` | Offline OCR + TTS reader | ✅ Complete |
| `TargetsActivity` | Object category picker | ✅ Complete |
| `CameraConnectActivity` | USB/BT/WiFi external camera config | ✅ Complete |
| `GodsEyeOrchestrator` | Tactical mesh + path logging (stub) | 🔧 Needs completion |
| `SentinelNode` | Fall detection node (stub) | 🔧 Needs completion |
| `FeedbackActivity` | Bug reporting with diagnostics | ✅ Complete |
| `CrashReportActivity` | Crash log viewer | ✅ Complete |

### 5.2 Implemented — Web PWA

| File | Function | Status |
|---|---|---|
| `locator.html` | TF.js COCO-SSD object locator | ✅ Complete |
| `reader.html` | Tesseract.js OCR reader | ✅ Complete |
| `feedback.html` | Feedback form | ✅ Complete |
| `chat.html` | Conversational AI (WebGPU LLM) | ✅ Complete |
| `auriga-voice.js` | Voice navigation engine | ✅ Complete |
| `jarvis.js` | AI assistant layer | ✅ Complete |
| `auriga-memory.js` | RAG memory store | ✅ Complete |
| `sw.js` | Service worker (offline cache) | ✅ Complete |

### 5.3 Partially Built (In Progress)

| Class | Function | Status |
|---|---|---|
| `ButlerCommandRegistry.java` | 50+ voice command registry with categories + tips | 🔧 Scaffold created |

### 5.4 Not Yet Implemented (This Blueprint)

All items in Section 6 that are not listed above.

---

## 6. COMPLETE FEATURE SPECIFICATION — THE FULL BUILD

### 6.0 Accessibility & Onboarding Layer ⚠️ BUILD THIS FIRST

> **These fixes are prerequisites for all other modules.** A blind user who cannot get through onboarding will never reach any feature listed below. This section must be completed before Session 1 of the module build chain.

#### 6.0.1 Fix A — Voice-First Assistant Naming (VoiceSetupActivity)

**Problem:** First screen on a VI app requires typing. Keyboard is a barrier.

**Files to modify:** `VoiceSetupActivity.java`, `activity_voice_setup.xml`

**Specification:**
- On `onCreate`: check `AccessibilityManager.isTouchExplorationEnabled()`. If TalkBack is active, do NOT compete with it via TTS. Use `view.announceForAccessibility()` instead.
- Show a large, full-width **"TAP TO SPEAK YOUR NAME"** button above the EditText. `contentDescription` = "Speak your assistant name. Double tap to start listening."
- Tapping it starts `SpeechRecognizer` in single-utterance mode. On result: populate EditText and auto-call `confirm()` if the recognised name passes validation (1–24 letters/spaces).
- If SpeechRecognizer returns empty or error: focus EditText and speak "I didn't catch that. You can type a name instead."
- Skip button speaks "Skipping. Your assistant will be called Auriga." then exits.
- EditText kept as fallback — always visible below the mic button.
- Remove the `windowSoftInputMode="adjustResize"` from the manifest entry — keyboard should not auto-open on this screen.
- TTS race condition fix: delay TTS welcome message by 1200ms after `onCreate` to let TalkBack finish its initial traversal.

#### 6.0.2 Fix B — Boot Announcement (LocatorActivity)

**Problem:** App opens silently. User with no sight has no idea what just happened.

**File to modify:** `LocatorActivity.java` → `initTts()`

**Specification:**
- After TTS initialises successfully, check SharedPreferences key `auriga_boot_welcomed` (boolean, default false).
- If false: after 1500ms delay, speak the following **one time ever**:
  > *"Welcome to Auriga. I am your spatial navigation assistant. Point your camera forward and I will tell you what is ahead, how far it is, and which direction to move. Long press anywhere on the screen, or say [AssistantName] Auriga, to give me a command. Say help for a list of everything I can do."*
- Set `auriga_boot_welcomed = true`.
- On all subsequent launches: speak a shorter boot confirmation (3 seconds after start):
  > *"Auriga ready. [N] targets active."* (or "all objects" if no filter set)
- This announcement must NOT overlap with detection speech. Add a 3-second grace period before the detection TTS loop begins on first boot.

#### 6.0.3 Fix C — TalkBack-Safe Drawer Navigation (activity_locator.xml)

**Problem:** Floating hamburger pill is sometimes skipped by TalkBack's focus traversal.

**File to modify:** `activity_locator.xml`, `LocatorActivity.java`

**Specification in XML:**
- Add to the menu pill Button:
  ```xml
  android:importantForAccessibility="yes"
  android:contentDescription="Open menu. Double tap to access all features."
  android:accessibilityTraversalBefore="@id/locator_preview"
  ```
- Add to DrawerLayout root: `android:focusableInTouchMode="true"`

**Specification in Java:**
- In `LocatorActivity.onCreate()`, after the camera starts: register a long-press `GestureDetector` on the full `locator_frame` FrameLayout. Long press fires `drawerLayout.openDrawer(Gravity.START)`.
- When drawer opens, call `drawerLayout.announceForAccessibility("Menu open. Swipe to navigate options. Double tap to select.")`.
- When drawer closes, return focus to the camera frame: `locatorFrame.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)`.
- Add `contentDescription` to every interactive row in the drawer XML (each `LinearLayout` with `onClick`).

#### 6.0.4 SmartCalibrationEngine — Auto-Calibration from Device Library

**Problem:** Calibration walk requires visual pose matching. Blind users cannot see the diagram.

**New file:** `SmartCalibrationEngine.java`

**Specification — Part 1: Auto-detection from library**
- On `onCreate` of `CalibrationWalkActivity`, call `SmartCalibrationEngine.tryAutoCalibrate(context)`.
- `tryAutoCalibrate()`: read `Build.MANUFACTURER + " " + Build.MODEL` (e.g. `"Samsung Galaxy S24 Ultra"`). Also read `Build.PRODUCT` as codename fallback.
- Load `calibration_library.json` from assets (it is already there via the `copyWebDeployToAssets` Gradle task).
- Parse JSON and match against `model` field (case-insensitive contains match), then `codename` field as fallback.
- If match found:
  - Extract `camera.fov_horizontal_deg`, `camera.fov_vertical_deg`, `camera.offset_from_screen_center_mm`.
  - Store to SharedPreferences under keys: `calib_fov_h`, `calib_fov_v`, `calib_offset_x`, `calib_offset_y`, `calib_source` = `"library"`, `calib_model` = matched model name.
  - Set `calibration_walk_completed = true` (so feedback is unblocked).
  - Speak: *"Your device [model name] was found in the calibration library. Calibration applied automatically. You don't need to do the walk."*
  - Return `true`. `CalibrationWalkActivity` exits immediately.
- If no match found: return `false`. Proceed to Part 2.

**Specification — Part 2: Geometry-based alignment walk (replaces visual poses)**
- When no library match, launch the walk in **audio-guided mode** instead of visual-diagram mode.
- Each step uses `SensorManager` TYPE_ROTATION_VECTOR to get real-time pitch, roll, and azimuth.
- For each target pose (e.g. "hold phone flat, face up" = pitch 0°, roll 0°):
  - Speak the instruction: *"Hold the phone flat, face up, in front of you."*
  - Continuously read sensor. Compute deviation: `sqrt((targetPitch - currentPitch)² + (targetRoll - currentRoll)²)`.
  - When deviation < 5°: play `ToneGenerator` `TONE_PROP_ACK` beep (short, 880Hz) → *"Good. Hold still."* → wait 1 second for stable reading → record sensor values.
  - If deviation 5°–15°: speak directional hint every 1.5 seconds: *"Tilt left 8 degrees"*, *"Tilt forward 12 degrees"* etc.
  - If deviation > 15°: speak heading only: *"Tilt left"*, *"Tilt back"*.
- The 10 calibration poses translated to audio instructions:

| Step | Old visual | Audio instruction | Target pitch/roll |
|---|---|---|---|
| 1 | Phone flat, face up | "Hold phone flat, screen facing up" | pitch 0°, roll 0° |
| 2 | Portrait, upright | "Hold phone upright in front of you, screen facing you" | pitch 90°, roll 0° |
| 3 | Tilt forward 30° | "Tilt the top of the phone 30 degrees away from you" | pitch 60°, roll 0° |
| 4 | Landscape left | "Rotate phone sideways to the left" | pitch 90°, roll -90° |
| 5 | Landscape right | "Rotate phone sideways to the right" | pitch 90°, roll 90° |
| 6 | Upright, step forward | "Hold upright and take one step forward" | pitch 90°, roll 0° (motion) |
| 7 | Upright, bright light | "Point phone at bright light source overhead" | pitch 45°, roll 0° |
| 8 | Upright, low light | "Move to a darker area or cover camera briefly" | (lux reading) |
| 9 | Upright, near surface | "Hold phone 30cm from any flat surface" | pitch 90°, dist est |
| 10 | Full upright, still | "Stand still, hold phone upright for 5 seconds" | pitch 90°, roll 0° (stable) |

- On completion: compute FOV from recorded stable-pose sensor data using the existing `TriangulationEngine` geometry. Store to SharedPreferences. Set `calib_source = "geometry_walk"`.

---

### 6.1 Core Navigation Layer (Extend Existing)

#### 6.1.1 TruePath™ v2 — Enhanced Ground Plane Navigation
**Status:** Extend existing `TriangulationEngine`
- Add multi-object simultaneous tracking (currently single primary target)
- Add path corridor width estimation (walkable gap detection)
- Add ground surface change detection (grass → concrete → gravel)
- Add slope estimation (uphill/downhill angle)
- Performance target: <80ms per frame on Snapdragon 665+

#### 6.1.2 StairSense™ — Stair & Edge Detection
**New module:** `StairSenseEngine.java`
- Detect step-edge breaks in ground plane using row-differential analysis
- Count steps via frame-over-frame floor discontinuity
- Classify direction: ascending / descending
- Output: spoken warning "3 steps down ahead, 0.8 metres" + fast haptic pulse
- Works with existing CameraX feed, no additional hardware

#### 6.1.3 TrafficSense™ — Approaching Vehicle & Object Warning
**New module:** `TrafficSenseEngine.java`
- Optical flow magnitude in upper frame zones detects large fast-moving objects
- Doppler-proxy: object scale growth rate over 5 frames = approach velocity
- Threshold: warn when estimated TTC (time to collision) < 4 seconds
- Output: urgent haptic escalation + spoken "vehicle approaching from left"
- Sensitivity tunable: road crossing mode (high) vs indoor mode (off)

#### 6.1.4 SpatialMemory™ — Topological Environment Memory
**New module:** `SpatialMemoryEngine.java`
**Storage:** SQLite (offline, on-device)
- Record a route: store landmark sequence (door, corner, junction, sign text) with step counts
- Replay a route: compare live landmark detections to stored sequence, give turn-by-turn audio
- Named locations: "home", "office", "clinic" saved by user voice command
- Memory capacity: ~500 routes, ~10,000 landmarks per device
- No GPS required — works indoors, underground, rural

---

### 6.2 Identification Layer (All New Modules)

#### 6.2.1 PillGuard™ — Medicine Identification
**New module:** `PillGuardEngine.java`
**Model:** MobileNetV3-Small fine-tuned on NIH Pillbox dataset (bundled, ~12MB)
**Supplementary:** Offline pill database SQLite (~40MB, NDC codes + descriptions)
- Capture pill via camera crop to reticle
- Classify by shape (round/oval/oblong/capsule) + color (HSV analysis) + imprint (OCR)
- Cross-reference classification with database
- Output: "White oval tablet, imprint L484, this is Paracetamol 500mg"
- Confidence gate: if confidence < 0.75, speak "I cannot identify this safely, please verify"
- **Safety note:** Always append "verify with your pharmacist before taking"

#### 6.2.2 FaceVault™ — Offline Face Recognition
**New module:** `FaceVaultEngine.java`
**Model:** MobileFaceNet TFLite (~5MB, 128-dim embedding)
**Storage:** Local encrypted SQLite (embeddings + name labels)
- Enrolment: user says "Auriga, learn this person, name is [name]" → captures 5 frames for robust embedding
- Recognition: match live face embedding to vault, cosine similarity threshold 0.75
- Output: "John is 2 metres ahead" spoken quietly
- Privacy: face embeddings are cryptographic vectors — not reconstructable to images
- Vault capacity: ~200 enrolled people (negligible storage per embedding)

#### 6.2.3 CashLens™ — Offline Currency Detection
**New module:** `CashLensEngine.java`
**Model:** MobileNetV2 classifier per supported currency (each ~8MB)
**Initial currencies:** USD, GBP, EUR, KES (Kenyan Shilling — relevant to developer)
**Architecture:** Per-currency model swap; community can contribute new currency models
- Capture note under consistent lighting (guide overlay on screen)
- Output: "This is a twenty pound note" or "This looks like a ten, but I'm not certain"
- Currency model download: one-time, offline thereafter

#### 6.2.4 LabelReader™ — Product & Expiry Date Reading
**New module:** `LabelReaderEngine.java` (extends existing `ReaderActivity`)
- Preprocessor tuned for curved surfaces: adaptive threshold + perspective unwarp
- Expiry date parser: regex engine for common formats (DD/MM/YY, MM/YYYY, BEST BEFORE, USE BY)
- Barcode/QR decoder: ZXing library (offline) → Open Food Facts offline database (~500MB optional, lazy-downloaded)
- Output priority: product name → expiry → ingredients (on request)

#### 6.2.5 ColorSense™ — Color Identification
**New module:** `ColorSenseEngine.java`
- HSV centroid analysis of a configurable reticle area
- Named color output using the standard 140-color web palette expanded with fashion/textile colors (200 named colors)
- Special detection: traffic light red/amber/green (band detection in upper zone)
- Output: "The label is red" / "Traffic light is green"

#### 6.2.6 SceneDescriber™ — Full Scene Narration
**New module:** `SceneDescriberEngine.java`
**Model:** MobileVLM or MoondreamV2 quantized (INT4, ~1.5GB, optional download)
**Fallback:** Rule-based scene description from YOLO detections + positions
- On-demand: user says "Auriga, describe what you see"
- Output: "You are facing a corridor. There is a door approximately 3 metres ahead slightly to the right. A person is standing near the door."
- When VLM model not installed: "I see a person at 2.8 metres center, a chair at 1.2 metres left, a door at 4 metres right"

---

### 6.3 Safety Layer (All New Modules)

#### 6.3.1 EmergencySOS™
**New module:** `EmergencySOSEngine.java`
- Trigger: voice ("Auriga emergency") or 3× rapid power button press (AccessibilityService)
- Actions (in order):
  1. Speak current environment description aloud (location landmarks from SpatialMemory)
  2. Dial pre-configured emergency contact (no internet required)
  3. Send SMS with last known GPS coordinates + "I need help" (if SMS available)
  4. If no network: continuous loud tone + haptic SOS pattern (··· — — — ···)
- Configuration: contact stored in SharedPreferences, set up during onboarding

#### 6.3.2 PassiveHazardListener™
**New module:** `PassiveHazardEngine.java`
**Model:** YAMNet TFLite (audio classifier, ~4MB)
- Always-on audio classification in background service (low CPU mode)
- Detects: smoke alarm, CO alarm, smoke detector, car horn, aggressive dog bark, glass break, gunshot
- Threshold: 2 consecutive frames at >0.85 confidence before alert
- Output: urgent haptic + spoken "Warning: smoke alarm detected"
- Battery cost: ~2% per hour additional drain (validated on Pixel 5)

#### 6.3.3 CrossingGuard™ — Pedestrian Crossing Assistant
**New module:** `CrossingGuardEngine.java`
- Combines TrafficSense™ (vehicle detection) + ColorSense™ (traffic light state)
- Mode: activated by voice "Auriga, crossing mode"
- Continuous monitoring: "Light is red, vehicles still moving" → "Light is green, no vehicles detected, safe to cross" → "Crossing, 8 metres to far kerb, keep bearing center"
- Falls back to audio-only if light state cannot be determined: "I cannot confirm the light state, use your judgement"

---

### 6.4 Connectivity & Wearable Layer

#### 6.4.1 HapticBelt™ / HapticCane™ — External Haptic Node
**Protocol:** BLE GATT or USB Serial (Arduino/ESP32)
**Arduino firmware:** `auriga_haptic_node/auriga_haptic_node.ino`
- Receives haptic commands from phone over BLE: `{zone: LEFT|CENTER|RIGHT, intensity: 0-255, pattern: PULSE|CONTINUOUS|SOS}`
- Drives vibration motors at corresponding wrist/belt positions
- Directional feedback: left motor for left obstacle, right motor for right, center for forward
- Assembly: ESP32 + 3× ERM vibration motors + 500mAh LiPo — BOM ~$18

#### 6.4.2 BrailleLink™ — Braille Display Serial Output
**New module:** `BrailleLinkEngine.java`
**Protocol:** USB Serial (standard, no drivers needed on Android with USB OTG)
- Output: Grade 1 Braille ASCII via serial to any USB Braille display (Orbit Reader, BrailleNote)
- Translate spoken output to Braille cells in parallel with TTS
- Can suppress TTS when Braille display connected (silent mode for public use)

#### 6.4.3 CameraLink™ — Wearable Camera Input
**Existing:** `CameraConnectActivity` — extend for headless mode
- Glasses camera via USB OTG (UVC class, no driver needed)
- Raspberry Pi camera via local WiFi MJPEG (already partially implemented)
- Action camera (GoPro) via USB OTG: same UVC path
- Allows phone to be pocketed while camera is mounted on glasses/chest rig

---

### 6.5 AI Companion Layer

#### 6.5.1 AurigaMind™ — Persistent AI Companion
**Existing foundation:** `AurigaMemoryStore`, `AurigaKnowledge`, `jarvis.js`
- Extend with long-term episodic memory: "You last went to the pharmacy on Tuesday at 3pm"
- Learn user preferences: speech rate, preferred alert style, favourite routes
- Proactive alerts: "It's 8pm, your evening medication reminder"
- Natural language environment queries: "Where did I put my keys?" → cross-reference SpatialMemory™ for last known location of detected objects

#### 6.5.2 OfflineLLM™ — On-Device Language Model
**Model:** Gemma 2B INT4 (Android NNAPI) or Phi-2 quantized (~1.5GB)
**Fallback:** Existing `AurigaKnowledge` rule-based Q&A
- Powers SceneDescriber, conversational responses, complex command parsing
- Loaded on-demand, unloaded when not in use (memory management)
- Requires: ≥3GB RAM device, Android 12+; graceful degradation on lower-end hardware

---

#### 6.5.3 AurigaButler™ — System-Wide Voice Assistant

> **The vision:** Auriga is not just a navigation tool. It is a full-time AI butler that handles everything a blind user needs across the entire phone — like Alexa or Siri but fully offline, with no cloud, no account, and no subscription. The user should feel they never need to touch the screen again.

**New files:** `AurigaButlerService.java`, `AurigaAccessibilityService.java`, `ButlerCommandRegistry.java` (scaffold exists)

**Design principle:** The Butler is a foreground service that is always listening. When the wake word fires (via existing `AurigaVoiceService`), Butler takes command, executes the action, and speaks the result. It can operate in any app — not just Auriga — because it uses Android's AccessibilityService API to read and interact with any screen.

##### AurigaButlerService.java — Command Execution Engine

**Service type:** Foreground, always-on  
**Notification:** "Auriga Butler active — say [Name] Auriga for help"  
**SpeechRecognizer:** Inherits wake events from `AurigaVoiceService` via `LocalBroadcastManager`. Does NOT run its own always-on mic — piggybacks on the existing wake service to avoid double mic use.

**Command categories (50+ commands, see `ButlerCommandRegistry.java`):**

| Category | Example commands |
|---|---|
| **Auriga Navigation** | "navigate", "what's ahead", "start camera", "crossing mode", "stair mode" |
| **Identification** | "read label", "identify pill", "who is this", "what money is this", "describe scene" |
| **Safety** | "emergency", "SOS", "help me", "call for help" |
| **System** | "go home", "go back", "recent apps", "open [app name]", "lock screen", "torch" |
| **Volume / Media** | "volume up", "pause music", "next song", "mute" |
| **Time / Info** | "what time is it", "what's the date", "battery level", "signal strength" |
| **Communication** | "call [name]", "send message to [name]", "read my messages", "answer", "reject" |
| **Help / Tutorial** | "help", "what can you do", "tutorial", "tip", "list commands" |
| **Feature Discovery** | "what else can you do", "surprise me" → random unused feature tip |

**Command dispatch flow:**
1. Receive spoken text from wake event broadcast
2. Pass to `ButlerCommandRegistry.match(spoken)` → get best `ButlerCommand`
3. Play earcon C7 (command accepted chime) via `ToneGenerator`
4. Execute `ActionCode` (see below)
5. Speak result via `OutputLayer`
6. If no match: play earcon C8 (low buzz) + speak "I didn't understand. Say help for commands."

**ActionCode execution map:**

```
SYSTEM_GO_HOME      → AccessibilityService.performGlobalAction(GLOBAL_ACTION_HOME)
SYSTEM_GO_BACK      → AccessibilityService.performGlobalAction(GLOBAL_ACTION_BACK)
SYSTEM_RECENT_APPS  → AccessibilityService.performGlobalAction(GLOBAL_ACTION_RECENTS)
SYSTEM_OPEN_APP     → PackageManager.getLaunchIntentForPackage(resolveAppName(arg))
SYSTEM_TORCH        → CameraManager.setTorchMode(toggle)
SYSTEM_VOLUME_UP    → AudioManager.adjustVolume(ADJUST_RAISE, FLAG_SHOW_UI)
SYSTEM_MUTE         → AudioManager.setStreamMute(STREAM_MUSIC, toggle)
SYSTEM_LOCK_SCREEN  → DevicePolicyManager.lockNow() [requires admin] or PowerManager.goToSleep()
INFO_TIME           → new SimpleDateFormat("h:mm a").format(new Date())
INFO_DATE           → new SimpleDateFormat("EEEE, MMMM d, yyyy").format(new Date())
INFO_BATTERY_PERCENT → BatteryManager.getIntProperty(BATTERY_PROPERTY_CAPACITY) + "%"
INFO_SIGNAL         → ConnectivityManager / TelephonyManager signal strength
COMM_CALL           → Intent(Intent.ACTION_CALL, Uri.parse("tel:" + resolveContact(arg)))
COMM_SEND_SMS       → Intent(Intent.ACTION_SENDTO, "smsto:" + resolveContact(arg)) + putExtra SMS_BODY + dictated text
COMM_ANSWER_CALL    → AccessibilityService KEYCODE_HEADSETHOOK or KeyEvent.KEYCODE_CALL
MEDIA_PLAY          → AudioManager sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON, KEYCODE_MEDIA_PLAY))
MEDIA_NEXT          → same with KEYCODE_MEDIA_NEXT
AURIGA_NAVIGATE     → startActivity(Intent(context, LocatorActivity))
AURIGA_SOS          → bind to EmergencySOSEngine.trigger(currentEnvDescription)
HELP_LIST_COMMANDS  → speak ButlerCommandRegistry.buildHelpText()
HELP_TUTORIAL       → startActivity(Intent(context, TutorialActivity))
HELP_FEATURE_TIPS   → speak ButlerCommandRegistry.randomFeatureTip()
```

**App name resolution (`resolveAppName(String arg)`):**
- Query `PackageManager.getInstalledApplications()`
- Filter by `ApplicationInfo.loadLabel()` containing the spoken word (case-insensitive)
- If multiple matches: speak "I found [N] apps matching [name]. Did you mean [first]? Say yes or no."
- Cache results in a `HashMap<String, String>` for 60 seconds

**Contact resolution (`resolveContact(String arg)`):**
- Query `ContactsContract.Contacts` with `DISPLAY_NAME LIKE '%arg%'`
- If multiple matches: speak top 3 options with "Say one, two, or three."
- Requires READ_CONTACTS permission (requested at runtime)

**Proactive feature tip engine:**
- On every app launch (after boot announcement): check SharedPreferences for `butler_tip_last_shown_at` (timestamp).
- If >24 hours since last tip: wait 10 seconds then speak one `randomFeatureTip()`.
- Track which tips have been shown; cycle through all before repeating.
- User can say "stop tips" → set `butler_tips_enabled = false`.
- User can say "tip" at any time → immediate random tip.

##### AurigaAccessibilityService.java — Cross-App Control

**Type:** `AccessibilityService` (requires user to enable in Settings → Accessibility → Auriga)  
**New file:** `res/xml/accessibility_service_config.xml`

**Manifest entry:**
```xml
<service
    android:name=".AurigaAccessibilityService"
    android:exported="true"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config" />
</service>
```

**`accessibility_service_config.xml`:**
```xml
<accessibility-service
    android:accessibilityEventTypes="typeWindowStateChanged|typeViewClicked|typeViewFocused"
    android:accessibilityFeedbackType="feedbackSpoken"
    android:accessibilityFlags="flagDefault|flagRetrieveInteractiveWindows"
    android:canRetrieveWindowContent="true"
    android:canPerformGestures="true"
    android:description="@string/accessibility_service_description"
    android:notificationTimeout="100" />
```

**Capabilities:**
- `performGlobalAction(GLOBAL_ACTION_BACK / HOME / RECENTS / NOTIFICATIONS)` — system-level navigation
- `getRootInActiveWindow()` → traverse AccessibilityNodeInfo tree to find nodes by text/description
- `findNodeByText(String text)` → `node.performAction(ACTION_CLICK)` — tap any visible button by label
- `findNodeByViewId(String resourceId)` → same
- `readScreenContent()` → collect all visible text from the window tree → return as joined string for Butler to speak
- Called by `AurigaButlerService` via a bound service connection or `LocalBroadcastManager`

**Setup guidance (spoken to user on first launch):**
> *"For me to control other apps, you need to enable Auriga in your phone's Accessibility settings. I'll open the settings now. Find Auriga in the list and turn it on. Say done when finished."*

**Graceful degradation:** Every cross-app action that requires the AccessibilityService is wrapped in a null-check. If the service is not enabled, Butler speaks: *"I can't do that yet. Please enable Auriga in your Accessibility settings. Say 'open accessibility settings' and I will take you there."*

##### AurigaTutorialEngine™ — Voice-Guided Interactive Tutorial

**New file:** `AurigaTutorialEngine.java` + `TutorialActivity.java`

**Goal:** A blind user who opens the tutorial can learn every Auriga feature using only their voice, with zero screen interaction required.

**Design:**
- Chapters are a sequential list. Each chapter has: title, 3–5 spoken steps, and a list of voice phrases the user says to continue.
- `TutorialActivity` is a full-screen dark background with a single centred text label (for low-vision users) showing the current step. The content is entirely driven by TTS.
- Navigation: say "next" → advance, "repeat" → hear again, "skip chapter" → jump to next chapter, "stop tutorial" → exit and speak "You can restart the tutorial any time by saying tutorial."
- Progress is saved per chapter to SharedPrefs key `tutorial_chapter_[name]_done`. Completed chapters are skipped on re-launch.

**Chapters:**

| # | Chapter | What it covers |
|---|---|---|
| 1 | Welcome to Auriga | What the app is, offline promise, wake word |
| 2 | Navigation Basics | Point camera forward, what the announcements mean, distance and bearing |
| 3 | Voice Commands | Wake word, long press, command categories, "say help" |
| 4 | Object Targets | How to narrow detection to specific objects |
| 5 | DrakoVoice Reader | Point at text, auto-read mode, paragraph navigation |
| 6 | Face Vault | Enrolling a person, identifying them |
| 7 | Pill Guard | Identifying medicine, safety caution |
| 8 | Cash Lens | Identifying banknotes, switching currency |
| 9 | Emergency SOS | Setting emergency contact, triggering SOS |
| 10 | AurigaButler | All cross-phone commands: calling, messaging, opening apps |
| 11 | Crossing Mode | When and how to use it, what the announcements mean |
| 12 | Spatial Memory | Recording a route, replaying it |
| 13 | Tips & Tricks | Earcon meanings, haptic patterns, battery tips |

**Tutorial discovery:**
- After first boot welcome message: *"Would you like a guided voice tutorial? Say 'yes' to start, or 'skip' to go straight to navigation."*
- Butler's `HELP_TUTORIAL` command launches it at any time.
- Drawer row "Voice Tutorial" launches it from the menu.

---

### 6.6 Platform Extension Layer

#### 6.6.1 AurigaPC™ — Linux/Windows Desktop Application
**Stack:** Python 3.11 + OpenCV + PyTorch Mobile + PyQt6 (or Electron for Windows)
**Entry point:** `platforms/pc/auriga_pc.py`
- All same feature modules as Android, implemented in Python
- TTS: espeak-ng (offline, cross-platform)
- Input: webcam, USB camera, screen capture
- Use case: screen reader enhancement, desktop navigation assistant
- Packaging: PyInstaller → standalone `.exe` (Windows) / `.AppImage` (Linux)

#### 6.6.2 AurigaPi™ — Raspberry Pi Spatial Computer
**Target hardware:** Raspberry Pi 4B (4GB RAM) or Pi Zero 2W
**OS:** Raspberry Pi OS Lite (headless)
**Entry point:** `platforms/pi/auriga_pi.py`
- Camera: Pi Camera Module 3 (NoIR variant recommended for low-light)
- Audio output: 3.5mm to earpiece or USB speaker
- Haptics: GPIO to ERM motor driver board
- Power: USB-C power bank (8–12 hours runtime on Pi Zero 2W)
- Form factor: 3D-printed glasses mount or chest harness
- Boot-to-run: systemd service starts Auriga on power-on, no display needed

#### 6.6.3 AurigaMCU™ — Microcontroller Satellite Node
**Targets:** ESP32-S3 (with camera), Arduino Nano 33 BLE
**Role:** Haptic and audio satellite, not full vision processing
**ESP32-S3 with OV2640 camera:**
- Runs lightweight edge detection only (no full YOLO)
- Streams compressed zone data to phone/Pi via BLE
- Use case: wrist-worn supplement providing close-range (<30cm) obstacle micro-alerts

**Arduino Nano BLE:**
- Pure haptic output node (receives BLE commands from phone/Pi)
- Drives motor array (belt, wristband, cane grip)

---

## 7. SYSTEM ARCHITECTURE

### 7.1 Android Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        AurigaNavi APK                           │
├─────────────────────────────────────────────────────────────────┤
│  PRESENTATION LAYER                                             │
│  LocatorActivity │ ReaderActivity │ TargetsActivity             │
│  FaceVaultActivity │ PillGuardActivity │ RouteActivity          │
│  CashLensActivity │ EmergencyActivity │ SettingsActivity        │
├─────────────────────────────────────────────────────────────────┤
│  COMMAND LAYER                                                  │
│  AurigaVoiceEngine ──→ CommandRouter ──→ AurigaSkillEngine      │
│  SerpentineGestureDetector │ AurigaAlarmReceiver                │
├─────────────────────────────────────────────────────────────────┤
│  PERCEPTION LAYER                                               │
│  ImageProcessor ──→ TriangulationEngine ──→ ZoneAnalyser        │
│  YoloDetector │ StairSenseEngine │ TrafficSenseEngine           │
│  FaceVaultEngine │ PillGuardEngine │ CashLensEngine             │
│  LabelReaderEngine │ ColorSenseEngine │ SceneDescriberEngine    │
├─────────────────────────────────────────────────────────────────┤
│  REASONING LAYER                                                │
│  GodsEyeOrchestrator │ SpatialMemoryEngine │ CrossingGuardEngine│
│  AurigaMind │ AurigaKnowledge │ AurigaMemoryStore               │
├─────────────────────────────────────────────────────────────────┤
│  OUTPUT LAYER                                                   │
│  DrakoVoice │ HapticManager │ SonarManager                     │
│  BrailleLinkEngine │ HapticBeltEngine │ EmergencySOSEngine      │
├─────────────────────────────────────────────────────────────────┤
│  HARDWARE ABSTRACTION LAYER                                     │
│  HardwareHAL │ CameraService │ CameraLink │ OdometryManager     │
│  PassiveHazardEngine │ CalibrationManager │ FiducialLUT         │
├─────────────────────────────────────────────────────────────────┤
│  PLATFORM SERVICES                                              │
│  AurigaVoiceService (foreground) │ AurigaApplication           │
│  LicenseManager │ AurigaConfig │ CrashReportActivity            │
└─────────────────────────────────────────────────────────────────┘
```

### 7.2 Cross-Platform Architecture

```
                    ┌─────────────────────┐
                    │   AURIGA CORE LOGIC  │
                    │  (language-agnostic  │
                    │   interface contract)│
                    └──────────┬──────────┘
              ┌────────────────┼────────────────┐
              ▼                ▼                ▼
    ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
    │ Android      │  │  Python PC   │  │ Python Pi    │
    │ Java/TFLite  │  │ OpenCV/Torch │  │ OpenCV/Torch │
    │ CameraX      │  │ PyQt6/espeak │  │ headless/GPIO│
    └──────┬───────┘  └──────┬───────┘  └──────┬───────┘
           │                 │                  │
           └─────────────────┼──────────────────┘
                             ▼
                  ┌──────────────────────┐
                  │ BLE / USB Serial     │
                  │ Satellite Nodes      │
                  │ ESP32 │ Arduino      │
                  │ HapticBelt │ Braille │
                  └──────────────────────┘
```

### 7.3 Data Flow — Per Frame (Android, Target <100ms)

```
CameraX frame (NV21, 640×480)
        │ <5ms
        ▼
HardwareHAL (normalize, extract IMU pitch)
        │ <2ms
        ▼
ImageProcessor (resize, stabilise via OdometryManager)
        │ <5ms
        ▼
┌───────────────────────────────────────────┐
│          PARALLEL INFERENCE (async)        │
│  YoloDetector    │ ZoneAnalyser            │
│  StairSense      │ TrafficSense            │
│  FaceVault (5fps)│ ColorSense              │
└───────────────┬───────────────────────────┘
                │ <50ms (GPU/NNAPI)
                ▼
GodsEyeOrchestrator (merge detections, resolve conflicts)
                │ <5ms
                ▼
TriangulationEngine (distance + bearing + height per detection)
                │ <5ms
                ▼
SpatialMemoryEngine (match landmarks, update route state)
                │ <3ms
                ▼
OutputPriorityQueue (rank alerts, throttle cadence)
                │ <2ms
                ▼
DrakoVoice │ HapticManager │ BrailleLink
```

---

## 8. MODULE INTERFACE CONTRACTS

These are the canonical Java interfaces. All implementations must honour exactly these signatures. No deviations.

```java
// ── FrameProvider ────────────────────────────────────────────────────────
public interface IFrameProvider {
    /** Start delivering NV21 frames to cb. Thread-safe. */
    void start(FrameCallback cb);
    /** Release camera and stop delivery. */
    void stop();
    /** @return true if this provider is currently active. */
    boolean isActive();

    interface FrameCallback {
        void onFrame(byte[] nv21, int width, int height, long timestampNs);
    }
}

// ── ZoneAnalyser ─────────────────────────────────────────────────────────
public interface IZoneAnalyser {
    /**
     * Analyse one NV21 frame. Returns a ZoneMap within 20ms.
     * Must be callable from any background thread.
     */
    ZoneMap analyse(byte[] nv21, int width, int height);

    enum Zone { LEFT, CENTER, RIGHT, BLOCKED }

    class ZoneMap {
        public final Zone safeZone;           // recommended direction
        public final float[] edgeDensity;     // [left, center, right] 0–1
        public final float[] clearanceM;      // estimated clearance metres per zone
        public final boolean stairEdgeDetected;
        public ZoneMap(Zone safe, float[] density, float[] clearance, boolean stair) {
            this.safeZone = safe; this.edgeDensity = density;
            this.clearanceM = clearance; this.stairEdgeDetected = stair;
        }
    }
}

// ── DepthProxy ───────────────────────────────────────────────────────────
public interface IDepthProxy {
    /**
     * Submit a new frame. Returns optical-flow-based approach velocity
     * per zone: float[3] {left, center, right}, 0=static, 1=fast approach.
     * First call always returns {0,0,0}.
     */
    float[] update(byte[] nv21, int width, int height);
}

// ── StairSenseEngine ─────────────────────────────────────────────────────
public interface IStairSenseEngine {
    StairResult detect(byte[] nv21, int width, int height);

    class StairResult {
        public final boolean stairsDetected;
        public final int stepCount;           // 0 if unknown
        public final StairDirection direction; // UP, DOWN, UNKNOWN
        public final float distanceM;         // distance to first step edge
        public StairResult(boolean det, int cnt, StairDirection dir, float dist) {
            this.stairsDetected=det; this.stepCount=cnt;
            this.direction=dir; this.distanceM=dist;
        }
    }

    enum StairDirection { UP, DOWN, UNKNOWN }
}

// ── TrafficSenseEngine ───────────────────────────────────────────────────
public interface ITrafficSenseEngine {
    TrafficResult assess(byte[] nv21, int width, int height);

    class TrafficResult {
        public final boolean vehicleApproaching;
        public final IZoneAnalyser.Zone approachZone;
        public final float ttcSeconds;         // estimated time to collision
        public final TrafficLightState lightState;
        public TrafficResult(boolean app, IZoneAnalyser.Zone zone,
                             float ttc, TrafficLightState light) {
            this.vehicleApproaching=app; this.approachZone=zone;
            this.ttcSeconds=ttc; this.lightState=light;
        }
    }

    enum TrafficLightState { RED, AMBER, GREEN, UNKNOWN }
}

// ── FaceVaultEngine ──────────────────────────────────────────────────────
public interface IFaceVaultEngine {
    /** Enrol a person. Provide 3–5 NV21 frames for robust embedding. */
    boolean enrol(String name, List<byte[]> frames, int width, int height);
    /** Identify faces in a frame. Returns list of matches. */
    List<FaceMatch> identify(byte[] nv21, int width, int height);
    /** Remove a person from the vault. */
    boolean forget(String name);

    class FaceMatch {
        public final String name;
        public final float similarity;    // 0–1
        public final float bearingDeg;    // horizontal position
        public final float distanceM;     // estimated (face-size heuristic)
        public FaceMatch(String n, float sim, float bear, float dist) {
            this.name=n; this.similarity=sim;
            this.bearingDeg=bear; this.distanceM=dist;
        }
    }
}

// ── PillGuardEngine ──────────────────────────────────────────────────────
public interface IPillGuardEngine {
    /** Identify a pill from a cropped NV21 frame centred on reticle. */
    PillResult identify(byte[] nv21, int width, int height);

    class PillResult {
        public final String commonName;       // "Paracetamol 500mg" or null
        public final String imprint;          // OCR'd imprint or null
        public final float confidence;        // 0–1
        public final boolean safeToReport;    // false if confidence < 0.75
        public final String cautionMessage;   // always non-null
        public PillResult(String name, String imprint,
                          float conf, boolean safe, String caution) {
            this.commonName=name; this.imprint=imprint;
            this.confidence=conf; this.safeToReport=safe;
            this.cautionMessage=caution;
        }
    }
}

// ── CashLensEngine ───────────────────────────────────────────────────────
public interface ICashLensEngine {
    /** Identify currency denomination from NV21 frame. */
    CashResult identify(byte[] nv21, int width, int height);
    /** Set active currency (model swap). */
    void setCurrency(String isoCode);   // "USD", "GBP", "EUR", "KES"

    class CashResult {
        public final String denomination; // "Twenty pounds" or null
        public final String isoCode;
        public final float confidence;
        public CashResult(String denom, String iso, float conf) {
            this.denomination=denom; this.isoCode=iso; this.confidence=conf;
        }
    }
}

// ── SpatialMemoryEngine ──────────────────────────────────────────────────
public interface ISpatialMemoryEngine {
    /** Start recording a route under a name. */
    void startRecording(String routeName);
    /** Add the current scene landmark to the active recording. */
    void addLandmark(String description, int stepsSinceLastLandmark);
    /** Stop and save the current recording. */
    void stopRecording();
    /** Begin replaying a named route. Calls cb on each guidance step. */
    void startReplay(String routeName, ReplayCallback cb);
    /** Match current scene against stored landmarks. Returns best match. */
    LandmarkMatch matchCurrentScene(String sceneDescription);

    interface ReplayCallback {
        void onGuidanceStep(String instruction);
        void onRouteComplete();
        void onRouteLost();
    }

    class LandmarkMatch {
        public final String routeName;
        public final String nextInstruction;
        public final float matchConfidence;
        public LandmarkMatch(String route, String instr, float conf) {
            this.routeName=route; this.nextInstruction=instr;
            this.matchConfidence=conf;
        }
    }
}

// ── OutputLayer ──────────────────────────────────────────────────────────
public interface IOutputLayer {
    /** Speak text. Uses priority to interrupt lower-priority speech. */
    void speak(String text, OutputPriority priority);
    /** Fire haptic pattern on device and any connected satellite nodes. */
    void haptic(HapticPattern pattern, HapticZone zone);
    /** Send text line to Braille display if connected. */
    void braille(String text);
    /** Mute/unmute all outputs. */
    void setMuted(boolean muted);
    /** Release resources. */
    void shutdown();

    enum OutputPriority { BACKGROUND, NORMAL, HIGH, EMERGENCY }
    enum HapticPattern { SLOW_PULSE, FAST_PULSE, SINGLE, SOS, STAIR_WARN }
    enum HapticZone { LEFT, CENTER, RIGHT, ALL }
}

// ── EmergencySOSEngine ───────────────────────────────────────────────────
public interface IEmergencySOSEngine {
    /** Trigger full emergency sequence. Non-blocking; runs on its own thread. */
    void trigger(String environmentDescription);
    /** Configure primary emergency contact. */
    void setContact(String phoneNumber, String name);
    /** @return true if SOS is currently active. */
    boolean isActive();
    /** Cancel an active SOS (e.g. false alarm). */
    void cancel();
}

// ── PassiveHazardEngine ──────────────────────────────────────────────────
public interface IPassiveHazardEngine {
    /** Start always-on audio monitoring. */
    void start(HazardCallback cb);
    /** Stop monitoring. */
    void stop();

    interface HazardCallback {
        void onHazardDetected(HazardType type, float confidence);
    }

    enum HazardType {
        SMOKE_ALARM, CO_ALARM, DOG_BARK_AGGRESSIVE,
        CAR_HORN, GLASS_BREAK, GUNSHOT, UNKNOWN
    }
}

// ── CommandRouter ────────────────────────────────────────────────────────
public interface ICommandRouter {
    /**
     * Dispatch a voice command string. Returns a spoken response string,
     * or null if the command was handled with no spoken reply needed.
     */
    String dispatch(String command);
    /** Register a new named skill handler at runtime. */
    void registerSkill(String triggerPhrase, SkillHandler handler);

    interface SkillHandler {
        String handle(String fullCommand);
    }
}
```

---

## 9. CROSS-PLATFORM DEPLOYMENT MATRIX

### 9.1 Android (Primary)

| Item | Spec |
|---|---|
| Min SDK | 24 (Android 7.0) |
| Target SDK | 34 (Android 14) |
| Min RAM | 2GB (4GB for OfflineLLM) |
| Camera | Any CameraX-compatible |
| ML backend | TFLite + Android NNAPI |
| TTS | Android system (offline voice required) |
| Build | Gradle 8.5, Java 17 |
| Output | `.apk` (debug, no Play Store required) |

### 9.2 Linux / PC (AurigaPC)

| Item | Spec |
|---|---|
| OS | Ubuntu 20.04+, Debian 11+, Windows 10+ (WSL2) |
| Runtime | Python 3.11 |
| Camera | OpenCV VideoCapture (webcam, USB) |
| ML | PyTorch Mobile or ONNX Runtime |
| TTS | espeak-ng (apt install espeak-ng) |
| Haptics | N/A (audio + optional USB serial) |
| Packaging | PyInstaller → AppImage / .exe |
| Dependencies | `opencv-python torch torchvision pyqt6 espeak-ng pyttsx3` |

### 9.3 Raspberry Pi (AurigaPi)

| Item | Spec |
|---|---|
| Hardware | Pi 4B 4GB (full) or Pi Zero 2W (lite) |
| OS | Raspberry Pi OS Lite (bookworm, 64-bit) |
| Camera | Pi Camera Module 3, or USB webcam |
| ML | TFLite runtime for ARM64 |
| TTS | espeak-ng via ALSA → USB speaker or 3.5mm |
| GPIO | Haptic motor drivers (GPIO 17, 18, 27) |
| Power | 10,000mAh USB-C bank (~14hr runtime on Zero 2W) |
| Boot | systemd service: `/etc/systemd/system/auriga.service` |
| Form factor | Pi Zero 2W + Pi Cam 3 + earpiece = glasses-mounted |

### 9.4 ESP32-S3 Satellite Node (AurigaMCU)

| Item | Spec |
|---|---|
| Board | ESP32-S3-DevKitC-1 (with OV2640 camera) |
| Framework | Arduino / ESP-IDF |
| Role | Local haptic + proximity micro-alerting |
| ML | Edge Impulse / custom Canny on-chip |
| Comms | BLE GATT (to phone) + USB Serial (to Pi) |
| Power | LiPo 500mAh → ~8hr haptic-only runtime |
| Firmware | `platforms/mcu/esp32_haptic_node/` |

### 9.5 Arduino Nano 33 BLE

| Item | Spec |
|---|---|
| Role | Pure BLE haptic receiver node |
| Outputs | 3× GPIO → ERM driver → motors (left, center, right) |
| Protocol | GATT characteristic write: `0xAURIGA01` custom service |
| Firmware | `platforms/mcu/arduino_haptic_belt/` |

---

## 10. OFFLINE-FIRST DESIGN PRINCIPLES

### 10.1 The Offline Contract

Every feature in Auriga must satisfy:

> **"This feature must work on a factory-reset device with no SIM card, no WiFi, no Google account, on an aeroplane, indefinitely."**

If a feature cannot pass this test, it is either redesigned or gated as clearly-optional with a degraded-mode fallback.

### 10.2 Model Bundling Strategy

| Model | Size | Bundled in APK | Optional Download |
|---|---|---|---|
| YOLOv8n INT8 | ~6MB | ✅ | — |
| MobileFaceNet | ~5MB | ✅ | — |
| MobileNetV3 (pill) | ~12MB | ✅ | — |
| ColorSense HSV engine | <1MB | ✅ | — |
| YAMNet (audio) | ~4MB | ✅ | — |
| MobileNetV2 (currency) | 8MB × 4 currencies | First currency bundled | Others optional |
| MoondreamV2 (scene) | ~1.5GB | ❌ | User-triggered one-time |
| Gemma 2B (LLM) | ~1.5GB | ❌ | User-triggered one-time |
| Open Food Facts DB | ~500MB | ❌ | User-triggered one-time |
| Pill database SQLite | ~40MB | ✅ (compressed) | — |

### 10.3 Zero-Cloud Dependencies

The following third-party cloud services are **explicitly banned** from the core product:
- Google Cloud Vision / Vertex AI
- Azure Cognitive Services
- AWS Rekognition
- OpenAI / Anthropic / Gemini API
- Any service that requires an API key rotation or subscription renewal

The feedback pipeline (`submit-feedback.js`) uses Gmail SMTP as an **operator tool only** — no user data goes to any cloud without user intent. This is not a core product dependency.

### 10.4 Network Use Policy

| Network use | Allowed | Notes |
|---|---|---|
| One-time model download | ✅ | User-explicit, cancellable, resumable |
| Feedback submission | ✅ | User-explicit, graceful offline queue |
| Calibration DB fetch | ✅ | Optional, local fallback always ready |
| Any inference API call | ❌ | Strictly prohibited |
| Telemetry / analytics | ❌ | Strictly prohibited |
| Crash reporting to cloud | ❌ | Crash logs stay on device only |

---

## 11. FINANCIAL INDEPENDENCE ARCHITECTURE

### 11.1 Core Principle

Auriga must never be held hostage by:
- An API provider raising prices
- A cloud vendor deprecating a service
- A hardware manufacturer locking out sideloading
- An app store taking a cut of the survival tool a blind person depends on

### 11.2 Revenue Model (Sustainable Without VC)

| Channel | Model | Target |
|---|---|---|
| Core APK | **Free, open source** | Maximum adoption |
| Hardware kit | **One-time $89** (Pi Zero 2W + camera + earpiece + 3D-printed mount, pre-configured) | NGOs, clinics, individuals |
| Enterprise SDK | **Per-seat licensing** to hospitals, rehab centres, white-label | Institutional revenue |
| Community contributions | **Open currency/pill model contributions** | Ecosystem growth |
| Grants | WHO, RNIB, NFB, Google.org accessibility grants | Capital without equity |
| Braille integration | **Partnership with Orbit Research** (not dependency) | Distribution |

### 11.3 What Must Never Require Payment to Function

- Navigation (TruePath, ZoneAnalyser, StairSense)
- Object detection (YOLO, ColorSense)
- OCR reading
- Face recognition (basic, up to 10 enrolled)
- Emergency SOS
- Haptic feedback
- Voice interaction

---

## 12. PHASED BUILD ROADMAP

### Phase 0 — Foundation (DONE)
✅ TriangulationEngine, YOLOv8n, OCR, voice engine, calibration, PWA, web feedback

### Phase 0.5 — Accessibility & Butler Foundation ⚠️ DO THIS BEFORE PHASE 1
**Goal:** A blind user can install, onboard, and discover features without any sighted assistance.
**Sprint:** 2–3 sessions

- [ ] Fix A: VoiceSetupActivity — voice-first name capture (SpeechRecognizer replaces keyboard-first)
- [ ] Fix B: LocatorActivity — boot announcement (first-run welcome + subsequent short confirmations)
- [ ] Fix C: activity_locator.xml + LocatorActivity — TalkBack-safe drawer (contentDescriptions, long-press trigger, focus restore)
- [ ] SmartCalibrationEngine — auto-detect device model from Build.MODEL, match to calibration_library.json, apply FOV preset; fallback to geometry-based audio walk with beep-on-alignment
- [ ] AurigaButlerService — command execution engine (50+ commands, dispatches to AccessibilityService + system intents)
- [ ] AurigaAccessibilityService + accessibility_service_config.xml — cross-app control (home, back, recents, tap by label, read screen)
- [ ] TutorialActivity + AurigaTutorialEngine — 13-chapter voice-guided tutorial, no screen interaction required
- **Deliverable:** A blind user can install and use all existing features without help

### Phase 1 — Safety Completeness (Sprint: 4 weeks)
**Goal:** No VI user gets hurt using Auriga.
- [ ] StairSenseEngine (stair/edge detection)
- [ ] TrafficSenseEngine (vehicle approach warning)
- [ ] CrossingGuardEngine (traffic light + vehicle combined)
- [ ] EmergencySOSEngine (SOS sequence)
- [ ] PassiveHazardEngine (smoke/CO audio detection)
- [ ] GodsEyeOrchestrator completion (multi-object tracking merge)
- **Deliverable:** APK with all safety features, GitHub Release v0.9

### Phase 2 — Identification Suite (Sprint: 5 weeks)
**Goal:** Replace OrCam's core feature set, offline, free.
- [ ] PillGuardEngine + SQLite pill database
- [ ] FaceVaultEngine + enrolment UI
- [ ] CashLensEngine (USD + GBP + EUR + KES)
- [ ] LabelReaderEngine (expiry + barcode + ZXing)
- [ ] ColorSenseEngine
- **Deliverable:** APK v1.0, press release vs OrCam

### Phase 3 — Memory & Intelligence (Sprint: 5 weeks)
**Goal:** The app knows your world.
- [ ] SpatialMemoryEngine + route recording/replay UI
- [ ] AurigaMind persistent companion extensions
- [ ] SceneDescriberEngine (VLM-powered on capable devices)
- [ ] OfflineLLM integration (Gemma 2B on compatible hardware)
- [ ] Multi-language command routing
- **Deliverable:** APK v1.5

### Phase 4 — Wearable & Cross-Platform (Sprint: 6 weeks)
**Goal:** Hands-free, multi-device.
- [ ] AurigaPi™ Python platform (Pi 4B + Pi Zero 2W)
- [ ] AurigaPC™ Python/PyQt desktop app (Linux + Windows)
- [ ] ESP32 haptic satellite firmware
- [ ] Arduino haptic belt firmware
- [ ] BrailleLinkEngine
- [ ] CameraLink wearable glasses support
- **Deliverable:** Full cross-platform release v2.0

### Phase 5 — Ecosystem & Commercial (Sprint: ongoing)
**Goal:** Self-sustaining ecosystem.
- [ ] Enterprise SDK packaging
- [ ] Hardware kit production (BOM, assembly guide, supplier list)
- [ ] NGO partnership programme
- [ ] Community model contribution pipeline (currency models)
- [ ] Developer documentation site
- [ ] WHO / RNIB / NFB grant applications

---

## 13. GITHUB ACTIONS CI/CD PIPELINE

### 13.1 Repository Structure

```
AURIGA/
├── app/                          # Android app
├── web_deploy/                   # PWA
├── netlify/                      # Netlify serverless
├── docs/                         # Documentation (this file)
├── platforms/
│   ├── pc/                       # AurigaPC Python
│   ├── pi/                       # AurigaPi Python
│   └── mcu/
│       ├── esp32_haptic_node/    # ESP32 Arduino firmware
│       └── arduino_haptic_belt/  # Arduino firmware
└── .github/
    └── workflows/
        ├── build-android.yml     # APK CI
        ├── build-pc.yml          # Python package CI
        └── release.yml           # Unified release
```

### 13.2 Android APK Build Workflow

```yaml
# .github/workflows/build-android.yml
name: Build Android APK

on:
  push:
    branches: [ main, develop ]
  pull_request:
    branches: [ main ]
  release:
    types: [ created ]

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Setup Android SDK
        uses: android-actions/setup-android@v3

      - name: Grant execute permission for gradlew
        run: chmod +x AURIGA/app/../gradlew
        working-directory: AURIGA

      - name: Cache Gradle packages
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: ${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle*') }}
          restore-keys: ${{ runner.os }}-gradle-

      - name: Build Debug APK
        run: ./gradlew assembleDebug --no-daemon
        working-directory: AURIGA

      - name: Build Release APK (unsigned)
        run: ./gradlew assembleRelease --no-daemon
        working-directory: AURIGA

      - name: Sign Release APK
        if: github.event_name == 'release'
        uses: r0adkll/sign-android-release@v1
        with:
          releaseDirectory: AURIGA/app/build/outputs/apk/release
          signingKeyBase64: ${{ secrets.SIGNING_KEY_BASE64 }}
          alias: ${{ secrets.KEY_ALIAS }}
          keyStorePassword: ${{ secrets.KEY_STORE_PASSWORD }}
          keyPassword: ${{ secrets.KEY_PASSWORD }}

      - name: Upload Debug APK artifact
        uses: actions/upload-artifact@v4
        with:
          name: auriga-debug-apk
          path: AURIGA/app/build/outputs/apk/debug/app-debug.apk
          retention-days: 30

      - name: Upload to GitHub Release
        if: github.event_name == 'release'
        uses: softprops/action-gh-release@v2
        with:
          files: |
            AURIGA/app/build/outputs/apk/debug/app-debug.apk
            AURIGA/app/build/outputs/apk/release/app-release-signed.apk
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

### 13.3 PC Platform Build Workflow

```yaml
# .github/workflows/build-pc.yml
name: Build AurigaPC

on:
  push:
    branches: [ main ]
  release:
    types: [ created ]

jobs:
  build-linux:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: '3.11'
      - name: Install dependencies
        run: |
          sudo apt-get install -y espeak-ng libespeak-dev
          pip install pyinstaller opencv-python-headless \
            torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cpu \
            pyttsx3 pyqt6
      - name: Build AppImage
        run: |
          cd platforms/pc
          pyinstaller auriga_pc.py --onefile --name AurigaPC-Linux
      - name: Upload artifact
        uses: actions/upload-artifact@v4
        with:
          name: AurigaPC-Linux
          path: platforms/pc/dist/AurigaPC-Linux

  build-windows:
    runs-on: windows-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: '3.11'
      - name: Install dependencies
        run: pip install pyinstaller opencv-python torch torchvision pyttsx3 pyqt6
      - name: Build EXE
        run: |
          cd platforms/pc
          pyinstaller auriga_pc.py --onefile --name AurigaPC-Windows --windowed
      - name: Upload artifact
        uses: actions/upload-artifact@v4
        with:
          name: AurigaPC-Windows
          path: platforms/pc/dist/AurigaPC-Windows.exe
```

### 13.4 Unified Release Workflow

```yaml
# .github/workflows/release.yml
name: Create Release

on:
  push:
    tags:
      - 'v*.*.*'

jobs:
  trigger-all-builds:
    runs-on: ubuntu-latest
    steps:
      - name: Trigger Android build
        uses: actions/github-script@v7
        with:
          script: |
            await github.rest.actions.createWorkflowDispatch({
              owner: context.repo.owner, repo: context.repo.repo,
              workflow_id: 'build-android.yml', ref: context.ref
            });
      - name: Trigger PC build
        uses: actions/github-script@v7
        with:
          script: |
            await github.rest.actions.createWorkflowDispatch({
              owner: context.repo.owner, repo: context.repo.repo,
              workflow_id: 'build-pc.yml', ref: context.ref
            });
```

### 13.5 GitHub Secrets Required

| Secret Name | Description |
|---|---|
| `SIGNING_KEY_BASE64` | Base64-encoded Android keystore file |
| `KEY_ALIAS` | Keystore key alias |
| `KEY_STORE_PASSWORD` | Keystore password |
| `KEY_PASSWORD` | Key password |
| `GMAIL_USER` | Feedback email address |
| `GMAIL_APP_PASSWORD` | Gmail app password (16-char) |
| `FEEDBACK_GITHUB_TOKEN` | PAT for filing feedback as GitHub Issues |

---

## 14. HARDWARE TARGET SPECIFICATIONS

### 14.1 Minimum Android Target

- SoC: Snapdragon 450 / MediaTek Helio G85 or equivalent
- RAM: 2GB (3GB recommended)
- Camera: 8MP rear, autofocus
- Storage: 2GB free (6GB with optional models)
- OS: Android 7.0 (SDK 24)
- Example devices: Redmi 9, Samsung Galaxy A12, Tecno Spark 8

### 14.2 Optimal Android Target

- SoC: Snapdragon 695 / Dimensity 700 or better (with DSP/NPU)
- RAM: 4GB+
- Camera: 12MP+ with OIS
- OS: Android 12+
- Example devices: Redmi Note 12, Samsung A53, Pixel 6a

### 14.3 Raspberry Pi Wearable Configuration

**Pi Zero 2W (Ultra-portable)**
```
Pi Zero 2W (512MB RAM)
Pi Camera Module 3 NoIR (low-light, no IR filter)
USB-C → USB-A OTG hub
  → USB sound card → 3.5mm earpiece
  → USB micro-speaker (for louder outdoor use)
3D-printed glasses frame mount (STL files in /hardware/stl/)
500mAh LiPo + PiSugar 3 UPS hat
Total weight: ~38g
Runtime: ~5 hours
```

**Pi 4B (Full capability station)**
```
Pi 4B 4GB
Pi Camera Module 3
GPIO 17/18/27 → DRV2605L haptic driver → 3× ERM motors
USB → Braille display (optional)
USB speaker
10,000mAh USB-C bank
Runtime: ~14 hours
```

### 14.4 ESP32 Haptic Belt BOM

```
ESP32-S3-DevKitC-1 × 1 (~$5)
ERM vibration motor 10mm × 3 (~$3)
DRV2605L haptic driver × 1 (~$4)
LiPo 500mAh × 1 (~$5)
TP4056 charge module × 1 (~$1)
Neoprene belt material, 50cm
3D-printed motor housings (STL: /hardware/stl/haptic_belt_motor_pod.stl)
Total BOM: ~$18
```

---

## 15. ACCESSIBILITY STANDARDS COMPLIANCE

### 15.1 Standards Targeted

- **WCAG 2.1 AA** — Web Content Accessibility Guidelines (PWA)
- **CVAA** — 21st Century Communications and Video Accessibility Act (US)
- **EN 301 549** — European accessibility standard for ICT
- **CRPD Article 9** — UN Convention on Rights of Persons with Disabilities
- **Android Accessibility** — TalkBack, Switch Access, Voice Access compatibility

### 15.2 Implementation Requirements

**All UI elements must have:**
- `contentDescription` on every View (no exceptions)
- Touch target minimum 48×48dp
- Color contrast ratio ≥ 4.5:1 for normal text, 3:1 for large text
- No information conveyed by color alone (always paired with text/icon)

**All audio outputs must:**
- Be interruptible (stop speaking when new command received)
- Support variable speech rate (0.5× to 2.0×)
- Use a consistent earcon system (short distinct tones) for categories of alert
- Never play simultaneously without priority queue management

**All haptic outputs must:**
- Have a corresponding audio equivalent (for users without haptic sensitivity)
- Be configurable in intensity (0%, 50%, 100%)
- Follow a consistent spatial mapping (left haptic = left obstacle, always)

**Navigation must:**
- Complete every user flow without touching the screen (voice-only path)
- Work with TalkBack enabled
- Announce screen transitions automatically

---

## 16. TESTING STRATEGY

### 16.1 Automated Tests (GitHub Actions)

Each module must include a `selfTest(Context ctx): boolean` static method that exercises the primary code path against a static test fixture and returns true/false. These run in GitHub Actions on every push.

### 16.2 Physical Testing Protocol

**Distance accuracy test:**
- 10 measurements at each of: 0.5m, 1.0m, 1.5m, 2.0m, 3.0m
- Acceptance: 90% of readings within ±20cm of ground truth

**Zone direction test:**
- 20 walks through structured corridors (blocked left / blocked right / blocked center)
- Acceptance: >85% correct zone recommendation

**Stair detection test:**
- 10 approaches to stairs (ascending) + 10 descending
- Acceptance: 100% detection (zero missed stairs), <2% false positives indoors

**Latency test:**
- Instrument full frame-to-output pipeline
- Acceptance: P95 < 120ms (phone), P95 < 200ms (Pi Zero 2W)

**Battery drain test:**
- 4-hour continuous use session
- Acceptance: <35% battery drain on 4,000mAh device (core navigation only)

### 16.3 VI User Testing Protocol

Before any public release:
- 5 sessions with users who have been VI from birth (not acquired)
- 5 sessions with users with low vision (not total)
- 5 sessions in outdoor environments including road crossings
- All feedback recorded via in-app feedback form
- Minimum threshold: 4/5 users can complete a defined route without human assistance

---

## 17. SUCCESS METRICS & BENCHMARKS

### 17.1 Technical KPIs

| Metric | Target | Critical Threshold |
|---|---|---|
| Obstacle detection accuracy | >90% | >75% |
| Zone direction accuracy | >85% | >70% |
| Stair detection recall | 100% | 95% |
| Frame-to-output latency (P95) | <120ms | <200ms |
| Battery drain per hour | <8% | <12% |
| APK size (base, no optional models) | <80MB | <120MB |
| Cold start time | <3 seconds | <6 seconds |
| OCR accuracy on clean print | >97% | >90% |
| Face recognition accuracy (enrolled) | >95% | >85% |
| Pill identification safety gate | 100% caution appended | 100% |

### 17.2 Product KPIs

| Metric | 6-month target | 12-month target |
|---|---|---|
| APK installs | 1,000 | 10,000 |
| Active VI users (self-reported) | 500 | 5,000 |
| Community currency models submitted | 4 | 15 |
| NGO partnerships | 2 | 10 |
| GitHub stars | 500 | 3,000 |
| Competitor mentions in VI forums vs Auriga | 5% Auriga | 30% Auriga |

---

## 18. ORDERED SESSION BUILD LIST

> **How to use:** Paste one session prompt at a time into Replit Agent. Wait for it to finish and verify before moving to the next. Each session references only files that exist by that point. Sessions within the same Phase that are marked *(parallel)* can be run simultaneously in separate Agent sessions if desired.

---

### PHASE 0.5 — Accessibility & Butler (Run These First)

---

#### SESSION A — Fix A: Voice-First Onboarding
```
Read AURIGA/app/src/main/java/com/drakosanctis/auriga/VoiceSetupActivity.java
Read AURIGA/app/src/main/res/layout/activity_voice_setup.xml
Read AURIGA/app/src/main/AndroidManifest.xml

Make the following changes exactly as specified in Section 6.0.1 of AURIGA/docs/AURIGA_FULL_BUILD_BLUEPRINT.md:

1. activity_voice_setup.xml: Add a full-width Button with id="voice_speak_name_btn",
   text="TAP TO SPEAK YOUR NAME", contentDescription="Speak your assistant name. Double tap to start listening."
   Place it ABOVE the existing EditText. Style it with background @color/auriga_cyan, textColor @color/deep_space.

2. VoiceSetupActivity.java:
   - Import SpeechRecognizer, RecognizerIntent, RecognitionListener, AccessibilityManager.
   - Add field: SpeechRecognizer nameRecognizer.
   - In onCreate(): check AccessibilityManager.isTouchExplorationEnabled(). If true, suppress TTS
     and use nameInput.announceForAccessibility() for all user guidance instead.
   - Delay TTS welcome message by 1200ms using Handler.postDelayed().
   - Wire voice_speak_name_btn: on click, start SpeechRecognizer in RECOGNIZER_EXTRA_MAX_RESULTS=1
     single-utterance mode. On result: populate nameInput.setText(result) and call confirm().
     On error or empty result: speak/announce "I didn't catch that. You can type a name instead."
     and request nameInput focus.
   - Remove windowSoftInputMode from the VoiceSetupActivity manifest entry so keyboard does not
     auto-open.
   - Keep existing EditText, confirm button, and skip button unchanged.

Output: modified VoiceSetupActivity.java, activity_voice_setup.xml, AndroidManifest.xml (one line change only).
```

---

#### SESSION B — Fix B + C: Boot Announcement & TalkBack Drawer
```
Read AURIGA/app/src/main/java/com/drakosanctis/auriga/LocatorActivity.java (lines 780-810)
Read AURIGA/app/src/main/res/layout/activity_locator.xml (lines 1-120)
Read AURIGA/app/src/main/java/com/drakosanctis/auriga/AurigaVoiceEngine.java
Read AURIGA/app/src/main/java/com/drakosanctis/auriga/TargetStore.java

Make the following changes exactly as specified in Sections 6.0.2 and 6.0.3 of AURIGA/docs/AURIGA_FULL_BUILD_BLUEPRINT.md:

FIX B — LocatorActivity.java initTts():
After tts.setLanguage() succeeds, add:
  - SharedPreferences check for "auriga_boot_welcomed" (key, boolean, default false).
  - If false: Handler.postDelayed(1500ms) → speak the full welcome message (use AurigaVoiceEngine.getAssistantName() for [AssistantName]), set auriga_boot_welcomed=true, set a boolean firstBoot=true on the activity.
  - If firstBoot: delay the detection TTS loop by 3 additional seconds (add a boolean detectionGraceActive guarded by a CountDownTimer or Handler).
  - If true (returning user): Handler.postDelayed(3000ms) → speak short confirmation "Auriga ready. [N] targets active" using TargetStore.read(this).size().

FIX C — activity_locator.xml:
On the menu pill Button: add android:importantForAccessibility="yes",
android:contentDescription="Open menu. Double tap to access all features."
On every clickable drawer row LinearLayout: add android:contentDescription matching the row's function.
On the DrawerLayout root: add android:focusableInTouchMode="true".

FIX C — LocatorActivity.java:
In wireMenuToggle(): after setting onClickListener, also register a GestureDetector on locator_frame
for long-press → drawerLayout.openDrawer(Gravity.START).
After openDrawer(): call drawerLayout.announceForAccessibility("Menu open. Swipe to navigate options.").
After closeDrawer(): call locatorFrame.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED).

Output: modified LocatorActivity.java and activity_locator.xml only.
```

---

#### SESSION C — SmartCalibrationEngine
```
Read AURIGA/app/src/main/java/com/drakosanctis/auriga/CalibrationManager.java
Read AURIGA/app/src/main/java/com/drakosanctis/auriga/CalibrationWalkActivity.java
Read AURIGA/app/src/main/java/com/drakosanctis/auriga/TriangulationEngine.java (first 80 lines)

Create: AURIGA/app/src/main/java/com/drakosanctis/auriga/SmartCalibrationEngine.java

Implement exactly as specified in Section 6.0.4 of AURIGA/docs/AURIGA_FULL_BUILD_BLUEPRINT.md:

Part 1 — Library auto-match:
- tryAutoCalibrate(Context ctx): static method.
- Read Build.MANUFACTURER + " " + Build.MODEL and Build.PRODUCT.
- Open assets/data/calibration_library.json (already exists via Gradle asset copy).
- Parse JSON array "profiles". For each profile: case-insensitive contains-match on "model" field,
  then "codename" as fallback.
- If match: extract camera.fov_horizontal_deg, fov_vertical_deg, offset x/y. Write to SharedPrefs
  (keys: calib_fov_h, calib_fov_v, calib_offset_x, calib_offset_y, calib_source="library").
  Set calibration_walk_completed=true in auriga_prefs. Return true.
- If no match: return false.

Part 2 — Geometry audio walk:
- startAudioWalk(Context ctx, TextToSpeech tts): initialise SensorManager TYPE_ROTATION_VECTOR.
- Walk through 10 poses (as specified in the blueprint table). For each pose:
  (a) Speak the audio instruction.
  (b) Register SensorEventListener. Every 200ms: compute deviation from target pitch/roll.
  (c) Deviation < 5°: ToneGenerator.startTone(TONE_PROP_ACK, 200), speak "Good. Hold still.",
      record stable reading after 1 second, advance to next pose.
  (d) Deviation 5–15°: speak directional hint (e.g. "tilt left 8 degrees") every 1500ms.
  (e) Deviation > 15°: speak heading only ("tilt left") every 1000ms.
- After all 10 poses: derive FOV from geometry, store to SharedPrefs with calib_source="geometry_walk",
  set calibration_walk_completed=true.

Also modify CalibrationWalkActivity.onCreate() to call SmartCalibrationEngine.tryAutoCalibrate(this)
first. If it returns true, speak result and finish() immediately.

Output: SmartCalibrationEngine.java (new), CalibrationWalkActivity.java (modified onCreate only).
```

---

#### SESSION D — AurigaAccessibilityService
```
Read AURIGA/app/src/main/AndroidManifest.xml

Create TWO files:

1. AURIGA/app/src/main/res/xml/accessibility_service_config.xml
   Exact content as specified in Section 6.5.3 of AURIGA/docs/AURIGA_FULL_BUILD_BLUEPRINT.md.

2. AURIGA/app/src/main/java/com/drakosanctis/auriga/AurigaAccessibilityService.java
   Extends AccessibilityService.
   Fields: static AurigaAccessibilityService instance (singleton for Butler to call).
   onServiceConnected(): set instance = this. Speak "Auriga Accessibility Service connected."
   onAccessibilityEvent(): handle TYPE_WINDOW_STATE_CHANGED to track current app package name.
   Public methods:
     - static boolean isAvailable(): return instance != null.
     - boolean goHome(): performGlobalAction(GLOBAL_ACTION_HOME).
     - boolean goBack(): performGlobalAction(GLOBAL_ACTION_BACK).
     - boolean showRecents(): performGlobalAction(GLOBAL_ACTION_RECENTS).
     - boolean pullNotifications(): performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS).
     - boolean tapNodeByText(String text): getRootInActiveWindow(), traverse tree, find first node
       where getText() or getContentDescription() contains text (case-insensitive), call
       node.performAction(ACTION_CLICK). Return true if found.
     - String readScreenContent(): collect all non-empty getText() and getContentDescription() from
       all visible nodes, join with ". ", return.
   onInterrupt(): no-op.

Also add to AndroidManifest.xml: the service entry with BIND_ACCESSIBILITY_SERVICE permission
and meta-data pointing to accessibility_service_config.xml (exactly as in blueprint Section 6.5.3).

Output: accessibility_service_config.xml (new), AurigaAccessibilityService.java (new),
AndroidManifest.xml (add service entry only).
```

---

#### SESSION E — AurigaButlerService
```
Read AURIGA/app/src/main/java/com/drakosanctis/auriga/ButlerCommandRegistry.java
Read AURIGA/app/src/main/java/com/drakosanctis/auriga/AurigaAccessibilityService.java
Read AURIGA/app/src/main/java/com/drakosanctis/auriga/AurigaVoiceEngine.java
Read AURIGA/app/src/main/java/com/drakosanctis/auriga/AurigaVoiceService.java
Read AURIGA/app/src/main/AndroidManifest.xml

Create: AURIGA/app/src/main/java/com/drakosanctis/auriga/AurigaButlerService.java

Implement exactly as specified in Section 6.5.3 of AURIGA/docs/AURIGA_FULL_BUILD_BLUEPRINT.md:

- Extends Service. Foreground. Notification channel "auriga_butler".
- Registers a LocalBroadcastReceiver for AurigaVoiceEngine.ACTION_WAKE_WORD to receive spoken commands.
- On wake event: extract the spoken text from the intent. Pass to ButlerCommandRegistry.match().
  Play ToneGenerator TONE_PROP_ACK (C7 earcon) if matched, TONE_PROP_NACK if not.
- Execute the matched ActionCode using the full execution map in Section 6.5.3.
- For SYSTEM_* actions: call AurigaAccessibilityService.instance methods. If null: speak
  "Please enable Auriga in Accessibility settings."
- For SYSTEM_OPEN_APP: query PackageManager.getInstalledApplications(), match app label to arg,
  launch via getLaunchIntentForPackage().
- For COMM_CALL: startActivity(Intent(ACTION_CALL, tel:) with REQUIRE_CONTACTS resolveContact()).
- For INFO_TIME/DATE/BATTERY: query system, format, speak via TextToSpeech.
- For AURIGA_* actions: fire explicit intents to the relevant activities (LocatorActivity, ReaderActivity, etc).
- For HELP_LIST_COMMANDS: speak ButlerCommandRegistry.buildHelpText() (split into 3 chunks with 2s pause between to avoid overwhelming).
- Proactive tip engine: on start, check butler_tip_last_shown_at. If >24h, Handler.postDelayed(10000) → speak randomFeatureTip(), update timestamp.
- static start(Context) / stop(Context) helpers.

Also add to AndroidManifest.xml:
- Service entry: android:name=".AurigaButlerService" foregroundServiceType="microphone"
- Permissions: CALL_PHONE, SEND_SMS, READ_CONTACTS, QUERY_ALL_PACKAGES (for app launch)

Also modify LocatorActivity.onCreate() to call AurigaButlerService.start(this).

Output: AurigaButlerService.java (new), AndroidManifest.xml (additions only), LocatorActivity.java (one line in onCreate).
```

---

#### SESSION F — TutorialActivity + AurigaTutorialEngine
```
Read AURIGA/app/src/main/java/com/drakosanctis/auriga/AurigaVoiceEngine.java
Read AURIGA/app/src/main/AndroidManifest.xml

Create TWO files:

1. AURIGA/app/src/main/java/com/drakosanctis/auriga/AurigaTutorialEngine.java
   All 13 chapters as specified in Section 6.5.3 of the blueprint.
   Each chapter: String title, String[] steps (TTS script per step), String[] advancePhrases.
   Methods:
     - List<TutorialChapter> getChapters(): return ordered chapter list.
     - boolean isChapterDone(Context ctx, String chapterName): read SharedPrefs.
     - void markChapterDone(Context ctx, String chapterName): write SharedPrefs.
     - TutorialChapter getNextIncompleteChapter(Context ctx): iterate, return first undone.

2. AURIGA/app/src/main/java/com/drakosanctis/auriga/TutorialActivity.java
   Extends Activity. Full-screen dark background. Single centred TextView showing step text (for low-vision).
   TextToSpeech for all content. SpeechRecognizer for navigation ("next", "repeat", "skip chapter", "stop tutorial").
   Flow: load next incomplete chapter from AurigaTutorialEngine. Speak chapter title. Iterate steps.
   After each step: listen for voice navigation phrase (timeout 8 seconds → auto-advance to next step).
   On chapter complete: markChapterDone(). Speak "[Chapter] complete. Say next chapter to continue."
   On all chapters done: speak "Tutorial complete. Say help at any time for commands." → finish().
   Volume keys also advance (onKeyDown KEYCODE_VOLUME_UP → next, KEYCODE_VOLUME_DOWN → repeat).

Also add to AndroidManifest.xml: TutorialActivity entry with AurigaDocTheme.

Output: AurigaTutorialEngine.java (new), TutorialActivity.java (new), AndroidManifest.xml (add activity).
```

---

### PHASE 1 — Safety Modules (Run After Phase 0.5 is Complete)

---

#### SESSION 1 — Interface Contracts
```
Read AURIGA/docs/AURIGA_FULL_BUILD_BLUEPRINT.md Section 8.

Create: AURIGA/app/src/main/java/com/drakosanctis/auriga/AurigaInterfaces.java

One file containing ALL interface declarations as inner interfaces in a single public class AurigaInterfaces:
IFrameProvider, IZoneAnalyser (ZoneMap inner class, Zone enum), IDepthProxy,
IStairSenseEngine (StairResult, StairDirection enum), ITrafficSenseEngine (TrafficResult, TrafficLightState enum),
IFaceVaultEngine (FaceMatch), IPillGuardEngine (PillResult), ICashLensEngine (CashResult),
ISpatialMemoryEngine (LandmarkMatch, ReplayCallback), IOutputLayer (OutputPriority, HapticPattern, HapticZone enums),
IEmergencySOSEngine, IPassiveHazardEngine (HazardType enum), ICommandRouter (SkillHandler).
Package: com.drakosanctis.auriga. No implementations. No explanations.
```

---

#### SESSION 2 — OutputLayer
```
Read AurigaInterfaces.java, HapticManager.java, DrakoVoice.java
Build OutputLayer.java — implements IOutputLayer. Spec: Section 8 of blueprint.
```

---

#### SESSION 3 — CommandRouter
```
Read AurigaInterfaces.java, AurigaVoiceEngine.java, AurigaSkillEngine.java
Build CommandRouter.java — implements ICommandRouter. Spec: Section 8 of blueprint.
```

---

#### SESSION 4 — ColorSenseEngine *(parallel with 5, 6)*
```
Read AurigaInterfaces.java, ImageProcessor.java
Build ColorSenseEngine.java. Spec: Section 6.2.5 + Section 8 of blueprint.
```

---

#### SESSION 5 — StairSenseEngine *(parallel with 4, 6)*
```
Read AurigaInterfaces.java, ImageProcessor.java, TriangulationEngine.java (first 80 lines)
Build StairSenseEngine.java — implements IStairSenseEngine. Spec: Section 6.1.2 + Section 8 of blueprint.
```

---

#### SESSION 6 — TrafficSenseEngine *(parallel with 4, 5)*
```
Read AurigaInterfaces.java, ColorSenseEngine.java, ImageProcessor.java
Build TrafficSenseEngine.java — implements ITrafficSenseEngine. Spec: Section 6.1.3 + Section 8 of blueprint.
```

---

#### SESSION 7 — CrossingGuardEngine
```
Read AurigaInterfaces.java, TrafficSenseEngine.java, ColorSenseEngine.java, OutputLayer.java
Build CrossingGuardEngine.java. Spec: Section 6.3.3 of blueprint.
```

---

#### SESSION 8 — EmergencySOSEngine
```
Read AurigaInterfaces.java, OutputLayer.java, HapticManager.java
Build EmergencySOSEngine.java — implements IEmergencySOSEngine. Spec: Section 6.3.1 of blueprint.
```

---

#### SESSION 9 — PassiveHazardEngine
```
Read AurigaInterfaces.java
Build PassiveHazardEngine.java — implements IPassiveHazardEngine. Spec: Section 6.3.2 of blueprint.
```

---

#### SESSION 10 — GodsEyeOrchestrator (complete the existing stub)
```
Read GodsEyeOrchestrator.java, Detection.java, TriangulationEngine.java, AurigaInterfaces.java
Rewrite GodsEyeOrchestrator.java per Section 8 of blueprint. Keep PathLog and addPoint() intact.
```

---

### PHASE 2 — Identification Suite

---

#### SESSION 11 — PillGuardEngine + PillDatabase *(parallel with 12, 13)*
```
Read AurigaInterfaces.java, ReaderActivity.java
Build PillDatabase.java + PillGuardEngine.java. Spec: Section 6.2.1 + Section 8 of blueprint.
```

---

#### SESSION 12 — FaceVaultEngine + FaceDatabase *(parallel with 11, 13)*
```
Read AurigaInterfaces.java
Build FaceDatabase.java + FaceVaultEngine.java. Spec: Section 6.2.2 + Section 8 of blueprint.
```

---

#### SESSION 13 — CashLensEngine *(parallel with 11, 12)*
```
Read AurigaInterfaces.java
Build CashLensEngine.java — implements ICashLensEngine. Spec: Section 6.2.3 + Section 8 of blueprint.
```

---

#### SESSION 14 — LabelReaderEngine
```
Read AurigaInterfaces.java, ReaderActivity.java
Build LabelReaderEngine.java. Add ZXing to build.gradle. Spec: Section 6.2.4 of blueprint.
```

---

### PHASE 3 — Memory & Intelligence

---

#### SESSION 15 — SpatialMemoryEngine + SpatialDatabase
```
Read AurigaInterfaces.java
Build SpatialDatabase.java + SpatialMemoryEngine.java. Spec: Section 6.1.4 + Section 8 of blueprint.
```

---

#### SESSION 16 — SceneDescriberEngine
```
Read AurigaInterfaces.java, YoloDetector.java, Detection.java, TriangulationEngine.java (first 80 lines)
Build SceneDescriberEngine.java. Spec: Section 6.2.6 of blueprint.
```

---

#### SESSION 17 — AurigaCoreService (Integration — final Android session)
```
Read AurigaInterfaces.java, OutputLayer.java, CommandRouter.java, StairSenseEngine.java,
TrafficSenseEngine.java, GodsEyeOrchestrator.java, PassiveHazardEngine.java,
CrossingGuardEngine.java, EmergencySOSEngine.java, AurigaVoiceEngine.java, AurigaButlerService.java

Build AurigaCoreService.java. Android foreground Service with CameraX analysis loop.
Full spec: Section 18 integration prompt below.

Wire ALL modules. Register Butler commands for all Auriga features in CommandRouter.
Register in AndroidManifest.xml as foreground service with CAMERA + RECORD_AUDIO permissions.
```

---

### PHASE 4 — Platforms

---

#### SESSION 18 — AurigaPi (Raspberry Pi)
```
Read platforms/pc/auriga_pc.py
Build platforms/pi/auriga_pi.py. Full spec: Section 9.3 of blueprint.
Also create platforms/pi/auriga.service (systemd unit file).
```

---

#### SESSION 19 — ESP32 Haptic Node *(parallel with 20)*
```
Build platforms/mcu/esp32_haptic_node/esp32_haptic_node.ino. Full spec: Section 6.4.1 of blueprint.
BLE GATT server. 3-byte protocol: zone, intensity, pattern.
```

---

#### SESSION 20 — Arduino Haptic Belt *(parallel with 19)*
```
Build platforms/mcu/arduino_haptic_belt/arduino_haptic_belt.ino. Full spec: Section 6.4.1 of blueprint.
ArduinoBLE library. Same 3-byte protocol as ESP32.
```

---

### After Session 20 — Tag and Release

```bash
git add .
git commit -m "feat: complete Phases 0.5 through 4"
git tag v1.0.0
git push origin main --tags
```

GitHub Actions builds APK automatically. Release page will have download link within ~10 minutes.

---

### 18.1 AurigaCoreService Integration Prompt (Session 17 detail)

```
I have 12 Java module implementations for the Auriga Android spatial navigation system.
[paste all .java files from sessions 1–16]

Wire them into AurigaCoreService.java (Android Service, HandlerThread):

onCreate():
  - Init OutputLayer, CommandRouter, PassiveHazardEngine, EmergencySOSEngine, AurigaButlerService
  - Start PassiveHazardEngine → callback calls OutputLayer.speak(hazardType.name(), EMERGENCY)
  - Register all CommandRouter skills (one per module with natural language triggers matching ButlerCommandRegistry)

onStartCommand():
  - Start CameraX ImageAnalysis. Frame gate: process every 150ms max.
  - Each frame: run StairSenseEngine + TrafficSenseEngine in parallel (2-thread ExecutorService)
  - Merge in GodsEyeOrchestrator.mergeDetections()
  - getNavigationInstruction(mergedScene) → if changed since last frame → OutputLayer.speak(instruction, NORMAL)
  - CrossingGuardEngine.submitFrame() on every frame when crossing mode active

onDestroy():
  - Shutdown OutputLayer, stop PassiveHazardEngine, release CameraX, stop AurigaButlerService

Output: AurigaCoreService.java only.
```

```
You are a senior Android architect (Java, SDK 34, min SDK 24).

Output ONLY Java interface files — no implementations, no explanations.
Use the exact module names below. All interfaces go in package com.drakosanctis.auriga.

Generate interfaces for:
1. IStairSenseEngine — detects stairs/edges, returns StairResult(detected:bool, stepCount:int, direction:enum UP/DOWN/UNKNOWN, distanceM:float)
2. ITrafficSenseEngine — detects approaching vehicles + traffic light state, returns TrafficResult(approaching:bool, zone:enum LEFT/CENTER/RIGHT, ttcSeconds:float, lightState:enum RED/AMBER/GREEN/UNKNOWN)
3. IFaceVaultEngine — enrol(name, frames) + identify(frame) → List<FaceMatch(name, similarity, bearingDeg, distanceM)> + forget(name)
4. IPillGuardEngine — identify(frame) → PillResult(commonName, imprint, confidence, safeToReport:bool, cautionMessage)
5. ICashLensEngine — identify(frame) → CashResult(denomination, isoCode, confidence) + setCurrency(isoCode)
6. ISpatialMemoryEngine — startRecording(name), addLandmark(desc, steps), stopRecording(), startReplay(name, callback), matchCurrentScene(desc) → LandmarkMatch
7. IPassiveHazardEngine — start(HazardCallback) + stop(); HazardType enum: SMOKE_ALARM, CO_ALARM, DOG_BARK_AGGRESSIVE, CAR_HORN, GLASS_BREAK, GUNSHOT
8. IEmergencySOSEngine — trigger(envDescription), setContact(phone, name), isActive():bool, cancel()
9. IOutputLayer — speak(text, priority:enum BACKGROUND/NORMAL/HIGH/EMERGENCY), haptic(pattern:enum SLOW_PULSE/FAST_PULSE/SINGLE/SOS/STAIR_WARN, zone:enum LEFT/CENTER/RIGHT/ALL), braille(text), setMuted(bool), shutdown()
10. ICommandRouter — dispatch(command):String, registerSkill(trigger, SkillHandler)

Output: one Java interface per module. Nothing else.
```

### 18.2 Phase 2 — Parallel Module Build Prompts

**Use one session per module. Run all simultaneously after Phase 1.**

```
MASTER TEMPLATE — replace [MODULE] and [SPEC] per module:

CONTRACTS: [paste Phase 1 output]

Build MODULE [MODULE] ONLY. Package: com.drakosanctis.auriga.
Rules:
- Java, Android SDK 34, min SDK 24
- No third-party libraries except: TensorFlow Lite (ML modules only), OpenCV 4 (vision modules only), ZXing (LabelReader only)
- Reference all other modules via their interface only — instantiate as null stubs for compilation
- Include: public static boolean selfTest(Context ctx) that returns true if core logic path executes without crash
- Output: one complete, compilable .java file
- No explanations outside of code comments

MODULE 1: StairSenseEngine — implements IStairSenseEngine
SPEC: Use row-differential analysis on NV21 luminance channel. Detect ground-plane discontinuity in the bottom 40% of frame. Count discontinuities as step edges. Direction from luminance gradient polarity.

MODULE 2: TrafficSenseEngine — implements ITrafficSenseEngine  
SPEC: Optical flow Lucas-Kanade in upper 50% of frame for vehicle detection. Color band detection (R/A/G) in a configurable upper-center ROI for traffic light. Scale growth rate over 5 frames for TTC estimation.

MODULE 3: FaceVaultEngine — implements IFaceVaultEngine
SPEC: MobileFaceNet TFLite (128-dim embedding). Store embeddings in SQLite (table: faces, cols: id, name, embedding BLOB). Cosine similarity threshold 0.75 for match. Use ML Kit face detector for bounding box extraction before embedding.

MODULE 4: PillGuardEngine — implements IPillGuardEngine
SPEC: MobileNetV3 TFLite for shape/color classification. Then OCR via ML Kit Latin on the imprint. Cross-reference via SQLite pill_db table (ndc_code, common_name, shape, color, imprint). SafeToReport = confidence > 0.75. Always append cautionMessage = "Verify with your pharmacist before taking."

MODULE 5: CashLensEngine — implements ICashLensEngine
SPEC: Per-currency MobileNetV2 TFLite model. Model file path pattern: assets/cash_[isoCode].tflite. setCurrency loads the correct model. Confidence from softmax output. Return null denomination if confidence < 0.65.

MODULE 6: SpatialMemoryEngine — implements ISpatialMemoryEngine
SPEC: SQLite DB (routes table: id, name, created_at; landmarks table: id, route_id, description, step_offset, seq_order). Recording stores landmark sequence. Replay computes Levenshtein distance between live scene description and stored landmark descriptions. matchCurrentScene returns best-matching route's next landmark instruction.

MODULE 7: PassiveHazardEngine — implements IPassiveHazardEngine
SPEC: YAMNet TFLite (AudioRecord, 16kHz, mono). Window: 15,600 samples. Run inference every 500ms on background thread. Map top class index to HazardType enum. Fire callback if confidence > 0.85 on 2 consecutive windows.

MODULE 8: EmergencySOSEngine — implements IEmergencySOSEngine
SPEC: trigger() starts a new Thread: (1) speak environmentDescription via TextToSpeech, (2) dial stored contact via Intent.ACTION_CALL, (3) send SMS via SmsManager with coordinates from LocationManager last known fix, (4) if no network: loop SOS haptic pattern. cancel() sets a volatile boolean to stop the loop.

MODULE 9: OutputLayer — implements IOutputLayer
SPEC: speak() uses TextToSpeech with priority mapping to QUEUE_FLUSH (HIGH/EMERGENCY) or QUEUE_ADD (BACKGROUND/NORMAL). haptic() uses Vibrator with pattern arrays. braille() writes ASCII via UsbSerialPort (if connected). setMuted() gates all output. shutdown() releases TTS and disconnects serial.

MODULE 10: CommandRouter — implements ICommandRouter
SPEC: Internal HashMap<String, SkillHandler> registry. dispatch() lowercases input, iterates registry keys checking contains(), calls matching handler, returns response. registerSkill() adds to map. Default handler for unknown commands: return "I didn't understand that command."
```

### 18.3 Phase 3 — Integration Prompt (After All 10 Modules Built)

```
I have 10 Java module implementations for the Auriga Android spatial navigation system.
[paste all 10 .java files]

Wire them into a single Android Service called AurigaCoreService.java that:

onCreate():
  - Initialise OutputLayer, CommandRouter, PassiveHazardEngine
  - Start PassiveHazardEngine with a callback that calls OutputLayer.speak(hazardType.name(), EMERGENCY)
  - Register all 10 CommandRouter skills with natural language triggers

onStartCommand():
  - Start CameraX analysis loop
  - Each frame: run ZoneAnalyser → DepthProxy → StairSenseEngine → TrafficSenseEngine in parallel (ExecutorService, 4 threads)
  - Merge results in GodsEyeOrchestrator
  - Pass to OutputPriorityQueue → OutputLayer
  - Frame rate gate: process one frame every 100ms maximum

onDestroy():
  - Shutdown all engines
  - Release camera

Output: AurigaCoreService.java only. No explanations.
```

---

## APPENDIX A — DIRECTORY STRUCTURE (FULL TARGET)

```
AURIGA/
├── app/
│   └── src/main/java/com/drakosanctis/auriga/
│       ├── [all existing .java files]
│       │
│       │   ── Phase 0.5: Accessibility & Butler ──
│       ├── SmartCalibrationEngine.java    [Phase 0.5 — Session C]
│       ├── AurigaAccessibilityService.java [Phase 0.5 — Session D]
│       ├── AurigaButlerService.java       [Phase 0.5 — Session E]
│       ├── ButlerCommandRegistry.java     [Phase 0.5 — scaffold exists]
│       ├── AurigaTutorialEngine.java      [Phase 0.5 — Session F]
│       ├── TutorialActivity.java          [Phase 0.5 — Session F]
│       │
│       │   ── Phase 1: Safety ──
│       ├── AurigaInterfaces.java          [Phase 1 — Session 1]
│       ├── OutputLayer.java               [Phase 1 — Session 2]
│       ├── CommandRouter.java             [Phase 1 — Session 3]
│       ├── ColorSenseEngine.java          [Phase 1 — Session 4]
│       ├── StairSenseEngine.java          [Phase 1 — Session 5]
│       ├── TrafficSenseEngine.java        [Phase 1 — Session 6]
│       ├── CrossingGuardEngine.java       [Phase 1 — Session 7]
│       ├── EmergencySOSEngine.java        [Phase 1 — Session 8]
│       ├── PassiveHazardEngine.java       [Phase 1 — Session 9]
│       │
│       │   ── Phase 2: Identification ──
│       ├── PillDatabase.java              [Phase 2 — Session 11]
│       ├── PillGuardEngine.java           [Phase 2 — Session 11]
│       ├── FaceDatabase.java              [Phase 2 — Session 12]
│       ├── FaceVaultEngine.java           [Phase 2 — Session 12]
│       ├── CashLensEngine.java            [Phase 2 — Session 13]
│       ├── LabelReaderEngine.java         [Phase 2 — Session 14]
│       │
│       │   ── Phase 3: Memory & Intelligence ──
│       ├── SpatialDatabase.java           [Phase 3 — Session 15]
│       ├── SpatialMemoryEngine.java       [Phase 3 — Session 15]
│       ├── SceneDescriberEngine.java      [Phase 3 — Session 16]
│       └── AurigaCoreService.java         [Phase 3 — Session 17, integration]
│
│   ── res additions ──
│   └── src/main/res/xml/
│       └── accessibility_service_config.xml  [Phase 0.5 — Session D]
├── platforms/
│   ├── pc/
│   │   ├── auriga_pc.py
│   │   ├── modules/
│   │   │   ├── zone_analyser.py
│   │   │   ├── stair_sense.py
│   │   │   ├── face_vault.py
│   │   │   ├── pill_guard.py
│   │   │   ├── cash_lens.py
│   │   │   ├── spatial_memory.py
│   │   │   └── output_layer.py
│   │   └── requirements.txt
│   ├── pi/
│   │   ├── auriga_pi.py
│   │   ├── modules/ [symlink or copy of pc/modules]
│   │   ├── gpio_haptic.py
│   │   └── auriga.service [systemd unit]
│   └── mcu/
│       ├── esp32_haptic_node/
│       │   └── esp32_haptic_node.ino
│       └── arduino_haptic_belt/
│           └── arduino_haptic_belt.ino
├── hardware/
│   ├── stl/
│   │   ├── glasses_mount_pi_zero.stl
│   │   ├── chest_harness_pi4.stl
│   │   └── haptic_belt_motor_pod.stl
│   └── bom/
│       ├── haptic_belt_bom.csv
│       └── pi_glasses_bom.csv
├── docs/
│   ├── AURIGA_FULL_BUILD_BLUEPRINT.md     [this file]
│   ├── SCALING_STRATEGY.md
│   └── API_REFERENCE.md
├── .github/
│   └── workflows/
│       ├── build-android.yml
│       ├── build-pc.yml
│       └── release.yml
└── server.js                              [Replit dev server]
```

---

## APPENDIX B — EARCON SYSTEM (AUDIO CUES)

A consistent audio shorthand so users don't need full speech for frequent alerts:

| Earcon | Sound description | Meaning |
|---|---|---|
| `C1` | Single high beep (880Hz, 80ms) | Path clear |
| `C2` | Double mid beep (440Hz, 80ms×2) | Turn recommendation |
| `C3` | Triple low beep (220Hz, 100ms×3) | Obstacle close (<1m) |
| `C4` | Rising tone (200→800Hz, 300ms) | Recognised face detected |
| `C5` | Descending tone (800→200Hz, 300ms) | Face leaving frame |
| `C6` | Fast buzz tone (50ms×5) | Emergency / danger |
| `C7` | Soft chime (C major, 200ms) | Command accepted |
| `C8` | Low buzz (100Hz, 500ms) | Command not understood |
| `C9` | Two-note ascending (C+E, 150ms each) | Route milestone reached |
| `C10` | SOS pattern | Emergency SOS active |

All earcons generated programmatically via `AudioTrack` — no audio file assets required.

---

*Document version: 1.0 | DrakoSanctis Engineering | Auriga Ecosystem*
*This document is the authoritative build reference. All module implementations, GitHub Actions pipelines, and product decisions must be consistent with the specifications herein.*
*Last updated: 2026-06*
