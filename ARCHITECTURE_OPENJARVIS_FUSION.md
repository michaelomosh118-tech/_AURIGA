# AURIGA + OpenJarvis Fusion Architecture
**Personal AI for Accessibility — Voice-First Navigation for Visually Impaired Users**

---

## Executive Summary

This document describes a **unified, voice-first accessibility platform** that merges:
- **AURIGA**: On-device object/text locator, haptic feedback, TTS announcements, HUD overlay
- **OpenJarvis**: Modular AI agent framework, speech-to-text, tool orchestration, skill system

**Goal**: Create a seamless, no-setup voice-commanded system where blind/low-vision users navigate features, control hardware, and access information entirely by voice — with zero visual interface requirement.

**Key Principle**: *Eliminate visual redundancy*. AURIGA's current drawer navigation and button UI become **voice-only commands** routed through OpenJarvis's intent recognition and skill system.

---

## Current State Analysis

### AURIGA (michaelomosh118-tech/_AURIGA)
**Strengths:**
- ✅ Native Android, minimal dependencies (CameraX, ML Kit, TensorFlow Lite)
- ✅ Fully offline object/text localization + haptic feedback
- ✅ Drawer-based UI with mute toggles (voice, haptic, smart-light)
- ✅ Wake-word foreground service (`AurigaVoiceService`)
- ✅ Voice command skeleton in `AurigaVoiceEngine`

**Weaknesses:**
- ❌ Voice commands hardcoded (open menu, go back, describe page)
- ❌ No intent routing or skill discovery
- ❌ Drawer requires visual/gesture navigation
- ❌ No multi-turn conversation or reasoning
- ❌ Speech recognition only used for wake-word, not general commands

### OpenJarvis (open-jarvis/OpenJarvis)
**Strengths:**
- ✅ Multi-turn agent with tool orchestration
- ✅ Pluggable speech backends (Whisper, Deepgram, Faster Whisper)
- ✅ Pluggable TTS backends (Cartesia, Kokoro, OpenAI)
- ✅ Skill registry + dynamic tool discovery
- ✅ Modular, extensible Python framework
- ✅ Local-first inference (no cloud required)

**Weaknesses:**
- ❌ Desktop/server-only (no native Android integration)
- ❌ Requires Python environment + HTTP API bridge
- ❌ No haptic feedback primitives
- ❌ No real-time camera frame analysis
- ❌ Heavy (LLM inference, speech models)

---

## Integration Strategy

### Layer 1: Unified Voice Pipeline

```
User speaks
    ↓
[Foreground Wake-Word Service] ← AurigaVoiceService (unchanged)
    ↓ detects "[Name] Auriga" or serpentine gesture
    ↓
[Listen & Transcribe] ← Jarvis SpeechRecognizer (Whisper/Deepgram)
    ↓
[Intent Router] ← Jarvis Orchestrator Agent
    ├─ Quick commands (AURIGA-native)
    │   └─ "describe page" → LocatorActivity.onDescribePage()
    │   └─ "open reader" → startActivity(ReaderActivity)
    │   └─ "toggle voice" → voiceEnabled = !voiceEnabled
    │   └─ "open menu" → drawerLayout.openDrawer()
    │
    └─ Complex queries (Jarvis tools)
        └─ "find a chair and tell me where" → orchestrator loop
           ├─ camera frame → detector.detect()
           ├─ result → tool_call("describe_detection", {...})
           └─ TTS → user hears response
```

### Layer 2: AURIGA Activities as Jarvis Tools

**Problem**: Each activity (Reader, Targets, CameraConnect) is opaque to voice navigation.

**Solution**: Wrap activities as stateless Jarvis **skills** that return structured data

---

## Implementation: Phase 1 (Immediate Fixes)

### 1.1 Fix CameraStreamActivity Theme Crash
