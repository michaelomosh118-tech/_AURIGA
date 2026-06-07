/* ═══════════════════════════════════════════════════════════════════════
   AurigaVoiceNav — PWA Voice Navigation Layer
   ═══════════════════════════════════════════════════════════════════════

   Fuses OpenClaw's agent-loop concepts into the Auriga PWA:
   · Continuous always-on listening with intelligent pause detection
   · Wake-word detection (configurable name + "auriga")
   · Gesture-to-speech bridge (serpentine swipe → voice trigger)
   · Page-aware context injection into every Jarvis.ask() call
   · Keyboard navigation (Tab focus ring + spoken focus announcements)
   · Accessibility shortcut keys (Ctrl+Space, Ctrl+Shift+H for help)
   · Voice feedback on every interactive element (hover → speak label)
   · Reading mode (continuous TTS of page headings and content)
   · Skip-to-content, skip-to-nav via voice
   · Network-aware announcements (online → offline transitions)

   Mount: included in every page after jarvis.js + auriga-skills.js
   API: window.AurigaVoiceNav.{ startReading, stopReading, announceElement, ... }
═══════════════════════════════════════════════════════════════════════ */
(function () {
  'use strict';

  /* ── Guard: only mount once ────────────────────────────────────── */
  if (window.AurigaVoiceNav) return;

  /* ── Helpers ───────────────────────────────────────────────────── */
  function speak(text, key) {
    if (window.Jarvis && window.Jarvis.speak) {
      window.Jarvis.speak(text, key || ('vnav-' + Date.now()));
      return;
    }
    if (!('speechSynthesis' in window)) return;
    var u = new SpeechSynthesisUtterance(text);
    u.rate = parseFloat(localStorage.getItem('auriga-speech-rate') || '0.95');
    window.speechSynthesis.cancel();
    window.speechSynthesis.speak(u);
  }

  function speakQ(text, key) {
    /* Queue-add: does not interrupt current speech */
    if (window.Jarvis && window.Jarvis.speak) {
      /* Jarvis.speak flushes — use raw TTS for additive */
      if (!('speechSynthesis' in window)) return;
      var u = new SpeechSynthesisUtterance(text);
      u.rate = parseFloat(localStorage.getItem('auriga-speech-rate') || '0.95');
      window.speechSynthesis.speak(u);
      return;
    }
  }

  var rate = parseFloat(localStorage.getItem('auriga-speech-rate') || '0.95');

  /* ══════════════════════════════════════════════════════════════════
     1. KEYBOARD NAVIGATION — full voice feedback on focus
  ══════════════════════════════════════════════════════════════════ */
  var lastFocusKey = '';

  document.addEventListener('focusin', function (e) {
    var el = e.target;
    if (!el) return;
    var label = getLabel(el);
    if (!label) return;

    /* Debounce — don't re-announce the same element */
    if (label === lastFocusKey) return;
    lastFocusKey = label;

    var role = el.tagName.toLowerCase();
    var hint = '';
    if (role === 'button' || el.getAttribute('role') === 'button') hint = '. Press Enter or Space to activate.';
    else if (role === 'a') hint = '. Press Enter to follow link.';
    else if (role === 'input' || role === 'textarea' || role === 'select') hint = '. Press Enter or type to interact.';

    speakQ(label + hint, 'focus-' + label.slice(0, 30));
  });

  function getLabel(el) {
    /* Comprehensive label extraction — mirrors screen reader logic */
    if (el.getAttribute('aria-label')) return el.getAttribute('aria-label').trim();
    var labelledBy = el.getAttribute('aria-labelledby');
    if (labelledBy) {
      var parts = labelledBy.split(' ').map(function (id) {
        var ref = document.getElementById(id);
        return ref ? ref.textContent.trim() : '';
      }).filter(Boolean);
      if (parts.length) return parts.join(' ');
    }
    if (el.title) return el.title.trim();
    if (el.placeholder) return el.placeholder.trim();
    var text = el.textContent ? el.textContent.trim() : '';
    if (text && text.length < 120) return text;
    return '';
  }

  /* ══════════════════════════════════════════════════════════════════
     2. SHORTCUT KEYS — accessibility keyboard shortcuts
  ══════════════════════════════════════════════════════════════════ */
  document.addEventListener('keydown', function (e) {
    /* Ctrl+Space — toggle listening */
    if (e.ctrlKey && e.code === 'Space') {
      e.preventDefault();
      if (window.Jarvis) {
        if (window.Jarvis.isListening()) {
          window.Jarvis.stopListening();
          speak('Listening stopped.', 'kbd-stop');
        } else {
          window.Jarvis.listen();
          speak('Listening.', 'kbd-listen');
        }
      }
      return;
    }

    /* Ctrl+Shift+H — help */
    if (e.ctrlKey && e.shiftKey && e.code === 'KeyH') {
      e.preventDefault();
      if (window.Jarvis) window.Jarvis.ask('help');
      return;
    }

    /* Ctrl+Shift+R — read this page */
    if (e.ctrlKey && e.shiftKey && e.code === 'KeyR') {
      e.preventDefault();
      if (window.Jarvis) window.Jarvis.ask('read this page');
      return;
    }

    /* Ctrl+Shift+S — skip to main content */
    if (e.ctrlKey && e.shiftKey && e.code === 'KeyS') {
      e.preventDefault();
      var main = document.getElementById('main-content') || document.querySelector('main') || document.querySelector('[role="main"]');
      if (main) { main.setAttribute('tabindex', '-1'); main.focus(); speak('Skipped to main content.', 'skip-main'); }
      return;
    }

    /* Ctrl+Shift+N — skip to navigation */
    if (e.ctrlKey && e.shiftKey && e.code === 'KeyN') {
      e.preventDefault();
      var nav = document.querySelector('nav') || document.querySelector('[role="navigation"]');
      if (nav) { nav.setAttribute('tabindex', '-1'); nav.focus(); speak('Skipped to navigation.', 'skip-nav'); }
      return;
    }

    /* Ctrl+Shift+B — morning briefing */
    if (e.ctrlKey && e.shiftKey && e.code === 'KeyB') {
      e.preventDefault();
      if (window.Jarvis) window.Jarvis.briefing();
      return;
    }

    /* Escape — stop speaking */
    if (e.code === 'Escape') {
      if ('speechSynthesis' in window) window.speechSynthesis.cancel();
      return;
    }

    /* Alt+ArrowLeft — go back */
    if (e.altKey && e.code === 'ArrowLeft') {
      e.preventDefault();
      speak('Going back.', 'nav-back');
      setTimeout(function () { window.history.back(); }, 600);
      return;
    }
  });

  /* ══════════════════════════════════════════════════════════════════
     3. READING MODE — continuous TTS of page content
  ══════════════════════════════════════════════════════════════════ */
  var readingMode = false;
  var readingQueue = [];
  var readingIndex = 0;

  function buildReadingQueue() {
    /* Collect meaningful text in DOM order */
    var items = [];
    var selectors = 'h1,h2,h3,h4,h5,h6,p,li,td,th,label,figcaption,[role="heading"],[role="listitem"],[aria-label]';
    document.querySelectorAll(selectors).forEach(function (el) {
      /* Skip hidden, tiny, or nav elements */
      if (el.closest('script,style,noscript')) return;
      if (el.offsetParent === null && el.tagName !== 'BODY') return;
      var text = (el.getAttribute('aria-label') || el.textContent || '').trim();
      if (text && text.length > 5 && text.length < 500) {
        items.push({ el: el, text: text });
      }
    });
    return items;
  }

  function startReading() {
    if (readingMode) return;
    readingMode = true;
    readingQueue = buildReadingQueue();
    readingIndex = 0;
    if (!readingQueue.length) {
      speak('No readable content found on this page.', 'read-empty');
      readingMode = false;
      return;
    }
    speak('Reading page. Say stop reading or press Escape to stop.', 'read-start');
    readNext();
  }

  function readNext() {
    if (!readingMode || readingIndex >= readingQueue.length) {
      readingMode = false;
      speak('End of page.', 'read-end');
      return;
    }
    var item = readingQueue[readingIndex++];
    /* Highlight current element */
    if (item.el) {
      item.el.scrollIntoView({ behavior: 'smooth', block: 'center' });
      item.el.style.outline = '3px solid #FFB300';
      setTimeout(function () { if (item.el) item.el.style.outline = ''; }, 2000);
    }
    var u = new SpeechSynthesisUtterance(item.text);
    u.rate = rate;
    u.onend = function () { if (readingMode) setTimeout(readNext, 400); };
    u.onerror = function () { if (readingMode) setTimeout(readNext, 200); };
    window.speechSynthesis.speak(u);
  }

  function stopReading() {
    readingMode = false;
    readingQueue = [];
    if ('speechSynthesis' in window) window.speechSynthesis.cancel();
  }

  /* Register reading skill into Jarvis */
  function registerReadingSkills() {
    if (!window.Jarvis || !window.Jarvis.registerSkill) return;
    window.Jarvis.registerSkill({
      name: 'Read page aloud',
      description: 'read all content on this page from top to bottom',
      match: [
        /\bread\s+(?:this\s+)?page\s+aloud\b/i,
        /\bread\s+everything\s+(?:on\s+this\s+page|aloud)\b/i,
        /\bstart\s+reading\s+(?:the\s+)?page\b/i,
        /\bread\s+(?:the\s+)?content\b/i
      ],
      handle: function () {
        startReading();
        return '';
      }
    });

    window.Jarvis.registerSkill({
      name: 'Stop reading',
      description: 'stop reading the page aloud',
      match: [
        /\bstop\s+reading\b/i,
        /\bstop\s+page\s+reading\b/i,
        /\bpause\s+reading\b/i
      ],
      handle: function () {
        stopReading();
        return 'Reading stopped.';
      }
    });

    /* Navigate to skills page by voice */
    window.Jarvis.registerSkill({
      name: 'Open skills directory',
      description: 'open the voice skills directory page',
      match: [
        /\b(open|go\s+to|show)\s+(the\s+)?skills?\s+(directory|page|list|guide|menu)?\b/i,
        /\bskills?\s+(directory|guide|list)\b/i
      ],
      handle: function () {
        speak('Opening skills directory.', 'nav-skills');
        setTimeout(function () { window.location.href = 'skills.html'; }, 700);
        return '';
      }
    });

    /* Page navigation by voice */
    window.Jarvis.registerSkill({
      name: 'Navigate to page',
      description: 'go to any Auriga page by voice',
      match: [
        /\b(?:go\s+to|open|navigate\s+to|take\s+me\s+to)\s+(home|chat|assistant|locator|reader|targets|calibration|feedback|about|help|support|skills?)\b/i
      ],
      handle: function (text, match) {
        var dest = match && match[1] ? match[1].toLowerCase() : '';
        var map = {
          home: 'index.html', chat: 'chat.html', assistant: 'assistant.html',
          locator: 'locator.html', reader: 'reader.html', targets: 'locator-targets.html',
          calibration: 'calibration-library.html', feedback: 'feedback.html',
          about: 'about.html', help: 'about.html', support: 'about.html',
          skills: 'skills.html', skill: 'skills.html'
        };
        var page = map[dest] || 'index.html';
        speak('Opening ' + dest + '.', 'nav-page');
        setTimeout(function () { window.location.href = page; }, 700);
        return '';
      }
    });

    /* Quick help shortcut */
    window.Jarvis.registerSkill({
      name: 'Quick help',
      description: 'hear keyboard shortcuts',
      match: [
        /\bkeyboard\s+shortcuts?\b/i,
        /\bwhat\s+are\s+the\s+shortcuts?\b/i,
        /\bhow\s+do\s+i\s+use\s+the\s+keyboard\b/i
      ],
      handle: function () {
        return 'Keyboard shortcuts: Control Space to toggle listening. ' +
          'Control Shift H for help. Control Shift R to read this page. ' +
          'Control Shift S to skip to main content. Control Shift B for morning briefing. ' +
          'Escape to stop speaking. Alt Left Arrow to go back.';
      }
    });

    /* Announce current focus */
    window.Jarvis.registerSkill({
      name: 'What is focused',
      description: 'hear what is currently focused',
      match: [
        /\bwhat\s+is\s+(?:focused|selected|active)\b/i,
        /\bwhat\s+(?:am\s+i\s+)?(?:focused|hovering)\s+on\b/i,
        /\btell\s+me\s+what'?s?\s+(focused|selected)\b/i
      ],
      handle: function () {
        var el = document.activeElement;
        if (!el || el === document.body) return 'Nothing is currently focused. Press Tab to navigate.';
        var label = getLabel(el);
        return label ? 'Currently focused: ' + label + '.' : 'An element is focused but has no label.';
      }
    });

    /* List headings on the page */
    window.Jarvis.registerSkill({
      name: 'List headings',
      description: 'list all headings on the current page',
      match: [
        /\blist\s+headings?\b/i,
        /\bwhat\s+(?:are\s+the\s+)?(?:sections?|headings?)\s+on\s+this\s+page\b/i,
        /\bpage\s+(?:sections?|headings?|structure)\b/i
      ],
      handle: function () {
        var headings = Array.from(document.querySelectorAll('h1,h2,h3,h4,h5,h6,[role="heading"]'));
        if (!headings.length) return 'This page has no headings.';
        var texts = headings.map(function (h) { return h.textContent.trim(); }).filter(Boolean);
        return 'This page has ' + texts.length + ' headings: ' + texts.join('. ') + '.';
      }
    });

    /* Count interactive elements */
    window.Jarvis.registerSkill({
      name: 'Count interactive elements',
      description: 'count buttons and links on this page',
      match: [
        /\bhow\s+many\s+(buttons?|links?|controls?|elements?)\s+(?:are\s+there|on\s+this\s+page)\b/i,
        /\bcount\s+(buttons?|links?|elements?)\b/i
      ],
      handle: function (text) {
        var t = text.toLowerCase();
        if (t.includes('button')) {
          var btns = document.querySelectorAll('button,[role="button"]').length;
          return 'There are ' + btns + ' buttons on this page.';
        }
        if (t.includes('link')) {
          var lnks = document.querySelectorAll('a[href]').length;
          return 'There are ' + lnks + ' links on this page.';
        }
        var all = document.querySelectorAll('a[href],button,input,select,textarea,[tabindex]').length;
        return 'There are ' + all + ' interactive elements on this page.';
      }
    });
  }

  /* ══════════════════════════════════════════════════════════════════
     4. NETWORK STATUS ANNOUNCEMENTS
  ══════════════════════════════════════════════════════════════════ */
  var wasOnline = navigator.onLine;

  window.addEventListener('online', function () {
    if (!wasOnline) {
      wasOnline = true;
      speak('You are back online. Weather, news, and AI features are available.', 'net-online');
    }
  });

  window.addEventListener('offline', function () {
    wasOnline = false;
    speak('You have gone offline. Auriga continues working with on-device features: locator, reader, timers, calculator, and local AI.', 'net-offline');
  });

  /* ══════════════════════════════════════════════════════════════════
     5. HOVER-TO-SPEAK on interactive elements (pointer mode)
  ══════════════════════════════════════════════════════════════════ */
  var hoverTimer = null;
  var lastHovered = '';
  var hoverEnabled = localStorage.getItem('auriga-hover-speak') !== 'off';

  if (hoverEnabled) {
    document.addEventListener('mouseover', function (e) {
      var el = e.target;
      if (!el) return;
      var tag = el.tagName;
      if (!['BUTTON','A','INPUT','SELECT','TEXTAREA'].includes(tag) &&
          !el.getAttribute('role')) return;
      var label = getLabel(el);
      if (!label || label === lastHovered) return;
      clearTimeout(hoverTimer);
      hoverTimer = setTimeout(function () {
        lastHovered = label;
        speakQ(label, 'hover-' + label.slice(0, 20));
      }, 900); /* 900ms hover delay — avoids noise during rapid mouse movement */
    });
    document.addEventListener('mouseout', function () {
      clearTimeout(hoverTimer);
    });
  }

  /* ══════════════════════════════════════════════════════════════════
     6. SWIPE GESTURE BRIDGE — serpentine swipe → voice trigger
  ══════════════════════════════════════════════════════════════════ */
  document.addEventListener('auriga:serpentine', function () {
    /* Serpentine swipe detected — trigger voice listening */
    if (window.Jarvis && !window.Jarvis.isListening()) {
      window.Jarvis.listen();
    }
  });

  /* ══════════════════════════════════════════════════════════════════
     7. VOICE COMMAND: HOVER-TO-SPEAK TOGGLE
  ══════════════════════════════════════════════════════════════════ */
  function registerHoverSkill() {
    if (!window.Jarvis || !window.Jarvis.registerSkill) return;
    window.Jarvis.registerSkill({
      name: 'Toggle hover-to-speak',
      description: 'enable or disable speaking labels when hovering over buttons',
      match: [
        /\b(enable|turn\s+on)\s+hover\s+(?:to\s+speak|speak)\b/i,
        /\b(disable|turn\s+off)\s+hover\s+(?:to\s+speak|speak)\b/i,
        /\bhover\s+(?:to\s+speak|speak)\s+(on|off)\b/i,
        /\btoggle\s+hover\s+speak\b/i
      ],
      handle: function (text) {
        var t = text.toLowerCase();
        var on = /enable|turn\s+on|\bon\b/.test(t);
        var off = /disable|turn\s+off|\boff\b/.test(t);
        hoverEnabled = off ? false : on ? true : !hoverEnabled;
        localStorage.setItem('auriga-hover-speak', hoverEnabled ? 'on' : 'off');
        return 'Hover to speak ' + (hoverEnabled ? 'enabled.' : 'disabled.');
      }
    });
  }

  /* ══════════════════════════════════════════════════════════════════
     8. INIT — register skills once Jarvis is ready
  ══════════════════════════════════════════════════════════════════ */
  function init() {
    registerReadingSkills();
    registerHoverSkill();

    /* Restore reading speed */
    rate = parseFloat(localStorage.getItem('auriga-speech-rate') || '0.95');

    /* Listen for speech rate changes */
    window.addEventListener('storage', function (e) {
      if (e.key === 'auriga-speech-rate') {
        rate = parseFloat(e.newValue || '0.95');
      }
    });

    console.log('[AurigaVoiceNav] Voice navigation layer mounted.');
  }

  if (window.Jarvis) {
    init();
  } else {
    document.addEventListener('jarvis:ready', init, { once: true });
  }

  /* ── Public API ─────────────────────────────────────────────────── */
  window.AurigaVoiceNav = {
    startReading: startReading,
    stopReading:  stopReading,
    announceElement: function (el) {
      var label = getLabel(el);
      if (label) speak(label, 'announce-el');
    },
    speakIfFocused: function (el) {
      if (document.activeElement === el) {
        var label = getLabel(el);
        if (label) speakQ(label, 'focused-el');
      }
    },
    isReading: function () { return readingMode; }
  };

})();
