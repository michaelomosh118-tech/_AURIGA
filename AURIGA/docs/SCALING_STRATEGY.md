# AurigaNavi — What We Have & How to Scale Against Competitors

## What AurigaNavi Has Today

Based on the codebase and market analysis, here's AURIGA's current inventory:

### Unique Strengths (No Competitor Has These)
| Capability | Implementation | Status |
|---|---|---|
| **Real-time obstacle avoidance** (3-column scan) | `ImageProcessor`, `SonarManager` | Working |
| **Distance/bearing readouts** | `FiducialLUT`, `TriangulationEngine` | Working (jittery) |
| **SkyShield (overhead detection)** | `GodsEyeOrchestrator` | Working |
| **Community-contributed calibration** | `CalibrationManager`, web library | Working |
| **Per-device hardware profiling** | `HardwareHAL` (real HFOV, pitch correction) | Working |
| **Haptic feedback** | `HapticManager` | Stub |
| **100% on-device / zero cloud** | Entire stack | ✅ |
| **4 product flavors** (Navi/Sentinel/Aero/Industrial) | Gradle config | ✅ |
| **In-house crash logging** | `CrashReportActivity` | Working |
| **OCR page reader** | `ReaderActivity` (ML Kit on-device) | Working |
| **Target object locator** | `LocatorWebActivity`, `TargetStore`, `TargetsActivity` | Working (WebView-based) |
| **Feedback system** | `FeedbackActivity`, `FeedbackSubmitter` | Working |
| **$0 price** | Open-source | ✅ |

### The Hard Truth
AURIGA excels at **spatial awareness** (distance, bearing, obstacles, overhead) — something NO competitor does on a phone camera. But the features blind users actually pay for and use daily are **read-my-world tools**: OCR, scene description, object identification, face recognition, currency ID. AURIGA is mostly missing those (OCR/Reader was just added, object locator is WebView-based).

---

## Competitive Landscape — Where AURIGA Stands

### AURIGA vs. The Field

```
                    SPATIAL AWARENESS ──────────────────►
                    (distance, obstacles, navigation)
    ▲
    │
    │  ┌─────────────┐
 R  │  │  Seeing AI   │    ┌──────────┐
 E  │  │  (36 langs,  │    │  OrCam   │
 A  │  │   GPT-4V)    │    │ ($4.5k)  │
 D  │  └─────────────┘    └──────────┘
    │  ┌─────────────┐
 M  │  │  Lookout     │    ┌──────────┐
 Y  │  │  (Gemini)    │    │ Envision │
    │  └─────────────┘    │ ($2-3.5k)│
 W  │  ┌─────────────┐    └──────────┘
 O  │  │ Be My Eyes   │
 R  │  │ (GPT-4V +    │
 L  │  │  volunteers) │
 D  │  └─────────────┘
    │  ┌─────────────┐
    │  │ Supersense   │
    │  └─────────────┘
    │
    │                              ┌──────────────────┐
    │                              │    AURIGA         │
    │                              │  (FREE, offline,  │
    │                              │   unique spatial) │
    │                              └──────────────────┘
    └──────────────────────────────────────────────────►
```

**AURIGA owns the bottom-right quadrant** — strong spatial awareness, weak read-my-world features. Every competitor is in the top-left — strong reading/description, zero spatial awareness.

**The winning move: fill the top-right quadrant.** Be the first app that does BOTH.

---

## How to Scale — The 3 Strategic Wedges

### Wedge 1: "The Only Integrated Stack" (Biggest Differentiator)
Blind users currently juggle 3-5 apps: Seeing AI for scanning, Be My Eyes for help, Voice Dream for books, Google Maps for nav, WeWALK app for their cane. **No single app does everything.**

AURIGA can be the first. The market analysis doc's roadmap (Phases 1-7) lays out how, but here's the priority order for maximum competitive impact:

| Priority | Feature | Competitive Impact | Effort |
|---|---|---|---|
| **#1** | Target object locator (native, not WebView) | Only Envision + Supersense have this; Seeing AI/Lookout don't | 1 week |
| **#2** | OCR + document reading (PaddleOCR + Piper TTS) | Table stakes — every competitor has it, we need it | 1 week |
| **#3** | Book reading (EPUB/PDF) | Voice Dream charges $10-80; we'd offer it free inside a nav app | 3-5 days |
| **#4** | Template scene description (no AI needed) | "I see 2 people, 1 chair at 2m" — works on ALL phones, no cloud | 3-5 days |
| **#5** | Currency + barcode recognition | Table stakes for accessibility | 1 week |

### Wedge 2: "100% Independent / Zero Cloud" (Trust Differentiator)
Every competitor relies on cloud AI:
- Seeing AI → Azure
- Lookout → Google Cloud / Gemini
- Be My AI → OpenAI GPT-4V
- Envision → Cloud for Ally/Aira

**AURIGA is the only product that can claim zero third-party cloud dependency.** This matters enormously for:
- **Privacy-conscious users** (blind users are especially vulnerable to data exploitation)
- **Users in areas with poor connectivity** (rural Africa, Southeast Asia, developing markets)
- **Institutional buyers** (schools for the blind, VA hospitals) who can't send patient data to cloud

The independence roadmap in §4 of the market analysis is achievable using:
- PaddleOCR (Apache 2.0, on-device)
- Piper TTS (MIT, on-device, replaces Samsung/Google TTS)
- YOLOv8n (6 MB, 15-25 FPS on mid-range Android)
- Moondream 2 (optional, for high-end phones only)

### Wedge 3: "Free vs. $4,500" (Price Disruption)
OrCam charges $4,500+. Envision charges $1,899-$3,499. AURIGA does the spatial features better and costs $0. As we add OCR + object finding + scene description, the feature gap narrows while the price gap stays infinite.

**Target messaging:** "Everything OrCam does, on the phone you already own, for free."

---

## Scaling Playbook — Concrete Steps

### Phase 1: Fix & Ship (This Week)
1. ✅ Fix the build (PR #17 — done, merge it)
2. Move the Target Locator from WebView → native (YOLOv8n TFLite)
3. Polish the OCR Reader (already have `ReaderActivity` with ML Kit)

### Phase 2: Close Table-Stakes Gaps (Weeks 2-4)
4. PaddleOCR + Piper TTS (replace ML Kit + system TTS)
5. EPUB/PDF book reader
6. Currency + barcode recognition
7. Template-based scene description

### Phase 3: Build Moats (Months 2-3)
8. Voice commands (Vosk, offline)
9. Wake word ("Hey AURIGA")
10. OsmAnd pedestrian navigation
11. Optional Moondream 2 scene description for high-end phones
12. Multi-language support (5+ Piper voices)

### Phase 4: Distribution & Growth (Month 3+)
13. **Google Play accessibility category** — AURIGA should aim for Play Store featuring
14. **Partner with blindness organizations** (NFB, RNIB, Kenya Society for the Blind) for distribution
15. **VA / vocational rehab channel** — in the US, devices/apps for blind people are often funded by VA or state vocational rehab. Being free makes the paperwork trivial
16. **Open-source the repo** — builds trust, attracts contributors, gets accessibility community invested
17. **Bookshare / NLS BARD integration** — access to 1.1M+ accessible books

### Phase 5: Hardware Partnerships (Month 6+)
18. Smart cane BLE integration (WeWALK or custom $50 cane)
19. Smart glasses integration (Ray-Ban Meta, Xreal)
20. Be My Eyes B2B API for human fallback

---

## Key Decision Points for You

1. **Paradigm: navigation-only or integrated suite?** The analysis assumes integrated suite. This is the right call — pure navigation is a feature, not a product.

2. **Flavor differentiation:** Should Navi get the full suite, Sentinel get security-focused features (face recognition), Aero get navigation-only, Industrial get barcode/scene? Or should all flavors get everything?

3. **Scene description trade-off:** Template-based ("I see 2 people, 1 chair") works on ALL phones, instant, no download. Moondream 2 gives GPT-4V-quality descriptions but needs 900MB download and high-end phone. Ship both?

4. **Open-source timing:** Going public now builds community but exposes your code. Going public after Phase 2 (with table-stakes features done) is stronger positioning.

---

## Bottom Line

**What you have:** The only free, offline, spatial-awareness navigation app for blind users. Real moats in obstacle detection, distance/bearing, overhead scanning, community calibration, and zero cloud dependency.

**What you're missing:** The read-my-world features that 90% of blind users use daily (OCR, scene description, face recognition, currency ID).

**How to win:** Close the read-my-world gaps (4-6 weeks of work) while keeping the spatial + independence moats that no competitor can match. The result: the first and only integrated vision-assistance suite that works 100% offline, costs $0, and does things OrCam charges $4,500 for.

---

## Architecture Decisions (Session 18 — Integration Fixes)

### Fix 1 — Camera ownership: FrameRelay singleton

**Problem:** `AurigaCoreService` and `LocatorActivity` both called
`ProcessCameraProvider.unbindAll()` then `bindToLifecycle(this, ...)`, competing for
the physical camera device. On Android 12+ (SDK 34/35, enforced by Samsung SM-A057F)
the camera HAL throws `SecurityException: Attempt to use camera from a different
process than original client` when CameraX session-drain callbacks fire on the
service's thread pool after the activity originally opened the device.

**Decision:** Exactly one component owns the camera at any given time:
`LocatorActivity` owns it when in the foreground. `AurigaCoreService` never binds
CameraX directly. Instead, a new `FrameRelay` singleton (thread-safe
`CopyOnWriteArrayList` of `IFrameProvider.FrameListener`) acts as a frame bus:

```
LocatorActivity (YoloAnalyzer thread)
    │  after each inference cycle
    ▼
FrameRelay.get().publishBitmap(bmp, 0)   ← ARGB→NV21 conversion here
    │  synchronous dispatch
    ▼
AurigaCoreService.processFrame(nv21, w, h, rot)
    │  rate-gated (100ms), offloads to analysisPool
    ├── StairSenseEngine
    ├── TrafficSenseEngine
    ├── CrossingGuardEngine
    └── GodsEyeOrchestrator
```

`LocatorActivity.onResume()` calls `FrameRelay.markSourceActive()`.
`LocatorActivity.onPause()` calls `FrameRelay.markSourceInactive()`.
When the locator is not in the foreground, no frames flow and the service engines
are silent (correct — camera access from background is also restricted on Android 12+).

### Fix 2 — LLM wired into command dispatch chain

**Problem:** `AurigaCoreService.CommandRouter` had all camera skills registered but
its dispatch result was never passed to `AurigaSkillEngine` or `MindEngine`. Unknown
commands returned the fallback string and stopped. `AurigaVoiceEngine.routeCommand()`
handled timers/weather/LLM but had no path into the service's camera skills (describe,
stair, crossing, find face, read cash, etc.).

**Decision:** Unified 5-tier dispatch chain:

```
AurigaVoiceEngine.routeCommand(cmd)
    ├── T1: UI / navigation commands (open drawer, go back, etc.)
    ├── T2: AurigaSkillEngine  (timers, alarms, weather, compass)
    ├── T3: AurigaCoreService.tryDispatchCameraCommand(cmd)   ← NEW TIER
    │       └── CommandRouter  (describe, stair, crossing, face, pill, cash…)
    │           returns null on no-match (CommandRouter.dispatch changed)
    ├── T4: AurigaKnowledge rule-based KB   (instant, offline)
    ├── T5: MindEngine Qwen LLM             (streams via its own TTS)
    └── T6: KnowledgeCache context / AurigaKnowledge.fallback()
```

`AurigaCoreService` also has a full standalone `onVoiceCommand()` that runs the same
5-tier chain, for callers that don't go through `AurigaVoiceEngine` (e.g. the Butler).

`CommandRouter.dispatch()` now returns `null` on no-match (previously returned a
"I don't know that command" string, which prevented callers from detecting miss).

`AurigaCoreService.initTts()` boots `TextToSpeech` in `onCreate()`, then wires
`AurigaSkillEngine`, `KnowledgeCache.warmUp()`, and `MindEngine.createAsync()`.
Hot-reload: if a Qwen model download completes while the service is live,
`ModelDownloadManager.DownloadListener` reinitialises `MindEngine` immediately.

### Fix 3 — Mic kills music: AudioRecord VAD

**Problem:** `AurigaVoiceService` called `SpeechRecognizer.startListening()` on
every ~300 ms cycle. The recognizer's internal Google process requests
`AUDIOFOCUS_GAIN` on every call, ducking music and media playback continuously.

**Decision:** Replace the always-on `SpeechRecognizer` loop with `AudioRecord` +
energy-RMS Voice Activity Detector (VAD):

```
AudioRecord (VOICE_RECOGNITION, 16 kHz, mono, 16-bit PCM)
    │  reads 100 ms chunks — NO audio focus request
    ▼
computeRms(chunk) > 800.0f ?
    │  yes, for ≥ 3 consecutive chunks (300 ms sustained speech)
    ▼
SpeechRecognizer.startListening()   ← fires once, ~2–3 s window
    │  onResults: check wake phrase → broadcast
    ▼
back to AudioRecord loop (cooldown 2 s minimum)
```

`AudioRecord` with `MediaRecorder.AudioSource.VOICE_RECOGNITION` gets hardware
noise suppression (AGC/NS/EC) for free with zero audio focus interaction. Music
plays completely uninterrupted. The `SpeechRecognizer` is only active for the
brief window after confirmed speech — fully intentional and acceptable.

Graceful fallback: if `AudioRecord` fails to initialize (rare; some emulators),
the service degrades to the original `SpeechRecognizer` loop with a log warning.

### Audit fixes (incomplete flows patched)

- **`captureFaceEnrolment()`** — 5-frame capture loop now null-checks each
  `FrameSnapshot.nv21` and aborts with a voice message if no frame is available
  (previously passed null frames to `faceVault.enrol()`, risking NPE or silent
  enrolment failure).
- **`stopForeground(true)`** — replaced with `stopForeground(STOP_FOREGROUND_REMOVE)`
  (the `boolean` overload was deprecated in API 33; the `int` overload has been
  available since API 24, which is this app's minSdk).
- **`CommandRouter` Javadoc** — updated to reflect `null` return on no-match.
