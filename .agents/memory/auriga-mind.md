---
name: AurigaMind architecture
description: On-device LLM assistant layer — MindEngine + KnowledgeCache wiring into AurigaVoiceEngine
---

## The rule

MindEngine slots in as the FINAL fallback in AurigaVoiceEngine's dispatch chain,
after SkillEngine and AurigaKnowledge. It is null-safe — the chain works identically
without any model file.

## Dispatch order (AurigaVoiceEngine.routeCommand)

1. Navigation commands (activity launch, drawer, back, etc.) — exact/fuzzy match
2. AurigaSkillEngine.dispatch()  — timers, alarms, weather API calls, GPS
3. AurigaKnowledge.answer()      — instant rule-based KB (returns null on miss)
4. MindEngine.ask()              — on-device LLM, streamed TTS sentences
5. KnowledgeCache.getContext()   — weather/news context passthrough (no LLM)
6. AurigaKnowledge.fallback()    — always-safe "I don't know" response

## KnowledgeCache

- SQLite DB: `auriga_knowledge.db`, table `knowledge_cache(category,key,value,ts)` — PRIMARY KEY (category,key)
- Three feeds: weather (Open-Meteo, free, 30-min TTL), news (Google News RSS, 1-hr TTL), wiki (Wikipedia REST, 7-day TTL, on-demand per topic)
- `getContext(query)` is synchronous / non-blocking — returns cached value and triggers background refresh if stale
- `warmUp()` called from initTts() callback once TTS is ready
- GPS coords set via `updateLocation(lat, lon)` — needs wiring to HardwareHAL/AurigaSkillEngine when available

## MindEngine

- Loads MediaPipe LlmInference **via reflection** — project compiles without AAR
- Model files (gitignored): `gemma2b_q4.bin` (~1.5 GB) or `qwen2_5_0_5b_q8.bin` (~400 MB) in assets/
- `createAsync()` called from initTts() callback; sets `AurigaVoiceEngine.mindEngine` field when ready
- Copies asset → getCacheDir() so MediaPipe can mmap by file path (AssetFileDescriptor not accepted)
- Streaming: tokens → `flushSentences()` → `tts.speak(QUEUE_ADD)` per sentence — user hears answer in ~2s
- `speakMain("Just a moment.")` QUEUE_FLUSH acknowledgement before generation starts
- `clean()` strips `<end_of_turn>`, `<|im_end|>`, markdown before TTS

## To enable full LLM

1. Uncomment in build.gradle: `implementation 'com.google.mediapipe:tasks-genai:0.10.14'`
2. Drop model file into `app/src/main/assets/` — see MODEL_README.md for export commands
3. Add `aaptOptions { noCompress '*.bin' }` to android{} block if the model fails to mmap

**Why:** MediaPipe is optional so the APK ships and builds cleanly without a 1.5 GB model in CI. Reflection means zero compile-time AAR coupling.
