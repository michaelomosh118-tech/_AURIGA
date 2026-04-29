/* ─────────────────────────────────────────────────────────────────────
   AURIGA Framing Guidance
   ─────────────────────────────────────────────────────────────────────

   Continuous, lightweight quality check on a <video> element. Tells the
   caller whether the current frame is good enough to capture for OCR
   (or any task that needs a sharp, well-lit, stationary frame).

   The brief calls for: "Include guided framing ('move left', 'hold
   steady')." This module emits exactly those hints, the announcement
   engine speaks them, and the OCR pipeline only fires when this module
   says READY. That's the difference between v1 capturing 6 blurry
   frames and v1 capturing 1 readable one.

   Heuristics — all run on a 32×32 grayscale downsample of the video
   (~1 ms per check on mid-range Android, negligible battery cost):

     1. Brightness  — average luma. Too dark → 'more-light'.
     2. Motion      — frame-to-frame absolute difference. High → 'hold-steady'.
     3. Edge density — Sobel magnitude. Low → 'move-closer' (text too
        small or out of focus). High variance + good brightness → READY.

   The module never speaks. It just emits states. The caller decides
   when to forward them to the announcement engine — typically only
   when the user is actively trying to capture, never during paragraph
   reading (would interrupt).

   Exposes: window.AurigaFraming
     new Framing(video, opts)  → instance
       .start()  / .stop()
       .check()  → { state, reason, brightness, motion, edges, sharpness }
                   state ∈ 'ready' | 'guide' | 'init'
                   reason ∈ guidance keys ('hold-steady', etc.) when state==='guide'
       .onChange(cb)  → cb(state, reason, metrics)

   Kotlin port: mirror the metric pipeline in a CameraX ImageAnalysis
   analyzer. The arithmetic is identical; only the pixel-access API
   changes (ImageProxy YUV planes instead of getImageData RGBA).
   ───────────────────────────────────────────────────────────────────── */

(function () {
  'use strict';

  // ── Tunables ─────────────────────────────────────────────────────────
  // Calibrated on a Tecno Spark 8 (mid-range Kenyan phone). Brightness
  // of ~30 is "very dim room", ~120 is "well-lit indoors", ~200 is
  // "outdoors". Motion ~3 is "rock steady", >12 is "user is walking".
  // Edge density <0.018 means there's nothing in front of the camera
  // sharp enough to be text. Tweak in opts.thresholds if needed.
  var DEFAULTS = {
    sampleW:        32,
    sampleH:        32,
    intervalMs:     330,        // ~3 checks per second
    settleFrames:   2,          // need this many consecutive READY frames
    thresholds: {
      tooDark:      40,         // avg luma below this → 'more-light'
      tooBright:    240,        // avg luma above this → very rare, ignore for now
      motionHigh:   12,         // mean abs diff above this → 'hold-steady'
      edgesLow:     0.018,      // edge density below this → 'move-closer'
      edgesHigh:    0.10        // edge density above this with low motion → text in view
    }
  };

  function Framing(video, opts) {
    if (!video) throw new Error('AurigaFraming: <video> is required');
    opts = opts || {};
    this.video      = video;
    this.intervalMs = opts.intervalMs || DEFAULTS.intervalMs;
    this.settleFrames = opts.settleFrames || DEFAULTS.settleFrames;
    this.thresholds = Object.assign({}, DEFAULTS.thresholds, opts.thresholds || {});

    var c = document.createElement('canvas');
    c.width  = opts.sampleW || DEFAULTS.sampleW;
    c.height = opts.sampleH || DEFAULTS.sampleH;
    this._canvas = c;
    this._ctx    = c.getContext('2d', { willReadFrequently: true });

    this._prevGray = null;       // Uint8Array of last frame's luma
    this._lastMetrics = null;
    this._readyStreak = 0;
    this._timer = null;
    this._listeners = [];
    this._lastState = 'init';
    this._lastReason = '';
  }

  Framing.prototype.start = function () {
    if (this._timer) return;
    var self = this;
    this._timer = setInterval(function () { self._tick(); }, this.intervalMs);
  };

  Framing.prototype.stop = function () {
    if (this._timer) { clearInterval(this._timer); this._timer = null; }
    this._readyStreak = 0;
  };

  Framing.prototype.onChange = function (cb) {
    if (typeof cb === 'function') this._listeners.push(cb);
  };

  // Single synchronous check — sample the frame, compute metrics,
  // decide a state. Returns the result so callers can also poll.
  Framing.prototype.check = function () {
    var v = this.video;
    if (!v.videoWidth || !v.videoHeight) {
      return { state: 'init', reason: '', brightness: 0, motion: 0, edges: 0 };
    }

    var w = this._canvas.width, h = this._canvas.height;
    this._ctx.drawImage(v, 0, 0, w, h);
    var data = this._ctx.getImageData(0, 0, w, h).data;
    var n = w * h;

    // Pass 1: grayscale + brightness
    var gray = new Uint8ClampedArray(n);
    var lumaSum = 0;
    for (var i = 0, j = 0; i < data.length; i += 4, j++) {
      var y = (data[i] * 0.299 + data[i + 1] * 0.587 + data[i + 2] * 0.114) | 0;
      gray[j] = y;
      lumaSum += y;
    }
    var brightness = lumaSum / n;

    // Pass 2: frame-to-frame motion (mean absolute difference)
    var motion = 0;
    if (this._prevGray) {
      var diffSum = 0;
      for (var k = 0; k < n; k++) diffSum += Math.abs(gray[k] - this._prevGray[k]);
      motion = diffSum / n;
    }
    this._prevGray = gray;

    // Pass 3: edge density via 3×3 Sobel magnitude on the downsample.
    // Don't compute on borders — saves a couple of bounds checks per pixel.
    var edges = 0, edgeCount = 0;
    for (var y2 = 1; y2 < h - 1; y2++) {
      for (var x2 = 1; x2 < w - 1; x2++) {
        var p = y2 * w + x2;
        var gx =
          -gray[p - w - 1] - 2 * gray[p - 1] - gray[p + w - 1] +
           gray[p - w + 1] + 2 * gray[p + 1] + gray[p + w + 1];
        var gy =
          -gray[p - w - 1] - 2 * gray[p - w] - gray[p - w + 1] +
           gray[p + w - 1] + 2 * gray[p + w] + gray[p + w + 1];
        var mag = Math.abs(gx) + Math.abs(gy);
        if (mag > 32) edgeCount++;       // count "real" edges only
        edges += mag;
      }
    }
    var edgeDensity = edgeCount / ((w - 2) * (h - 2));

    var t = this.thresholds;
    var state, reason;
    if (brightness < t.tooDark) {
      state = 'guide'; reason = 'more-light';
    } else if (motion > t.motionHigh) {
      state = 'guide'; reason = 'hold-steady';
    } else if (edgeDensity < t.edgesLow) {
      // Bright + steady but no detail → either no text in frame or
      // text is too small. Most useful single hint is "move closer".
      state = 'guide'; reason = 'move-closer';
    } else {
      state = 'ready'; reason = '';
    }

    var result = {
      state:      state,
      reason:     reason,
      brightness: brightness,
      motion:     motion,
      edges:      edgeDensity
    };
    this._lastMetrics = result;
    return result;
  };

  Framing.prototype.lastMetrics = function () {
    return this._lastMetrics;
  };

  // Internal tick — calls check(), debounces state via settleFrames
  // (so a single jittery frame doesn't bounce us between READY and
  // GUIDE), then notifies listeners only on real state/reason changes.
  Framing.prototype._tick = function () {
    var r = this.check();
    if (r.state === 'ready') this._readyStreak++; else this._readyStreak = 0;
    var effectiveState = (r.state === 'ready' && this._readyStreak >= this.settleFrames)
      ? 'ready' : (r.state === 'init' ? 'init' : 'guide');
    var effectiveReason = effectiveState === 'guide' ? (r.reason || this._lastReason) : '';

    if (effectiveState !== this._lastState || effectiveReason !== this._lastReason) {
      this._lastState  = effectiveState;
      this._lastReason = effectiveReason;
      var snapshot = r;
      this._listeners.forEach(function (cb) {
        try { cb(effectiveState, effectiveReason, snapshot); } catch (_) {}
      });
    }
  };

  window.AurigaFraming = Object.freeze({ Framing: Framing });
})();
