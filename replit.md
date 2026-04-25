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
