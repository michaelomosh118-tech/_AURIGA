// Bump the cache name when the asset list or shell HTML changes so old
// clients pick up the new bundle on their next page load.
const CACHE_NAME = 'drakosanctis-v3';

// Local app-shell pages we always want available offline. The fetch
// handler also opportunistically caches every other GET response so
// that the heavy ML CDN scripts (TF.js + COCO-SSD + Tesseract.js) are
// available on the second visit even without a network.
const ASSETS_TO_CACHE = [
  './index.html',
  './reader.html',
  './locator.html',
  './calibration-library.html',
  './feedback.html',
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
