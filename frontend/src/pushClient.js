import axios from 'axios';
const BINDING = 'timealert.push.device';
const SW_PATH = '/timealert-push-sw.js';
let sessionGeneration = 0;
export const pushSession = () => sessionGeneration;
const request = (apiUrl, token, method, path, data) => axios({
  method, url: `${apiUrl}/push${path}`, data, timeout: 4000,
  headers: { Authorization: `Bearer ${token}` },
});
export const supported = () => window.isSecureContext && 'serviceWorker' in navigator &&
  'PushManager' in window && 'Notification' in window && 'ServiceWorkerRegistration' in window &&
  'showNotification' in window.ServiceWorkerRegistration.prototype;
export const homeScreenHint = () => !window.matchMedia?.('(display-mode: standalone)')?.matches && !navigator.standalone
  ? 'On iPhone or iPad, add TimeAlert to your Home Screen and open it there before enabling notifications. HTTPS and device permission are required.'
  : 'Device permission is required. Your browser and operating system control notification delivery.';
function binding() {
  try { return JSON.parse(localStorage.getItem(BINDING) || 'null'); } catch { return null; }
}
function deadline(promise, milliseconds = 5000) {
  let timer;
  return Promise.race([promise, new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error('Notification setup timed out.')), milliseconds);
  })]).finally(() => clearTimeout(timer));
}
async function registration() {
  const worker = await deadline(navigator.serviceWorker.register(SW_PATH, { scope: '/' }));
  if (worker.active) return worker;
  return deadline(new Promise((resolve, reject) => {
    const installing = worker.installing || worker.waiting;
    if (!installing) { reject(new Error('Service worker not ready.')); return; }
    const changed = () => {
      if (installing.state === 'activated') { installing.removeEventListener('statechange', changed); resolve(worker); }
      if (installing.state === 'redundant') { installing.removeEventListener('statechange', changed); reject(new Error('Service worker setup failed.')); }
    };
    installing.addEventListener('statechange', changed);
    changed();
  }));
}
function keyBytes(key) {
  const raw = atob(key.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(key.length / 4) * 4, '='));
  return Uint8Array.from(raw, character => character.charCodeAt(0));
}
export async function inspectPush(apiUrl, token, owner) {
  if (!supported()) return { state: 'unsupported' };
  if (Notification.permission === 'denied') return { state: 'blocked' };
  let config;
  try { config = (await request(apiUrl, token, 'get', '/public-key')).data; }
  catch (error) {
    if (error.response?.status === 503 && error.response?.data?.status === 'setup_pending') return { state: 'setup-pending' };
    throw error;
  }
  if (config.status !== 'ready') return { state: 'setup-pending' };
  const worker = await registration();
  const sub = await worker.pushManager.getSubscription();
  const saved = binding();
  if (sub && saved?.owner === owner &&
      String(new Uint8Array(sub.options.applicationServerKey || [])) === String(keyBytes(config.publicKey))) {
    try {
      await request(apiUrl, token, 'get', `/subscriptions/${saved.deviceId}`);
      return { state: 'enabled', publicKey: config.publicKey };
    } catch (error) { if (![404, 410].includes(error.response?.status)) throw error; }
  }
  if (sub && saved?.owner !== owner) await sub.unsubscribe();
  return { state: 'permission-required', publicKey: config.publicKey };
}
export async function enablePush(apiUrl, token, owner, publicKey, generation = pushSession()) {
  const sessionEnded = () => generation !== sessionGeneration || localStorage.getItem('token') !== token;
  if (sessionEnded()) throw new Error('Session ended.');
  const worker = await registration();
  let sub = await worker.pushManager.getSubscription();
  const saved = binding();
  const key = keyBytes(publicKey);
  if (sub && (saved?.owner !== owner || String(new Uint8Array(sub.options.applicationServerKey || [])) !== String(key))) {
    await sub.unsubscribe(); sub = null;
  }
  const created = !sub;
  sub = sub || await worker.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: key });
  try {
    if (sessionEnded()) { await sub.unsubscribe(); throw new Error('Session ended.'); }
    const response = await request(apiUrl, token, 'post', '/subscriptions', sub.toJSON());
    if (sessionEnded()) {
      await sub.unsubscribe();
      await request(apiUrl, token, 'delete', `/subscriptions/${response.data.deviceId}`).catch(() => {});
      throw new Error('Session ended.');
    }
    localStorage.setItem(BINDING, JSON.stringify({ owner, deviceId: response.data.deviceId }));
  } catch (error) { if (created) await sub.unsubscribe().catch(() => {}); throw error; }
}
export async function disconnectPush(apiUrl, token, owner) {
  sessionGeneration++;
  const saved = binding();
  let serverCleaned = true;
  let localCleaned = true;
  const cleanup = saved?.owner === owner && token
    ? request(apiUrl, token, 'delete', `/subscriptions/${saved.deviceId}`).catch(() => { serverCleaned = false; })
    : Promise.resolve();
  try {
    if ('serviceWorker' in navigator) {
      const worker = await deadline(navigator.serviceWorker.getRegistration(SW_PATH));
      const sub = await deadline(worker?.pushManager.getSubscription() || Promise.resolve(null));
      if (sub) localCleaned = await deadline(sub.unsubscribe());
      const notifications = await deadline(worker?.getNotifications() || Promise.resolve([]));
      notifications.forEach(notification => notification.close());
    }
  } catch { localCleaned = false; }
  await cleanup;
  if (localCleaned) localStorage.removeItem(BINDING);
  return { serverCleaned, localCleaned };
}
export async function testPush(apiUrl, token, owner) {
  const saved = binding();
  if (!saved || saved.owner !== owner) throw new Error('Enable notifications on this device first.');
  return (await request(apiUrl, token, 'post', `/subscriptions/${saved.deviceId}/test`)).data;
}
export function pushError(error) {
  const messages = {
    setup_pending: 'Notifications are awaiting server setup.',
    unavailable: 'Notification service unavailable. If you sent a test, delivery is unconfirmed.',
    expired: 'This subscription expired. Enable notifications again.',
    not_registered: 'This device is not registered. Enable notifications again.',
    rate_limited: 'Too many requests. Try again in a minute.',
    device_conflict_or_limit: 'This device belongs to another account, or your device limit was reached.',
    invalid_subscription: 'This browser push service or subscription is not supported.',
  };
  return messages[error.response?.data?.status] || 'Notification setup failed. Check your connection and try again.';
}
