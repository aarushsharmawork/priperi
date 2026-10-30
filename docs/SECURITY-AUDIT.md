[Overview](../README.md) · [Developer roadmap](PIPELINE.md)

> Current2.15.0 adds a private login entry, matching local account/session checks and a shared animated splash/loading view. Signup admission017 is tested locally but NOT deployed; production remains001–016. See RELEASE-REVIEW.md and backend/deployment/SIGNUP-2.15.0.md. Neither signup CAPTCHA nor Android root/emulator checks establish official-APK-only access. Earlier mandatory contact verification remains superseded by optional comparison.

> Current2.14.1 policy supersedes the mandatory-verification behavior described in the historical2.13.0 sections below. Username-only sending is allowed, optional comparison stays available and changed pinned keys still fail. First-contact directory substitution is consequently a known remaining risk. See [RELEASE-REVIEW.md](RELEASE-REVIEW.md) for the current release decision, input hardening, attack review and unresolved production requirements. No new backend deployment is needed.

# Priperi 2.13.0 security audit

30 October 2026. Scope: the Android source and packaged builds, complete relay source and migrations 001–016, disposable local Android/PostgreSQL/Auth fixtures, and narrowly scoped read-only checks of the existing Supabase project. This is a defensive review and regression exercise, not an independent penetration-test certificate. Findings below distinguish demonstrated defects from architectural limitations and hypothetical prerequisites.

## Verdict on the earlier architectural claims

| Claim | What the implementation actually establishes |
| --- | --- |
| `SHA-256(device_id)` is the private key | **False.** `DeviceIdentity.value()` hashes scoped Android ID, package and namespace into a stable binding identifier. `Crypto.newIdentity()` separately generates a random 32-byte installation secret and an X25519 HPKE identity. `Crypto.createKeys()` generates separate Keystore P-256 signing and AES-256-GCM storage keys. The identifier alone cannot sign in or decrypt chats. |
| Hashing Android ID makes it secret or anonymous | **False.** Hashing adds no entropy and the result is a stable pseudonym. Android ID is scoped by signing key, user and device on supported Android versions. Retaining this binding supports the requested reinstall/replacement and one-account-per-device behavior; it is not a tracking-proof identifier or an authentication secret. |
| Extracting the Supabase project URL/public key compromises accounts | **False by itself.** Both are public configuration. Privileged credentials must never ship. Direct REST/RPC attempts must fail without authorization independently of app controls; current live grants and bounded denial tests support that boundary. |
| Messages reach Supabase as plaintext | **False for the reviewed message-body path.** `Repository.send()` / `GroupRepository.send()` invoke Tink HPKE before queueing/upload. `Crypto.encryptEnvelope()` signs the ciphertext and recipient/sender/message/group context. Local history and identity are AES-GCM wrapped. Routing metadata remains visible to the service. |
| RLS alone protects privileged relay code | **False.** The relay's service role bypasses RLS, so its JWT validation and server-only SQL authorization are essential. Their current tests and live grants passed; no new BOLA/RLS bypass was demonstrated in this run. |
| First-contact key substitution is possible | **Confirmed conditional High; patched below.** A malicious directory could replace the first key before independent comparison. Existing-key pinning did not authenticate the first key. |
| Keystore means every key is hardware backed | **Not established.** AES/P-256 use Android Keystore; hardware security level varies by device and was not attested. HPKE private bytes are wrapped in the vault and loaded into software memory. |
| Transfer/recovery are known bypasses | **No authorization bypass demonstrated.** Replay, wrong password/proof, destination substitution and rollback paths passed the existing adversarial backend/Android tests. Optional recovery deliberately adds a persistent delegated authority and requires careful kit custody. |
| The protocol has forward secrecy like Signal | **False; still open.** The static recipient HPKE private key can decrypt captured historic ciphertext if later obtained. Random per-message HPKE encryption does not replace a ratchet that deletes old receiving keys. |
| No email/phone means zero metadata/no digital footprint | **False; still a privacy limit.** IDs, usernames, relationships, timestamps, provider request/IP logs and push tokens can be linkable. Account deletion cannot erase recipients' phones, external kits, screenshots or provider-controlled retention. |

References: [Android identifier guidance](https://developer.android.com/identity/user-data-ids), [Android ID scope](https://developer.android.com/reference/android/provider/Settings.Secure#ANDROID_ID), [Supabase API keys](https://supabase.com/docs/guides/getting-started/api-keys), [Supabase RLS and bypass roles](https://supabase.com/docs/guides/database/postgres/row-level-security), [Android Keystore guarantees and limits](https://developer.android.com/privacy-and-security/keystore).

## Threat model and reviewed execution paths

An attacker can unpack/rebuild the APK, discover all endpoint names and public keys, patch root/VPN/biometric/screenshot checks, intercept traffic on an environment they control, and send their own HTTP requests. Those local checks are useful device policy and screen protection, not server proof that the official app or biometric ran. Authentication must survive their removal.

The more serious separate adversary is a compromised directory/relay operator: encryption to an attacker-supplied first key still succeeds cryptographically. Independent key verification closes that specific sending path on an honest client. A compromised client, dishonest comparison channel, coerced consent or malicious app update is outside that guarantee. The server can still withhold traffic, disclose routing metadata and manipulate availability. Group membership is server managed; this change adds explicit local key/roster consent, not a fully signed decentralized group-state protocol.

Reviewed boundaries:

1. Identity → `DeviceIdentity`, `Crypto`, `Session`, relay `handler.js`, SQL device/proof/live-session routines.
2. Message → `Repository.send` / `GroupRepository.send` → encrypted durable outbox → `SyncEngine.outbox` → JWT/signature/proof validation → server-only SQL ownership/membership/queue limits → encrypted recipient save → acknowledgement.
3. Storage → `Vault`, Keystore AES-GCM AAD, encrypted indexes/history, no-backup directories, backup/recovery/transfer codecs and document-picker callbacks.
4. Platform/network → manifest exported components, session-gated screens, guarded dialogs, system-only TLS, cleartext/backup/debug flags, redirect checks and callback/account-generation leases.
5. Lifecycle → one-time transfer and rollback, recovery delegation/challenges/session revocation, replacement signup, inactivity cleanup and deletion dependencies.

## Finding 1 — first-contact key substitution

**Severity:** High, conditional on malicious/misbehaving directory or server. **Category:** MASVS-CRYPTO-2; authenticated peer-key establishment. This was not an unauthenticated takeover of an existing pinned identity.

**Before:** username lookup → `Vault.pin(peer)` → first key accepted → `Repository.send()` encrypted to that key. An attacker controlling the directory could return their own internally consistent HPKE/signing pair under the intended contact's identity. The signatures validate the substituted key, not the real person. Group first-member keys had the same trust gap.

**Patch:** the user explicitly selected mandatory verification before sending. `Vault.verifyIdentity()` now records the exact UID/fingerprint in an independently stored encrypted `trusted-key:<uid>` map only after explicit comparison or received QR/NFC confirmation. `isVerified()` ignores remote and legacy boolean flags. Send and edit paths use the repository gates; the background outbox independently holds pending unverified messages. QR/NFC lookup must match the scanned public fingerprint before confirmation. Manual confirmation rechecks the currently pinned fingerprint and account before committing.

```java
if (!v.isVerified(peer))
    throw new SecurityException("Verify this contact’s security key before sending. Scan their QR/NFC code or compare keys using a trusted call.");
JSONObject envelope = v.crypto().encrypt(uid(), v.identity(), peer, text);
```

Groups require every nonself UID/fingerprint to be independently checked and the exact current sorted `(UID, fingerprint, admin)` roster to be confirmed. The `trusted-group:<id>` digest includes group ID and a domain separator. Adding even an already-verified friend or changing admin roles requires fresh roster consent. Concurrent roster change is rejected before saving consent. Stale pending group envelopes are marked for review instead of being silently sent to an obsolete roster.

**Changed files:** `Vault.java`, `Repository.java`, `GroupRepository.java`, `SyncEngine.java`, `ChatActivity.java`, `GroupDetailsActivity.java`, `ContactCodeActivity.java`, `NfcContactPanel.java` under `priperi/app/src/main/java/com/priperi/`.

**Compatibility:** contacts remain saved, but old `verified:true` flags cannot prove independent verification and are not migrated into trust. Existing contacts need one explicit recheck. Receiving/reading remains available; unverified incoming identities are labelled as unchecked. Each person verifies before sending from their own phone. This is separate from the existing single-recipient chat approval and does not require two chat approvals.

**Verification:** `ContactTrustTest` checks hostile first profiles, legacy trust flags, held outbox, changed keys, substituted code lookup, group recipient/roster changes and real Cancel/Confirm UI. Existing positive-flow fixtures explicitly trust only their generated synthetic identities in AndroidTest code; no production intent/config bypass was added.

**Coach's takeaway:** encryption authenticates a key only after you establish independently whose key it is.

## Finding 2 — remote profile fields could overwrite local state

**Severity:** Medium hardening, conditional on a compromised or incorrectly expanded directory response. **Category:** MASVS-STORAGE-1 / trust-boundary validation. The normal current SQL directory DTO is constrained; this is not a demonstrated arbitrary-user injection through the deployed API.

**Before:** first `Vault.pin()` cloned an entire remote JSON profile. Fields such as `verified`, `blocked` and `readThrough` could be imported as if locally set. Combining remote trust flags with the new sending policy would defeat its purpose.

**Patch:** validate profile cryptography, UID and username, then allowlist only `uid, username, hpke, signing, fingerprint, delegations, contactState`. Preserve local block/read state and derive verification exclusively from the independent encrypted trust map. Roll back the in-memory contact/trust write if durable save fails.

```java
JSONObject clean = new JSONObject();
for (String field : new String[]{"uid", "username", "hpke", "signing", "fingerprint", "delegations", "contactState"})
    if (peer.has(field)) clean.put(field, peer.get(field));
clean.put("blocked", old != null && old.optBoolean("blocked"))
     .put("verified", isVerified(clean))
     .put("readThrough", old == null ? 0 : old.optLong("readThrough"));
```

`contactState` remains a server-supplied chat-quota hint; final sending authorization is still enforced in SQL. It never grants cryptographic verification.

**Verification:** a synthetically hostile profile carries `verified`, `_verified`, nested `settings`, a forged read watermark and block changes. Tests prove those fields cannot establish trust, overwrite local block/read state or enable sending.

**Coach's takeaway:** deserialize server data into an explicit public-data model; local security decisions belong to a separate authority.

## Finding 3 — document-picker file URI confused deputy

**Severity:** Medium, conditional on an attacker-controlled picker result. **Category:** MASVS-PLATFORM-1 / MASVS-STORAGE-1.

**Before:** recovery result 141 and transfer result 119 passed the returned URI directly to `ContentResolver.openFileDescriptor(uri, "wt")`. Android's resolver can open `file://` paths using the calling app's own file privileges. A malicious handler/provider able to influence the launched picker result could therefore return a known app-private writable path and make the app truncate it during export. Corresponding imports also accepted nonprovider file paths. Private Activities being unexported does not protect results received from a delegated operation. This requires a hostile result path; another app cannot directly launch these private Activities through the tested normal exported-component boundary.

**Reproduction:** tests used only disposable app-cache source/sentinel files. Before the patch, recovery and transfer overwrote the 44-byte sentinel with 64 test bytes; the existing message-backup scheme guard already rejected that result. No real vault, user kit or account was touched.

**Patch:** `DocumentUris.isProviderDocument()` requires a `content://` URI with a nonempty authority before any copy or temporary import-file creation. Applied to recovery 141/142, transfer 119/120 and backup 91/92. Recovery export also refuses a missing prepared offer. Provider access still uses Android's URI permission checks and existing size/cancellation/error handling; arbitrary HTTP and file paths are never opened.

```java
static boolean isProviderDocument(Uri uri) {
    return uri != null && "content".equals(uri.getScheme())
            && uri.getAuthority() != null && !uri.getAuthority().isEmpty();
}
```

**Verification:** `DocumentResultTest` reproduces both prior overwrites, checks the existing backup denial, and tests valid provider URI recognition against file/HTTPS/missing-authority/null inputs. All 4 cases pass after the patch. This validates URI rejection, not positive behavior of every OEM/cloud file provider; physical provider validation remains needed.

**Coach's takeaway:** a file-picker result is untrusted input; never lend your own filesystem privileges to a returned path.

## Backend evidence and SQL disposition

No server vulnerability requiring a new migration was demonstrated in this audit. Applying generic new client SELECT/INSERT policies would weaken this architecture: clients intentionally have no direct relay-table/RPC access. Therefore there is **no new production SQL patch or relay deployment** in 2.13.0. Existing001–016 must not be replayed on the live project.

Live read-only catalog checks showed:

- 0 relay tables without RLS;0 PUBLIC/anon/authenticated direct relay-table grants;0 client-executable public Priperi RPC grants.
- `anon` and `authenticated` have no relay-schema USAGE or superuser/BYPASSRLS role.
- 0 reviewed SECURITY DEFINER functions with an unsafe/unset search path.
- 0 relay Realtime publication tables;0 public Storage buckets and0 custom Storage policies.
- 0 public tables without RLS/client grants and no public SECURITY DEFINER functions executable by client roles.
- The complete relay/Priperi stored-routine body digest matches the locally applied migration reference (`71ad6a5c023f71cebc8101eaa24f444e`). This checksum detects drift; MD5 here is not an authentication primitive or proof against a dishonest database operator.

Use the exact read-only queries in [`backend/qa/catalog-security-readonly.sql`](../backend/qa/catalog-security-readonly.sql). Expected false/zero/empty values are described above. No account/message rows or secret values are selected. The live review used three small catalog executions and 51 bounded negative relay/REST/Auth HTTP probes (plus the small read-only Auth-settings request), not broad Supabase log searches. Auth reports public signup disabled; internally managed synthetic email-shaped account names are not user-provided email addresses.

The service-role boundary still requires complete operation allowlisting, real JWT verification, live SQL session/installation proof, actor-bound resource checks, signature/context validation and committed shared quotas. Existing adversarial tests cover wrong actors/proofs, forgery, replay, membership changes, queue limits and recovery/transfer races. Service-role or database-owner compromise remains privileged access; RLS cannot constrain it.

## Remaining architectural work and honest limits

1. **Forward secrecy — unresolved protocol gap (High for a long-term identity-key compromise threat).** Use a maintained, vetted ratcheting implementation rather than a custom cryptographic patch. Before a versioned protocol 3 rollout: review Android support/licensing/dependencies; define authenticated identity/prekey publication and signed capability negotiation; disallow silent downgrade; specify per-session/root/chain key deletion, bounded skipped keys, replay windows, multi-device replacement, group membership epochs and attachment encryption; explicitly decide how recovery snapshots interact with erased session keys. Build two-client offline/out-of-order/reinstall/recovery interoperability tests and obtain independent protocol review. Keep the present protocol labelled accurately during a staged migration. Do not silently reinterpret existing ciphertext. [Signal Double Ratchet specification](https://signal.org/docs/specifications/doubleratchet/).
2. **TLS pinning — stronger assurance not yet implemented.** Packaged TLS forbids cleartext and user-installed CAs, uses system trust and rejects unsafe redirects. It does not pin a managed Supabase certificate/key. MASVS-NETWORK-2's stronger endpoint-pinning control is therefore not met. A safe rollout needs an operator-controlled pin lifecycle, independently obtained backup pins, certificate-rotation/expiry/recovery tests and update planning. Pinning today's managed leaf blindly can lock out every client, and pinning cannot stop a patched hostile client. [MASVS network controls](https://mas.owasp.org/MASVS/08-MASVS-NETWORK/), [OWASP pinning guidance](https://cheatsheetseries.owasp.org/cheatsheets/Pinning_Cheat_Sheet.html).
3. **Device assurance — not remote attestation.** Keystore nonexportability was tested, not hardware backing on the user's handset. Local fingerprint/face/root/VPN/emulator restrictions can be patched by a device owner; no backend guarantee of their execution is claimed. Device checks do not replace authentication, and a software-compromised endpoint can see its own plaintext.
4. **Metadata/privacy — remains linkable.** A stable device binding is retained for the requested account model. Moving entirely to resettable random installation IDs would change reinstall/one-device behavior and still would not hide network metadata. Minimize operational/provider log retention where supported and avoid message/credential logging; do not promise zero footprint. External backup custody and other recipients' copies are outside remote erasure.
5. **Recovery custody.** Anyone holding both the encrypted file and 24 words can decrypt its saved snapshot offline without the app password. The password protects server adoption of the account. The creation page now states that distinction and provides a short safety-details dialog. Large-font layout checks wait for the asynchronous status reflow before scrolling, retaining full-control visibility assertions. Account deletion/revocation invalidates server recovery authority, not already-copied ciphertext/words. No old Keystore private signing key is exported.
6. **Distribution/physical validation.** The installable artifact retains the existing development certificate. The unsigned release still needs owner-managed production signing/distribution. Positive physical biometrics, two-phone NFC/camera, OEM file providers, push delivery, memory-pressure and power behavior are not proven by emulator results. No zero-vulnerability or universal-device claim is made.

## Verification checklist

- [x] Trace actual ID → random secret → public/private key generation; keep the ID out of credential roles. Test random AES-GCM IVs, AAD/purpose separation, ciphertext/context tampering and wrong-vault decryption. Keystore AES/P-256 keys must not be exportable via `getEncoded()`.
- [x] Confirm username discovery cannot send before independent verification; test direct Cancel/Confirm, QR/NFC mismatch/cancellation, legacy boolean flags and already-pending messages. Repeat independently on both phones; chat approval remains single-recipient.
- [x] Confirm every group recipient key and exact roster; test additions of new and already-verified members, changed keys/roles, concurrent changes and stale pending envelopes.
- [x] Prove hostile `file://` export results fail before opening a descriptor; normal content-provider URI recognition must still work.
- [x] Run local backend handler/SQL/JWT/Edge regressions, including forged identities, BOLA, revoked sessions, wrong installation proof, shared quotas, recovery/transfer nonce replay and rollback. Use disposable local accounts only.
- [x] Run read-only catalog SQL above; compare deployed routine bodies to the complete tested migration reference. Attempt anonymous REST/private schema and protected relay routes; expect rejection without supplying real owner credentials.
- [x] Run Android normal positive and adversarial flows, screen/font/theme matrix and notification permission grant/deny; restore emulator settings afterward. The matrix includes required-verification states and compact 1.8 font.
- [x] Build normal debug/debugAndroidTest/secureTest/release; run JVM tests and full lint. Ensure every normal BuildConfig disables QA-device allowance after fixture builds.
- [x] Inspect final APK manifest/TLS/backup resources and privileged-credential/fixture payloads; verify existing signer; test denied external private-activity/service starts, denied run-as and normal emulator rejection. Public config remains recoverable by design.
- [ ] Complete the physical-device and distribution checks above, independently review the cryptographic protocol and implement a separately reviewed forward-secrecy migration before claiming Signal-equivalent security.

Exact current run counts, build warnings, artifact hashes and scoped test limitations are in [`VERIFICATION.md`](VERIFICATION.md). Test-only synthetic identity trust helpers are excluded from app runtime source sets. Private before/after evidence stays in `local-only/evidence/v2.13.0` and is excluded from the GitHub export.
