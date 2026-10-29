import { DurableObject } from 'cloudflare:workers';
import { buildPushPayload } from '@block65/webcrypto-web-push';
import { RequestError, decodeKey, readJson, requireFields, validateSubscription } from './validation.js';

const json = (body, status = 200) => Response.json(body, {
  status, headers: { 'Cache-Control': 'no-store' },
});

function configured(env) {
  return env.INTERNAL_API_SECRET?.length >= 32 && !env.INTERNAL_API_SECRET.startsWith('REPLACE_') &&
    decodeKey(env.VAPID_PUBLIC_KEY, 65) && decodeKey(env.VAPID_PRIVATE_KEY, 32) &&
    /^mailto:[^\s@]+@[^\s@]+$/.test(env.VAPID_SUBJECT || '');
}

async function authorized(request, env) {
  // Fixed-size digest comparison avoids comparing the secret one character at a time.
  const digest = async value => new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value)));
  const [actual, expected] = await Promise.all([
    digest(request.headers.get('Authorization') || ''), digest(`Bearer ${env.INTERNAL_API_SECRET}`),
  ]);
  let diff = 0;
  for (let i = 0; i < expected.length; i++) diff |= actual[i] ^ expected[i];
  return diff === 0;
}

export default {
  async fetch(request, env) {
    try {
      const path = new URL(request.url).pathname;
      if (request.method === 'GET' && path === '/health') return json({ status: 'UP' });
      if (!path.startsWith('/internal/')) return json({ status: 'not_found' }, 404);
      if (!configured(env)) return json({ status: 'setup_pending' }, 503);
      if (!await authorized(request, env)) return json({ status: 'unauthorized' }, 401);
      if (request.method === 'GET' && path === '/internal/public-key') {
        return json({ status: 'ready', publicKey: env.VAPID_PUBLIC_KEY });
      }
      if (path !== '/internal/subscriptions' && path !== '/internal/test' && path !== '/internal/device') {
        return json({ status: 'not_found' }, 404);
      }
      const body = await readJson(request);
      requireFields(body, path === '/internal/subscriptions' ? ['userId', 'subscription'] : ['userId', 'deviceId']);
      if (typeof body.userId !== 'string' || !/^[1-9][0-9]{0,18}$/.test(body.userId)) throw new RequestError(400, 'invalid_user');
      if (path === '/internal/subscriptions') {
        if (request.method !== 'POST') throw new RequestError(405, 'method_not_allowed');
        body.subscription = validateSubscription(body.subscription);
      } else {
        if (!/^[0-9a-f-]{36}$/.test(body.deviceId || '')) throw new RequestError(400, 'invalid_device');
        if (!(path === '/internal/test' ? request.method === 'POST' : ['POST', 'DELETE'].includes(request.method))) {
          throw new RequestError(405, 'method_not_allowed');
        }
      }
      // One store enforces unique endpoint ownership across accounts. No browser can choose an object.
      const store = env.PUSH_STORE.get(env.PUSH_STORE.idFromName('subscriptions-v1'));
      return await store.fetch(new Request('https://store' + path, {
        method: request.method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
      }));
    } catch (error) {
      return json({ status: error instanceof RequestError ? error.code : 'unavailable' },
        error instanceof RequestError ? error.status : 503);
    }
  },
};

export class PushStore extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    this.sql.exec(`CREATE TABLE IF NOT EXISTS devices (
      id TEXT PRIMARY KEY, user_id TEXT NOT NULL, endpoint TEXT NOT NULL UNIQUE,
      subscription TEXT NOT NULL
    ); CREATE INDEX IF NOT EXISTS devices_user ON devices(user_id);
    CREATE TABLE IF NOT EXISTS test_limits (user_id TEXT PRIMARY KEY, window INTEGER NOT NULL, count INTEGER NOT NULL);`);
  }

  async fetch(request) {
    const body = await request.json();
    const { userId, deviceId } = body;
    const path = new URL(request.url).pathname;
    if (path === '/internal/subscriptions') {
      const sub = body.subscription;
      const existing = this.sql.exec('SELECT id, user_id FROM devices WHERE endpoint = ?', sub.endpoint).toArray()[0];
      if (existing && existing.user_id !== userId) return json({ status: 'device_conflict' }, 409);
      if (!existing && this.sql.exec('SELECT COUNT(*) AS count FROM devices WHERE user_id = ?', userId).one().count >= 10) {
        return json({ status: 'device_limit' }, 409);
      }
      const id = existing?.id || crypto.randomUUID();
      this.sql.exec(`INSERT INTO devices(id, user_id, endpoint, subscription) VALUES (?, ?, ?, ?)
        ON CONFLICT(endpoint) DO UPDATE SET subscription = excluded.subscription`, id, userId, sub.endpoint, JSON.stringify(sub));
      return json({ status: 'registered', deviceId: id });
    }
    const device = this.sql.exec('SELECT subscription FROM devices WHERE id = ? AND user_id = ?', deviceId, userId).toArray()[0];
    if (path === '/internal/device' && request.method === 'DELETE') {
      this.sql.exec('DELETE FROM devices WHERE id = ? AND user_id = ?', deviceId, userId);
      return json({ status: 'removed' }); // Idempotent, including unknown/other-owner IDs.
    }
    if (!device) return json({ status: 'not_registered' }, 404);
    if (path === '/internal/device') return json({ status: 'registered', deviceId });
    const now = Date.now();
    const limit = this.sql.exec('SELECT window, count FROM test_limits WHERE user_id = ?', userId).toArray()[0];
    if (limit && now - limit.window < 60000 && limit.count >= 3) return json({ status: 'rate_limited' }, 429);
    this.sql.exec(`INSERT INTO test_limits(user_id, window, count) VALUES (?, ?, 1)
      ON CONFLICT(user_id) DO UPDATE SET window = excluded.window, count = ?`,
      userId, limit && now - limit.window < 60000 ? limit.window : now,
      limit && now - limit.window < 60000 ? limit.count + 1 : 1);
    const stored = JSON.parse(device.subscription);
    if (stored.expirationTime != null && stored.expirationTime <= now) {
      this.sql.exec('DELETE FROM devices WHERE id = ? AND user_id = ?', deviceId, userId);
      return json({ status: 'expired' }, 410);
    }
    const subscription = validateSubscription(stored);
    try {
      const payload = await buildPushPayload({ data: JSON.stringify({
        title: 'TimeAlert test', body: 'Notifications are enabled on this device.', url: '/', id: crypto.randomUUID(),
      }), options: { ttl: 60 } }, subscription, {
        subject: this.env.VAPID_SUBJECT, publicKey: this.env.VAPID_PUBLIC_KEY, privateKey: this.env.VAPID_PRIVATE_KEY,
      });
      // Workers supports manual/follow, not redirect:error. Manual keeps vendor redirects from becoming an SSRF hop.
      const response = await fetch(subscription.endpoint, { ...payload, redirect: 'manual', signal: AbortSignal.timeout(5000) });
      if ([404, 410].includes(response.status)) {
        this.sql.exec('DELETE FROM devices WHERE id = ? AND user_id = ?', deviceId, userId);
        return json({ status: 'expired' }, 410);
      }
      if (!response.ok) return json({ status: 'push_service_error' }, response.status >= 500 || response.status === 429 ? 503 : 502);
      return json({ status: 'accepted', delivery: 'unconfirmed' }, 202);
    } catch {
      // Network timeout may have happened after acceptance. Do not claim delivery or retry automatically.
      return json({ status: 'unavailable', delivery: 'unknown' }, 503);
    }
  }
}
