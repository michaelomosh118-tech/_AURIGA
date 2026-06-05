/* ═══════════════════════════════════════════════════════════════════════
   AurigaLLM — on-device Llama 3.2 1B inference via WebLLM + WebGPU
   Runs entirely in-browser. No API key. Fully offline after first load.

   Flow:
     1. On first use, asks the user (via voice) if they want to download
        the AI model (~700 MB, stored permanently in browser cache).
     2. Downloads in the background — does not block page use.
     3. Once loaded, every Jarvis question is answered by the local model
        with full conversation context injected from AurigaMemory.
     4. If WebGPU is unavailable or the model isn't loaded yet,
        returns null so jarvis.js falls through to the API/KB fallback.

   window.AurigaLLM:
     .status          — 'idle' | 'consent-needed' | 'loading' | 'ready' | 'unavailable'
     .progress        — 0–100 download progress
     .ask(text, ctx)  — Promise<string|null>
     .requestDownload()  — trigger download after user consent
     .decline()          — user said no, remember the choice
═══════════════════════════════════════════════════════════════════════ */
(function () {
  'use strict';

  var MODEL_ID         = 'Llama-3.2-1B-Instruct-q4f32_1-MLC';
  var WEB_LLM_CDN      = 'https://esm.run/@mlc-ai/web-llm';
  var PREF_KEY         = 'auriga-llm-consent';   // 'yes' | 'no'
  var MAX_NEW_TOKENS   = 80;    /* voice assistant — keep responses short  */
  var CHAT_MAX_TOKENS  = 350;   /* chat page — full conversational replies */

  var status   = 'idle';
  var progress = 0;
  var engine   = null;
  var loading  = false;

  /* ── Check WebGPU availability ──────────────────────────────── */
  function hasWebGPU() {
    return !!(navigator.gpu);
  }

  /* ── System prompt builder ──────────────────────────────────── */
  function buildSystemPrompt(profileCtx) {
    var base = 'You are Auriga, a calm and precise AI assistant for blind and low-vision users. ' +
      'Answer in plain spoken English — no markdown, no bullet points, no emojis. ' +
      'Keep every answer under 40 words. Lead with the most important fact. ' +
      'For objects, give tactile and spatial descriptions. ' +
      'For navigation, always state direction and distance clearly.';
    if (profileCtx && profileCtx.trim()) {
      base += ' User profile: ' + profileCtx.trim();
    }
    return base;
  }

  /* ── Load the model in the background ──────────────────────── */
  function loadModel() {
    if (loading || engine) return;
    if (!hasWebGPU()) {
      status = 'unavailable';
      notifyStatus();
      return;
    }
    loading = true;
    status  = 'loading';
    notifyStatus();

    /* Dynamic ES-module import — avoids polluting the global scope */
    import(WEB_LLM_CDN).then(function (webllm) {
      engine = new webllm.MLCEngine();

      engine.setInitProgressCallback(function (report) {
        /* report.progress is 0–1 */
        progress = Math.round((report.progress || 0) * 100);
        notifyProgress(progress, report.text || '');
      });

      return engine.reload(MODEL_ID);
    }).then(function () {
      status   = 'ready';
      loading  = false;
      progress = 100;
      notifyStatus();
      notifyProgress(100, 'AI model loaded and ready.');
      /* Announce to user */
      speak('Your offline AI assistant is now ready. I can answer questions without an internet connection.');
    }).catch(function (err) {
      status  = 'unavailable';
      loading = false;
      engine  = null;
      notifyStatus();
      console.warn('[AurigaLLM] load failed:', err && err.message);
    });
  }

  /* ── Main inference call ────────────────────────────────────── */
  function ask(text, memoryContext) {
    if (status !== 'ready' || !engine) return Promise.resolve(null);

    return (window.AurigaMemory
      ? window.AurigaMemory.getProfileContext()
      : Promise.resolve('')
    ).then(function (profileCtx) {

      var messages = [
        { role: 'system', content: buildSystemPrompt(profileCtx) }
      ];

      /* Inject relevant memory context as prior turns */
      if (memoryContext && memoryContext.trim()) {
        memoryContext.trim().split('\n').forEach(function (line) {
          var colon = line.indexOf(':');
          if (colon === -1) return;
          var role = line.slice(0, colon).trim().toLowerCase();
          var content = line.slice(colon + 1).trim();
          if (role === 'user')   messages.push({ role: 'user',      content: content });
          if (role === 'auriga') messages.push({ role: 'assistant', content: content });
        });
      }

      messages.push({ role: 'user', content: text });

      return engine.chat.completions.create({
        messages:   messages,
        max_tokens: MAX_NEW_TOKENS,
        temperature: 0.45,
        stream:     false
      });
    }).then(function (reply) {
      var content = reply &&
        reply.choices &&
        reply.choices[0] &&
        reply.choices[0].message &&
        reply.choices[0].message.content;
      return content ? content.trim() : null;
    }).catch(function (err) {
      console.warn('[AurigaLLM] inference error:', err && err.message);
      return null;
    });
  }

  /* ── Streaming inference (for chat.html) ───────────────────── */
  /*
   * Streams tokens one chunk at a time.
   *   messages  — full messages array including system prompt
   *   onChunk   — called with each text delta (string)
   *   onDone    — called with no args when stream ends
   *   onError   — called with error when something fails
   */
  function streamAsk(messages, onChunk, onDone, onError) {
    if (status !== 'ready' || !engine) {
      if (onError) onError(new Error('model-not-ready'));
      return;
    }

    /* Use async IIFE — WebGPU / WebLLM requires modern Chrome anyway */
    (async function () {
      try {
        var stream = await engine.chat.completions.create({
          messages:    messages,
          max_tokens:  CHAT_MAX_TOKENS,
          temperature: 0.7,
          top_p:       0.92,
          stream:      true
        });

        /* Consume the AsyncGenerator */
        for await (var chunk of stream) {
          var delta = (chunk &&
                       chunk.choices &&
                       chunk.choices[0] &&
                       chunk.choices[0].delta &&
                       chunk.choices[0].delta.content) || '';
          if (delta) onChunk(delta);
        }

        if (onDone) onDone();
      } catch (err) {
        console.warn('[AurigaLLM] stream error:', err && err.message);
        if (onError) onError(err);
      }
    })();
  }

  /* ── Consent flow ───────────────────────────────────────────── */
  function checkConsent() {
    try {
      var pref = localStorage.getItem(PREF_KEY);
      if (pref === 'yes') { loadModel(); return; }
      if (pref === 'no')  { status = 'unavailable'; notifyStatus(); return; }
    } catch (_) {}

    /* No decision yet — prompt the user (once, via voice) */
    status = 'consent-needed';
    notifyStatus();

    /* Delay slightly so page TTS has settled */
    setTimeout(function () {
      speak(
        'Auriga can download an offline AI model — about 700 megabytes — ' +
        'so I can answer any question without an internet connection. ' +
        'Say "download AI" to start, or "skip AI" to use online answers only.'
      );
    }, 4000);
  }

  function requestDownload() {
    try { localStorage.setItem(PREF_KEY, 'yes'); } catch (_) {}
    loadModel();
  }

  function decline() {
    try { localStorage.setItem(PREF_KEY, 'no'); } catch (_) {}
    status = 'unavailable';
    notifyStatus();
    speak('No problem. I will use online answers when available.');
  }

  /* ── Status/progress notification helpers ───────────────────── */
  var statusListeners   = [];
  var progressListeners = [];

  function notifyStatus() {
    statusListeners.forEach(function (fn) { try { fn(status); } catch (_) {} });
    /* Keep assistant.html status dot in sync */
    updateStatusDot();
  }

  function notifyProgress(pct, msg) {
    progressListeners.forEach(function (fn) { try { fn(pct, msg); } catch (_) {} });
    updateStatusDot();
  }

  function updateStatusDot() {
    var dot  = document.getElementById('jv-status-dot');
    var text = document.getElementById('jv-status-text');
    if (!dot) return;
    dot.classList.remove('llm-loading', 'llm-ready', 'llm-unavailable');
    if (status === 'loading') {
      dot.classList.add('llm-loading');
      if (text && progress > 0) text.textContent = 'Downloading AI model… ' + progress + '%';
    } else if (status === 'ready') {
      dot.classList.add('llm-ready');
      if (text) text.textContent = 'AI ready — fully offline';
    }
  }

  /* ── Speak helper (defer to Jarvis/AurigaAnnounce if available) */
  function speak(text) {
    if (window.Jarvis && window.Jarvis.speak) { window.Jarvis.speak(text, 'llm-sys'); return; }
    if (!('speechSynthesis' in window)) return;
    var u = new SpeechSynthesisUtterance(text);
    u.rate = 0.95;
    speechSynthesis.speak(u);
  }

  /* ── Wire "download AI" / "skip AI" voice commands into Jarvis  */
  function wireVoiceCommands() {
    if (!window.Jarvis || !window.Jarvis.registerSkill) return;

    window.Jarvis.registerSkill({
      name: 'Download offline AI',
      description: 'start downloading the offline AI model',
      match: [
        /\bdownload\s+(ai|llm|model|offline\s+ai|the\s+model)\b/i,
        /\binstall\s+(ai|the\s+model)\b/i,
        /\byes,?\s+download\b/i,
        /\b(get|fetch)\s+(the\s+)?offline\s+(ai|model)\b/i
      ],
      handle: function () {
        requestDownload();
        return 'Starting AI model download. This will take a few minutes depending on your connection. I will let you know when it is ready.';
      }
    });

    window.Jarvis.registerSkill({
      name: 'Skip offline AI',
      description: 'skip downloading the offline AI model',
      match: [
        /\bskip\s+(ai|llm|download|the\s+model)\b/i,
        /\bno\s+(ai|model|download)\b/i,
        /\bdont\s+download\b/i,
        /\bnot\s+now\b/i
      ],
      handle: function () {
        decline();
        return 'Skipped. I will use online answers when available.';
      }
    });

    window.Jarvis.registerSkill({
      name: 'AI model status',
      description: 'check the status of the offline AI model',
      match: [
        /\bai\s+status\b/i,
        /\bmodel\s+status\b/i,
        /\bis\s+(the\s+)?ai\s+(ready|loaded|available)\b/i,
        /\bhow\s+is\s+the\s+ai\b/i
      ],
      handle: function () {
        var msgs = {
          'idle':           'The offline AI model has not been set up yet. Say "download AI" to start.',
          'consent-needed': 'Waiting for your decision. Say "download AI" to start or "skip AI" to skip.',
          'loading':        'The AI model is downloading — ' + progress + '% complete.',
          'ready':          'The offline AI model is fully loaded and running on your device.',
          'unavailable':    'The offline AI is not available — either declined or your browser does not support WebGPU.'
        };
        return msgs[status] || 'AI status unknown.';
      }
    });
  }

  /* ── Boot ───────────────────────────────────────────────────── */
  function boot() {
    if (!hasWebGPU()) {
      status = 'unavailable';
      return;
    }
    /* Wire voice commands immediately */
    if (window.Jarvis) {
      wireVoiceCommands();
    } else {
      /* jarvis.js may load after us — wait for it */
      document.addEventListener('jarvis:ready', wireVoiceCommands, { once: true });
    }

    /* Check consent and maybe start loading in idle time */
    if ('requestIdleCallback' in window) {
      requestIdleCallback(checkConsent, { timeout: 3000 });
    } else {
      setTimeout(checkConsent, 2000);
    }
  }

  /* Run after DOM is interactive */
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    setTimeout(boot, 500);
  }

  /* ── Public API ─────────────────────────────────────────────── */
  window.AurigaLLM = {
    get status()   { return status; },
    get progress() { return progress; },
    ask:             ask,
    streamAsk:       streamAsk,
    requestDownload: requestDownload,
    decline:         decline,
    onStatus:        function (fn) { statusListeners.push(fn); },
    onProgress:      function (fn) { progressListeners.push(fn); }
  };

})();
