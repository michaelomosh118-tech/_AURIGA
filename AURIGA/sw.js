const CACHE_NAME = 'drakosanctis-v1';
const ASSETS_TO_CACHE = [
  './index.html',
  './logo.png',
  './manifest.json'
];

// Install Event: Cache critical files
self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => {
      console.log('Caching essential app shell');
      return cache.addAll(ASSETS_TO_CACHE);
    })
  );
});

// Fetch Event: Serve from Cache or Network (Network First strategy for complex apps)
self.addEventListener('fetch', (event) => {
  event.respondWith(
    fetch(event.request).catch(() => {
      return caches.match(event.request);
    })
  );
});

// Activate Event: Clean up old caches
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((cacheNames) => {
      return Promise.all(
        cacheNames.map((cache) => {
          if (cache !== CACHE_NAME) {
            console.log('Clearing old cache');
            return caches.delete(cache);
          }
        })
      );
    })
  );
});