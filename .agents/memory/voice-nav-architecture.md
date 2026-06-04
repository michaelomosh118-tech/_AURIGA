---
name: Voice Navigation Architecture
description: How the Auriga voice navigation engine works, key design decisions, and conventions for future work.
---

## The Rule
`auriga-voice.js` and `auriga-voice.css` are the single source of truth for all voice input on the web PWA. Every page loads them after `nav-drawer.js` and `auriga-announce.js`. Never duplicate speech recognition logic into individual page scripts.

**Why:** The voice engine must share state (assistant name, enabled flag) across all pages via localStorage. Splitting it would break cross-page persistence.

## Android voice engine — four-class design

| Class | Role |
|---|---|
| `AurigaVoiceEngine` | Core engine — SpeechRecognizer + TTS + command routing + lifecycle. One instance per activity. |
| `AurigaVoiceService` | Foreground service — always-on SpeechRecognizer loop that broadcasts `ACTION_WAKE_WORD` on detection. |
| `VoiceSetupActivity` | First-run name setup; also reachable via "change name" command or drawer. Uses `AurigaDocTheme`. |
| `SerpentineGestureDetector` | Touch gesture: start in left 30% → down ≥60dp → up ≥60dp → lift in centre 40%. |

**SharedPreferences keys (all inside `MainActivity.PREFS_NAME`):**
- `auriga_voice_name` — assistant name (default "Auriga")
- `auriga_voice_nav_enabled` — voice nav on/off boolean
- `auriga_voice_setup_done` — first-run gate

**Broadcast:** `com.drakosanctis.auriga.VOICE_WAKE` — sent by `AurigaVoiceService`, received by `LocatorActivity` to call `voiceEngine.startListening()`.

## Key design decisions

- **First-run gate:** No voice features work until the user names the assistant. Setup modal launches automatically on first open of LocatorActivity.
- **Wake phrase:** `"[NAME] AURIGA"` — e.g. "Nova Auriga". Also "hey auriga" / "ok auriga".
- **Three activation methods (web):** (1) serpentine swipe, (2) long-press screen centre, (3) Ctrl+Space.
- **Three activation methods (Android):** (1) serpentine swipe on locator frame, (2) long-press locator frame, (3) mic FAB button (bottom centre, id=`voice_mic_fab`).
- **Always-on (Android only):** `AurigaVoiceService` runs as a microphone foreground service, auto-restarts on SpeechRecognizer errors with exponential back-off (500ms → max 8s).
- **Screen announcer:** Web: `PAGE_DESCRIPTIONS` / `PAGE_GUIDES` maps in `auriga-voice.js`. Android: `onDescribePage()` callback in `AurigaVoiceEngine.Listener` — each activity provides its own description string.
- **Permissions:** `RECORD_AUDIO` + `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE` (Android 14+).

## Command table (Android)
Commands live in `AurigaVoiceEngine.routeCommand()`. To add a new command: add a `contains(text, ...)` branch in that method, call `speak(...)` and the appropriate action.

## Web command table
`COMMANDS` array in `auriga-voice.js` — each entry: `match` (RegExp[]), `reply` (string|null), `action` (function).

## How to apply
- **New Android activity:** Add it as a navigation target in `AurigaVoiceEngine.routeCommand()`. Override `onDescribePage()` in its `Listener` impl.
- **New web page:** Add `auriga-voice.js` + `auriga-voice.css` script/link tags, add the page to `PAGE_DESCRIPTIONS` + `PAGE_GUIDES` in `auriga-voice.js`, add to `ASSETS_TO_CACHE` in `sw.js`.
- **SW cache:** `drakosanctis-v10` as of voice nav addition — bump on shell asset changes.
- **DrakoVoice.say(String):** General TTS method added for non-navigation speech from the locator or other activities that hold a `DrakoVoice` instance.
