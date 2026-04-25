# DrakoSanctis // Auriga Ecosystem

## Overview
Static landing/PWA site for the Auriga Ecosystem by DrakoSanctis. The published site is served from `AURIGA/web_deploy`, which contains:
- `index.html` — main landing page
- `calibration-library.html` — calibration library page
- `manifest.json`, `sw.js`, `logo.png` — PWA assets
- `data/` — JSON datasets used by the calibration pages

The repository also contains an Android app skeleton (`AURIGA/app`, Gradle) and a marketing HTML (`AURIGA/DrakoSanctis_LandingPage.html`), but the public web product is the static site under `web_deploy`.

## Replit Setup
- Workflow `Start application` serves `AURIGA/web_deploy` via `node server.js` on `0.0.0.0:5000`.
- `server.js` (project root): Node.js HTTP server that serves static files from `AURIGA/web_deploy` and also handles the feedback endpoint at `/.netlify/functions/submit-feedback`, replacing the Netlify serverless function for Replit compatibility.
- Deployment is configured as a `static` site with `publicDir = AURIGA/web_deploy`.

## Notes
- The feedback endpoint (`/.netlify/functions/submit-feedback`) is handled server-side by `server.js`. It supports optional env vars: `FEEDBACK_FORWARD_WEBHOOK` (generic webhook), `FEEDBACK_GITHUB_REPO` + `FEEDBACK_GITHUB_TOKEN` (auto-file GitHub issues).
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
- **In-app hamburger menu (0.4e)**: Wrapped `activity_main.xml` in an
  `androidx.drawerlayout.widget.DrawerLayout` and added a side panel with
  HUD / Reader / About / Help destinations plus a Diagnostics section.
  The DIAG toggle now lives inside the drawer; a "Pin DIAG to HUD" switch
  (persisted to SharedPreferences `auriga_prefs / pin_diag_to_hud`)
  controls whether the inline pill in the top bar is visible. New
  `AboutActivity` + `HelpActivity` (plus `activity_about.xml`,
  `activity_help.xml`, `AurigaDocTheme`/`DocH1`/`DocH2`/`DocBody` styles,
  `drawer_bg.xml`, `drawer_item_bg.xml`, `drawer_item_text.xml`) provide
  the destination screens. Back-press closes the drawer instead of
  exiting the HUD when it's open. Activities registered in
  `AndroidManifest.xml`.
- **10-Point Calibration Walk + Send Feedback (D1=C, D3=gated)**: New
  `CalibrationWalkActivity` + `activity_calibration_walk.xml` step the
  user through ten phone poses (distance, tilt, pan, lighting) and
  persist `calibration_walk_completed` in `auriga_prefs` on completion.
  New `FeedbackActivity` + `activity_feedback.xml` collect a category
  (bug/accuracy/idea/other), free-text description and optional reply-to
  email, auto-attaching the device profile, app version, calibration
  profile label and the latest diagnostic snapshot mirrored from
  `MainActivity#lastDiagnosticSnapshot`. Submissions go through
  `FeedbackSubmitter` which POSTs JSON to `BuildConfig.FEEDBACK_ENDPOINT`
  (defaults to `https://drakosanctis-auriga.netlify.app/.netlify/functions/submit-feedback`)
  with a graceful `mailto:` fallback to `BuildConfig.FEEDBACK_MAILTO`
  when the endpoint is unreachable. Both URLs are overridable at build
  time via `-PfeedbackEndpoint` / `-PfeedbackMailto` Gradle properties or
  `AURIGA_FEEDBACK_*` env vars. The drawer's "Send Feedback" row dims
  and shows an amber gate hint until the walk is done; tapping a gated
  row launches the walk so users always have a forward path.
- **Web Send Feedback page (`AURIGA/web_deploy/feedback.html`)**: Mirrors
  the Android `FeedbackActivity` for blind/low-vision users on the PWA.
  Same JSON shape, same endpoint (`/.netlify/functions/submit-feedback`),
  same graceful `mailto:` fallback when the network or function is down.
  Auto-attaches a browser profile (UA, language, screen dims, online
  status, cookie support, current URL) so accuracy reports correlate
  with what the visitor actually had loaded. Linked from `index.html`
  nav and the `reader.html` topbar with a 44 dp pill that matches the
  state-pill styling.
- **Drawer v0.5 — full string-resource migration + flavor differentiation (complete)**: All
  hardcoded strings in the drawer panel of `activity_main.xml` replaced with `@string/`
  references defined in `src/main/res/values/strings.xml`. NAVIGATE and DIAGNOSTICS section
  headers now use `@color/flavor_accent` (cyan `#00B8D4` for navi, red `#D50000` for sentinel)
  for both `textColor` and `shadowColor`, making them auto-differentiate at build time.
  The engine-label TextView likewise now reads `@color/flavor_accent`. Flavor-specific string
  overrides live in `src/navi/res/values/strings.xml` and `src/sentinel/res/values/strings.xml`;
  sentinel overrides: brand name, tagline, engine label, sys status, HUD sub, reader sub,
  calibrate sub, feedback sub, diag overlay sub, both contribute-row subs.

- **Netlify Function (`AURIGA/netlify/functions/submit-feedback.js`)**:
  Accepts the JSON payload, validates length, logs a one-line summary
  to Netlify function logs, and optionally fan-outs to a generic webhook
  (`FEEDBACK_FORWARD_WEBHOOK` env var, e.g. Slack/Discord/Zapier) and/or
  files a GitHub issue (`FEEDBACK_GITHUB_REPO` + `FEEDBACK_GITHUB_TOKEN`)
  with `user-feedback` + `cat:<category>` labels. `netlify.toml` now
  declares `functions = "AURIGA/netlify/functions"` so the function ships
  on every Netlify build of the public site.
