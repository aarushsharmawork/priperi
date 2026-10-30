#!/usr/bin/env python3
"""Create a complete, sanitized source export and separate per-file notes."""
from pathlib import Path
import base64
import hashlib
import json
import posixpath
import re
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / 'github-ready/Priperi'
ZIP = ROOT / 'artifacts/github/Priperi-source.zip'
COMPONENTS = ('priperi', 'backend', 'docs', 'tools', 'config')
ROOT_FILES = ('README.md', '.gitignore', 'features_to_add.md')
SKIP_DIRS = {'.git', '.gradle', 'build', 'node_modules', '.firebase', '.supabase', '.temp', '.wrangler', '__pycache__', 'ui-previews'}
PRIVATE_SUFFIXES = {'.pem', '.key', '.keystore', '.jks', '.p12', '.pfx', '.log', '.pyc', '.apk', '.zip', '.gz', '.bin', '.png', '.jpg', '.jpeg', '.mp4', '.db', '.sqlite'}
# These are original public project identifiers, not credentials to reuse.
properties=(ROOT/'priperi/gradle.properties').read_text()
project_match=re.search(r'https://([a-z0-9]{20})\.supabase\.co',properties)
ORIGINAL_REF=project_match[1] if project_match else ''
resource_text=(ROOT/'priperi/app/src/main/res/values/secrets.xml').read_text()
project_match=re.search(r'<string name="project_id"[^>]*>([^<]+)</string>',resource_text)
ORIGINAL_FIREBASE=project_match[1] if project_match and not project_match[1].startswith('PASTE_') else ''
# Replace presentation fixture identities consistently, without carrying their original labels.
fixture_text=(ROOT/'priperi/app/src/androidTest/java/com/priperi/UiSmokeTest.java').read_text()
fixture_pairs=list(dict.fromkeys(re.findall(r'profile\("([a-f0-9-]{36})","([a-z][a-z0-9_]+)"',fixture_text)))
FIXTURE_REPLACEMENTS={}
for index,(uid,name) in enumerate(fixture_pairs):
    FIXTURE_REPLACEMENTS[uid]=f'00000000-0000-4000-8000-{index+1:012d}'
    FIXTURE_REPLACEMENTS[name]='demo_'+('alice' if index==0 else 'bob')

JAVA = {
'AccountPolicy':'Reads and changes account inactivity settings and checks when an account is due to expire.',
'AccountRecovery':'Creates, activates, revokes and restores persistent recovery kits using the account service.',
'AccountTransfer':'Coordinates the signed one-time move of an account between two installations.',
'AppNavigation':'Builds the All chats, Groups and Settings dock and switches between those screens.',
'BackupActivity':'Lets the user export or restore an encrypted message-history snapshot through Android file pickers.',
'BackupCodec':'Defines the encrypted message-backup container and its bounded record format.',
'BackupFiles':'Manages temporary message-backup files and deletes stale temporary copies.',
'BiometricGate':'Requests supported strong biometric confirmation before sensitive local account actions.',
'ChatActivity':'Displays direct and group messages, the composer, replies, message actions and chat controls.',
'ContactAllowance':'Tracks the introductory-message allowance before the recipient approves a conversation.',
'ContactCode':'Encodes and checks public contact QR/NFC identity codes and fingerprints.',
'ContactCodeActivity':'Displays a contact code and handles QR scanning and foreground NFC contact exchange.',
'ConversationSurface':'Draws the chat background locally using cached shapes.',
'Crypto':'Creates identity keys and handles message encryption, signatures, fingerprints and local encryption.',
'DeviceIdentity':'Derives the scoped installation identity used in account binding.',
'DeviceSecurity':'Applies secure windows and checks detected root, emulator, VPN and unsupported device conditions.',
'DocumentUris':'Validates file-provider URIs so account files cannot be redirected to arbitrary filesystem paths.',
'GroupDetailsActivity':'Displays group members and handles invitations, names, roles, leaving and deletion controls.',
'GroupRepository':'Validates group state and calls server operations for group membership and encrypted sends.',
'GuardedActivity':'Provides common protected-screen behavior and lifecycle handling.',
'Icons':'Draws the app line icons, including chat pins, without downloaded image assets.',
'InboxActivity':'Builds the combined chat list, search, unread filter and group requests; stores pin/unpin choices and sorts pinned chats first.',
'JsonBounds':'Bounds JSON depth and input size before parsing untrusted records.',
'KeyDelegation':'Checks signed key-delegation chains used when moving or recovering an account.',
'MainActivity':'Handles account creation, returning sign-in and entry to recovery or account move.',
'MemberPicker':'Searches local contacts and the server directory when choosing group members.',
'MessageActions':'Checks message reply, edit and deletion actions and their allowed time windows.',
'MessageContent':'Defines message content, reply references and signed message changes.',
'MessageLinks':'Finds supported web links and asks the user before opening one.',
'MessageService':'Receives Firebase messaging callbacks and requests authenticated sync.',
'MuteControls':'Shows conversation notification mute periods and saves the encrypted local choice.',
'NfcContactPanel':'Controls the contact page NFC interface and its foreground sharing state.',
'NfcContactService':'Handles the permission-protected NFC host-card-emulation exchange.',
'NfcProtocol':'Defines the bounded NFC contact exchange packets and checks their structure.',
'NfcShareLease':'Limits how long foreground public-contact sharing remains active.',
'Notices':'Manages generic notification channels, permission state, tokens and alerts after durable message saves.',
'PriperiApp':'Initializes app services, theme and lifecycle tracking; clears temporary account files at startup.',
'PrivateActivity':'Checks account/session and device policy before showing private content.',
'PushSyncService':'Runs the system-scheduled, network-constrained sync job after a generic push hint.',
'RecordCodec':'Compresses and bounds local records before they are encrypted and stored.',
'RecoveryActivity':'Guides recovery-kit creation, activation, revocation and account restoration.',
'RecoveryBundle':'Defines the recovery snapshot payload and validates its identity fields.',
'RecoveryCipher':'Encrypts and decrypts recovery containers using their separate recovery words.',
'RecoveryFiles':'Manages recovery file-picker streams and temporary files.',
'ReplyGesture':'Recognizes the bounded sideways gesture that selects a message to reply to.',
'Repository':'Serializes encrypted local work and dispatches validated backend requests.',
'SendButton':'Draws and animates the composer send control.',
'Session':'Handles managed login, installation/session binding, token refresh and local sign-in state.',
'SettingsActivity':'Shows appearance, notifications, recovery, transfer and account policy controls.',
'SignupCheck':'Runs the guarded signup verification WebView and accepts only the expected main-frame provider result.',
'SplashActivity':'Provides the public cosmetic startup screen and opens the private sign-in flow without forwarding caller extras.',
'StartupMotion':'Draws and animates the local startup/loading brand while respecting reduced motion.',
'SyncBudget':'Bounds sync work so push, foreground refresh and sends do not run without limits.',
'SyncCoordinator':'Coordinates observers, sync requests, reconnect handling and foreground/background pacing.',
'SyncEngine':'Downloads and uploads bounded message work and acknowledges only after local save.',
'SyncPace':'Chooses fallback polling intervals and backoff for quiet or offline conversations.',
'TransferActivity':'Guides old-phone and new-phone account move, confirmation and unfinished-move recovery.',
'TransferArchive':'Defines the signed encrypted history archive carried separately during account move.',
'TransferCipher':'Encrypts and decrypts the temporary account-move capsule.',
'TransferFiles':'Handles temporary account-transfer files and file-picker streams.',
'TransferPhrase':'Converts one-time transfer entropy to and from the bundled word list.',
'Ui':'Builds shared controls, dialogs, spacing, colors, loading states and small animations.',
'Validation':'Checks usernames, passwords and other app input before network or storage work.',
'Vault':'Stores encrypted identity/settings and message records, indexes, replay markers, mute preferences and chat pins.',
}
BACKEND = {
'index.ts':'Starts the Deno relay, creates server/Auth clients from platform secrets, and wires push and signup protection into the handler.',
'handler.js':'Routes relay operations and checks request bodies, passwords, JWTs, session claims and operation-specific inputs.',
'policy.js':'Defines server input bounds, fingerprints, signature checks, error types and common request policy.',
'account.js':'Handles account policy and signed account-move operations through restricted database routines.',
'delegation.js':'Validates delegated signing keys and the signed contexts used for account moves and recovery.',
'groups.js':'Checks group operation input and dispatches membership, invitation, role and group-message requests.',
'push.js':'Uses the server-only Google sender credential to send a generic FCM hint without chat text.',
'recovery.js':'Checks recovery-kit operations and calls the server-only recovery routine.',
'signup.js':'Binds signup verification to the attempt, verifies Turnstile server-side, and returns the public widget template.',
'runtime.d.ts':'Declares the Edge runtime types needed when checking the relay entry point.',
}
MIGRATIONS = {
'001':'Creates the private relay schema, account/profile and encrypted queue tables, access restrictions and core request routines.',
'002':'Enables pg_cron and schedules the hourly relay cleanup sweep.',
'003':'Updates the first account/request routines for retried setup and delivery operations.',
'004':'Adds scoped device binding and signed device-access operations.',
'005':'Adds contact approval and the introductory-message allowance.',
'006':'Propagates validated username changes to contact views.',
'007':'Adds invite-only groups, roles, membership and encrypted group delivery.',
'008':'Adds group cache/version state used for efficient sync.',
'009':'Adds bounded group storage and cleanup controls.',
'010':'Hardens database authorization and guarded server routines.',
'011':'Adds account transfer, inactivity policy and account-control routines.',
'012':'Cleans up abandoned transfer receiver state during authorized rollback.',
'013':'Batches sync/receipt work and reduces message request database round trips.',
'014':'Adds the warned password-authorized fresh-account flow after installation key loss.',
'015':'Adds persistent encrypted recovery-kit registration and recovery routines.',
'016':'Makes one recipient approval apply to both conversation directions.',
'017':'Adds server-verified signup admission and replaces guarded signup routines. This update was prepared but not activated on the original hosted project.',
}
SPECIAL = {
'README.md':'Introduces the complete app, the setup order, service boundaries and remaining security limits.',
'.gitignore':'Keeps cloud credentials, keystores, account files, local results, dependencies and generated output out of Git.',
'features_to_add.md':'Preserves the historical feature list and implementation decisions with original-project references removed.',
'priperi/build.gradle':'Pins the Android Gradle plugin used by the app build.',
'priperi/settings.gradle':'Selects dependency repositories and includes the Android app module.',
'priperi/app/build.gradle':'Defines app version, SDK levels, dependencies and debug, secureTest and release build behavior.',
'priperi/gradle.properties':'Holds Android build settings and the two public backend connection fields; fill them with your own project values.',
'priperi/gradlew':'Starts the pinned Gradle wrapper on Unix-like systems.',
'priperi/gradlew.bat':'Starts the pinned Gradle wrapper on Windows.',
'priperi/gradle/wrapper/gradle-wrapper.jar':'Bootstraps the Gradle distribution. It is build tooling, not an account archive.',
'priperi/gradle/wrapper/gradle-wrapper.properties':'Pins the Gradle download URL and SHA-256 verification value.',
'priperi/app/proguard-rules.pro':'Keeps required runtime classes and rules during release shrinking.',
'priperi/app/src/main/AndroidManifest.xml':'Declares permissions, private screens, the public splash launcher and protected push/NFC services.',
'priperi/app/src/main/res/values/secrets.xml':'Contains public Firebase resource placeholders. Paste your own Android registration values; never a private service-account key.',
'backend/supabase/config.toml':'Records Auth defaults and the relay JWT gateway setting; hosted Auth dashboard settings must also be configured.',
'backend/supabase/package.json':'Pins JavaScript runtime/test dependencies and defines backend test/check commands.',
'backend/supabase/package-lock.json':'Locks the backend dependency tree for npm ci.',
'backend/supabase/deno.json':'Defines Deno module resolution and check settings.',
'backend/supabase/deno.lock':'Records Deno dependency integrity information.',
'backend/supabase/test/database.js':'Creates a disposable PGlite database, synthetic Auth tables and selected migrations for backend tests.',
'backend/supabase/test/android-server.js':'Provides fake managed Auth and relay behavior backed by disposable SQL for emulator tests.',
'backend/qa/android-fixture.js':'Starts the local test server, builds the private emulator variant and runs selected Android instrumentation cases.',
'backend/qa/catalog-security-readonly.sql':'Reads schema/routine/grant catalogs for security review without exporting account or message rows.',
'backend/qa/hosted-security-readonly.js':'Runs opted-in denial probes on a configured hosted endpoint without a valid user login.',
'backend/qa/hosted-account-controls.js':'Runs opted-in account move and account policy checks using its own synthetic hosted accounts; it changes disposable server state.',
'backend/qa/hosted-sync-latency.js':'Creates opted-in synthetic hosted accounts and measures bounded send/sync timings, then attempts cleanup.',
'backend/qa/push-lifecycle-trigger.js':'Captures a disposable push-test bridge and sends only its prepared synthetic messages; its temporary file contains test credentials.',
'backend/deployment/fcm-sender-role.yaml':'Defines a minimal Google custom role containing only the FCM send permission.',
'backend/deployment/firebase-lockdown/.firebaserc':'Selects the Firebase project for optional legacy-rule maintenance; replace the project placeholder.',
'backend/deployment/firebase-lockdown/firebase.json':'Connects the legacy database, Firestore and Storage rules to Firebase tooling.',
'backend/deployment/firebase-lockdown/database.rules.json':'Denies all legacy Realtime Database access.',
'backend/deployment/firebase-lockdown/firestore.rules':'Denies all legacy Firestore document access.',
'backend/deployment/firebase-lockdown/storage.rules':'Denies all legacy Cloud Storage object access.',
'backend/deployment/firebase-lockdown/firestore.indexes.json':'Preserves the legacy Firestore index definitions; Supabase is the current chat database.',
'config/relay.env.example':'Shows the custom Edge secrets to supply privately; platform Supabase secrets are documented separately.',
'config/fcm-service-account.json.example':'Shows the shape of a Google sender credential with explicit placeholders instead of a private key.',
'config/README.md':'Explains where example configuration belongs and why filled private files stay outside Git.',
'config/local.properties.example':'Shows how to set a machine-local Android SDK path without committing it.',
'config/hosted-qa.env.example':'Lists explicit disposable-hosted-test opt-ins and the expected target URL.',
}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def allowed(path):
    rel = path.relative_to(ROOT)
    if rel.parts[:2]==('docs','files') or str(rel)=='docs/FILE-INDEX.md':
        return False
    if any(part in SKIP_DIRS for part in rel.parts):
        return False
    if path.is_symlink():
        raise RuntimeError('Symlink in source component: ' + str(rel))
    if path.name in {'.DS_Store', 'local.properties', 'CHECKPOINT.md'} or path.name.startswith(('.env', '.dev.vars')):
        return False
    if path.suffix.lower() in PRIVATE_SUFFIXES or path.name.startswith(('service-account', ORIGINAL_FIREBASE + '-')) or path.name == 'priperi-push-key.json':
        return False
    return True


def sanitize(rel, data):
    if rel.endswith('.jar') or rel=='tools/prepare-github.py':
        return data
    try:
        text = data.decode('utf-8')
    except UnicodeDecodeError:
        raise RuntimeError('Unreviewed binary file: ' + rel)
    if rel == 'features_to_add.md':
        # Personal agent scheduling/budget instructions are not developer documentation.
        text = re.sub(r'^\*\*Work on those feature[^\n]*\*\*\s*', '# Feature progress\n\n', text, count=1)
        text = '\n'.join(line.rstrip() for line in text.splitlines())+'\n'
    if rel == 'priperi/gradle.properties':
        text = re.sub(r'(?m)^(priperiBackendUrl|priperiSupabaseKey)=.*$', r'\1=', text)
    text = re.sub(r'sb_publishable_[A-Za-z0-9_-]+', 'PASTE_SUPABASE_PUBLISHABLE_KEY', text)
    text = re.sub(r'AIza[A-Za-z0-9_-]{30,}', 'PASTE_FIREBASE_ANDROID_API_KEY', text)
    text = re.sub(r'1:[0-9]+:android:[a-f0-9]+', 'PASTE_FIREBASE_ANDROID_APP_ID', text)
    sender=re.search(r'<string name="gcm_defaultSenderId"[^>]*>([0-9]+)</string>',resource_text)
    if sender:text=text.replace(sender[1],'PASTE_FIREBASE_PROJECT_NUMBER')
    if ORIGINAL_REF:text=text.replace(ORIGINAL_REF,'YOUR_PROJECT_REF')
    if ORIGINAL_FIREBASE:text=text.replace(ORIGINAL_FIREBASE,'YOUR_FIREBASE_PROJECT_ID')
    for before,after in FIXTURE_REPLACEMENTS.items():text=text.replace(before,after)
    text = re.sub(r'/Users/[^/\s]+/[^\s`\'"<>;,)]+', '/absolute/path/to/your/local-file', text)
    text = text.replace('/opt/homebrew/opt/openjdk@21', '/absolute/path/to/jdk-21')
    if rel == 'priperi/app/src/main/res/values/secrets.xml':
        fields = {'project_id':'PASTE_FIREBASE_PROJECT_ID','google_app_id':'PASTE_FIREBASE_ANDROID_APP_ID','google_api_key':'PASTE_FIREBASE_ANDROID_API_KEY','gcm_defaultSenderId':'PASTE_FIREBASE_PROJECT_NUMBER','google_storage_bucket':'PASTE_FIREBASE_STORAGE_BUCKET'}
        for name, value in fields.items():
            text = re.sub(r'(<string name="' + name + r'"[^>]*>)[^<]*(</string>)', r'\g<1>' + value + r'\2', text)
    # Historical deployment observations contain live counts, not instructions.
    if rel.endswith('.md') and not rel.startswith('tools/'):
        lines = text.splitlines()
        text = '\n'.join(line for line in lines if not re.search(r'(?i)(?:\d+\s*(?:Auth[/ ]|profiles?[/ ]|accounts?[/ ]|queued (?:direct )?messages?|receipts?[/ ,])|operational uploaded key|failed unused keys|at verification there were|existing.*(?:accounts|profiles).*preserv)', line)) + '\n'
    # Keep a positive target check without retaining the original hosted endpoint.
    if rel in {'backend/qa/hosted-account-controls.js', 'backend/qa/hosted-sync-latency.js'}:
        text = text.replace("assert.equal(endpoint,'https://YOUR_PROJECT_REF.supabase.co/functions/v1/relay');", "assert.ok(process.env.PRIPERI_EXPECTED_HOSTED_URL,'Set the explicitly expected disposable target URL');assert.equal(endpoint,process.env.PRIPERI_EXPECTED_HOSTED_URL);")
        text = text.replace("import {readFileSync,writeFileSync,existsSync,unlinkSync}", "import {readFileSync,writeFileSync,existsSync,unlinkSync,mkdirSync}")
        marker="if(existsSync(recovery))"
        text=text.replace(marker,"mkdirSync(new URL('.',recovery),{recursive:true});"+marker)
    if rel == 'backend/qa/push-lifecycle-trigger.js':
        text = "import {tmpdir} from 'node:os';\nimport {join} from 'node:path';\n" + text
        text = text.replace("const location='/private/tmp/priperi-push-qa-20261026/trigger.json';", "const temporary=join(tmpdir(),'priperi-push-qa');const location=join(temporary,'trigger.json');")
        text = text.replace("mkdirSync('/private/tmp/priperi-push-qa-20261026'", 'mkdirSync(temporary')
        text = text.replace("fetch('https://YOUR_PROJECT_REF.supabase.co/functions/v1/relay'", "fetch(process.env.PRIPERI_EXPECTED_HOSTED_URL")
        text = text.replace("apikey:'PASTE_SUPABASE_PUBLISHABLE_KEY'", "apikey:process.env.PRIPERI_HOSTED_PUBLISHABLE_KEY")
        text = "if(process.env.PRIPERI_PUSH_TEST!=='yes'||!process.env.PRIPERI_EXPECTED_HOSTED_URL?.startsWith('https://')||!process.env.PRIPERI_HOSTED_PUBLISHABLE_KEY?.startsWith('sb_publishable_'))throw Error('Explicit disposable push-test target and public key required');\n"+text
    return text.encode()


def assert_clean(rel, data):
    patterns = [rb'-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----\r?\n', rb'sb_secret_[A-Za-z0-9_-]{12,}', rb'AIza[A-Za-z0-9_-]{30,}', rb'sb_publishable_[A-Za-z0-9_-]{15,}']
    if any(re.search(pattern, data) for pattern in patterns):
        raise RuntimeError('Credential-shaped value in export: ' + rel)
    if rel != 'tools/prepare-github.py':
        if (ORIGINAL_REF and ORIGINAL_REF.encode() in data) or (ORIGINAL_FIREBASE and ORIGINAL_FIREBASE.encode() in data) or re.search(rb'/Users/[^/\s]+/',data):
            raise RuntimeError('Original project reference in export: ' + rel)
    for token in re.findall(rb'eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+',data):
        try:
            claims=json.loads(base64.urlsafe_b64decode(token.split(b'.')[1]+b'='*(-len(token.split(b'.')[1])%4)))
        except (ValueError,UnicodeDecodeError):
            continue
        if claims.get('role') in {'service_role','supabase_admin','postgres','authenticator'}:
            raise RuntimeError('Privileged token in export: '+rel)
    try:
        obj=json.loads(data)
    except (ValueError,UnicodeDecodeError):
        return
    if isinstance(obj,dict) and obj.get('type')=='service_account' and not rel.endswith('.example'):
        raise RuntimeError('Service credential file in export: '+rel)


def description(rel, data):
    p=Path(rel)
    if rel in SPECIAL:
        return SPECIAL[rel]
    if '/functions/relay/' in rel:
        return BACKEND[p.name]
    if '/migrations/' in rel:
        return MIGRATIONS[re.search(r'(\d{3})_',p.name)[1]]
    if '/src/main/java/' in rel:
        return JAVA[p.stem]
    if '/androidTest/' in rel or '/src/test/' in rel:
        if p.stem=='TestIdentityTrust':
            return 'Provides explicit trust setup for synthetic test identities. This helper belongs only in the test APK.'
        return ('Runs Android instrumentation checks for ' if '/androidTest/' in rel else 'Runs JVM unit checks for ') + re.sub(r'(?<!^)(?=[A-Z])',' ',p.stem).lower() + '. Read the listed test cases for the exact coverage.'
    if '/supabase/test/' in rel:
        return 'Checks '+p.name.replace('.test.js','').replace('.test.ts','').replace('-',' ')+' behavior with synthetic fixtures. It is test code, not a deployed endpoint.'
    if p.name.startswith('NOTICE'):
        return 'Preserves the third-party license notice for the bundled BIP-39 word list.'
    if p.name=='bip39_english.txt':
        return 'Contains the standard BIP-39 English word list used to encode transfer/recovery entropy. It contains no saved recovery phrase.'
    if '/res/' in rel:
        return {
            'colors.xml':'Defines the dark palette.' if 'values-night' in rel else 'Defines the light palette and shared color names.',
            'styles.xml':'Defines the dark Android theme.' if 'values-night' in rel else 'Defines the base Android theme and component styles.',
            'strings.xml':'Defines shared app text and app-name resources.',
            'nfc.xml':'Defines NFC resource values used by the contact exchange.',
            'nfc_contact_service.xml':'Declares the NFC host-card-emulation service and supported contact application ID.',
            'network_security_config.xml':'Allows local fixture HTTP only in debug.' if '/debug/' in rel else 'Requires normal TLS trust and disallows cleartext traffic in normal app builds.',
            'backup_rules.xml':'Excludes app records from Android automatic backup.',
            'data_extraction_rules.xml':'Restricts Android cloud-backup and device-transfer extraction of app data.',
            'ic_launcher.xml':'Defines an adaptive launcher icon using the app foreground artwork.',
            'ic_launcher_foreground.xml':'Draws the launcher foreground artwork as a vector resource.',
            'ic_notification.xml':'Draws the small Android notification icon as a vector resource.',
        }[p.name]
    if rel.startswith('tools/'):
        if 'github-content/' in rel:
            return 'Stores the plain-language '+p.stem.lower().replace('-',' ')+' guide copied into future sanitized exports.'
        return {'prepare-github.py':'Builds the sanitized export, per-file notes, manifest and ZIP without changing live services.', 'check-github-export.py':'Checks exported source completeness, hashes, private-value patterns and per-file note coverage.', 'run-android-regressions.py':'Runs portable local emulator regression batches and writes ignored local results.', 'android-test-batches.json':'Lists the bounded local instrumentation batches recovered from the private regression runner.'}[p.name]
    if rel.startswith('docs/'):
        return 'Explains '+p.stem.lower().replace('-',' ')+' for maintainers. Historical review pages describe earlier versions; use the current build/cloud guides for setup.'
    if rel.startswith('backend/deployment/'):
        return 'Documents '+p.stem.lower().replace('-',' ')+' setup and deployment decisions. It is not executed by the APK build.'
    raise RuntimeError('Missing file description: '+rel)


def note(rel,data):
    p=Path(rel)
    text=data.decode('utf-8',errors='ignore') if p.suffix!='.jar' else ''
    depth=len(p.parts)-1
    target='../'*(depth+2)+rel
    body=f'# {p.name}\n\nSource: [{rel}]({target})\n\n{description(rel,data)}\n'
    if p.suffix=='.java' and ('/test/' in rel or '/androidTest/' in rel):
        cases=re.findall(r'@Test(?:\([^)]*\))?\s+(?:public\s+)?void\s+(\w+)',text)
        if cases:
            body+='\nTest cases:\n\n'+'\n'.join('- `'+case+'`' for case in cases)+'\n'
    elif '/functions/relay/' in rel and p.suffix in {'.js','.ts'}:
        exports=re.findall(r'export\s+(?:async\s+)?(?:function|class|const)\s+(\w+)',text)
        if exports:
            body+='\nEntry points: '+', '.join('`'+name+'`' for name in exports)+'.\n'
    elif '/migrations/' in rel:
        body+='\nApply once in filename order on a reviewed new database. Keep any routine-body guards. This file does not contain a production database dump. See [cloud setup]('+('../'*(depth+2))+'docs/CLOUD-SETUP.md).\n'
    if rel in {'priperi/gradle.properties','priperi/app/src/main/res/values/secrets.xml'} or rel.startswith('config/'):
        body+='\nRequired values and private storage locations are listed in [Configuration]('+('../'*(depth+2))+'docs/CONFIGURATION.md).\n'
    elif '/androidTest/' in rel or '/test/' in rel or rel.startswith('backend/qa/'):
        body+='\nSee [Tests]('+('../'*(depth+2))+'docs/TESTING.md) before running it. Hosted Live tests need an explicitly chosen disposable service; local tests must not use an installation containing wanted accounts.\n'
    elif '/src/main/java/' in rel or '/res/' in rel or rel.startswith('priperi/'):
        body+='\nThis file is compiled or packaged by the Android build. See [Build]('+('../'*(depth+2))+'docs/BUILD.md). It has no private value to paste unless its configuration note says otherwise.\n'
    body+='\n[Developer roadmap]('+('../'*(depth+2))+'docs/PIPELINE.md) · [File index]('+('../'*(depth+2))+'docs/FILE-INDEX.md)\n'
    return body.encode()


def main():
    known=set(COMPONENTS)|set(ROOT_FILES)|{'.git','.DS_Store','local-only','artifacts','github-ready','EXPORT-MANIFEST.json','EXPORT-REPORT.json'}
    unknown=[p.name for p in ROOT.iterdir() if p.name not in known]
    if unknown:
        raise RuntimeError('Review new top-level entries before exporting: '+', '.join(unknown))
    files=[]
    for name in (*ROOT_FILES,*COMPONENTS):
        base=ROOT/name
        candidates=[base] if base.is_file() else base.rglob('*')
        files.extend(p for p in candidates if p.is_file() and allowed(p))
    source={str(p.relative_to(ROOT)):p.read_bytes() for p in sorted(set(files))}
    payload={rel:sanitize(rel,data) for rel,data in source.items()}
    content=ROOT/'tools/github-content'
    for p in content.glob('*.md'):
        target='README.md' if p.name=='README.md' else 'docs/'+p.name
        payload[target]=p.read_bytes()
    payload['docs/DEPLOYMENT.md']=b'# Deployment\n\nUse [Cloud setup](CLOUD-SETUP.md) for a fresh project. The source includes migrations 001-017. The original deployment was recorded as 001-016; signup admission in 017 was prepared, not activated. Never replay already installed migrations.\n'
    payload['config/relay.env.example']=b'# Custom Supabase Edge secrets only. Fill a private copy outside Git.\nTURNSTILE_SITE_KEY=PASTE_TURNSTILE_SITE_KEY\nTURNSTILE_SECRET_KEY=PASTE_TURNSTILE_SECRET_KEY\n# Optional: paste your dedicated sender JSON on one line, enclosed in single quotes.\n# FCM_SERVICE_ACCOUNT=\'PASTE_COMPLETE_GOOGLE_SERVICE_ACCOUNT_JSON\'\n'
    payload['config/fcm-service-account.json.example']=(json.dumps({'type':'service_account','project_id':'PASTE_FIREBASE_PROJECT_ID','private_key_id':'PASTE_GOOGLE_PRIVATE_KEY_ID','private_key':'PASTE_PRIVATE_KEY_FROM_YOUR_OWN_CREDENTIAL_ONLY_IN_A_PRIVATE_COPY','client_email':'PASTE_SENDER_SERVICE_ACCOUNT_EMAIL','client_id':'PASTE_GOOGLE_CLIENT_ID','token_uri':'https://oauth2.googleapis.com/token'},indent=2)+'\n').encode()
    payload['config/local.properties.example']=b'# Copy to priperi/local.properties and use your SDK folder.\nsdk.dir=/absolute/path/to/your/Android/sdk\n'
    payload['config/hosted-qa.env.example']=b'# Opt-in tests only; use a disposable project. Never source unreviewed files.\nPRIPERI_EXPECTED_HOSTED_URL=https://YOUR_PROJECT_REF.supabase.co/functions/v1/relay\nPRIPERI_HOSTED_PUBLISHABLE_KEY=PASTE_SUPABASE_PUBLISHABLE_KEY\n# Set only for the test you have reviewed:\n# PRIPERI_HOSTED_READ_ONLY=yes\n# PRIPERI_HOSTED_ACCOUNT_TEST=yes\n# PRIPERI_HOSTED_SPEED_TEST=yes\n# PRIPERI_PUSH_TEST=yes\n'
    payload['config/README.md']=b'# Configuration examples\n\nUse [Configuration](../docs/CONFIGURATION.md) to fill your own values. Keep filled Edge env files, service-account JSON and signing keys outside Git. These examples contain placeholders only. Supabase supplies its own platform variables. Android Firebase values and the Supabase publishable key are public app settings; original values were removed from this copy.\n'
    if b'# Local credentials and personal exports' not in payload['.gitignore']:payload['.gitignore']+=b'\n# Local credentials and personal exports\n**/.env\n**/.env.*\n!**/*.example\n**/google-services.json\n**/service-account*.json\n**/*service-account*.json\n*.db\n*.sqlite\n*.sqlite3\n*.apk\n*.aab\n'
    payload['backend/deployment/firebase-lockdown/README.md']=b'# Legacy Firebase lockdown\n\nThe app uses Supabase for accounts and encrypted relay data. Firebase supplies optional generic push. These rules deny legacy Firestore, Realtime Database and Storage access. Replace the project placeholder in .firebaserc only if maintaining those services in your own project. See [cloud setup](../../../docs/CLOUD-SETUP.md).\n'
    # These generated entry guides are not copied from Markdown templates.
    for rel in ('docs/DEPLOYMENT.md','config/README.md','backend/deployment/firebase-lockdown/README.md'):
        roadmap=posixpath.relpath('docs/PIPELINE.md',posixpath.dirname(rel))
        payload[rel]+=('\n[Developer roadmap]('+roadmap+')\n').encode()
    for rel,data in payload.items():
        assert_clean(rel,data)
        description(rel,data)
    # Source must remain stable throughout the export.
    for rel,data in source.items():
        if (ROOT/rel).read_bytes()!=data:
            raise RuntimeError('Source changed during export: '+rel)
    if DEST.is_symlink() or DEST.parent.is_symlink() or ZIP.parent.is_symlink():
        raise RuntimeError('Output must not use symlink destinations')
    if DEST.exists():
        shutil.rmtree(DEST)
    DEST.mkdir(parents=True)
    entries=[]
    for rel,data in sorted(payload.items()):
        target=DEST/rel;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
        if rel in {'priperi/gradlew','tools/prepare-github.py','tools/check-github-export.py','tools/run-android-regressions.py'}:
            target.chmod(0o755)
        guide='docs/files/'+rel+'.md';p=DEST/guide;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(note(rel,data))
        entries.append({'path':rel,'sha256':digest(data),'guide':guide,'guideSha256':digest(p.read_bytes()),'origin':'source' if rel in source else 'export guide/example','sanitizedOrRewritten':rel not in source or data!=source[rel]})
    index='# File guide\n\n[Overview](../README.md) · [Developer roadmap](PIPELINE.md)\n\nEach payload file has a separate note below. Notes explain its job and link to setup or test steps. Generated notes are navigation material; they do not need another recursive set of notes. EXPORT-MANIFEST.json records payload/note hashes and export scope; EXPORT-REPORT.json records automated verification. Neither contains user data.\n\n'
    for e in entries:
        index+='- ['+e['path']+'](files/'+e['path']+'.md)\n'
    (DEST/'docs/FILE-INDEX.md').write_text(index)
    manifest={'appVersion':'2.15.0','sourceFileCount':len(source),'payloadFileCount':len(entries),'fileGuideCount':len(entries),'sourceInventory':[{'path':rel,'sha256':digest(data)} for rel,data in sorted(source.items())], 'files':entries,'excludedCategories':['User accounts/messages/keys/sessions/recovery material','Working private credentials and original public project connections','Git history and owner checkpoints','Raw private evidence, logs, screenshots and recordings','Historical duplicate code snapshots and one-off patch/evidence scripts','Build outputs, old APKs, caches and downloaded dependencies'],'notes':'Current reusable source and policy are included. Portable regression runner replaces machine-specific private orchestration. Original live services and user files were not modified.'}
    (DEST/'EXPORT-MANIFEST.json').write_text(json.dumps(manifest,indent=2)+'\n')
    import subprocess
    subprocess.run(['python3',str(ROOT/'tools/check-github-export.py'),str(DEST),'--source-root',str(ROOT)],check=True)
    ZIP.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(ZIP,'w',zipfile.ZIP_DEFLATED) as archive:
        for p in sorted(DEST.rglob('*')):
            if p.is_file():
                archive.write(p,'Priperi/'+str(p.relative_to(DEST)))
    (ZIP.with_suffix('.zip.sha256')).write_text(digest(ZIP.read_bytes())+'  '+ZIP.name+'\n')
    print(f'Prepared {len(entries)} payload files and {len(entries)} per-file notes in {DEST}; ZIP: {ZIP}')

if __name__=='__main__':
    main()
