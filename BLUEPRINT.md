# AURIGA — System Blueprint

**DrakoSanctis Auriga Ecosystem**
Spatial intelligence platform for blind and low-vision users.

---

## 1. Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                        AURIGA ECOSYSTEM                             │
│                                                                     │
│   ┌──────────────────────────┐   ┌──────────────────────────────┐  │
│   │       PWA (Preview)      │   │   Android App (Primary)      │  │
│   │   AURIGA/web_deploy/     │   │   AURIGA/app/src/main/       │  │
│   │                          │   │                              │  │
│   │  jarvis.js               │   │  AurigaVoiceEngine.java      │  │
│   │  auriga-skills.js        │   │  AurigaSkillEngine.java      │  │
│   │  auriga-voice-nav.js     │   │  AurigaKnowledge.java        │  │
│   │  auriga-llm.js           │   │  AurigaMemoryStore.java      │  │
│   │  auriga-memory.js        │   │  YoloDetector.java           │  │
│   │  auriga-voice.js         │   │  DrakoVoice.java             │  │
│   │  auriga-swipe.js         │   │  HapticManager.java          │  │
│   │                          │   │  AurigaAlarmReceiver.java    │  │
│   └──────────────────────────┘   └──────────────────────────────┘  │
│                                                                     │
│   ┌─────────────────────────────────────────────────────────────┐  │
│   │                    Node.js Server                           │  │
│   │                    server.js (port 5000)                    │  │
│   │          Serves PWA static files from web_deploy/           │  │
│   └─────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 2. Design Principles

| Principle | Implementation |
|---|---|
| **Offline-first** | Every feature has a no-internet fallback. Skills degrade gracefully. |
| **Voice-first** | No feature requires a screen. Everything is accessible by voice alone. |
| **Android-superior** | Android uses real OS APIs (AlarmManager, sensors, notifications). PWA is preview-only. |
| **Privacy-by-default** | All AI inference runs on-device. No user data sent to servers unless explicitly required. |
| **OpenClaw agent loop** | match → tool use → observe → respond. Skills are first-class plugins. |

---

## 3. PWA Stack

### Entry Point
```
server.js → express static → AURIGA/web_deploy/
```

### Page Map
| Page | Purpose |
|---|---|
| `index.html` | Ecosystem home & feature overview |
| `assistant.html` | Primary Jarvis voice interface |
| `chat.html` | Text + voice chat with LLM |
| `locator.html` | Real-time YOLO object detection (COCO-SSD via WebGL) |
| `reader.html` | DrakoVoice OCR reader (Tesseract.js) |
| `locator-targets.html` | Target object configuration |
| `calibration-library.html` | Distance calibration profiles |
| `skills.html` | Voice skills directory (26+ skills) |
| `camera-connect.html` | External camera / Pi camera bridge |
| `feedback.html` | Bug reports & suggestions |
| `about.html` | Mission & contact |

### Core JS Modules (load order matters)
```
auriga-announce.js    — page announcer, ARIA live regions
auriga-memory.js      — IndexedDB conversation + profile store
auriga-voice.js       — voice overlay UI (mic ring, transcript bubble)
auriga-skills.js      — 26-skill pack (timers, weather, calc, home automation…)
jarvis.js             — OpenClaw-style agent loop: match → KB → LLM → fallback
auriga-llm.js         — Llama 3.2 1B via WebLLM + WebGPU (fully offline after cache)
auriga-swipe.js       — serpentine swipe → voice trigger gesture
auriga-voice-nav.js   — PWA voice navigation layer (focus speak, reading mode, shortcuts)
```

### On-Device LLM (PWA)
- **Model**: Llama 3.2 1B Instruct (WebLLM / MLC, ~700 MB cached in browser)
- **Runtime**: WebGPU via `@mlc-ai/web-llm`
- **Consent flow**: First-use voice prompt → user opts in → background download
- **Fallback chain**: WebLLM → Free AI API → Knowledge Base → Offline fallback

---

## 4. Android App Stack

### Package
`com.drakosanctis.auriga`

### Core Components
| Class | Role |
|---|---|
| `MainActivity` | App entry point, navigation host |
| `AurigaVoiceEngine` | SpeechRecognizer + TTS + wake word + command routing |
| `AurigaSkillEngine` | **OpenClaw-style skill dispatcher** — 24 skills, online/offline aware |
| `AurigaAlarmReceiver` | BroadcastReceiver for AlarmManager alarms + reminders |
| `AurigaKnowledge` | Pattern-based offline knowledge base (~50 facts) |
| `AurigaMemoryStore` | SQLite conversation history + user profile extraction |
| `AurigaVoiceService` | Foreground service for always-on wake word detection |
| `YoloDetector` | TFLite YOLO object detection (camera stream) |
| `DrakoVoice` | Custom TTS with pitch/rate control and phoneme tuning |
| `HapticManager` | Rich haptic patterns (object proximity, alarms, confirmations) |
| `GodsEyeOrchestrator` | Multi-sensor fusion (GPS + camera + accelerometer + compass) |
| `TriangulationEngine` | Distance estimation from bounding box + device geometry |
| `SerpentineGestureDetector` | Figure-8 swipe → voice activation |
| `SonarManager` | Ultrasonic distance (Arduino/HC-SR04 via BLE) |
| `SentinelNode` | Pi/Raspberry Pi camera stream consumer |

### Activity Map
| Activity | Purpose |
|---|---|
| `MainActivity` | Home + global nav drawer |
| `LocatorActivity` | Real-time object detection + TTS obstacle announcements |
| `ReaderActivity` | Camera OCR → TTS reading |
| `TargetsActivity` | Tracked object configuration |
| `CalibrationWalkActivity` | 10-point distance calibration walk |
| `VoiceSetupActivity` | First-run assistant name setup |
| `CameraStreamActivity` | External camera stream (Pi/RTSP) |
| `HelpActivity` | Spoken help guide |
| `FeedbackActivity` | In-app bug/feature submission |

### Android vs PWA Feature Matrix
| Feature | PWA | Android |
|---|---|---|
| Object detection | ✅ COCO-SSD WebGL | ✅ YOLO TFLite (faster, camera API 2) |
| OCR / Text reading | ✅ Tesseract.js | ✅ ML Kit (on-device, faster) |
| Voice commands | ✅ Web Speech API | ✅ Android SpeechRecognizer (more reliable) |
| Always-on wake word | ✅ Continuous recognition | ✅ **Foreground service** (survives screen off) |
| On-device LLM | ✅ Llama 1B via WebGPU | 🔧 llama.cpp JNI (planned) |
| Alarms | ✅ setTimeout (app must be open) | ✅ **AlarmManager** (fires when app killed) |
| Reminders | ✅ setTimeout | ✅ **AlarmManager + Notifications** |
| Timers | ✅ setInterval | ✅ CountDownTimer + notifications |
| Haptics | ✅ navigator.vibrate | ✅ **Rich VibrationEffect patterns** |
| GPS | ✅ navigator.geolocation | ✅ **Fused location, high accuracy** |
| Compass | ✅ DeviceOrientation | ✅ **SensorManager TYPE_ORIENTATION** |
| Battery | ✅ Battery API | ✅ BroadcastReceiver (exact level) |
| Volume control | ❌ | ✅ AudioManager |
| Home automation | ✅ Custom DOM events | ✅ Local broadcast + Intent |
| News headlines | ✅ RSS via AllOrigins | ✅ RSS fetch (background thread) |
| Weather | ✅ Open-Meteo + GPS | ✅ Open-Meteo + FusedLocation |
| Sonar (ultrasonic) | ❌ | ✅ BLE Arduino/HC-SR04 |
| Pi camera stream | ✅ WebRTC | ✅ RTSP + SentinelNode |
| Offline mode | ✅ Service Worker cache | ✅ **All core features work offline** |

---

## 5. OpenClaw Integration

OpenClaw (https://github.com/openclaw/openclaw) is a TypeScript AI agent OS.
AURIGA ports its **concepts** natively — not the TypeScript code.

### Concepts Ported

| OpenClaw Concept | AURIGA PWA Implementation | AURIGA Android Implementation |
|---|---|---|
| Skill registry | `Jarvis.registerSkill({name, match, handle})` | `AurigaSkillEngine.registerSkills()` |
| Agent loop | `jarvis.js ask()`: command → KB → LLM → fallback | `AurigaVoiceEngine.routeCommand()` → `AurigaSkillEngine.dispatch()` → KB → reply |
| Memory store | `AurigaMemory` (IndexedDB, semantic search) | `AurigaMemoryStore` (SQLite, async BG thread) |
| Context injection | `AurigaMemory.getContext()` → LLM system prompt | `AurigaMemoryStore.getContext()` → LLM prompt |
| Tool use | Skills handle weather, calc, news etc. | `AurigaSkillEngine` dispatches to tool handlers |
| Multi-model routing | Llama 1B → Free API → KB → fallback | KB → online API → fallback |
| Plugin system | `Jarvis.registerSkill()` — any script can add skills | `AurigaSkillEngine.registry` list |
| MCP-style events | `window.dispatchEvent('auriga:home', ...)` | `ctx.sendBroadcast(homeIntent)` |

---

## 6. Skill Pack (26 PWA + 24 Android)

### Timers & Alarms
- Set timer (with halfway/10-second spoken warnings)
- Cancel timer
- List timers
- Set alarm (PWA: setTimeout; Android: AlarmManager — fires when app killed)
- Cancel alarm

### Reminders
- Set reminder ("remind me to X in Y minutes")
- List reminders
- Clear reminders
- Stopwatch (start/stop/lap/reset)
- Countdown (spoken count-down from N)

### Math & Conversion
- Calculator (arithmetic, %, square root, powers)
- Unit converter (temperature, distance, weight, volume)
- Random number / dice roll / coin flip

### Weather & Location
- Current weather + 2-day forecast (Open-Meteo, GPS, free, no API key)
- GPS coordinates (lat/lon, accuracy, speed, altitude)
- Compass heading (degrees + cardinal direction)

### Home Automation
- Smart lights (on/off/dim/brighten/set %, room-aware)
- Thermostat (set temp, warmer/cooler)
- Door locks (lock/unlock, status check)

### System & Accessibility
- Font size (increase/decrease/reset)
- High contrast mode toggle
- Speech rate (faster/slower/normal)
- Volume control (Android only — AudioManager)
- Battery status

### Knowledge & News
- Spell any word
- News headlines (BBC RSS, cached 1 hour)
- Memory recall (what do you know about me)
- Morning briefing (time, date, battery, weather, tip)

### PWA-Only (auriga-voice-nav.js)
- Read page aloud (continuous TTS, highlights element)
- Stop reading
- List headings on this page
- Count interactive elements
- What is focused
- Keyboard shortcuts guide
- Navigate to any page by voice
- Hover-to-speak toggle

---

## 7. Data Flow

### PWA Voice Pipeline
```
Mic → Web Speech API → handleFinalTranscript()
  → Jarvis.ask()
    → 1. matchCommand()     [navigation, settings, core commands]
    → 2. matchSkill()       [auriga-skills.js registered skills]
    → 3. queryKnowledge()   [KNOWLEDGE[] array — time, date, Auriga facts]
    → 4. AurigaLLM.ask()   [on-device Llama 3.2 1B via WebGPU]
    → 5. queryAI()          [free cloud LLM — online only, 7s timeout]
    → 6. buildOfflineFallback() [graceful message with next steps]
  → Jarvis.speak() → TTS → user hears response
```

### Android Voice Pipeline
```
Mic → SpeechRecognizer → onResults()
  → AurigaVoiceEngine.routeCommand()
    → 1. Navigation commands  [locator, reader, targets, help…]
    → 2. AurigaSkillEngine.dispatch()  [timers, weather, calc, home…]
    → 3. AurigaKnowledge.answer()      [pattern KB — ~50 facts]
    → 4. AurigaKnowledge.fallback()    [graceful offline reply]
  → TextToSpeech.speak() → user hears response
```

### Memory Pipeline (both platforms)
```
User speech → profile extraction (regex patterns)
  → name, location, age, occupation, guide_dog, cane, preferences
  → stored in IndexedDB (PWA) / SQLite (Android)
  → retrieved as context for LLM system prompt
  → quality signals (correct/wrong) improve context ranking
```

---

## 8. Offline Strategy

### PWA
- **Service Worker** (`sw.js`) caches all static assets
- **Skill fallbacks**: every online skill checks `navigator.onLine` first
- **LLM**: Llama 1B cached in browser after first consent (~700 MB)
- **Weather**: cached 30 minutes, spoken notice when stale
- **News**: cached 1 hour
- **Memory**: IndexedDB is always local

### Android
- **Core features run 100% offline**: locator (TFLite), reader (ML Kit), voice engine, KB, alarms, timers, reminders, calculator, converter, compass, GPS, haptics
- **Online-optional**: weather (Open-Meteo), news (BBC RSS), cloud LLM API
- **Connectivity check** before any network call → graceful spoken fallback
- **AlarmManager** fires even when device is in deep sleep and app is killed

---

## 9. Home Automation Protocol

AURIGA dispatches structured events. Integrate your hub by listening:

### PWA
```javascript
window.addEventListener('auriga:home', (e) => {
  const { action, target, value } = e.detail;
  // action: 'lights-on' | 'lights-off' | 'lights-dim' | 'lights-set'
  //         'thermostat-set' | 'thermostat-up' | 'thermostat-down'
  //         'door-lock' | 'door-unlock' | 'door-status'
  // target: room name or 'all' or 'home'
  // value:  brightness % or temperature degrees
});
```

### Android
```java
// Register receiver in your integration module:
IntentFilter f = new IntentFilter("com.drakosanctis.auriga.HOME_CONTROL");
registerReceiver(myReceiver, f);

// In onReceive:
String action = intent.getStringExtra("action");  // "lights-on", etc.
String target = intent.getStringExtra("target");  // room or "all"
int value     = intent.getIntExtra("value", 0);   // % or degrees
```

Compatible with: **Home Assistant** (local API), **SmartThings** (webhook), **Philips Hue** (bridge REST), **MQTT**, any BLE/WiFi bridge.

---

## 10. Keyboard Shortcuts (PWA)

| Shortcut | Action |
|---|---|
| `Ctrl + Space` | Toggle microphone listening |
| `Ctrl + Shift + H` | Voice help |
| `Ctrl + Shift + R` | Read this page aloud |
| `Ctrl + Shift + S` | Skip to main content |
| `Ctrl + Shift + N` | Skip to navigation |
| `Ctrl + Shift + B` | Morning briefing |
| `Escape` | Stop speaking |
| `Alt + ←` | Go back |
| `Tab` | Navigate elements (each speaks its label) |

---

## 11. File Structure

```
AURIGA/
├── web_deploy/                  # PWA (served by server.js)
│   ├── jarvis.js                # Agent loop core
│   ├── auriga-skills.js         # 26-skill pack
│   ├── auriga-voice-nav.js      # PWA voice navigation layer
│   ├── auriga-llm.js            # On-device LLM (WebLLM/WebGPU)
│   ├── auriga-memory.js         # IndexedDB memory store
│   ├── auriga-voice.js          # Mic UI overlay
│   ├── auriga-swipe.js          # Serpentine gesture engine
│   ├── auriga-announce.js       # Page announcer
│   ├── skills.html              # Skills directory
│   ├── assistant.html           # Primary voice interface
│   ├── chat.html                # Text + voice chat
│   ├── locator.html             # Object detection
│   ├── reader.html              # OCR text reader
│   ├── sw.js                    # Service Worker (offline cache)
│   └── manifest.json            # PWA manifest
│
└── app/src/main/java/com/drakosanctis/auriga/
    ├── AurigaVoiceEngine.java   # Voice + command routing
    ├── AurigaSkillEngine.java   # OpenClaw-style skill dispatcher
    ├── AurigaAlarmReceiver.java # AlarmManager broadcast handler
    ├── AurigaKnowledge.java     # Offline knowledge base
    ├── AurigaMemoryStore.java   # SQLite memory + profile
    ├── AurigaVoiceService.java  # Always-on wake word service
    ├── YoloDetector.java        # TFLite object detection
    ├── DrakoVoice.java          # Custom TTS engine
    ├── HapticManager.java       # Rich haptic patterns
    ├── GodsEyeOrchestrator.java # Multi-sensor fusion
    ├── TriangulationEngine.java # Distance estimation
    ├── SerpentineGestureDetector.java
    ├── SonarManager.java        # BLE ultrasonic sensor
    └── SentinelNode.java        # Pi camera stream consumer

server.js                        # Express server (port 5000)
BLUEPRINT.md                     # This file
```

---

## 12. Extending with New Skills

### PWA — Add to `auriga-skills.js`
```javascript
window.Jarvis.registerSkill({
  name: 'My skill',
  description: 'what this skill does',
  match: [
    /\bmy trigger phrase\b/i,
    /\balternative phrase\b/i
  ],
  handle: function(text, match) {
    // Sync: return a string
    // Async: return a Promise<string>
    return 'This is the spoken reply.';
  }
});
```

### Android — Add to `AurigaSkillEngine`
```java
// 1. Add a Skill descriptor to registerSkills():
registry.add(new Skill("My skill", "what it does",
    new String[]{"trigger", "phrase"},
    "\\bmy\\s+trigger\\s+phrase\\b|\\balternative\\b"));

// 2. Add handler in dispatch():
if (matchesSkill(t, "My skill")) { handleMySkill(t); return true; }

// 3. Implement handler:
private void handleMySkill(String text) {
    speak("This is the spoken reply.");
}
```

---

*Blueprint version: 2025. Maintained by DrakoSanctis.*
