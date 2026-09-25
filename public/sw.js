const CACHE = 'xianzhun-v5'
const scopedUrl = (path = '') => new URL(path, self.registration.scope).href
const CORE = [
  scopedUrl(),
  scopedUrl('manifest.webmanifest'),
  scopedUrl('favicon.svg'),
  scopedUrl('icons/icon-192.png'),
  scopedUrl('icons/icon-512.png'),
  scopedUrl('audio/guitar-acoustic/D2.mp3'),
  scopedUrl('audio/guitar-acoustic/E2.mp3'),
  scopedUrl('audio/guitar-acoustic/G2.mp3'),
  scopedUrl('audio/guitar-acoustic/A2.mp3'),
  scopedUrl('audio/guitar-acoustic/C3.mp3'),
  scopedUrl('audio/guitar-acoustic/D3.mp3'),
  scopedUrl('audio/guitar-acoustic/F3.mp3'),
  scopedUrl('audio/guitar-acoustic/G3.mp3'),
  scopedUrl('audio/guitar-acoustic/A3.mp3'),
  scopedUrl('audio/guitar-acoustic/B3.mp3'),
  scopedUrl('audio/guitar-acoustic/D4.mp3'),
  scopedUrl('audio/guitar-acoustic/E4.mp3'),
  scopedUrl('audio/guitar-acoustic/A4.mp3'),
  scopedUrl('audio/guitar-acoustic/D5.mp3'),
]

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(CORE)))
  self.skipWaiting()
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key))))
      .then(() => self.clients.claim()),
  )
})

self.addEventListener('fetch', (event) => {
  if (event.request.method !== 'GET') return
  event.respondWith(
    fetch(event.request)
      .then((response) => {
        if (response.ok && new URL(event.request.url).origin === self.location.origin) {
          const copy = response.clone()
          caches.open(CACHE).then((cache) => cache.put(event.request, copy))
        }
        return response
      })
      .catch(() => caches.match(event.request).then((cached) => cached || caches.match(scopedUrl()))),
  )
})
