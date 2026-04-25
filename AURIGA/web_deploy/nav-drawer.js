/* ─────────────────────────────────────────────────────────────
   Auriga shared hamburger nav drawer
   ─────────────────────────────────────────────────────────────
   Auto-injects the slide-in nav drawer + scrim on every page
   that loads this script. If the page does not already have a
   #navToggle button (e.g. inside its own topbar), a floating
   hamburger button is injected in the top-right corner.

   Pages can mount a custom hamburger by including markup like:
     <button id="navToggle" class="nav-toggle"
             onclick="toggleNavDrawer()" aria-controls="navLinks">
       <span class="hamburger-icon"><span></span><span></span><span></span></span>
       MENU
     </button>

   Globals exposed: toggleNavDrawer(), closeNavDrawer().
   ───────────────────────────────────────────────────────────── */

(function () {
    'use strict';

    // The single nav model shared across every page.
    // Update once; all pages reflect it on next load.
    const NAV_SECTIONS = [
        {
            label: 'Site',
            items: [
                { text: 'HOME',      href: 'index.html' },
                { text: 'ECOSYSTEM', href: 'index.html#ecosystem' }
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
                { text: 'DRAKOVOICE READER',   href: 'reader.html' }
            ]
        },
        {
            label: 'Other',
            items: [
                { text: 'FEEDBACK', href: 'feedback.html' }
            ]
        }
    ];

    function buildDrawer() {
        const aside = document.createElement('aside');
        aside.className = 'nav-drawer';
        aside.id = 'navLinks';
        aside.setAttribute('role', 'dialog');
        aside.setAttribute('aria-modal', 'true');
        aside.setAttribute('aria-label', 'Site navigation');

        let html = ''
            + '<div class="nav-drawer-head">'
            +   '<span class="nav-drawer-title">Menu</span>'
            +   '<button type="button" class="nav-drawer-close" id="navClose" '
            +     'aria-label="Close navigation menu">&times;</button>'
            + '</div>';

        NAV_SECTIONS.forEach(function (sec) {
            html += '<div class="nav-section-label">' + sec.label + '</div>';
            html += '<div class="nav-section">';
            sec.items.forEach(function (item) {
                html += '<a href="' + item.href + '">' + item.text + '</a>';
            });
            html += '</div>';
        });

        aside.innerHTML = html;
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

    // Expose the toggle/close globally so inline onclick handlers
    // and any page-specific code can call them.
    window.toggleNavDrawer = toggleNavDrawer;
    window.closeNavDrawer  = closeNavDrawer;

    function init() {
        // Inject scrim + drawer if absent.
        if (!document.getElementById('navLinks')) {
            document.body.appendChild(buildDrawer());
        }
        if (!document.getElementById('navScrim')) {
            document.body.appendChild(buildScrim());
        }
        // Inject a floating hamburger only if the page didn't supply
        // its own (e.g. index.html embeds one inside its own <nav>).
        if (!document.getElementById('navToggle')) {
            document.body.appendChild(buildFloatingToggle());
        }

        const btn   = document.getElementById('navToggle');
        const close = document.getElementById('navClose');
        const scrim = document.getElementById('navScrim');

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

        // ESC anywhere on the page closes the drawer.
        document.addEventListener('keydown', function (e) {
            if (e.key !== 'Escape') return;
            const links = document.getElementById('navLinks');
            if (links && links.classList.contains('open')) closeNavDrawer();
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
