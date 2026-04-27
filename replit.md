# DrakoSanctis // Auriga Ecosystem

## Overview
Static landing/PWA site for the Auriga Ecosystem by DrakoSanctis. The published site is served from `AURIGA/web_deploy`, which contains:
- `index.html` — main landing page (sections: HOME, ECOSYSTEM, POSITION, NAVI, SENTINEL, AERO, INDUSTRIAL)
- `calibration-library.html` — calibration library page
- `manifest.json`, `sw.js`, `logo.png` — PWA assets
- `data/` — JSON datasets used by the calibration pages

The repository also contains an Android app skeleton (`AURIGA/app`, Gradle) and a marketing HTML (`AURIGA/DrakoSanctis_LandingPage.html`), but the public web product is the static site under `web_deploy`.

## Strategy & Roadmap
Living spec: `AURIGA/docs/SCALING_STRATEGY.md` (mirrored on the site as the **POSITION** section in `index.html`). Covers:
- Current capability inventory and competitive matrix vs. Seeing AI, Lookout, Be My Eyes, Envision, OrCam.
- Three strategic wedges: integrated stack · 100% on-device · free vs. $4,500.
- 5-phase build roadmap (Phase 1 native YOLOv8n + OCR polish → Phase 5 hardware partnerships).

The Android-native features in Phases 1–3 (PaddleOCR, Piper TTS, Vosk wake word, Moondream 2, OsmAnd) live in `AURIGA/app` and require the Android SDK/NDK toolchain — track them as separate work items, not in this Replit session.

## Replit Setup
- Workflow `Start application` serves `AURIGA/web_deploy` via `node server.js` on `0.0.0.0:5000`.
- `server.js` (project root): Node.js HTTP server that serves static files from `AURIGA/web_deploy` and also handles the feedback endpoint at `/.netlify/functions/submit-feedback`, replacing the Netlify serverless function for Replit compatibility.
- Deployment is configured as a `static` site with `publicDir = AURIGA/web_deploy`.

## Netlify Auto-Deploy
- `package.json` pulls in `netlify-cli` as a dev dependency.
- `scripts/deploy-netlify.js` does a one-shot production deploy of
  `AURIGA/web_deploy` (and `AURIGA/netlify/functions`) to the live
  Netlify site identified by the `NETLIFY_SITE_ID` secret using the
  `NETLIFY_AUTH_TOKEN` secret. Run it manually with `npm run deploy`,
  or `npm run deploy:draft` for a non-production preview deploy.
- `scripts/watch-deploy.js` watches `AURIGA/web_deploy` and
  `AURIGA/netlify/functions` and triggers a debounced production
  deploy whenever any file changes (default debounce 4000 ms,
  override with `NETLIFY_WATCH_DEBOUNCE_MS`).
- Workflow `Netlify Auto-Deploy` runs the watcher in the background so
  every save in Replit publishes to https://aurigaecosystem.netlify.app
  automatically.

## Notes
- The feedback endpoint (`/.netlify/functions/submit-feedback`) is the
  single source of truth for all comms (web AND APK). `server.js`
  re-exports the Netlify Function handler, so dev (Replit) and prod
  (Netlify) run identical code.
- Pipeline per submission: validate → mint ticket ID `AUR-<CAT>-<XXXX>`
  → notification email to project Gmail (Reply-To: user's email)
  → auto-acknowledgement reply to user → mirror to GitHub Issue
  → optional generic webhook. Each sink is best-effort; the request
  succeeds if any sink succeeds.
- Required Netlify env vars: `GMAIL_USER`, `GMAIL_APP_PASSWORD`.
- Optional Netlify env vars: `GMAIL_FROM_NAME`,
  `FEEDBACK_GITHUB_REPO`, `FEEDBACK_GITHUB_TOKEN`,
  `FEEDBACK_FORWARD_WEBHOOK`.
- Bug + "Need help" categories require a reply-to email. Idea + Other
  accept anonymous submissions but warn that no ticket reference will
  be sent.
- Web client persists failed submissions to IndexedDB (db
  `auriga-feedback-queue`, store `pending`) and replays them on the
  `online` event. Service worker `sw.js` (cache `drakosanctis-v5`) also
  registers a `sync` handler under tag `auriga-feedback-flush` so
  browsers that support Background Sync drain the queue when the OS
  reports the network is back, even with the tab closed.
- APK client persists failed submissions to SharedPreferences (file
  `auriga_feedback_queue`, key `pending_payloads`, cap 32) and flushes
  them whenever the Send Feedback screen opens or a fresh submission
  succeeds. Permanent (4xx) failures fall through to a mailto: composer
  pointed at `BuildConfig.FEEDBACK_MAILTO` (default
  `drakosanctis@gmail.com`).
- About page (`about.html` web, `AboutActivity` APK) exposes both the
  project Gmail (drakosanctis@gmail.com) and the maintainer's personal
  Gmail (oluochmichael975@gmail.com — Michael Omondi). Addresses are
  assembled at runtime from username + provider so they don't sit in
  the source HTML/XML as raw scrape-bait.
- CrashReportActivity SHARE button now pre-fills `EXTRA_EMAIL` with
  the project Gmail and uses `[AURIGA · CRASH] <build>` as the subject
  so picking Gmail/Outlook from the share sheet produces an
  already-addressed draft.
- Service worker registers automatically on load (PWA).

## Recent Changes
- **In-drawer VOICE / HAPTIC mute toggles for the WebView HUD**:
  Two new tappable rows under SETUP let the user instantly mute or
  un-mute the Object Locator's voice readout and detection vibrations
  when the HUD becomes disruptive. State is persisted to
  `MainActivity.PREFS_NAME` under `locator_web_voice_enabled` and
  `locator_web_haptic_enabled` (default ON), restored on every
  `LocatorWebActivity.onCreate`, and pushed live into the WebView via
  `evaluateJavascript`. The injection (`buildMutePatchJs`) is
  idempotent: on first run it stashes the original
  `speechSynthesis.speak` and `navigator.vibrate` references, then
  swaps in wrappers that gate on `window.__aurigaVoiceEnabled` /
  `window.__aurigaHapticEnabled`. Subsequent runs (every toggle, every
  `onPageFinished`) just refresh the two flags. Muting voice also
  fires `speechSynthesis.cancel()` so any utterance already in flight
  stops immediately. Sub-labels read "ON · tap to mute" / "MUTED · tap
  to enable" and a Toast confirms each toggle. Zero web/PWA changes —
  the patches live entirely on the native shell.

- **Native rich hamburger drawer restored on top of `LocatorWebActivity`**:
  The APK now wraps the WebView HUD in the same `DrawerLayout` chrome
  the legacy native HUD used. `activity_locator_web.xml` was rebuilt
  with a `DrawerLayout` root: main content = WebView + fallback +
  floating `≡` MENU pill (top-start, `diag_toggle_bg`); side panel =
  the rich Auriga drawer (NAVIGATE: HUD / Reader / Targets / About;
  SETUP: 10-Point Calibration Walk amber row + Send Feedback with
  the same calibration-walk-completed gate hint MainActivity uses;
  SUPPORT: Help & Tips + Support Centre; CONTRIBUTE: Submit Calibration
  + SDK Interest). Toggle/diagnostics rows that only apply to the
  native camera HUD (haptic, voice, quiet hours, DIAG overlay,
  pin-DIAG, system/engine status console) were left out so the drawer
  matches what the WebView HUD can actually surface. `LocatorWebActivity`
  imports `DrawerLayout` + `Gravity`, wires every row through a local
  `safeStart(Class, label)` helper that toasts on failure, closes the
  drawer first on Back, and falls back to `webView.canGoBack()` before
  exiting. The website's PWA drawer is suppressed inside the WebView
  by injecting a `<style id="auriga-native-shell-hide-web-nav">…</style>`
  block from `WebViewClient.onPageFinished` on every page load
  (locator → targets → locator stays clean), hiding `#navToggle`,
  `.nav-toggle`, `.nav-drawer`, `#navLinks`, `.nav-scrim`, `#navScrim`
  so only the native pill is ever visible. No web/PWA changes —
  `nav-drawer.js`, the SW cache (`drakosanctis-v8`), and the public
  site stay exactly as they were.

- **Reverted website hamburger to its original 3-item Tools section**:
  The earlier overhaul added a `TARGETS` link to the website's
  `nav-drawer.js`, but that drawer is shared with the public PWA where
  Targets is an APK-only concept. Removed the line so the website
  drawer now matches what shipped before the locator overhaul
  (Calibration Library / Object Locator / DrakoVoice Reader). The
  Targets page itself (`locator-targets.html`) still exists and is
  reachable from the locator's TARGETS button and the per-target
  controls — only the global drawer entry was rolled back. SW cache
  bumped to v8.

- **APK launch crash fix — Theme.AppCompat requirement**: The launcher
  `LocatorWebActivity` (and the previously-broken `ReaderActivity`)
  extended `androidx.appcompat.app.AppCompatActivity` while their
  manifest themes (`AurigaHUDTheme`, `AurigaDocTheme`) inherit from
  `android:Theme.NoTitleBar.Fullscreen` — not an AppCompat theme. On
  every launch this crashed with `IllegalStateException: You need to
  use a Theme.AppCompat theme (or descendant) with this activity`.
  Neither activity actually used any AppCompat-specific API (no action
  bar, no support delegate, no AppCompatDelegate), and every other
  activity in the app already extends plain `android.app.Activity`.
  Switched both to `extends Activity` so they match the rest of the
  app and stop demanding an AppCompat-parented theme — no XML/theme
  edits needed.

- **Release APK build fix — Gradle 8.5 implicit-dependency validation**:
  AGP's release lint pipeline (`generate<Variant>LintVitalReportModel`,
  `lintVital*`, `lintAnalyze*`) scans the merged asset source-sets,
  which include `build/generated/web_assets` written by our
  `copyWebDeployToAssets` task. Wiring only `mergeAssetsProvider` was
  enough for debug builds but Gradle 8.5 fails release builds with an
  "implicit dependency" error. `AURIGA/app/build.gradle` now adds a
  `tasks.configureEach { ... }` block that declares
  `dependsOn 'copyWebDeployToAssets'` on every task whose name starts
  with `lint` or matches `generate*Lint*`, so the staged web bundle is
  always in place before lint reads it. Debug + release variants of
  navi/garden/spirit flavours all build cleanly.

- **Locator targets — inline notes editor + per-target focus deep-link**:
  Each card on `locator-targets.html` now shows three round actions:
  amber `⨁` (Focus), cyan `✎` (Edit notes), red `×` (Delete). The edit
  button swaps the description block for an inline textarea with
  Save/Cancel; Save calls `Store.update(name, {description})` and
  re-renders, Esc cancels, ⌘/Ctrl-Enter saves. The focus button is a
  link to `locator.html?focus=<name>`. On the locator page, a new
  `readFocusFromUrl()` helper parses that param at init, validates the
  target still exists in the store, and overrides `activeNameSet` to
  just that one class for the session — the persisted target list is
  never mutated. A cyan FOCUS pill appears under the topbar with an
  EXIT button that strips the URL param via `history.replaceState` and
  falls back to the full active set. SW cache bumped to v7.

- **Object Locator UX overhaul (web → APK via WebView)**: The locator
  now models targets as rich objects (`{name, description, lastSeenAt,
  lastBearing, lastDistance}`) instead of bare class-name strings, with
  transparent migration from the old chip-only storage. Three files
  changed in `AURIGA/web_deploy/`:
  - **New `locator-store.js`** — single source of truth for the targets
    list (key `auriga-locator-targets`). Exposes `load/save/add/remove/
    update/touch/names/has` plus the frozen 80-class `COCO_CLASSES`
    list. `touch(name, bearing, distance)` records a sighting (timestamp
    + last location) the locator calls every ~1.5 s while a tracked
    target is the primary detection.
  - **New `locator-targets.html`** — dedicated targets management page.
    Search-as-you-type over all 80 COCO classes, "Add Target" dialog
    with mission-notes textarea, list of active targets showing name,
    description, last-seen relative time, and last location (distance +
    bearing). Already-tracked classes appear disabled in the picker so
    you can't double-add. `CLEAR ALL` confirms before wiping.
  - **Rewritten `locator.html`** — keeps the proven TF.js + COCO-SSD
    detection engine and the muted-voice hardening, but replaces the
    chip grid with an L-bracket corner frame, a centre reticle/cross-
    hair, an amber "⨁ <CLASS> acquired" banner that lights up when a
    tracked target is locked, and a compact summary card with a "+"
    FAB linking to the targets page. The bottom controls are now
    VOICE / TARGETS / CLEAR. Sightings are persisted via
    `Store.touch()` and re-read on `visibilitychange`/`focus`/cross-tab
    `storage` events so the "last seen" caption stays fresh when the
    user returns from the targets page.
  - `nav-drawer.js` Tools section gains a `TARGETS` link between
    `OBJECT LOCATOR` and `DRAKOVOICE READER`.
  - `sw.js` cache bumped to `drakosanctis-v6`; pre-caches the new
    `locator-targets.html` shell and `locator-store.js` module.
  Because `AURIGA/app/build.gradle`'s `copyWebDeployToAssets` task
  mirrors `web_deploy/` into the APK's WebView assets every build, the
  Android `LocatorWebActivity` automatically picks up the same UX —
  no Java changes needed.

- **APK becomes a web replica + locator mute hardening**: The Android
  app now ships with a new `LocatorWebActivity` (Java) that hosts a
  `WebView` loading the bundled `web_deploy/locator.html`, so the
  in-app HUD is byte-for-byte the same surface the public PWA serves.
  A new Gradle task `copyWebDeployToAssets` in `AURIGA/app/build.gradle`
  stages `AURIGA/web_deploy` into `build/generated/web_assets/web/`
  and is wired into every variant's `mergeAssets` step, with `sw.js`
  and `data/` excluded (the WebView loads over `file://` so the SW
  never registers, and the calibration JSON is unused by the locator).
  Camera permission is brokered through `WebChromeClient.onPermissionRequest`
  so `getUserMedia` works without per-page prompts. The launcher
  intent-filter moved from `MainActivity` to `LocatorWebActivity` in
  `AURIGA/app/src/main/AndroidManifest.xml`; the legacy native HUD
  (ML Kit + native DrakoVoice) stays installed for diagnostics but is
  no longer the default screen.
  In `AURIGA/web_deploy/locator.html` the VOICE ON/OFF mute button now
  reliably silences the speech readout on Android Chrome and the
  System WebView: a new `applyVoiceState()` helper persists the
  preference under `localStorage['auriga-locator-voice']`, runs a
  10-tick / 1-second cancel guard (`speechSynthesis.cancel()` every
  100 ms) right after muting, and `maybeSpeak()` defensively
  re-cancels on every detection frame while muted to defeat the known
  WebView bug where a single `.cancel()` does not stop a long
  in-flight utterance. Service-worker cache bumped to
  `drakosanctis-v4` so existing PWA clients pick up the fix.
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

- **Object Locator (Web)**: New `AURIGA/web_deploy/locator.html` — runs
  TensorFlow.js + COCO-SSD lite fully in-browser (cached offline by `sw.js`
  v3) for real on-device object detection. Draws bounding boxes over the
  live camera feed, reports real distance (m/cm) from box height and
  bearing (°L/°R) from horizontal offset, voices the primary detection,
  and ships a chip-grid TARGETS picker exposing all 80 COCO classes
  persisted to `localStorage` under `auriga-locator-targets`. Linked from
  the Tools section of the hamburger drawer. The home-page NAVI HUD
  (`index.html`) also exposes a "▶ GO LIVE" button that swaps in the
  same detector and replaces the previous `Math.random` distance/bearing
  with measured values.
- **Object Targets (APK)**: New `TargetsActivity` + `activity_targets.xml`
  + `target_chip_bg.xml` / `target_chip_selected_bg.xml` give the Android
  app the same picker concept. Categories use the 5 labels emitted by
  Google ML Kit's bundled object-detection classifier (Fashion good,
  Home good, Food, Place, Plant) so what the user picks is exactly what
  gets matched at runtime. `TargetStore.java` persists the selection as
  CSV under SharedPreferences key `auriga_targets` inside `auriga_prefs`,
  with an `_any_` wildcard sentinel that means "match every detection"
  (the default for new installs, preserving pre-Targets behaviour).
  `MainActivity` initialises an `ObjectDetector` (STREAM_MODE,
  multi-objects, classification), submits a throttled detection on every
  frame (350 ms cadence), and gates the existing haptic + DrakoVoice
  triggers in `processNavigationFrame` through `isTargetGateOpen()` --
  so when the user narrows beyond ANY OBJECT, the HUD only buzzes /
  speaks for the categories they picked. The matched label is prefixed
  to the spoken signature so the user knows why an announcement fired.
  New drawer row `nav_targets` (between Reader and About) launches the
  picker; `onResume` reloads `activeTargets` from disk so changes apply
  immediately on return. Dependency added: `com.google.mlkit:object-detection:17.0.2`
  (bundled, fully offline). `TargetsActivity` registered in `AndroidManifest.xml`.
- **DrakoVoice Reader OCR fix (Web + APK)**: Web `reader.html` now crops
  to the on-screen reticle (≈72vw × 38vh capped at 540×320), 2× upscales
  the crop, applies grayscale + contrast stretch (`preprocessForOcr`),
  and runs Tesseract with PSM 6 + 300 DPI hint + `preserve_interword_spaces=1`.
  Image uploads receive the same preprocessing. APK `ReaderActivity`
  already used the bundled offline ML Kit Latin recognizer
  (`com.google.mlkit:text-recognition:16.0.0`); confirmed it ships the
  model inside the APK and runs without Play Services / network.
- **Service worker v3 (`AURIGA/web_deploy/sw.js`)**: Bumped cache name,
  pre-caches the reader / locator / calibration / feedback shells and
  the new nav-drawer asset, and opportunistically caches every
  successful GET (so heavy CDN bundles like TF.js / COCO-SSD /
  Tesseract.js work fully offline on the second visit).

- **Netlify Function (`AURIGA/netlify/functions/submit-feedback.js`)**:
  Accepts the JSON payload, validates length, logs a one-line summary
  to Netlify function logs, and optionally fan-outs to a generic webhook
  (`FEEDBACK_FORWARD_WEBHOOK` env var, e.g. Slack/Discord/Zapier) and/or
  files a GitHub issue (`FEEDBACK_GITHUB_REPO` + `FEEDBACK_GITHUB_TOKEN`)
  with `user-feedback` + `cat:<category>` labels. `netlify.toml` now
  declares `functions = "AURIGA/netlify/functions"` so the function ships
  on every Netlify build of the public site.
