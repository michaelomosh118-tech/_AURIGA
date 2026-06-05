---
name: Jarvis Fusion Architecture
description: How OpenJarvis concepts were ported into the Auriga browser PWA and how the layers fit together.
---

# Jarvis Fusion Architecture

## What was built
OpenJarvis is a Python CLI — its concepts (persona, skills, morning briefing, intent routing, conversational memory) were ported natively into the browser.

## Layer stack (load order matters)
1. `auriga-announce.js` — low-level TTS queue (sync)
2. `nav-drawer.js` + `auriga-voice.js` — nav + wake-word recognition (defer)
3. `jarvis.js` — AI assistant engine (defer, loads after voice engine)

## Key integration points
- `auriga-voice.js` unrecognised commands → `window.Jarvis.ask(text)` fallback
- `locator.html` → `window.Jarvis.setSceneProvider(() => currentDetections)` after model loads
- `jarvis.js` exposes `window.Jarvis.onMemory(fn)` — assistant.html uses this to update the conversation log live

## New files
- `AURIGA/web_deploy/jarvis.js` — Jarvis engine (knowledge base, skills, briefing, scene provider)
- `AURIGA/web_deploy/assistant.html` — dedicated voice assistant UI

## Modified files
- `auriga-voice.js` — fallback to `Jarvis.ask()` for unrecognised commands
- `nav-drawer.js` — added "Assistant" section with JARVIS ASSISTANT link (above Tools)
- `locator.html` — added `currentDetections` tracking + scene provider registration + jarvis.js script
- `reader.html` — added jarvis.js script tag
- `sw.js` — cache bumped to v13, added jarvis.js + assistant.html to pre-cache list

**Why:** OpenJarvis cannot run in a browser (Python/Ollama required). The correct fusion strategy is porting its design patterns (personality, skills registry, memory, briefing) as pure JS in the same browser context as Auriga.

**How to extend:** Call `window.Jarvis.registerSkill({ name, description, match: [/regex/], handle: fn })` from any page to add a new voice skill at runtime.
