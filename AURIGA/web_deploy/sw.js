// Bump the cache name when the asset list or shell HTML changes so old
// clients pick up the new bundle on their next page load.
const CACHE_NAME = 'drakosanctis-v6';

// Local app-shell pages we always want available offline. The fetch
// handler also opportunistically caches every other GET response so
// that the heavy ML CDN scripts (TF.js + COCO-SSD + Tesseract.js) are
// available on the second visit even without a network.
const ASSETS_TO_CACHE = [
  './index.html',
  './reader.html',
  './locator.html',
  './locator-targets.html',
  './locator-store.js',
  './calibration-library.html',
  './feedback.html',
  './about.html',
  './nav-drawer.css',
  './nav-drawer.js',
  './logo.png',
  './manifest.json'
];

self.addEventListener('install', (event) => {
  self.skipWaiting();
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(ASSETS_TO_CACHE))
  );
});

// Network-first with cache fallback, plus opportunistic background
// caching of any successful GET response. This is what lets the
// Object Locator's TF.js + COCO-SSD payload and the Reader's
// Tesseract.js payload stay available offline on the second run.
self.addEventListener('fetch', (event) => {
  if (event.request.method !== 'GET') return;
  event.respondWith(
    fetch(event.request).then((res) => {
      // Only mirror successful, non-opaque responses.
      if (res && res.ok && res.type !== 'opaque') {
        const clone = res.clone();
        caches.open(CACHE_NAME).then((cache) => cache.put(event.request, clone)).catch(() => {});
      }
      return res;
    }).catch(() => caches.match(event.request).then((hit) => hit || caches.match('./index.html')))
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((cacheNames) => Promise.all(
      cacheNames.map((cache) => cache !== CACHE_NAME ? caches.delete(cache) : null)
    )).then(() => self.clients.claim())
  );
});

// ── Background Sync: replay queued feedback when the OS says we're online ──
// The feedback page enqueues failed POSTs in IndexedDB (db `auriga-feedback-queue`,
// store `pending`). On a `sync` event we drain that store the same way the
// page does. Browsers without SyncManager (Safari/iOS) fall back to the
// page's `online` event listener, so feedback still flushes — just only
// while a tab is open.
const QUEUE_DB = 'auriga-feedback-queue';
const QUEUE_STORE = 'pending';
const QUEUE_ENDPOINT = '/.netlify/functions/submit-feedback';

self.addEventListener('sync', (event) => {
  if (event.tag === 'auriga-feedback-flush') {
    event.waitUntil(flushFeedbackQueue());
  }
});

function openQueueDb() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(QUEUE_DB, 1);
    req.onupgradeneeded = () => {
      req.result.createObjectStore(QUEUE_STORE, { keyPath: 'id', autoIncrement: true });
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function flushFeedbackQueue() {
  let db;
  try { db = await openQueueDb(); } catch (e) { return; }
  const items = await new Promise((resolve) => {
    const acc = [];
    const tx = db.transaction(QUEUE_STORE, 'readonly');
    const cur = tx.objectStore(QUEUE_STORE).openCursor();
    cur.onsuccess = () => {
      const c = cur.result;
      if (!c) return resolve(acc);
      acc.push({ id: c.key, payload: c.value.payload });
      c.continue();
    };
    cur.onerror = () => resolve(acc);
  });
  for (const it of items) {
    try {
      const res = await fetch(QUEUE_ENDPOINT, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(it.payload),
      });
      if (res.ok) {
        await new Promise((resolve) => {
          const tx = db.transaction(QUEUE_STORE, 'readwrite');
          tx.objectStore(QUEUE_STORE).delete(it.id);
          tx.oncomplete = resolve;
          tx.onerror = resolve;
        });
      }
    } catch (e) { /* try again next sync */ }
  }
}
