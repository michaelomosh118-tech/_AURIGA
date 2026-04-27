/* ─────────────────────────────────────────────────────────────
   Auriga Locator — shared targets store
   ─────────────────────────────────────────────────────────────
   Targets are now objects (not just class-name strings) so we can
   carry extra mission context per target:

     {
       name:          string,    // COCO class, e.g. "person"
       description:   string,    // free-text mission notes
       lastSeenAt:    number,    // ms epoch, 0 if never
       lastBearing:   number,    // signed degrees, NaN if never
       lastDistance:  number     // metres, NaN if never
     }

   Backwards compatible: any pre-existing storage written by the
   old locator.html (a JSON array of plain class-name strings) is
   transparently migrated on first read.

   Storage key: 'auriga-locator-targets' (unchanged on purpose so
   old chip-only data is preserved across the upgrade).

   Exposes window.AurigaLocatorStore with:
     load()                   → Target[]
     save(targets)            → void
     add(name, description)   → Target | null   (no-op if duplicate)
     remove(name)             → boolean
     update(name, patch)      → Target | null
     touch(name, bearing, distance) → Target | null  (records sighting)
     names()                  → string[]
     has(name)                → boolean
     COCO_CLASSES             → readonly string[]
   ─────────────────────────────────────────────────────────────── */

(function () {
  'use strict';

  const STORAGE_KEY = 'auriga-locator-targets';

  // The 80 COCO classes the bundled COCO-SSD model recognises.
  const COCO_CLASSES = Object.freeze([
    'person','bicycle','car','motorcycle','airplane','bus','train','truck','boat',
    'traffic light','fire hydrant','stop sign','parking meter','bench',
    'bird','cat','dog','horse','sheep','cow','elephant','bear','zebra','giraffe',
    'backpack','umbrella','handbag','tie','suitcase',
    'frisbee','skis','snowboard','sports ball','kite','baseball bat','baseball glove','skateboard','surfboard','tennis racket',
    'bottle','wine glass','cup','fork','knife','spoon','bowl',
    'banana','apple','sandwich','orange','broccoli','carrot','hot dog','pizza','donut','cake',
    'chair','couch','potted plant','bed','dining table','toilet',
    'tv','laptop','mouse','remote','keyboard','cell phone',
    'microwave','oven','toaster','sink','refrigerator',
    'book','clock','vase','scissors','teddy bear','hair drier','toothbrush'
  ]);

  function makeTarget(name, description) {
    return {
      name: String(name || '').trim(),
      description: String(description || '').trim(),
      lastSeenAt: 0,
      lastBearing: NaN,
      lastDistance: NaN
    };
  }

  function normalise(entry) {
    if (typeof entry === 'string') {
      // Legacy chip data — migrate transparently.
      return makeTarget(entry, '');
    }
    if (entry && typeof entry === 'object' && entry.name) {
      return {
        name: String(entry.name).trim(),
        description: typeof entry.description === 'string' ? entry.description : '',
        lastSeenAt: Number.isFinite(entry.lastSeenAt) ? entry.lastSeenAt : 0,
        lastBearing: Number.isFinite(entry.lastBearing) ? entry.lastBearing : NaN,
        lastDistance: Number.isFinite(entry.lastDistance) ? entry.lastDistance : NaN
      };
    }
    return null;
  }

  function load() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) return [];
      const parsed = JSON.parse(raw);
      if (!Array.isArray(parsed)) return [];
      const out = [];
      const seen = new Set();
      parsed.forEach(function (e) {
        const t = normalise(e);
        if (!t || !t.name) return;
        if (seen.has(t.name)) return;
        seen.add(t.name);
        out.push(t);
      });
      return out;
    } catch (_) {
      return [];
    }
  }

  function save(targets) {
    try {
      const safe = (Array.isArray(targets) ? targets : [])
        .map(normalise)
        .filter(function (t) { return t && t.name; });
      localStorage.setItem(STORAGE_KEY, JSON.stringify(safe));
    } catch (_) { /* storage may be blocked; non-fatal */ }
  }

  function add(name, description) {
    const list = load();
    const cleanName = String(name || '').trim();
    if (!cleanName) return null;
    if (list.some(function (t) { return t.name === cleanName; })) return null;
    const target = makeTarget(cleanName, description);
    list.push(target);
    save(list);
    return target;
  }

  function remove(name) {
    const list = load();
    const next = list.filter(function (t) { return t.name !== name; });
    if (next.length === list.length) return false;
    save(next);
    return true;
  }

  function update(name, patch) {
    const list = load();
    const idx = list.findIndex(function (t) { return t.name === name; });
    if (idx < 0) return null;
    const merged = Object.assign({}, list[idx], patch || {});
    list[idx] = normalise(merged) || list[idx];
    save(list);
    return list[idx];
  }

  function touch(name, bearing, distance) {
    return update(name, {
      lastSeenAt: Date.now(),
      lastBearing: Number.isFinite(bearing) ? bearing : NaN,
      lastDistance: Number.isFinite(distance) ? distance : NaN
    });
  }

  function names() {
    return load().map(function (t) { return t.name; });
  }

  function has(name) {
    return load().some(function (t) { return t.name === name; });
  }

  window.AurigaLocatorStore = Object.freeze({
    STORAGE_KEY: STORAGE_KEY,
    COCO_CLASSES: COCO_CLASSES,
    load: load,
    save: save,
    add: add,
    remove: remove,
    update: update,
    touch: touch,
    names: names,
    has: has
  });
})();
