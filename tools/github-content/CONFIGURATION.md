# Configuration and private values

[Overview](../README.md) · [Developer roadmap](PIPELINE.md) · [File index](FILE-INDEX.md)

Use your own projects. `PASTE_...` means replace that text before using the file. No old account, message, device key, database dump or working private cloud key is included.

## Android

| File and field | What to paste | Where to get it |
| --- | --- | --- |
| `priperi/gradle.properties`: `priperiBackendUrl` | `https://YOUR_PROJECT_REF.supabase.co/functions/v1/relay` | Your deployed Supabase function |
| `priperi/gradle.properties`: `priperiSupabaseKey` | Your public `sb_publishable_...` key | Supabase API settings |
| `priperi/app/src/main/res/values/secrets.xml`: `project_id` | Firebase project ID | Firebase project settings |
| `google_app_id` | Firebase Android mobile SDK app ID | Android registration's `mobilesdk_app_id` |
| `google_api_key` | Firebase Android API key | Android registration's `current_key` |
| `gcm_defaultSenderId` | Firebase project number | Firebase project settings |
| `google_storage_bucket` | Firebase bucket identifier if assigned | Firebase project settings; this app does not use it for chat storage |
| `backend/deployment/firebase-lockdown/.firebaserc`: default project | Your Firebase project ID | Only needed if applying the legacy deny-all rules |

The publishable key, Firebase app ID, sender ID and Firebase Android API key are recoverable from an APK by design. Restrict API keys appropriately for your app. Never substitute a Supabase service-role key, `sb_secret_...` key or private Google credential in any Android resource or build property.

For a private local build you can keep backend properties in your user's Gradle properties instead. This export leaves the project properties blank; remove those two blank project entries if you want user-level properties to supply the values, or pass `-PpriperiBackendUrl=... -PpriperiSupabaseKey=...` explicitly.

## Supabase Edge secrets

`config/relay.env.example` lists the custom secrets. Copy it to an ignored `.env` file outside the repository and paste your own values there. The Supabase dashboard is also suitable. Do not commit the filled file.

| Name | Use |
| --- | --- |
| `SUPABASE_URL` | Project API base URL; provided by the hosted platform |
| `SUPABASE_ANON_KEY` | Server's Auth client; provided by the hosted platform |
| `SUPABASE_SERVICE_ROLE_KEY` | Server-only Auth/database administration; provided by the hosted platform |
| `FCM_SERVICE_ACCOUNT` | Entire Google service-account JSON object, encoded on one line; optional generic push |
| `TURNSTILE_SITE_KEY` | Public widget key used in the server's signup template |
| `TURNSTILE_SECRET_KEY` | Secret provider verification key; required for signup admission with 017 |

Keep platform-provided `SUPABASE_...` values in the platform's built-in environment. Do not try to upload them through the custom-secret example. `config/fcm-service-account.json.example` shows the expected shape without a private key. Replace the whole example with the JSON from your own dedicated sender account, then put that JSON in the server secret. A placeholder is not a valid credential.

## Other private material

Your Supabase database password and CLI login belong in your password manager or local CLI credential store. Your APK release keystore and passwords belong outside the repository. Each user's device keys, sessions, installation proof and recovery words are generated at runtime. There is no shared user key to paste into source. Do not copy the original app's account directories into a new project.

Hosted QA scripts require an explicit opt-in and your own configured endpoint. Some create synthetic accounts and remove them again. They are not part of ordinary builds. Use a disposable project; read each script before running it.
