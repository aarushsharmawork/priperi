# Priperi

[Developer roadmap](docs/PIPELINE.md) · [File index](docs/FILE-INDEX.md)

Priperi is an Android chat app. This folder contains the Android screens, encrypted local storage, message protocol, Supabase server, SQL migrations, Firebase push configuration, tests and build tools. There is no separate web frontend. The Android app is the frontend.

This export is based on version 2.15.0, version code 47. It includes pinned chats. It has no saved accounts, messages, recovery words, private signing keys or working cloud credentials. Connection fields are empty or marked `PASTE_...`. Use your own projects and keys.

Start with [Build the Android app](docs/BUILD.md), then [Set up the cloud services](docs/CLOUD-SETUP.md). [Configuration](docs/CONFIGURATION.md) lists every value you need to supply and where it belongs. [Tests](docs/TESTING.md) explains local checks. [File guide](docs/FILE-INDEX.md) links to a separate explanation for every source, configuration, test, asset and top-level guide in this export.

## What is here

| Folder | Contents |
| --- | --- |
| `priperi/` | Android app, Gradle wrapper, resources and Android/JVM tests |
| `backend/supabase/functions/relay/` | Server request handling, login, groups, recovery, signup verification and push |
| `backend/supabase/migrations/` | Database schema, access rules, quotas and cleanup jobs; migrations 001–017 |
| `backend/deployment/` | Cloud setup notes, push sender role and legacy Firebase deny-all rules |
| `backend/qa/` | Local emulator server and explicitly opted-in hosted checks |
| `tools/` | Export and portable regression tools; guide templates |
| `docs/` | Setup, pipeline, security, historical reviews and per-file notes |
| `config/` | Examples showing where private server values go |

## How a message travels

1. The user signs in on the registered installation. The server checks the password and installation proof.
2. The phone encrypts and signs a message before sending it over HTTPS.
3. The relay checks the current session, device binding, sender, recipient, permissions and request limits. SQL stores the encrypted envelope temporarily.
4. The receiving phone downloads it, checks it and saves it to encrypted local storage before acknowledging delivery.
5. An optional Firebase push wakes the app. It contains a generic hint, not the message text or sender name.

Read [Pipeline](docs/PIPELINE.md) for the build and deployment sequence.

## Security

Chat content is encrypted on the phones and in local storage. Private Android screens are not public entry points. Android's automatic app backup is disabled. Private server tables use row-level security and are not granted directly to app clients. Protected requests still check JWT signatures, live sessions and installation proof even though the relay's outer JWT gateway is disabled for pre-login routes.

These controls have limits. First-contact key comparison is optional, so trusting only a username trusts the directory's first key. Changed saved keys are rejected. The protocol does not provide Signal-style forward secrecy. The server can still observe account and delivery metadata. A compromised phone can bypass app-side biometric or device checks. Deleting a message or account cannot erase another person's external copy, an exported backup or provider-retained records. See [Security](docs/SECURITY-OVERVIEW.md).

## Cloud status

The source includes a prepared Turnstile signup-verification update in migration 017. At export time, the original deployment was recorded as using 001–016; 017 was not activated there. On your own new project, configure the real provider, deploy the matching relay and apply 017 after 016. Local fake-provider tests are not proof that a real widget works on physical phones.

There is no automatic production deployment in this folder. Cloud changes and production signing are manual. The original project's private keys are intentionally absent. You can build the same app code with your own configuration, but cannot reproduce its original signing identity or install over that identity without the original signing key.

## Before sharing an APK

Run local tests, build without emulator allowances, configure your own services, sign with your own release key and test on physical phones. Keep the signing key outside Git. The `secureTest` variant uses a local development key and is for private testing; `release` builds unsigned until you sign it.

No license has been supplied for the project as a whole. Check rights before redistributing it. The included BIP-39 notice covers that bundled word list.
