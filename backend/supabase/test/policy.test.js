import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, generateKeyPairSync, sign } from 'node:crypto';
import * as p from '../functions/relay/policy.js';
const keys = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
const publicKey = keys.publicKey.export({ format: 'der', type: 'spki' }).toString('base64');
function envelope() {
  const m = { id: '3db74880-a739-4c31-91bf-199072a90dcb', sender: 'alice', recipient: 'bob', sentAt: Date.now(), senderFingerprint: 'a'.repeat(64), recipientFingerprint: 'b'.repeat(64), ciphertext: randomBytes(120).toString('base64') };
  m.signature = sign('sha256', Buffer.from(p.context(m) + '\n' + m.ciphertext), keys.privateKey).toString('base64'); return m;
}
test('usernames reject path traversal, unicode lookalikes and unbounded input', () => {
  for (const value of ['alice', 'alice_01', 'abc']) assert.equal(p.username(value), value);
  for (const value of ['../alice', 'Aarush', 'a/b', 'aa', '1alice', 'аlice', 'a'.repeat(25), null]) assert.throws(() => p.username(value));
});
test('device proof is a 256-bit random secret, hashed and constant-time compared', () => {
  const secret = randomBytes(32).toString('base64url'); const hash = p.secretHash(secret); assert.equal(hash.length, 64);
  assert.ok(p.equalHash(hash, p.secretHash(secret))); assert.ok(!p.equalHash(hash, p.secretHash(randomBytes(32).toString('base64url'))));
  for (const invalid of ['', 'abc', '../../', 'x'.repeat(300)]) assert.throws(() => p.secretHash(invalid));
});
test('a signed envelope passes; changing any bound field or message body fails', () => {
  const m = envelope(); assert.equal(p.validateEnvelope(m), m); p.verifyEnvelope(m, publicKey);
  for (const [key,value] of Object.entries({ id: '4db74880-a739-4c31-91bf-199072a90dcb', sender: 'mallory', recipient: 'eve', sentAt: m.sentAt + 1, senderFingerprint: 'c'.repeat(64), recipientFingerprint: 'd'.repeat(64), ciphertext: randomBytes(120).toString('base64') })) assert.throws(() => p.verifyEnvelope({ ...m, [key]: value }, publicKey));
  const other = generateKeyPairSync('ec', { namedCurve: 'prime256v1' }); assert.throws(() => p.verifyEnvelope(m, other.publicKey.export({ format: 'der', type: 'spki' }).toString('base64')));
});
test('rejects expired, future, self-addressed and oversized messages', () => {
  for (const change of [{ sentAt: Date.now() - p.RETENTION_MS - 1 }, { sentAt: Date.now() + 120000 }, { recipient: 'alice' }, { ciphertext: randomBytes(19000).toString('base64') }, { signature: 'bad' }, { id: '../../users/admin' }, { sentAt: NaN }, { plaintext: 'must not be stored' }]) assert.throws(() => p.validateEnvelope({ ...envelope(), ...change }));
});
test('public key registration rejects secret material and unsupported algorithms', () => {
  const hpke = { primaryKeyId: 123, key: [{ keyId: 123, status: 'ENABLED', outputPrefixType: 'TINK', keyData: { typeUrl: 'type.googleapis.com/google.crypto.tink.HpkePublicKey', keyMaterialType: 'ASYMMETRIC_PUBLIC', value: randomBytes(48).toString('base64') } }] };
  assert.equal(p.validateKeys(JSON.stringify(hpke), publicKey).length, 64);
  const bad = structuredClone(hpke); bad.key[0].keyData.keyMaterialType = 'ASYMMETRIC_PRIVATE'; assert.throws(() => p.validateKeys(JSON.stringify(bad), publicKey));
  const rsa = generateKeyPairSync('rsa', { modulusLength: 2048 }); assert.throws(() => p.validateKeys(JSON.stringify(hpke), rsa.publicKey.export({ format: 'der', type: 'spki' }).toString('base64')));
  for(const namedCurve of ['secp384r1','secp521r1']) {
    const otherCurve=generateKeyPairSync('ec',{namedCurve});
    assert.throws(()=>p.validateKeys(JSON.stringify(hpke),otherCurve.publicKey.export({format:'der',type:'spki'}).toString('base64')));
  }
  assert.throws(() => p.validateKeys('{}', publicKey)); assert.throws(() => p.validateKeys('{broken', publicKey));
});
test('rate limits enforce the boundary and recover only after the time window', () => {
  const data = {}; for (let i = 0; i < 30; i++) data.rates = p.rateUpdate(data, 'send', 1000);
  assert.throws(() => p.rateUpdate(data, 'send', 1001), { code: 'resource-exhausted' });
  assert.equal(p.rateUpdate(data, 'send', 61000).send.count, 1);
});
test('public profiles never expose device proofs, tokens or rate records', () => {
  const out = p.publicProfile('alice', { username: 'alice', hpke: 'public', signing: 'public', fingerprint: 'fingerprint', deviceHash: 'secret', fcmToken: 'token', rates: {} });
  assert.deepEqual(Object.keys(out).sort(), ['uid','username','hpke','signing','fingerprint'].sort());
});
test('base64 canonical encoding and account identifiers are constrained', () => {
  for (const value of ['----', '!!!!', 'AAAA\n', 'A===']) assert.throws(() => p.base64(value, 4, 100));
  for (const value of ['a/b', '..', 'a\nb', '', null]) assert.throws(() => p.uid(value));
});
