/* ═══════════════════════════════════════════════════════════════════════
   JARVIS — Auriga AI Assistant Engine
   Fused from the OpenJarvis project philosophy (open-jarvis/OpenJarvis)
   and the Auriga accessibility platform.

   Key capabilities (browser-native, zero external API required):
     · Jarvis personality adapted for visually impaired users
     · Conversational AI via a curated VI knowledge base
     · Extended command routing for all Auriga tools
     · Morning briefing (time, date, battery, daily tips)
     · Scene awareness hook (reads locator detections)
     · Conversational memory (last 10 exchanges, localStorage)
     · Skill registry (modular expansions, same architecture as OpenJarvis)

   Exposed globals:
     window.Jarvis
       .ask(text)             — process a text query, returns Promise<string>
       .speak(text)           — TTS output
       .listen()              — activate mic
       .stopListening()       — deactivate mic
       .briefing()            — morning-digest-style spoken briefing
       .describeScene()       — describe current locator scene if available
       .registerSkill(skill)  — add a custom skill {name, match, handle}
       .setSceneProvider(fn)  — inject live detection data from locator
═══════════════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  /* ── Config ──────────────────────────────────────────────────────── */
  var STORAGE_NAME    = 'auriga-voice-name';
  var STORAGE_ENABLED = 'auriga-voice-enabled';
  var STORAGE_MEMORY  = 'jarvis-memory';
  var STORAGE_TIPS    = 'jarvis-tip-index';
  var MAX_MEMORY      = 10;

  var SpeechRecognition =
    window.SpeechRecognition || window.webkitSpeechRecognition || null;

  /* ── State ───────────────────────────────────────────────────────── */
  var assistantName   = '';
  var listening       = false;
  var recognition     = null;
  var sceneProvider   = null;    // injected by locator.html
  var renamePending   = false;
  var briefingActive  = false;
  var memory          = [];      // [{role, text, ts}]
  var skills          = [];      // custom skill extensions
  var _speaker        = null;    // singleton AurigaAnnounce.Speaker

  /* ── Jarvis Persona ──────────────────────────────────────────────── */
  /*
   * Adapted from OpenJarvis jarvis.md persona:
   * Loyal, efficient, warm, proactive. Calm under pressure.
   * Delivers information clearly — no markdown, no emojis, always
   * optimised for spoken output.
   * For a VI audience: always descriptive, never assumes the user
   * can see the screen, leads with the most important information.
   */

  /* ── VI Knowledge Base ───────────────────────────────────────────── */
  /* Pattern → response pairs. Responses are written to be spoken. */
  var KNOWLEDGE = [
    {
      match: [/what (is|are) (auriga|this app)/i, /tell me about (auriga|yourself)/i],
      answer: 'Auriga is a spatial intelligence platform built for blind and low-vision users. It gives you real-time awareness of your surroundings through three main tools: the Object Locator, which finds and names objects in your environment; the DrakoVoice Reader, which reads printed text aloud from your camera; and voice navigation, so you can control everything hands-free.'
    },
    {
      match: [/how (do i|can i|to) use (the )?locator/i, /object locator help/i],
      answer: 'The Object Locator uses your camera and artificial intelligence to detect objects around you in real time. It tells you what each object is, how far away it is, and whether it is to your left, right, or straight ahead. Say "open locator" to start it. Say "set targets" to choose which objects to watch for, like chairs, cups, or people.'
    },
    {
      match: [/how (do i|can i|to) (use |read |scan )?text/i, /ocr help/i, /reader help/i],
      answer: 'The DrakoVoice Reader uses your camera to read printed text. Point your camera at any text, then tap the capture button or say "auto mode" to have it read continuously. You can tap any word in the transcript to start reading from that point, and use "next paragraph" or "previous paragraph" to move through longer documents.'
    },
    {
      match: [/what is (a )?bearing/i, /what does bearing mean/i],
      answer: 'Bearing tells you the horizontal direction to an object. Zero degrees means directly ahead. Positive degrees — up to 90 — means to your right. Negative degrees means to your left. So if the locator says "cup, 60 right", the cup is 60 degrees to your right of centre.'
    },
    {
      match: [/battery|charge|power level/i],
      answer: function () {
        if (navigator.getBattery) {
          return navigator.getBattery().then(function (b) {
            var pct = Math.round(b.level * 100);
            var status = b.charging ? 'and charging' : (pct < 20 ? ', and it is running low' : '');
            return 'Battery is at ' + pct + ' percent' + status + '.';
          });
        }
        return 'Battery information is not available in this browser.';
      }
    },
    {
      match: [/what time is it|current time|the time/i],
      answer: function () {
        var now = new Date();
        var h = now.getHours();
        var m = now.getMinutes();
        var ampm = h >= 12 ? 'PM' : 'AM';
        h = h % 12 || 12;
        return 'The time is ' + h + (m > 0 ? ' ' + (m < 10 ? 'oh ' + m : m) : '') + ' ' + ampm + '.';
      }
    },
    {
      match: [/what (day|date) is it|today's date|the date/i],
      answer: function () {
        var now = new Date();
        var days = ['Sunday','Monday','Tuesday','Wednesday','Thursday','Friday','Saturday'];
        var months = ['January','February','March','April','May','June','July','August','September','October','November','December'];
        return 'Today is ' + days[now.getDay()] + ', ' + months[now.getMonth()] + ' ' + now.getDate() + ', ' + now.getFullYear() + '.';
      }
    },
    {
      match: [/weather|temperature|forecast/i],
      answer: 'I do not have access to weather data in this session. For weather, you could ask your phone\'s built-in assistant, or visit a weather app. Is there something else I can help with?'
    },
    {
      match: [/vi |visually impaired|blind|low.vision/i, /accessibility tip/i, /tip (of the day|for today)/i],
      answer: function () {
        return getDailyTip();
      }
    },
    {
      match: [/how (do i|to) (calibrate|improve) (accuracy|distance)/i],
      answer: 'Calibration profiles help the locator estimate distances accurately for your specific phone camera. Go to the Calibration Library and select your device model. The 10-point calibration walk takes about two minutes and covers different distances and lighting conditions.'
    },
    {
      match: [/can (you|i|it) work offline|offline (mode|use)/i],
      answer: 'Yes. Auriga is a Progressive Web App, which means once you have loaded the site, the core tools work without an internet connection. The Object Locator, DrakoVoice Reader, and voice navigation all run entirely on your device.'
    },
    {
      match: [/how (do i|to) send feedback|report (a )?bug/i],
      answer: 'Say "open feedback" or tap the Feedback link in the menu. You can report bugs, suggest ideas, or ask for help. Bug and support reports need your email address so the team can reply to you.'
    },
    {
      match: [/what (can you|are your) (do|commands|skills)/i, /list (commands|skills)/i],
      answer: function () {
        return buildSkillList();
      }
    },
    {
      match: [/hello|hi there|good morning|good afternoon|good evening/i],
      answer: function () {
        var name = assistantName || 'there';
        var hour = new Date().getHours();
        var greeting = hour < 12 ? 'Good morning' : hour < 17 ? 'Good afternoon' : 'Good evening';
        return greeting + '. I am ' + name + ', your Auriga assistant. How can I help you today?';
      }
    },
    {
      match: [/thank(s| you)/i],
      answer: 'Of course. Is there anything else you need?'
    },
    {
      match: [/who (made|built|created) (this|auriga)/i, /about drakosanctis/i],
      answer: 'Auriga was built by DrakoSanctis, focused on making spatial intelligence accessible to blind and low-vision users. The assistant you are speaking with was inspired by the OpenJarvis open-source project.'
    }
  ];

  /* ── Daily accessibility tips ─────────────────────────────────────── */
  var TIPS = [
    'Tip: In the Object Locator, say "set targets" to narrow detection to just the objects that matter to you, like chairs or doors.',
    'Tip: You can activate voice navigation anywhere by long-pressing the centre of the screen, or pressing Control Space on a keyboard.',
    'Tip: In the Reader, Auto mode will automatically capture text when it detects the camera is steady and text is in frame.',
    'Tip: All your accessibility preferences — high contrast, large text, and voice navigation — are saved automatically across all pages.',
    'Tip: You can say "describe this page" at any time to hear a summary of what is on the current screen.',
    'Tip: Say "open menu" to access all tools and settings by voice, without needing to tap the screen.',
    'Tip: The Calibration Library has profiles for hundreds of phone models. Selecting yours improves distance accuracy significantly.',
    'Tip: In the Object Locator, the primary target — the object closest to the centre of your view — is announced first and highlighted.',
    'Tip: You can use Focus mode in the locator by saying "focus on" followed by an object name, like "focus on chair".',
    'Tip: The DrakoVoice Reader supports Auto mode, which reads new text automatically as you move the camera, useful for scanning menus or signs.'
  ];

  function getDailyTip() {
    var idx = 0;
    try { idx = parseInt(localStorage.getItem(STORAGE_TIPS) || '0', 10); } catch (_) {}
    if (isNaN(idx) || idx >= TIPS.length) idx = 0;
    var tip = TIPS[idx];
    try { localStorage.setItem(STORAGE_TIPS, String((idx + 1) % TIPS.length)); } catch (_) {}
    return tip;
  }

  /* ── Skill list builder ───────────────────────────────────────────── */
  function buildSkillList() {
    var core = [
      'Open Locator — launch the Object Locator.',
      'Open Reader — launch the DrakoVoice Reader.',
      'Describe scene — hear what the locator is currently detecting.',
      'Briefing — get a spoken summary of the time, date, and a daily tip.',
      'Open menu — open the navigation drawer.',
      'Go home — return to the main page.',
      'Set targets — choose which objects to track.',
      'Calibrate — open the Calibration Library.',
      'Send feedback — report a bug or suggestion.',
      'Help — hear available commands.',
      'What time is it — hear the current time.',
      'What day is it — hear today\'s date.',
      'Battery level — hear your device battery status.',
      'Accessibility tip — hear a daily tip for using Auriga.'
    ];
    var skillNames = skills.map(function (s) { return s.name + ' — ' + (s.description || 'custom skill.'); });
    var all = core.concat(skillNames);
    return 'Here are my capabilities. ' + all.join(' ');
  }

  /* ── Memory ───────────────────────────────────────────────────────── */
  function loadMemory() {
    try {
      var stored = localStorage.getItem(STORAGE_MEMORY);
      memory = stored ? JSON.parse(stored) : [];
    } catch (_) { memory = []; }
  }

  function saveMemory() {
    try { localStorage.setItem(STORAGE_MEMORY, JSON.stringify(memory)); } catch (_) {}
  }

  function addToMemory(role, text) {
    memory.push({ role: role, text: text, ts: Date.now() });
    if (memory.length > MAX_MEMORY) memory = memory.slice(-MAX_MEMORY);
    saveMemory();
    notifyMemoryListeners(role, text);
  }

  var memoryListeners = [];
  function notifyMemoryListeners(role, text) {
    memoryListeners.forEach(function (fn) {
      try { fn(role, text); } catch (_) {}
    });
  }

  /* ── Speaker singleton (defer to AurigaAnnounce if available) ────── */
  /*
   * BUG FIX: the old code created a NEW Speaker instance on every speak()
   * call, so the deduplication / gap logic inside Speaker was useless —
   * each instance had no history. Speeches could collide and cancel each
   * other. We now keep one module-level singleton and lazy-init it.
   */
  function getSpeaker() {
    if (_speaker) return _speaker;
    if (window.AurigaAnnounce) {
      _speaker = new window.AurigaAnnounce.Speaker({
        rate: 0.95, pitch: 1.0, repeatMs: 600, minGapMs: 100
      });
      _speaker.enabled = true;
    }
    return _speaker;
  }

  function speak(text, key) {
    addToMemory('assistant', text);
    var sp = getSpeaker();
    if (sp) {
      sp.say(text, key || ('j-' + Date.now()));
      return;
    }
    // Fallback to raw SpeechSynthesis when AurigaAnnounce isn't loaded yet
    if (!('speechSynthesis' in window)) return;
    window.speechSynthesis.cancel();
    var u = new SpeechSynthesisUtterance(text);
    u.rate = 0.95; u.pitch = 1.0;
    window.speechSynthesis.speak(u);
  }

  /* ── Core command table ───────────────────────────────────────────── */
  /*
   * Design: every command accepts broad natural-language paraphrases so
   * users don't need to memorise exact phrases. Three groups of patterns:
   *   1. Verb + noun  ("open locator", "start reader")
   *   2. Intent       ("I want to find objects", "help me read")
   *   3. Question     ("what's around me", "what does this say")
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
        /\bwhat('s|\s+is)\s+(a|a?round|nearby|in\s+front)\b/i,
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
        /\b(ten|10)[- ]point\s+calibration\b/i,
        /\bdistance\s+calibration\b/i
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
        /\bsubmit\s+(a\s+)?suggestion\b/i,
        /\bcontact\s+support\b/i,
        /\bfeedback\s+form\b/i,
        /\bfile\s+a\s+report\b/i
      ],
      reply: 'Opening Send Feedback.',
      action: function () { navigateTo('feedback.html'); }
    },

    /* ── Targets Manager ── */
    {
      match: [
        /\b(open|start|go\s+to|manage|set|edit)\s+(the\s+)?targets?\b/i,
        /\btrack\s+objects?\b/i,
        /\bwhat\s+(am\s+I|are\s+you)\s+tracking\b/i,
        /\b(add|remove)\s+(a?\s+)?target\b/i,
        /\bwatch\s+list\b/i,
        /\btracking\s+list\b/i,
        /\bconfigure\s+targets?\b/i
      ],
      reply: 'Opening Targets Manager.',
      action: function () { navigateTo('locator-targets.html'); }
    },

    /* ── About ── */
    {
      match: [
        /\b(open\s+)?about\b/i,
        /\bwho\s+(made|built|created)\s+(this|auriga)\b/i,
        /\btell\s+me\s+about\s+auriga\b/i,
        /\bapp\s+info\b/i,
        /\bcredits\b/i
      ],
      reply: 'Opening About.',
      action: function () { navigateTo('about.html'); }
    },

    /* ── Jarvis / Assistant page ── */
    {
      match: [
        /\b(open\s+)?(the\s+)?assistant\b/i,
        /\bopen\s+jarvis\b/i,
        /\bjarvis\s+mode\b/i,
        /\bai\s+assistant\b/i,
        /\bchat\s+(with\s+)?jarvis\b/i
      ],
      reply: 'Opening the Jarvis Assistant.',
      action: function () { navigateTo('assistant.html'); }
    },

    /* ── Section navigation (home page) ── */
    {
      match: [/\b(go\s+to\s+|show\s+|view\s+)?ecosystem\b/i],
      reply: 'Showing the Ecosystem section.',
      action: function () { showSection('ecosystem'); }
    },
    {
      match: [/\b(go\s+to\s+|show\s+)?((strategic\s+)?position|competitive)\b/i],
      reply: 'Showing Strategic Position.',
      action: function () { showSection('position'); }
    },
    {
      match: [/\b(go\s+to\s+|show\s+)?navi\b/i, /\bauriga\s+navi\b/i, /\bcharioteer\b/i],
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
        /\b(close|hide|dismiss|collapse|shut)\s+(the\s+)?(menu|drawer|sidebar|navigation)\b/i
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
        /\breturn\b/i,
        /\bnavigate\s+back\b/i,
        /\bpress\s+back\b/i,
        /\btake\s+me\s+back\b/i,
        /\b^back$\b/i
      ],
      reply: 'Going back.',
      action: function () { window.history.back(); }
    },

    /* ── Jarvis-specific skills ── */
    {
      match: [
        /\b(morning\s+)?briefing\b/i,
        /\bdaily\s+(briefing|digest|summary|update)\b/i,
        /\bgood\s+morning\b/i,
        /\bwhat'?s?\s+(new|happening)\b/i,
        /\bmorning\s+update\b/i
      ],
      reply: null,
      action: function () { runBriefing(); }
    },
    {
      match: [
        /\bdescribe\s+(the\s+)?scene\b/i,
        /\bwhat('s|\s+is)\s+(a|a?round|nearby|in\s+front)\b/i,
        /\bwhat\s+do\s+you\s+see\b/i,
        /\bwhat\s+can\s+you\s+see\b/i,
        /\bwhat('s|\s+is)\s+(detected|visible|there)\b/i,
        /\btell\s+me\s+what'?s?\s+around\b/i,
        /\bdescribe\s+(my\s+)?surroundings?\b/i
      ],
      reply: null,
      action: function () { describeScene(); }
    },
    {
      match: [
        /\bchange\s+(my\s+)?name\b/i,
        /\brename\b/i,
        /\bnew\s+name\b/i,
        /\bcall\s+you\s+(something|by)\b/i,
        /\bgive\s+you\s+a\s+name\b/i,
        /\bi\s+want\s+to\s+rename\b/i
      ],
      reply: 'Sure. Say your new assistant name.',
      action: function () { startRenameFlow(); }
    },
    {
      match: [
        /\bhelp\b/i,
        /\bwhat\s+can\s+you\s+do\b/i,
        /\blist\s+commands?\b/i,
        /\bavailable\s+commands?\b/i,
        /\bwhat\s+(are\s+your|do\s+you\s+know)\b/i,
        /\bshow\s+commands?\b/i,
        /\bwhat\s+can\s+I\s+say\b/i
      ],
      reply: null,
      action: function () { speak(buildSkillList(), 'help'); }
    },
    {
      match: [
        /\b(read|describe|summarise|summarize)\s+(this\s+)?page\b/i,
        /\bwhat('s|\s+is)\s+(on\s+)?(this\s+)?page\b/i,
        /\bwhere\s+am\s+I\b/i,
        /\bwhat\s+(screen|page)\s+am\s+I\s+on\b/i,
        /\btell\s+me\s+about\s+this\s+page\b/i,
        /\bcurrent\s+page\b/i
      ],
      reply: null,
      action: function () { announcePage(true); }
    },
    {
      match: [
        /\bstop\s+(listening|voice|speaking|talking)\b/i,
        /\bquiet\b/i,
        /\b(be\s+)?quiet\b/i,
        /\bshh+\b/i,
        /\bmute\s+(me|voice|mic|yourself)\b/i,
        /\bsilence\b/i,
        /\bpause\s+voice\b/i,
        /\bthat'?s?\s+enough\b/i
      ],
      reply: 'Voice paused. Long-press the screen or press Control Space to wake me again.',
      action: function () {
        stopListening();
        if (window.AurigaVoice && window.AurigaVoice.setEnabled) window.AurigaVoice.setEnabled(false);
      }
    },
    {
      match: [
        /\baccessibility\s+tip\b/i,
        /\btip\s+(of\s+the\s+day|for\s+today)\b/i,
        /\bdaily\s+tip\b/i,
        /\bany\s+tips?\b/i,
        /\bgive\s+me\s+a\s+tip\b/i
      ],
      reply: null,
      action: function () { speak(getDailyTip(), 'tip'); }
    },
    {
      match: [/\bfocus\s+on\s+(.+)/i],
      reply: null,
      action: function (match) {
        var target = match && match[1] ? match[1].trim() : '';
        if (target) {
          speak('Focusing on ' + target + '.', 'focus');
          navigateTo('locator.html?focus=' + encodeURIComponent(target));
        }
      }
    }
  ];

  /* ── Skill registry (OpenJarvis-style extensibility) ─────────────── */
  function registerSkill(skill) {
    /* skill: { name, description, match: RegExp|RegExp[], handle: fn(text) → string|Promise<string> } */
    if (!skill || !skill.name || !skill.handle) return;
    skills.push(skill);
  }

  /* ── Command matching ─────────────────────────────────────────────── */
  function matchCommand(text) {
    var t = text.trim().toLowerCase();

    /* Check custom skills first */
    for (var s = 0; s < skills.length; s++) {
      var skill = skills[s];
      var patterns = Array.isArray(skill.match) ? skill.match : [skill.match];
      for (var ps = 0; ps < patterns.length; ps++) {
        if (patterns[ps] && patterns[ps].test && patterns[ps].test(t)) {
          return { custom: true, skill: skill, match: t.match(patterns[ps]) };
        }
      }
    }

    /* Core commands */
    for (var i = 0; i < COMMANDS.length; i++) {
      var cmd = COMMANDS[i];
      var pats = Array.isArray(cmd.match) ? cmd.match : [cmd.match];
      for (var j = 0; j < pats.length; j++) {
        var p = pats[j];
        var isMatch = (typeof p === 'string')
          ? t.indexOf(p.toLowerCase()) !== -1
          : (p.exec ? p.exec(t) : p.test(t));
        if (isMatch) {
          var captureMatch = (p.exec) ? p.exec(t) : null;
          return { core: true, cmd: cmd, match: captureMatch };
        }
      }
    }
    return null;
  }

  /* ── Knowledge base lookup ────────────────────────────────────────── */
  function queryKnowledge(text) {
    var t = text.trim();
    for (var i = 0; i < KNOWLEDGE.length; i++) {
      var kb = KNOWLEDGE[i];
      var patterns = Array.isArray(kb.match) ? kb.match : [kb.match];
      for (var j = 0; j < patterns.length; j++) {
        if (patterns[j].test(t)) {
          var ans = kb.answer;
          if (typeof ans === 'function') {
            var result = ans();
            if (result && result.then) return result; // Promise
            return Promise.resolve(String(result));
          }
          return Promise.resolve(String(ans));
        }
      }
    }
    return null;
  }

  /* ── Main ask() entry point ───────────────────────────────────────── */
  function ask(text) {
    if (!text || !text.trim()) return Promise.resolve('');
    addToMemory('user', text);

    var lower = text.toLowerCase().trim();

    /* Strip wake phrase if present */
    var nameLower = (assistantName || '').toLowerCase();
    if (nameLower && lower.startsWith(nameLower)) {
      lower = lower.slice(nameLower.length).replace(/^\s*auriga\s*/i, '').trim();
    }

    if (!lower) {
      var ack = 'Yes? I am listening.';
      speak(ack, 'wake-ack');
      return Promise.resolve(ack);
    }

    /* 1. Try command routing */
    var matched = matchCommand(lower);
    if (matched) {
      if (matched.custom) {
        return Promise.resolve(matched.skill.handle(lower, matched.match)).then(function (reply) {
          if (reply) speak(reply, 'skill-reply');
          return reply || '';
        });
      }
      if (matched.core) {
        var reply = matched.cmd.reply;
        if (reply) speak(reply, 'cmd-reply');
        setTimeout(function () {
          matched.cmd.action(matched.match);
        }, reply ? 700 : 0);
        return Promise.resolve(reply || '');
      }
    }

    /* 2. Try knowledge base */
    var kbResult = queryKnowledge(lower);
    if (kbResult) {
      return kbResult.then(function (ans) {
        speak(ans, 'kb-' + lower.slice(0, 20));
        return ans;
      });
    }

    /* 3. Try conversational AI (free endpoint, no key required) */
    return queryAI(lower).then(function (aiAnswer) {
      if (aiAnswer) {
        speak(aiAnswer, 'ai-' + lower.slice(0, 20));
        return aiAnswer;
      }
      /* 4. Final offline fallback */
      var fallback = buildOfflineFallback(lower);
      speak(fallback, 'fallback');
      return fallback;
    });
  }

  /* ── Conversational AI query ──────────────────────────────────────
   * Tries a free no-key chat completion endpoint. If offline or the
   * request fails for any reason, returns null so the caller falls
   * through to the offline fallback — the user always gets a response.
   *
   * The system prompt is tuned for a VI accessibility assistant:
   *   - Always answer in plain spoken English (no markdown, no lists)
   *   - Lead with the most important fact
   *   - Keep answers under ~40 words so TTS stays comfortable
   */
  var AI_ENDPOINT = 'https://api.freeai.chat/v1/chat/completions';
  var AI_MODEL    = 'gpt-4o-mini';
  var AI_SYSTEM   = 'You are Auriga, a voice assistant for blind and low-vision users. ' +
    'Answer questions concisely in plain spoken English — no bullet points, no markdown. ' +
    'Keep every answer under 40 words. Lead with the most important fact. ' +
    'If asked what something is, give a clear, tactile, spatial description.';
  var AI_TIMEOUT_MS = 7000;

  function queryAI(text) {
    if (!navigator.onLine) return Promise.resolve(null);
    return new Promise(function (resolve) {
      var done = false;
      var timer = setTimeout(function () {
        if (!done) { done = true; resolve(null); }
      }, AI_TIMEOUT_MS);

      var body = JSON.stringify({
        model: AI_MODEL,
        messages: [
          { role: 'system', content: AI_SYSTEM },
          { role: 'user',   content: text }
        ],
        max_tokens: 80,
        temperature: 0.5
      });

      fetch(AI_ENDPOINT, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: body
      })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (data) {
        if (done) return;
        done = true;
        clearTimeout(timer);
        var answer = data &&
          data.choices &&
          data.choices[0] &&
          data.choices[0].message &&
          data.choices[0].message.content;
        resolve(answer ? answer.trim() : null);
      })
      .catch(function () {
        if (!done) { done = true; clearTimeout(timer); resolve(null); }
      });
    });
  }

  /* ── Offline fallback answer builder ──────────────────────────────
   * When both the KB and the AI are unavailable, give a response that
   * is genuinely useful rather than a dead end.
   */
  function buildOfflineFallback(text) {
    /* Detect question intent and give a directional answer */
    if (/\bwhat\s+is\b|\bwhat\s+are\b|\bdefine\b|\bexplain\b/i.test(text)) {
      return 'I do not have that answer stored offline right now. ' +
        'I will try to look it up when you are connected. ' +
        'You can also ask me about Auriga features — say "help" to hear what I know.';
    }
    if (/\bhow\s+(do|can|to)\b|\bhow\s+does\b/i.test(text)) {
      return 'That is a good question. I am offline right now so I cannot fetch an answer. ' +
        'For Auriga help, say "help" or "open help".';
    }
    return 'I heard you, but I am not sure how to answer that right now. ' +
      'Say "help" to hear what I can do, or connect to the internet for broader questions.';
  }

  /* ── Morning Briefing (OpenJarvis morning-digest concept) ────────── */
  function runBriefing() {
    briefingActive = true;
    var name = assistantName || 'there';
    var now  = new Date();
    var hour = now.getHours();
    var days = ['Sunday','Monday','Tuesday','Wednesday','Thursday','Friday','Saturday'];
    var months = ['January','February','March','April','May','June','July','August','September','October','November','December'];
    var greeting = hour < 12 ? 'Good morning' : hour < 17 ? 'Good afternoon' : 'Good evening';
    var h12 = now.getHours() % 12 || 12;
    var m = now.getMinutes();
    var ampm = now.getHours() >= 12 ? 'PM' : 'AM';
    var timeStr = h12 + (m > 0 ? ' ' + (m < 10 ? 'oh ' + m : m) : '') + ' ' + ampm;
    var dateStr = days[now.getDay()] + ', ' + months[now.getMonth()] + ' ' + now.getDate();

    var lines = [
      greeting + ', ' + name + '.',
      'The time is ' + timeStr + '. Today is ' + dateStr + '.',
      getDailyTip(),
      'Auriga is ready. Say "open locator" to start object detection, "open reader" to read text, or "help" to hear all available commands.'
    ];

    var delay = 0;
    lines.forEach(function (line, i) {
      setTimeout(function () {
        speak(line, 'briefing-' + i);
        if (i === lines.length - 1) briefingActive = false;
      }, delay);
      delay += line.length * 55 + 600;
    });
  }

  /* ── Scene description (hooks into locator's detection data) ──────── */
  function describeScene() {
    if (sceneProvider) {
      var detections = sceneProvider();
      if (!detections || !detections.length) {
        speak('The scene is currently empty. No objects have been detected yet. Make sure the locator is running and the camera is active.', 'scene-empty');
        return;
      }
      var sorted = detections.slice().sort(function (a, b) {
        return Math.abs(a.bearing || 0) - Math.abs(b.bearing || 0);
      });
      var parts = sorted.slice(0, 5).map(function (d) {
        var bearing = d.bearing != null
          ? (Math.abs(d.bearing) < 5 ? 'directly ahead' : (d.bearing > 0 ? d.bearing + ' degrees right' : Math.abs(d.bearing) + ' degrees left'))
          : '';
        var dist = d.distance ? d.distance : '';
        return d.label + (dist ? ', ' + dist : '') + (bearing ? ', ' + bearing : '');
      });
      var summary = 'I can see ' + sorted.length + ' object' + (sorted.length > 1 ? 's' : '') + '. ' + parts.join('. ') + '.';
      speak(summary, 'scene');
    } else {
      speak('Scene description is available when the Object Locator is running. Say "open locator" to start it.', 'scene-no-provider');
    }
  }

  /* ── Page announcer ───────────────────────────────────────────────── */
  var PAGE_DESCRIPTIONS = {
    'index.html':             'Home. DrakoSanctis Auriga Ecosystem overview. Sections: Home, Ecosystem, Position, Navi, Sentinel, Aero, Industrial.',
    'locator.html':           'Object Locator. Real-time camera-based object detection. Announces what is around you with distance and direction.',
    'locator-targets.html':   'Targets Manager. Choose which objects the locator watches for, and see when each was last spotted.',
    'reader.html':            'DrakoVoice Reader. Point camera at text to have it read aloud. Supports auto capture and paragraph navigation.',
    'calibration-library.html': 'Calibration Library. Device-specific distance profiles for improved accuracy.',
    'feedback.html':          'Send Feedback. Report bugs, suggest ideas, or ask for help.',
    'about.html':             'About Auriga. Mission and contact information.',
    'assistant.html':         'Jarvis Assistant. Your AI-powered voice interface for the entire Auriga platform.'
  };

  function currentPage() {
    return window.location.pathname.split('/').pop() || 'index.html';
  }

  function announcePage(withGuide) {
    var page = currentPage();
    var desc = PAGE_DESCRIPTIONS[page] || ('You are on ' + (document.title || 'this page') + '.');
    speak(desc, 'page-desc');
  }

  /* ── Rename flow ──────────────────────────────────────────────────── */
  function startRenameFlow() {
    renamePending = true;
  }

  /* ── Navigation helpers ───────────────────────────────────────────── */
  function navigateTo(href) {
    window.location.href = href;
  }

  function showSection(id) {
    if (typeof window.showSection === 'function') {
      window.showSection(id);
    } else {
      var target = document.getElementById(id);
      if (target) target.scrollIntoView({ behavior: 'smooth' });
    }
  }

  /* ── Speech Recognition ───────────────────────────────────────────── */
  function listen() {
    if (!SpeechRecognition) {
      speak('Speech recognition is not available in this browser. Please use Chrome or Edge.', 'no-sr');
      return;
    }
    if (listening) return;
    listening = true;
    updateListeningUI(true);

    recognition = new SpeechRecognition();
    recognition.lang = 'en-US';
    recognition.interimResults = true;
    recognition.continuous = false;
    recognition.maxAlternatives = 3;

    recognition.onresult = function (e) {
      var interim = '', final = '';
      for (var i = e.resultIndex; i < e.results.length; i++) {
        if (e.results[i].isFinal) final += e.results[i][0].transcript;
        else interim += e.results[i][0].transcript;
      }
      if (interim) updateTranscript(interim, true);
      if (final) {
        updateTranscript(final, false);
        handleFinalTranscript(final.trim());
      }
    };

    recognition.onerror = function (e) {
      if (e.error === 'not-allowed' || e.error === 'service-not-allowed') {
        speak('Microphone access was denied. Please allow microphone access in your browser settings.', 'mic-denied');
      }
      stopListening();
    };

    recognition.onend = function () { stopListening(); };

    try { recognition.start(); } catch (_) { stopListening(); }
  }

  function stopListening() {
    listening = false;
    updateListeningUI(false);
    if (recognition) {
      try { recognition.stop(); } catch (_) {}
      recognition = null;
    }
  }

  function handleFinalTranscript(text) {
    if (!text) return;
    setTimeout(function () { updateTranscript('', false); }, 2200);

    /* Rename flow intercept */
    if (renamePending) {
      renamePending = false;
      var newName = text.trim().split(/\s+/)[0];
      newName = newName.charAt(0).toUpperCase() + newName.slice(1).toLowerCase();
      assistantName = newName;
      try { localStorage.setItem(STORAGE_NAME, newName); } catch (_) {}
      speak('Got it. I am now ' + newName + '. Say ' + newName + ' Auriga any time to activate me.', 'renamed');
      return;
    }

    ask(text);
  }

  /* ── UI hooks (works with both assistant.html and auriga-voice.js UI) */
  function updateListeningUI(on) {
    /* Notify AurigaVoice if present */
    if (window.AurigaVoice && window.AurigaVoice._setListeningUI) {
      window.AurigaVoice._setListeningUI(on);
    }
    /* Update Jarvis-specific UI elements */
    var ring = document.getElementById('jv-ring');
    var btn  = document.getElementById('jv-listen-btn');
    if (ring) ring.classList.toggle('listening', on);
    if (btn)  btn.setAttribute('aria-label', on ? 'Listening — tap to stop' : 'Tap or hold to speak to Jarvis');
    if (!on) updateTranscript('', false);
    /* Fire custom event for any page to hook into */
    try {
      document.dispatchEvent(new CustomEvent('jarvis:listening', { detail: { on: on } }));
    } catch (_) {}
  }

  function updateTranscript(text, interim) {
    /* Notify AurigaVoice transcript bubble if present */
    var el = document.getElementById('av-transcript') || document.getElementById('jv-transcript');
    if (!el) return;
    if (!text) { el.classList.add('av-hidden'); return; }
    el.textContent = text;
    el.classList.remove('av-hidden');
    el.classList.toggle('av-interim', !!interim);
    /* Also fire event */
    try {
      document.dispatchEvent(new CustomEvent('jarvis:transcript', { detail: { text: text, interim: interim } }));
    } catch (_) {}
  }

  /* ── Scene provider registration ──────────────────────────────────── */
  function setSceneProvider(fn) {
    sceneProvider = fn;
  }

  /* ── Init ─────────────────────────────────────────────────────────── */
  function init() {
    try { assistantName = localStorage.getItem(STORAGE_NAME) || ''; } catch (_) {}
    loadMemory();
  }

  init();

  /* ── Public API ───────────────────────────────────────────────────── */
  window.Jarvis = {
    ask:              ask,
    speak:            speak,
    listen:           listen,
    stopListening:    stopListening,
    isListening:      function () { return listening; },
    briefing:         runBriefing,
    describeScene:    describeScene,
    setSceneProvider: setSceneProvider,
    registerSkill:    registerSkill,
    getMemory:        function () { return memory.slice(); },
    onMemory:         function (fn) { memoryListeners.push(fn); },
    getName:          function () { return assistantName; },
    getKnowledge:     function () { return KNOWLEDGE; },
    announcePage:     announcePage,
    getDailyTip:      getDailyTip
  };

})();
