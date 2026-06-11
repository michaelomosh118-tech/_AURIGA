---
name: VAD Wake-Word Service
description: AurigaVoiceService now uses AudioRecord + energy VAD instead of always-on SpeechRecognizer to avoid audio focus requests that killed music playback.
---

## Rule
`AurigaVoiceService` must never call `SpeechRecognizer.startListening()` in a tight loop. Use the AudioRecord VAD path.

## Why
`SpeechRecognizer.startListening()` causes the Google recognition process to request `AUDIOFOCUS_GAIN` every ~300 ms. This ducks music continuously — a severe UX regression for blind users who rely on audio. `AudioRecord` does not request audio focus at all.

## How to apply
- `AudioRecord(VOICE_RECOGNITION, 16kHz, MONO, PCM_16BIT)` reads 100 ms chunks on background thread
- `computeRms(chunk) > 800f` for ≥ 3 consecutive chunks (300 ms) → `fireRecognizer()`
- 2-second cooldown between recognizer invocations (`lastRecognitionAt`)
- `SpeechRecognizer` is active for ≤ 3 seconds per utterance — audio focus grab is brief and intentional
- `RecognitionListener.onResults()` sets `recognizerBusy = false`, `lastRecognitionAt = now`
- Graceful fallback to old loop if `AudioRecord` fails to initialize (logged, not crashed)
- `onDestroy()` stops AudioRecord, joins vadThread with 500ms timeout, then destroys recognizer
- VAD threshold `800f` can be tuned empirically; higher = less sensitive (fewer false triggers)
