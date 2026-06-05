/* ─────────────────────────────────────────────────────────────────────
   AURIGA Voice Navigation Engine  —  auriga-voice.js
   ─────────────────────────────────────────────────────────────────────

   What this file does:
     1. First-run onboarding: asks the user to name their assistant.
        No name → no voice features until they do.
     2. Serpentine swipe gesture to grant mic permission:
        Swipe from the left border, curve down then up to screen centre.
     3. Mic FAB: long-press centre of screen (mobile) or Ctrl+Space
        (desktop) activates listening mode.
     4. Wake-word detection: once the assistant is named, every
        utterance that starts with "<NAME> AURIGA" (or just the name
        alone as a quick trigger) activates listening mode.
     5. Command routing: matches spoken text against a command table
        and executes the right action (navigate, read, start locator,
        open feedback, etc.).
     6. Screen-reader-style page announcer: on every new page the
        assistant reads out the page name and offers a guided tour.

   Storage keys (localStorage):
     auriga-voice-name       — assistant name, set on first run
     auriga-voice-enabled    — "1" when voice nav is switched on
     auriga-voice-tour-done  — "1" after the user completes the tour

   Globals exposed:
     window.AurigaVoice
       .speak(text)           — TTS (uses AurigaAnnounce.Speaker)
       .listen()              — activate mic
       .stopListening()       — deactivate mic
       .setEnabled(bool)      — master on/off
       .getName()             — current assistant name
   ───────────────────────────────────────────────────────────────────── */

(function () {
  'use strict';

  /* ── Constants ───────────────────────────────────────────────────── */
  var STORAGE_NAME    = 'auriga-voice-name';
  var STORAGE_ENABLED = 'auriga-voice-enabled';
  var STORAGE_TOUR    = 'auriga-voice-tour-done';

  var SpeechRecognition =
    window.SpeechRecognition || window.webkitSpeechRecognition || null;

  /* ── State ───────────────────────────────────────────────────────── */
  var assistantName   = '';   // set by user on first run
  var voiceEnabled    = false;
  var listening       = false;
  var recognition     = null;
  var speaker         = null;
  var swipeState      = null; // tracks the serpentine gesture
  var toastTimer      = null;
  var alwaysOn        = false; // continuously restarts mic after each command
  var manualStop      = false; // true when user explicitly stops always-on

  /* ── Speaker (lazy-init after AurigaAnnounce loads) ──────────────── */
  function getSpeaker() {
    if (speaker) return speaker;
    if (window.AurigaAnnounce) {
      speaker = new window.AurigaAnnounce.Speaker({
        rate: 1.0, pitch: 1.0, repeatMs: 800, minGapMs: 200
      });
      speaker.enabled = true;
    }
    return speaker;
  }

  function speak(text, key) {
    var s = getSpeaker();
    if (s) { s.say(text, key || text); return; }
    /* Fallback if AurigaAnnounce isn't loaded yet */
    if (!('speechSynthesis' in window)) return;
    var u = new SpeechSynthesisUtterance(text);
    u.rate = 1.0; u.pitch = 1.0;
    window.speechSynthesis.speak(u);
  }

  /* ── Storage helpers ─────────────────────────────────────────────── */
  function store(key, val) {
    try { localStorage.setItem(key, val); } catch (_) {}
  }
  function recall(key) {
    try { return localStorage.getItem(key); } catch (_) { return null; }
  }

  /* ── Toast ───────────────────────────────────────────────────────── */
  function showToast(msg) {
    var el = document.getElementById('av-toast');
    if (!el) return;
    el.textContent = msg;
    el.classList.remove('av-hidden');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () {
      el.classList.add('av-hidden');
    }, 2600);
  }

  /* ── Transcript bubble ───────────────────────────────────────────── */
  function setTranscript(text, interim) {
    var el = document.getElementById('av-transcript');
    if (!el) return;
    if (!text) { el.classList.add('av-hidden'); return; }
    el.textContent = text;
    el.classList.remove('av-hidden');
    el.classList.toggle('av-interim', !!interim);
  }

  /* ── Listening ring ──────────────────────────────────────────────── */
  function setListeningUI(on) {
    var ring = document.getElementById('av-listen-ring');
    var fab  = document.getElementById('av-mic-fab');
    if (ring) ring.classList.toggle('av-hidden', !on);
    if (fab)  fab.classList.toggle('av-active', on);
    if (!on) setTranscript('');
    if (fab) {
      fab.setAttribute('data-label', on ? 'Listening…' : 'Hold to speak');
      fab.setAttribute('aria-label', on ? 'Listening — tap to stop' : 'Hold or press Ctrl+Space to activate voice');
    }
  }

  /* ── Command table ───────────────────────────────────────────────── */
  /*
   * Design: every command accepts broad natural-language paraphrases.
   * Users shouldn't need to memorise exact phrases. Three tiers:
   *   1. Verb + noun  ("open locator", "start reader")
   *   2. Intent       ("I want to find objects", "help me read")
   *   3. Question     ("what's around me", "what does this say")
   *
   * Unknown commands fall through to Jarvis.ask() for conversational AI.
   */
  var COMMANDS = [
    /* ── Home ── */
    {
      match: [
        /\b(go\s+)?(to\s+)?home\b/i,
        /\bmain\s+page\b/i,
        /\btake\s+me\s+home\b/i,
        /\breturn\s+(to\s+)?home\b/i,
        /\bstart\s+page\b/i,
        /\blanding\s+page\b/i
      ],
      reply: 'Going home.',
      action: function () { navigateTo('index.html'); }
    },
    /* ── Object Locator ── */
    {
      match: [
        /\b(open|start|launch|go\s+to|load)\s+(the\s+)?locator\b/i,
        /\bobject\s+locator\b/i,
        /\bfind\s+objects?\b/i,
        /\b(start|begin)\s+detect(ing|ion)\b/i,
        /\bscan\s+(my\s+)?surroundings?\b/i,
        /\bwhat('s|\s+is)\s+(a?round|nearby|in\s+front)\b/i,
        /\bshow\s+me\s+what'?s?\s+around\b/i,
        /\blook\s+around\b/i,
        /\bidentify\s+objects?\b/i,
        /\bnavigat(e|ion)\s+mode\b/i,
        /\bspatial\s+(view|mode|scan)\b/i,
        /\bdetect(ion)?\s+mode\b/i,
        /\bI\s+want\s+to\s+(find|see)\s+objects?\b/i,
        /\bhelp\s+me\s+(find|navigate)\b/i
      ],
      reply: 'Opening the Object Locator.',
      action: function () { navigateTo('locator.html'); }
    },
    /* ── DrakoVoice Reader ── */
    {
      match: [
        /\b(open|start|launch|go\s+to)\s+(the\s+)?(drako\s*voice\s+)?reader\b/i,
        /\bdrako\s*voice\b/i,
        /\bread\s+(this|text|a?\s*sign|a?\s*document|a?\s*label|a?\s*menu|a?\s*page)\b/i,
        /\bocr(\s+mode)?\b/i,
        /\bscan\s+text\b/i,
        /\bwhat\s+does\s+this\s+say\b/i,
        /\bwhat('s|\s+is)\s+(written|printed|on\s+this)\b/i,
        /\bI\s+want\s+to\s+read\b/i,
        /\bhelp\s+me\s+read\b/i,
        /\breadout\s+mode\b/i,
        /\btext\s+recogni(tion|ze)\b/i,
        /\bdocument\s+reader\b/i
      ],
      reply: 'Opening the DrakoVoice Reader.',
      action: function () { navigateTo('reader.html'); }
    },
    /* ── Calibration ── */
    {
      match: [
        /\b(open|start|launch|go\s+to)\s+(the\s+)?calibrat(e|ion)\b/i,
        /\b(calibration\s+)?library\b/i,
        /\bcalibrate\b/i,
        /\bimprove\s+(accuracy|distance)\b/i,
        /\bsetup\s+calibration\b/i,
        /\brecalibrate\b/i,
        /\b(ten|10)[- ]point\s+calibration\b/i
      ],
      reply: 'Opening the Calibration Library.',
      action: function () { navigateTo('calibration-library.html'); }
    },
    /* ── Send Feedback ── */
    {
      match: [
        /\b(send|open|give|submit)\s+(the\s+)?feedback\b/i,
        /\breport\s+(a\s+)?(bug|issue|problem|error)\b/i,
        /\bI\s+(found|have)\s+a\s+bug\b/i,
        /\bsomething('s|\s+is)\s+wrong\b/i,
        /\bsuggest\s+(an?\s+)?idea\b/i,
        /\bfile\s+a\s+report\b/i,
        /\bfeedback\s+form\b/i
      ],
      reply: 'Opening Send Feedback.',
      action: function () { navigateTo('feedback.html'); }
    },
    /* ── Targets ── */
    {
      match: [
        /\b(open|start|go\s+to|manage|set|edit)\s+(the\s+)?targets?\b/i,
        /\btrack\s+objects?\b/i,
        /\bwhat\s+am\s+I\s+tracking\b/i,
        /\b(add|remove)\s+(a?\s+)?target\b/i,
        /\bwatch\s+list\b/i,
        /\btracking\s+list\b/i
      ],
      reply: 'Opening Targets.',
      action: function () { navigateTo('locator-targets.html'); }
    },
    /* ── About ── */
    {
      match: [
        /\b(open\s+)?about\b/i,
        /\bwho\s+(made|built|created)\s+(this|auriga)\b/i,
        /\btell\s+me\s+about\s+auriga\b/i,
        /\bapp\s+info\b/i
      ],
      reply: 'Opening About.',
      action: function () { navigateTo('about.html'); }
    },
    /* ── Section navigation (home page) ── */
    {
      match: [/\b(go\s+to\s+|show\s+)?ecosystem\b/i],
      reply: 'Showing the Ecosystem.',
      action: function () { showSection('ecosystem'); }
    },
    {
      match: [/\b(go\s+to\s+|show\s+)?((strategic\s+)?position|competitive)\b/i],
      reply: 'Showing Strategic Position.',
      action: function () { showSection('position'); }
    },
    {
      match: [/\b(go\s+to\s+|show\s+)?navi\b/i, /\bauriga\s+navi\b/i],
      reply: 'Showing Auriga Navi.',
      action: function () { showSection('navi'); }
    },
    {
      match: [/\b(go\s+to\s+|show\s+)?sentinel\b/i, /\bauriga\s+sentinel\b/i],
      reply: 'Showing Auriga Sentinel.',
      action: function () { showSection('sentinel'); }
    },
    {
      match: [/\b(go\s+to\s+|show\s+)?aero\b/i, /\bauriga\s+aero\b/i],
      reply: 'Showing Auriga Aero.',
      action: function () { showSection('aero'); }
    },
    {
      match: [/\b(go\s+to\s+|show\s+)?industrial\b/i, /\bauriga\s+industrial\b/i],
      reply: 'Showing Auriga Industrial.',
      action: function () { showSection('industrial'); }
    },
    /* ── Menu controls ── */
    {
      match: [
        /\b(open|show|expand|pull\s+out)\s+(the\s+)?(menu|drawer|sidebar|navigation)\b/i,
        /\bnavigation\s+drawer\b/i,
        /\bmenu\s+please\b/i,
        /\bapp\s+menu\b/i
      ],
      reply: 'Opening the menu.',
      action: function () { if (window.toggleNavDrawer) window.toggleNavDrawer(); }
    },
    {
      match: [
        /\b(close|hide|dismiss|collapse|shut)\s+(the\s+)?(menu|drawer|sidebar)\b/i
      ],
      reply: 'Menu closed.',
      action: function () { if (window.closeNavDrawer) window.closeNavDrawer(); }
    },
    /* ── Scroll / navigate ── */
    {
      match: [
        /\bscroll\s+down\b/i,
        /\bnext\s+section\b/i,
        /\bmove\s+down\b/i,
        /\bmore\s+content\b/i
      ],
      reply: 'Scrolling down.',
      action: function () { window.scrollBy({ top: window.innerHeight * 0.75, behavior: 'smooth' }); }
    },
    {
      match: [
        /\bscroll\s+up\b/i,
        /\bprevious\s+section\b/i,
        /\bmove\s+up\b/i,
        /\bback\s+to\s+top\b/i
      ],
      reply: 'Scrolling up.',
      action: function () { window.scrollBy({ top: -window.innerHeight * 0.75, behavior: 'smooth' }); }
    },
    {
      match: [
        /\bgo\s+back\b/i,
        /\bprevious\s+page\b/i,
        /\bnavigate\s+back\b/i,
        /\bpress\s+back\b/i,
        /\btake\s+me\s+back\b/i
      ],
      reply: 'Going back.',
      action: function () { window.history.back(); }
    },
    /* ── Voice controls ── */
    {
      match: [
        /\bstop\s+(listening|voice|mic|talking|speaking)\b/i,
        /\bquiet\b/i,
        /\b(be\s+)?quiet\b/i,
        /\bshh+\b/i,
        /\bmute\s*(voice|mic|yourself|me)?\b/i,
        /\bsilence\b/i,
        /\bpause\s+voice\b/i,
        /\bthat'?s?\s+enough\b/i
      ],
      reply: 'Voice paused. Long press or press Control Space to resume.',
      action: function () { stopListening(); setEnabled(false); }
    },
    /* ── Help / describe ── */
    {
      match: [
        /\bhelp\b/i,
        /\bwhat\s+can\s+you\s+do\b/i,
        /\b(list|available|show)\s+commands?\b/i,
        /\bwhat\s+can\s+I\s+say\b/i
      ],
      reply: null,
      action: function () { readHelp(); }
    },
    {
      match: [
        /\b(read|describe|summarise|summarize)\s+(this\s+)?page\b/i,
        /\bwhat('s|\s+is)\s+(on\s+)?(this\s+)?page\b/i,
        /\bwhere\s+am\s+I\b/i,
        /\bwhat\s+(screen|page)\s+am\s+I\s+on\b/i,
        /\btell\s+me\s+about\s+this\s+page\b/i,
        /\bdescribe\b/i
      ],
      reply: null,
      action: function () { announcePage(true); }
    },
    /* ── Rename ── */
    {
      match: [
        /\bchange\s+(my\s+)?name\b/i,
        /\brename\b/i,
        /\bnew\s+name\b/i,
        /\bcall\s+you\s+(something|by)\b/i,
        /\bgive\s+you\s+a\s+name\b/i
      ],
      reply: 'Sure. Say your new assistant name.',
      action: function () { startRenameFlow(); }
    }
  ];

  function matchCommand(text) {
    var t = text.trim().toLowerCase();
    for (var i = 0; i < COMMANDS.length; i++) {
      var cmd = COMMANDS[i];
      var patterns = Array.isArray(cmd.match) ? cmd.match : [cmd.match];
      for (var j = 0; j < patterns.length; j++) {
        var p = patterns[j];
        var matched = (typeof p === 'string')
          ? t.indexOf(p.toLowerCase()) !== -1
          : p.test(t);
        if (matched) return cmd;
      }
    }
    return null;
  }

  /* ── Navigation helpers ──────────────────────────────────────────── */
  function navigateTo(href) {
    var base = window.location.pathname.split('/').slice(0, -1).join('/') + '/';
    window.location.href = href;
  }

  function showSection(id) {
    if (typeof window.showSection === 'function') {
      window.showSection(id);
    } else {
      /* Fallback: toggle .active on sections */
      var all = document.querySelectorAll('section[id]');
      var target = document.getElementById(id);
      all.forEach(function (s) { s.classList.remove('active'); });
      if (target) {
        target.classList.add('active');
        target.scrollIntoView({ behavior: 'smooth' });
      }
    }
  }

  /* ── Page announcer ──────────────────────────────────────────────── */
  var PAGE_DESCRIPTIONS = {
    'index.html':             'Home page. DrakoSanctis Auriga Ecosystem. Sections: Home, Ecosystem, Position, Navi, Sentinel, Aero, Industrial.',
    'locator.html':           'Object Locator. Real-time on-device object detection using your camera. Detects and announces objects with distance and bearing.',
    'locator-targets.html':   'Locator Targets. Choose which objects the locator watches for, and review the last time each was seen.',
    'reader.html':            'DrakoVoice Reader. Point your camera at text and the app reads it aloud. Tap the capture button or use Auto mode.',
    'calibration-library.html': 'Calibration Library. Device-specific distance calibration profiles. Select your device model to improve accuracy.',
    'feedback.html':          'Send Feedback. Report a bug, suggest an idea, or ask for help. Your message goes directly to the Auriga team.',
    'about.html':             'About Auriga. Mission, technology, and contact information for DrakoSanctis.'
  };

  var PAGE_GUIDES = {
    'index.html': [
      'Say "ecosystem" to explore the product range.',
      'Say "navi" to learn about Auriga Navi.',
      'Say "open locator" to launch the Object Locator.',
      'Say "open reader" to launch the DrakoVoice Reader.',
      'Say "menu" to open the navigation drawer.'
    ],
    'locator.html': [
      'The locator is now running. Objects are announced with distance and bearing.',
      'Say "set targets" to choose what the locator watches for.',
      'Say "stop" or tap the Voice button to mute announcements.'
    ],
    'reader.html': [
      'Point the camera at text and tap Capture, or say "auto mode" to read continuously.',
      'Say "next paragraph" or "previous paragraph" to move through the text.',
      'Say "stop reading" to pause.'
    ],
    'feedback.html': [
      'This is the feedback form. Fill in the category, describe your issue, and tap Send.',
      'Bug and Support reports require an email address so the team can reply.',
      'Say "go home" to return to the main page.'
    ]
  };

  function currentPage() {
    var p = window.location.pathname.split('/').pop() || 'index.html';
    if (!p || p === '') p = 'index.html';
    return p;
  }

  function announcePage(withGuide) {
    var page = currentPage();
    var desc = PAGE_DESCRIPTIONS[page] || ('You are on ' + document.title + '.');
    speak(desc, 'page-desc');
    if (withGuide) {
      var guide = PAGE_GUIDES[page];
      if (guide && guide.length) {
        var delay = 0;
        guide.forEach(function (line, i) {
          setTimeout(function () { speak(line, 'guide-' + i); }, 2800 + i * 3200);
        });
      }
    }
  }

  function readHelp() {
    var name = assistantName || 'your assistant';
    speak(
      'I am ' + name + ', your Auriga navigation assistant. ' +
      'I can open any page, navigate sections, start the locator or reader, and describe what\'s on screen. ' +
      'Try saying: open locator, go to navi, read this page, or open menu.',
      'help'
    );
  }

  /* ── Rename flow ─────────────────────────────────────────────────── */
  var renamePending = false;
  function startRenameFlow() {
    renamePending = true;
    speak('What would you like to call me? Say your chosen name now.', 'rename-prompt');
  }

  /* ── Speech Recognition ──────────────────────────────────────────── */
  function startListening() {
    if (!SpeechRecognition) {
      speak('Voice recognition is not supported in this browser. Try Chrome or Edge.', 'no-sr');
      showToast('Speech recognition not supported');
      return;
    }
    if (listening) return;
    listening = true;
    setListeningUI(true);

    recognition = new SpeechRecognition();
    recognition.lang = 'en-US';
    recognition.interimResults = true;
    recognition.continuous = false;
    recognition.maxAlternatives = 3;

    recognition.onresult = function (e) {
      var interim = '';
      var final   = '';
      for (var i = e.resultIndex; i < e.results.length; i++) {
        if (e.results[i].isFinal) {
          final += e.results[i][0].transcript;
        } else {
          interim += e.results[i][0].transcript;
        }
      }
      if (interim) setTranscript(interim, true);
      if (final)   handleFinalTranscript(final.trim());
    };

    recognition.onerror = function (e) {
      if (e.error === 'not-allowed' || e.error === 'service-not-allowed') {
        speak('Microphone access was denied. Please allow microphone access and try again.', 'mic-denied');
        showToast('Mic access denied — check browser settings');
      }
      stopListening();
    };

    recognition.onend = function () {
      stopListening();
      /* Always-on: restart mic after a short breath unless manually stopped */
      if (alwaysOn && !manualStop) {
        setTimeout(function () {
          if (alwaysOn && !manualStop && !listening) startListening();
        }, 700);
      }
    };

    try {
      recognition.start();
    } catch (err) {
      stopListening();
    }
  }

  function stopListening() {
    listening = false;
    setListeningUI(false);
    if (recognition) {
      try { recognition.stop(); } catch (_) {}
      recognition = null;
    }
  }

  /* ── Always-on mode ──────────────────────────────────────────────── */
  function setAlwaysOn(on) {
    alwaysOn    = on;
    manualStop  = !on;
    try { localStorage.setItem('auriga-always-on', on ? '1' : '0'); } catch (_) {}

    /* Visual: pulse the listen ring differently when always-on */
    var ring = document.getElementById('av-listen-ring');
    if (ring) ring.classList.toggle('av-always-on', on);

    /* Update AurigaSwipe's always-on button if present */
    var alwaysBtn = document.getElementById('ch-always-btn');
    if (alwaysBtn) {
      alwaysBtn.classList.toggle('active', on);
      alwaysBtn.setAttribute('aria-pressed', on ? 'true' : 'false');
    }

    if (on) {
      showToast('Always-on mic enabled');
      speak('Always on. I\'m continuously listening.', 'always-on-on');
      if (!listening) setTimeout(startListening, 600);
    } else {
      showToast('Mic will close after each command');
      speak('Normal mode.', 'always-on-off');
    }
  }

  /* ── Handle final transcript ─────────────────────────────────────── */
  function handleFinalTranscript(text) {
    setTranscript(text, false);
    setTimeout(function () { setTranscript(''); }, 2000);

    if (!text) return;
    var lower = text.toLowerCase();

    /* ── Always-on commands (highest priority) ───────────────── */
    if (/\b(always\s+on|always\s+listen(?:ing)?|keep\s+listen(?:ing)?|continuous\s+mode|stay\s+on)\b/i.test(lower)) {
      setAlwaysOn(true);
      return;
    }
    if (/\b(stop\s+always|normal\s+mode|quiet\s+mode|stop\s+continuous|turn\s+off\s+always|one\s+shot\s+mode)\b/i.test(lower)) {
      setAlwaysOn(false);
      return;
    }

    /* Rename flow intercept */
    if (renamePending) {
      renamePending = false;
      var newName = text.trim().split(/\s+/)[0]; // first word only
      newName = newName.charAt(0).toUpperCase() + newName.slice(1).toLowerCase();
      assistantName = newName;
      store(STORAGE_NAME, newName);
      speak('Got it. My name is now ' + newName + '. Say ' + newName + ' Auriga any time to activate me.', 'renamed');
      showToast('Name updated: ' + newName);
      return;
    }

    /* Check for wake phrase: "<name> auriga" or just "<name>" alone */
    var nameLower = assistantName.toLowerCase();
    var isWake = lower.startsWith(nameLower + ' auriga') ||
                 lower.startsWith(nameLower + ' or go') ||
                 lower === nameLower;

    /* Strip wake phrase prefix before routing */
    var command = lower;
    if (lower.startsWith(nameLower)) {
      command = lower.slice(nameLower.length).replace(/^\s*auriga\s*/i, '').trim();
    }

    if (!command) {
      /* Just the name or wake phrase — announce and stay listening */
      speak('Yes? What would you like?', 'wake-ack');
      setTimeout(startListening, 1200);
      return;
    }

    var cmd = matchCommand(command);
    if (cmd) {
      if (cmd.reply) {
        speak(cmd.reply, 'cmd-reply');
        showToast(cmd.reply);
      }
      setTimeout(function () { cmd.action(); }, cmd.reply ? 800 : 0);
    } else {
      /* ── Jarvis fallback: route unrecognised commands to the AI engine ── */
      if (window.Jarvis && window.Jarvis.ask) {
        window.Jarvis.ask(text).then(function (reply) {
          if (reply) showToast(reply.slice(0, 60) + (reply.length > 60 ? '…' : ''));
        });
      } else {
        speak('I didn\'t understand that. Say "help" to hear what I can do.', 'unrecognised');
        showToast('Not understood — say "help"');
      }
    }
  }

  /* ── Enable / disable voice ──────────────────────────────────────── */
  function setEnabled(on) {
    voiceEnabled = !!on;
    store(STORAGE_ENABLED, on ? '1' : '0');
    var fab = document.getElementById('av-mic-fab');
    if (fab) fab.classList.toggle('av-hidden', !on);
    updateNavChip();
    if (!on) stopListening();
  }

  function updateNavChip() {
    /* Sync the chip in the a11y status strip */
    var strip = document.getElementById('a11yStatus');
    if (!strip) return;
    var existing = strip.querySelector('[data-av-chip]');
    if (voiceEnabled && assistantName) {
      if (!existing) {
        var chip = document.createElement('span');
        chip.className = 'a11y-chip';
        chip.setAttribute('data-tone', 'voice');
        chip.setAttribute('data-av-chip', '1');
        chip.setAttribute('title', 'Voice navigation is active');
        chip.textContent = '🎤';
        strip.appendChild(chip);
        strip.classList.add('has-chips');
      }
    } else {
      if (existing) existing.remove();
    }
  }

  /* ── Serpentine swipe gesture ────────────────────────────────────── */
  /*
     Gesture definition (from user spec):
       - Start:   near the centre of the absolute left border
       - Phase 1: curve DOWN
       - Phase 2: curve UP
       - End:     at the horizontal centre of the screen
     We track touch/pointer events and score the path against the
     expected shape using three checkpoints.
  */
  var SWIPE_PHASES = {
    IDLE:    0,
    STARTED: 1,
    DOWN:    2,
    UP:      3
  };

  function initSwipeGesture() {
    swipeState = {
      phase:   SWIPE_PHASES.IDLE,
      startX:  0,
      startY:  0,
      maxDown: 0,
      maxRight: 0
    };

    function onTouchStart(e) {
      var t = e.touches[0];
      var leftBorder = window.innerWidth * 0.08; /* within 8% of left edge */
      var midY       = window.innerHeight * 0.5;
      var yTolerance = window.innerHeight * 0.28;

      if (t.clientX > leftBorder) return;
      if (Math.abs(t.clientY - midY) > yTolerance) return;

      swipeState.phase   = SWIPE_PHASES.STARTED;
      swipeState.startX  = t.clientX;
      swipeState.startY  = t.clientY;
      swipeState.maxDown = t.clientY;
      swipeState.maxRight = t.clientX;
    }

    function onTouchMove(e) {
      if (swipeState.phase === SWIPE_PHASES.IDLE) return;
      var t = e.touches[0];
      var dy = t.clientY - swipeState.startY;

      /* Track how far down the user went */
      if (t.clientY > swipeState.maxDown) swipeState.maxDown = t.clientY;
      if (t.clientX > swipeState.maxRight) swipeState.maxRight = t.clientX;

      /* Phase transitions */
      if (swipeState.phase === SWIPE_PHASES.STARTED) {
        if (dy > window.innerHeight * 0.12) swipeState.phase = SWIPE_PHASES.DOWN;
      }
      if (swipeState.phase === SWIPE_PHASES.DOWN) {
        var wentDown = swipeState.maxDown - swipeState.startY;
        if (wentDown > window.innerHeight * 0.1 && t.clientY < swipeState.maxDown - window.innerHeight * 0.08) {
          swipeState.phase = SWIPE_PHASES.UP;
        }
      }
    }

    function onTouchEnd(e) {
      if (swipeState.phase === SWIPE_PHASES.IDLE) return;
      var phase = swipeState.phase;
      swipeState.phase = SWIPE_PHASES.IDLE;

      /* Require: went DOWN, then UP, and ended near horizontal centre */
      if (phase < SWIPE_PHASES.UP) return;

      var changedTouch = e.changedTouches[0];
      var endX = changedTouch.clientX;
      var endY = changedTouch.clientY;
      var centerX = window.innerWidth * 0.5;
      var xTolerance = window.innerWidth * 0.3;

      if (Math.abs(endX - centerX) > xTolerance) return;

      /* Success — this is the serpentine gesture */
      onSerperntineSwipe();
    }

    document.addEventListener('touchstart', onTouchStart, { passive: true });
    document.addEventListener('touchmove',  onTouchMove,  { passive: true });
    document.addEventListener('touchend',   onTouchEnd,   { passive: true });
  }

  function onSerperntineSwipe() {
    if (!voiceEnabled || !assistantName) return;
    requestMicAndListen();
  }

  /* ── Mic FAB interactions ─────────────────────────────────────────── */
  function initMicFab() {
    var fab = document.getElementById('av-mic-fab');
    if (!fab) return;

    var pressTimer  = null;
    var LONG_PRESS  = 400; /* ms */

    /* Mobile: long-press activates, tap-while-listening stops */
    fab.addEventListener('touchstart', function (e) {
      e.preventDefault();
      if (listening) { stopListening(); return; }
      pressTimer = setTimeout(function () {
        pressTimer = null;
        requestMicAndListen();
      }, LONG_PRESS);
    }, { passive: false });

    fab.addEventListener('touchend', function () {
      if (pressTimer) { clearTimeout(pressTimer); pressTimer = null; }
    });

    /* Desktop: single click toggles */
    fab.addEventListener('click', function () {
      if (listening) { stopListening(); return; }
      requestMicAndListen();
    });
  }

  /* ── Keyboard shortcut: Ctrl+Space ──────────────────────────────── */
  function initKeyboardShortcut() {
    document.addEventListener('keydown', function (e) {
      if (!voiceEnabled) return;
      if (e.ctrlKey && e.code === 'Space') {
        e.preventDefault();
        if (listening) { stopListening(); return; }
        requestMicAndListen();
      }
    });
  }

  /* ── Request mic then start ──────────────────────────────────────── */
  function requestMicAndListen() {
    if (!SpeechRecognition) {
      speak('Voice recognition is not supported in this browser.', 'no-sr');
      return;
    }
    if (listening) return;
    /* Web Speech API implicitly requests permission; start directly */
    startListening();
  }

  /* ── Long-press anywhere on screen (mobile, centre zone) ─────────── */
  function initLongPressScreen() {
    var screenTimer = null;
    var LONG_MS = 600;

    document.addEventListener('touchstart', function (e) {
      if (e.target && e.target.closest && e.target.closest('#av-mic-fab')) return;
      if (e.touches.length !== 1) return;
      var t = e.touches[0];
      var cx = window.innerWidth  / 2;
      var cy = window.innerHeight / 2;
      var radius = Math.min(window.innerWidth, window.innerHeight) * 0.18;
      if (Math.abs(t.clientX - cx) > radius) return;
      if (Math.abs(t.clientY - cy) > radius) return;

      screenTimer = setTimeout(function () {
        screenTimer = null;
        if (!voiceEnabled || listening) return;
        requestMicAndListen();
      }, LONG_MS);
    }, { passive: true });

    document.addEventListener('touchend', function () {
      if (screenTimer) { clearTimeout(screenTimer); screenTimer = null; }
    }, { passive: true });

    document.addEventListener('touchmove', function () {
      if (screenTimer) { clearTimeout(screenTimer); screenTimer = null; }
    }, { passive: true });
  }

  /* ── Build UI elements ───────────────────────────────────────────── */
  function buildOnboardingModal() {
    var div = document.createElement('div');
    div.id = 'av-onboard';
    div.setAttribute('role', 'dialog');
    div.setAttribute('aria-modal', 'true');
    div.setAttribute('aria-labelledby', 'av-onboard-title');
    div.innerHTML =
      '<div class="av-onboard-box">' +
        '<div class="av-onboard-logo" aria-hidden="true">🤖</div>' +
        '<div class="av-onboard-eyebrow">Auriga Voice Setup</div>' +
        '<div class="av-onboard-title" id="av-onboard-title">Name Your Assistant</div>' +
        '<p class="av-onboard-desc">' +
          'Before voice navigation can begin, give your assistant a name.<br>' +
          'You\'ll activate it by saying <strong>&ldquo;[Name] Auriga&rdquo;</strong> — for example, <strong>&ldquo;Nova Auriga&rdquo;</strong>.' +
        '</p>' +
        '<div class="av-name-row">' +
          '<input id="av-name-input" class="av-name-input" type="text" ' +
            'placeholder="e.g. NOVA" maxlength="20" autocomplete="off" ' +
            'aria-label="Assistant name" />' +
          '<button class="av-onboard-confirm" id="av-name-confirm" type="button">CONFIRM</button>' +
        '</div>' +
        '<div class="av-name-hint" id="av-name-hint">One word, any name you like. You can rename it later by saying "change name".</div>' +
        '<div class="av-onboard-steps">' +
          '<div class="av-onboard-steps-title">How to use voice navigation</div>' +
          '<ul>' +
            '<li><span class="av-step-num">1</span>Say your assistant\'s name + "Auriga" to wake it — e.g. <em>"Nova Auriga"</em></li>' +
            '<li><span class="av-step-num">2</span>Or: long-press the centre of the screen, or press Ctrl+Space on desktop</li>' +
            '<li><span class="av-step-num">3</span>Or: swipe a serpentine curve from the left edge of the screen to the centre</li>' +
            '<li><span class="av-step-num">4</span>Give a command like <em>"open locator"</em>, <em>"go to navi"</em>, <em>"read this page"</em></li>' +
            '<li><span class="av-step-num">5</span>Say <em>"help"</em> at any time to hear what the assistant can do</li>' +
          '</ul>' +
        '</div>' +
      '</div>';
    return div;
  }

  function buildSwipeHint() {
    var div = document.createElement('div');
    div.id = 'av-swipe-hint';
    div.setAttribute('aria-hidden', 'true');
    div.innerHTML =
      '<div class="av-swipe-zone"></div>' +
      '<div class="av-swipe-label">Swipe to speak</div>';
    return div;
  }

  function buildListenRing() {
    var div = document.createElement('div');
    div.id = 'av-listen-ring';
    div.setAttribute('aria-hidden', 'true');
    div.innerHTML = '<div class="av-ring"></div><div class="av-ring"></div><div class="av-ring"></div>';
    div.classList.add('av-hidden');
    return div;
  }

  function buildTranscript() {
    var div = document.createElement('div');
    div.id = 'av-transcript';
    div.setAttribute('role', 'status');
    div.setAttribute('aria-live', 'polite');
    div.classList.add('av-hidden');
    return div;
  }

  function buildMicFab() {
    var btn = document.createElement('button');
    btn.id = 'av-mic-fab';
    btn.type = 'button';
    btn.setAttribute('data-label', 'Hold to speak');
    btn.setAttribute('aria-label', 'Hold or press Ctrl+Space to activate voice');
    btn.textContent = '🎤';
    return btn;
  }

  function buildToast() {
    var div = document.createElement('div');
    div.id = 'av-toast';
    div.setAttribute('role', 'alert');
    div.setAttribute('aria-live', 'assertive');
    div.classList.add('av-hidden');
    return div;
  }

  /* ── Onboarding modal wiring ─────────────────────────────────────── */
  function wireOnboarding() {
    var modal   = document.getElementById('av-onboard');
    var input   = document.getElementById('av-name-input');
    var confirm = document.getElementById('av-name-confirm');
    var hint    = document.getElementById('av-name-hint');
    if (!modal || !input || !confirm) return;

    function submit() {
      var val = input.value.trim();
      if (!val || val.length < 2) {
        hint.textContent = 'Please enter at least 2 characters.';
        hint.classList.add('av-error');
        input.focus();
        return;
      }
      var name = val.charAt(0).toUpperCase() + val.slice(1).toLowerCase();
      assistantName = name;
      store(STORAGE_NAME, name);
      modal.classList.add('av-hidden');
      setEnabled(true);
      speak(
        'Hello! I\'m ' + name + ', your Auriga navigation assistant. ' +
        'To activate me, say ' + name + ' Auriga, ' +
        'long-press the centre of your screen, or press Control Space on a keyboard. ' +
        'I\'ll now describe this page. ' + PAGE_DESCRIPTIONS[currentPage() || 'index.html'],
        'onboard-welcome'
      );
      store(STORAGE_TOUR, '1');
    }

    confirm.addEventListener('click', submit);
    input.addEventListener('keydown', function (e) {
      if (e.key === 'Enter') submit();
    });

    /* Auto-focus the input */
    setTimeout(function () { if (input) input.focus(); }, 400);

    /* Speak the onboarding prompt for screen reader users */
    setTimeout(function () {
      speak(
        'Welcome to Auriga. To enable voice navigation, please type or speak the name you want to give your assistant, then press Confirm.',
        'onboard-prompt'
      );
    }, 600);
  }

  /* ── Add voice tile to a11y console ─────────────────────────────── */
  function addVoiceTileToConsole() {
    var grid = document.querySelector('.a11y-grid');
    if (!grid || grid.querySelector('[data-a11y="voice"]')) return;

    var tile = document.createElement('button');
    tile.type = 'button';
    tile.className = 'a11y-tile' + (voiceEnabled ? ' on' : '');
    tile.setAttribute('data-a11y', 'voice');
    tile.setAttribute('data-tone', 'amber');
    tile.setAttribute('aria-pressed', voiceEnabled ? 'true' : 'false');
    tile.innerHTML =
      '<span class="a11y-led" aria-hidden="true"></span>' +
      '<span class="a11y-tile-label">' +
        '<span>VOICE</span><span>NAV</span>' +
      '</span>';

    tile.addEventListener('click', function () {
      var on = !voiceEnabled;
      if (on && !assistantName) {
        /* Re-show onboarding if no name is set */
        var modal = document.getElementById('av-onboard');
        if (modal) modal.classList.remove('av-hidden');
        return;
      }
      setEnabled(on);
      tile.setAttribute('aria-pressed', on ? 'true' : 'false');
      tile.classList.toggle('on', on);
      speak(on ? 'Voice navigation activated. Say ' + assistantName + ' Auriga to begin.' : 'Voice navigation off.', 'voice-toggle');
    });

    grid.appendChild(tile);
  }

  /* ── Init ────────────────────────────────────────────────────────── */
  function init() {
    /* Load persisted state */
    assistantName = recall(STORAGE_NAME) || '';
    voiceEnabled  = recall(STORAGE_ENABLED) === '1';

    /* Inject UI */
    if (!document.getElementById('av-onboard')) {
      document.body.appendChild(buildOnboardingModal());
    }
    if (!document.getElementById('av-swipe-hint')) {
      document.body.appendChild(buildSwipeHint());
    }
    if (!document.getElementById('av-listen-ring')) {
      document.body.appendChild(buildListenRing());
    }
    if (!document.getElementById('av-transcript')) {
      document.body.appendChild(buildTranscript());
    }
    if (!document.getElementById('av-mic-fab')) {
      document.body.appendChild(buildMicFab());
    }
    if (!document.getElementById('av-toast')) {
      document.body.appendChild(buildToast());
    }

    /* Show or hide onboarding */
    var modal = document.getElementById('av-onboard');
    if (!assistantName) {
      /* First run — show modal */
      if (modal) modal.classList.remove('av-hidden');
      wireOnboarding();
    } else {
      /* Returning user */
      if (modal) modal.classList.add('av-hidden');
      setEnabled(voiceEnabled);

      /* Announce the page if voice is on */
      if (voiceEnabled) {
        setTimeout(function () { announcePage(false); }, 1000);
      }
    }

    /* Show swipe hint briefly on first few visits */
    var swipeHintDone = recall('auriga-swipe-hint-shown');
    if (!swipeHintDone) {
      var hint = document.getElementById('av-swipe-hint');
      if (hint) {
        hint.classList.remove('av-hidden');
        setTimeout(function () {
          hint.classList.add('av-hidden');
          store('auriga-swipe-hint-shown', '1');
        }, 6000);
      }
    }

    /* FAB visibility */
    var fab = document.getElementById('av-mic-fab');
    if (fab) fab.classList.toggle('av-hidden', !voiceEnabled || !assistantName);

    /* Wire interactions */
    initSwipeGesture();
    initMicFab();
    initKeyboardShortcut();
    initLongPressScreen();

    /* Add voice tile to accessibility console (after nav-drawer.js runs) */
    setTimeout(addVoiceTileToConsole, 200);

    /* Restore always-on preference from previous session */
    try {
      if (localStorage.getItem('auriga-always-on') === '1') {
        alwaysOn = true;
        /* Delay startup so TTS introduction plays first */
        setTimeout(function () {
          if (alwaysOn && !listening && voiceEnabled) startListening();
        }, 3000);
      }
    } catch (_) {}

    /* Expose public API */
    window.AurigaVoice = {
      speak:         speak,
      listen:        requestMicAndListen,
      stopListening: stopListening,
      setEnabled:    setEnabled,
      setAlwaysOn:   setAlwaysOn,
      get alwaysOn() { return alwaysOn; },
      getName:       function () { return assistantName; },
      announcePage:  announcePage
    };
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
