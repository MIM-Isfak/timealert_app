import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync, randomBytes } from 'node:crypto';
import { build } from 'esbuild';
import { Miniflare, createFetchMock } from 'miniflare';
import { validEndpoint } from '../src/validation.js';
import { mkdirSync, mkdtempSync, rmSync } from 'node:fs';
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
      VAPID_PRIVATE_KEY: vapid.privateKey, VAPID_SUBJECT: 'mailto:test@example.com', TEST_MODE: 'true' }, fetchMock: mock,
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
async function replaceEventReminder({ batchId='8001', eventId, version=1, userId='8002', startAt, type='CLASS', reminderMinutes=[5] }) {
  const response=await call('/internal/reminders/replace',{batchId,eventId,version,event:{title:`Event ${eventId}`,type,startAt,timeLabel:'2026-10-30 09:00 (Asia/Colombo)',location:'Room 7',url:`/?batchId=${batchId}&eventId=${eventId}`},recipients:[{userId,reminderMinutes}]});
  assert.equal(response.status,200); return response.json();
}
async function alarm(now) { return call('/internal/reminders/_alarm',{now}); }
async function summary(batchId) { return (await call(`/internal/reminders/summary?batchId=${batchId}`,null,'GET')).json(); }
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
  } finally { await runtime.dispose(); rmSync(persist, { recursive: true, force: true }); }
});
test('a subscription past its browser expiration time is removed without a push request', async () => {
  const subscription = { ...sub('time-expired'), expirationTime: Date.now() + 1000 };
  const id = await register('5002', subscription);
  await new Promise(resolve => setTimeout(resolve, 1100));
  assert.equal((await call('/internal/test', { userId: '5002', deviceId: id })).status, 410);
  assert.equal((await call('/internal/device', { userId: '5002', deviceId: id })).status, 404);
});

test('alarm orders jobs across UTC midnight and persists the next alarm time', async () => {
  const userId='8100', batchId='8101', deviceId=await register(userId,sub('midnight'));
  mock.get('https://fcm.googleapis.com').intercept({path:'/wp/midnight',method:'POST'}).reply(201,'').times(2);
  const midnight=Math.ceil(Date.now()/86_400_000)*86_400_000;
  const first=midnight-30_000, second=midnight+30_000;
  await replaceEventReminder({batchId,eventId:'8102',userId,startAt:first+300_000});
  await replaceEventReminder({batchId,eventId:'8103',userId,startAt:second+300_000});
  assert.equal((await summary(batchId)).nextAlarmAt,first);
  await alarm(first);
  assert.equal((await summary(batchId)).nextAlarmAt,second);
  await alarm(second);
  const counts=(await summary(batchId)).counts;
  assert.equal(counts.DELIVERED,2); assert.equal(counts.PENDING,0);
  assert.equal((await call('/internal/device',{userId,deviceId})).status,200);
});

test('duplicate alarm execution does not resend an accepted job', async () => {
  const userId='8200', batchId='8201'; await register(userId,sub('once'));
  mock.get('https://fcm.googleapis.com').intercept({path:'/wp/once',method:'POST'}).reply(201,'').times(1);
  const due=Date.now()+60_000; await replaceEventReminder({batchId,eventId:'8202',userId,startAt:due+300_000});
  await alarm(due); await alarm(due+1);
  assert.equal((await summary(batchId)).counts.DELIVERED,1);
});

test('temporary provider failures retry with bounded exponential backoff', async () => {
  const userId='8300', batchId='8301'; await register(userId,sub('retry'));
  mock.get('https://fcm.googleapis.com').intercept({path:'/wp/retry',method:'POST'}).reply(503,'').times(4);
  mock.get('https://fcm.googleapis.com').intercept({path:'/wp/retry',method:'POST'}).reply(201,'').times(1);
  const due=Date.now()+120_000; await replaceEventReminder({batchId,eventId:'8302',userId,startAt:due+300_000});
  await alarm(due); const waiting=await summary(batchId);
  assert.equal(waiting.counts.RETRY,1); assert.equal(waiting.nextAlarmAt,due+60_000);
  await alarm(due+60_000); assert.equal((await summary(batchId)).nextAlarmAt,due+180_000);
  await alarm(due+180_000); assert.equal((await summary(batchId)).nextAlarmAt,due+420_000);
  await alarm(due+420_000); assert.equal((await summary(batchId)).nextAlarmAt,due+900_000);
  await alarm(due+900_000); assert.equal((await summary(batchId)).counts.DELIVERED,1);
});

test('retry exhaustion records FAILED after the bounded attempt count', async () => {
  const userId='8350',batchId='8351';await register(userId,sub('exhausted'));
  mock.get('https://fcm.googleapis.com').intercept({path:'/wp/exhausted',method:'POST'}).reply(503,'').times(5);
  const due=Date.now()+150_000;await replaceEventReminder({batchId,eventId:'8352',userId,startAt:due+300_000});
  for(const offset of [0,60_000,180_000,420_000,900_000])await alarm(due+offset);
  assert.equal((await summary(batchId)).counts.FAILED,1);
});

test('permanent provider failures become FAILED and never count as accepted', async () => {
  const userId='8400', batchId='8401'; await register(userId,sub('permanent'));
  mock.get('https://fcm.googleapis.com').intercept({path:'/wp/permanent',method:'POST'}).reply(403,'');
  const due=Date.now()+180_000; await replaceEventReminder({batchId,eventId:'8402',userId,startAt:due+300_000});
  await alarm(due); const counts=(await summary(batchId)).counts;
  assert.equal(counts.FAILED,1); assert.equal(counts.DELIVERED,0);
});

for (const status of [404,410]) test(`reminder provider ${status} expires the subscription and fails its job`, async () => {
  const userId=String(8500+status), batchId=String(8600+status); const deviceId=await register(userId,sub('reminder-expired'+status));
  mock.get('https://fcm.googleapis.com').intercept({path:'/wp/reminder-expired'+status,method:'POST'}).reply(status,'');
  const due=Date.now()+240_000; await replaceEventReminder({batchId,eventId:String(8700+status),userId,startAt:due+300_000});
  await alarm(due); assert.equal((await summary(batchId)).counts.FAILED,1);
  assert.equal((await call('/internal/device',{userId,deviceId})).status,404);
});

test('event replacement cancels old-version jobs and schedules only the new version', async () => {
  const userId='8800', batchId='8801'; await register(userId,sub('version'));
  const startAt=Date.now()+3_600_000; await replaceEventReminder({batchId,eventId:'8802',userId,startAt,version:1});
  await replaceEventReminder({batchId,eventId:'8802',userId,startAt:startAt+600_000,version:2});
  const counts=(await summary(batchId)).counts; assert.equal(counts.CANCELLED,1); assert.equal(counts.PENDING,1);
});

test('member preference sync replaces that member batch job set idempotently', async () => {
  const userId='8850',batchId='8851',eventId='8852',due=Date.now()+3_600_000;
  const makeJob=minutes=>{const id=`event:${eventId}:v1:user:${userId}:before:${minutes}`;return {id,batchId,eventId,version:1,userId,kind:'REMINDER',scheduledAt:due,payload:{id,title:'TimeAlert reminder',body:`Exam starts in ${minutes} minutes`,url:`/?batchId=${batchId}&eventId=${eventId}`}};};
  const path='/internal/reminders/sync';
  assert.equal((await call(path,{userId,batchId,jobs:[makeJob(5),makeJob(10)]})).status,200);
  assert.equal((await call(path,{userId,batchId,jobs:[makeJob(10)]})).status,200);
  const counts=(await summary(batchId)).counts;assert.equal(counts.CANCELLED,1);assert.equal(counts.PENDING,1);
});

test('cancellation removes future reminders and schedules one immediate update push', async () => {
  const userId='8900', batchId='8901'; await register(userId,sub('cancel'));
  const startAt=Date.now()+3_600_000; await replaceEventReminder({batchId,eventId:'8902',userId,startAt,version:1});
  mock.get('https://fcm.googleapis.com').intercept({path:'/wp/cancel',method:'POST'}).reply(201,'');
  const event={title:'Event 8902',type:'EXAM',startAt,timeLabel:'2026-10-30 09:00 (Asia/Colombo)',location:'Room 7',url:`/?batchId=${batchId}&eventId=8902`};
  const cancelled=await call('/internal/reminders/cancel',{batchId,eventId:'8902',version:2,event,recipients:[userId],reason:'Exam moved'});
  assert.equal(cancelled.status,200);
  await new Promise(resolve => setTimeout(resolve, 100)); mock.assertNoPendingInterceptors(); const counts=(await summary(batchId)).counts;
  const report=await summary(batchId); assert.equal(counts.CANCELLED,1,JSON.stringify(report)); assert.equal(counts.DELIVERED,1,JSON.stringify(report));
});

test('paused test environment retains jobs without arming or executing delivery alarms', async () => {
  const runtime = new Miniflare({ ...options, bindings: { ...options.bindings, REMINDER_DELIVERY_ENABLED: 'false' } });
  const invoke = (path, body, method='POST') => runtime.dispatchFetch('http://worker/internal/reminders/' + path, {
    method, headers: { Authorization: `Bearer ${secret}`, 'Content-Type': 'application/json' },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  try {
    const id='event:9902:v1:user:9900:before:5';
    const job={id,batchId:'9901',eventId:'9902',userId:'9900',version:1,kind:'REMINDER',scheduledAt:Date.now()-1000,payload:{id,title:'Paused',body:'No outgoing push',url:'/'}};
    assert.equal((await invoke('schedule',job)).status,200);
    await invoke('_alarm',{now:Date.now()+60_000});
    const report=await (await invoke('summary?batchId=9901',null,'GET')).json();
    assert.equal(report.counts.PENDING,1);assert.equal(report.counts.FAILED,0);assert.equal(report.nextAlarmAt,null);
  } finally { await runtime.dispose(); }
});
