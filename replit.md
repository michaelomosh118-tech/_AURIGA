# DrakoSanctis // Auriga Ecosystem

## Overview
Static landing/PWA site for the Auriga Ecosystem by DrakoSanctis. The published site is served from `AURIGA/web_deploy`, which contains:
- `index.html` — main landing page
- `calibration-library.html` — calibration library page
- `manifest.json`, `sw.js`, `logo.png` — PWA assets
- `data/` — JSON datasets used by the calibration pages

The repository also contains an Android app skeleton (`AURIGA/app`, Gradle) and a marketing HTML (`AURIGA/DrakoSanctis_LandingPage.html`), but the public web product is the static site under `web_deploy`.

## Replit Setup
- Workflow `Start application` serves `AURIGA/web_deploy` via `python3 -m http.server` on `0.0.0.0:5000`.
- Deployment is configured as a `static` site with `publicDir = AURIGA/web_deploy` (mirroring the existing Netlify config).

## Notes
- No backend; the site is fully static.
- Service worker registers automatically on load (PWA).

## Recent Changes
- **Mobile app HUD redesign (AurigaNavi)**: Updated `AURIGA/app/src/main/res/`
  with an extended palette (`colors.xml`), glassmorphism card style
  (`hud_glass_card.xml`), gradient top/bottom bars
  (`hud_top_bar_bg.xml` / `hud_bottom_bar_bg.xml`), pulsing status dot,
  concentric radar reticle with crosshair (`radar_circle_anim.xml`),
  glowing readout typography, and a polished alert banner
  (`alert_banner_bg.xml`). Layout (`activity_main.xml`) now uses glass
  cards for distance/bearing, a sub-labelled mode pill, and a Reader
  launcher button on the bottom bar.
- **DrakoVoice Reader (OrCam-style) — Android**: New
  `ReaderActivity.java` + `activity_reader.xml`. Uses CameraX preview,
  on-device Google ML Kit Latin OCR (offline, bundled model) and the
  platform `TextToSpeech` engine. Supports point-and-read (capture FAB),
  AUTO mode (auto-capture every 6s), paragraph-by-paragraph navigation,
  and TalkBack-friendly content descriptions. Wired into `MainActivity`
  via the new `READ` button and registered in `AndroidManifest.xml`.
  Required dependencies added in `app/build.gradle` (CameraX 1.3.4 +
  `com.google.mlkit:text-recognition:16.0.0`).
- **DrakoVoice Reader — Web**: New `AURIGA/web_deploy/reader.html`. Uses
  `getUserMedia` for camera + Tesseract.js for in-browser OCR + the Web
  Speech API for TTS. Includes Touch-Read (tap a word to read from there,
  matching OrCam's touch-bar gesture), AUTO mode, paragraph
  prev/play-pause/next controls, keyboard shortcuts (Space, ←/→, A, T,
  Enter), live word highlighting, and a graceful upload-mode fallback
  when the camera is unavailable. Linked from the main nav in
  `index.html`.
