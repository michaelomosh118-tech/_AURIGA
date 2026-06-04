---
name: Voice Navigation Architecture
description: How the Auriga voice navigation engine works, key design decisions, and conventions for future work.
---

## The Rule
`auriga-voice.js` and `auriga-voice.css` are the single source of truth for all voice input on the web PWA. Every page loads them after `nav-drawer.js` and `auriga-announce.js`. Never duplicate speech recognition logic into individual page scripts.

**Why:** The voice engine must share state (assistant name, enabled flag) across all pages via localStorage. Splitting it would break cross-page persistence.

## Key design decisions

- **First-run gate:** No voice features work until the user names the assistant (stored at `auriga-voice-name` in localStorage). The onboarding modal blocks the page until confirmed.
- **Wake phrase:** `"[NAME] AURIGA"` — e.g. "Nova Auriga". The name alone also triggers it.
- **Three activation methods:** (1) serpentine swipe from left border, (2) long-press screen centre on mobile, (3) Ctrl+Space on desktop.
- **Serpentine gesture:** touch start near left border + centre Y → curve down → curve up → end near horizontal centre. Detected via 3-phase touch tracking (STARTED → DOWN → UP).
- **No always-on wake word on web** — browser security prevents background mic listening. The Android app is the target for always-on detection.
- **Command table** in `auriga-voice.js` `COMMANDS` array — add new commands there, each entry has `match` (array of RegExp), `reply` (string or null), and `action` (function).
- **Screen announcer** — `PAGE_DESCRIPTIONS` and `PAGE_GUIDES` maps in `auriga-voice.js` define per-page TTS descriptions and guided tours. Extend these when new pages are added.

## How to apply
- When adding a new HTML page: add `<script defer src="auriga-voice.js"></script>` + `<link rel="stylesheet" href="auriga-voice.css">` after `nav-drawer.js`, add the page to `PAGE_DESCRIPTIONS` and `PAGE_GUIDES` in `auriga-voice.js`, and add it to `ASSETS_TO_CACHE` in `sw.js`.
- When adding a new voice command: append an entry to `COMMANDS` in `auriga-voice.js`.
- SW cache is `drakosanctis-v10` as of the voice nav addition — bump it whenever shell assets change.
