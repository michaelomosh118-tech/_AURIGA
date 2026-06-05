/* ═══════════════════════════════════════════════════════════════════════
   AurigaMemory — persistent conversation store + user profile extractor
   Runs entirely on-device using IndexedDB. No server, no API key.

   What it stores:
     · Every Q&A exchange (role: 'user' | 'assistant', text, page, ts)
     · User profile facts auto-extracted from natural speech
       ("my name is…", "I live in…", "I have a guide dog…")
     · Quality signals — "yes exactly" marks an exchange as helpful,
       "that's wrong" marks it low quality — used to rank context

   Exposed as window.AurigaMemory:
     .store(role, text)         — save one turn (call after every exchange)
     .getContext(query, n)      — top-n relevant past turns for RAG
     .getProfileContext()       — user profile as a readable sentence
     .extractAndSaveProfile(t)  — run profile extraction on user text
     .getStats()                — { conversations, profileFacts }
     .clear()                   — wipe all stored data
═══════════════════════════════════════════════════════════════════════ */
(function () {
  'use strict';

  var DB_NAME    = 'auriga-ai-memory';
  var DB_VERSION = 1;
  var STORE_CONV = 'conversations';
  var STORE_PROF = 'profile';
  var MAX_CONV   = 2000;   // cap to prevent unbounded growth

  var db = null;

  /* ── Open / init DB ────────────────────────────────────────────── */
  function openDB() {
    return new Promise(function (resolve, reject) {
      if (db) { resolve(db); return; }
      if (!window.indexedDB) { reject(new Error('IndexedDB not available')); return; }
      var req = indexedDB.open(DB_NAME, DB_VERSION);
      req.onupgradeneeded = function (e) {
        var d = e.target.result;
        if (!d.objectStoreNames.contains(STORE_CONV)) {
          var cs = d.createObjectStore(STORE_CONV, { keyPath: 'id', autoIncrement: true });
          cs.createIndex('ts',   'ts',   { unique: false });
          cs.createIndex('role', 'role', { unique: false });
        }
        if (!d.objectStoreNames.contains(STORE_PROF)) {
          d.createObjectStore(STORE_PROF, { keyPath: 'key' });
        }
      };
      req.onsuccess = function (e) { db = e.target.result; resolve(db); };
      req.onerror   = function (e) { reject(e.target.error); };
    });
  }

  /* ── Store one conversation turn ──────────────────────────────── */
  function store(role, text, quality) {
    if (!text || !text.trim()) return Promise.resolve();
    return openDB().then(function (d) {
      return new Promise(function (resolve) {
        var tx  = d.transaction(STORE_CONV, 'readwrite');
        var st  = tx.objectStore(STORE_CONV);
        var rec = {
          role:    role || 'user',
          text:    text.trim().slice(0, 400),   // cap length
          page:    (window.location.pathname.split('/').pop() || 'index.html'),
          ts:      Date.now(),
          quality: quality || 0   // -1 wrong, 0 neutral, 1 good
        };
        st.add(rec);
        tx.oncomplete = resolve;
        tx.onerror    = resolve;   // non-fatal — don't break the main flow

        /* Enforce MAX_CONV cap asynchronously */
        st.count().onsuccess = function (e) {
          if (e.target.result > MAX_CONV) {
            var trim = d.transaction(STORE_CONV, 'readwrite').objectStore(STORE_CONV);
            trim.index('ts').openCursor().onsuccess = function (ce) {
              var cur = ce.target.result;
              if (cur) { cur.delete(); }
            };
          }
        };
      });
    }).catch(function () {});
  }

  /* ── Mark the last assistant turn with a quality signal ─────── */
  function markLastQuality(signal) {
    /* signal: 1 (good) or -1 (bad) */
    return openDB().then(function (d) {
      return new Promise(function (resolve) {
        var tx  = d.transaction(STORE_CONV, 'readwrite');
        var idx = tx.objectStore(STORE_CONV).index('ts');
        idx.openCursor(null, 'prev').onsuccess = function (e) {
          var cur = e.target.result;
          if (cur && cur.value.role === 'assistant') {
            var rec = cur.value;
            rec.quality = signal;
            cur.update(rec);
          }
          resolve();
        };
      });
    }).catch(function () {});
  }

  /* ── Retrieve recent turns as RAG context ─────────────────────── */
  /*
   * Returns the last `n` turns as a formatted string like:
   *   User: what is a cup?
   *   Auriga: A cup is a small open container used for drinking…
   *
   * If a query is provided, turns that share keywords with the query
   * are ranked higher so the most relevant context comes first.
   */
  function getContext(query, n) {
    n = n || 5;
    return openDB().then(function (d) {
      return new Promise(function (resolve) {
        var all = [];
        var tx  = d.transaction(STORE_CONV, 'readonly');
        tx.objectStore(STORE_CONV).index('ts').openCursor(null, 'prev').onsuccess = function (e) {
          var cur = e.target.result;
          if (cur && all.length < 60) { all.push(cur.value); cur.continue(); }
          else {
            /* Score by recency + keyword overlap + quality */
            var queryWords = query
              ? query.toLowerCase().split(/\s+/).filter(function (w) { return w.length > 3; })
              : [];
            all = all.map(function (rec) {
              var score = 0;
              if (queryWords.length) {
                var txt = rec.text.toLowerCase();
                queryWords.forEach(function (w) { if (txt.indexOf(w) !== -1) score += 2; });
              }
              score += rec.quality;   // quality boost
              return { rec: rec, score: score };
            });
            /* Take top-n, re-sort by timestamp for natural flow */
            all.sort(function (a, b) { return b.score - a.score; });
            var top = all.slice(0, n).map(function (x) { return x.rec; });
            top.sort(function (a, b) { return a.ts - b.ts; });

            var lines = top.map(function (rec) {
              return (rec.role === 'user' ? 'User' : 'Auriga') + ': ' + rec.text;
            });
            resolve(lines.join('\n'));
          }
        };
      });
    }).catch(function () { return ''; });
  }

  /* ── User profile extraction ──────────────────────────────────── */
  /*
   * Detects first-person facts and stores them in the profile store.
   * Examples:
   *   "my name is Michael"         → {key:'name', value:'Michael'}
   *   "I live in Nairobi"          → {key:'location', value:'Nairobi'}
   *   "I have a guide dog"         → {key:'guide_dog', value:'true'}
   *   "I am 34 years old"          → {key:'age', value:'34'}
   *   "I work as a teacher"        → {key:'occupation', value:'teacher'}
   *   "I prefer short answers"     → {key:'pref_length', value:'short'}
   */
  var PROFILE_RULES = [
    { re: /my name is ([a-z][a-z\s]{1,30})/i,          key: 'name',       extract: 1 },
    { re: /(?:i live in|i'm from|i am from) ([a-z][a-z\s]{1,40})/i, key: 'location', extract: 1 },
    { re: /i am (\d{1,3}) years? old/i,                key: 'age',        extract: 1 },
    { re: /i work(?:ed)? as (?:a |an )?([a-z][a-z\s]{1,30})/i, key: 'occupation', extract: 1 },
    { re: /i have (?:a )?guide dog(?: named ([a-z]+))?/i, key: 'guide_dog', extract: 0, value: 'true' },
    { re: /my (?:guide )?dog(?:'s name)? is ([a-z]+)/i, key: 'dog_name',  extract: 1 },
    { re: /i have (?:a )?white cane/i,                 key: 'uses_cane',  extract: 0, value: 'true' },
    { re: /i prefer (?:short|brief|quick) answers?/i,  key: 'pref_length', extract: 0, value: 'short' },
    { re: /i prefer (?:detailed|long|full) answers?/i, key: 'pref_length', extract: 0, value: 'detailed' },
    { re: /call me ([a-z][a-z]{1,20})/i,               key: 'name',       extract: 1 },
    { re: /i speak ([a-z]+)(?: and ([a-z]+))?/i,       key: 'language',   extract: 1 },
    { re: /my (?:phone|device) is (?:a |an )?([a-z][a-z\s\d]{1,30})/i, key: 'device', extract: 1 }
  ];

  function extractAndSaveProfile(text) {
    if (!text) return Promise.resolve();
    var tasks = [];
    PROFILE_RULES.forEach(function (rule) {
      var m = text.match(rule.re);
      if (m) {
        var val = rule.extract ? (m[1] ? m[1].trim() : null) : rule.value;
        if (val) tasks.push(saveProfileFact(rule.key, val));
      }
    });
    return Promise.all(tasks);
  }

  function saveProfileFact(key, value) {
    return openDB().then(function (d) {
      return new Promise(function (resolve) {
        var tx  = d.transaction(STORE_PROF, 'readwrite');
        tx.objectStore(STORE_PROF).put({ key: key, value: value, updatedAt: Date.now() });
        tx.oncomplete = resolve;
        tx.onerror    = resolve;
      });
    }).catch(function () {});
  }

  function getAllProfileFacts() {
    return openDB().then(function (d) {
      return new Promise(function (resolve) {
        var facts = [];
        var tx = d.transaction(STORE_PROF, 'readonly');
        tx.objectStore(STORE_PROF).openCursor().onsuccess = function (e) {
          var cur = e.target.result;
          if (cur) { facts.push(cur.value); cur.continue(); }
          else resolve(facts);
        };
      });
    }).catch(function () { return []; });
  }

  /* ── Build profile context string for LLM system prompt ──────── */
  function getProfileContext() {
    return getAllProfileFacts().then(function (facts) {
      if (!facts.length) return '';
      var parts = [];
      facts.forEach(function (f) {
        switch (f.key) {
          case 'name':        parts.push('The user\'s name is ' + f.value); break;
          case 'location':    parts.push('They live in ' + f.value); break;
          case 'age':         parts.push('They are ' + f.value + ' years old'); break;
          case 'occupation':  parts.push('They work as a ' + f.value); break;
          case 'guide_dog':   parts.push('They use a guide dog'); break;
          case 'dog_name':    parts.push('Their guide dog is named ' + f.value); break;
          case 'uses_cane':   parts.push('They use a white cane'); break;
          case 'pref_length': parts.push('They prefer ' + f.value + ' answers'); break;
          case 'language':    parts.push('They speak ' + f.value); break;
          case 'device':      parts.push('Their device is a ' + f.value); break;
        }
      });
      return parts.join('. ') + '.';
    });
  }

  /* ── Stats ────────────────────────────────────────────────────── */
  function getStats() {
    return openDB().then(function (d) {
      return Promise.all([
        new Promise(function (res) {
          d.transaction(STORE_CONV, 'readonly').objectStore(STORE_CONV).count().onsuccess =
            function (e) { res(e.target.result); };
        }),
        getAllProfileFacts()
      ]);
    }).then(function (r) {
      return { conversations: r[0], profileFacts: r[1].length };
    }).catch(function () { return { conversations: 0, profileFacts: 0 }; });
  }

  /* ── Clear ────────────────────────────────────────────────────── */
  function clear() {
    return openDB().then(function (d) {
      var tx = d.transaction([STORE_CONV, STORE_PROF], 'readwrite');
      tx.objectStore(STORE_CONV).clear();
      tx.objectStore(STORE_PROF).clear();
      return new Promise(function (res) { tx.oncomplete = res; tx.onerror = res; });
    }).catch(function () {});
  }

  /* ── Init: open DB eagerly in background ─────────────────────── */
  openDB().catch(function () {});   // warm up silently

  /* ── Public API ───────────────────────────────────────────────── */
  window.AurigaMemory = {
    store:                store,
    markLastQuality:      markLastQuality,
    getContext:           getContext,
    extractAndSaveProfile: extractAndSaveProfile,
    getProfileContext:    getProfileContext,
    getStats:             getStats,
    clear:                clear
  };

})();
