import { DurableObject } from 'cloudflare:workers';
import { buildPushPayload } from '@block65/webcrypto-web-push';
import { RequestError, decodeKey, readJson, requireFields, validateSubscription } from './validation.js';

const json = (body, status = 200) => Response.json(body, { status, headers: { 'Cache-Control': 'no-store' } });
const TEMP_RETRIES = 5;
const BASE_RETRY_MS = 60_000;
const MAX_RETRY_MS = 60 * 60_000;
const TYPES = new Set(['CLASS', 'EXAM', 'DEADLINE', 'OTHER']);
const SAFE_URL = value => typeof value === 'string' && value.startsWith('/') && !value.startsWith('//') && value.length <= 256;

function configured(env) {
  return env.INTERNAL_API_SECRET?.length >= 32 && !env.INTERNAL_API_SECRET.startsWith('REPLACE_') &&
    decodeKey(env.VAPID_PUBLIC_KEY, 65) && decodeKey(env.VAPID_PRIVATE_KEY, 32) && /^mailto:[^\s@]+@[^\s@]+$/.test(env.VAPID_SUBJECT || '');
}
async function authorized(request, env) {
  const digest = async value => new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value)));
  const [actual, expected] = await Promise.all([digest(request.headers.get('Authorization') || ''), digest(`Bearer ${env.INTERNAL_API_SECRET}`)]);
  let diff = 0; for (let i = 0; i < expected.length; i++) diff |= actual[i] ^ expected[i]; return diff === 0;
}
function userId(value) { if (typeof value !== 'string' || !/^[1-9][0-9]{0,18}$/.test(value)) throw new RequestError(400, 'invalid_user'); return value; }
function idValue(value, name) { if (typeof value !== 'string' || !/^[1-9][0-9]{0,18}$/.test(value)) throw new RequestError(400, `invalid_${name}`); return value; }
function validateEvent(event) {
  requireFields(event, ['title', 'type', 'startAt', 'timeLabel', 'location', 'url']);
  if (typeof event.title !== 'string' || !event.title.trim() || event.title.length > 180 || !TYPES.has(event.type) ||
      !Number.isSafeInteger(event.startAt) || event.startAt <= 0 || typeof event.timeLabel !== 'string' || event.timeLabel.length > 80 ||
      (event.location != null && (typeof event.location !== 'string' || event.location.length > 180)) || !SAFE_URL(event.url)) {
    throw new RequestError(400, 'invalid_event');
  }
  return { title: event.title.trim(), type: event.type, startAt: event.startAt, timeLabel: event.timeLabel, location: event.location || '', url: event.url };
}
function validateMinutes(value, type) {
  if (!Array.isArray(value) || value.some(n => !Number.isInteger(n) || ![5, 10, 30, ...(type === 'EXAM' ? [1440] : [])].includes(n))) {
    throw new RequestError(400, 'invalid_preferences');
  }
  return [...new Set(value)];
}
function validateReminderSpec(spec, batchId, eventId, version, event) {
  requireFields(spec, ['userId', 'reminderMinutes']);
  const uid = userId(spec.userId); const mins = validateMinutes(spec.reminderMinutes, event.type);
  return mins.map(minutes => ({ id: `event:${eventId}:v${version}:user:${uid}:before:${minutes}`, batchId, eventId,
    version, userId: uid, kind: 'REMINDER', scheduledAt: event.startAt - minutes * 60_000,
    payload: { title: 'TimeAlert reminder', body: `${event.title} (${event.type}) at ${event.timeLabel} starts in ${minutes === 1440 ? '1 day' : `${minutes} minutes`}.${event.location ? ` Room: ${event.location}.` : ''}`,
      url: event.url, id: `event:${eventId}:v${version}:user:${uid}:before:${minutes}` } }));
}
function validateJob(job) {
  requireFields(job, ['id', 'batchId', 'eventId', 'version', 'userId', 'kind', 'scheduledAt', 'payload']);
  if (typeof job.id !== 'string' || job.id.length > 200 || !/^[A-Za-z0-9:_-]+$/.test(job.id) ||
      !Number.isSafeInteger(job.version) || job.version < 1 || !Number.isSafeInteger(job.scheduledAt) || job.scheduledAt < 0 ||
      !['REMINDER', 'CANCELLATION'].includes(job.kind)) throw new RequestError(400, 'invalid_job');
  idValue(job.batchId, 'batch'); idValue(job.eventId, 'event'); userId(job.userId);
  requireFields(job.payload, ['title', 'body', 'url', 'id']);
  if (typeof job.payload.title !== 'string' || job.payload.title.length > 120 || typeof job.payload.body !== 'string' ||
      job.payload.body.length > 500 || !SAFE_URL(job.payload.url) || job.payload.id !== job.id) throw new RequestError(400, 'invalid_payload');
  return job;
}

export default {
  async fetch(request, env) {
    try {
      const path = new URL(request.url).pathname;
      if (request.method === 'GET' && path === '/health') return json({ status: 'UP' });
      if (!path.startsWith('/internal/')) return json({ status: 'not_found' }, 404);
      if (!configured(env)) return json({ status: 'setup_pending' }, 503);
      if (!await authorized(request, env)) return json({ status: 'unauthorized' }, 401);
      if (request.method === 'GET' && path === '/internal/public-key') return json({ status: 'ready', publicKey: env.VAPID_PUBLIC_KEY });
      const reminderPaths = new Set(['/internal/reminders/schedule', '/internal/reminders/replace', '/internal/reminders/cancel', '/internal/reminders/sync', '/internal/reminders/summary']);
      if (!reminderPaths.has(path) && !['/internal/subscriptions', '/internal/test', '/internal/device'].includes(path) && !(path === '/internal/reminders/_alarm' && env.TEST_MODE === 'true')) {
        return json({ status: 'not_found' }, 404);
      }
      let body = {};
      if (request.method !== 'GET') body = await readJson(request, path.startsWith('/internal/reminders/') ? 262144 : 8192);
      if (path === '/internal/subscriptions') {
        if (request.method !== 'POST') throw new RequestError(405, 'method_not_allowed');
        requireFields(body, ['userId', 'subscription']); userId(body.userId); body.subscription = validateSubscription(body.subscription);
      } else if (path === '/internal/test' || path === '/internal/device') {
        requireFields(body, ['userId', 'deviceId']); userId(body.userId);
        if (!/^[0-9a-f-]{36}$/.test(body.deviceId || '')) throw new RequestError(400, 'invalid_device');
        if (!(path === '/internal/test' ? request.method === 'POST' : ['POST', 'DELETE'].includes(request.method))) throw new RequestError(405, 'method_not_allowed');
      } else if (path === '/internal/reminders/summary') {
        if (request.method !== 'GET') throw new RequestError(405, 'method_not_allowed');
      } else if (path === '/internal/reminders/_alarm') {
        if (request.method !== 'POST') throw new RequestError(405, 'method_not_allowed');
        if (body.now != null && !Number.isSafeInteger(body.now)) throw new RequestError(400, 'invalid_time');
      } else if (path === '/internal/reminders/schedule') {
        if (request.method !== 'POST') throw new RequestError(405, 'method_not_allowed');
        validateJob(body);
      } else if (path === '/internal/reminders/replace') {
        if (request.method !== 'POST') throw new RequestError(405, 'method_not_allowed');
        idValue(body.batchId, 'batch'); idValue(body.eventId, 'event');
        if (!Number.isSafeInteger(body.version) || body.version < 1 || !Array.isArray(body.recipients) || body.recipients.length > 5000) throw new RequestError(400, 'invalid_request');
        body.event = validateEvent(body.event);
        body.jobs = body.recipients.flatMap(r => validateReminderSpec(r, body.batchId, body.eventId, body.version, body.event)).map(validateJob);
      } else if (path === '/internal/reminders/cancel') {
        if (request.method !== 'POST') throw new RequestError(405, 'method_not_allowed');
        idValue(body.batchId, 'batch'); idValue(body.eventId, 'event');
        if (!Number.isSafeInteger(body.version) || body.version < 1 || !Array.isArray(body.recipients) || body.recipients.length > 5000 ||
            typeof body.reason !== 'string' || !body.reason.trim() || body.reason.length > 1000) throw new RequestError(400, 'invalid_request');
        body.event = validateEvent(body.event);
        body.jobs = body.recipients.map(value => {
          const uid = userId(value);
          const id = `event:${body.eventId}:v${body.version}:user:${uid}:cancel`;
          return validateJob({ id, batchId: body.batchId, eventId: body.eventId, version: body.version, userId: uid, kind: 'CANCELLATION', scheduledAt: Date.now(),
            payload: { title: 'Timetable update', body: `${body.event.title} (${body.event.type}) at ${body.event.timeLabel} was cancelled. ${body.reason}${body.event.location ? ` Room: ${body.event.location}.` : ''}`,
              url: body.event.url, id } });
        });
      } else if (path === '/internal/reminders/sync') {
        if (request.method !== 'POST') throw new RequestError(405, 'method_not_allowed');
        userId(body.userId); idValue(body.batchId, 'batch');
        if (!Array.isArray(body.jobs) || body.jobs.length > 1000) throw new RequestError(400, 'invalid_request');
        if (body.revoke != null && typeof body.revoke !== 'boolean') throw new RequestError(400, 'invalid_request');
        body.jobs = body.jobs.map(job => validateJob(job));
        if (body.jobs.some(job => job.userId !== body.userId || job.batchId !== body.batchId)) throw new RequestError(400, 'invalid_request');
      }
      const store = env.PUSH_STORE.get(env.PUSH_STORE.idFromName('subscriptions-v1'));
      const suffix = path === '/internal/reminders/summary' ? new URL(request.url).search : '';
      return await store.fetch(new Request(`https://store${path}${suffix}`, {
        method: request.method, headers: { 'Content-Type': 'application/json' },
        ...(request.method === 'GET' ? {} : { body: JSON.stringify(body) }),
      }));
    } catch (error) {
      return json({ status: error instanceof RequestError ? error.code : 'unavailable' }, error instanceof RequestError ? error.status : 503);
    }
  },
};

export class PushStore extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env); this.sql = ctx.storage.sql;
    this.ready = ctx.blockConcurrencyWhile(async () => {
      this.sql.exec(`CREATE TABLE IF NOT EXISTS devices (
        id TEXT PRIMARY KEY, user_id TEXT NOT NULL, endpoint TEXT NOT NULL UNIQUE, subscription TEXT NOT NULL
      ); CREATE INDEX IF NOT EXISTS devices_user ON devices(user_id);
      CREATE TABLE IF NOT EXISTS test_limits (user_id TEXT PRIMARY KEY, window INTEGER NOT NULL, count INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS reminder_jobs (
        id TEXT PRIMARY KEY, batch_id TEXT NOT NULL, event_id TEXT NOT NULL, event_version INTEGER NOT NULL,
        user_id TEXT NOT NULL, kind TEXT NOT NULL, scheduled_at INTEGER NOT NULL, payload TEXT NOT NULL,
        state TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, next_retry_at INTEGER,
        created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, accepted_at INTEGER, error_code TEXT
      ); CREATE INDEX IF NOT EXISTS reminder_jobs_due ON reminder_jobs(state, scheduled_at, next_retry_at);
      CREATE INDEX IF NOT EXISTS reminder_jobs_event ON reminder_jobs(event_id, event_version, state);
      CREATE INDEX IF NOT EXISTS reminder_jobs_member ON reminder_jobs(batch_id, user_id, state);
      CREATE TABLE IF NOT EXISTS reminder_deliveries (
        delivery_id TEXT PRIMARY KEY, job_id TEXT NOT NULL, device_id TEXT NOT NULL,
        state TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, next_retry_at INTEGER,
        updated_at INTEGER NOT NULL, error_code TEXT, UNIQUE(job_id, device_id)
      ); CREATE INDEX IF NOT EXISTS reminder_deliveries_job ON reminder_deliveries(job_id, state);`);
      await this.scheduleNextAlarm();
    });
  }
  async fetch(request) {
    await this.ready;
    const path = new URL(request.url).pathname;
    if (path === '/internal/reminders/_alarm') {
      const body = await request.json(); await this.runAlarm(body.now ?? Date.now());
      return json({ status: 'alarm_run' });
    }
    if (path.startsWith('/internal/reminders/')) return this.reminders(request, path);
    const body = await request.json(); const { userId: uid, deviceId } = body;
    if (path === '/internal/subscriptions') {
      const sub = body.subscription;
      const existing = this.sql.exec('SELECT id, user_id FROM devices WHERE endpoint = ?', sub.endpoint).toArray()[0];
      if (existing && existing.user_id !== uid) return json({ status: 'device_conflict' }, 409);
      if (!existing && this.sql.exec('SELECT COUNT(*) AS count FROM devices WHERE user_id = ?', uid).one().count >= 10) return json({ status: 'device_limit' }, 409);
      const id = existing?.id || crypto.randomUUID();
      this.sql.exec(`INSERT INTO devices(id, user_id, endpoint, subscription) VALUES (?, ?, ?, ?)
        ON CONFLICT(endpoint) DO UPDATE SET subscription = excluded.subscription`, id, uid, sub.endpoint, JSON.stringify(sub));
      // A new device makes previous no-device failures eligible for another attempt.
      this.sql.exec(`UPDATE reminder_jobs SET state='PENDING', attempts=0, next_retry_at=NULL, error_code=NULL, updated_at=?
        WHERE user_id=? AND state='FAILED' AND error_code='no_subscription'`, Date.now(), uid);
      await this.scheduleNextAlarm(); return json({ status: 'registered', deviceId: id });
    }
    const device = this.sql.exec('SELECT subscription FROM devices WHERE id = ? AND user_id = ?', deviceId, uid).toArray()[0];
    if (path === '/internal/device' && request.method === 'DELETE') {
      this.sql.exec('DELETE FROM devices WHERE id = ? AND user_id = ?', deviceId, uid);
      this.sql.exec('UPDATE reminder_deliveries SET state="FAILED", error_code="device_removed", updated_at=? WHERE device_id=? AND state IN ("PENDING","RETRY")', Date.now(), deviceId);
      await this.scheduleNextAlarm(); return json({ status: 'removed' });
    }
    if (!device) return json({ status: 'not_registered' }, 404);
    if (path === '/internal/device') return json({ status: 'registered', deviceId });
    const now = Date.now(); const limit = this.sql.exec('SELECT window, count FROM test_limits WHERE user_id = ?', uid).toArray()[0];
    if (limit && now - limit.window < 60000 && limit.count >= 3) return json({ status: 'rate_limited' }, 429);
    this.sql.exec(`INSERT INTO test_limits(user_id, window, count) VALUES (?, ?, 1)
      ON CONFLICT(user_id) DO UPDATE SET window = excluded.window, count = ?`, uid,
      limit && now - limit.window < 60000 ? limit.window : now, limit && now - limit.window < 60000 ? limit.count + 1 : 1);
    const stored = JSON.parse(device.subscription);
    if (stored.expirationTime != null && stored.expirationTime <= now) { this.sql.exec('DELETE FROM devices WHERE id = ? AND user_id = ?', deviceId, uid); return json({ status: 'expired' }, 410); }
    const response = await this.sendPush(stored, { title: 'TimeAlert test', body: 'Notifications are enabled on this device.', url: '/', id: crypto.randomUUID() });
    if (response.kind === 'expired') { this.sql.exec('DELETE FROM devices WHERE id = ? AND user_id = ?', deviceId, uid); return json({ status: 'expired' }, 410); }
    if (response.kind !== 'accepted') return json({ status: response.kind === 'temporary' ? 'unavailable' : 'push_service_error', delivery: 'unknown' }, response.kind === 'temporary' ? 503 : 502);
    return json({ status: 'accepted', delivery: 'unconfirmed' }, 202);
  }
  async reminders(request, path) {
    const now = Date.now();
    if (path === '/internal/reminders/summary') {
      const url = new URL(request.url); const batchId = url.searchParams.get('batchId'); const eventId = url.searchParams.get('eventId');
      let rows;
      if (eventId) rows = this.sql.exec('SELECT state, COUNT(*) AS count FROM reminder_jobs WHERE event_id=? GROUP BY state', eventId).toArray();
      else if (batchId) rows = this.sql.exec('SELECT state, COUNT(*) AS count FROM reminder_jobs WHERE batch_id=? GROUP BY state', batchId).toArray();
      else return json({ status: 'invalid_request' }, 400);
      const counts = { PENDING: 0, PROCESSING: 0, DELIVERED: 0, RETRY: 0, FAILED: 0, CANCELLED: 0 };
      for (const row of rows) if (Object.hasOwn(counts, row.state)) counts[row.state] = row.count;
      const failures = eventId ? this.sql.exec(`SELECT error_code,COUNT(*) AS count FROM reminder_jobs WHERE event_id=? AND error_code IS NOT NULL GROUP BY error_code`,eventId).toArray()
        : this.sql.exec(`SELECT error_code,COUNT(*) AS count FROM reminder_jobs WHERE batch_id=? AND error_code IS NOT NULL GROUP BY error_code`,batchId).toArray();
      const deliveryFailures = eventId ? this.sql.exec(`SELECT d.error_code,COUNT(*) AS count FROM reminder_deliveries d JOIN reminder_jobs j ON j.id=d.job_id WHERE j.event_id=? AND d.error_code IS NOT NULL GROUP BY d.error_code`,eventId).toArray()
        : this.sql.exec(`SELECT d.error_code,COUNT(*) AS count FROM reminder_deliveries d JOIN reminder_jobs j ON j.id=d.job_id WHERE j.batch_id=? AND d.error_code IS NOT NULL GROUP BY d.error_code`,batchId).toArray();
      return json({ status: 'ready', counts, failureReasons: [...failures,...deliveryFailures].map(row=>({code:row.error_code,count:row.count})), nextAlarmAt: await this.ctx.storage.getAlarm() });
    }
    const body = await request.json();
    if (path === '/internal/reminders/schedule') {
      this.upsertJob(body, now); await this.scheduleNextAlarm(); return json({ status: 'scheduled', count: 1 });
    }
    if (path === '/internal/reminders/replace') {
      this.sql.exec(`UPDATE reminder_jobs SET state='CANCELLED', updated_at=?, error_code=NULL
        WHERE event_id=? AND event_version<>? AND state IN ('PENDING','PROCESSING','RETRY')`, now, body.eventId, body.version);
      for (const job of body.jobs) this.upsertJob(job, now);
      await this.scheduleNextAlarm(); return json({ status: 'replaced', count: body.jobs.length });
    }
    if (path === '/internal/reminders/cancel') {
      this.sql.exec(`UPDATE reminder_jobs SET state='CANCELLED', updated_at=?
        WHERE event_id=? AND state IN ('PENDING','PROCESSING','RETRY') AND kind='REMINDER'`, now, body.eventId);
      for (const job of body.jobs) this.upsertJob(job, now);
      await this.scheduleNextAlarm(); return json({ status: 'cancelled', count: body.jobs.length });
    }
    if (path === '/internal/reminders/sync') {
      this.sql.exec(`UPDATE reminder_jobs SET state='CANCELLED', updated_at=?
        WHERE batch_id=? AND user_id=? AND state IN ('PENDING','PROCESSING','RETRY') AND (kind='REMINDER' OR ?=1)`, now, body.batchId, body.userId, body.revoke === true ? 1 : 0);
      for (const job of body.jobs) this.upsertJob(job, now);
      await this.scheduleNextAlarm(); return json({ status: 'synced', count: body.jobs.length });
    }
    return json({ status: 'not_found' }, 404);
  }
  upsertJob(job, now) {
    this.sql.exec(`INSERT INTO reminder_jobs(id,batch_id,event_id,event_version,user_id,kind,scheduled_at,payload,state,attempts,next_retry_at,created_at,updated_at,accepted_at,error_code)
      VALUES(?,?,?,?,?,?,?,?,'PENDING',0,NULL,?,?,NULL,NULL)
      ON CONFLICT(id) DO UPDATE SET batch_id=excluded.batch_id,event_id=excluded.event_id,event_version=excluded.event_version,
        user_id=excluded.user_id,kind=excluded.kind,scheduled_at=excluded.scheduled_at,payload=excluded.payload,
        state=CASE WHEN reminder_jobs.state='DELIVERED' THEN 'DELIVERED' ELSE 'PENDING' END,
        attempts=CASE WHEN reminder_jobs.state='DELIVERED' THEN reminder_jobs.attempts ELSE 0 END,
        next_retry_at=NULL,updated_at=excluded.updated_at,error_code=NULL`,
      job.id, job.batchId, job.eventId, job.version, job.userId, job.kind, job.scheduledAt, JSON.stringify(job.payload), now, now);
  }
  async scheduleNextAlarm() {
    if (this.env.REMINDER_DELIVERY_ENABLED === 'false') { await this.ctx.storage.deleteAlarm(); return; }
    const row = this.sql.exec(`SELECT MIN(CASE WHEN state='PENDING' THEN scheduled_at WHEN state='RETRY' THEN next_retry_at END) AS due
      FROM reminder_jobs WHERE state IN ('PENDING','RETRY')`).one();
    if (row.due == null) await this.ctx.storage.deleteAlarm(); else await this.ctx.storage.setAlarm(row.due);
  }
  async alarm() { await this.ready; await this.runAlarm(Date.now()); }
  async runAlarm(now) {
    if (this.env.REMINDER_DELIVERY_ENABLED === 'false') { await this.ctx.storage.deleteAlarm(); return; }
    // PROCESSING jobs left by a terminated execution are made retryable. Per-device state
    // prevents sending again to devices whose provider already accepted the notification.
    this.sql.exec(`UPDATE reminder_jobs SET state='RETRY', next_retry_at=?, updated_at=?, error_code='interrupted'
      WHERE state='PROCESSING'`, now, now);
    const jobs = this.sql.exec(`SELECT * FROM reminder_jobs WHERE
      (state='PENDING' AND scheduled_at<=?) OR (state='RETRY' AND next_retry_at<=?)
      ORDER BY scheduled_at ASC,id ASC LIMIT 100`, now, now).toArray();
    for (const job of jobs) await this.deliverJob(job, now);
    await this.scheduleNextAlarm();
  }
  async deliverJob(job, now) {
    // Alarm delivery can overlap a request-driven test/resync or a retried alarm.
    // Claim atomically so only one execution sends this job at a time.
    const claim = this.sql.exec("UPDATE reminder_jobs SET state='PROCESSING', attempts=attempts+1, updated_at=? WHERE id=? AND state IN ('PENDING','RETRY') RETURNING id", now, job.id).toArray();
    if (!claim.length) return;
    const devices = this.sql.exec('SELECT id,subscription FROM devices WHERE user_id=?', job.user_id).toArray();
    if (!devices.length) {
      this.sql.exec("UPDATE reminder_jobs SET state='FAILED', error_code='no_subscription', next_retry_at=NULL, updated_at=? WHERE id=?", now, job.id); return;
    }
    let pending = false; let accepted = false;
    for (const device of devices) {
      const deliveryId = `${job.id}:${device.id}`;
      let delivery = this.sql.exec('SELECT * FROM reminder_deliveries WHERE job_id=? AND device_id=?', job.id, device.id).toArray()[0];
      if (delivery?.state === 'DELIVERED' || delivery?.state === 'FAILED') { accepted ||= delivery.state === 'DELIVERED'; continue; }
      if (delivery?.state === 'RETRY' && delivery.next_retry_at > now) { pending = true; continue; }
      this.sql.exec(`INSERT INTO reminder_deliveries(delivery_id,job_id,device_id,state,attempts,next_retry_at,updated_at,error_code)
        VALUES(?,?,?,'PROCESSING',1,NULL,?,NULL) ON CONFLICT(job_id,device_id) DO UPDATE SET state='PROCESSING',attempts=reminder_deliveries.attempts+1,updated_at=excluded.updated_at`, deliveryId, job.id, device.id, now);
      const subscription = JSON.parse(device.subscription);
      const result = await this.sendPush(subscription, JSON.parse(job.payload));
      if (result.kind === 'accepted') {
        accepted = true; this.sql.exec("UPDATE reminder_deliveries SET state='DELIVERED',next_retry_at=NULL,updated_at=?,error_code=NULL WHERE job_id=? AND device_id=?", now, job.id, device.id);
      } else if (result.kind === 'expired') {
        this.sql.exec('DELETE FROM devices WHERE id=?', device.id);
        this.sql.exec("UPDATE reminder_deliveries SET state='FAILED',next_retry_at=NULL,updated_at=?,error_code='expired_subscription' WHERE job_id=? AND device_id=?", now, job.id, device.id);
      } else if (result.kind === 'temporary') {
        const attempts = (delivery?.attempts || 0) + 1;
        if (attempts >= TEMP_RETRIES) this.sql.exec("UPDATE reminder_deliveries SET state='FAILED',next_retry_at=NULL,updated_at=?,error_code='retry_exhausted' WHERE job_id=? AND device_id=?", now, job.id, device.id);
        else {
          const retryAt = now + Math.min(BASE_RETRY_MS * 2 ** (attempts - 1), MAX_RETRY_MS);
          pending = true; this.sql.exec("UPDATE reminder_deliveries SET state='RETRY',next_retry_at=?,updated_at=?,error_code=? WHERE job_id=? AND device_id=?", retryAt, now, result.code || 'temporary_provider_failure', job.id, device.id);
        }
      } else {
        this.sql.exec("UPDATE reminder_deliveries SET state='FAILED',next_retry_at=NULL,updated_at=?,error_code='permanent_provider_failure' WHERE job_id=? AND device_id=?", now, job.id, device.id);
      }
    }
    const retry = this.sql.exec("SELECT MIN(next_retry_at) AS due FROM reminder_deliveries WHERE job_id=? AND state='RETRY'", job.id).one().due;
    const state = retry != null ? 'RETRY' : accepted ? 'DELIVERED' : 'FAILED';
    this.sql.exec('UPDATE reminder_jobs SET state=?,next_retry_at=?,accepted_at=?,updated_at=?,error_code=? WHERE id=?', state, retry, accepted ? now : null, now, state === 'FAILED' ? 'all_subscriptions_failed' : null, job.id);
  }
  async sendPush(rawSubscription, data) {
    try {
      if (rawSubscription.expirationTime != null && rawSubscription.expirationTime <= Date.now()) return { kind: 'expired' };
      const subscription = validateSubscription(rawSubscription);
      const payload = await buildPushPayload({ data: JSON.stringify(data), options: { ttl: 3600 } }, subscription, {
        subject: this.env.VAPID_SUBJECT, publicKey: this.env.VAPID_PUBLIC_KEY, privateKey: this.env.VAPID_PRIVATE_KEY,
      });
      const response = await fetch(subscription.endpoint, { ...payload, redirect: 'manual', signal: AbortSignal.timeout(5000) });
      if ([404, 410].includes(response.status)) return { kind: 'expired' };
      if (response.ok) return { kind: 'accepted' };
      if (response.status === 408 || response.status === 429 || response.status >= 500) return { kind: 'temporary', code: `provider_${response.status}` };
      return { kind: 'permanent' };
    } catch (error) { return { kind: 'temporary', code: error instanceof TypeError ? 'push_transport_failure' : 'push_payload_failure' }; }
  }
}
