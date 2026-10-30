# Security

[Overview](../README.md) · [Developer roadmap](PIPELINE.md) · [File index](FILE-INDEX.md)

The app uses several separate controls. Each has a specific job and a limit.

| Control | What it does | What it does not promise |
| --- | --- | --- |
| Message encryption and signatures | Encrypts message content for the recipient and checks signed envelopes | Signal-style forward secrecy, protection after a phone is compromised, or hidden delivery metadata |
| Encrypted Vault | Encrypts local identity metadata and chat records using Android-backed keys | Remote wiping of an offline phone or removal of exported files |
| Installation binding | Checks device proof, current account/session and revocation on the server | A hardware-attested guarantee that only the official APK can connect |
| Contact-key checks | Rejects changes to saved keys; supports QR/NFC/manual comparison | Independent first-contact identity when the user trusts only a username |
| Private tables and server-only RPCs | Blocks direct app-client access to internal tables/functions | Protection from a stolen server administrator credential |
| Input and request limits | Bounds envelopes, JSON, names, groups, queues and retries | Complete resistance to network flooding or unlimited capacity |
| Biometric and device gates | Adds local confirmation and rejects detected unsupported environments | Server-verified hardware attestation; a modified OS/app can bypass client checks |
| Screen and backup restrictions | Restricts screenshots and disables Android automatic app backup | Prevention of an external camera, another participant's copy or all OEM/OS behavior |
| Optional generic push | Sends an inbox hint without sender or message text | Anonymous use of Google or guaranteed delivery |
| Signup admission in prepared 017 | Checks provider proof server-side and binds it to a signup attempt | Unique physical people/devices, official-app origin, or proof it is active on an existing server |

## Keys and credentials

Android generates each user's installation keys at runtime. Supabase service-role credentials, Google sender JSON, Turnstile verification secrets and APK signing keys are not app configuration. Keep them outside source and outside the APK. The example files show the shape of configuration, never working private keys.

A recovery file plus its separate words can decrypt its saved snapshot offline. The account password also protects the server-side restoration step. Keep recovery words away from the file. Chat backups are snapshots; they may contain messages later deleted elsewhere. An uninstall does not delete the server account and does remove local identity material.

## Server access

The outer function JWT gateway is disabled because some login and bounded signed-capability routes run before login. Protected operations verify JWT claims and then recheck the live session and installation proof. SQL checks permissions, membership, ownership and quotas. Never replace those checks with a client-supplied flag. Service-only database functions use restricted grants and controlled search paths.

Cron deletes expired queue state and enforces configured inactivity cleanup. It runs only when the service can execute it. Never is the default account inactivity setting. No cleanup can guarantee deletion of provider-retained logs/backups, other participants' copies or files already exported by a user.

## Release limits

The source contains historical review reports and tests. Those reports describe particular versions and environments, not an independent certification. A new maintainer must repeat the checks against their own build and service. Real Turnstile acceptance, positive biometric/account-move flows, NFC, camera, file providers, push behavior, device policies and power behavior need physical-phone testing. Production signing is the maintainer's responsibility. No zero-vulnerability or zero-footprint claim is made.

The historical reports in this export have had original-project identifiers, owner-machine paths and live account-count observations removed. They are retained to explain past decisions, not as setup instructions. Use BUILD.md and CLOUD-SETUP.md for a new installation.
