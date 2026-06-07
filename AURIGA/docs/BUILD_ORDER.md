# AURIGA Ecosystem — Build Order Reference

> **How to use this doc:** Work top to bottom. Sessions in the same parallel
> group can be built simultaneously by separate agents or in separate Replit
> sessions. Never start a session until every session it depends on is marked ✅.
>
> Status key: ✅ Done · 🔄 In progress · ⬜ Not started · ⚠️ Blocked

---

## PHASE 0.5 — Accessibility & Onboarding Foundation
> *Build this entire phase before Phase 1. A blind user must be able to onboard
> without ever seeing the screen.*

| # | Session | File(s) Created / Modified | Depends On | Status |
|---|---------|---------------------------|------------|--------|
| A | Voice-first name capture | `VoiceSetupActivity.java` (modify) | — | ⬜ |
| B | Boot announcement + TalkBack drawer | `LocatorActivity.java` (modify) | A | ⬜ |
| C | SmartCalibrationEngine | `SmartCalibrationEngine.java` (new) | A | ⬜ |
| D | AurigaAccessibilityService | `AurigaAccessibilityService.java` (new), `accessibility_service_config.xml` (new) | — | ⬜ |
| E | AurigaButlerService | `AurigaButlerService.java` (new), manifest additions | D, A | ⬜ |
| F | TutorialActivity + AurigaTutorialEngine | `TutorialActivity.java` (new), `AurigaTutorialEngine.java` (new) | A, E | ⬜ |

### Parallel groups in Phase 0.5
- **Group 0.5-α** (run together): A, D — no inter-dependencies
- **Group 0.5-β** (after α): B, C, E — all need A or D
- **Group 0.5-γ** (after β): F — needs A + E

---

## PHASE 1 — Core Safety & Output Modules

| # | Session | File(s) Created | Depends On | Status |
|---|---------|----------------|------------|--------|
| 1 | Interface Contracts | `AurigaInterfaces.java` (new) | Phase 0.5 ✅ | ✅ Done |
| 2 | OutputLayer | `OutputLayer.java` (new) | 1 | ✅ Done |
| 3 | CommandRouter | `CommandRouter.java` (new) | 1 | ✅ Done |
| 4 | ColorSenseEngine | `ColorSenseEngine.java` (new) | 1 | ✅ Done |
| 5 | StairSenseEngine | `StairSenseEngine.java` (new) | 1 | ✅ Done |
| 6 | TrafficSenseEngine | `TrafficSenseEngine.java` (new) | 1 | ✅ Done |
| 7 | CrossingGuardEngine | `CrossingGuardEngine.java` (new) | 4, 6, 2 | ⬜ |
| 8 | EmergencySOSEngine | `EmergencySOSEngine.java` (new) | 1, 2 | ⬜ |
| 9 | PassiveHazardEngine | `PassiveHazardEngine.java` (new) | 1 | ⬜ |
| 10 | GodsEyeOrchestrator (complete stub) | `GodsEyeOrchestrator.java` (rewrite) | 4, 5, 6, 2, 3 | ⬜ |

### Parallel groups in Phase 1
- **Group 1-α** (run together after Session 1): Sessions 2, 3, 4, 5, 6
- **Group 1-β** (after α): Sessions 7, 8, 9 — can run in parallel
- **Group 1-γ** (after β): Session 10 — needs all of α done

---

## PHASE 2 — Identification Suite

| # | Session | File(s) Created | Depends On | Status |
|---|---------|----------------|------------|--------|
| 11 | PillGuardEngine + PillDatabase | `PillDatabase.java`, `PillGuardEngine.java` | 1, 2 | ⬜ |
| 12 | FaceVaultEngine + FaceDatabase | `FaceDatabase.java`, `FaceVaultEngine.java` | 1 | ⬜ |
| 13 | CashLensEngine | `CashLensEngine.java` | 1 | ⬜ |
| 14 | LabelReaderEngine | `LabelReaderEngine.java`, `build.gradle` (+ ZXing) | 1 | ⬜ |

### Parallel groups in Phase 2
- **Group 2-α** (run together): Sessions 11, 12, 13, 14 — all independent of each other

---

## PHASE 3 — Memory & Intelligence

| # | Session | File(s) Created | Depends On | Status |
|---|---------|----------------|------------|--------|
| 15 | SpatialMemoryEngine + SpatialDatabase | `SpatialDatabase.java`, `SpatialMemoryEngine.java` | 1, 2, 3 | ⬜ |
| 16 | SceneDescriberEngine | `SceneDescriberEngine.java` | 1, 4, 5, 6, 10 | ⬜ |
| 17 | AurigaMind (offline LLM companion) | `AurigaMind.java`, `AurigaKnowledge.java` | 1, 15 | ⬜ |

### Parallel groups in Phase 3
- **Group 3-α**: Session 15 (after Phase 1 complete)
- **Group 3-β** (after 3-α): Sessions 16, 17 in parallel

---

## PHASE 4 — Integration & Platform Extension

| # | Session | File(s) Created / Modified | Depends On | Status |
|---|---------|---------------------------|------------|--------|
| 18 | AurigaCoreService wire-up | `AurigaCoreService.java` (new), manifest | All Phase 1–3 | ⬜ |
| 19 | Platform extensions | `platforms/pc/auriga_pc.py`, `platforms/pi/auriga_pi.py` | 18 | ⬜ |
| 20 | Release hardening + Gradle flavors | `build.gradle`, ProGuard rules, flavor configs | 18 | ⬜ |

---

## Full Dependency Graph

```
Phase 0.5                Phase 1                  Phase 2          Phase 3       Phase 4
─────────────────────    ──────────────────────    ──────────────    ──────────    ────────
A ──┬──► B               1 (AurigaInterfaces)      ┌─ 11 PillGuard
   │     └──► F             │                       │
   └──► C              ┌────┼────┐                  ├─ 12 FaceVault
                        │   │    │                  │              ──► 15 Spatial
D ──────────► E        2   3   4,5,6                ├─ 13 CashLens  ──► 16 Scene   ──► 18 ──► 19,20
              └──► F   │   │    │                  │
                        │   │    └──► 7,8,9 ──► 10  └─ 14 Label
                        │   │
                        └───┴──► 10 GodsEye
```

---

## Invariants (never violate)

1. **`AurigaInterfaces.java` is the single source of truth.** No module may define
   its own version of any interface, enum, or value class that appears in that file.

2. **No module implementation may import another module implementation.**
   Only `AurigaInterfaces.*` types cross module boundaries. The orchestrator
   (`GodsEyeOrchestrator`, `AurigaCoreService`) is the only class that touches
   multiple concrete engines.

3. **Every engine must pass its own `selfTest(Context ctx)` returning `true`
   before it may be wired into the orchestrator.**

4. **OutputLayer is the only class that calls TTS or the vibrator.**
   `DrakoVoice` and `HapticManager` remain for the legacy native HUD; all new
   modules use `OutputLayer` exclusively.

5. **All NV21 analysis is allocation-free in the hot path.** Engines may allocate
   during `selfTest()` and construction, but `analyse()` must not allocate on
   every call — reuse buffers as instance fields.

---

## Session Prompt Template

When starting a new session, paste this prompt and fill in the blanks:

```
Read: <list source files from the "Depends On" column>
Build: <file name(s) from "File(s) Created" column>
Spec: Section <X> of AURIGA/docs/AURIGA_FULL_BUILD_BLUEPRINT.md
Invariants: Section 1-5 of AURIGA/docs/BUILD_ORDER.md
```

---

*Last updated: Session 6 complete. Next: Sessions 7, 8, 9 (parallel).*
