---
name: Conversational AI Layer
description: How Jarvis answers non-command questions on web and Android, including offline LLM, memory, and user profile.
---

## Web pipeline (jarvis.js ask())

Priority order when no command or KB match:

1. **Profile extraction** — `AurigaMemory.extractAndSaveProfile(text)` runs silently on every user turn
2. **On-device LLM** — `AurigaLLM.ask(text, memCtx)` if `status === 'ready'` (WebGPU + Llama 3.2 1B)
3. **Online API** — `queryAI(text)` via `api.freeai.chat`, 7s timeout, returns null on failure
4. **Offline fallback** — `buildOfflineFallback(text)` — question-type-aware response

## On-device LLM (auriga-llm.js)

- Model: `Llama-3.2-1B-Instruct-q4f32_1-MLC` (~700MB)
- Loaded via dynamic `import('https://esm.run/@mlc-ai/web-llm')`
- Requires WebGPU (`navigator.gpu`) — Chrome/Edge 113+ only; gracefully unavailable elsewhere
- **Opt-in only** — asks user by voice on first visit; preference stored in `localStorage['auriga-llm-consent']`
- Download triggered by `requestIdleCallback` after consent
- Status: `'idle' | 'consent-needed' | 'loading' | 'ready' | 'unavailable'`
- System prompt injected with user profile context from AurigaMemory

## Conversation memory (auriga-memory.js)

- IndexedDB: `auriga-ai-memory` v1
- Stores: `conversations` (role, text, page, ts, quality) + `profile` (key→value facts)
- `getContext(query, n)` — RAG: keyword-scored top-n past turns injected as LLM prior turns
- `extractAndSaveProfile(text)` — regex rules extract "my name is…", "I live in…", etc.
- Quality signals: user says "yes exactly" → `markLastQuality(1)`; "that's wrong" → `markLastQuality(-1)`
- Cap: 2000 rows; oldest trimmed automatically

## Script load order (all pages with Jarvis)

1. `auriga-announce.js`
2. `auriga-memory.js`
3. `auriga-voice.js`
4. `jarvis.js` (fires `jarvis:ready` CustomEvent after init)
5. `auriga-llm.js` (hooks on `jarvis:ready` to register voice commands)

## Android (AurigaMemoryStore.java)

- SQLite DB `auriga_memory` v1 — same two tables as web
- All ops on single-thread `ExecutorService` background
- `store(ctx, role, text, page)` → saves turn
- `extractAndSaveProfile(ctx, text)` → regex profile extraction
- `getContext(ctx, query, n, cb)` → async RAG context callback
- `getProfileContext(ctx, cb)` → async profile sentence callback
- `markLastQuality(ctx, signal)` → marks last assistant turn
- Wired into `AurigaVoiceEngine.routeCommand()` — stores every user turn + quality signals

**Why:** True in-browser/on-device LLM fine-tuning is not feasible. RAG with growing conversation history + profile facts gives the same "organic, personalised" feel with no training infrastructure needed.
