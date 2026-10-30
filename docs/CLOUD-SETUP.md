# Set up the cloud services

[Overview](../README.md) · [Developer roadmap](PIPELINE.md) · [File index](FILE-INDEX.md)

This is a new-project guide. Do not run these steps against someone else's existing project. There are no user-data exports to import. The SQL files create structure and server routines; tests create their own disposable records.

## Supabase database and Auth

Create your own Supabase project and save its project reference, API URL, publishable key and database password privately. Use the internal email/password Auth provider for server-created accounts. Disable public signup, anonymous sign-in, phone and unused OAuth providers. Set the password rules to at least 12 characters with uppercase, lowercase, digits and symbols. The app also enforces a 72-byte UTF-8 maximum. Internal Auth addresses are non-deliverable `UID@accounts.priperi.invalid` identifiers; users do not supply real email addresses.

Review `backend/supabase/config.toml`. It records the Auth policy and deliberately disables the function's outer JWT gateway. The relay itself checks tokens for protected operations; do not remove those checks. Deploying a function does not by itself apply every Auth dashboard setting in this file. Set the hosted Auth settings explicitly and verify them.

In a fresh project's SQL editor, apply migrations 001–016 once, in filename order. Every SQL file in `backend/supabase/migrations/` is included. The last three digits before the underscore give the sequence. Some later migrations check exact earlier routine bodies. A mismatch means stop and compare versions; do not delete the guards.

Migration 002 enables `pg_cron` and schedules `SELECT relay.sweep()` hourly. If your platform cannot create that extension directly, enable Supabase Cron first, then run the file. Check that one `priperi-expiry` job exists and is active. Do not silently skip cleanup. An outage or paused project can delay cleanup.

SQL-editor execution does not update the CLI migration-history table automatically. This guide uses SQL-editor execution throughout. Do not mix it with an unreviewed `db push`; reconcile the history first if you choose to adopt CLI migrations later. Never replay old migrations on a populated project.

## Signup verification and migration 017

The current source includes a prepared signup-protection change. It was not activated on the original hosted project at export time. For a complete fresh setup:

1. Create your own managed Turnstile widget for `YOUR_PROJECT_REF.supabase.co`. Review the provider's privacy terms and plan yourself.
2. Set `TURNSTILE_SITE_KEY` and `TURNSTILE_SECRET_KEY` in your Supabase function secrets. They must be real production widget values for a real release.
3. Deploy all matching relay files, including `signup.js`, using the command below. The newer relay is also compatible with database 016 while you finish setup.
4. Review and apply `202610300017_signup_admission.sql` once after 016. It adds the private admission table and guarded server routines. Missing provider configuration then blocks new signup on purpose; existing users can still sign in.
5. Test signup, cancellation, expired verification, offline retry and existing-account sign-in on physical phones with current WebView. Local stub tests cannot replace this step.

The signup endpoint returns bounded JSON containing a public HTML template. The Android WebView renders that template through a restricted native path. Do not replace it with a publicly hosted page that receives passwords or device secrets. See `backend/deployment/SIGNUP-2.15.0.md` for the detailed review.

## Deploy the relay

Install the Supabase CLI and sign in using your own account. Run these commands from `backend/`, whose `supabase/` child contains the config, migrations and functions. Replace the example reference.

```sh
cd backend
supabase login
supabase link --project-ref YOUR_PROJECT_REF
supabase functions deploy relay --project-ref YOUR_PROJECT_REF --no-verify-jwt
```

`index.ts` is the entry point. Deploy its whole directory: account, delegation, groups, handler, policy, push, recovery and signup modules are all required. `runtime.d.ts` is source typing. Never deploy `backend/qa/` or the test server as the real relay.

Set custom secrets in the dashboard, or with a private env file:

```sh
supabase secrets set --env-file /private/path/priperi-relay.env --project-ref YOUR_PROJECT_REF
```

The hosted platform already supplies the three `SUPABASE_...` variables. The secret file supplies your Turnstile values and optional FCM JSON. Confirm that protected calls with no session are denied and that `relay` tables/RPCs remain inaccessible to direct app clients. `backend/qa/catalog-security-readonly.sql` contains catalog checks; it does not export account rows.

## Firebase push

Create your own Firebase project. Register the Android package you will build: `com.priperi` for release or `com.priperi.debug` for debug/secureTest. Copy the matching public app resource values into `secrets.xml` as explained in [Configuration](CONFIGURATION.md).

Enable the FCM HTTP v1 API. Create a dedicated push sender service account. `backend/deployment/fcm-sender-role.yaml` contains the minimal custom role permission, `cloudmessaging.messages.create`. Assign that role to the sender in your own project. If your organization permits service-account keys, obtain a credential for this account and store its complete JSON as the Supabase `FCM_SERVICE_ACCOUNT` secret. Restrict who can read it, rotate it and revoke old credentials. No Google private key goes in Android or Git.

Push is optional and carries only a generic hint. Without its server secret, foreground sync still works. Firebase is not the chat database. The legacy Firestore, Realtime Database and Storage rules in `backend/deployment/firebase-lockdown/` deny all access. They are supplied for completeness if you maintain those services; do not enable paid services just to use this app. Review service availability and pricing in your own account before creating resources.

## Finish

Paste the new relay URL and publishable key into the Android build properties. Build and sign the app. Use two disposable test accounts on real phones to check chat delivery, invitation/approval, muted push, pins, recovery and account move. Keep production signing and real-provider acceptance separate from local test success.

Official references: [Supabase function deployment](https://supabase.com/docs/guides/functions/deploy), [function secrets](https://supabase.com/docs/guides/functions/secrets), and [FCM server authentication](https://firebase.google.com/docs/cloud-messaging/send/v1-api).
