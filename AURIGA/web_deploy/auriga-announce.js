/* ─────────────────────────────────────────────────────────────────────
   AURIGA Announcement Engine
   ─────────────────────────────────────────────────────────────────────

   The single source of truth for how AURIGA talks.

   THE HOUSE STYLE — non-negotiable:

       Every announcement follows: <what> + <distance> + <bearing>.
       If a feature can't say all three, it doesn't ship in that feature.

   Examples it produces:

       Object find  →  "Keys, 1.2 meters, slightly right."
       OCR          →  "Sign, 80 centimeters, ahead: 'Tusker 100 bob'."
       SkyShield    →  "Pole, 60 centimeters, ahead, stop."
       Failure      →  "Lens covered."   /   "Too dark."

   Why this exists as a standalone module:
   - Reused by every feature that talks (object find, OCR, scene, alerts).
   - Testable in isolation (open browser console, call AurigaAnnounce.compose…).
   - Direct reference for the Kotlin port on the Android side — every
     function below has a 1:1 Kotlin equivalent. Trae can mirror this
     file line-for-line.

   Exposes: window.AurigaAnnounce
     compose.objectFound(label, meters, degrees)        → string
     compose.ocrFound(kind, text, meters, degrees)      → string
     compose.alert(hazard, meters, degrees)             → string
     compose.failure(reason)                            → string
     compose.guidance(reason)                           → string
     format.distance(meters)                            → string
     format.bearing(degrees, mode='narrow'|'wide')      → string
     Speaker(opts)                                      → instance with .say(text, key)
   ───────────────────────────────────────────────────────────────────── */

(function () {
  'use strict';

  // ── Distance phrasing ────────────────────────────────────────────────
  // Rules:
  //   < 1.0 m  → centimeters, rounded to nearest 10 ("80 centimeters")
  //   ≈ 0.5 m  → "half a meter"  (0.45–0.55)
  //   ≈ 1.0 m  → "one meter"     (0.95–1.05)
  //   ≈ N m    → "N meters" when within ±0.05 of a whole (≥ 2)
  //   else     → "X point Y meters" (one decimal max)
  //
  // We deliberately never say raw floats like "1.27 meters" — that's
  // cognitive load with no useful precision. One decimal is plenty.
  function formatDistance(meters) {
    if (!Number.isFinite(meters) || meters < 0) return 'unknown distance';

    if (meters >= 0.45 && meters <= 0.55) return 'half a meter';
    if (meters >= 0.95 && meters <= 1.05) return 'one meter';

    if (meters < 1.0) {
      var cm = Math.max(10, Math.round(meters * 10) * 10);
      return cm + ' centimeters';
    }

    var whole = Math.round(meters);
    if (whole >= 2 && Math.abs(meters - whole) <= 0.05) {
      return whole + ' meters';
    }

    return meters.toFixed(1) + ' meters';
  }

  // ── Bearing phrasing ─────────────────────────────────────────────────
  // Two modes, because the right vocabulary depends on the sensor:
  //
  //   'narrow' (default) — phone camera, ~±30° from forward.
  //     Clock positions in this range collapse to 11/12/1 which is silly.
  //     Use directional words instead — they're more intuitive AND match
  //     how blind navigators already describe space.
  //
  //   'wide' — full 360° spatial awareness (your GhostAnchor / spatial
  //     engine on Android). Clock positions are the right primitive
  //     here because they map to a body-relative compass.
  //
  // Convention: degrees signed, 0 = directly ahead, negative = left,
  // positive = right. For 'wide' mode we accept any value and wrap.
  function formatBearing(degrees, mode) {
    if (!Number.isFinite(degrees)) return 'ahead';
    mode = mode || 'narrow';

    if (mode === 'narrow') {
      var d = degrees;
      if (Math.abs(d) < 5)  return 'ahead';
      if (d <= -15)         return 'to your left';
      if (d < 0)            return 'slightly left';
      if (d >= 15)          return 'to your right';
      return 'slightly right';
    }

    // wide — full 360°, return clock positions.
    // 0° = 12 o'clock; +30° per hour clockwise.
    var norm = ((degrees % 360) + 360) % 360;        // 0..360
    var hour = Math.round(norm / 30);                // 0..12
    if (hour === 0 || hour === 12) return 'ahead';
    if (hour === 6) return 'behind you';
    if (hour === 3) return 'to your right';
    if (hour === 9) return 'to your left';
    return hour + " o'clock";
  }

  // ── Composers ────────────────────────────────────────────────────────
  // These produce the actual strings the speaker reads. Order is locked:
  // <what> + <distance> + <bearing>. Do not reorder. Do not add adverbs
  // ("roughly", "approximately"). Do not pad ("I see…"). The user's
  // attention is the scarcest resource — every word costs them.
  function composeObjectFound(label, meters, degrees, opts) {
    opts = opts || {};
    var mode = opts.bearingMode || 'narrow';
    var what = String(label || 'object').trim();
    return capitalize(what) + ', ' + formatDistance(meters)
         + ', ' + formatBearing(degrees, mode) + '.';
  }

  function composeOcrFound(kind, text, meters, degrees, opts) {
    opts = opts || {};
    var mode = opts.bearingMode || 'narrow';
    var k = String(kind || 'text').trim();
    var t = String(text || '').trim();
    return capitalize(k) + ', ' + formatDistance(meters)
         + ', ' + formatBearing(degrees, mode)
         + (t ? ": '" + t + "'." : '.');
  }

  function composeAlert(hazard, meters, degrees, opts) {
    opts = opts || {};
    var mode = opts.bearingMode || 'narrow';
    var h = String(hazard || 'obstacle').trim();
    return capitalize(h) + ', ' + formatDistance(meters)
         + ', ' + formatBearing(degrees, mode) + ', stop.';
  }

  // Failure speech — the recovery acceptance criterion:
  //   "Lens covered, sun glare, low light, drop, pocket — app says
  //    what's wrong, never goes silent."
  // Keep these short, declarative, no apologies, no instructions
  // (the user knows what to do once they know what's wrong).
  var FAILURE_PHRASES = {
    'lens-covered':    'Lens covered.',
    'too-dark':        'Too dark.',
    'no-camera':       'Camera unavailable.',
    'model-loading':   'Loading detection model.',
    'model-failed':    'Detection model failed to load.',
    'no-targets':      'No targets set. Watching everything.',
    'target-lost':     'Target lost.',
    'scanning':        'Scanning.',
    'no-text':         'No text detected.',
    'ocr-loading':     'Loading reader.',
    'ocr-failed':      'Reader failed.'
  };
  function composeFailure(reason) {
    return FAILURE_PHRASES[reason] || capitalize(String(reason || 'unknown error'));
  }

  // Framing guidance — short imperative phrases the framing module
  // emits while the user is trying to point the camera at text/an
  // object. Distinct channel from `failure` because guidance is a
  // recoverable, repeatable hint, not an error. Keep these to two
  // words max — the user is moving and listening at the same time.
  var GUIDANCE_PHRASES = {
    'hold-steady':  'Hold steady.',
    'move-closer':  'Move closer.',
    'move-back':    'Move back.',
    'more-light':   'More light.',
    'move-left':    'Move left.',
    'move-right':   'Move right.',
    'center-text':  'Center the text.',
    'ready':        'Ready.'
  };
  function composeGuidance(reason) {
    return GUIDANCE_PHRASES[reason] || capitalize(String(reason || ''));
  }

  function capitalize(s) {
    if (!s) return s;
    return s.charAt(0).toUpperCase() + s.slice(1);
  }

  // ── Speaker ──────────────────────────────────────────────────────────
  // A small wrapper around the Web Speech API that enforces:
  //   - Quiet mode: only announce on CHANGE (key changes), not every frame.
  //   - Repeat throttle: same key won't repeat within `repeatMs` ms.
  //   - Hard mute: cancel in-flight utterances; keep cancelling for ~1s
  //     to defeat the Android WebView bug where one .cancel() doesn't
  //     stop a long utterance already handed to the engine.
  //
  // The Kotlin port should mirror this contract exactly using
  // android.speech.tts.TextToSpeech with QUEUE_FLUSH on changes and
  // a coroutine-based debounce. The behavioural surface is the same.
  function Speaker(opts) {
    opts = opts || {};
    this.repeatMs   = Number.isFinite(opts.repeatMs)   ? opts.repeatMs   : 3500;
    this.minGapMs   = Number.isFinite(opts.minGapMs)   ? opts.minGapMs   : 500;
    this.rate       = Number.isFinite(opts.rate)       ? opts.rate       : 1.05;
    this.pitch      = Number.isFinite(opts.pitch)      ? opts.pitch      : 0.95;
    this.volume     = Number.isFinite(opts.volume)     ? opts.volume     : 1.0;
    this.enabled    = opts.enabled !== false;

    this._lastKey   = '';
    this._lastAt    = 0;
    this._lastSaid  = '';
    this._guardTimer = null;
  }

  Speaker.prototype.supported = function () {
    return typeof window !== 'undefined' && 'speechSynthesis' in window;
  };

  // Say `text`. `key` is the de-dup key (e.g. the object label, or
  // 'failure:lens-covered'). Same key won't repeat within repeatMs.
  // Different key always preempts (cancel + speak).
  Speaker.prototype.say = function (text, key) {
    if (!this.enabled) return false;
    if (!this.supported()) return false;
    if (!text) return false;
    var now = Date.now();
    var k = key || text;

    if (k === this._lastKey && (now - this._lastAt) < this.repeatMs) return false;
    if ((now - this._lastAt) < this.minGapMs) return false;

    this._lastKey  = k;
    this._lastAt   = now;
    this._lastSaid = text;

    try { window.speechSynthesis.cancel(); } catch (_) {}
    var u = new SpeechSynthesisUtterance(text);
    u.rate   = this.rate;
    u.pitch  = this.pitch;
    u.volume = this.volume;
    try { window.speechSynthesis.speak(u); } catch (_) {}
    return true;
  };

  Speaker.prototype.setEnabled = function (on) {
    this.enabled = !!on;
    if (!this.enabled) this.hardMute();
  };

  // Defeats the Android WebView "cancel doesn't really cancel"
  // long-utterance bug by re-cancelling for ~1s.
  Speaker.prototype.hardMute = function () {
    if (!this.supported()) return;
    var self = this;
    try { window.speechSynthesis.cancel(); } catch (_) {}
    if (self._guardTimer) clearInterval(self._guardTimer);
    var ticks = 0;
    self._guardTimer = setInterval(function () {
      try { window.speechSynthesis.cancel(); } catch (_) {}
      if (++ticks > 10 || self.enabled) {
        clearInterval(self._guardTimer);
        self._guardTimer = null;
      }
    }, 100);
  };

  // ── Public surface ───────────────────────────────────────────────────
  window.AurigaAnnounce = Object.freeze({
    compose: Object.freeze({
      objectFound: composeObjectFound,
      ocrFound:    composeOcrFound,
      alert:       composeAlert,
      failure:     composeFailure,
      guidance:    composeGuidance
    }),
    format: Object.freeze({
      distance: formatDistance,
      bearing:  formatBearing
    }),
    Speaker: Speaker
  });
})();
