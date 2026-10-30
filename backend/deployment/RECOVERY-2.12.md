[Overview](../../README.md) · [Developer roadmap](../../docs/PIPELINE.md)

# Priperi 2.12.0 activation review

Status: specifically approved and ACTIVATED on28October2026; verified complete runtime and database access rules. Target existing Supabase Free project YOUR_PROJECT_REF (Priperi). No billing or secret change.

This reviewed activation is already complete here; do not replay015. The approved steps were to apply `202610280015_recovery_kits.sql` once after001–014, then deploy all eight runtime files in `supabase/functions/relay`: account.js, delegation.js, groups.js, handler.js, index.ts, policy.js, push.js and recovery.js. Keep existing gateway setting, secrets, client-denied grants and RLS. Do not replay older migrations or owner-reset SQL.


An activated kit is an additional persistent way into the same account. Anyone possessing its encrypted file, separate24words and current app password can restore onto a new installation. This revokes old SQL Auth sessions and rotates installation proof, while preserving account UID, public root fingerprint, HPKE keys, membership and queued recipient messages. Kit stays active until revoked/replaced or invalidated by an account move/deletion/inactivity. Phone biometric gates are local protection, not server attestation.

Protection: AES256-GCM encrypted file, random256-bit offline words, two P256 signatures, exact delegation prefix,2minute nonce bound to installation/proof/key/device ID, fresh managed-password verification, live SQL session check, independent committed account/global limits and atomic cutover. Wrong input does not change account binding. Interrupted same-target completion is retryable; finish clears nonce. No new broad Android storage permission.

Local evidence:87backend cases including9recovery cases,53JVM cases,4Android recovery cases. Additional9notification/mute/job cases,61other app cases,114layout previews and final build/lint/APK checks have completed; see VERIFICATION.md. Tests include lost key on same scoped device, repeated kit use, wrong words/password, tampered snapshot, identity-only backup, revocation, collision, expiry and injected transaction failure.

The user specifically approved activation after the browser policy confirmation for this persistent recovery boundary. Deployment completed; do not replay015. For future reviewed updates, compare migration/complete runtime bytes, apply015once, verify RLS/grants/routine hash/counts and deploymatching relay. Use read-only negative hosted checks; do not sign into or delete owner accounts.
