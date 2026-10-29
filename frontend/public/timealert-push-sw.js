/* Visible Web Push only. No fetch handler, authenticated API cache, or JWT storage. */
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', event => event.waitUntil(self.clients.claim()));
self.addEventListener('push', event => {
  let payload = {};
  try { payload = event.data?.json() || {}; } catch { /* Still show a visible notification. */ }
  // One display path for foreground and background; the page shows no second alert.
  event.waitUntil(self.registration.showNotification('TimeAlert test', {
    body: 'Notifications are enabled on this device.',
    tag: typeof payload.id === 'string' ? `timealert-${payload.id.slice(0, 64)}` : 'timealert-test', data: { url: '/' },
  }));
});
self.addEventListener('notificationclick', event => {
  event.notification.close();
  event.waitUntil((async () => {
    const destination = new URL('/', self.location.origin).href;
    const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    const existing = windows.find(client => new URL(client.url).origin === self.location.origin);
    if (existing) { await existing.navigate(destination); return existing.focus(); }
    return self.clients.openWindow(destination);
  })());
});
