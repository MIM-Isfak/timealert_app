import axios from 'axios';
import { disconnectPush, enablePush, inspectPush, pushSession, testPush } from './pushClient';
jest.mock('axios', () => jest.fn());
const API = 'http://localhost/api';
const OWNER = 'test@example.com';
const TOKEN = 'test-only-token';
const PUBLIC_KEY = btoa(String.fromCharCode(4) + 'x'.repeat(64)).replace(/=/g, '');
let sub, worker;
beforeEach(() => {
  localStorage.clear(); jest.clearAllMocks();
  localStorage.setItem('token', TOKEN);
  sub = { unsubscribe: jest.fn().mockResolvedValue(true), toJSON: () => ({ endpoint: 'fake-test-endpoint' }),
    options: { applicationServerKey: Uint8Array.from(atob(PUBLIC_KEY), c => c.charCodeAt(0)) } };
  worker = { active: {}, pushManager: { getSubscription: jest.fn().mockResolvedValue(null), subscribe: jest.fn().mockResolvedValue(sub) }, getNotifications: jest.fn().mockResolvedValue([]) };
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  Object.defineProperty(window, 'PushManager', { configurable: true, value: function () {} });
  Object.defineProperty(window, 'ServiceWorkerRegistration', { configurable: true, value: function () {} });
  window.ServiceWorkerRegistration.prototype.showNotification = jest.fn();
  Object.defineProperty(window, 'Notification', { configurable: true, value: { permission: 'granted', requestPermission: jest.fn() } });
  Object.defineProperty(navigator, 'serviceWorker', { configurable: true, value: {
    register: jest.fn().mockResolvedValue(worker), getRegistration: jest.fn().mockResolvedValue(worker),
  } });
});
test('unsupported/blocked/pending capability states do not ask permission', async () => {
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: false });
  expect((await inspectPush(API, TOKEN, OWNER)).state).toBe('unsupported');
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  Notification.permission = 'denied';
  expect((await inspectPush(API, TOKEN, OWNER)).state).toBe('blocked');
  Notification.permission = 'default';
  axios.mockRejectedValue({ response: { status: 503, data: { status: 'setup_pending' } } });
  expect((await inspectPush(API, TOKEN, OWNER)).state).toBe('setup-pending');
  expect(Notification.requestPermission).not.toHaveBeenCalled();
});
test('configured but unavailable service is an error, not setup pending', async () => {
  axios.mockRejectedValue({ response: { status: 503, data: { status: 'unavailable' } } });
  await expect(inspectPush(API, TOKEN, OWNER)).rejects.toEqual(expect.objectContaining({ response: expect.any(Object) }));
});
test('logout while the permission prompt is open cancels the later Enable action', async () => {
  const session = pushSession();
  await disconnectPush(API, TOKEN, OWNER);
  await expect(enablePush(API, TOKEN, OWNER, PUBLIC_KEY, session)).rejects.toThrow('Session ended');
  expect(worker.pushManager.subscribe).not.toHaveBeenCalled();
  expect(axios).not.toHaveBeenCalled();
});
test('logout in another tab prevents stale-token registration', async () => {
  localStorage.removeItem('token');
  await expect(enablePush(API, TOKEN, OWNER, PUBLIC_KEY)).rejects.toThrow('Session ended');
  expect(worker.pushManager.subscribe).not.toHaveBeenCalled();
});
test('logout during registration removes a late server registration and never marks the device enabled', async () => {
  let finishRegistration;
  let started;
  const registered = new Promise(resolve => { started = resolve; });
  axios.mockImplementation(config => {
    if (config.method === 'post') { started(); return new Promise(resolve => { finishRegistration = resolve; }); }
    return Promise.resolve({ data: { status: 'removed' } });
  });
  const enabling = enablePush(API, TOKEN, OWNER, PUBLIC_KEY);
  const cancelled = expect(enabling).rejects.toThrow('Session ended');
  await registered;
  await disconnectPush(API, TOKEN, OWNER);
  finishRegistration({ data: { deviceId: 'late-device' } });
  await cancelled;
  expect(sub.unsubscribe).toHaveBeenCalled();
  expect(axios).toHaveBeenLastCalledWith(expect.objectContaining({ method: 'delete', url: API + '/push/subscriptions/late-device' }));
  expect(localStorage.getItem('timealert.push.device')).toBeNull();
});
test('a rotated application key requires re-enabling, not an inaccurate enabled state', async () => {
  localStorage.setItem('timealert.push.device', JSON.stringify({ owner: OWNER, deviceId: 'device' }));
  worker.pushManager.getSubscription.mockResolvedValue(sub);
  axios.mockResolvedValue({ data: { status: 'ready', publicKey: btoa(String.fromCharCode(4) + 'y'.repeat(64)).replace(/=/g, '') } });
  expect((await inspectPush(API, TOKEN, OWNER)).state).toBe('permission-required');
});
test('registration, test, and removal use this account token and selected server device id', async () => {
  axios.mockResolvedValue({ data: { deviceId: 'server-device' } });
  await enablePush(API, TOKEN, OWNER, PUBLIC_KEY);
  expect(worker.pushManager.subscribe).toHaveBeenCalledWith({ userVisibleOnly: true, applicationServerKey: expect.any(Uint8Array) });
  await testPush(API, TOKEN, OWNER);
  expect(axios).toHaveBeenLastCalledWith(expect.objectContaining({ method: 'post', url: API + '/push/subscriptions/server-device/test', headers: { Authorization: 'Bearer ' + TOKEN } }));
  worker.pushManager.getSubscription.mockResolvedValue(sub);
  expect(await disconnectPush(API, TOKEN, OWNER)).toEqual({ localCleaned: true, serverCleaned: true });
  expect(sub.unsubscribe).toHaveBeenCalled();
  expect(localStorage.getItem('timealert.push.device')).toBeNull();
});
test('server failure still unsubscribes locally on logout', async () => {
  localStorage.setItem('timealert.push.device', JSON.stringify({ owner: OWNER, deviceId: 'device' }));
  worker.pushManager.getSubscription.mockResolvedValue(sub);
  axios.mockRejectedValue(new Error('unavailable'));
  expect(await disconnectPush(API, TOKEN, OWNER)).toEqual({ localCleaned: true, serverCleaned: false });
  expect(sub.unsubscribe).toHaveBeenCalled();
});
test('failed registration rolls back a new local subscription', async () => {
  axios.mockRejectedValue(new Error('unavailable'));
  await expect(enablePush(API, TOKEN, OWNER, PUBLIC_KEY)).rejects.toThrow();
  expect(sub.unsubscribe).toHaveBeenCalled();
  expect(localStorage.getItem('timealert.push.device')).toBeNull();
});
test('another account cannot select the saved device for test sends', async () => {
  localStorage.setItem('timealert.push.device', JSON.stringify({ owner: 'other@example.com', deviceId: 'device' }));
  await expect(testPush(API, TOKEN, OWNER)).rejects.toThrow();
  expect(axios).not.toHaveBeenCalled();
});
