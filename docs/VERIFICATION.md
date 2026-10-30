[Overview](../README.md) · [Developer roadmap](PIPELINE.md)

# Priperi2.15.0 — final local verification; signup activation pending

30October2026, code47. **Not cleared for public production distribution.** See RELEASE-REVIEW.md. No live SQL, relay deployment, provider setup, account/message mutation, signer, billing or gateway change occurred. Existing hosted001–016 still has the20/hour shared signup policy.017 and its matching relay are prepared and tested, not live. Never replay001–016.

| Check | Result and limits |
| --- | --- |
| Android regressions |119distinct cases in20isolated fixtures PASS, including input/queue poisoning, optional key comparison, login/session rejection, groups, recovery/moves, backup, notification/mute, slow/offline delivery, pinning and UI. New app-entry3cases cover forged splash extras, inconsistent session IDs and strict verification origins. |
| Protected signup |1additional Android case PASS with local017 and FAKE provider: cancel creates no account, subframe cannot approve, valid main-frame proof completes signup. Total120distinct app cases. This does not establish real Cloudflare/device acceptance. |
| Layout/startup |6screen/font/theme cases PASS, 126previews including the new loading view. Startup notification grant/deny2PASS. One emulator image, not every Android version/OEM. Selected previews visually reviewed. |
| Backend |99cases PASS:91retained tests use016,8new cases exercise017/provider/relay admission. Includes60unverified rotated IDs with no reservations/counter use,21admitted signups, scope/one-use/expiry/retry/private grants, guarded rollback, provider host/action/binding/freshness/output bounds and forged-login flags. No production abuse/load test. |
| Edge runtime |Type check PASS;6runtime cases PASS including new signup verification/template API. |
| Build/unit/lint |Normal debug/debugAndroidTest/secureTest/release build and full lint PASS. 57JVM cases PASS.0lint errors; warning counts below. Normal configs all reject QA devices. |
| APK boundaries |Strict normal emulator-policy1case PASS for public splash and private login. External starts of9private Activities and3app services, plus run-as, denied. Packaged cleartext/backup/debug controls checked; system-only TLS trust retained. BothAPKs scanned for privileged JWT/server keys/private PEM/service-account/fixture content. Same existing development certificate, NOT a production signer. |
| Dependency advisories |npm lockfile audit0known advisories; OSV query of111resolved Maven coordinates returned0affected. A current database result is not a guarantee of supply-chain safety. |
| Source integrity |All190frozen main/test/backend/config files match their final hashes. Old unrelated source deletions/pins preserved. No publication or Git commit. |

Lint warnings: lint-results-release 25, lint-results-secureTest 25, lint-results-debug 33.

- `Priperi-2.15.0-release-unsigned.apk`: SHA256 `a9579758dac38ad498647a0fa2f1e3481c7938bcdb40a4de7765a37539b4cb5d`, 2,185,068bytes.
- `Priperi-2.15.0-secure-test.apk`: SHA256 `002dc4c2ebdf3bb73a4fa078563b8a290ef5d95cdf78072a27d890159d2200bc`, 2,193,292bytes.

Install the secure-test update OVER the existing app to retain keys/history. It remains an internal development-signed artifact. The release package is unsigned. Real provider/OEM/biometric/NFC/background-push acceptance, owner production signing and sideload-compatible genuine-app policy remain release conditions. Neither CAPTCHA nor a passed local login test makes public APIs APK-exclusive.

Earlier investigation retained: a fixed-delay loading assertion missed the transient view once the problem page had already appeared. The final policy test continuously requires private content hidden until the error/retry state; the separate slow-worker test still explicitly requires the loading view before private content. Fake signup fixture initially attached an empty body to GET; corrected to a real bodyless GET, then verified successful native completion. These were test/fixture corrections, not silently disabled security assertions. Real Supabase Free returns the public verification template as JSON, with bounded native rendering, because its shared endpoint does not serve HTML. No real provider test was substituted by the local stub.

## Historical2.14.1 evidence (not a new deployment)

# Priperi 2.14.1 — final defensive review, production not yet cleared

30 October 2026, code46. See [RELEASE-REVIEW.md](RELEASE-REVIEW.md) for the attack matrix, confirmed inbox-jamming flaw/fix and remaining production conditions. QR/NFC/manual comparison is optional again; usernames permit a first message, changed keys remain rejected and blocks/approval/quotas remain. Chat-pinning changes are included. Input handling now bounds network/public-key JSON nesting and strictly decodes network UTF-8. No backend migration or deployment was needed.

| Check | Current evidence |
| --- | --- |
| Application regressions |116 distinct Android application cases pass in19 isolated fixtures after the inbox fix. Covers auth/device/revocation, discovery, single approval, blocks/limits, direct/groups/invites, message changes/reply/replay, storage/backups, move/rollback/recovery/reinstall, notifications/mute, slow/offline/reconnect, optional comparison, document results, hostile network input and chat pins. Repeated focused cases are not counted twice. |
| Poisoned inbox |Before fix,2 approved local senders submitted25 signed unusable envelopes each and hid a later valid message behind the first50 rows; the regression failed. After fix, the valid message arrives, the malformed queue drains only after encrypted placeholders are saved, an unreadable signed group copy is handled, and a tampered signature does not become an authenticated-placeholder error. This exercises normal local HTTP/Auth/SQL APIs with unchanged production quotas. No live queues attacked. |
| Screen/startup |6 size/font/theme cases pass120 synthetic previews, including optional-comparison states, compact1.8font, landscape and tablet. Selected final images visually inspected.2 notification startup grant/deny cases pass; emulator display/font/IME/network settings restored. One Android35 emulator image was used, not every Android version or physical handset. |
| Backend/Edge |91 adversarial handler/SQL/JWT cases pass56.31s; pinned Edge entry point and5 actual runtime cases pass. Tests cover forged/expired tokens, actor/proof/session mismatch, queues/shared quotas, ownership, membership and recovery/transfer replay/races. Backend runtime/migrations remain unchanged. |
| Hosted checks |51 current bounded unauthorized/malformed relay/REST/Auth probes pass, plus read-only Auth settings. No valid owner credentials/installation proof, account mutation, load test or Supabase log searches. Live catalog/body-digest evidence is from the earlier2.13.0 audit; it was not freshly rerun as SQL in this update. |
| Dependency advisories |OSV query reports0 affected entries among 110 Maven coordinates in the actual release dependency graph; backend npm audit reports0 known advisories. This is a registry snapshot, not coverage of unknown flaws or proof of supply-chain integrity. The initial Python HTTPS client lacked a local CA chain; the successful query used the system HTTPS client with certificate verification enabled. |
| Build/unit/lint |Normal debug/debugAndroidTest/secureTest/release/unit/full lint pass; 57 JVM tests pass.0 lint errors; warnings below. Every normal BuildConfig disables QA devices after fixture builds. |
| Final APK |Strict normal emulator-policy case passes. External starts of8 private Activities and3 app services (including NFC permission binding), and run-as, are denied. Packaged TLS uses system trust/no cleartext; backup/debug flags stay off. Existing development certificate retained. Both APKs pass scans for privileged JWTs/keys/PEM/service-account and fixture payloads. |
| Source integrity |The source-under-test manifest matches the final source hashes. Earlier external pinning edits were paused by the owner; a fresh complete run included them. The initially failing pin-test compilation belonged to the changing pre-pause source, not a passing final result. |

Lint warnings: release 25, secureTest 25, debug 25. See the build reports for maintenance/localisation/platform diagnostics.

- `Priperi-2.14.1-release-unsigned.apk`: SHA256 `c61211193a3a1c4c200d7e157ef13719b6ba2cc1123a01b976dc13e3dd6d2503`, 2,168,503 bytes.
- `Priperi-2.14.1-secure-test.apk`: SHA256 `1891450fae27e593800c32a17dda8f84dd2c5f0b6ea7132793ace36ddbbe0ccf`, 2,176,719 bytes.

The secure-test APK is an internal development update; install over the existing app to retain its keys/history. It is not the production signing/distribution artifact. Production release remains conditional on owner-controlled signing, physical acceptance and a realistic anti-abuse/capacity policy. First-contact directory trust, absent forward secrecy, unproven physical-device uniqueness/biometric attestation, no managed TLS pin lifecycle and server-visible metadata remain explicitly documented limitations. Do not claim zero vulnerabilities, zero footprint or Signal-equivalent guarantees.

Private evidence: local-only/evidence/v2.14.1. Historical audit-start results before the source pause are retained in v2.13.1, and the reproduced poison failure in v2.14.1/poison-before.txt. A shared-process UI reauthentication deadline initially failed after prior UI flows consumed the rolling sync budget; the same case passed in a fresh fixture. UI smoke and reauthentication checks were isolated without changing assertions or production quotas. Earlier sections below are historical, not fresh rerun claims.

# Priperi 2.14.0 — pinned chats

30 October 2026, code45. Long-press a saved direct or group chat for Pin chat / Unpin chat. Pinned conversations appear above unpinned conversations, with newest activity first within each section. Search, Unread and the Groups view retain their existing filtering. Pins live in encrypted account settings, separate from contact-key pinning, and survive profile/group refreshes and reopening.

Validation: 57 JVM tests passed; both focused ChatPinFlowTest emulator cases passed against the disposable local fixture. These cover direct/group pin persistence, profile/group refresh, invalid targets, unpin persistence, group-first ordering above a newer direct message, menu taps and activity recreation. Android secureTest lint reports 0 errors and 25 existing warnings. Optimized secureTest and unsigned release APKs build successfully; the secureTest signature verifies and matches the previous development certificate. Physical-phone pin interaction was not run. No backend changes were needed. Earlier audit and broad-suite results below are historical, not rerun claims for this change.

# Priperi 2.13.0 — security audit and mandatory verification

30 October 2026, code43. See [SECURITY-AUDIT.md](SECURITY-AUDIT.md) for severity/MASVS findings, exact source fixes, read-only SQL and remaining architecture work. Three changes address a conditional first-contact substitution risk, remote profile/local-state confusion, and reproduced recovery/transfer file URI overwrites. Existing backend001–016 is unchanged; no migration, relay deployment, account mutation, credential or signing change was required.

| Check | Current result |
| --- | --- |
| Android application cases |111 distinct cases pass:104 baseline application cases across15 bounded fixtures, plus2 new trust UI cases,1 new nonce/Keystore case and4 document-result cases. Includes groups, approvals, moves/recovery/reinstall, backups/storage/replay, notifications, sync interruptions and hostile identity/profile input. Repeated cases are not counted twice. |
| New trust boundary |7 focused cases pass. Tests prove no send or queued upload before independent key verification; remote/legacy flags cannot establish trust; each group key and exact roster need consent. Actual Cancel/Confirm and QR/NFC software paths retain separate chat approval. Synthetic test-only trust helpers never ship in the app. |
| Document results |Before-fix disposable cache sentinels were overwritten by recovery and transfer file URI exports:2 of3 baseline cases failed. After-fix all4 cases pass, including valid-provider URI recognition and malformed schemes/authority. No real private files or owner accounts used. |
| Crypto/storage |5 focused cases pass, including128 independently generated AES-GCM IVs, AAD/ciphertext tampering and wrong-vault rejection. AES/P-256 getEncoded() returns null. This tests nonexportability, not physical hardware security level. |
| Screens/startup |All6 window/font/theme scenarios pass120 synthetic previews, including unverified-contact states, landscape/tablet and compact1.8font. Selected images visually reviewed. Recovery status can reflow after arrival; the bounded scroll wait preserves full-control visibility assertions. Notification permission grant/deny both pass. Emulator density/font/IME/network settings restored. |
| Backend/Edge |91 handler/SQL/JWT cases pass; pinned Edge entry-point check and5 actual Edge runtime cases pass. Runtime/migrations unchanged. Covers auth/proof/session, wrong actor, quota/queue boundaries, recovery/transfer replay/substitution and membership races. |
| Live read-only checks |51 negative relay/REST/Auth probes reject unauthorized/malformed input, plus a read-only public Auth-settings request. Catalog checks find0 client private-table/RPC grants, no relay-schema usage/bypass role, no unsafe reviewed definer search paths, no relay Realtime publication/public Storage buckets, and no other public client-executable definers. Complete routine-body digest matches local001–016 reference. No Supabase log searches or owner account credentials used. |
| Dependencies/source |Pinned backend npm registry audit reports0 published advisories; this is not a complete Android/transitive or unknown-vulnerability assessment. Exportable source JWT scan finds no embedded privileged JWTs. |
| Responsiveness |Injected slow-inbox fixture: local save6ms and send67ms;300 historic rows267ms versus3 active rows6ms. Delayed presence chat opening653ms. Trust checks are local and add no per-send network round trip. Local figures do not establish global latency or physical battery behavior. |
| Normal build |Debug/debugAndroidTest/secureTest/release, unit tests and full lint pass. 53 JVM cases pass;0 lint errors. All3 normal BuildConfigs reject QA devices. |
| Final APK boundary |Normal strict emulator-policy case passes. run-as,8 private Activity launches and external PushSync/NFC service starts are denied. Packaged backup/cleartext/debug flags remain off with system-only TLS. Existing development certificate retained; both APKs pass privileged-key/JWT, private-PEM, service-account and fixture-payload checks. |

Lint warnings: release 25, secureTest 25, debug 25. These remain maintenance/localisation/platform diagnostics, not a zero-warning or security-certification claim.

- `Priperi-2.13.0-release-unsigned.apk`: SHA256 `1140d6154fc660d9ed61e87dd98ecb610225d5ac715d1b22678bf5a070ab5993`, 2,168,507 bytes.
- `Priperi-2.13.0-secure-test.apk`: SHA256 `d387f9b1b52362bd06a8ce5fd12f24269212d99d2f58784fa7c35e9dcf1b9c49`, 2,176,719 bytes.

Install over the existing app to retain keys/history. Existing contacts require one independent key recheck; groups additionally require current-roster consent. Single-recipient chat approval remains separate. Legacy verification booleans are deliberately not migrated into trust.

Still open: static recipient HPKE does not provide forward secrecy; network/relationship metadata remains linkable; managed TLS pin lifecycle and hardware/server attestation are not implemented. Positive physical biometric/NFC/camera/OEM/provider/push/power checks, owner production signing and independent protocol review remain pending. File+24 words decrypt a recovery snapshot offline; the current password protects server restoration. The audit supplies a versioned protocol migration plan rather than an improvised ratchet. No all-devices, zero-footprint or zero-vulnerability claim is made.

Private evidence: local-only/evidence/v2.13.0. Earlier sections below are historical results and are not current rerun claims.

# Priperi 2.12.3 — notification controls in a dialog

Code42. Removed the standalone Turn on/Turn off row. Notifications opens an On/Off radio dialog with Cancel and Phone settings; existing permission, registration and private-screen gates remain. Four targeted NotificationRecoveryTest cases pass11.593s, including actual radio-row touch, opt-out persistence, permission denial/grant, slow private-screen gate and overdue-policy retry. The older accessibility click helper could not activate the radio rows; the existing flow test now uses a real touch at their observed bounds. No production API, backend, permission or encryption change. Wider2.12.2 results below are historical and were not rerun for this small UI change.

Normal debug/optimized secureTest/unsigned release builds and secureTest lint pass;0lint errors/25warnings. Normal application QA gates remain off, development certificate matches2.12.2, and final APKs contain no fixture endpoint/token or privileged backend key.

- `Priperi-2.12.3-release-unsigned.apk`: SHA256`11db1c8d48df9be082221ae702203ff4a9bda686110517e3c86a3dd7c85086ec`, 2168507bytes.
- `Priperi-2.12.3-secure-test.apk`: SHA256`80820df45cc3d25c32602b22d1ee3485fb4b1df2fc93a9dec1c025671687bb27`, 2176719bytes.


# Priperi 2.12.2 verification — broader reliability review

Code41. Four confirmed bugs were fixed in the Android client. No production account, message, permission, credential, billing, gateway or backend routine changed. The existing001–016 migrations and all9relay source/type files are byte-identical to the preserved2.12.1 export. No server activation is needed.

- A deleted/blocked/unavailable recipient could abort the whole outgoing batch. Rejections now pause that conversation for a bounded60seconds, retain the exact encrypted envelope and order, and allow other conversations to proceed. Typed429resource exhaustion (including a full inbox) also backs off; network/5xx errors propagate, terminal sign-in failures are never swallowed, and the server independently retains every quota/auth check. A clock adjustment cannot make the saved delay unbounded.
- The Auth refresh response uses top-level error_code/code. Known expired/revoked session errors now clear the unusable session and return to password sign-in while retaining account, keys and history. A temporary503keeps the valid session for retry. Error formats/codes were checked against [Supabase Auth documentation](https://supabase.com/docs/guides/auth/debugging/error-codes).
- A queued chat load previously advanced readThrough after the screen had paused. Only a snapshot presented by the current visible private screen can mark messages read, with owner/session/visibility checks repeated on the storage worker. Direct and group cases pass, including read marking after returning.
- onLost for a previous network previously cancelled polling even when a new default network was already working. It now rechecks the actual active connection. Stale-loss and actual offline/reconnect cases pass.

Before-fix proof: the initial5case regression run reproduced4failures covering the first3bugs; a separate stale-network-loss case failed before its fix. Final6resilience cases include full-inbox rejection, preservation/order/backoff, invalid-refresh reauthentication, transient refresh failure, and paused direct/group reads. The network suite has2passing cases.

| Check | Result |
| --- | --- |
| Android flows |99distinct application cases pass across14bounded local fixtures. Covers recovery/reinstall/move/self-destruct, groups/invites, approval/message actions, backup/storage/replay, notifications/mute/push job, screen gates, QR/NFC software paths and connection interruptions. |
| Timing-test isolation |Two initial combined batches exposed fixture-state assumptions: overlapping global slow-request counters, and the final battery test comparing against an already-full24request process budget. Tests were separated into fresh fixtures; assertions, slow delays and production quotas remain unchanged. Attempt metadata/notes are retained privately; this is not a product bypass. |
| Layout/startup |All6window/font/theme cases pass114synthetic previews, including landscape, tablet and compact1.8font. A selection was visually reviewed. Actual startup notification grant and denial each pass their own case. Display/font/IME/network settings restored. |
| Backend/Edge |91backend handler/SQL/JWT cases pass31.29s. Pinned entry-point check and5actual Edge runtime cases pass. Production backend source is unchanged. |
| Speed |Injected2second inbox delay: local save8ms/send73ms.300history rows305ms versus3active6ms. Delayed presence chat opening533ms. Local figures do not establish worldwide/device latency or battery gains. |
| Dependencies |Package-registry audit reports0known advisories; this does not cover unknown flaws or certify Android transitive dependencies. |
| Live backend |51bounded read-only negative checks pass; protected endpoints reject missing authentication, malformed prelogin input and anonymous REST/Auth access. Public managed signup remains disabled. No owner sign-in, positive account mutation or synthetic production signup was performed. |
| Build |Normal debug/debugAndroidTest/secureTest/release/unit/full lint pass63.47s. 53JVM cases pass.0lint errors; warnings below. All3normal BuildConfigs reject QA devices. |
| Final APK |Strict emulator-policy case passes. run-as,8private activity launches and external PushSync/NFC service starts are denied. Backup/cleartext/debug off, system-only trust and existing dev certificate retained. Both APKs pass privileged credential/fixture-payload checks. |

- `Priperi-2.12.2-release-unsigned.apk`: SHA256`72c5bbf3896a78981a1a138176efa8aafced9662d0ef9a99fdb36264cfb8b3c9`,2,168,503bytes.
- `Priperi-2.12.2-secure-test.apk`: SHA256`b7e5c148bf9302f474280815b5b107aa9e3ca41df04afda25c99641d8c94f496`,2,176,719bytes.

Lint warnings: release 25, secureTest 25, debug 25.

Install over the existing app to retain its keys/history. Production signing, positive physical biometrics/provider/OEM/push/NFC/power validation and independent protocol assessment remain pending. Emulator scenarios and source review do not prove all devices/buttons are defect-free or establish absence of vulnerabilities. Previous results below are historical and are distinguished from this run.

# Priperi 2.12.1 verification — single approval activated

Code40. The specifically approved migration016 is deployed. One recipient acceptance opens both directions, including past approvals. Initiating an unanswered chat does not let the sender approve it. Blocks and the three-message cap for unapproved pairs remain enforced. Waiting outgoing messages resume when the accepted state arrives. Relay runtime files are unchanged from2.12.0; no redeployment, credential, billing or gateway change was needed.

| Check | Result |
| --- | --- |
| Backend |91cases pass48.716s; final guarded016 focused4case rerun passes2.301s. Covers both users sending beyond3after one approval, simultaneous introductions, initiator/outsider denials, idempotent acceptance, blocks, pre-existing approval and row/counter preservation, private grants and unexpected-function rejection. |
| Android |10focused cases pass15.602s:3approval,5conversation-alert and2sync-responsiveness cases. Includes real confirm/cancel, five reverse replies after a single acceptance and automatic retry of waiting outbox messages after asynchronous inbox sync.20synthetic accounts fit the unchanged20/hour fixture quota. |
| Speed |Under an injected2second inbox delay, final local save7ms and send48ms. Local fixture figures do not establish physical-phone or worldwide latency. Existing coalesced outbox scheduling and account-bound callbacks are retained. |
| Build |Normal debug, secureTest, unsigned release, unit and lint checks pass87s.53JVM cases and5Edge cases plus pinned entry-point check pass.0lint errors and25warnings per variant. All normal BuildConfigs disallow QA devices. |
| APK boundaries |Optimized normal APK passes strict emulator rejection. run-as,8external private activity launches and external PushSync/NFC service starts are denied. Backup, cleartext and debugging remain disabled; system-only TLS trust and existing development certificate retained. Both final APKs contain no private PEM, privileged backend key, service-account credential or fixture endpoint/token. |
| Hosted denial checks |51bounded negative requests pass:34protected operations without JWT,13malformed prelogin requests,1invalid-name replacement request and3REST/Auth denials. Public managed signup remains disabled. No valid owner credentials or account mutations used. |

- `Priperi-2.12.1-secure-test.apk`: SHA256`6621f7c2435fa04e0ea79383d46c64c8cf9e467d59e8f51b54d0e5c1ce9ee6c2`,2,176,715bytes.
- `Priperi-2.12.1-release-unsigned.apk`: SHA256`bcd47ab2ad70f307f10b1e688ba5c3a1bd985e8ddd3c674fbfa65c534fdd9f48`,2,168,503bytes.

Install over the existing app; do not uninstall and discard its private key. No layout changes were made beyond the approval text. The2.12.0 full layout/flow and REA results below are historical evidence, not rerun claims for2.12.1. Physical biometric/provider/OEM/push/NFC/power validation, production signing and independent protocol assessment remain pending. No zero-vulnerability claim is made.

# Priperi 2.12.0 verification — recovery activated

Code39. Optional persistent encrypted recovery is implemented; specifically approved migration015 and all8relay runtime modules are deployed. Existing data is preserved by the migration; no owner account was signed into, reset, recovered or deleted during verification. No credential, signer, billing or gateway setting changed.

| Check | Result |
| --- | --- |
| Recovery backend |87handler/SQL/JWT cases pass28.54s, including9new recovery cases: same-phone lost-key restore, preserved UID/fingerprint, reusable/revoked kit, bad password/forged/substituted target, expired/replayed nonce, interrupted exact retry, shared committed quotas, erasure/client-role denials, occupied device binding/inactivity expiry, competing challenges and injected atomic failure rollback. Existing78cases pass. |
| Android recovery |4cases pass; final bounded-parser rerun4.675s. Real Android Keystore/HPKE/storage + local SQL/Auth fixture checks repeated fresh-install recovery, signed snapshot including pending messages, identity-only snapshot/contacts/mute, old-session revocation, corruption/wrongwords/wrongpassword, deeply nested correctly encrypted input and biometric failclosed/pause/rotation. No positive biometric authentication was simulated. Saved provider files are read back and hashed before activation; success/mismatch tested in JVM. Positive physical provider roundtrip remains pending. |
| Notifications/mute |9cases pass18.658s: permission denial/grant and pause/error gates, explicit On/Off/settings route, muted direct/group delivery, unmute/expiry, generic alert content, replay/global optout/foreground/wrongaccount suppression, allowance above composer, actual system JobService restoring the session and saving muted messages, optout job suppression. Final alert check/emission stays on the vault actor. Push remains a generic inbox hint; job uses the existing shared sync budget. |
| Other Android regressions |61cases pass:7QR/NFC,7group/backup,6messageactions,7feature10,11UI/security,9navigation/reinstall/replacement,9accountcontrols and5sync/battery. With4recovery+9notification/mute cases,74distinct application cases pass. Fixtures use disposable API35 installations, real relay/SQL and an Auth stand-in. No quotas weakened. |
| Layout/motion/startup |All6window/font/theme cases pass114previews, including recovery: compactlight, darkphone1.3font, largelight, landscapedark1.3, tabletlight and compactdark1.8. A selection was visually reviewed. Motion-disabled gesture/longpress2case rerun passes; real startup permission grant/deny each passes1actual case with the other opt-in case skipped. Emulator animation/display/network settings restored. No all-device claim. |
| Speed |Under injected2s inbox delay, local save8ms and outgoing send59ms.300historical rows292ms versus3active9ms. Existing delayed-presence opening and offline8.5s zero-sync/reconnect cases pass. These local measurements do not claim physical-phone or worldwide latency/battery performance. Native startup drawing adds no artificial delay/library; private loading uses delayed neutral placeholders and retains all gates. |
| Edge |Pinned entry-point type check and5actual Deno primitive/runtime cases pass. New recovery module imports through the checked handler. |
| Live checks |All8published files were copied back in a fresh editor and match tested source.51bounded negative requests pass:34protected operations missingJWT,13malformed prelogin routes,1invalid-name replacement probe and3anonymous REST/private-schema/Auth-user denials. Public managed signup remains disabled. No positive hosted restore/owner login or production synthetic account creation was attempted. |
| Build/package |Normal debug, optimized secureTest and unsigned release builds/unit/full lint pass; final hardened build42s.53JVM cases,0lint errors; warnings below. All3normal BuildConfigs reject QA devices. Strict emulator-policy case passes. Installed optimized APK denies run-as, all8external private screens and bothPushSync/NFC service starts. Backup/cleartext/debugging disabled, system-only TLS trust, existing dev certificate retained. No private PEM, server key, service account or fixture endpoint/token in either final APK. |
| Packaged inspection |REA/JADX independently reads code39/min26/target36, complete11043-character manifest, private RecoveryActivity and BIND_JOB_SERVICE/nonexported PushSyncService. Focused entry-point bodies corroborate file I/O dispatch and generation-checked job delegation. These bodies are not a complete protocol audit; source/runtime/SQL tests supply separate evidence. APK signature verified separately with apksigner. |

Lint warning counts: debug 25, release 25, secureTest 25. Maintenance/localisation/identifier/accessibility warnings remain; no lint error or warning is a proof of absence of unknown vulnerabilities.

- `Priperi-2.12.0-release-unsigned.apk`: SHA256 `885d692e20ba8f2460bfb45dedb7200db74a9782f022be7113a09a713286e00f`, 2,168,503bytes.
- `Priperi-2.12.0-secure-test.apk`: SHA256 `350ed2a1e9e5642cbd1c605ea426a9d71b738b7ef567fa7a2b55f6d2a7f14122`, 2,176,719bytes.

Install over the current development app. Create and activate a kit before uninstalling; keep its file and24words separately. A kit restores only the saved snapshot while its account exists. It never overrides deletion/inactivity and cannot erase recipient/provider/exported copies. Reused-kit delegation branches can limit later message-only backup compatibility; save a fresh kit after restoring to capture latest history/signing state. Two physical phones with supported biometrics, real cloud/file-provider/OEM/power/NFC/push behavior, independent cryptographic assessment and production signing remain pending. Client device/biometric gates are not server hardware attestation. No zero-vulnerability/zero-footprint guarantee.

Evidence: ignored local-only/evidence/v2.12.0. REA summary ev_f975cf8da6eed86721b6bada3374f33bcf46810caa6f7441b69c33b4b9a92d7c, parent ev_0ab47d9bc24d9e441e22b9afc82e94f25549ace012e91821b04a027c8d3c68eb, complete manifest view ev_e45d57dbac82d7d05ca3b92b54e0b0a222bdf2c39464d893ed96900d4b1221fe. No native analysis session/configuration was opened/changed.

Reproduce local RecoveryFlowTest and ConversationAlertsTest using backend/qa/android-fixture.js on an empty disposable emulator. NotificationRecoveryTest's explicit-denial case runs first on an OS-denied installation; do not revoke permission mid-instrumentation, because Android terminates that process. Account tests require PRIPERI_ACCOUNT_TESTS=yes, injected delays SPEED_TESTS=yes and transport audit TRANSPORT_AUDIT=yes. Layout uses MATRIX_ONLY/SCREEN_MATRIX=yes. QA flag builds must never be distributed; rebuild normally before packaging.

Testing corrections: one null-description test helper and a mismatched mute-button label were fixed. A permission test attempted OS revocation inside instrumentation, which Android correctly terminated; it now uses the runner's pre-test revoked permission and a callback. A service-denial assertion was made case-insensitive. These were test corrections, not relaxed production checks. Browser review rejected overwriting an unrelated unfinished SQL query; the agent undid its own paste and used a separate draft, preserving that query. A stale relay editor was not deployed; the fresh7-file source matched the previous verified deployment before staging8files. Failed/intermediate evidence remains private.

Earlier version results below are historical.

# Priperi2.11.0 verification

Code38. Feature10’s software work and the user’s visual corrections are implemented. The final bottom order is **All chats / Groups / Settings**, with All chats selected by default. QR remains at the top. The backend runtime and Android main manifest match2.10 byte-for-byte: migrations001–014 remain active; no deployment, permission, real-account mutation, credential or billing change was made.

| Check | Result |
| --- | --- |
| New UI flows |7cases pass: corrected dock order, Groups requests/decline, local contacts plus directory member search without automatic joining, sole-member deletion/header entry, concurrent join preserving the other member’s group, horizontal swipe versus vertical scroll/reply rotation and send, guarded hyperlinks/long-press, and landscape keyboard search. Some functions are combined in one case. |
| Existing app regressions |64distinct app flow/security cases pass in quota-safe local batches, including7QR/NFC,7group/backup,6message actions,7new UI,14UI/security/notification,9navigation/reinstall/replacement,9account-control and5sync/battery cases. These use a disposable API35 emulator and the real relay/SQL with an Auth stand-in, never a real account installation. |
| QR/NFC |Actual mixed-polarity decoder reads normal and white-on-dark QR. Scanner checks cover camera grant/cancel, invalid input, repeated-frame suppression, cancel/reopen, independent key/block checks and explicit confirmation before pin. NFC checks preserve secure windows, foreground owner/lease/pause/scanner expiry boundaries. JVM tests add bounded mutual APDU, same-peer retry, invalid/self/expired/owner rejection and legacy one-way fallback. Two-phone RF/OEM behaviour remains unverified. |
| Layout/motion |Six window/font/theme configurations pass with108synthetic previews: compact light, dark phone1.3font, large light, landscape dark1.3, tablet light and compact dark1.8. Visual review found a clipped long member name; single-line ellipsis and an explicit visibility assertion fixed it, followed by phone/landscape/1.8font reruns. Two gesture/long-press cases also pass with OS animations disabled; original settings restored. Startup notification grant and denial each pass one actual staged case (the other method is an opt-in skip). |
| Speed |Under an injected2second inbox delay, local save2ms and outgoing send33ms;300historical records273ms versus3active6ms. Chat opening during delayed account presence572ms. Offline8.5second idle starts0network syncs; reconnect wakes sync. These are local fixture/emulator results, not a worldwide delivery/FPS/battery guarantee. No encryption, durability, rate quota or auth checks were weakened. |
| Backend/runtime |Unchanged78handler/SQL/JWT tests pass29.945s; pinned Edge type check and5runtime checks pass. All7deployed runtime source files remain byte-identical to the previously verified2.10 source. No new server endpoint or automatic URL-preview fetch was added. |
| Build/unit/lint |49JVM cases pass; normal debug, optimized secure-test and unsigned release builds succeed. Full lint has0errors; per-variant warning counts below. All3normal BuildConfigs have ALLOW_TEST_DEVICES=false. Normal strict emulator-blocking case passes. |
| Packaged APK |REA/JADX confirms code38/min26/target36 and declarations; AAPT2 confirms private app screens, secure non-debuggable/no-backup/no-cleartext/system-CA policy. Runtime run-as and7external private-screen starts are denied. Payload scans find no private PEM, privileged server key/service-account JSON or fixture endpoint/token. Existing development certificate is retained. Unsigned release still requires owner production signing. Static evidence does not establish runtime behaviour or absence of every vulnerability. |

Lint warning counts: debug 23, release 23, secureTest 23.

- `Priperi-2.11.0-release-unsigned.apk`: SHA256 `d7191d4b90b1df9a9e36e6aee5fae14b817494af4be0e13d4ea1046042b2ec93`, 2,152,059bytes.
- `Priperi-2.11.0-secure-test.apk`: SHA256 `51b7b5505306f750b40b7b346a222db221f601b12775f8b923117b64115362bc`, 2,160,279bytes.

Install over the existing development app; **do not uninstall or clear its data**. Update both phones for the new automatic NFC exchange and inverted dark QR; external camera support for inverted QR varies. Actual two-phone NFC/camera/biometric/file-provider/Drive/OEM/power tests, independent protocol assessment and production signing remain pending. Runtime coverage here is API35; compile37/min26 does not claim testing on every Android version. No guarantee of zero vulnerabilities or erasure of provider/contact/exported copies is made.

The first draft’s dock order was reversed and was corrected after user feedback. Testing also caught an expanding highlight that hid chats, keyboard history collapse, a cramped landscape inbox and a clipped large-font member row; these were fixed before packaging. One mixed regression batch lacked the fixture-only account-expiry cleanup flag; the app assertions passed and the correctly configured9case rerun passed. A scanner test initially asserted dismissal before Android processed its button callback; it now waits for the actual dismissed window. Failed/intermediate evidence stays private.

Reproduce with `PRIPERI_TEST_CLASS=com.priperi.FeatureTenFlowTest node backend/qa/android-fixture.js`; QR/NFC use their two classes, and SecurityBoundaryTest needs PRIPERI_TRANSPORT_AUDIT=yes. Account expiry/replacement cleanup requires PRIPERI_ACCOUNT_TESTS=yes; injected delays require PRIPERI_SPEED_TESTS=yes. The local fixture deliberately allows the emulator/loopback endpoint; never distribute that APK. Rebuild normally before sharing. Final layout checks use PRIPERI_MATRIX_ONLY=yes and PRIPERI_SCREEN_MATRIX=yes. Evidence, APK identity/REA records, lint, mappings and archive integrity are retained under ignored local-only/evidence/v2.11.0/.

Earlier version results below are historical.

# Priperi2.10.0 verification — deployed

Code37. The specifically approved migration014 and relay update are installed and verified. Current production has001–014; do not replay them. No existing owner account was changed or deleted by this task. Uninstall alone does not delete the server account; the new flow needs the old password and two explicit warnings before successful new signup schedules7day inactivity for the old account.

| Check | Result |
| --- | --- |
| Backend |78actual handler/SQL/JWT tests pass33.358s. Includes nine new cases for password/consent/claimed-ID rejection, seven-day start only on successful new signup, separate identity/history, shared committed failure quotas, expiration/retry, username/installation collision and client-denied grants, atomic rollback after injected commit failure, counter deletion preserving the old grace-period account, transfer/binding races and migration hash-guard rollback. All existing authorization/group/queue/inactivity checks retained. |
| Android flow |Initial9cases pass26.105s (replacement3, reinstall2, navigation4). Final new-flow/UI/security14cases pass60.404s after adding warning-text checks and shortening the password label. Covers both cancellations, bad old password, fresh UID/fingerprint/empty history, old-session revocation and safe transfer/chat navigation. Final test cleanup closes the Inbox launched by signup before erasing its synthetic account. |
| Layout |Six screen/font/theme configurations pass,84synthetic previews including the new replacement form. Visual review found the long old-password hint truncated; changed to Old password and reran relevant dark/landscape/large-text cases. These are API35 emulator checks, not all-device validation. |
| Edge |Pinned runtime entry-point check and5runtime tests pass. Deno's warning about ignored npm build scripts does not prevent the existing runtime/check; no scripts were approved or extra packages intentionally added. |
| Live denial checks |45negative requests pass:30missing-JWT protected routes,11malformed pre-login routes,1new-route valid-proof/invalid-name probe rejected before SQL and3anonymous REST/private-schema/Auth denials. Public managed signup stays disabled; the existing legacy gateway setting remains OFF with actual function JWT/live-session/proof checks. No owner login/reset/deletion was performed. Happy-path replacement/history separation is covered by local Android/SQL/managed-Auth fixtures; no destructive live replacement was run against a real account. |
| Final build/APK |42JVM checks pass; debug/optimized secure-test/unsigned release builds pass41s. All three lint reports have0errors/18maintenance warnings (one new hard-coded-text warning). Final normal strict-device case passes; installed secure APK denies run-as and all7external private starts. Same existing development certificate, no backups/cleartext/user CA/debugging. Both APK scans exclude private PEM, privileged credentials, local fixture tokens/endpoints and service-role JWTs; new flow is present and QAallowancefalse in all3BuildConfigs. |

Intermediate evidence is retained privately: the first backend run needed its expected service-only RPC count updated11→12; the first added warning assertion read accessibility before the dialog attached, fixed by waiting for its visible text. An initial UI/security batch omitted its opt-in local TLS/redirect audit server, causing fixture-only failures; the correctly configured14case run passes. One invalid-session UI timing failure in that initial batch did not repeat; new signup-test cleanup now explicitly closes its launched Inbox to isolate following tests. No authentication, encryption, rate limits or device restrictions were relaxed.

Reproduce the new Android flow with `PRIPERI_ACCOUNT_TESTS=yes PRIPERI_TEST_CLASS=com.priperi.ReinstallReplacementTest node backend/qa/android-fixture.js` on an empty disposable emulator. Combined SecurityBoundaryTest needs `PRIPERI_TRANSPORT_AUDIT=yes`. Never run synthetic tests against owner installations. The optional fixture expiry endpoint is local-only and not deployed. Positive physical/OEM, independent protocol assessment and production signing limits remain as described below.

Final secure-test SHA256 `aeeb76b760aa7c41e007941471f173aafa972e171380ef5d039486bb525964ff`; unsigned release `3f2f5674ac80d0ad916d72e6100bf2e0e8b187d8a17532452f251dcb7c1d0abd`. 014 and the relay are now active; install over the existing app and retain its keys. Existing development signing retained; production signing remains pending.

Earlier releases below are historical.

# Priperi2.9.1 verification

Android-only update, code36. The existing backend remains at migrations001–013 with no relay/grant/credential/permission changes. The separately approved reset of one existing account is complete and verified; it did not restore lost private keys or chats. Historical preservation counts in earlier release sections are snapshots from those deployments, not current account totals.

| Check | Result |
| --- | --- |
| Transfer navigation | Three baseline regressions reproduced: Done left an unusable landing, completed source stayed invisible after resume, and slow presence held chat opening. Final affected11cases passed24.505s: Navigation4, Reinstall2, AutomaticStorage1, Storage2, Notification2. Pending move and completed-source resume are covered without bypassing biometric/password checks. |
| Chat opening and sync | Chat usable in673ms while account presence deliberately waited2.5s. Local save3ms/send36ms while inbox waited2s;300history rows307ms versus3working rows6ms. Eight speed/index/async/delivery/offline cases pass27.838s, including late-response and stale401 protection. These are local fixture/API35 emulator measurements, not physical-network latency promises. |
| Protected failure recovery | An additional overdue-policy503case passes6.588s: private root stays hidden during the wait/error, Retry rechecks the protected policy and restores usable Settings. Slow local checks still hide private content and show a delayed spinner. |
| Controls and regressions | Account controls9pass19.111s; actions/approval8pass14.228s; groups/backup7pass18.806s; relay/crypto/device9pass4.771s; UI/security/QR/NFC17pass59.720s. Together with the12affected/new cases and8speed cases,70feature/regression cases passed in quota-safe batches. No authentication or rate protections weakened. |
| Permission/layout | Notification grant and denial each pass (one actual case per phase; other opt-in method skipped). Six size/font/theme cases pass: compact light, phone dark1.3font, large light, landscape dark, tablet light and compact dark1.8font.78synthetic previews include setup, paused move, missing key, inbox, direct/group chats, keyboard/rotation, Settings, group details, contact/NFC, transfer and backup. Selected new-page/keyboard previews were visually inspected too. |
| Backend | Unchanged actual handler/SQL/JWT suite69passes22.564s, covering authorization, limits, deletion/transfer races and private grants. No backend deployment is needed for this update. |
| Build/unit/lint |42JVM checks pass; normal debug, optimized secure-test and unsigned release builds succeed2m54s. All three full lint reports have0errors/17maintenance warnings each. Normal strict-device case passes3.546s; QA allowance isfalse in all three final BuildConfigs. |
| Finished APK |Package/version/min26/target36 confirmed with REA/JADX; decompiled private gates retain hidden content,180ms delay and current-session-generation checks, and transfer resume restores visibility after guards. AAPT2 confirms non-debuggable/no-backup/system-only CA/no-cleartext. Run-as and all7external private-screen starts denied. Both APK scans found no private PEM/server key/service-account JSON/fixture token or endpoint/service-role JWT. Existing development certificate retained; release remains unsigned. |


Final secure-test SHA256 `e9c243d099c2fa2f9b9b6aa3e9e5d71a0837fbac75c2b816743a4db2158d3f5a` (2,143,771bytes); unsigned release `f8c689830811d5fb2da284097e90a3f1537001567876b89eed2a4b09da5d716b` (2,135,555bytes). Install over the existing development app; do not uninstall or clear account data.

The first account-control regression run had one missing-dialog failure; a diagnostic single test and complete9test rerun passed. The new Navigation case also checks3pause/resume/selfdestruct-cancel cycles after legitimate password reauthentication. Intermediate failures and diagnostic output remain private. The failing pre-fix navigation suite and passing final suite are retained separately.

Reproduce new regressions with `PRIPERI_SPEED_TESTS=yes PRIPERI_TEST_CLASS=com.priperi.NavigationRecoveryTest,com.priperi.ReinstallRecoveryTest,com.priperi.AutomaticStorageTest,com.priperi.StorageFlowTest,com.priperi.NotificationRecoveryTest node backend/qa/android-fixture.js` on an empty disposable emulator. Delay/error endpoints exist only in this explicitly enabled local fixture. Screen cases use `PRIPERI_MATRIX_ONLY=yes PRIPERI_SCREEN_MATRIX=yes` with the same fixture. Never run disposable-identity tests on a real owner installation.

Runtime coverage is API35; compile37/target36/min26 and lint do not establish testing on all Android versions. Positive physical fingerprint/face transfer on two phones, real NFC/camera/file-provider/OEM/Drive and power profiling, independent protocol assessment and production signing remain pending. Uninstall removes the private device key; install updates over the current app. No password/Android-ID recovery bypass was introduced. Feature10 broader redesign remains deferred.

Earlier release sections below are historical.

# Priperi2.9.0 verification

## Sync speed (2.9.0/code35)


| Check | Result |
| --- | --- |
| Local save during a deliberately2s delayed inbox | Baseline1529ms; final5ms. Final outgoing send completed in36ms while the inbox was still delayed. This measures local fixture/emulator behavior. |
| History scaling |300encrypted historical rows read in275ms;3active rows in5ms. Peer/work indexes authenticate encrypted references; legacy backfill, transfer rebuild, unrelated corruption isolation and transactional index failure are checked. |
| Database network trips |Send3→2; direct/group sync3→2;12message acknowledgements24→2. Limits still charge each ID; mixed-recipient/partially limited batches retain earlier committed progress without deleting another recipient's messages. |
| Hosted latency |6signed opaque synthetic messages: send568–1370ms,median602ms; inbox426–456ms;6message ack524ms. Wrong sender ack403. These are server responses on a small network sample, with no before-version hosted baseline or physical two-phone rendering/FCM measurement. Account creation5564/1887ms shows cold-start/Auth costs remain. |
| Backend / Edge |69actual handler/SQL/JWT tests pass28.741s;3final hash-guard/migration/performance cases pass1.919s;5Edge tests and entry-point check pass. Runtime dependency audit reports0known production vulnerabilities. |
| Android |63cases passed in quota-safe runs:relay/crypto/device/notification11;account controls9;actions/approval8;groups/backup7;UI/security/QR/NFC17;final speed/index/async/delivery/storage/offline9;additional delayed401-after-token-refresh1;normal strict-policy1. Positive physical biometrics/NFC/OEM/Drive not claimed. |
| Build |42JVM checks pass; optimized secure-test and unsigned release build pass. Both full lint reports have0errors/17maintenance warnings. APK retains package/certificate, normal device guards/system-onlyCA/no cleartext/no backups. Run-as and7private external starts denied; no privileged key/PEM/QA token or address in either APK. REA/JADX sees generation/proof/Vault/move guards and response validation around network calls. |
| Live security / cleanup |43negative requests:30missing-JWT protected routes,10malformed pre-login and3anonymousREST/Auth denials. Public signup remains disabled. Only2new random synthetic accounts were deleted; their Auth/profile/queue/rate counts are0. All7deployed runtime modules exactly match tested source. |

Final secure-test SHA256 `5a544e9699fe7456cba8bffdf271061728f25db4c8385999af020bc33ab5d75f`; unsigned release `b43d13489e2d5e59bc2c0c9cd66569964a2b1d92278fb1451c5e1a4ff542d2e7`. Each is about2.1MB. Production signing/physical validation remain pending. Install over the current development app; do not uninstall/clear a real account.

Reproduce speed tests on an empty disposable emulator with `PRIPERI_SPEED_TESTS=yes PRIPERI_TEST_CLASS=com.priperi.SyncResponsivenessTest,com.priperi.SyncIndexTest,com.priperi.AsyncDeliveryTest,com.priperi.DeliveryLatencyTest,com.priperi.StorageFlowTest,com.priperi.BatteryFlowTest node backend/qa/android-fixture.js`. Delay endpoints exist only in the explicitly enabled local fixture and are absent from deployed functions/APKs. Keep test batches under unchanged signup/operation quotas.

A first oversized regression batch hit the20signup limit and left a failed setup; clearing only the disposable QA emulator and splitting the batch resolved it. The original responsiveness regression failed before the code change. Final review also caught an empty-vault recreation after removal; checking the old lease before opening storage fixed it. The affected five async cases and normal strict policy were rerun successfully after that final client-only patch; other listed regression runs precede that small ordering fix. One test constructor needed explicit overload selection. APK resource paths were resolved through the actual optimized resource table after the original descriptive paths were absent. Intermediate evidence/failures are retained privately. No protections were loosened to pass tests.

Feature12 research: [Telegram updates](https://core.telegram.org/api/updates) informed incremental local state and recovery; [Meta's WhatsApp device-verification article](https://engineering.fb.com/2023/04/13/security/whatsapp-device-verification-protects-your-account/) describes waking to retrieve queued messages, consistent with retaining generic FCM hints and encrypted queue retrieval; [Supabase claims verification](https://supabase.com/docs/reference/javascript/auth-getclaims) documents public-key caching. Priperi retains its own protocol and fallback polling; it does not implement MTProto, Signal Double Ratchet or an equivalent global push infrastructure. No guaranteed instant delivery or zero-vulnerability claim. Rare token refresh/ordinary account actions still serialize IO; current quotas remain30sends/min,30syncs/min and120acks/min per account.

The2.8and earlier sections below are historical unless included in current results above.

## Account move and inactivity controls (2.8.0/code34)


| Check | Current result |
| --- | --- |
| Actual handler/SQL/JWT suite |66passed22.138s. Includes all30protected actions’ auth/proof/session matrix plus device/legacy/password/contact/group/rate/cascade cases; one-use atomic move, competitor receiver, collision, expiry, signed nonce/retry, rollback/finish race, temporary key rejection, Never/foreground activity/scheduled erase and new grants/search-path/projection checks and abandoned receiver counter cleanup. |
| Edge runtime |Entry-point check passes;5Deno runtime tests pass24ms including delegated current-device signatures and rejection of temporary authorizer content signatures. |
| New Android controls |9passed22.878s using real Android Keystore/HPKE/local encrypted storage with isolated SQL and mocked managed Auth. Covers private-history/deletion-marker/old-backup continuity, delegated direct delivery/peer refresh, pending group outbox, wrongpassword, occupied receiver, interrupted persisted resume, unconfirmed rollback, source retention until history save, abandoned local receive safety, failclosed unsupported biometrics/fakeextras, selfdestruct dialog/currentpassword, actual local expirycleanup, real QRdecode and hiding on pause, and rejection of deeply nested signed transfer records before adoption. The final pass includes the backend-readiness safeguard and provider-independent local cleanup. |
| Existing Android regressions |43actual tests passed in four isolated batches:UiSmoke6+Security5+QR3+NFC3=17/58.088s;Actions6+Approval2=8/15.807s;Groups3+Backup4=7;Relay4+CryptoStorage4+Device1+NotificationRecovery2=11/8.896s. No quotas relaxed. |
| Startup/layout |Notification grant and denial each pass (one actual case per phase; other opt-in method skipped). Six screen/font/theme configurations pass including new transfer/settings controls, real keyboard/composer, rotation and visible send.66synthetic screenshots retained privately. A timing failure after rotation was fixed by waiting for layout/insets to settle before asserting geometry; the complete matrix then passed. |
| Builds/JVM/lint |42JVM tests pass; normal debug, optimized secure-test and unsigned release builds pass. All three full lint reports have0errors/fatal findings and17maintenance warnings. Final secure-test/release wording rebuild passed80s; lint remains0errors/17warnings. The normal strict-device test passed; final installed optimized APK denies run-as and all7private external starts and retains secure overlay/screenshot flags. |
| Packaged artifact |REA/JADX final APK confirms private transfer screen, strict biometric Keystore/CryptoObject/generation/lifecycle guards, signed history-save finish and unconditional normal device policy. System-only CA/no-cleartext and backup rules decoded via AAPT2. Both payload scans clean of privileged keys/private PEM/service-role JWTs/fixture address or token. BIP39 MIT notice packaged. v2 signature verifies and matches2.7.1 development certificate; unsigned release remains unsigned. |
| Live denial/config checks |30protected routes rejected missing JWT;10malformed pre-login operations failed before SQL;3anonymous REST/schema/Auth-user probes denied. Public signup disabled; only internal password provider enabled.43negative requests total, no valid owner proof/token. |
| Live integration |Two synthetic runs of38requests each passed real managed Authv2, policy7days/Never roundtrip, wrong password, transfer/rollback, encrypted capsule authentication, exact accept retry, preserved UID/fingerprint/queue, delegated signature, current-signer finish, source acknowledgement and finish retry. Only their own four test accounts were deleted. These use opaque test message envelopes; genuine Android HPKE/history behavior is covered in the local9, not claimed as a physical live biometric test. |
| Remaining validation |Positive fingerprint/face approval on two physical phones, actual provider file sharing/camera/OEM lifecycle, optimized authenticated physical flows and actual two-phone biometric/file handoff are not claimed. |

A live cleanup review reproduced a retained abandoned-receiver counter; separately approved012 fixed it. The baseline regression failed1vs0, all66tests passed afterward, and the final live run/SQL query verified its removal. Earlier anonymous unlinked counters retain normal expiry; unidentified records were not bulk-deleted.

Intermediate failures are retained privately: an old SQL alias/type annotation/test-row click issue; new expiry test exposed stale in-memory vault references (fixed); an initial regression runner used three incorrect class names (corrected); old synthetic storage profiles used name-prefixed IDs and were updated to valid UUIDs without weakening production validation. No production protections or quotas were relaxed to make tests pass. Test-only `/qa/account-expire` exists only in the local fixture behind explicit environment flag and is never in deployed functions/APK.

Reproduce controls with Java21/SDK/emulator configured: `PRIPERI_ACCOUNT_TESTS=yes PRIPERI_TEST_CLASS=com.priperi.AccountControlsFlowTest node backend/qa/android-fixture.js`. The flag permits expiry of only the caller's synthetic fixture account. Never target real users or production with fixtures. `npm test`, `npm run check`, and `npm run test:edge` run in backend/supabase. Evidence remains private in local-only/evidence/v2.8.0.

Final secure-test SHA256 `1581e578049bb5d625ce852dff3f279abaaf434e91c3218ed555e7c5e48f080b`; unsigned release SHA256 `2565e5a9696c7dda0123b53c5609e20b3befce8bbafb4dfe84ce13d361a853ca`. Each is about2.1MB. Final packaged evidence is in apk-review/packaged-*; earlier snapshots have different digests and are explicitly pre-final. Later APK-only change clarifies hourly deletion depends on service availability; Free-plan pausing/outages can delay it. No paid upgrade or keepalive was configured.

The following records are historical and do not claim they were rerun for2.8 unless included in the current results above.

## Security review (2.7.1/code33)

The review used decompiled shipped APKs, manifest/resource inspection, actual handler/PostgreSQL requests, disposable Android runtime checks and bounded read-only hosted probes. Burp Suite itself was not run; untrusted-proxy and redirect capture tests used local servers with synthetic credentials. Feature10 UI styling was not implemented. See SECURITY-REVIEW.md for findings, operation-by-operation coverage and residual attack paths.

| Check | Result |
| --- | --- |
| Backend authorization / actual SQL |52 passed in16.544s. Nine added tests cover limiter namespace, retained direct queue rows, expiry crossing after Edge validation, migration preservation/private grants/optional helper and full protected-operation auth/actor matrix. Existing device/password/reset, ownership, signatures, replay, quotas, contact/group/admin/recipient/cascade cases pass. |
| Edge runtime / dependencies |Entry-point type check and4 Deno runtime tests passed. Production npm audit reported zero published advisories in the resolved server dependency set. This is not an Android/native dependency audit or proof of no unknown CVEs. |
| Android security reproduction |The old dialog behavior failed both baseline lifecycle and modal FLAG_SECURE tests. Fixed isolated run:5 passed6.593s, including late-dialog gating, password-input clearing, self-signed TLS rejection before HTTP and redirect capture remaining zero. Initial redirect test expected a structured302 exception; Android returned IOException for its empty error stream. Correcting the test expectation retained the no-forwarding assertion. |
| Affected Android flow regressions |32 unique tests across isolated runs: UiSmoke6+Security5+QR3+NFC3 passed68.764s; MessageActions6+ContactApproval2 passed13.572s; Groups3+Backup4 passed26.519s. No quota was relaxed. Tests use disposable local SQL/identities and do not create hosted accounts. |
| Normal strict policy |1 passed1.178s with liveHTTPS/ALLOW_TEST_DEVICES=false. Fake intent extras cannot unlock the emulator or make requests. |
| Actual optimized secure-test APK |Installed over the test package; `run-as` denied as non-debuggable. All six private Activity launches from external ADB denied as non-exported. Exported launcher still blocks emulator despite fake login/test extras; dumpsys confirms SECURE and HIDE_NON_SYSTEM_OVERLAY_WINDOWS. No owner installation was modified; this was the disposable emulator. |
| Artifact static review |REA/JADX of final secureTest verifies private screens/HCE permission, system-only CA anchors, no cleartext/debug emulator exception, unconditional root/emulator/VPN/debugger policy, secure dialog windows and tracked pause/gate handling. Archive scan found no privileged Supabase/service-role/private PEM or fixture credentials/URL. Public project key remains recoverable as intended. Minification confirmed against R8 mapping. |
| Builds / JVM / lint |Normal debug, secureTest and unsigned release built;35 JVM passed. Initial full build54s; additional full secureTest/release lint4s. All three lint reports:0 errors,17 maintenance warnings. Shared configs DEBUG/ALLOW_TEST_DEVICES are false for secureTest/release; debug QA outputs are not distributed. |
| Hosted negative/config checks |All23 protected operations rejected unauthenticated requests;4 malformed pre-login routes rejected before database access;3 anonymous REST/schema/Auth probes denied. Public signup/anonymous/phone/OAuth disabled; internal email/password provider intentionally enabled. No production token or real owner ID used. |
| Signing / packaging |Secure-test certificate verifies and matches2.7.0 dev, preserving the registered test installation/Keystore on update. This is still a development certificate; unsigned release awaits owner production signing. Old2.7.0 artifacts retained locally. Secure-test SHA256 `0ad2c270e0fc1ab0a811e8bef018facb735fc7e4f07f4266d0e6ce5192cd8df2`; unsigned release SHA256 `72b4186818934c0da99efa01f6a0e09c5562e2389b7a22c96d9cf5fa39429577`. Clean source/deployment exports and full hashes accompany the APKs. |

Private evidence: `local-only/evidence/v2.7.1/`. Static/decompiled evidence is not proof of every runtime path; the optimized APK's private authenticated flows were statically inspected while synthetic functional tests used the unminified QA variant. No real Burp/Frida/root/physical-device campaign, volumetric load test, independently audited protocol, or production signing was completed. One legacy account still requires owner sign-in on its registered phone; physical OEM/NFC/Drive/battery validation remains pending. These limits are retained rather than labelled secure without evidence.

Reproduce local checks with `npm ci --ignore-scripts && npm test && npm run check && npm run test:edge` in `backend/supabase`, plus the Gradle wrapper tasks above. For the isolated Android security class use `PRIPERI_TRANSPORT_AUDIT=yes PRIPERI_TEST_CLASS=com.priperi.SecurityBoundaryTest node backend/qa/android-fixture.js` with Java21/SDK/emulator configured; this explicitly opts into disposable QA and never belongs in a shared APK. The hosted probe requires `PRIPERI_HOSTED_READ_ONLY=yes node backend/qa/hosted-security-readonly.js` and sends only non-mutating negative requests to the configured project. Do not use raw test fixtures against production.

## Battery/storage completion and message actions (2.7.0/code32)

The software work for features7–8 adds offline gating/reconnect wake-up, idle-list pacing, revision-based UI refresh and encrypted-database reclamation/compaction. Feature9 adds reply references, edit/delete requests and local deletion to direct/group chats through the existing signed encrypted relay. No hosted API, backend code, migration, secret, service or permission changed. Both phones require2.7.0 before using the new content frame; downgrade is unsupported.

| Check | Result |
| --- | --- |
| Existing Node/backend actual SQL/authorization |43 passed14.843s. Source unchanged; hosted state was not accessed. |
| Final message actions/group/storage |10 passed12.672s after content compatibility handling. Earlier final terminal-control cleanup run also passed10 in11.242s. Covers direct/group encrypted edits, reply IDs, deletion, current epoch/author/chat/window rejection, out-of-order/delete-wins, invalid API controls durably ignored, unsupported signed frames saved as placeholders with original ciphertext, old-backup local-deletion resistance, pending-outbox retention, menu/rotation behavior, authenticated reopen and compaction. |
| Leaving the chat after Save |1 passed4.730s with the worker deliberately held until the screen paused; the encrypted change still reached the server. |
| Contact approval/delivery |Actions5+Approval2+Latency1 passed16.747s after fixing revision observation. Local deletion cannot recover an unapproved send slot. Latest foreground sample3777ms; earlier3906ms. These are isolated emulator timings,not production guarantees. |
| Backup/private UI/offline |Backup4+UiSmoke6+Battery1 passed70.129s. Includes existing same-key/atomic archive behavior, password/device login, private-screen denial, settings and actual network loss/recovery. |
| Group/direct/crypto regressions |Group3+Relay4+CryptoStorage4 passed9.070s. Targeted group/action cases were also repeated after later compatibility/cleanup changes. |
| Six final layouts |15.225/18.195/19.871/14.038/19.704/16.040s:compact light,phone dark,large light,landscape dark,tablet light,compact1.8-font dark. Includes edited/pending status,deleted placeholders,replies to deleted content,reply draft rotation/keyboard/send bounds,groups,backup and combined NFC/QR.60 synthetic previews. |

The offline test observed zero sync starts over8.5seconds with networking disabled, then automatic sync on reconnection. Open chats retain the4second fallback; the quiet list uses8seconds after unchanged results. This verifies scheduling behavior,not a measured battery percentage. A synthetic older database measured315392bytes before deletion/compaction and reclaimed266240bytes while preserving its saved row,identity and receive digest. This is one fixture,not a universal storage saving. Physical battery/storage/thermal/battery-saver/OEM profiling remains pending.

Testing found and fixed a networkLost race,refresh suppression of changes saved by another worker job,stale edit-text retention on local deletion and authenticated invalid-control reprocessing. The first action setup tried approval before creating a message request; it was corrected. A UI test clicked Save before the dialog’s asynchronous OnShow hook; the test now waits for that hook. Raw failed/intermediate evidence is retained. No production quota was relaxed. Six-case geometry checks preceded only content compatibility handling,which did not change layout.

Final normal debug/release/unit/lint passed21s:35 JVM cases,zero errors/17 maintenance warnings. Normal strict-device policy passed1.619s. Final shared configurations use liveHTTPS/ALLOW_TEST_DEVICES=false. REA manifest/aapt2 checks confirm unchanged permissions,private app screens,signature-protected HCE and backup/cleartext/release debugging disabled. APK scans found no actual private PEM,privileged backend key/service-role JWT,fixture token or fixture URL. The development v2 signature verifies and matches2.6.1; optimized release remains unsigned. Static/testing evidence is not a zero-vulnerability guarantee. New backups exclude deleted/hidden controls and preserve signed visible edit/reply metadata; external archives/clipboard/other clients cannot be remotely erased. Actual two-phone NFC and Drive/physical-device validation,production signing and an independent protocol assessment remain pending. See SECURITY.md for the advisory client time windows,opaque relay,group membership and recovery limits. Evidence:local-only/evidence/v2.7.0/.

## Inline NFC and QR correction (2.6.1/code31)

NFC Share and Read are embedded in a compact card directly above the username and QR code in the same scrolling contact page. QR/NFC tabs and the separate NFC view are removed. Opening the camera scanner revokes NFC consent; returning restores idle controls, never automatically sharing. The transport, 90-second grant, confirmation/relookup, device/account checks, permissions and backend are unchanged.

The updated NFC3/QR3 Android cases passed15.817s. They verify the absence of tabs, both NFC controls on the QR page, scanner cancellation/return, pause/recreation, no-hardware fallback, confirmation/no implicit verification, wrong-key/self rejection and the existing QR decoding/rename/block/camera lifecycle cases. Six responsive cases passed17.174/17.808/19.180/18.460/20.123/17.135s for compact light,phone dark,large light,landscape dark,tablet light and compact1.8-font dark. Each checks reachable NFC Share/Read and QR Scan on the same page,plus existing chat/keyboard/group/backup layouts. Sixty current synthetic previews are retained;12 stale previews from the prior emulator run were removed after pulling. Normal debug/release/unit/lint passed22s:28 JVM tests,zero errors/16 maintenance warnings. Strict normal device policy passed1.851s. Both shared builds use liveHTTPS/ALLOW_TEST_DEVICES=false. Final REA manifest/aapt2 checks confirm unchanged permissions,private screens,signature-protected HCE,nonpayment AID and unlocked-device requirement; backup/cleartext/release debugging stay disabled. APK payload scans found no actual private PEM,privileged server key/service-role JWT,fixture token or fixture URL. Development v2 signature verifies and matches2.6.0; optimized release remains unsigned. These targeted checks are not a guarantee against all vulnerabilities. Actual two-phone RF exchange and OEM routing remain pending. Evidence:local-only/evidence/v2.6.1/. Backend results below are historical; no cloud changes or hosted data access were needed.

## NFC inside the QR screen (2.6.0/code30)

QR and NFC share the existing private Add a contact screen; the inbox/New chat layout is unchanged. One person chooses Share and the other Read. Sharing uses a 90-second foreground, account-bound memory grant. The reader resolves the public identity/key through the current authenticated relay and asks for confirmation/rechecks before saving; NFC does not grant verified status. No backend deployment or cryptographic change.

- Seven new JVM cases cover exact custom AID/commands, two-round-trip public identity, bounded/malformed/oversize/status/URL/version/key rejection, lost transport, lock/logout/owner mismatch/expiry/clock rollback/process restart, and revocation/renewed-share connection isolation. Total28 JVM cases.
- Six NFC/QR Android cases passed59.615s. NFC cases cover both roles together inside QR, no-hardware fallback, QR switch/pause/recreation revocation, explicit confirmation/cancel, no implicit verification, wrong-key/self rejection and a late paused callback. The QR cases retain real bitmap decoding, username-rename lookup, wrong-key/block rejection, camera-denial lifecycle handling and confirmation before pinning. Callback inputs are synthetic, not RF/optical hardware capture.
- Final NFC plus signed-out private-screen check:4 passed33.303s after the final disabled-control styling. The test lifecycle was updated to resume before closing a paused ActivityScenario, avoiding an artificial45-second runner wait. There were no failed NFC cases. Signed-out checks cover all six private screens.
- Six screen/font cases passed:compact light16.357s,phone dark18.854s,large light19.127s,landscape dark13.705s,tablet light18.864s,compact1.8-font dark15.470s. Sixty synthetic previews include QR/NFC, reachable Share/Read, keyboard/composer and existing group/backup screens. Layout checks preceded only a disabled-control opacity adjustment; geometry stayed unchanged.

Final normal debug/release/unit/lint passed21s:28 JVM cases,zero errors/17 maintenance warnings. Normal StrictDevicePolicyTest passed1.347s with liveHTTPS and ALLOW_TEST_DEVICES=false. REA inspected the final optimized manifest; aapt2 confirmed the packaged custom AID,nonpayment category and requireDeviceUnlock=true. Only NFC was added to the permission set. HCE is exported only with BIND_NFC_SERVICE; private screens remain non-exported and release debugging,automatic backups and cleartext are disabled. Both APK payload scans found no actual private PEM,privileged backend key/service-role JWT,fixture URL or fixture token. Development v2 signature verifies and matches2.5.2 for an update; the optimized release remains unsigned. These targeted checks do not prove absence of all vulnerabilities. Physical two-phone RF exchange, antenna alignment, device screen-lock routing and OEM support remain pending. Optional NFC/HCE hardware declarations do not require an NFC phone to install the app. No hosted accounts/data were accessed for this feature; prior backend results below remain historical. Private evidence:local-only/evidence/v2.6.0/.

## Prior local-storage patch (2.5.2/code29)

New/updated message records use bounded per-record zlib compression before existing authenticated encryption. Compression runs only at1KiB or larger and only if the complete frame saves at least64bytes. Legacy JSON remains readable; no bulk history migration, new permission, server deployment, network compression or cryptographic change.

- Normal builds/unit/lint passed23s:21 JVM cases (including6 codec tests),0 errors/16 maintenance warnings. Codec tests cover legacy/Unicode/random/small data,1MiB bounds,malformed lengths,corruption,truncation/trailing data,unknown versions,dictionary/concatenated streams and large19-copy outboxes.
- Android storage1,backup4 andgroup2 cases passed in the combined26.199s run. Two of its4 direct-chat setup probes reached the isolated fixture's shared account-creation limit; a fresh fixture then passed all4 direct cases in2.128s. No production quota was changed. Final normal strict policy passed1.293s.
- Storage test checks mixed legacy/new encrypted rows,closed-vault reopen,receipt/dedup,backup merge and authentication rejecting tampered ciphertext before decompression.
- Synthetic Android record:old ciphertext10291bytes (10262JSON+29encryption overhead),new4863bytes:about53% less. Synthetic19-copy group outbox with independent random base64 ciphertexts:307373bytes to235354bytes,about23% less before the same encryption overhead. These are record samples,not a whole-database or typical-phone percentage. SQLite may reuse free pages without shrinking its file.

Shared builds use liveHTTPS/ALLOW_TEST_DEVICES=false. Final manifest permissions match2.5.1,private screens and backup/cleartext/release-debug protections remain. Private/privileged-key and fixture scans are clean. Dev v2 certificate matches2.5.1. Evidence:local-only/evidence/v2.5.2/. Physical storage/battery profiling and production signing remain pending. Hosted/backend results below remain historical; no hosted data was accessed for this client-only patch. Older apps cannot read newly compressed rows; downgrade is unsupported. Archive format remains unchanged.

## Prior sync-efficiency patch (2.5.1/code28)

The group outbox reuses the existing inbox history snapshot, removing a second full read/decryption pass per sync. Fully validated, identical group/invitation metadata is left on disk unchanged; actual changes still use the same encrypted atomic write and rollback. Poll cadence, quotas, cryptography and durable-save-before-ack behavior are unchanged. No backend deployment or new permission.

Eight targeted Android cases passed22.193s: groups, direct delivery and contact approvals. The added regression compares encrypted metadata bytes across two idle syncs, then checks that removal changes the file and remains effective after reopening the vault. The existing group checks cover fanout, stale outboxes, key/session preservation, backups and request UI. Normal debug/release/unit/lint passed21s:15 JVM tests,zero errors/16 maintenance warnings. Strict-device policy passed2.150s with liveHTTPS/ALLOW_TEST_DEVICES=false. APK permissions match2.5.0; release screens remain private and automatic backup/cleartext/debugging stay off. Private-key/privileged-key/fixture scans were clean; development v2 certificate matches2.5.0. Evidence is retained in local-only/evidence/v2.5.1/. This verifies reduced redundant work; real-device battery profiling remains pending. Prior 2.5.0 backend/hosted/layout results below are historical and were not rerun for this client-only patch.

## Prior groups and request tab (2.5.0/code27)

| Check | Result |
| --- | --- |
| Backend actual SQL |43 passed; ten group/cache/storage cases plus33 prior cases. Includes invitation ownership, role/admin/outsider denial, exact signature/recipient/epoch binding, retry/ack/removal/block/deletion, concurrent capacity, grants, expiry, roster rename/cache and expired-row storage caps. |
| Deno |Entry-point check and4 runtime cases passed, including the group context signature. |
| JVM |15 passed. |
| Dedicated Android backup/group |6 passed in19.076s after excluding stopped unsent group drafts from archives. Covers wrong password/corruption/cancellation/atomic merge, provider lifecycle, keys/settings/session preservation, real HPKE fanout/decryption, stale epochs, group backups and explicit request acceptance/admin affordances. |
| Ordinary Android |18 actual passed in67.310s;37 reported includes19 opt-in skips (before the later opt-in hosted-group test was added). |
| Startup notifications |Allow3.772s/deny3.465s, one actual each. |
| Six layouts with groups |Compact light14.422s; phone dark16.946s; large light17.244s; landscape dark15.170s; tablet light22.275s; compact1.8-font dark14.656s. 48 synthetic previews include group chat/details. Later cache/icon/export changes did not change geometry. |
| Hosted group |Complete isolated case passed39.640s: owned invitations, role denial, fanout, no old copies for a new member, stale draft pause, no retry resurrection, removal and renamed member/pinned key. |
| Hosted direct approval/FCM |Both passed in the earlier combined74.404s run; that run's group case failed on an expected404 receiving503. No hidden test retry; isolated group rerun passed. Generic background delivery/save-before-ack and opt-out remain. |
| Final normal debug/release/unit/lint |Passed18s;0 errors and16 maintenance warnings. |
| Normal strict-device policy |1 passed in1.312s; live HTTPS and ALLOW_TEST_DEVICES=false. |

Final package manifest review confirms private GroupDetailsActivity/screens/services, no new Android permission, automatic backup/cleartext/release debugging disabled. Actual private PEM, privileged backend keys/service-role JWTs and fixture token/URL scans were clean. Development v2 signing verifies and matches2.4.0 for an in-place update. The optimized release remains unsigned. Production signing, physical/OEM/Drive validation and an independent protocol assessment remain pending.


Evidence is retained in local-only/evidence/v2.5.0/. Earlier failures include a test-vault selection mismatch, case-sensitive dialog automation and a measurement before composer layout settled; fixes and final runs are retained. Provider tests use synthetic selected files; no real Google Drive upload occurred. Group composition assumes the membership service is trusted; see SECURITY.md for limits.

Reproduce dedicated local cases with `JAVA_HOME=/absolute/path/to/jdk-21 PRIPERI_TEST_CLASS=com.priperi.BackupFlowTest,com.priperi.GroupFlowTest node backend/qa/android-fixture.js`; add `PRIPERI_SCREEN_MATRIX=yes` for layouts. Hosted LiveGroupTest is explicitly gated with `livegroups=yes`, live HTTPS and the private QA build. Rebuild without QA flags before sharing. Never run fixture/source-only local servers as the deployed function.

## Prior backup and name refresh (2.4.0/code26)

| Check | Current result |
| --- | --- |
| Backend actual SQL |33 passed, including unblocked contact-name refresh, unchanged fingerprint/approval, and no unrelated/blocked directory shortcut. |
| Deno |3 runtime passed; entry-point check passed. |
| JVM |15 passed, including4 backup codec cases for segmented round trip, salt/fixed-cost KDF, tampering, wrong key, truncation/trailing data, identity/signature substitution and size bounds. |
| Dedicated Android backup |4 passed11.541s: signed merge/dedup, excluded sensitive fields, unchanged keys/settings/session/pending outbox, atomic rollback, wrong password/corruption/cancel, selected-file flow, rotation, cancellation and result-before-resume gating, plus username refresh. |
| Ordinary isolated Android |18 actual passed57.701s;35 reported includes17 opt-in skips. |
| Startup system permission |Allow4.368s/deny4.016s; one actual each. |
| Six layouts including backup screen |Compact light9.760s;phone dark12.456s;large light11.592s;landscape dark8.667s;tablet light11.663s;compact1.8-font dark9.582s. |
| Hosted name/approval and FCM |2 passed42.300s. Rename appeared on the other account, same pinned key/approval; contact cap/block and generic background durable-save/ack/opt-out retained. |
| Normal builds/unit/lint |Passed21s; zero errors,16 maintenance warnings. |
| Normal strict device policy |1 passed1.733s; shared APKs use live HTTPS and ALLOW_TEST_DEVICES=false. |

The provider-file test uses an app-owned synthetic MediaStore document and instrumented Android document-picker results. It verifies the actual content-resolver streams and lifecycle/gate paths; it does not upload a real file to Drive. The first picker monitor lacked matching MIME/category entries and timed out; correcting the test monitor made all four cases pass. **Google Drive/OEM/physical-phone validation remains pending.** Thirty-six synthetic layout previews are private evidence; no human message or Google account was used in these tests.


Final REA manifest review confirms non-exported BackupActivity, private app screens/services, backup/cleartext/release debugging off and no new permission. Both APK scans found no actual private PEM, privileged key/service-role JWT, fixture token or fixture URL. Dev APK v2 certificate matches2.3.0; release remains unsigned. Raw logs/reports/mapping and UI evidence:local-only/evidence/v2.4.0/.

Reproduce dedicated checks with `JAVA_HOME=/absolute/path/to/jdk-21 PRIPERI_TEST_CLASS=com.priperi.BackupFlowTest node backend/qa/android-fixture.js`. Add `PRIPERI_SCREEN_MATRIX=yes` for the six cases. Rebuild without QA overrides before sharing. The normal no-session UI regression includes BackupActivity. Backup/restore is for the same registered account/key material; it is not reinstall/phone-loss recovery.

## Prior contact approval (2.3.0/code25)

| Check | Current result |
| --- | --- |
| Backend/actual SQL |32 passed, including atomic concurrent cap, recipient-only approval, delivery/receipt-deletion/rename/retry resistance, blocks, JWT/session/proof rejection, bounded state lists, RLS/grants, deletion cascades and migration preservation/backfill. |
| Deno runtime/check |3 passed; entry-point check passed. |
| JVM |11 passed, including queued/confirmed allowance calculations. |
| Ordinary isolated Android |18 actual passed62.115s;31 reported includes13 opt-in skips. |
| Startup system permission |Allow3.304s and Don't allow3.493s; one actual test each. |
| Dedicated approval Android |2 passed3.860s; also passed7.337s with the final six-layout matrix. |
| Six layouts with request banner |Compact light7.800s;phone dark9.337s;large light12.317s;landscape dark6.876s;tablet light10.607s;compact1.8-font dark8.583s. |
| Hosted approval and FCM |2 passed34.472s using only disposable synthetic accounts. |
| Normal final builds/unit/lint |Passed14s; zero errors,16 maintenance warnings. |
| Normal device policy |1 passed1.321s; ALLOW_TEST_DEVICES=false/live HTTPS. |

Android checks cover three offline queued slots, fourth-message rejection, legacy outbox parking/resume, lost-success-response recovery at the cap, chat-clear resistance, old local-history compatibility, approval cancellation/confirmation and subsequent delivery. The six-case matrix includes an incoming request banner, long messages, rotation, keyboard bounds and large fonts. Physical phones/OEM keyboards/camera behavior remain pending.

The hosted test sent three encrypted messages, attempted a fourth through the API despite the client cap, delivered/acknowledged the three, verified the fourth remained rejected, rejected sender self-approval, then accepted a fourth after recipient approval. Blocking still rejected sends. The FCM test verified generic content, actual background durable-save-before-ack and opt-out. Its first run followed a permission-denial test and lacked notification consent; after granting consent only on the disposable emulator, both hosted cases passed. Test accounts cleaned their own data.


Testing found and corrected an approval-banner layout loop and an old-history request deadlock. Multi-account receipt stress probes were serialized on the app's worker to prevent delayed UI cleanup changing their session mid-probe. An optional preview-copy error in the harness was fixed; its earlier matrix test cases themselves passed. Raw failed/intermediate and final logs remain in local-only/evidence/v2.3.0/.

Final APK manifest review: only launcher app activity exported, private chat/QR/settings/services remain private, backups/cleartext/release debugging disabled, no added permission. Both APK scans found no actual private PEM, privileged key/service-role JWT, fixture token or local fixture URL. Development APK v2 signing verifies and retains2.2.0's certificate; optimized release remains unsigned. Source unchanged after final builds.

Reproduce dedicated approval tests with `JAVA_HOME=/absolute/path/to/jdk-21 PRIPERI_TEST_CLASS=com.priperi.ContactApprovalTest node backend/qa/android-fixture.js`. `PRIPERI_SCREEN_MATRIX=yes` adds the six layout cases. Hosted test is opt-in `LiveContactApprovalTest` with `-e liveapproval yes` in a private live-HTTPS emulator QA build; never share that build. Ordinary full runs preserve server quotas and skip opt-in tests. Rebuild normally before sharing.

## Prior QR feature (2.2.0/code24)

Feature1 from features_to_add.md adds an inbox Add by QR button, your public contact code and an in-app scanner. Optional camera access is requested only on Scan a code. The non-exported private screen retains screenshot/device/session guards and pauses the camera on background/security lock. Strict parsing rejects arbitrary URLs and malformed codes before network access. Lookup uses UID so a username rename does not invalidate the code. UID, public fingerprint and recomputed key fingerprint must match; local blocks/changed pinned keys are rejected. Contact pinning requires confirmation and an independent recheck.

Nine JVM tests pass, including strict code round trips, malformed/extra/version/URL rejection, self/substitution/key mismatch. Three isolated QR Android tests passed in5.970s: real displayed QR bitmap decoding; lookup after rename and rejection of wrong keys/blocks; permission-denial lifecycle return; confirmation before contact pin; opening chat only after acceptance. Camera callback inputs are synthetic; optical scanning on physical cameras remains unverified.

Existing isolated Android regressions:18 actual passed47.871s (28 reported including10 opt-in skips); startup Allow3.758s and Don't allow2.969s (one actual each). Three current layout cases passed:compact light7.880s, phone dark9.830s and landscape dark7.240s. The prior six-case matrix below remains historical2.1.2 coverage. QR tests run in a fresh separate fixture:putting their extra account setups into the ordinary full suite exhausted the unchanged production creation quota. No server quota was relaxed.

Final normal debug/release/unit/lint passed15s (zero errors,16 maintenance warnings). StrictDevicePolicyTest passed1.523s with QA exception disabled. APK manifest review confirmed private QR activity, no generic library capture activity and backup/cleartext/debugging disabled in release. Both APK scans found no private PEM, privileged key or fixture payload/URL. Development APK v2 signature verifies and retains the2.1.2 certificate. Artifact hashes and raw evidence are retained in local-only/evidence/v2.2.0/. No hosted account or backend deployment was touched. The only new permission is optional CAMERA; the library's generic CaptureActivity is removed from the merged manifest. Production signing and physical-phone validation remain pending.

Reproduce QR cases using `JAVA_HOME=/absolute/path/to/jdk-21 PRIPERI_TEST_CLASS=com.priperi.ContactCodeFlowTest node backend/qa/android-fixture.js` against a disposable API35 emulator. Never share that fixture APK; rebuild normally before packaging.

## Prior UI patch (2.1.2/code23)

The inbox reload button is removed; automatic foreground/push sync remains. The send control has a 40dp rounded-square visual inside a 48dp tap target, ripple/press feedback and a short send animation. It respects disabled system animations. Drafts survive rotation only in memory, duplicate saves are disabled and a newly typed draft is not erased by the previous send completion.

Forms/settings are centered and bounded on large screens; compact layouts use smaller outer margins. Message widths, usernames, timestamps and unread badges adapt to available space. Keyboard/system/cutout insets are preserved. Very short landscape keyboard layouts temporarily collapse the full chat header and reduce composer padding/line count to retain message space. All essential settings remain scrollable.

| UI configuration (emulated API35) | Logical window / font scale | Result |
| --- | --- | --- |
| Compact light | 320×569dp / 1.0 | Passed |
| Phone dark | 360×740dp / 1.3 | Passed |
| Large phone light | 412×915dp / 1.0 | Passed |
| Landscape dark | 640×360dp / 1.3 | Passed |
| Tablet light | 800×1280dp / 1.0 | Passed |
| Compact large text dark | 320×569dp / 1.8 | Passed |

Each configuration checks first setup, inbox, chat, settings, a real visible keyboard, a fully reachable send/composer, at least24dp message-history height above the keyboard, rotation-retained drafts and a single durable send without erasing the next draft. Thirty synthetic preview images are retained privately. These emulated checks support responsive behavior; they do not establish compatibility with every physical phone, keyboard, foldable, OEM or Android version.

The full isolated regression run passed18 actual tests in40.972s (25 reported, seven opt-in skips), plus startup permission grant3.677s and denial3.873s (one actual test per phase). The final six-case layout matrix was rerun after the last widget/layout adjustment. Final normal debug/release builds, unit tests and lint passed in15s; lint has zero errors and13 maintenance warnings. Six JVM tests passed. Normal-build StrictDevicePolicyTest passed in1.445s with ALLOW_TEST_DEVICES=false. The packaged release manifest was inspected with REA: backup/cleartext/debugging disabled, private components not exported, no added permissions. Both APK payload scans found no actual private PEM, privileged backend key/service-role JWT, fixture token or local fixture URL. Normal build configurations retain live HTTPS and disable the QA device exception. The development APK v2 signature verifies and retains the2.1.1 signing certificate for an in-place update. The optimized release remains unsigned.

Current development SHA256: `8c79f13c09329d0bcbcc090693cc84f419a0978f0c851b5c2d2dfd881425b13d`. Current unsigned release SHA256: `c1e7475ab99e00fdac5f4528b8ebbb1138b33cf7c5ec0a4597f088dd96153f08`. No live account, backend configuration, API, permission or cryptographic protocol changed for2.1.2. The hosted/backend results in the next section belong to2.1.1 and were not repeated for this UI-only patch.

## Prior backend release results (2.1.1)

| Check | Result |
| --- | --- |
| Node/backend | 28 passed, including actual production SQL in isolated PGlite and real SDK JWT-signature checks. |
| Deno | 3 runtime tests passed; relay entry-point check passed. |
| JVM | 6 passed: validation, representative device signals and rolling sync budget. |
| Android local fixture | 18 actual tests passed in 48.237s. The runner reports 24 because six opt-in live/strict/startup tests are skipped. |
| Real Android startup permission | Grant phase passed in 5.014s; denial phase passed in 4.002s. Each phase reports two tests, with one skipped. Total two additional actual tests. |
| Normal-build device policy | 1 passed in 2.197s, with ALLOW_TEST_DEVICES=false. |
| Hosted encrypted messaging/Auth | LiveRelayTest passed in 21.760s. |
| Hosted FCM/background save | LivePushTest passed in 17.019s. |
| Final normal builds/unit/lint | Passed in 19s; lint zero errors/fatal findings, 13 maintenance warnings. |

The **20 actual isolated Android tests** include encrypted storage/keys, device/password login, rename/search/reset, message delivery, save-before-ack, replay suppression, receipt backpressure, batched receive, receiving despite an unavailable outgoing contact, UI light/dark modes, invalid-session redirection without account erasure, notification grant/cancel return, a busy private-screen gate, and the two real system-permission prompt phases. The staged permission checks run in separate processes because revoking an Android runtime permission can terminate its process.

## Notification and delivery regressions

- First launch on Android 13+ requests the system notification permission once. Both Allow and Don't allow returned to Continue; subsequent launch did not ask again. Declining keeps messaging available. The preference is applied after sign-in; existing explicit in-app opt-outs are preserved.
- The settings grant/cancel regression confirms the screen becomes usable after lifecycle return. A deliberately blocked account worker shows a visible loading gate while private content stays hidden; it restores content after the check. Token retrieval no longer blocks that worker.
- A foreground message appeared automatically in **3.681 seconds** in the isolated final run, including simulated relay transport and crypto/storage work. This is one local measurement, not a production latency guarantee. The previous fallback interval was 15s; the new single foreground coordinator polls 4s after completion and can sync immediately on push/send, subject to a rolling budget and unchanged server quotas.
- Live FCM verified generic alert content, no sender/message text, durable encrypted background save and subsequent empty server inbox without opening the chat, plus opt-out suppression. Earlier 2.0 cold-process, permission-denial and sign-out lifecycle checks remain historical coverage; they were not all repeated for 2.1.1.

## Backend and managed Auth checks

Coverage includes wrong device/password, revoked/forged JWTs, issuer/audience/expiry checks, recent-password requirements, unique usernames, bounded search, key/signature/ciphertext tampering, blocks, write races, queue cap/expiry, resets/signature expiry/replay, abandoned setup cleanup and deletion retries.

New batch tests reject unauthenticated/forged access, foreign-recipient IDs, invalid/duplicate IDs, oversized batches and exhausted per-message quotas. Each acknowledgement retains its individual atomic receipt-and-deletion transaction and session/recipient checks. Batch retries cannot resurrect ciphertext. Non-string and inherited-object operation names are rejected explicitly.

New managed credential tests verify that the human password alone is not the Auth credential, private-device proof is required, bound accounts cannot fall back to legacy password-only authentication, and old unbound token versions are rejected. Isolated tests cover legacy credential upgrade after correct device proof/password. The live test verified new-account direct Auth login with the human password is rejected, then exercised token refresh, sign-out/revocation, rename/password change, encrypted delivery, acknowledgement/deletion and cleanup.

**Existing-account migration remains user-driven:** owners must sign in once on the registered phone. Two legacy accounts remained at the final hosted check. Their old managed password is not changed without that successful sign-in. See SECURITY.md for the remaining availability risk before migration.

The live private schema still has seven RLS tables and seven service-only app RPCs, with zero anon/authenticated RPC execution rights. The update required no database migration or new server secret. Hosted tests preserved the two pre-existing profiles/Auth users and four existing receipts. Test-named profiles, pending accounts, queued messages, reset challenges, orphan Auth users/sessions/refresh tokens were zero afterward.

## Prior APK review (2.1.1)

Final optimized release: version 2.1.1/code22, minimum 26, target 36. SHA256 `96f8ce7079c6a10fe3c17f7fd4f60821ac1566f13579d881909d5221f1a6b08d`.

REA/JADX reviewed the complete merged manifest and the release device-policy method. Only the launcher is an exported app activity; private screens/services/providers are not exported. Exported library receivers require Google SEND or Android DUMP permissions. Backups/cleartext transport are disabled, release is not debuggable, and release code has no emulator-test exception.

Archive scans found no actual private PEM, privileged Supabase key/service-role JWT, private-key filename or fixture token. Public Supabase/Firebase connection identifiers are expected. The development APK's v2 signature verifies. The optimized release is unsigned; REA did not verify a release signing certificate. Static scans and passing tests do not prove absence of every secret or vulnerability.

The development APK retains the previous2.1.0 development signing certificate, so it can be installed over that build while preserving private keys and history. Uninstalling or clearing app data is not part of the upgrade.

## Isolation and reproduction

Node SQL tests use PGlite with a modeled Auth schema. The Android fixture uses the production relay/SQL and a managed-Auth stand-in, bound only to host loopback. It contains no production credential. Local Android builds intentionally permit the disposable emulator and use a debug-only 10.0.2.2 HTTP exception. Hosted checks use private debug QA builds with live HTTPS; **never share those QA APKs**. Shared debug/release artifacts have ALLOW_TEST_DEVICES=false and live HTTPS settings.

```sh
cd backend/supabase
npm ci
npm test
npm run test:edge
npm run check
npm audit
```

From the project root, with a disposable API35 emulator:

```sh
JAVA_HOME=/absolute/path/to/jdk-21 node backend/qa/android-fixture.js
JAVA_HOME=/absolute/path/to/jdk-21 ./priperi/gradlew -p priperi \
  :app:assembleDebug :app:testDebugUnitTest :app:lintDebug \
  :app:assembleRelease :app:lintRelease
```

The fixture includes separately staged real startup-permission grant/deny checks. For hosted testing, build/install private debug APKs with `-PpriperiTestDevices=true`, then opt into LiveRelayTest with `-e live yes` and LivePushTest with `-e livepush yes`. Use the com.priperi.debug.test/androidx.test.runner.AndroidJUnitRunner runner. Each test creates/deletes only its own synthetic accounts. Rebuild without overrides before sharing. StrictDevicePolicyTest uses `-e strict yes` on the normal build.

Raw 2.1.1 logs, dashboard screenshots and retained APK evidence stay in ignored local-only/evidence/v2.1.1/. Current UI/build evidence is in local-only/evidence/v2.1.2/. Public synthetic UI previews and this summary are included in the source export. Physical-phone/OEM root/VPN behavior, independent penetration testing, production load and hosted expiry timing remain unverified. See SECURITY-REVIEW.md.
