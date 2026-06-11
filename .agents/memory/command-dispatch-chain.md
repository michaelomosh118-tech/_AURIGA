---
name: Unified Command Dispatch Chain
description: How voice commands flow from SpeechRecognizer through all tiers to MindEngine, including the new camera-skill tier.
---

## Rule
All voice commands pass through a 5-tier chain. Never add more fallback strings to `CommandRouter.dispatch()` — it returns `null` on no-match.

## Why
Previously `AurigaCoreService.CommandRouter` was wired up with camera skills (describe, stair, crossing, face, pill, cash, colour, emergency) but nothing in the voice pipeline ever called `dispatch()`. `AurigaVoiceEngine` handled timers/LLM but had no path to camera skills. Camera commands were silently unreachable from voice.

## How to apply
Dispatch chain in `AurigaVoiceEngine.routeCommand()`:
```
T1: UI / navigation (open drawer, back, open screen)
T2: AurigaSkillEngine  (timers, alarms, weather, compass)
T3: AurigaCoreService.instance?.tryDispatchCameraCommand(cmd)  ← added
    └── CommandRouter.dispatch() → null if no match (not a fallback string)
T4: AurigaKnowledge.answer()  (rule-based KB, instant)
T5: MindEngine.ask()          (Qwen LLM, async, streams via its own TTS)
T6: KnowledgeCache.getContext() / AurigaKnowledge.fallback()
```

`AurigaCoreService.onVoiceCommand()` runs the same 5-tier chain for callers that don't go through `AurigaVoiceEngine` (AurigaButlerService, etc.).

`AurigaCoreService` boots `TextToSpeech` in `onCreate()` via `initTts()`, which then creates `AurigaSkillEngine`, warms `KnowledgeCache`, and calls `MindEngine.createAsync()`.

`static volatile AurigaCoreService instance` is the access point — set in `onCreate()`, cleared in `onDestroy()`.

`CommandRouter.dispatch()` returns `null` on no-match (changed from returning "I don't know" string).
