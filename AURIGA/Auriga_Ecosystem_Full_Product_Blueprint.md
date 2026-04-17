# Auriga Ecosystem – Complete Product Blueprint
**DrakoSanctis Spatial Intelligence Platform**
**Version 1.0 – Production Ready**

---

## 1. COMPANY & BRAND IDENTITY
DrakoSanctis is the parent company building a universal **Spatial Intelligence Platform**. We turn any camera + processor into precise, real-time spatial awareness.

- **AurigaNavi** – The Charioteer (Accessibility app for visually impaired users)
- **AurigaSentinel** – The Watchman (Fall detection, crowd awareness, safety)
- **AurigaAero** – The Winged One (SDK for drones and robotics)
- **AurigaIndustrial** – The Builder (Warehouse forklift hazard detection)

**Tagline:** “See Beyond Sight”
**Core promise:** Sub-20cm accurate distance, bearing, and height using only a phone camera.

---

## 2. TRADEMARKED FEATURES (IP)
- **TruePath™ (Fiducial Triangulation):** Proprietary ground-plane triangulation.
- **SkyShield™ (Overhang Detection):** Aerial obstacle geometry for head/chest safety.
- **AuraTextures™ (Acoustic Signatures):** Width-based audio cues for intuitive environmental mapping.
- **GhostAnchor™ (Visual SLAM):** Stabilization and spatial memory engine.

---

## 3. BUSINESS MODEL & ACCESS
- **FREE Tier:** Basic ground navigation, beeps only.
- **PREMIUM Tier ($9.99/mo):** Unlocks TruePath™, SkyShield™, AuraTextures™, GhostAnchor™.
- **DEVELOPER Tier ($299/yr):** Full SDK access for one deployment target.
- **COMMERCIAL Tier ($2,499/yr per deployment):** Enterprise-grade licensing and support.

**Security:** Hybrid validation with RSA-2048 signed tokens, device fingerprinting, and a 7-day local heartbeat.

---

## 4. TECHNICAL ARCHITECTURE
- **Language:** Java (Android/AIDE) -> C++ (Future cross-platform).
- **Processing:** 30+ FPS real-time feedback loop.
- **Logic:** 3-column spatial scan (Left, Center, Right).
- **Feedback:** DrakoVoice™ TTS, Stereo Sonar, and Proximity Haptics.
- **Zero-Calibration:** Pre-populated Device Profile Database (Manufacturer + Model fingerprint).

---

## 5. REPOSITORY STRUCTURE
- `AurigaConfig.java`: Central feature flag control panel.
- `FiducialLUT.java`: Ground-ruler training data storage.
- `LicenseManager.java`: Security and licensing logic.
- `HardwareHAL.java`: Camera normalization.
- `ColorSquareDetector.java`: HSV training marker detector.
- `CalibrationManager.java`: Training mode state machine.
- `ImageProcessor.java`: 3-column spatial scan.
- `TriangulationEngine.java`: Distance/Height/Bearing math.
- `OdometryManager.java`: GhostAnchor stabilization.
- `DrakoVoice.java`: Inclusive TTS persona.
- `SonarManager.java`: AuraTextures audio engine.
- `HapticManager.java`: Vibration patterns.
- `MainActivity.java`: Main orchestrator.
- `res/`: HUD UI resources (layouts, colors, styles).

---
*DrakoSanctis - Empowering the world through spatial intelligence.*
