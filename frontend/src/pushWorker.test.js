import fs from 'fs';
import vm from 'vm';
import path from 'path';
let handlers, showNotification, openWindow, existing;
beforeEach(() => {
  handlers = {}; showNotification = jest.fn().mockResolvedValue(); openWindow = jest.fn();
  existing = { url: 'https://timealert.example/schedule', navigate: jest.fn().mockResolvedValue(), focus: jest.fn() };
  const self = { addEventListener: (name, fn) => { handlers[name] = fn; }, location: { origin: 'https://timealert.example' },
    registration: { showNotification }, clients: { matchAll: jest.fn().mockResolvedValue([existing]), openWindow } };
  vm.runInNewContext(fs.readFileSync(path.join(process.cwd(), 'public/timealert-push-sw.js'), 'utf8'), { self, URL });
});
test('foreground and background each use exactly one persistent notification path', async () => {
  for (const id of ['foreground', 'background']) {
    let pending;
    handlers.push({ data: { json: () => ({ id }) }, waitUntil: promise => { pending = promise; } });
    await pending;
  }
  expect(showNotification).toHaveBeenCalledTimes(2);
  expect(showNotification).toHaveBeenCalledWith('TimeAlert test', expect.objectContaining({ data: { url: '/' } }));
  expect(handlers.fetch).toBeUndefined();
});
test('notification click ignores an external payload destination and focuses same-origin app', async () => {
  let pending;
  const close = jest.fn();
  handlers.notificationclick({ notification: { close, data: { url: 'https://evil.example' } }, waitUntil: promise => { pending = promise; } });
  await pending;
  expect(existing.navigate).toHaveBeenCalledWith('https://timealert.example/');
  expect(existing.focus).toHaveBeenCalledTimes(1);
  expect(openWindow).not.toHaveBeenCalled();
});
test('batch reminder payload is shown and its same-origin timetable URL opens on click', async () => {
  let pending; const close=jest.fn();
  handlers.push({ data: { json: () => ({ id: 'batch-event', title: 'TimeAlert reminder', body: 'Midterm (EXAM) at 09:00 in Room 2', url: '/?batchId=12&eventId=77' }) }, waitUntil: promise => { pending=promise; } });
  await pending;
  expect(showNotification).toHaveBeenLastCalledWith('TimeAlert reminder',expect.objectContaining({body:'Midterm (EXAM) at 09:00 in Room 2',data:{url:'/?batchId=12&eventId=77'}}));
  handlers.notificationclick({ notification: { close, data: { url: '/?batchId=12&eventId=77' } }, waitUntil: promise => { pending=promise; } });
  await pending;
  expect(existing.navigate).toHaveBeenCalledWith('https://timealert.example/?batchId=12&eventId=77');
});
