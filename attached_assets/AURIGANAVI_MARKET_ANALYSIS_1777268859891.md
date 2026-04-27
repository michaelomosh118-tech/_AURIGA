# AURIGA — Competitive Market Analysis & Gap-Closure Roadmap

**Author:** Devin for Michael
**Repo:** `michaelomosh118-tech/_AURIGA`
**Date:** 2026-04-21
**Scope:** (1) Reference-app teardown of `Object-Locator.zip`, (2) competitor market study, (3) AURIGA feature-gap matrix, (4) third-party dependency audit with self-hosted / on-device replacements aligned to your independence goal, (5) prioritised implementation roadmap including voice book reading.

---

## 0. TL;DR — the three things that matter most

1. **AURIGA today solves the wrong problem for the blind-navigation market.** It's a *don't-bump-into-things* tool. The apps blind users actually pay for are *read-my-world* tools: OCR + text-to-speech + object recognition + scene description + human-assisted video calls. Distance/bearing jitter is a red herring — even if we fix it to 1% accuracy, the feature set is still below Seeing AI (free), Lookout (free), and Be My Eyes (free). The jitter work was necessary plumbing; it is not the product.

2. **Object-Locator is a "find my specific thing" paradigm.** It lets the user pick target classes ("keys", "mug", "dog"), runs COCO-SSD over the webcam, and narrates *"dog detected, 23 degrees right"* when a target appears. That paradigm is absent from AURIGA and is also absent from most competitors — it's a genuine differentiator if we build it properly.

3. **Your independence goal is achievable but non-trivial.** Every competitor leans on cloud LLMs (OpenAI GPT-4V for Be My AI, Azure for Seeing AI, Google Gemini for Lookout). Going fully self-hosted costs us the "describe this scene in a paragraph" feature — unless we run a small on-device VLM (e.g. Moondream 2, Qwen-VL 2B 4-bit) which is now feasible on mid-range Android in ~2-3 s per query. Everything else (OCR, TTS, object detection, depth, barcode) has mature offline open-source stacks.

---

## 1. Reference app teardown — what's inside `Object-Locator.zip`

Unzipped and read the whole codebase. It's a **React + Vite + Express + Drizzle/Postgres** web app (not a mobile app), ~80 files.

### 1.1 Architecture
- **Client (`client/src/`):** Vite + React 18 + TailwindCSS + Radix UI + shadcn/ui components. Webcam via `react-webcam`. Object detection via **TensorFlow.js COCO-SSD** (80 COCO classes, browser-side, ~5 MB model, runs at ~2 FPS per the code).
- **Server (`server/`):** Express + Drizzle ORM + Postgres (`connect-pg-simple` sessions). Persists user's target list.
- **Shared (`shared/schema.ts`):** Drizzle schema for `targetItems` table.
- **Pages:** `Home.tsx` (camera + HUD), `Targets.tsx` (add/remove object classes from 80-class COCO list).

### 1.2 Detection pipeline (from `Home.tsx`)
- `setInterval(detect, 500)` — 2 FPS, battery-friendly.
- `model.detect(video)` returns `DetectedObject[]` with `bbox`, `class`, `score`.
- For each prediction, check if `prediction.class` matches anything in the user's target list. First match wins, rest ignored.
- **Bearing:** `bearing_deg = ((bbox_cx − frame_w/2) / frame_w) × 60` — hardcoded 60° FOV, no calibration, no lens-distortion correction.
- **Distance:** `distance_m = 100 / (bbox_height / frame_h × 100)` — an *arbitrary constant of 100* calibrated for "generic objects". This is worse than AURIGA's row-based LUT. The app explicitly documents it: *"Arbitrary constant for distance calc (calibrated for generic objects)"*.
- **TTS:** `SpeechSynthesisUtterance` (browser-native, Google/Apple/Samsung voice) — throttled to either a new target class or a 5-second debounce on the same target.

### 1.3 UX features worth copying
- **Target list** — the user picks what they want to find. This is the killer UX feature missing from AURIGA.
- **Camera preview as the whole screen**, decorative HUD corners, center reticle, bounding boxes drawn over detections.
- **Target boxes highlighted in accent colour**; non-target detections dimmed to 50% opacity.
- **Detection panel** — pops in from the top when a target is found with bearing (±) and distance, ±5° treated as "ahead", >5° as "right", <-5° as "left".
- **"Scanning…" idle state** with a pulsing dot.
- **TTS debouncing** so the user isn't spammed ("keys, 12 degrees left" repeated 20× a second).

### 1.4 UX features NOT worth copying
- The distance math is genuinely toy-tier (arbitrary 100 constant). AURIGA's row-based LUT + pitch correction is better once a real profile is contributed.
- Bearing is based on a hardcoded 60° FOV — we already compute real per-device HFOV in `HardwareHAL`.
- Server-side persistence via Postgres for a *target list* is architectural overkill; SharedPreferences or a tiny Room DB is enough for AURIGA.
- TFJS COCO-SSD is iffy on mobile — 80 classes, ~5 MB, not great at small objects, and TFJS in a WebView on Android is slower than native TFLite. We'd use YOLOv8n or EfficientDet-Lite TFLite directly.

### 1.5 What this tells us about your quality expectations
You want AURIGA to (a) **look like a tactical HUD with discrete detections** rather than a generic "scan everything" radar, (b) **track user-chosen targets** by class/name, (c) **narrate in natural language** ("dog detected, 23 degrees right"), (d) **throttle speech intelligently**, and (e) **feel responsive** even at 2 FPS by keeping the UI smooth. The current AURIGA 3-column scanner doesn't support any of this.

---

## 2. Competitor market study

Organised by tier. Price / platform / feature surface / accuracy / independence-from-third-parties all captured.

### 2.1 Tier A — Premium dedicated hardware ($2k-$6k)

| Product | Price | Platform | Target |
|---|---|---|---|
| **OrCam MyEye 3 Pro** | ~$4,500–$5,900 | Wearable (clips to glasses) | Blind / low-vision, full-independence seeker |
| **OrCam MyEye 2 Pro** | ~$3,500 | Same (older gen) | Same |
| **OrCam Read 3 / Read 5** | ~$2,000 | Handheld reader | Low-vision, reading-focused |
| **Envision Glasses — Read Edition** | $1,899 | Smart glasses (Google Glass EE2) | Reading-focused |
| **Envision Glasses — Home Edition** | ~$2,699 | Same hardware, full feature set | General blind/low-vision |
| **Envision Glasses — Professional Edition** | ~$3,499 | Same, commercial licence | Enterprise/workplace |

**OrCam MyEye 3 Pro features (offline-capable):**
- Instant text reading (any surface: book, menu, screen, label)
- Face recognition (trained per-user, stored locally)
- Product recognition (barcodes + visual)
- Banknote / currency recognition
- Hand-gesture control (point-to-read, swipe)
- Smart Magnifier + AI chat ("Find dessert", "Read the headlines")
- 20+ languages, 13 MP camera, 22.5 g, mounts magnetically to glasses
- 2-year warranty, Wi-Fi OTA updates
- **Runs offline** for core features (OrCam's big selling point)

**Envision Glasses features (on Google Glass EE2 hardware):**
- Instant Text, Scan Text, Batch Scan (read documents page-by-page)
- Call a Companion (video call to friend) / Call Aira (professional visual interpreter)
- Describe Scene (AI scene description — cloud-based)
- Detect Light / Recognise Cash / Detect Colors
- Find People / Find Objects / Teach a Face / Explore
- **Ally** — AI companion (Envision's in-house LLM assistant)
- Scheduled feature updates via subscription

**Key takeaway:** both OrCam and Envision are hardware-first, charging $2-6k for a dedicated wearable. Their software features are replicable; their moat is form factor + warranty + distribution through VA / insurance / vocational rehab channels.

### 2.2 Tier B — Free mobile apps (most of the actual user base)

| Product | Price | Platforms | Operator |
|---|---|---|---|
| **Microsoft Seeing AI** | Free | iOS + Android | Microsoft |
| **Google Lookout** | Free | Android only | Google |
| **Be My Eyes (incl. Be My AI)** | Free | iOS + Android + Ray-Ban Meta Glasses | Danish non-profit |
| **Supersense** | Freemium (~$50–100/yr premium) | iOS + Android | Mediate Labs |
| **Voice Dream Reader** | $10 one-time (Reader) / $80 (Scanner+Reader) | iOS + macOS | Voice Dream |

**Seeing AI channels:**
- **Short Text** — reads text instantly as it appears in frame
- **Document** — guided capture of full pages with layout preservation
- **Product** — barcode scanning → product database lookup
- **Person** — face + age/emotion + description of what they're wearing
- **Currency** — US/GBP/CAD/EUR/etc. banknotes
- **Scene** — generative AI describes what's in front of you (rich, paragraph-long)
- **World** — experimental LiDAR-augmented spatial audio on iOS
- **Colour**, **Light** (brightness detector for finding windows / lamps)
- **Handwriting** recognition (new 2023–2024)
- **Chat to your documents** — ask questions about a scanned document ("what's on the menu under $10?") — generative AI
- 36 languages by end of 2024
- **Dependency:** Azure cloud for generative features; offline for short-text/barcode/colour/light

**Google Lookout:**
- Text mode / Documents mode (PDF capable)
- Food label (packaged-food recognition via barcode + visual)
- Currency mode
- Explore mode (scene description)
- **Image Question** — upload any photo, ask questions about it (Gemini-powered)
- Ships with Android accessibility stack; deep TalkBack integration
- **Dependency:** Google cloud services for generative features

**Be My Eyes / Be My AI:**
- **Volunteer video calls** — 10M+ volunteers in 150+ countries, instant connection (Be My Eyes's foundational feature)
- **Be My AI** — GPT-4V-powered image description and follow-up Q&A
- **Specialized support** — direct line to partner company customer service (Microsoft Disability Answer Desk, Google, etc.)
- **Ray-Ban Meta Glasses integration** — call a volunteer or AI directly through the glasses
- 180+ languages, 100% anonymous
- **Dependency:** GPT-4V (OpenAI) + Twilio WebRTC + their own network
- All free to end-users, monetized via B2B "Be My Eyes for Business" customer-service integration

**Supersense:**
- Smart Scanner — auto-detects whether user is scanning short text / document / barcode / currency
- Smart Guidance — *voice-guided camera framing* ("move the camera to the left", "hold steady") — this is a killer accessibility feature
- Quick Read / Document / Multipage Scanning / Currency / Barcode
- Object Explorer and Find — tell me what's around me
- Import PDFs and images from other apps via share sheet
- Works mostly offline
- **Dependency:** on-device ML (proprietary mobile models)

### 2.3 Tier C — Smart canes + physical-assist devices

| Product | Price | Notes |
|---|---|---|
| **WeWALK Smart Cane 2** | $595–$850 USD | Ultrasonic obstacle detection above waist, voice nav, AI assistant (built-in), flashlight, WeASSIST live interpreter service |
| **Glide by Glidance** | ~$1,500 (pre-order) | Motorised two-wheeled self-guided mobility aid, AI-steered, finds doors/elevators/stairs, obstacle avoidance |
| **Biped AI** | ~$3,500 | Chest-worn self-driving-car-style sensor harness; haptic + audio cues for obstacles |

### 2.4 Tier D — Visual-interpreter subscription services

| Product | Price | Notes |
|---|---|---|
| **Aira** | $26–$1,160/month (tiered by minutes) | Live human visual interpreters via video call; free in partner "Access Locations"; recently added **Aira ASL** for Deaf-blind users |
| **Be My Eyes (volunteer side)** | Free | Peer volunteers |

### 2.5 Tier E — Book-reading / TTS-reader apps (for your "voice reading in books" requirement)

| Product | Price | Notes |
|---|---|---|
| **Voice Dream Reader** | $10 one-time | PDF, EPUB, DOCX, Bookshare, NFB-NEWSLINE; 200+ voices across 30 languages; offline; pronunciation dictionary; iOS + macOS only |
| **Voice Dream Scanner** | $10 one-time | Camera OCR → cleaned document → read aloud |
| **Speechify** | $139/year+ | Cloud TTS with celebrity voices (Gwyneth Paltrow, Snoop Dogg); browser + iOS + Android; subscription; requires account |
| **@Voice Aloud Reader** | Free (ads) | Android; uses system TTS; reads web pages, PDFs, EPUBs |
| **KNFB Reader (retired → OneStep Reader)** | $99 | Camera OCR for books; field-sequence technology designed for blind users; tilt guidance for page alignment |
| **Bookshare** | $50/year (US accredited disability) | 1.1 M accessible book catalogue (DAISY, BRF, MP3, EPUB); legally free for qualifying US users under Chafee Amendment |
| **NLS BARD (Library of Congress)** | Free for qualifying US patrons | Talking-book + Braille library, 140k+ titles, human-narrated + DAISY |

**Key observation:** **none of these is an integrated part of a navigation stack.** Blind users currently juggle 3–5 apps: Seeing AI for scanning, Be My Eyes for help, Voice Dream Reader for books, Google Maps for nav, WeWALK companion app for cane. AURIGA could be *the first integrated stack* — that's a strategic wedge.

---

## 3. AURIGA feature-gap matrix

Colour-coded against competitors. ✅ = AURIGA has it, ⚠️ = partial / stub, ❌ = missing. Rows ordered by user-impact weight (high to low, per blind-user forums & Reddit r/Blind).

| # | Feature category | AURIGA today | OrCam 3 | Envision Home | Seeing AI | Lookout | Be My AI | Supersense | Voice Dream |
|---|---|---|---|---|---|---|---|---|---|
| 1 | **OCR / text reading** (signs, labels, menus) | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 2 | **Full-document reading** (books, PDFs, EPUBs) | ❌ | ✅ | ✅ | ✅ | ✅ | ⚠️ | ✅ | ✅ |
| 3 | **Scene description** (paragraph narration) | ❌ | ✅ (AI chat) | ✅ | ✅ | ✅ | ✅ | ⚠️ | ❌ |
| 4 | **Target-based object finding** ("find my keys") | ❌ | ✅ | ✅ (Find Objects) | ❌ (implicit) | ❌ | ⚠️ | ✅ (Find) | ❌ |
| 5 | **Face recognition** (known people) | ❌ | ✅ | ✅ (Teach a Face) | ✅ | ❌ | ⚠️ (describes) | ❌ | ❌ |
| 6 | **Currency / banknote recognition** | ❌ | ✅ | ✅ | ✅ | ✅ | ⚠️ | ✅ | ❌ |
| 7 | **Barcode / product lookup** | ❌ | ✅ | ❌ | ✅ | ✅ (Food) | ⚠️ | ✅ | ❌ |
| 8 | **Colour identification** | ❌ | ❌ | ✅ | ✅ | ❌ | ⚠️ | ❌ | ❌ |
| 9 | **Light-level detection** (find windows / lamps) | ⚠️ (`isLowLight`) | ❌ | ✅ | ✅ | ❌ | ⚠️ | ❌ | ❌ |
| 10 | **Obstacle / navigation avoidance** | ✅ (3-col scan) | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| 11 | **Distance / bearing readouts** | ✅ (jittery) | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| 12 | **Overhead object / SkyShield** | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| 13 | **Voice-guided camera framing** ("tilt left") | ❌ | ✅ | ✅ | ✅ | ✅ | ❌ | ✅ | ⚠️ (scanner) |
| 14 | **Handwriting recognition** | ❌ | ✅ (Smart Mag) | ❌ | ✅ | ❌ | ✅ | ❌ | ❌ |
| 15 | **AI chat / "ask about the image"** | ❌ | ✅ | ✅ (Ally) | ✅ | ✅ (Gemini) | ✅ (GPT-4V) | ❌ | ❌ |
| 16 | **Multi-language TTS / STT** | ⚠️ (en_US only) | ✅ (20+) | ✅ (many) | ✅ (36) | ✅ (many) | ✅ (180+) | ⚠️ | ✅ (30) |
| 17 | **Human-interpreter fallback** (video call) | ❌ | ❌ | ✅ (Aira) | ❌ | ❌ | ✅ (volunteers) | ❌ | ❌ |
| 18 | **Gesture / hands-free control** | ❌ | ✅ (point/swipe) | ⚠️ | ⚠️ | ⚠️ | ❌ | ✅ (auto) | ❌ |
| 19 | **Pedestrian GPS navigation** | ❌ | ❌ | ⚠️ | ❌ | ❌ | ❌ | ❌ | ❌ |
| 20 | **Public-transit / indoor nav** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| 21 | **Smart-cane integration** (WeWALK) | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| 22 | **Haptic feedback** | ✅ (stub) | ❌ | ⚠️ | ❌ | ❌ | ❌ | ❌ | ❌ |
| 23 | **Community-contributed calibration** | ✅ (new) | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| 24 | **Open-source / self-hosted** | ✅ (private repo) | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| 25 | **On-device everything** (independence) | ✅ (today) | ⚠️ (offline-capable) | ❌ (cloud for Ally/Aira) | ❌ (Azure) | ❌ (Google) | ❌ (OpenAI) | ⚠️ (on-device) | ⚠️ (premium voices cloud) |
| 26 | **Price to end user** | $0 | $4,500+ | $1,899+ | $0 | $0 | $0 | Freemium | $10+ |

### Rows where AURIGA is uniquely strong (moat candidates)
- **#10, #11, #12 obstacle + distance + SkyShield** — no competitor does real-time row-based obstacle distance on a phone camera. This is uniquely ours (though jittery, which is fixable).
- **#23 Community calibration** — nobody else has a crowdsourced per-phone calibration pipeline. Keep investing here.
- **#24, #25 Independence / self-hosted / on-device** — the only product in the space that can genuinely claim zero third-party cloud dependency once we complete the plan in §4.

### Rows where AURIGA is severely behind (must-close gaps)
- **#1–#8, #13–#16** — these are table-stakes for any accessibility app. Today AURIGA has none of them.
- **#15 AI chat** — this is what makes Seeing AI / Lookout feel magical. On-device replacement exists (Moondream 2, Qwen-VL 2B) but is heavy — needs 3-4 GB RAM free.
- **#17 human fallback** — expensive to build a volunteer network; cheaper to partner with Be My Eyes (they have a public API for B2B partners).

---

## 4. Independence audit — current third-party dependencies & proposed replacements

### 4.1 Current deps in the APK (from `app/build.gradle` + code scan)
```
androidx.appcompat:appcompat:1.6.1              ← OK (Android-SDK-layer, Apache 2.0, no cloud)
com.google.android.material:material:1.9.0      ← OK (Apache 2.0, Android-SDK-layer)
androidx.constraintlayout:constraintlayout:2.1.4 ← OK
android.speech.tts.TextToSpeech                 ← Samsung/Google TTS engine (hidden dep)
Android Camera2 API                             ← OK (SDK, on-device)
Android SensorManager                           ← OK (SDK, on-device)
```

**Hidden cloud dependencies:**
- `TextToSpeech` on a Samsung phone routes through Samsung's TTS engine (proprietary, sends nothing but is closed). On some phones, the default engine is Google's, which *can* download voice packs from Google servers.
- Website currently loads `calibration_profiles.json` from `drakosanctis.com` / Netlify — this is *your own* hosting, so it's fine for the independence rubric, but if those ever go down the app falls back to synthetic LUT.

### 4.2 Proposed independent / open-source stack (what we add)

| Capability | Library | Licence | On-device? | Notes |
|---|---|---|---|---|
| **On-device OCR** | **PaddleOCR (PP-OCRv4 mobile)** via NCNN | Apache 2.0 | ✅ | 8 MB model, 100+ languages, beats Tesseract by a wide margin on natural images. Alternative: ML Kit Text Recognition (Google, free, on-device but closed-source). |
| **On-device OCR (alt)** | Tesseract 5 + `tess-two` | Apache 2.0 | ✅ | Mature, battle-tested, weaker on natural/curved text. Good fallback for pure document OCR. |
| **Offline neural TTS** | **Piper TTS** (via Sherpa-ONNX runtime) | MIT | ✅ | Natural-sounding neural voices, 30+ languages, 20-80 MB per voice. Replaces Samsung TTS. Ships as `.onnx` asset in the APK. |
| **Offline TTS (alt)** | **RHVoice** | LGPL | ✅ | Lighter (~5 MB per voice), more robotic sound, very efficient on low-end. |
| **Object detection** | **YOLOv8n** via NCNN or TFLite | AGPL (weights) or retrain on COCO yourself | ✅ | 6 MB quantised, 15–25 FPS on mid-range Android. 80 COCO classes + retrainable for custom classes. |
| **Object detection (alt)** | **EfficientDet-Lite0** via TFLite | Apache 2.0 | ✅ | Google-built, smaller (4 MB), fewer FPS, more permissive licence. |
| **Monocular depth** | **Lite-Mono** (TFLite) or **MiDaS v2.1-small** | MIT (MiDaS) / Apache (Lite-Mono) | ✅ | Replaces the "no real depth" gap in SkyShield. MiDaS small is 21 MB and runs at ~3-5 FPS on mid-range Android. |
| **Face recognition** | **InsightFace (ArcFace)** mobile model via NCNN | MIT (code) / non-commercial (weights) | ✅ | Need to retrain on a permissive dataset (e.g. LFW) for commercial use. |
| **Face recognition (alt)** | **Google ML Kit Face Detection** | free, closed-source, on-device | ✅ | Faster to integrate but fails the independence rubric. |
| **Barcode / QR** | **ZXing** (`com.journeyapps:zxing-android-embedded`) | Apache 2.0 | ✅ | Nothing cloud, battle-tested. |
| **Product database** | **Open Food Facts API** + offline mirror | ODbL | ✅ (cached) | 2M+ products, CC-BY licensed photos. Self-host the mirror on your own server. |
| **Currency recognition** | Train a small CNN (MobileNet-V3-small, 2 MB) on per-currency images | — | ✅ | Start with USD/EUR/GBP/KES; crowdsource images via your calibration-library flow. |
| **Scene description (VLM)** | **Moondream 2** (1.9 B params, 4-bit GGUF via `llama.cpp` Android) | Apache 2.0 | ✅ | 900 MB download, 2-4 s per query on Snapdragon 8-gen. Optional feature, gated behind "You have a high-end phone — download Moondream?" prompt. |
| **Scene description (alt)** | **Qwen2-VL 2B 4-bit** | Apache 2.0 | ✅ | 1.4 GB, strong on OCR-heavy scenes. |
| **Scene description (fallback)** | A template-based describer using YOLO+depth output | — | ✅ | Works on ALL phones: "I see 2 people 1.5 m ahead, a chair on your right at 2 m, and a bright light source behind you." Deterministic, no AI needed. |
| **Voice wake word / hands-free** | **Picovoice Porcupine** or **openWakeWord** | Apache 2.0 (openWakeWord) | ✅ | "Hey AURIGA" triggers. openWakeWord is fully open; Porcupine has a free personal tier but paid commercial. |
| **Speech recognition (commands)** | **Vosk** or **whisper.cpp** (tiny/base) | Apache 2.0 | ✅ | Vosk is tiny (~50 MB for English) and fast; whisper-tiny is ~75 MB and more accurate. |
| **EPUB/PDF parsing** | **ApacheFOP** or lightweight custom EPUB parser | Apache 2.0 | ✅ | Parse EPUB zip → XHTML → plain text → Piper TTS. PDFs via `pdfbox-android` (Apache 2.0). |
| **Maps / pedestrian nav** | **OsmAnd core** (or direct Overpass-API OSM + custom routing) | GPL | ✅ (offline tiles pre-downloaded) | Fully replaces Google Maps. Needs offline regions downloaded. |
| **Public-transit** | **GTFS feeds + OpenTripPlanner** | Apache 2.0 | ⚠️ | Self-hosted OTP instance per region. Big infra cost. |
| **Indoor nav** | **Beacons (Eddystone / iBeacon) + your own venue-mapping tool** | — | ✅ | Own your own BLE beacon ecosystem. Partner with cafes / libraries. |
| **Human fallback (optional)** | **Build your own volunteer network + WebRTC via self-hosted Jitsi / LiveKit** | Apache 2.0 | ❌ (requires signalling server) | Alternative: partner with Be My Eyes (their B2B API is free for accessibility partners). |
| **Crash logging** | Already done in-house (`CrashReportActivity`) | — | ✅ | No Firebase Crashlytics needed — we already win here. |
| **Analytics** | **PostHog self-hosted** or **Plausible** or none | MIT / AGPL | ✅ | If analytics are needed. Avoid Google/Firebase. |
| **Push notifications** | **UnifiedPush** (open-source push) or WebSocket to your own server | Apache 2.0 | ⚠️ | Bypasses Firebase Cloud Messaging. Requires server. |

### 4.3 Independence scorecard after implementation

| Subsystem | Today | After Phase 1 | After Phase 3 |
|---|---|---|---|
| OCR | — | PaddleOCR | PaddleOCR |
| TTS | Samsung/Google | Piper | Piper |
| Object detection | — | YOLOv8n / EfficientDet-Lite | YOLOv8n |
| Depth | — | — | Lite-Mono |
| Scene description | — | Template-based | Moondream 2 (opt-in) |
| Voice commands | — | — | Vosk |
| Wake word | — | — | openWakeWord |
| Maps | — | — | OsmAnd offline |
| Crash logging | in-house | in-house | in-house |
| Product DB | — | Open Food Facts mirror | Own mirror + submissions |
| Face DB | — | — | Local-only (no upload) |
| Analytics | none | none | Plausible (self-hosted) |

**Every capability is either on-device, open-source, or self-hosted under your control.** No OpenAI, no Google Cloud, no Microsoft Azure, no AWS, no Firebase.

---

## 5. Prioritised roadmap (proposed)

Organised by **phase**, each phase a single PR or small group of PRs. I've weighted each feature on (impact × effort × independence-preservation) and sorted so the highest-leverage work lands first.

### Phase 0 — emergency: fix v0.1.3-jitter-fix "POOR" reports (blocker, 1–2 days)
Before building new features, get the existing nav path working well enough that it doesn't actively hurt the product.
- **P0.1 Diagnostic overlay** — add a debug HUD showing raw LUT lookup, pitch radians, confidence, and GhostAnchor shift per frame, gated behind a long-press on the AURIGA NAVI label. Lets us see on the A07 which smoother is flickering.
- **P0.2 Request actual metrics from you** — "distance reads 2.7 m for something at 1.0 m" vs "distance stable but flickering ±0.3 m" are two different bugs.
- **P0.3 Depending on #P0.2** — probably one of:
  - Synthetic LUT on A07 is still the dominant bias. **Fix:** ship a manually-captured A07 profile as the default built into the APK, not relying on community contributions yet.
  - Pitch correction isn't working. **Fix:** verify with on-screen pitch readout.
  - Frame-resolution mismatch: A07 is 1600×720 but we bitmap-read at 640×480. **Fix:** compute scale factor correctly.

### Phase 1 — the **Target Locator** feature (the Object-Locator paradigm) (1 week)
Delivers #4 "target-based object finding" which is the thing you liked about the reference app.
- **P1.1** Add YOLOv8n TFLite model + NCNN/TFLite runtime as a new `aar` dependency. Ship `yolov8n_int8.tflite` (6 MB) as APK asset.
- **P1.2** New `TargetDetector` class. Runs at 2 FPS (battery-aware), returns `List<Detection>{class, bbox, confidence}`.
- **P1.3** New "Targets" activity with searchable list of 80 COCO classes + ability to add/remove. SharedPreferences-backed, no DB.
- **P1.4** Extend HUD: when a target class is detected, show its bounding box (green) + side panel with class + bearing + distance. Non-targets dimmed.
- **P1.5** TTS announce: *"keys detected, 15 degrees right, 1 meter"* — 5-s debounce per class. Integrate with existing `DrakoVoice`.
- **P1.6** Replace current `ImageProcessor.scanFrame` 3-column scan as the *primary* nav detector when a target is set; fall back to 3-col scan only when no targets are selected.
- **P1.7** Distance for detections: use YOLO bbox height + your existing `FiducialLUT` row-based math (pass the bbox-bottom row as `baseY`). Bearing: bbox centreX → pinhole. Same formulas, different input.

### Phase 2 — OCR + full document reading + TTS upgrade (1 week)
Delivers #1 (OCR), #2 (document reading), #16 (multi-language TTS) in one shot.
- **P2.1** Swap `android.speech.tts.TextToSpeech` for **Piper TTS via Sherpa-ONNX Android**. Ship one English voice (`en_US-libritts-medium`, 50 MB) in the APK; offer additional voice downloads from your own mirror.
- **P2.2** Add **PaddleOCR (PP-OCRv4 mobile) via NCNN**. Ship detection + recognition models (~15 MB combined) as assets.
- **P2.3** New "Read" activity — big button, camera view, user holds phone over any text, single tap → OCR → Piper TTS reads aloud with VoiceOver-style playback controls (play/pause, rewind 10 s, speed).
- **P2.4** **Smart Guidance** (Supersense-style): detect whether text is in frame; if not, voice-guide: *"move camera left", "hold steady", "too close"*. Implemented as image-quality heuristics + PaddleOCR detection-model confidence.
- **P2.5** Document mode: user can capture multiple pages, app stitches them into one long document, reads in order. Save to `/sdcard/AURIGA/documents/` as plain-text `.txt` + original images.

### Phase 3 — voice reading of books (EPUB/PDF) (3–5 days)
Delivers #2 end-to-end for the "reading a book" use case.
- **P3.1** Add `androidx.documentfile` for SAF (Storage Access Framework) — user picks an EPUB or PDF from their device.
- **P3.2** EPUB: unzip → parse OPF manifest → walk spine → extract chapter XHTML → strip tags → queue for Piper TTS.
- **P3.3** PDF: use `pdfbox-android` (Apache 2.0) for text-layer extraction; fall back to PaddleOCR on pages that are image-only (scanned PDFs).
- **P3.4** **Bookshelf activity** — shows imported books + reading progress. SharedPreferences for position.
- **P3.5** Player UI: play/pause, chapter nav, speed (0.8×–2.0×), sleep timer.
- **P3.6** Optional: integrate **NLS BARD** / **Bookshare** feeds for US users who qualify — via their public OPDS catalogs (no closed API needed).

### Phase 4 — scene description + object finding + template-based describer (1–2 weeks)
Delivers #3 (scene description), #4 polish, #7 (barcode/product), #6 (currency).
- **P4.1** **Template-based describer**: run YOLOv8n + per-object depth lookup → generate "I see X at Y meters" sentences. Works on every phone, no AI heavy lifting needed.
- **P4.2** Add **ZXing** for barcode/QR scanning → query **Open Food Facts** mirror (self-hosted) → speak product name + ingredients + allergens.
- **P4.3** Train a currency classifier (MobileNetV3-small, ~2 MB per currency). Start with KES / USD / EUR / GBP. Use the existing calibration-library contribution flow to crowdsource training images for other currencies.
- **P4.4** (Optional, gated behind "Download expanded AI — 900 MB") Add **Moondream 2** 4-bit GGUF via llama.cpp Android bindings for paragraph-level scene descriptions on high-end phones. Off by default; user explicitly opts in in Settings.

### Phase 5 — face recognition + colour + handwriting + light (1 week)
Delivers #5, #8, #9, #14 — Seeing-AI-equivalent feature set.
- **P5.1** **InsightFace ArcFace** mobile model for face embeddings. "Teach a Face" UX: user holds phone in front of a friend, names them, app stores embedding locally. At runtime, matches faces in frame against stored embeddings.
- **P5.2** **Colour detector**: sample a 32×32 patch from the centre of the frame → HSV → nearest named colour (CSS3 / X11 palette). Speak on demand.
- **P5.3** **Handwriting** is hard — ship as "experimental" feature, use PaddleOCR with the handwriting model (PaddleOCR ships one).
- **P5.4** **Light detector**: use existing `isLowLight` infrastructure + brightness-centroid tracker (GhostAnchor) to tell users where the bright patches are ("bright light on your upper-right, probably a window").

### Phase 6 — voice commands + wake word + accessibility polish (1 week)
Delivers #13 voice-guided framing + #18 hands-free control + #16 multi-language.
- **P6.1** Integrate **Vosk** for offline speech recognition. Trigger by tapping the screen (simple) or eventually by wake word.
- **P6.2** Core commands: "read text", "find keys", "describe scene", "stop", "louder", "slower", "next page".
- **P6.3** Integrate **openWakeWord** with a custom wake phrase ("Hey AURIGA"). Optional, off by default — big CPU cost.
- **P6.4** Add 5+ additional Piper TTS voices (Spanish, French, Swahili, Arabic, Mandarin) — download from your mirror.

### Phase 7 — navigation (GPS + indoor) (2–3 weeks)
Delivers #19, #20 — pedestrian navigation.
- **P7.1** Ship **OsmAnd** offline tile regions (user picks which regions to pre-download).
- **P7.2** Integrate **Valhalla** or **GraphHopper** routing (both Apache 2.0, on-device-capable) for pedestrian routing.
- **P7.3** Turn-by-turn voice nav through Piper TTS: "in 20 meters, turn left onto Kenyatta Avenue".
- **P7.4** Indoor beacons — partner with 1–2 Nairobi venues (malls, schools) to install cheap BLE beacons; AURIGA detects beacon UUIDs and speaks location ("you are at Nakumatt Junction entrance 3, stairs are 5 meters ahead").

### Phase 8 — smart-cane integration + human fallback (optional, post v1.0)
- **P8.1** BLE protocol to pair with **WeWALK** or a cheap generic smart cane (we could even design our own ~$50 cane). Haptic motor in the cane buzzes when AURIGA detects an obstacle ahead.
- **P8.2** **Be My Eyes B2B API** — partner integration so AURIGA users can one-tap connect to a Be My Eyes volunteer. Free for accessibility partners.

---

## 6. What I recommend you do next

1. **Confirm the paradigm shift.** The biggest decision is whether AURIGA stays a *pure navigation aid* or becomes an *integrated vision-assistance suite* (navigation + OCR + object finding + scene description + book reading). I'm assuming the latter based on your message, but it's worth saying out loud. If yes, Phase 1 + Phase 2 + Phase 3 in that order is the right first 3 weeks.

2. **Before Phase 1, spend 1 day on Phase 0** so the current nav path works well enough to ship alongside the new features. I can start that immediately if you want — a debug overlay + an A07 default profile baked into the APK.

3. **Pick a branding answer for the 4 product variants (Navi / Sentinel / Aero / Industrial).** The roadmap above collapses most features into *Navi* because it's the accessibility-first variant. If Sentinel / Aero / Industrial are supposed to get different subsets, tell me now and I'll slot features into the right flavour (e.g. Aero gets navigation-only, Navi gets the full suite, Sentinel gets face-recognition-for-security, Industrial gets barcode + scene-description).

4. **Confirm independence rubric.** My proposal eliminates every third-party cloud. But it keeps two on-device deps that are technically "Google": the Android SDK itself and the Material Components library. Those are unavoidable unless you fork to a pure AOSP base. Is that acceptable, or do you want me to go further (e.g. strip Material, use custom widgets only)?

5. **Confirm the scene-description trade-off.** Running Moondream 2 or Qwen-VL on-device gives genuine GPT-4V-quality scene description, but it's a 1–2 GB model download and 3–4 s per query. Template-based description is instant but reads like *"I see 2 people, 1 chair, 1 dog"* — less rich. Both or just one?

6. **Confirm you want the Target Locator UX first.** It's the feature from the reference app you sent. It's also the single highest-leverage addition because it reuses all the existing distance/bearing code and gives AURIGA an "object finder" feature that Seeing AI / Lookout / Be My AI all notably lack.

7. **Confirm voice book reading scope.** I've proposed EPUB + PDF (including scanned PDFs via OCR). Do you also want us to parse DAISY books (the US NLS BARD format)? That's a slightly different parser but same runtime.

---

## Appendix A — further reading (sources used)

- OrCam MyEye Pro 3 / MyEye 2 Pro / Read 3 / Read 5 product pages on orcam.com
- Envision Glasses Home / Read / Professional editions at letsenvision.com
- Microsoft Seeing AI accessibility blog (Android launch, 2023-12-04)
- Be My Eyes + Be My AI product pages (bemyeyes.com)
- Google Lookout on Play Store (500k+ downloads, 4.0 star, 4.42k reviews)
- Supersense on supersense.app + App Store + Google Play
- Aira.io subscription pages (Silver / Gold / Platinum tiers)
- WeWALK Smart Cane 2 product page + Florida Vision Technology pricing
- Glidance Glide product announcement (2024-07-12)
- Voice Dream Reader voicedream.com + Speechify speechify.com
- PaddleOCR GitHub (Apache 2.0, PP-OCRv4, 100+ languages)
- Piper TTS / OHF-Voice GitHub + VoxSherpa-TTS Android (GPL-3.0)
- FastDepth (MIT CSAIL), MiDaS-Android (shubham0204), Lite-Mono
- Moondream 2 / Qwen2-VL 2B on Hugging Face
- Vosk / openWakeWord (Apache 2.0)
- Open Food Facts (ODbL)
- NLS BARD + Bookshare OPDS catalogues
