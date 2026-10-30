[Overview](../../README.md) · [Developer roadmap](../../docs/PIPELINE.md)

# Priperi2.15.0 — prepared signup protection, NOT activated

Existing project: YOUR_PROJECT_REF, installed001–016. Never replay those migrations. No live provider configuration, SQL or relay deployment was changed by this update. Preserve existing accounts/messages/secrets and the current relay JWT gateway setting.

## Why


## Activation checklist

1. Review the external verification provider/privacy choice with the owner. All application data stays in Supabase, but Turnstile sees browser/network verification metadata. Do not accept new terms or incur billing on their behalf.
2. Configure a Free managed Turnstile widget for `YOUR_PROJECT_REF.supabase.co`. Save `TURNSTILE_SITE_KEY` (public) and `TURNSTILE_SECRET_KEY` (secret) as Supabase Edge secrets. Never commit/export/print the secret. Do not use provider test keys in production.
3. Deploy the COMPLETE matching relay tree: index.ts,handler.js,policy.js,account.js,delegation.js,groups.js,push.js,recovery.js,signup.js; retain runtime.d.ts. Deploying the relay first remains compatible with016. Verify that GET `/functions/v1/relay/signup?binding=<64hex>` returns bounded JSON with a public template and no secret. It intentionally does not serve public HTML, which Supabase Free rewrites.
5. On physical supported phones with current WebView, verify real-provider success, failure, expiry, back/cancel, background/resume, offline retry, and sign-in of an existing account without CAPTCHA. Verify warned key-loss replacement and recovery/move entry separately. Current automated provider tests are stubs; they are not evidence of real Cloudflare acceptance/OEM behavior.
6. Verify server-only execution/table grants and RLS on signup_admissions; verify existing direct REST/RPC denials. Use narrow catalog queries, not repeated broad log searches. Recheck copied deployed runtime bytes, installed function bodies, existing logins, and signup end-to-end. A consumed proof must have binding fields NULL; unused expired records must be swept.
7. Release only after normal signed-APK review and acceptance. Missing provider secrets after017 blocks new signup intentionally. Do not weaken admission or allow client bypass flags as a rollback. Repair configuration or restore reviewed prior routine definitions under separately reviewed execution control.

## Test evidence and limits

Local PostgreSQL/provider/relay tests cover rotated IDs,21admitted accounts beyond the old global20gate, scope changes, token replay/expiry, pending retry, private grants, guarded rollback, provider success/host/action/binding/time/response bounds and forged login flags. Android local-provider test covers cancellation, subframe rejection and successful native main-frame completion. MainActivity is unexported; only cosmetic SplashActivity is public and it discards extras/data.

No new production signing key or sideload-compatible attestation verifier is configured. Public source and app signing do not turn a public backend into an APK-exclusive endpoint. Credential theft on a fully compromised endpoint, solver-assisted signup and volumetric flooding remain threats; capacity planning and abuse monitoring are still necessary.
