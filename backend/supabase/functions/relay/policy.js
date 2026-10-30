import { Buffer } from 'node:buffer';
import { createHash, timingSafeEqual, createPublicKey, verify } from 'node:crypto';

export const RETENTION_MS = 7 * 24 * 60 * 60 * 1000;
export class PolicyError extends Error { constructor(code, message) { super(message); this.code = code; } }
export function requireThat(ok, code = 'invalid-argument', message = 'Check the details and try again.') {
  if (!ok) throw new PolicyError(code, message);
}
export function text(value, min, max) {
  requireThat(typeof value === 'string' && value.length >= min && value.length <= max);
  return value;
}
export function username(value) { text(value, 3, 24); requireThat(/^[a-z][a-z0-9_]{2,23}$/.test(value)); return value; }
export function uid(value) { text(value, 1, 128); requireThat(/^[A-Za-z0-9_-]+$/.test(value)); return value; }
export function messageId(value) { requireThat(typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value)); return value; }
export function hash(value) { return createHash('sha256').update(value).digest('hex'); }
export function secretHash(value) { text(value, 43, 43); requireThat(/^[A-Za-z0-9_-]{43}$/.test(value)); return hash(value); }
export function equalHash(a, b) { return typeof a === 'string' && typeof b === 'string' && a.length === 64 && b.length === 64 && timingSafeEqual(Buffer.from(a), Buffer.from(b)); }
export function base64(value, min, max) {
  text(value, min, max); requireThat(/^[A-Za-z0-9+/]+={0,2}$/.test(value) && value.length % 4 === 0);
  const bytes = Buffer.from(value, 'base64'); requireThat(bytes.toString('base64') === value); return bytes;
}
export function validateKeys(hpke, signing) {
  text(hpke, 60, 4096);
  let set; try { set = JSON.parse(hpke); } catch { requireThat(false); }
  requireThat(set && Array.isArray(set.key) && set.key.length === 1);
  const key = set.key[0];
  requireThat(key.status === 'ENABLED' && key.keyId === set.primaryKeyId && key.outputPrefixType === 'TINK'
    && key.keyData?.typeUrl === 'type.googleapis.com/google.crypto.tink.HpkePublicKey'
    && key.keyData?.keyMaterialType === 'ASYMMETRIC_PUBLIC');
  base64(key.keyData.value, 20, 512);
  let publicKey; try { publicKey = createPublicKey({ key: base64(signing, 80, 512), format: 'der', type: 'spki' }); } catch { requireThat(false); }
  // JWK uses a standard curve name; Node-compatible Edge runtimes may expose
  // different OpenSSL aliases through asymmetricKeyDetails.namedCurve.
  let jwk; try { jwk = publicKey.export({ format: 'jwk' }); } catch { requireThat(false); }
  requireThat(publicKey.asymmetricKeyType === 'ec' && jwk.kty === 'EC' && jwk.crv === 'P-256');
  return hash(hpke + '|' + signing);
}
export function context(m) { return (m.group ? ['priperi-group-v1',m.group,String(m.groupVersion)] : ['priperi-v2']).concat([m.id, m.sender, m.recipient, String(m.sentAt), m.senderFingerprint, m.recipientFingerprint]).join('\n'); }
export function validateEnvelope(m, now = Date.now(), group = false) {
  const fields = ['id', 'sender', 'recipient', 'sentAt', 'senderFingerprint', 'recipientFingerprint', 'ciphertext', 'signature',...(group?['group','groupVersion']:[])];
  if(group){messageId(m?.group);requireThat(Number.isSafeInteger(m.groupVersion)&&m.groupVersion>0&&m.groupVersion<=2147483647);}
  requireThat(m && typeof m === 'object' && !Array.isArray(m) && Object.keys(m).length === fields.length && Object.keys(m).every(key => fields.includes(key)));
  messageId(m.id); uid(m.sender); uid(m.recipient); requireThat(m.sender !== m.recipient);
  requireThat(Number.isSafeInteger(m.sentAt) && m.sentAt <= now + 60_000 && m.sentAt > now - RETENTION_MS, 'invalid-argument', 'Check your phone’s date and time, then try again.');
  requireThat(/^[0-9a-f]{64}$/.test(m.senderFingerprint) && /^[0-9a-f]{64}$/.test(m.recipientFingerprint));
  base64(m.ciphertext, 64, 24_000); base64(m.signature, 80, 144);
  return m;
}
export function verifyEnvelope(m, signing) {
  let ok = false;
  try { ok = verify('sha256', Buffer.from(context(m) + '\n' + m.ciphertext), { key: Buffer.from(signing, 'base64'), format: 'der', type: 'spki' }, Buffer.from(m.signature, 'base64')); } catch { /* fail closed */ }
  requireThat(ok, 'permission-denied', 'Message signature could not be verified.');
}
export function resetContext(uid, nonce, installation, fingerprint) {
  return ['priperi-reset-v1', uid, nonce, installation, fingerprint].join('\n');
}
export function verifyDeviceSignature(signature, signing, context) {
  base64(signature, 80, 144);
  let ok = false;
  try { ok = verify('sha256', Buffer.from(context), { key: Buffer.from(signing, 'base64'), format: 'der', type: 'spki' }, Buffer.from(signature, 'base64')); } catch { /* fail closed */ }
  requireThat(ok, 'permission-denied', 'This phone could not confirm this action.');
}
export function publicProfile(id, data) { return { uid: id, username: data.username, hpke: data.hpke, signing: data.signing, fingerprint: data.fingerprint }; }
export function rateUpdate(data, kind, now = Date.now()) {
  const limits = { send: [60_000, 30], lookup: [60_000, 20], sync: [60_000, 30], ack: [60_000, 120], settings: [60_000, 20], attempts: [60_000, 180] };
  const [window, limit] = limits[kind];
  const old = data.rates?.[kind];
  const rate = old && now - old.start < window ? { start: old.start, count: old.count + 1 } : { start: now, count: 1 };
  requireThat(rate.count <= limit, 'resource-exhausted', 'Please wait a moment and try again.');
  return { ...(data.rates || {}), [kind]: rate };
}
