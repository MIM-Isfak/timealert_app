export class RequestError extends Error {
  constructor(status, code) { super(code); this.status = status; this.code = code; }
}

export function requireFields(value, names) {
  if (!value || typeof value !== 'object' || Array.isArray(value) ||
      Object.keys(value).some(key => !names.includes(key))) {
    throw new RequestError(400, 'invalid_request');
  }
}

export function validEndpoint(endpoint) {
  if (typeof endpoint !== 'string' || endpoint.length > 2048) return false;
  try {
    const url = new URL(endpoint);
    if (url.protocol !== 'https:' || url.port || url.username || url.password || url.hash || url.search) return false;
    // Exact vendor hosts (Apple documents *.push.apple.com). Paths remain opaque.
    return (url.hostname === 'fcm.googleapis.com' && /^\/(fcm\/send|wp)\/[^/]+$/.test(url.pathname)) ||
      (url.hostname === 'updates.push.services.mozilla.com' && /^\/wpush\/[^\s]+$/.test(url.pathname)) ||
      (/^(?:[a-z0-9-]+\.)*push\.apple\.com$/.test(url.hostname) && url.pathname.length > 1);
  } catch { return false; }
}

export function decodeKey(value, length) {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_-]+$/.test(value)) return false;
  try { return atob(value.replace(/-/g, '+').replace(/_/g, '/')).length === length; }
  catch { return false; }
}

export function validateSubscription(value) {
  requireFields(value, ['endpoint', 'expirationTime', 'keys']);
  requireFields(value.keys, ['p256dh', 'auth']);
  if (!validEndpoint(value.endpoint) || !decodeKey(value.keys.p256dh, 65) ||
      !decodeKey(value.keys.auth, 16) ||
      atob(value.keys.p256dh.replace(/-/g, '+').replace(/_/g, '/')).charCodeAt(0) !== 4 ||
      (value.expirationTime != null && (!Number.isFinite(value.expirationTime) || value.expirationTime <= Date.now()))) {
    throw new RequestError(400, 'invalid_subscription');
  }
  return { endpoint: value.endpoint, expirationTime: value.expirationTime ?? null,
    keys: { p256dh: value.keys.p256dh, auth: value.keys.auth } };
}

export async function readJson(request, maxBytes = 8192) {
  if (!request.headers.get('content-type')?.startsWith('application/json')) throw new RequestError(415, 'json_required');
  if (!request.body) throw new RequestError(400, 'invalid_request');
  const reader = request.body.getReader();
  const chunks = []; let length = 0;
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    length += value.byteLength;
    if (length > maxBytes) { await reader.cancel(); throw new RequestError(413, 'request_too_large'); }
    chunks.push(value);
  }
  const bytes = new Uint8Array(length); let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  try { return JSON.parse(new TextDecoder().decode(bytes)); }
  catch { throw new RequestError(400, 'invalid_json'); }
}
