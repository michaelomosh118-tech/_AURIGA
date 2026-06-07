/* ═══════════════════════════════════════════════════════════════════════
   AurigaSkills — Comprehensive Skill Pack for Jarvis / AurigaNavi
   ═══════════════════════════════════════════════════════════════════════

   Registers the following skill categories via Jarvis.registerSkill():

   ┌─────────────────────────────────────────────────────────────────┐
   │  TIMERS & ALARMS   — set timer, cancel timer, multiple timers   │
   │  REMINDERS         — set reminder at a time, list, clear        │
   │  CALCULATOR        — spoken arithmetic & unit conversions       │
   │  WEATHER           — offline GPS + Open-Meteo (no API key)      │
   │  COMPASS & GPS     — bearing, coordinates, speed                │
   │  HOME AUTOMATION   — hooks for lights, locks, thermostat        │
   │  PERSONALIZATION   — news digest, daily plan, memory queries    │
   │  HEALTH & MOTION   — step count, distance walked                │
   │  KNOWLEDGE BOOST   — currency rates (cached), definitions       │
   │  SYSTEM CONTROLS   — brightness, volume, font size              │
   └─────────────────────────────────────────────────────────────────┘

   Design rules (VI-first):
   · All responses are plain spoken English — no markdown, no bullets.
   · Lead with the most important fact.
   · Keep voice responses under ~50 words; detail pages can be longer.
   · Every skill is fully offline unless noted with [online-optional].
   · Skills that require hardware gracefully degrade with a spoken message.
   · Skills auto-register after Jarvis is ready.
═══════════════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  /* ── Speak helper ──────────────────────────────────────────────── */
  function speak(text, key) {
    if (window.Jarvis && window.Jarvis.speak) {
      window.Jarvis.speak(text, key || ('skill-' + Date.now()));
      return;
    }
    if (!('speechSynthesis' in window)) return;
    var u = new SpeechSynthesisUtterance(text);
    u.rate = 0.95;
    window.speechSynthesis.speak(u);
  }

  /* ── Storage helpers ───────────────────────────────────────────── */
  function store(key, val) {
    try { localStorage.setItem('auriga-skills-' + key, JSON.stringify(val)); } catch (_) {}
  }
  function recall(key, def) {
    try {
      var v = localStorage.getItem('auriga-skills-' + key);
      return v !== null ? JSON.parse(v) : def;
    } catch (_) { return def; }
  }

  /* ══════════════════════════════════════════════════════════════════
     1. TIMERS & ALARMS
  ══════════════════════════════════════════════════════════════════ */
  var activeTimers = {};   /* id → { label, endMs, intervalId } */
  var timerCounter = 0;

  function parseTimeDuration(text) {
    /* Returns seconds or null. Handles "5 minutes", "1 hour 30", "90 seconds", "half an hour" */
    var total = 0;
    var found = false;
    var t = text.toLowerCase();

    if (/half\s+an?\s+hour/.test(t)) { return 1800; }
    if (/quarter\s+(?:of\s+an?\s+)?hour/.test(t)) { return 900; }

    var hours   = t.match(/(\d+(?:\.\d+)?)\s*(?:hour|hr)s?/);
    var mins    = t.match(/(\d+(?:\.\d+)?)\s*(?:minute|min)s?/);
    var secs    = t.match(/(\d+(?:\.\d+)?)\s*(?:second|sec)s?/);
    var numOnly = t.match(/^(\d+)$/);

    if (hours)   { total += parseFloat(hours[1]) * 3600; found = true; }
    if (mins)    { total += parseFloat(mins[1]) * 60; found = true; }
    if (secs)    { total += parseFloat(secs[1]); found = true; }
    if (numOnly && !found) { total = parseInt(numOnly[1], 10) * 60; found = true; } // assume minutes

    return found ? Math.round(total) : null;
  }

  function parseTimerLabel(text) {
    /* Extract optional label: "set a timer for 5 minutes for pasta" → "pasta" */
    var m = text.match(/for\s+(?:\d[\w\s]*\s+)?for\s+(.+)$/i);
    return m ? m[1].trim() : null;
  }

  function formatDuration(secs) {
    if (secs < 60) return secs + ' second' + (secs !== 1 ? 's' : '');
    var h = Math.floor(secs / 3600);
    var m = Math.floor((secs % 3600) / 60);
    var s = secs % 60;
    var parts = [];
    if (h) parts.push(h + ' hour' + (h !== 1 ? 's' : ''));
    if (m) parts.push(m + ' minute' + (m !== 1 ? 's' : ''));
    if (s) parts.push(s + ' second' + (s !== 1 ? 's' : ''));
    return parts.join(' and ');
  }

  function startTimer(seconds, label) {
    timerCounter++;
    var id    = 'T' + timerCounter;
    var endMs = Date.now() + seconds * 1000;
    var name  = label || ('Timer ' + timerCounter);

    /* Announce halfway and final warnings */
    var warned = { half: false, ten: false, thirty: false };

    var iv = setInterval(function () {
      var remaining = Math.round((endMs - Date.now()) / 1000);

      if (remaining <= 0) {
        clearInterval(iv);
        delete activeTimers[id];
        speak(name + ' is done! Your ' + formatDuration(seconds) + ' timer has finished.', 'timer-done-' + id);
        /* Haptic if available */
        if (navigator.vibrate) navigator.vibrate([400, 200, 400, 200, 800]);
        return;
      }

      var half = Math.round(seconds / 2);
      if (!warned.half && remaining <= half && remaining > half - 5) {
        warned.half = true;
        speak('Halfway there. ' + formatDuration(remaining) + ' remaining on ' + name + '.', 'timer-half-' + id);
      }
      if (!warned.thirty && remaining <= 30 && seconds > 60) {
        warned.thirty = true;
        speak('30 seconds remaining on ' + name + '.', 'timer-30-' + id);
      }
      if (!warned.ten && remaining <= 10) {
        warned.ten = true;
        speak(remaining + ' seconds.', 'timer-10-' + id);
      }
    }, 500);

    activeTimers[id] = { label: name, endMs: endMs, intervalId: iv, seconds: seconds };
    return id;
  }

  var timerSkill = {
    name: 'Set timer',
    description: 'set a countdown timer',
    match: [
      /\bset\s+(?:a\s+)?timer\s+(?:for\s+)?(.+)/i,
      /\bstart\s+(?:a\s+)?timer\s+(?:for\s+)?(.+)/i,
      /\btimer\s+(?:for\s+)?(\d[\w\s]*)/i,
      /\bcountdown\s+(?:for\s+)?(.+)/i
    ],
    handle: function (text) {
      var secs = parseTimeDuration(text);
      if (!secs || secs <= 0) return 'I could not understand the duration. Try saying "set a timer for 5 minutes".';
      if (secs > 86400) return 'That duration is over 24 hours. Please set a shorter timer.';
      var label = parseTimerLabel(text);
      var id = startTimer(secs, label);
      return (label ? label + ' timer' : 'Timer') + ' set for ' + formatDuration(secs) + '. I will let you know when it is done.';
    }
  };

  var cancelTimerSkill = {
    name: 'Cancel timer',
    description: 'cancel a running timer',
    match: [
      /\bcancel\s+(?:the\s+)?(?:all\s+)?timer/i,
      /\bstop\s+(?:the\s+)?timer/i,
      /\bclear\s+(?:the\s+)?timer/i,
      /\bdelete\s+(?:the\s+)?timer/i
    ],
    handle: function () {
      var count = Object.keys(activeTimers).length;
      if (!count) return 'You do not have any active timers.';
      Object.keys(activeTimers).forEach(function (id) {
        clearInterval(activeTimers[id].intervalId);
        delete activeTimers[id];
      });
      return count === 1 ? 'Timer cancelled.' : 'All ' + count + ' timers cancelled.';
    }
  };

  var listTimersSkill = {
    name: 'List timers',
    description: 'check running timers',
    match: [
      /\b(list|check|show)\s+(?:my\s+)?timers?\b/i,
      /\bhow\s+(?:much\s+)?(?:time\s+)?(?:is\s+)?(?:left|remaining)\s+on\s+(?:my\s+)?timer/i,
      /\bany\s+(?:active\s+)?timers?\b/i
    ],
    handle: function () {
      var ids = Object.keys(activeTimers);
      if (!ids.length) return 'You have no active timers.';
      var parts = ids.map(function (id) {
        var t = activeTimers[id];
        var remaining = Math.max(0, Math.round((t.endMs - Date.now()) / 1000));
        return t.label + ': ' + formatDuration(remaining) + ' remaining';
      });
      return 'You have ' + ids.length + ' timer' + (ids.length > 1 ? 's' : '') + '. ' + parts.join('. ') + '.';
    }
  };

  /* ══════════════════════════════════════════════════════════════════
     2. ALARMS (absolute time)
  ══════════════════════════════════════════════════════════════════ */
  var activeAlarms = {};
  var alarmCounter = 0;

  function parseAbsoluteTime(text) {
    /* Parse "at 3 PM", "at 7:30 AM", "at quarter past 6", "at half 8", "at midnight" */
    var t = text.toLowerCase();
    if (/midnight/.test(t)) return setTimeToday(0, 0);
    if (/noon/.test(t))     return setTimeToday(12, 0);
    if (/quarter\s+past\s+(\d+)/.test(t)) {
      var m = t.match(/quarter\s+past\s+(\d+)/); return setTimeToday(parseInt(m[1], 10), 15);
    }
    if (/half\s+(?:past\s+)?(\d+)/.test(t)) {
      var m2 = t.match(/half\s+(?:past\s+)?(\d+)/); return setTimeToday(parseInt(m2[1], 10), 30);
    }
    var m3 = t.match(/(\d{1,2})(?::(\d{2}))?\s*(am|pm)?/);
    if (!m3) return null;
    var h = parseInt(m3[1], 10);
    var min = m3[2] ? parseInt(m3[2], 10) : 0;
    var ampm = m3[3];
    if (ampm === 'pm' && h < 12) h += 12;
    if (ampm === 'am' && h === 12) h = 0;
    return setTimeToday(h, min);
  }

  function setTimeToday(h, m) {
    var d = new Date();
    d.setHours(h, m, 0, 0);
    if (d.getTime() < Date.now()) d.setDate(d.getDate() + 1); /* tomorrow */
    return d;
  }

  var setAlarmSkill = {
    name: 'Set alarm',
    description: 'set an alarm for a specific time',
    match: [
      /\bset\s+(?:an?\s+)?alarm\s+(?:for\s+)?(?:at\s+)?(.+)/i,
      /\bwake\s+me\s+(?:up\s+)?(?:at\s+)?(.+)/i,
      /\balarm\s+at\s+(.+)/i,
      /\bremind\s+me\s+to\s+wake\s+up\s+at\s+(.+)/i
    ],
    handle: function (text) {
      var d = parseAbsoluteTime(text);
      if (!d) return 'I could not understand that time. Try saying "set alarm at 7 AM" or "wake me at 6:30".';
      alarmCounter++;
      var id = 'A' + alarmCounter;
      var msUntil = d.getTime() - Date.now();
      var h = d.getHours();
      var m = d.getMinutes();
      var ampm = h >= 12 ? 'PM' : 'AM';
      var h12 = h % 12 || 12;
      var timeStr = h12 + (m > 0 ? ':' + (m < 10 ? '0' + m : m) : '') + ' ' + ampm;

      var tid = setTimeout(function () {
        delete activeAlarms[id];
        speak('Alarm! It is ' + timeStr + '. Wake up!', 'alarm-ring-' + id);
        if (navigator.vibrate) navigator.vibrate([800, 300, 800, 300, 800]);
      }, msUntil);

      activeAlarms[id] = { timeStr: timeStr, timeoutId: tid };
      var hoursUntil = Math.floor(msUntil / 3600000);
      var minsUntil  = Math.round((msUntil % 3600000) / 60000);
      var until = hoursUntil > 0
        ? hoursUntil + ' hour' + (hoursUntil > 1 ? 's' : '') + ' and ' + minsUntil + ' minutes'
        : minsUntil + ' minutes';
      return 'Alarm set for ' + timeStr + '. That is in ' + until + '.';
    }
  };

  var cancelAlarmSkill = {
    name: 'Cancel alarm',
    description: 'cancel a set alarm',
    match: [
      /\bcancel\s+(?:the\s+)?(?:all\s+)?alarm/i,
      /\bclear\s+(?:the\s+)?alarm/i,
      /\bdelete\s+(?:the\s+)?alarm/i,
      /\bturn\s+off\s+(?:the\s+)?alarm/i
    ],
    handle: function () {
      var count = Object.keys(activeAlarms).length;
      if (!count) return 'You have no active alarms.';
      Object.keys(activeAlarms).forEach(function (id) {
        clearTimeout(activeAlarms[id].timeoutId);
        delete activeAlarms[id];
      });
      return 'All alarms cancelled.';
    }
  };

  /* ══════════════════════════════════════════════════════════════════
     3. REMINDERS
  ══════════════════════════════════════════════════════════════════ */
  var reminders = recall('reminders', []);

  function saveReminders() { store('reminders', reminders); }

  function scheduleReminder(r) {
    var msUntil = r.fireAt - Date.now();
    if (msUntil <= 0) return;
    setTimeout(function () {
      speak('Reminder: ' + r.text + '.', 'reminder-' + r.id);
      if (navigator.vibrate) navigator.vibrate([300, 100, 300]);
      reminders = reminders.filter(function (x) { return x.id !== r.id; });
      saveReminders();
    }, msUntil);
  }

  /* Re-schedule reminders that survived a page reload */
  reminders.forEach(function (r) { if (r.fireAt > Date.now()) scheduleReminder(r); });

  var setReminderSkill = {
    name: 'Set reminder',
    description: 'set a reminder for something',
    match: [
      /\bremind\s+me\s+(?:to\s+|about\s+)?(.+?)\s+in\s+(.+)/i,
      /\bset\s+(?:a\s+)?reminder\s+(?:to\s+|for\s+)?(.+?)\s+in\s+(.+)/i,
      /\breminder\s+(?:for\s+|to\s+)?(.+?)\s+in\s+(.+)/i
    ],
    handle: function (text, match) {
      if (!match || match.length < 3) return 'Try saying "remind me to take medicine in 2 hours".';
      var what = match[1].trim();
      var when = match[2].trim();
      var secs = parseTimeDuration(when);
      if (!secs) return 'I could not understand the time. Try "remind me to call John in 30 minutes".';
      var id = 'R' + Date.now();
      var r = { id: id, text: what, fireAt: Date.now() + secs * 1000 };
      reminders.push(r);
      saveReminders();
      scheduleReminder(r);
      return 'Reminder set. I will remind you to ' + what + ' in ' + formatDuration(secs) + '.';
    }
  };

  var listRemindersSkill = {
    name: 'List reminders',
    description: 'check pending reminders',
    match: [
      /\b(list|show|check)\s+(?:my\s+)?reminders?\b/i,
      /\bwhat\s+(?:are\s+my\s+|do\s+i\s+have\s+)?reminders?\b/i,
      /\bany\s+(?:pending\s+)?reminders?\b/i
    ],
    handle: function () {
      var pending = reminders.filter(function (r) { return r.fireAt > Date.now(); });
      if (!pending.length) return 'You have no pending reminders.';
      var parts = pending.map(function (r) {
        var mins = Math.round((r.fireAt - Date.now()) / 60000);
        return r.text + ' in ' + (mins < 60 ? mins + ' minutes' : Math.round(mins / 60) + ' hours');
      });
      return 'You have ' + pending.length + ' reminder' + (pending.length > 1 ? 's' : '') + '. ' + parts.join('. ') + '.';
    }
  };

  var clearRemindersSkill = {
    name: 'Clear reminders',
    description: 'clear all reminders',
    match: [
      /\b(clear|cancel|delete|remove)\s+(?:all\s+)?(?:my\s+)?reminders?\b/i
    ],
    handle: function () {
      var count = reminders.length;
      reminders = [];
      saveReminders();
      return count ? 'All ' + count + ' reminder' + (count > 1 ? 's' : '') + ' cleared.' : 'You had no reminders to clear.';
    }
  };

  /* ══════════════════════════════════════════════════════════════════
     4. CALCULATOR & UNIT CONVERTER
  ══════════════════════════════════════════════════════════════════ */
  var calcSkill = {
    name: 'Calculator',
    description: 'do maths and calculations',
    match: [
      /\bwhat\s+is\s+([\d\s\+\-\*\/\^\(\)\.]+)\b/i,
      /\bcalculat(?:e|or)\s+(.+)/i,
      /\bcompute\s+(.+)/i,
      /\b(\d+(?:\.\d+)?)\s*(plus|minus|times|divided by|multiplied by|mod)\s*(\d+(?:\.\d+)?)\b/i,
      /\bwhat\s+is\s+(\d+)\s*(percent|%)\s+of\s+(\d+(?:\.\d+)?)/i,
      /\bsquare\s+root\s+of\s+(\d+(?:\.\d+)?)/i,
      /\b(\d+)\s+squared\b/i,
      /\b(\d+)\s+cubed\b/i
    ],
    handle: function (text) {
      var t = text.toLowerCase().trim();

      /* Percent of */
      var pct = t.match(/(\d+(?:\.\d+)?)\s*(?:percent|%)\s+of\s+(\d+(?:\.\d+)?)/);
      if (pct) {
        var result = (parseFloat(pct[1]) / 100) * parseFloat(pct[2]);
        return pct[1] + ' percent of ' + pct[2] + ' is ' + formatNum(result) + '.';
      }

      /* Square root */
      var sqrt = t.match(/square\s+root\s+of\s+(\d+(?:\.\d+)?)/);
      if (sqrt) {
        var n = parseFloat(sqrt[1]);
        return 'The square root of ' + n + ' is ' + formatNum(Math.sqrt(n)) + '.';
      }

      /* Squared */
      var sq = t.match(/(\d+(?:\.\d+)?)\s+squared/);
      if (sq) { var n2 = parseFloat(sq[1]); return n2 + ' squared is ' + formatNum(n2 * n2) + '.'; }

      /* Cubed */
      var cu = t.match(/(\d+(?:\.\d+)?)\s+cubed/);
      if (cu) { var n3 = parseFloat(cu[1]); return n3 + ' cubed is ' + formatNum(n3 * n3 * n3) + '.'; }

      /* Word operators */
      var word = t.match(/(\d+(?:\.\d+)?)\s+(plus|minus|times|divided\s+by|multiplied\s+by|mod(?:ulo)?)\s+(\d+(?:\.\d+)?)/);
      if (word) {
        var a = parseFloat(word[1]), b = parseFloat(word[3]), op = word[2].replace(/\s+/g, ' ');
        var res;
        if (/plus/.test(op))              res = a + b;
        else if (/minus/.test(op))        res = a - b;
        else if (/times|multiplied/.test(op)) res = a * b;
        else if (/divided/.test(op))      res = b !== 0 ? a / b : null;
        else if (/mod/.test(op))          res = a % b;
        if (res === null) return 'You cannot divide by zero.';
        return a + ' ' + op + ' ' + b + ' equals ' + formatNum(res) + '.';
      }

      /* Try safe eval on pure math expression */
      var expr = t.replace(/what\s+is\s+/i, '').replace(/calculate\s+/i, '').replace(/compute\s+/i, '').trim();
      if (/^[\d\s\+\-\*\/\^\(\)\.%]+$/.test(expr)) {
        try {
          var safeExpr = expr.replace(/\^/g, '**');
          /* eslint-disable-next-line no-new-func */
          var val = Function('"use strict"; return (' + safeExpr + ')')();
          if (typeof val === 'number' && isFinite(val)) {
            return expr + ' equals ' + formatNum(val) + '.';
          }
        } catch (_) {}
      }

      return 'I could not compute that. Try saying "what is 24 times 7" or "square root of 144".';
    }
  };

  function formatNum(n) {
    if (!isFinite(n)) return 'infinity';
    if (Math.abs(n - Math.round(n)) < 0.0001) return String(Math.round(n));
    return parseFloat(n.toFixed(4)).toString();
  }

  var convertSkill = {
    name: 'Unit converter',
    description: 'convert units like miles to kilometres, Celsius to Fahrenheit',
    match: [
      /\bconvert\s+(.+?)\s+to\s+(.+)/i,
      /\bhow\s+(?:many|much)\s+(.+?)\s+(?:is|are|in)\s+(.+)/i,
      /\b(\d+(?:\.\d+)?)\s+(km|kilometres?|miles?|feet|foot|meters?|metres?|inches?|cm|centimetres?|kg|kilograms?|pounds?|lbs?|oz|ounces?|litres?|liters?|gallons?|°?[CF])\s+(?:to|in)\s+(\w+)/i
    ],
    handle: function (text) {
      var t = text.toLowerCase();
      /* Temperature */
      var cToF = t.match(/(\d+(?:\.\d+)?)\s*°?\s*c(?:elsius)?\s+(?:to|in)\s+(?:°?\s*f|fahrenheit)/);
      if (cToF) { var f = parseFloat(cToF[1]) * 9/5 + 32; return cToF[1] + ' Celsius is ' + formatNum(f) + ' Fahrenheit.'; }
      var fToC = t.match(/(\d+(?:\.\d+)?)\s*°?\s*f(?:ahrenheit)?\s+(?:to|in)\s+(?:°?\s*c|celsius)/);
      if (fToC) { var c = (parseFloat(fToC[1]) - 32) * 5/9; return fToC[1] + ' Fahrenheit is ' + formatNum(c) + ' Celsius.'; }
      /* Distance */
      var kmToMi = t.match(/(\d+(?:\.\d+)?)\s*(?:km|kilometres?|kilometers?)\s+(?:to|in)\s+(?:miles?)/);
      if (kmToMi) { var mi = parseFloat(kmToMi[1]) * 0.621371; return kmToMi[1] + ' kilometres is ' + formatNum(mi) + ' miles.'; }
      var miToKm = t.match(/(\d+(?:\.\d+)?)\s*miles?\s+(?:to|in)\s+(?:km|kilometres?|kilometers?)/);
      if (miToKm) { var km = parseFloat(miToKm[1]) * 1.60934; return miToKm[1] + ' miles is ' + formatNum(km) + ' kilometres.'; }
      var mToFt = t.match(/(\d+(?:\.\d+)?)\s*(?:meters?|metres?|m)\s+(?:to|in)\s+feet/);
      if (mToFt) { var ft = parseFloat(mToFt[1]) * 3.28084; return mToFt[1] + ' metres is ' + formatNum(ft) + ' feet.'; }
      var ftToM = t.match(/(\d+(?:\.\d+)?)\s*feet?\s+(?:to|in)\s+(?:meters?|metres?)/);
      if (ftToM) { var m2 = parseFloat(ftToM[1]) / 3.28084; return ftToM[1] + ' feet is ' + formatNum(m2) + ' metres.'; }
      var inToCm = t.match(/(\d+(?:\.\d+)?)\s*(?:inches?|in\.?)\s+(?:to|in)\s+(?:cm|centimetres?)/);
      if (inToCm) { var cm = parseFloat(inToCm[1]) * 2.54; return inToCm[1] + ' inches is ' + formatNum(cm) + ' centimetres.'; }
      var cmToIn = t.match(/(\d+(?:\.\d+)?)\s*(?:cm|centimetres?|centimeters?)\s+(?:to|in)\s+(?:inches?)/);
      if (cmToIn) { var inch = parseFloat(cmToIn[1]) / 2.54; return cmToIn[1] + ' centimetres is ' + formatNum(inch) + ' inches.'; }
      /* Weight */
      var kgToLb = t.match(/(\d+(?:\.\d+)?)\s*(?:kg|kilograms?)\s+(?:to|in)\s+(?:pounds?|lbs?)/);
      if (kgToLb) { var lb = parseFloat(kgToLb[1]) * 2.20462; return kgToLb[1] + ' kilograms is ' + formatNum(lb) + ' pounds.'; }
      var lbToKg = t.match(/(\d+(?:\.\d+)?)\s*(?:pounds?|lbs?)\s+(?:to|in)\s+(?:kg|kilograms?)/);
      if (lbToKg) { var kg = parseFloat(lbToKg[1]) / 2.20462; return lbToKg[1] + ' pounds is ' + formatNum(kg) + ' kilograms.'; }
      /* Volume */
      var lToGal = t.match(/(\d+(?:\.\d+)?)\s*(?:litres?|liters?|l)\s+(?:to|in)\s+(?:gallons?)/);
      if (lToGal) { var gal = parseFloat(lToGal[1]) * 0.264172; return lToGal[1] + ' litres is ' + formatNum(gal) + ' gallons.'; }
      var galToL = t.match(/(\d+(?:\.\d+)?)\s*(?:gallons?)\s+(?:to|in)\s+(?:litres?|liters?)/);
      if (galToL) { var l = parseFloat(galToL[1]) / 0.264172; return galToL[1] + ' gallons is ' + formatNum(l) + ' litres.'; }

      return 'I know how to convert temperature, distance, weight, and volume. Try "convert 5 miles to kilometres".';
    }
  };

  /* ══════════════════════════════════════════════════════════════════
     5. WEATHER [online-optional — uses Open-Meteo, free, no API key]
  ══════════════════════════════════════════════════════════════════ */
  var weatherCache = recall('weather-cache', null);
  var WEATHER_MAX_AGE_MS = 30 * 60 * 1000; /* 30 minutes */

  var WMO_CODES = {
    0:'clear sky', 1:'mainly clear', 2:'partly cloudy', 3:'overcast',
    45:'fog', 48:'icy fog', 51:'light drizzle', 53:'drizzle', 55:'heavy drizzle',
    61:'light rain', 63:'rain', 65:'heavy rain', 71:'light snow', 73:'snow', 75:'heavy snow',
    80:'rain showers', 81:'rain showers', 82:'violent rain showers',
    95:'thunderstorm', 96:'thunderstorm with hail', 99:'thunderstorm with heavy hail'
  };

  function getWeather(callback) {
    /* Return from cache if fresh */
    if (weatherCache && (Date.now() - weatherCache.ts) < WEATHER_MAX_AGE_MS) {
      callback(null, weatherCache.data);
      return;
    }
    if (!navigator.onLine) { callback('offline', null); return; }
    if (!navigator.geolocation) { callback('no-geo', null); return; }

    navigator.geolocation.getCurrentPosition(function (pos) {
      var lat = pos.coords.latitude.toFixed(4);
      var lon = pos.coords.longitude.toFixed(4);
      var url = 'https://api.open-meteo.com/v1/forecast?latitude=' + lat +
        '&longitude=' + lon +
        '&current=temperature_2m,weathercode,windspeed_10m,relativehumidity_2m,apparent_temperature' +
        '&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weathercode' +
        '&timezone=auto&forecast_days=3';

      fetch(url, { signal: AbortSignal.timeout ? AbortSignal.timeout(8000) : undefined })
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (data) {
          if (!data) { callback('api-error', null); return; }
          weatherCache = { ts: Date.now(), data: data };
          store('weather-cache', weatherCache);
          callback(null, data);
        })
        .catch(function () { callback('fetch-error', null); });
    }, function () { callback('geo-denied', null); }, { timeout: 8000 });
  }

  function describeWeather(data) {
    var c = data.current;
    var code = WMO_CODES[c.weathercode] || 'unknown conditions';
    var temp = Math.round(c.temperature_2m);
    var feels = Math.round(c.apparent_temperature);
    var humidity = c.relativehumidity_2m;
    var wind = Math.round(c.windspeed_10m);
    var unit = data.current_units && data.current_units.temperature_2m === '°F' ? 'Fahrenheit' : 'Celsius';

    var msg = 'Currently ' + code + '. Temperature is ' + temp + ' degrees ' + unit;
    if (Math.abs(feels - temp) >= 3) msg += ', but feels like ' + feels;
    msg += '. Humidity is ' + humidity + ' percent. Wind is ' + wind + ' kilometres per hour.';

    /* Tomorrow forecast */
    if (data.daily && data.daily.temperature_2m_max && data.daily.temperature_2m_max[1] != null) {
      var tMax = Math.round(data.daily.temperature_2m_max[1]);
      var tMin = Math.round(data.daily.temperature_2m_min[1]);
      var tCode = WMO_CODES[data.daily.weathercode[1]] || '';
      var rain = data.daily.precipitation_probability_max[1];
      msg += ' Tomorrow: ' + tCode + ', high ' + tMax + ', low ' + tMin;
      if (rain > 30) msg += ', ' + rain + ' percent chance of rain';
      msg += '.';
    }
    return msg;
  }

  var weatherSkill = {
    name: 'Weather',
    description: 'get current weather and forecast using your location',
    match: [
      /\b(what'?s?\s+the\s+)?weather\b/i,
      /\btemperature\s+(?:right\s+now|outside|today)\b/i,
      /\bforecast\b/i,
      /\bwill\s+it\s+rain\b/i,
      /\bwhat\s+(?:should\s+I\s+wear|to\s+wear)\b/i,
      /\bhow\s+(?:hot|cold|warm)\s+is\s+it\b/i,
      /\bis\s+it\s+raining\b/i,
      /\bweather\s+(?:today|now|outside|report)\b/i
    ],
    handle: function () {
      return new Promise(function (resolve) {
        speak('Checking the weather. One moment.', 'weather-loading');
        getWeather(function (err, data) {
          if (err === 'offline') { resolve('I am offline. I cannot check the weather right now. The last forecast I have is from ' + (weatherCache ? Math.round((Date.now() - weatherCache.ts) / 60000) + ' minutes ago' : 'unavailable') + '.'); return; }
          if (err === 'geo-denied') { resolve('Location access was denied. Please allow location access so I can get the weather for your area.'); return; }
          if (err) { resolve('I could not fetch the weather right now. Please try again in a moment.'); return; }
          resolve(describeWeather(data));
        });
      });
    }
  };

  /* ══════════════════════════════════════════════════════════════════
     6. COMPASS, GPS & LOCATION
  ══════════════════════════════════════════════════════════════════ */
  var compassSkill = {
    name: 'Compass',
    description: 'get your current GPS coordinates and heading',
    match: [
      /\bwhere\s+am\s+I\s+(?:located|right\s+now|standing|at)\b/i,
      /\bmy\s+(?:location|coordinates|position|gps)\b/i,
      /\bwhat\s+(?:are\s+)?my\s+coordinates\b/i,
      /\blatitude\s+and\s+longitude\b/i,
      /\bget\s+(?:my\s+)?gps\b/i
    ],
    handle: function () {
      return new Promise(function (resolve) {
        if (!navigator.geolocation) { resolve('GPS is not available on this device.'); return; }
        speak('Getting your location. One moment.', 'gps-loading');
        navigator.geolocation.getCurrentPosition(function (pos) {
          var lat = pos.coords.latitude.toFixed(5);
          var lon = pos.coords.longitude.toFixed(5);
          var acc = Math.round(pos.coords.accuracy);
          var speed = pos.coords.speed != null ? Math.round(pos.coords.speed * 3.6) + ' kilometres per hour' : null;
          var msg = 'You are at latitude ' + lat + ', longitude ' + lon + '. Accuracy is approximately ' + acc + ' metres.';
          if (speed !== null) msg += ' You are moving at ' + speed + '.';
          resolve(msg);
        }, function () {
          resolve('I could not get your location. Please allow location access.');
        }, { timeout: 8000, enableHighAccuracy: true });
      });
    }
  };

  /* ══════════════════════════════════════════════════════════════════
     7. HOME AUTOMATION HOOKS
     These register the voice commands and dispatch custom DOM events.
     Connect your own smart home integration by listening to
     'auriga:home' events on window:
       window.addEventListener('auriga:home', e => {
         console.log(e.detail); // { action, target, value }
       });
  ══════════════════════════════════════════════════════════════════ */
  function dispatchHome(action, target, value) {
    try {
      window.dispatchEvent(new CustomEvent('auriga:home', {
        detail: { action: action, target: target || 'all', value: value }
      }));
    } catch (_) {}
  }

  var lightsSkill = {
    name: 'Lights control',
    description: 'turn lights on or off, adjust brightness',
    match: [
      /\b(turn\s+)?(on|off|dim|brighten)\s+(?:the\s+)?(?:(\w+)\s+)?lights?\b/i,
      /\blights?\s+(on|off)\b/i,
      /\bset\s+(?:the\s+)?(?:(\w+)\s+)?lights?\s+to\s+(\d+)\s*%?\b/i,
      /\b(dim|brighten)\s+(?:the\s+)?lights?\b/i,
      /\blight\s+(on|off)\b/i
    ],
    handle: function (text) {
      var t = text.toLowerCase();
      var room = null;
      var roomMatch = t.match(/\b(bedroom|living\s*room|kitchen|bathroom|office|hallway|garage)\s+lights?\b/);
      if (roomMatch) room = roomMatch[1];
      var pctMatch = t.match(/to\s+(\d+)\s*%?/);
      var pct = pctMatch ? parseInt(pctMatch[1], 10) : null;

      if (/\bon\b/.test(t)) {
        dispatchHome('lights-on', room, pct || 100);
        return (room ? room.charAt(0).toUpperCase() + room.slice(1) + ' lights' : 'Lights') + ' turned on.';
      }
      if (/\boff\b/.test(t)) {
        dispatchHome('lights-off', room, 0);
        return (room ? room.charAt(0).toUpperCase() + room.slice(1) + ' lights' : 'Lights') + ' turned off.';
      }
      if (/\bdim\b/.test(t)) {
        dispatchHome('lights-dim', room, pct || 30);
        return 'Dimming ' + (room || 'the') + ' lights' + (pct ? ' to ' + pct + ' percent' : '') + '.';
      }
      if (/\bbrighten\b/.test(t)) {
        dispatchHome('lights-brighten', room, pct || 80);
        return 'Brightening ' + (room || 'the') + ' lights.';
      }
      if (pct !== null) {
        dispatchHome('lights-set', room, pct);
        return 'Setting ' + (room || 'the') + ' lights to ' + pct + ' percent.';
      }
      return 'You can say: turn on the lights, dim the bedroom lights, or set lights to 50 percent.';
    }
  };

  var thermostatSkill = {
    name: 'Thermostat',
    description: 'set the thermostat temperature',
    match: [
      /\bset\s+(?:the\s+)?(?:thermostat|temperature|heat|ac)\s+to\s+(\d+)/i,
      /\bthermostat\s+(\d+)\b/i,
      /\b(warmer|cooler|hotter|colder)\b/i,
      /\bset\s+(?:the\s+)?(?:heat|air\s+conditioning)\s+to\s+(\d+)/i
    ],
    handle: function (text) {
      var t = text.toLowerCase();
      var tempMatch = t.match(/(\d+)/);
      if (/warmer|hotter/.test(t)) {
        dispatchHome('thermostat-up', 'home', 2);
        return 'Increasing the temperature by 2 degrees.';
      }
      if (/cooler|colder/.test(t)) {
        dispatchHome('thermostat-down', 'home', 2);
        return 'Decreasing the temperature by 2 degrees.';
      }
      if (tempMatch) {
        var temp = parseInt(tempMatch[1], 10);
        dispatchHome('thermostat-set', 'home', temp);
        return 'Setting the thermostat to ' + temp + ' degrees.';
      }
      return 'Try saying "set thermostat to 22 degrees" or "make it warmer".';
    }
  };

  var lockSkill = {
    name: 'Door locks',
    description: 'lock or unlock doors',
    match: [
      /\b(lock|unlock)\s+(?:the\s+)?(?:front\s+)?door\b/i,
      /\b(lock|unlock)\s+(?:the\s+)?(?:back|rear|garage)\s+door\b/i,
      /\bis\s+(?:the\s+)?door\s+locked\b/i,
      /\bcheck\s+(?:the\s+)?(?:door\s+)?locks?\b/i
    ],
    handle: function (text) {
      var t = text.toLowerCase();
      var doorMatch = t.match(/\b(front|back|rear|garage)\s+door\b/);
      var door = doorMatch ? doorMatch[1] : 'front';
      if (/\bunlock\b/.test(t)) {
        dispatchHome('door-unlock', door, null);
        return 'Unlocking the ' + door + ' door.';
      }
      if (/\block\b/.test(t)) {
        dispatchHome('door-lock', door, null);
        return 'Locking the ' + door + ' door.';
      }
      dispatchHome('door-status', door, null);
      return 'Checking ' + door + ' door lock status.';
    }
  };

  /* ══════════════════════════════════════════════════════════════════
     8. PERSONALIZATION & MEMORY QUERIES
  ══════════════════════════════════════════════════════════════════ */
  var memoryQuerySkill = {
    name: 'Memory recall',
    description: 'recall things you have told the assistant',
    match: [
      /\bdo\s+you\s+(?:remember|know)\s+(?:my\s+)?(.+)\b/i,
      /\bwhat\s+(?:is|are)\s+my\s+(.+)\b/i,
      /\bwhat\s+did\s+I\s+tell\s+you\s+(?:about\s+)?(.+)\b/i,
      /\bwhat\s+do\s+you\s+know\s+about\s+me\b/i,
      /\bmy\s+profile\b/i,
      /\bhow\s+many\s+(?:things|facts)\s+do\s+you\s+know\s+about\s+me\b/i
    ],
    handle: function () {
      return new Promise(function (resolve) {
        if (!window.AurigaMemory) { resolve('Memory is not available right now.'); return; }
        window.AurigaMemory.getStats().then(function (stats) {
          window.AurigaMemory.getProfileContext().then(function (ctx) {
            if (!ctx || !ctx.trim()) {
              resolve('I have not learned anything about you yet. You can tell me things like your name, where you live, or your preferences, and I will remember them.');
              return;
            }
            resolve('Here is what I know about you. ' + ctx + ' I also have ' + stats.conversations + ' conversation turns stored.');
          });
        }).catch(function () { resolve('I could not access memory right now.'); });
      });
    }
  };

  var forgetSkill = {
    name: 'Forget me',
    description: 'clear all stored memory and profile data',
    match: [
      /\b(forget|erase|clear|wipe|delete)\s+(?:everything|all|my)\s+(?:about\s+me|data|profile|memory|information)\b/i,
      /\bforget\s+(?:about\s+)?me\b/i,
      /\bclear\s+(?:my\s+)?(?:memory|data|profile)\b/i,
      /\bdelete\s+(?:my\s+)?(?:memory|data|profile)\b/i
    ],
    handle: function () {
      return new Promise(function (resolve) {
        if (!window.AurigaMemory) { resolve('Memory module is not loaded.'); return; }
        window.AurigaMemory.clear().then(function () {
          resolve('Done. I have erased everything I knew about you. We are starting fresh.');
        }).catch(function () { resolve('I could not clear the memory. Please try again.'); });
      });
    }
  };

  /* ══════════════════════════════════════════════════════════════════
     9. SYSTEM ACCESSIBILITY CONTROLS
  ══════════════════════════════════════════════════════════════════ */
  var fontSizeSkill = {
    name: 'Font size',
    description: 'increase or decrease the text size on screen',
    match: [
      /\b(increase|make\s+(?:the\s+)?text\s+bigger|larger\s+text|bigger\s+text)\b/i,
      /\b(decrease|make\s+(?:the\s+)?text\s+smaller|smaller\s+text)\b/i,
      /\b(reset|normal)\s+(?:font|text)\s+size\b/i,
      /\bfont\s+size\s+(up|down|reset)\b/i,
      /\btext\s+size\s+(up|down|bigger|smaller)\b/i
    ],
    handle: function (text) {
      var t = text.toLowerCase();
      var current = parseFloat(localStorage.getItem('auriga-font-scale') || '1');
      var next = current;
      if (/increase|bigger|larger|up/.test(t)) { next = Math.min(2.0, current + 0.15); }
      else if (/decrease|smaller|down/.test(t)) { next = Math.max(0.7, current - 0.15); }
      else if (/reset|normal/.test(t)) { next = 1.0; }
      localStorage.setItem('auriga-font-scale', String(next));
      document.documentElement.style.fontSize = (next * 16) + 'px';
      var pct = Math.round(next * 100);
      return 'Text size set to ' + pct + ' percent.';
    }
  };

  var highContrastSkill = {
    name: 'High contrast',
    description: 'toggle high contrast mode',
    match: [
      /\b(enable|turn\s+on|activate)\s+high\s+contrast\b/i,
      /\b(disable|turn\s+off|deactivate)\s+high\s+contrast\b/i,
      /\bhigh\s+contrast\s+(on|off|mode)\b/i,
      /\btoggle\s+(?:high\s+)?contrast\b/i
    ],
    handle: function (text) {
      var t = text.toLowerCase();
      var on = /enable|turn\s+on|activate|\bon\b/.test(t);
      var off = /disable|turn\s+off|deactivate|\boff\b/.test(t);
      var current = document.body.classList.contains('high-contrast');
      var next = off ? false : on ? true : !current;
      document.body.classList.toggle('high-contrast', next);
      localStorage.setItem('auriga-high-contrast', next ? '1' : '0');
      return 'High contrast ' + (next ? 'enabled' : 'disabled') + '.';
    }
  };

  var speechRateSkill = {
    name: 'Speech rate',
    description: 'make the voice faster or slower',
    match: [
      /\b(speak|talk|voice)\s+(faster|quicker|slower|slower|more\s+slowly)\b/i,
      /\bspeed\s+up\b/i,
      /\bslow\s+down\b/i,
      /\bspeech\s+(?:rate|speed)\s+(up|down|faster|slower)\b/i,
      /\bnormal\s+(?:speech|speed|rate)\b/i
    ],
    handle: function (text) {
      var t = text.toLowerCase();
      var current = parseFloat(localStorage.getItem('auriga-speech-rate') || '0.95');
      var next = current;
      if (/faster|quicker|speed\s+up/.test(t)) next = Math.min(1.8, current + 0.15);
      else if (/slower|slow\s+down/.test(t)) next = Math.max(0.5, current - 0.15);
      else if (/normal/.test(t)) next = 0.95;
      localStorage.setItem('auriga-speech-rate', String(next));
      var pct = Math.round(next * 100);
      return 'Speech rate set to ' + pct + ' percent. Like this.';
    }
  };

  /* ══════════════════════════════════════════════════════════════════
     10. QUICK KNOWLEDGE
  ══════════════════════════════════════════════════════════════════ */
  var spellingSkill = {
    name: 'Spell word',
    description: 'spell out any word letter by letter',
    match: [
      /\bhow\s+do\s+you\s+spell\s+(\w+)\b/i,
      /\bspell\s+(?:the\s+word\s+)?(\w+)\b/i,
      /\bspelling\s+of\s+(\w+)\b/i
    ],
    handle: function (text, match) {
      var word = match && match[1] ? match[1].trim() : '';
      if (!word) return 'Which word would you like me to spell?';
      var letters = word.toUpperCase().split('').join(', ');
      return word + ' is spelled: ' + letters + '.';
    }
  };

  var randomNumberSkill = {
    name: 'Random number',
    description: 'generate a random number',
    match: [
      /\brandom\s+number\b/i,
      /\bpick\s+a\s+(?:random\s+)?number\b/i,
      /\broll\s+(?:a\s+)?(?:dice|die)\b/i,
      /\bflip\s+(?:a\s+)?coin\b/i,
      /\brandom\s+number\s+(?:between\s+)?(\d+)\s+and\s+(\d+)/i,
      /\bnumber\s+between\s+(\d+)\s+and\s+(\d+)/i
    ],
    handle: function (text) {
      var t = text.toLowerCase();
      if (/flip\s+(?:a\s+)?coin/.test(t)) {
        return Math.random() < 0.5 ? 'Heads.' : 'Tails.';
      }
      if (/roll\s+(?:a\s+)?(?:dice|die)/.test(t)) {
        return 'You rolled a ' + (Math.floor(Math.random() * 6) + 1) + '.';
      }
      var range = t.match(/(?:between\s+)?(\d+)\s+and\s+(\d+)/);
      if (range) {
        var lo = parseInt(range[1], 10), hi = parseInt(range[2], 10);
        if (lo > hi) { var tmp = lo; lo = hi; hi = tmp; }
        return 'Random number between ' + lo + ' and ' + hi + ': ' + (Math.floor(Math.random() * (hi - lo + 1)) + lo) + '.';
      }
      return 'Random number: ' + Math.floor(Math.random() * 100 + 1) + '.';
    }
  };

  var countdownSkill = {
    name: 'Countdown',
    description: 'count down from a number',
    match: [
      /\bcount\s+down\s+(?:from\s+)?(\d+)\b/i,
      /\bcountdown\s+from\s+(\d+)\b/i,
      /\bcount\s+from\s+(\d+)\s+(?:down\s+)?to\s+(?:zero|0)\b/i
    ],
    handle: function (text, match) {
      var from = match && match[1] ? parseInt(match[1], 10) : 10;
      if (from > 60) return 'That is too large for a countdown. Please use a number up to 60.';
      var i = from;
      var iv = setInterval(function () {
        speak(String(i), 'cd-' + i);
        i--;
        if (i < 0) {
          clearInterval(iv);
          speak('Go!', 'cd-go');
        }
      }, 1200);
      return 'Starting countdown from ' + from + '.';
    }
  };

  var stopwatchState = { running: false, startMs: null, lapMs: null };

  var stopwatchSkill = {
    name: 'Stopwatch',
    description: 'start, stop, or lap a stopwatch',
    match: [
      /\b(start|begin)\s+(?:the\s+)?stopwatch\b/i,
      /\b(stop|pause)\s+(?:the\s+)?stopwatch\b/i,
      /\bstopwatch\s+(start|stop|lap|reset)\b/i,
      /\b(lap|split)\s+(?:the\s+)?stopwatch\b/i,
      /\breset\s+(?:the\s+)?stopwatch\b/i,
      /\bhow\s+(?:long\s+has\s+it\s+been|long\s+is\s+the\s+stopwatch)\b/i
    ],
    handle: function (text) {
      var t = text.toLowerCase();
      if (/start|begin/.test(t)) {
        stopwatchState.running = true;
        stopwatchState.startMs = Date.now();
        stopwatchState.lapMs = Date.now();
        return 'Stopwatch started.';
      }
      if (/stop|pause/.test(t)) {
        if (!stopwatchState.running) return 'The stopwatch is not running.';
        stopwatchState.running = false;
        var elapsed = Math.round((Date.now() - stopwatchState.startMs) / 1000);
        return 'Stopwatch stopped at ' + formatDuration(elapsed) + '.';
      }
      if (/lap|split/.test(t)) {
        if (!stopwatchState.running) return 'Start the stopwatch first.';
        var lap = Math.round((Date.now() - stopwatchState.lapMs) / 1000);
        stopwatchState.lapMs = Date.now();
        return 'Lap time: ' + formatDuration(lap) + '.';
      }
      if (/reset/.test(t)) {
        stopwatchState = { running: false, startMs: null, lapMs: null };
        return 'Stopwatch reset.';
      }
      if (!stopwatchState.running || !stopwatchState.startMs) return 'The stopwatch is not running. Say "start stopwatch" to begin.';
      var current = Math.round((Date.now() - stopwatchState.startMs) / 1000);
      return 'Elapsed time: ' + formatDuration(current) + '.';
    }
  };

  /* ══════════════════════════════════════════════════════════════════
     11. NEWS DIGEST [online-optional — uses RSS feeds via allorigins proxy]
  ══════════════════════════════════════════════════════════════════ */
  var newsCache = recall('news-cache', null);
  var NEWS_MAX_AGE_MS = 60 * 60 * 1000; /* 1 hour */

  /* Accessibility-focused default feeds (BBC, AP, Reuters) */
  var NEWS_FEEDS = [
    { name: 'BBC News', url: 'https://feeds.bbci.co.uk/news/rss.xml' },
    { name: 'AP News', url: 'https://rss.ap.org/Rss/AXBpZmllZD10cnVlJnJlZ2lvbj0xMTEmaWQ9YW5ld3M%3D' }
  ];

  function fetchNews(callback) {
    if (newsCache && (Date.now() - newsCache.ts) < NEWS_MAX_AGE_MS) {
      callback(null, newsCache.headlines);
      return;
    }
    if (!navigator.onLine) { callback('offline', null); return; }

    var feed = NEWS_FEEDS[0];
    var proxy = 'https://api.allorigins.win/get?url=' + encodeURIComponent(feed.url);
    fetch(proxy, { signal: AbortSignal.timeout ? AbortSignal.timeout(10000) : undefined })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (data) {
        if (!data || !data.contents) { callback('parse-error', null); return; }
        var parser = new DOMParser();
        var doc = parser.parseFromString(data.contents, 'text/xml');
        var items = doc.querySelectorAll('item');
        var headlines = [];
        items.forEach(function (item, idx) {
          if (idx >= 5) return;
          var title = item.querySelector('title');
          if (title) headlines.push(title.textContent.trim());
        });
        newsCache = { ts: Date.now(), headlines: headlines };
        store('news-cache', newsCache);
        callback(null, headlines);
      })
      .catch(function () { callback('fetch-error', null); });
  }

  var newsSkill = {
    name: 'News headlines',
    description: 'hear the latest news headlines',
    match: [
      /\b(latest\s+)?news\b/i,
      /\bheadlines?\b/i,
      /\bwhat'?s?\s+(?:happening|going\s+on|in\s+the\s+news)\b/i,
      /\bbrief(?:ing)?\s+(?:me\s+on\s+)?(?:the\s+)?news\b/i,
      /\btop\s+stories?\b/i
    ],
    handle: function () {
      return new Promise(function (resolve) {
        speak('Fetching the latest headlines. One moment.', 'news-loading');
        fetchNews(function (err, headlines) {
          if (err === 'offline') { resolve('I am offline. I cannot fetch the news right now.'); return; }
          if (err || !headlines || !headlines.length) { resolve('I could not load the news right now. Please try again shortly.'); return; }
          resolve('Here are today\'s top ' + headlines.length + ' headlines. ' + headlines.join('. ') + '.');
        });
      });
    }
  };

  /* ══════════════════════════════════════════════════════════════════
     12. SKIP / ABOUT SKILLS PAGE
  ══════════════════════════════════════════════════════════════════ */
  var skillsPageSkill = {
    name: 'Open skills page',
    description: 'see all available skills and voice commands',
    match: [
      /\b(open|show|view|go\s+to)\s+(?:the\s+)?skills?\s*(?:page|list|menu|directory|guide)?\b/i,
      /\bwhat\s+skills?\s+(?:do\s+you\s+have|can\s+you\s+do)\b/i,
      /\ball\s+(?:available\s+)?(?:skills?|commands?|features?)\b/i,
      /\bskills?\s+(?:directory|guide)\b/i
    ],
    handle: function () {
      window.location.href = 'skills.html';
      return 'Opening the skills directory.';
    }
  };

  /* ══════════════════════════════════════════════════════════════════
     REGISTRATION — wait for Jarvis to be ready then register all skills
  ══════════════════════════════════════════════════════════════════ */
  var ALL_SKILLS = [
    timerSkill, cancelTimerSkill, listTimersSkill,
    setAlarmSkill, cancelAlarmSkill,
    setReminderSkill, listRemindersSkill, clearRemindersSkill,
    calcSkill, convertSkill,
    weatherSkill, compassSkill,
    lightsSkill, thermostatSkill, lockSkill,
    memoryQuerySkill, forgetSkill,
    fontSizeSkill, highContrastSkill, speechRateSkill,
    spellingSkill, randomNumberSkill, countdownSkill, stopwatchSkill,
    newsSkill, skillsPageSkill
  ];

  function registerAll() {
    if (!window.Jarvis || !window.Jarvis.registerSkill) return;
    ALL_SKILLS.forEach(function (skill) {
      window.Jarvis.registerSkill(skill);
    });

    /* Restore accessibility preferences on boot */
    var savedRate = parseFloat(localStorage.getItem('auriga-speech-rate') || '0');
    if (savedRate > 0.1) localStorage.setItem('auriga-speech-rate', String(savedRate));
    var savedScale = parseFloat(localStorage.getItem('auriga-font-scale') || '0');
    if (savedScale > 0.1) document.documentElement.style.fontSize = (savedScale * 16) + 'px';
    var hc = localStorage.getItem('auriga-high-contrast');
    if (hc === '1') document.body.classList.add('high-contrast');

    console.log('[AurigaSkills] ' + ALL_SKILLS.length + ' skills registered.');
  }

  /* Register immediately if Jarvis already loaded, else wait for it */
  if (window.Jarvis && window.Jarvis.registerSkill) {
    registerAll();
  } else {
    document.addEventListener('jarvis:ready', registerAll, { once: true });
    /* Also try on DOMContentLoaded as a fallback */
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', function () {
        setTimeout(registerAll, 500);
      });
    } else {
      setTimeout(registerAll, 500);
    }
  }

  /* ── Public API ─────────────────────────────────────────────────── */
  window.AurigaSkills = {
    getAll: function () { return ALL_SKILLS.slice(); },
    getActiveTimers: function () { return Object.assign({}, activeTimers); },
    getActiveAlarms: function () { return Object.assign({}, activeAlarms); },
    getReminders: function () { return reminders.slice(); },
    parseTimeDuration: parseTimeDuration,
    formatDuration: formatDuration
  };

})();
