/* ─────────────────────────────────────────────────────────────
   Auriga shared hamburger nav drawer + accessibility console
   ─────────────────────────────────────────────────────────────
   Auto-injects on every page that loads this script:
     - The slide-in nav drawer + scrim
     - A floating hamburger (only if the page has no #navToggle)
     - A "live status" chip strip at the top of the drawer that
       lights up the modes the user has currently active
     - An "Accessibility Console" docked at the bottom of the
       drawer with LED-style toggle tiles for High Contrast,
       Large Text, Reduce Motion, and DrakoVoice (TTS)

   State persists in localStorage and is reapplied on every
   page load, so toggling High Contrast on the home page keeps
   it on when you navigate to the calibration library, the
   reader, or the feedback page.

   Globals exposed:
     toggleNavDrawer()  closeNavDrawer()
     setA11y(key, on)   srAnnounce(text)
     window.ttsEnabled  (read-only mirror of TTS state)
   ───────────────────────────────────────────────────────────── */

(function () {
    'use strict';

    /* ── NAV MODEL ────────────────────────────────────────────── */
    const NAV_SECTIONS = [
        {
            label: 'Site',
            items: [
                { text: 'HOME',      href: 'index.html' },
                { text: 'ECOSYSTEM', href: 'index.html#ecosystem' },
                { text: 'POSITION',  href: 'index.html#position' }
            ]
        },
        {
            label: 'Products',
            items: [
                { text: 'NAVI',       href: 'index.html#navi' },
                { text: 'SENTINEL',   href: 'index.html#sentinel' },
                { text: 'AERO',       href: 'index.html#aero' },
                { text: 'INDUSTRIAL', href: 'index.html#industrial' }
            ]
        },
        {
            label: 'Tools',
            items: [
                { text: 'CALIBRATION LIBRARY', href: 'calibration-library.html' },
                { text: 'OBJECT LOCATOR',      href: 'locator.html' },
                { text: 'DRAKOVOICE READER',   href: 'reader.html' }
            ]
        },
        {
            label: 'Other',
            items: [
                { text: 'FEEDBACK', href: 'feedback.html' },
                { text: 'ABOUT',    href: 'about.html' }
            ]
        }
    ];

    /* ── ACCESSIBILITY MODEL ──────────────────────────────────── */
    // key       → body class applied when the toggle is on
    // chip      → label rendered in the top status strip
    // tile      → 2-line label rendered inside the toggle tile
    // tone      → "cyan" (visual modes) or "amber" (audio / DrakoVoice)
    const A11Y_TOGGLES = [
        { key: 'hc',  bodyClass: 'high-contrast', chip: 'HC',  tile: ['HIGH', 'CONTRAST'], tone: 'cyan'  },
        { key: 'lt',  bodyClass: 'large-text',    chip: 'A+',  tile: ['LARGE', 'TEXT'],    tone: 'cyan'  },
        { key: 'rm',  bodyClass: 'reduce-motion', chip: '~',   tile: ['REDUCE', 'MOTION'], tone: 'cyan'  },
        { key: 'tts', bodyClass: null,            chip: '♪',   tile: ['DRAKO', 'VOICE'],   tone: 'amber' }
    ];
    const A11Y_STORAGE_PREFIX = 'auriga-a11y-';

    /* TTS state lives at module scope so the focusin handler can
       check it cheaply on every focus change without DOM lookups. */
    let ttsEnabled = false;

    /* ── EARLY STATE LOAD ─────────────────────────────────────── */
    // Apply persisted body classes as soon as the script runs.
    // Because nav-drawer.js loads with `defer`, this executes
    // after <body> exists but before DOMContentLoaded fires —
    // which is the earliest the body element is available, and
    // keeps the flash-of-unstyled-content to a single repaint.
    function loadA11yState() {
        A11Y_TOGGLES.forEach(function (t) {
            const stored = localStorage.getItem(A11Y_STORAGE_PREFIX + t.key) === '1';
            if (t.key === 'tts') {
                ttsEnabled = stored;
            } else if (stored && t.bodyClass) {
                document.body.classList.add(t.bodyClass);
            }
        });
        window.ttsEnabled = ttsEnabled;
    }

    /* ── SPEECH (DRAKOVOICE) ──────────────────────────────────── */
    function srAnnounce(message) {
        if (!ttsEnabled) return;
        if (!('speechSynthesis' in window)) return;
        const u = new SpeechSynthesisUtterance(message);
        u.rate = 1.1;
        u.pitch = 0.9;
        window.speechSynthesis.speak(u);
    }
    window.srAnnounce = srAnnounce;

    /* When DrakoVoice is on, speak the label of any focusable
       element the user lands on. We restrict this to controls
       (links, buttons, form fields) so reading large blocks of
       body text doesn't drown them in narration. */
    function onFocusInForTTS(e) {
        if (!ttsEnabled) return;
        const t = e.target;
        if (!t || !t.matches) return;
        if (!t.matches('a, button, input, select, textarea, [role="button"], [tabindex]')) return;
        const text = t.getAttribute('aria-label')
            || t.innerText
            || t.value
            || '';
        const trimmed = text.trim().slice(0, 180);
        if (!trimmed) return;
        window.speechSynthesis.cancel();
        srAnnounce(trimmed);
    }

    /* ── BUILDERS ─────────────────────────────────────────────── */
    function buildStatusStrip() {
        const wrap = document.createElement('div');
        wrap.className = 'a11y-status';
        wrap.id = 'a11yStatus';
        wrap.setAttribute('aria-live', 'polite');
        wrap.setAttribute('aria-label', 'Active accessibility modes');
        return wrap;
    }

    function buildA11yConsole() {
        const wrap = document.createElement('div');
        wrap.className = 'a11y-console';
        wrap.setAttribute('role', 'region');
        wrap.setAttribute('aria-label', 'Accessibility console');

        let html = '<div class="a11y-console-head">'
                 +   '<span class="a11y-console-eyebrow">Accessibility</span>'
                 +   '<span class="a11y-console-hint">Settings persist across pages</span>'
                 + '</div>'
                 + '<div class="a11y-grid">';

        A11Y_TOGGLES.forEach(function (t) {
            html += '<button type="button" class="a11y-tile" '
                 +    'data-a11y="' + t.key + '" '
                 +    'data-tone="' + t.tone + '" '
                 +    'aria-pressed="false">'
                 +    '<span class="a11y-led" aria-hidden="true"></span>'
                 +    '<span class="a11y-tile-label">'
                 +      '<span>' + t.tile[0] + '</span>'
                 +      '<span>' + t.tile[1] + '</span>'
                 +    '</span>'
                 +  '</button>';
        });

        html += '</div>';
        wrap.innerHTML = html;
        return wrap;
    }

    function buildDrawer() {
        const aside = document.createElement('aside');
        aside.className = 'nav-drawer';
        aside.id = 'navLinks';
        aside.setAttribute('role', 'dialog');
        aside.setAttribute('aria-modal', 'true');
        aside.setAttribute('aria-label', 'Site navigation');

        // Drawer head: title + status chips on the left, close
        // button on the right.
        const head = document.createElement('div');
        head.className = 'nav-drawer-head';
        head.innerHTML = ''
            + '<div class="nav-drawer-head-left">'
            +   '<span class="nav-drawer-title">Menu</span>'
            + '</div>'
            + '<button type="button" class="nav-drawer-close" id="navClose" '
            +   'aria-label="Close navigation menu">&times;</button>';
        aside.appendChild(head);

        // Status strip slots into the head's left column so chips
        // sit right under the "MENU" title.
        head.querySelector('.nav-drawer-head-left').appendChild(buildStatusStrip());

        // Scrollable middle: nav sections.
        const body = document.createElement('div');
        body.className = 'nav-drawer-body';
        let bodyHtml = '';
        NAV_SECTIONS.forEach(function (sec) {
            bodyHtml += '<div class="nav-section-label">' + sec.label + '</div>';
            bodyHtml += '<div class="nav-section">';
            sec.items.forEach(function (item) {
                bodyHtml += '<a href="' + item.href + '">' + item.text + '</a>';
            });
            bodyHtml += '</div>';
        });
        body.innerHTML = bodyHtml;
        aside.appendChild(body);

        // Sticky footer: accessibility console.
        aside.appendChild(buildA11yConsole());

        return aside;
    }

    function buildScrim() {
        const scrim = document.createElement('div');
        scrim.className = 'nav-scrim';
        scrim.id = 'navScrim';
        scrim.setAttribute('aria-hidden', 'true');
        return scrim;
    }

    function buildFloatingToggle() {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'nav-toggle floating';
        btn.id = 'navToggle';
        btn.setAttribute('aria-expanded', 'false');
        btn.setAttribute('aria-controls', 'navLinks');
        btn.setAttribute('aria-label', 'Open navigation menu');
        btn.innerHTML = ''
            + '<span class="hamburger-icon" aria-hidden="true">'
            +   '<span></span><span></span><span></span>'
            + '</span>'
            + 'MENU';
        return btn;
    }

    /* ── DRAWER OPEN/CLOSE ────────────────────────────────────── */
    function setNavOpen(open) {
        const links = document.getElementById('navLinks');
        const scrim = document.getElementById('navScrim');
        const btn   = document.getElementById('navToggle');
        if (!links || !btn) return;
        links.classList.toggle('open', open);
        if (scrim) scrim.classList.toggle('open', open);
        document.body.classList.toggle('nav-open', open);
        btn.setAttribute('aria-expanded', open ? 'true' : 'false');
        btn.setAttribute('aria-label',
            open ? 'Close navigation menu' : 'Open navigation menu');
        if (open) {
            const closeBtn = document.getElementById('navClose');
            if (closeBtn) closeBtn.focus();
        } else {
            btn.focus();
        }
    }

    function toggleNavDrawer() {
        const links = document.getElementById('navLinks');
        if (!links) return;
        setNavOpen(!links.classList.contains('open'));
    }
    function closeNavDrawer() { setNavOpen(false); }

    window.toggleNavDrawer = toggleNavDrawer;
    window.closeNavDrawer  = closeNavDrawer;

    /* ── A11Y TOGGLES + UI SYNC ───────────────────────────────── */
    function isOn(key) {
        const t = A11Y_TOGGLES.find(function (x) { return x.key === key; });
        if (!t) return false;
        if (key === 'tts') return ttsEnabled;
        return t.bodyClass ? document.body.classList.contains(t.bodyClass) : false;
    }

    function setA11y(key, on) {
        const t = A11Y_TOGGLES.find(function (x) { return x.key === key; });
        if (!t) return;
        if (key === 'tts') {
            ttsEnabled = !!on;
            window.ttsEnabled = ttsEnabled;
            if (!ttsEnabled && 'speechSynthesis' in window) {
                window.speechSynthesis.cancel();
            }
        } else if (t.bodyClass) {
            document.body.classList.toggle(t.bodyClass, !!on);
        }
        try {
            localStorage.setItem(A11Y_STORAGE_PREFIX + key, on ? '1' : '0');
        } catch (_) { /* storage may be blocked; non-fatal */ }

        renderA11yUI();

        // Brief spoken confirmation. For TTS itself, announce on
        // turn-on so the user gets immediate audio feedback.
        if (key === 'tts' && on) {
            // Force an announcement even though srAnnounce normally
            // gates on ttsEnabled (which is now true).
            srAnnounce('DrakoVoice activated.');
        } else if (key !== 'tts') {
            srAnnounce(t.tile.join(' ') + (on ? ' enabled' : ' disabled'));
        }
    }
    window.setA11y = setA11y;

    function renderA11yUI() {
        // Tiles in the console
        document.querySelectorAll('.a11y-tile').forEach(function (tile) {
            const key = tile.getAttribute('data-a11y');
            const on  = isOn(key);
            tile.setAttribute('aria-pressed', on ? 'true' : 'false');
            tile.classList.toggle('on', on);
        });
        // Status chips at the top of the drawer
        const strip = document.getElementById('a11yStatus');
        if (!strip) return;
        let html = '';
        A11Y_TOGGLES.forEach(function (t) {
            if (!isOn(t.key)) return;
            html += '<span class="a11y-chip" data-tone="' + t.tone + '" '
                 +    'title="' + t.tile.join(' ') + ' is on">'
                 +    t.chip
                 +  '</span>';
        });
        strip.innerHTML = html;
        strip.classList.toggle('has-chips', html.length > 0);
    }

    function wireA11yTiles(root) {
        root.querySelectorAll('.a11y-tile').forEach(function (tile) {
            if (tile.dataset.navWired) return;
            tile.addEventListener('click', function () {
                const key = tile.getAttribute('data-a11y');
                setA11y(key, !isOn(key));
            });
            tile.dataset.navWired = '1';
        });
    }

    /* ── INIT ─────────────────────────────────────────────────── */
    function init() {
        loadA11yState();

        if (!document.getElementById('navLinks')) {
            document.body.appendChild(buildDrawer());
        }
        if (!document.getElementById('navScrim')) {
            document.body.appendChild(buildScrim());
        }
        if (!document.getElementById('navToggle')) {
            document.body.appendChild(buildFloatingToggle());
        }

        const btn   = document.getElementById('navToggle');
        const close = document.getElementById('navClose');
        const scrim = document.getElementById('navScrim');
        const drawer = document.getElementById('navLinks');

        if (btn && !btn.dataset.navWired) {
            btn.addEventListener('click', toggleNavDrawer);
            btn.dataset.navWired = '1';
        }
        if (close && !close.dataset.navWired) {
            close.addEventListener('click', closeNavDrawer);
            close.dataset.navWired = '1';
        }
        if (scrim && !scrim.dataset.navWired) {
            scrim.addEventListener('click', closeNavDrawer);
            scrim.dataset.navWired = '1';
        }
        if (drawer) wireA11yTiles(drawer);

        document.addEventListener('keydown', function (e) {
            if (e.key !== 'Escape') return;
            const links = document.getElementById('navLinks');
            if (links && links.classList.contains('open')) closeNavDrawer();
        });

        document.addEventListener('focusin', onFocusInForTTS);

        // Reflect persisted state in the freshly-built tiles + chips.
        renderA11yUI();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
