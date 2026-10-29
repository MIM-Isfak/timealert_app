import { generateKeyPairSync, randomBytes } from 'node:crypto';
import { writeFileSync } from 'node:fs';

// VAPID key generation only; Web Push encryption is handled by the library.
const { privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
const jwk = privateKey.export({ format: 'jwk' });
const publicKey = Buffer.concat([
  Buffer.from([4]), Buffer.from(jwk.x, 'base64url'), Buffer.from(jwk.y, 'base64url'),
]).toString('base64url');
writeFileSync(new URL('../.dev.vars', import.meta.url), [
  `INTERNAL_API_SECRET=${randomBytes(32).toString('hex')}`,
  `VAPID_PUBLIC_KEY=${publicKey}`,
  `VAPID_PRIVATE_KEY=${jwk.d}`,
  'VAPID_SUBJECT=mailto:developer@example.com', '',
].join('\n'), { flag: 'wx' });
console.log('Created ignored local development credentials. Values withheld. Existing files are never overwritten.');
