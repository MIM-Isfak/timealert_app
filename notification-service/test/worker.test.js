import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync, randomBytes } from 'node:crypto';
import { build } from 'esbuild';
import { Miniflare, createFetchMock } from 'miniflare';
import { validEndpoint } from '../src/validation.js';
import { mkdirSync, mkdtempSync } from 'node:fs';
import { resolve } from 'node:path';

// In-memory test credentials and fake subscriptions only. No real push recipients.
function keys() {
  const { privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const jwk = privateKey.export({ format: 'jwk' });
  return { publicKey: Buffer.concat([Buffer.from([4]), Buffer.from(jwk.x, 'base64url'),
    Buffer.from(jwk.y, 'base64url')]).toString('base64url'), privateKey: jwk.d };
}
const vapid = keys();
const secret = randomBytes(32).toString('hex');
const mock = createFetchMock();
mock.disableNetConnect();
let mf;
let options;
before(async () => {
  const bundle = await build({ entryPoints: [resolve('src/index.js')], bundle: true, write: false,
    format: 'esm', external: ['cloudflare:workers'] });
  options = { modules: true, script: bundle.outputFiles[0].text,
    compatibilityDate: '2026-07-30',
    durableObjects: { PUSH_STORE: { className: 'PushStore', useSQLite: true } },
    bindings: { INTERNAL_API_SECRET: secret, VAPID_PUBLIC_KEY: vapid.publicKey,
      VAPID_PRIVATE_KEY: vapid.privateKey, VAPID_SUBJECT: 'mailto:test@example.com' }, fetchMock: mock,
  };
  mf = new Miniflare(options);
});
after(async () => { await mf?.dispose(); await mock.close(); });
function call(path, body, method = 'POST', auth = secret) {
  return mf.dispatchFetch('http://worker' + path, { method,
    headers: { Authorization: `Bearer ${auth}`, 'Content-Type': 'application/json' },
    ...(body && method !== 'GET' ? { body: JSON.stringify(body) } : {}),
  });
}
function sub(name) {
  return { endpoint: 'https://fcm.googleapis.com/wp/' + name, expirationTime: null,
    keys: { p256dh: keys().publicKey, auth: randomBytes(16).toString('base64url') } };
}
async function register(userId, subscription) {
  const response = await call('/internal/subscriptions', { userId, subscription });
  assert.equal(response.status, 200);
  return (await response.json()).deviceId;
}

test('health exposes no secrets; all internal operations require server authentication', async () => {
  assert.deepEqual(await (await call('/health', null, 'GET', '')).json(), { status: 'UP' });
  for (const path of ['/internal/public-key', '/internal/subscriptions', '/internal/device', '/internal/test']) {
    assert.equal((await call(path, {}, path.endsWith('public-key') ? 'GET' : 'POST', 'wrong')).status, 401);
  }
  const config = await (await call('/internal/public-key', null, 'GET')).json();
  assert.deepEqual(config, { status: 'ready', publicKey: vapid.publicKey });
});
test('allowlist rejects arbitrary URLs, credentials, query strings, redirects targets and lookalike domains', () => {
  for (const url of ['http://localhost/x', 'https://127.0.0.1/x', 'https://fcm.googleapis.com.evil.test/wp/a',
    'https://fcm.googleapis.com:444/wp/a', 'https://u:p@fcm.googleapis.com/wp/a',
    'https://fcm.googleapis.com/wp/a?url=http://localhost', 'https://evil.test/x', 'https://fcm.googleapis.com/v1/projects/a']) {
    assert.equal(validEndpoint(url), false, url);
  }
  for (const url of ['https://fcm.googleapis.com/fcm/send/opaque', 'https://fcm.googleapis.com/wp/opaque',
    'https://updates.push.services.mozilla.com/wpush/v2/opaque', 'https://web.push.apple.com/opaque']) {
    assert.equal(validEndpoint(url), true, url);
  }
});
test('registration is idempotent and another user cannot steal, inspect, send to or delete a device', async () => {
  const subscription = sub('ownership');
  const id = await register('1', subscription);
  assert.equal(await register('1', subscription), id);
  assert.equal((await call('/internal/subscriptions', { userId: '2', subscription })).status, 409);
  assert.equal((await call('/internal/device', { userId: '2', deviceId: id })).status, 404);
  assert.equal((await call('/internal/test', { userId: '2', deviceId: id })).status, 404);
  assert.equal((await call('/internal/device', { userId: '2', deviceId: id }, 'DELETE')).status, 200);
  assert.equal((await call('/internal/device', { userId: '1', deviceId: id })).status, 200);
  for (let i = 0; i < 2; i++) assert.equal((await call('/internal/device', { userId: '1', deviceId: id }, 'DELETE')).status, 200);
  assert.equal((await call('/internal/test', { userId: '1', deviceId: id })).status, 404);
});
test('malformed and oversized requests cannot register arbitrary endpoints or messages', async () => {
  for (const subscription of [{ ...sub('bad'), endpoint: 'https://example.com/relay' },
    { ...sub('bad'), keys: { p256dh: 'bad', auth: 'bad' } }]) {
    assert.equal((await call('/internal/subscriptions', { userId: '1', subscription })).status, 400);
  }
  assert.equal((await call('/internal/test', { userId: '1', deviceId: crypto.randomUUID(), message: 'arbitrary' })).status, 400);
  assert.equal((await call('/internal/test', { userId: '1', deviceId: 'x'.repeat(9000) })).status, 413);
});
test('real library encrypts in Workers; push acceptance is not confirmed delivery and tests are limited', async () => {
  const id = await register('3', sub('accepted'));
  mock.get('https://fcm.googleapis.com').intercept({ path: '/wp/accepted', method: 'POST',
    headers: { 'content-encoding': 'aes128gcm' } }).reply(201, '').times(3);
  for (let i = 0; i < 3; i++) {
    const response = await call('/internal/test', { userId: '3', deviceId: id });
    assert.equal(response.status, 202);
    assert.deepEqual(await response.json(), { status: 'accepted', delivery: 'unconfirmed' });
  }
  assert.equal((await call('/internal/test', { userId: '3', deviceId: id })).status, 429);
});
for (const status of [404, 410]) test(`${status} from push service deletes the expired device`, async () => {
  const id = await register(String(status), sub('expired' + status));
  mock.get('https://fcm.googleapis.com').intercept({ path: '/wp/expired' + status, method: 'POST' }).reply(status, '');
  assert.equal((await call('/internal/test', { userId: String(status), deviceId: id })).status, 410);
  assert.equal((await call('/internal/device', { userId: String(status), deviceId: id })).status, 404);
});
for (const status of [403, 500, 302]) test(`push service ${status} is not reported as acceptance`, async () => {
  const id = await register(String(status), sub('failed' + status));
  mock.get('https://fcm.googleapis.com').intercept({ path: '/wp/failed' + status, method: 'POST' })
    .reply(status, '', { headers: status === 302 ? { location: 'http://localhost/relay' } : {} });
  assert.ok([502, 503].includes((await call('/internal/test', { userId: String(status), deviceId: id })).status));
  assert.equal((await call('/internal/device', { userId: String(status), deviceId: id })).status, 200);
});
test('network failure retains registration and reports uncertain delivery', async () => {
  const id = await register('9', sub('network'));
  // No mock matches: network access is disabled, so no real request can escape.
  const response = await call('/internal/test', { userId: '9', deviceId: id });
  assert.equal(response.status, 503);
  // Miniflare may translate a rejected outbound fetch into HTTP 500 at the service boundary.
  assert.ok(['unavailable', 'push_service_error'].includes((await response.json()).status));
  assert.equal((await call('/internal/device', { userId: '9', deviceId: id })).status, 200);
});
test('SQLite device ownership survives a local runtime restart', async () => {
  mkdirSync('.wrangler', { recursive: true });
  const persist = mkdtempSync(resolve('.wrangler/test-persistence-'));
  const savedOptions = { ...options, durableObjectsPersist: persist };
  let runtime = new Miniflare(savedOptions);
  const invoke = (path, body) => runtime.dispatchFetch('http://worker/internal/' + path, {
    method: 'POST', headers: { Authorization: `Bearer ${secret}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  try {
    const registered = await invoke('subscriptions', { userId: '5000', subscription: sub('persisted') });
    assert.equal(registered.status, 200);
    const { deviceId } = await registered.json();
    await runtime.dispose();
    runtime = new Miniflare(savedOptions);
    assert.equal((await invoke('device', { userId: '5000', deviceId })).status, 200);
    assert.equal((await invoke('device', { userId: '5001', deviceId })).status, 404);
  } finally { await runtime.dispose(); }
});
test('a subscription past its browser expiration time is removed without a push request', async () => {
  const subscription = { ...sub('time-expired'), expirationTime: Date.now() + 1000 };
  const id = await register('5002', subscription);
  await new Promise(resolve => setTimeout(resolve, 1100));
  assert.equal((await call('/internal/test', { userId: '5002', deviceId: id })).status, 410);
  assert.equal((await call('/internal/device', { userId: '5002', deviceId: id })).status, 404);
});
