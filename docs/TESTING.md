# Run the tests

[Overview](../README.md) · [Developer roadmap](PIPELINE.md) · [File index](FILE-INDEX.md)

Use local disposable data first. The tests contain generated or deliberately fake identities and test-only passwords. Those values are not current users' records. No live user data is needed.

## Backend

Install a current Node.js version supported by the pinned dependencies. Node 22 or newer is a practical starting point. The package-lock file pins the JavaScript packages and the local Deno binary. Run from the repository root:

```sh
npm ci --prefix backend/supabase
npm test --prefix backend/supabase
```

For the Edge checks, run from `backend/supabase/`:

```sh
npx --no-install deno check functions/relay/index.ts
npx --no-install deno test test/deno-runtime.test.ts
```

PGlite provides the isolated SQL engine. The test helper creates synthetic Auth tables and applies the required migrations. It intentionally handles platform-specific cron/retry setup differently; passing local tests does not prove the hosted cron is active. Signup tests use a fake provider, not real Cloudflare acceptance.

## Android JVM tests and lint

```sh
./priperi/gradlew -p priperi :app:testDebugUnitTest :app:lintSecureTest :app:lintRelease
```

## Android emulator flows

Use a disposable Android API 35 emulator named `emulator-5554`. Install a screen lock if a test requires one. Put `adb` on PATH. Configure valid public Firebase resources for your own debug registration. Do not use an emulator installation that contains an account you want to keep: the fixture installs a private QA build and the tests create and erase synthetic state.

```sh
PRIPERI_TEST_CLASS=com.priperi.ChatPinFlowTest node backend/qa/android-fixture.js
```

The fixture starts a local HTTP test server on port 5001, overrides the app backend to `10.0.2.2`, builds debug with emulator allowances, installs the test APK and runs the selected class. The allowance exists only in the debug build. Do not distribute that output. Run classes separately when a feature flag is required; inspect the test's setup and fixture flags.

`tools/run-android-regressions.py` and `tools/android-test-batches.json` preserve the reusable part of the former private regression runner. The runner uses your JAVA_HOME, ANDROID_HOME and PATH; no original machine paths are required. Its results go to ignored `local-only/test-results/`. It does not deploy cloud code. The batch map deliberately excludes hosted opt-in tests and the separately configured signup case.

For the prepared signup flow:

```sh
PRIPERI_SIGNUP_TESTS=yes PRIPERI_TEST_CLASS=com.priperi.SignupFlowTest node backend/qa/android-fixture.js
```

For startup permission and window checks:

```sh
PRIPERI_STARTUP_ONLY=yes node backend/qa/android-fixture.js
PRIPERI_MATRIX_ONLY=yes PRIPERI_SCREEN_MATRIX=yes node backend/qa/android-fixture.js
```

Window tests change and restore the disposable emulator's display/font settings. They are not a reason to change a real phone's settings.

## Hosted checks

Files beginning `hosted-` and Android tests beginning `Live` are not ordinary local tests. Some create accounts, send messages, move or reset synthetic identities and retain temporary recovery material if cleanup fails. They need your explicitly chosen disposable endpoint, valid configuration and, where applicable, a manual fixture preparation step. Do not run them simply because the local tests passed. Read their per-file notes and opt-in guards first.

## Check the export

```sh
python3 tools/prepare-github.py
python3 tools/check-github-export.py
```

The generated manifest records exactly which payload files have per-file notes. Generated notes and the manifest describe themselves collectively in FILE-INDEX.md to avoid generating notes about notes forever. No tests publish code or activate migration 017 as part of export verification.
