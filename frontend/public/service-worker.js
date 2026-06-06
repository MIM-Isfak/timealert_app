self.addEventListener('push', function(event) {
  const message = event.data ? event.data.text() : 'New notification!';
  
  const options = {
    body: message,
    icon: '/logo192.png',
    badge: '/logo192.png',
    vibrate: [200, 100, 200],
    requireInteraction: true
  };

  event.waitUntil(
    self.registration.showNotification('⏰ TimeAlert', options)
  );
});

self.addEventListener('notificationclick', function(event) {
  event.notification.close();
  event.waitUntil(
    clients.openWindow('http://localhost:3000')
  );
});