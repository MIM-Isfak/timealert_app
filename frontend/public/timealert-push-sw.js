/* Visible Web Push only. No fetch handler, authenticated API cache, or JWT storage. */
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', event => event.waitUntil(self.clients.claim()));
self.addEventListener('push', event => {
  let payload = {};
  try { payload = event.data?.json() || {}; } catch { /* Still show a visible notification. */ }
  const title = typeof payload.title === 'string' && payload.title.length <= 120 ? payload.title : 'TimeAlert test';
  const body = typeof payload.body === 'string' && payload.body.length <= 500 ? payload.body : 'Notifications are enabled on this device.';
  let url = '/';
  try { const candidate = new URL(payload.url || '/', self.location.origin); if (candidate.origin === self.location.origin) url = candidate.pathname + candidate.search + candidate.hash; } catch { /* Keep the same-origin home destination. */ }
  // One display path for foreground and background; the page shows no second alert.
  event.waitUntil(self.registration.showNotification(title, {
    body,
    tag: typeof payload.id === 'string' ? `timealert-${payload.id.slice(0, 64)}` : 'timealert-test', data: { url },
  }));
});
self.addEventListener('notificationclick', event => {
  event.notification.close();
  event.waitUntil((async () => {
    let destination = new URL('/', self.location.origin).href;
    try { const candidate = new URL(event.notification.data?.url || '/', self.location.origin); if (candidate.origin === self.location.origin) destination = candidate.href; } catch { /* Keep home. */ }
    const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    const existing = windows.find(client => new URL(client.url).origin === self.location.origin);
    if (existing) { await existing.navigate(destination); return existing.focus(); }
    return self.clients.openWindow(destination);
  })());
});
