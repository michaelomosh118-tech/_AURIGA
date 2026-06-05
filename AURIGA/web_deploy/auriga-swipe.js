/* ═══════════════════════════════════════════════════════════════════════
   AurigaSwipe — global swipe gesture engine
   Works on every page that loads this script.

   Gestures:
     Right edge swipe  (start within 48 px of left) → open AI Chat
     Left  edge swipe  (start within 48 px of right) → go back
     Bottom-up swipe   (start in bottom 90 px)       → activate mic
     Top-down swipe    (start in top   60 px)         → toggle nav drawer

   Always-on toggle:
     Double-tap the centre of the screen               → toggle always-on mic

   Flash indicator:
     A brief translucent pill appears in the centre to confirm the gesture.
═══════════════════════════════════════════════════════════════════════ */
(function () {
  'use strict';

  var EDGE    = 52;      /* px from screen edge that counts as "edge swipe" */
  var MIN_D   = 75;      /* minimum travel distance (px) to confirm swipe   */
  var MAX_CROSS = 65;    /* maximum perpendicular travel allowed             */
  var MAX_MS  = 550;     /* maximum gesture duration (ms)                   */

  var touch0  = null;    /* {x, y, time} of touchstart                      */

  /* ── Indicator pill ────────────────────────────────────────── */
  var pill = null;
  var pillTimer = null;

  function flash(text) {
    if (!pill) {
      pill = document.createElement('div');
      pill.setAttribute('aria-hidden', 'true');
      var s = pill.style;
      s.cssText = [
        'position:fixed',
        'top:50%', 'left:50%',
        'transform:translate(-50%,-50%)',
        'background:rgba(0,184,212,0.82)',
        'color:#000',
        'font:700 18px/1 monospace',
        'letter-spacing:1.5px',
        'padding:11px 22px',
        'border-radius:10px',
        'z-index:999999',
        'pointer-events:none',
        'opacity:0',
        'transition:opacity 0.15s ease'
      ].join(';');
      document.body.appendChild(pill);
    }
    clearTimeout(pillTimer);
    pill.textContent = text;
    /* Force reflow so transition fires */
    pill.style.opacity = '0';
    void pill.offsetWidth;
    pill.style.opacity = '1';
    pillTimer = setTimeout(function () {
      pill.style.opacity = '0';
    }, 600);
  }

  /* ── Touch listeners ──────────────────────────────────────── */
  document.addEventListener('touchstart', function (e) {
    var t = e.touches[0];
    touch0 = { x: t.clientX, y: t.clientY, time: Date.now() };
  }, { passive: true });

  document.addEventListener('touchend', function (e) {
    if (!touch0) return;
    var t   = e.changedTouches[0];
    var dx  = t.clientX - touch0.x;
    var dy  = t.clientY - touch0.y;
    var dt  = Date.now() - touch0.time;
    var sx  = touch0.x;
    var sy  = touch0.y;
    var W   = window.innerWidth;
    var H   = window.innerHeight;
    touch0  = null;

    if (dt > MAX_MS) return;

    /* Right edge swipe → AI Chat */
    if (sx < EDGE && dx > MIN_D && Math.abs(dy) < MAX_CROSS) {
      var dest = 'chat.html';
      /* Don't navigate if already on chat.html */
      if (window.location.pathname.indexOf('chat.html') !== -1) return;
      flash('→ CHAT');
      setTimeout(function () { window.location.href = dest; }, 220);
      return;
    }

    /* Left edge swipe → back */
    if (sx > W - EDGE && dx < -MIN_D && Math.abs(dy) < MAX_CROSS) {
      flash('← BACK');
      setTimeout(function () { history.back(); }, 220);
      return;
    }

    /* Bottom swipe up → activate mic */
    if (sy > H - 90 && dy < -MIN_D && Math.abs(dx) < MAX_CROSS) {
      flash('↑ MIC');
      activateMic();
      return;
    }

    /* Top swipe down → toggle nav drawer */
    if (sy < 60 && dy > 70 && Math.abs(dx) < MAX_CROSS) {
      flash('↓ MENU');
      if (window.toggleNavDrawer) {
        setTimeout(window.toggleNavDrawer, 100);
      }
      return;
    }
  }, { passive: true });

  /* ── Double-tap centre → toggle always-on ─────────────────── */
  var lastTap = 0;

  document.addEventListener('touchend', function (e) {
    var now = Date.now();
    var t   = e.changedTouches[0];
    var W   = window.innerWidth;
    var H   = window.innerHeight;

    /* Must be in the middle third of the screen */
    var inCentreX = t.clientX > W * 0.3 && t.clientX < W * 0.7;
    var inCentreY = t.clientY > H * 0.3 && t.clientY < H * 0.7;

    if (inCentreX && inCentreY && now - lastTap < 320) {
      lastTap = 0;
      toggleAlwaysOn();
    } else {
      lastTap = now;
    }
  }, { passive: true });

  /* ── Mic activation ───────────────────────────────────────── */
  function activateMic() {
    /* AurigaVoice (global, all pages) */
    if (window.AurigaVoice && AurigaVoice.listen) {
      AurigaVoice.listen();
      return;
    }
    /* chat.html has its own mic button */
    var btn = document.getElementById('ch-mic-btn');
    if (btn) { btn.click(); return; }
  }

  /* ── Always-on toggle ─────────────────────────────────────── */
  function toggleAlwaysOn() {
    /* Prefer the chat page's own toggle */
    var alwaysBtn = document.getElementById('ch-always-btn');
    if (alwaysBtn) {
      alwaysBtn.click();
      return;
    }
    /* Fallback: AurigaVoice setAlwaysOn */
    if (window.AurigaVoice && AurigaVoice.setAlwaysOn) {
      var next = !AurigaVoice.alwaysOn;
      AurigaVoice.setAlwaysOn(next);
      flash(next ? '● ALWAYS ON' : '○ NORMAL');
    }
  }

  /* ── Swipe-edge visual guide lines (subtle, accessible) ────── */
  function drawEdgeGuides() {
    /* Left guide — shows chat is reachable */
    var left = document.createElement('div');
    left.setAttribute('aria-hidden', 'true');
    left.style.cssText = [
      'position:fixed', 'left:0', 'top:30%', 'bottom:30%',
      'width:3px',
      'background:linear-gradient(to bottom,transparent,rgba(0,184,212,0.25),transparent)',
      'z-index:9998', 'pointer-events:none',
      'border-radius:0 2px 2px 0'
    ].join(';');

    /* Bottom guide — shows mic swipe */
    var bot = document.createElement('div');
    bot.setAttribute('aria-hidden', 'true');
    bot.style.cssText = [
      'position:fixed', 'bottom:0', 'left:30%', 'right:30%',
      'height:3px',
      'background:linear-gradient(to right,transparent,rgba(0,184,212,0.2),transparent)',
      'z-index:9998', 'pointer-events:none',
      'border-radius:2px 2px 0 0'
    ].join(';');

    /* Only show guides if there are no chat/locator page-specific UI elements
       that would collide. Gate on body having no 'no-swipe-guides' class. */
    if (!document.body.classList.contains('no-swipe-guides')) {
      document.body.appendChild(left);
      document.body.appendChild(bot);
    }
  }

  /* ── Boot ─────────────────────────────────────────────────── */
  function boot() {
    drawEdgeGuides();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    setTimeout(boot, 200);
  }

  /* ── Public API ───────────────────────────────────────────── */
  window.AurigaSwipe = {
    flash:         flash,
    activateMic:   activateMic,
    toggleAlwaysOn: toggleAlwaysOn
  };

})();
