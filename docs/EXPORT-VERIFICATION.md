# Export verification

[Overview](../README.md) · [Developer roadmap](PIPELINE.md) · [File index](FILE-INDEX.md)

Checked on 30 October 2026 using the sanitized source copy, not only the original workspace.

| Check | Result |
| --- | --- |
| Android JVM tests | 57 passed, including the final anonymized fixtures |
| Android instrumentation sources | Compiled successfully; the full emulator suite was not rerun |
| Android release build | Optimized unsigned APK built with backend values blank and Firebase placeholders retained |
| Android release lint | 0 errors, 32 warnings |
| Backend Node tests | 99 passed using disposable local SQL and synthetic Auth |
| Deno entry point | Type check passed |
| Deno runtime tests | 6 passed |
| Export inventory | Every selected current source/configuration/test file accounted for |
| Portable export tools | A fresh extracted copy regenerated the recorded payload and per-file notes; see the manifest for current counts |
| Per-file notes | One separate Markdown note for every payload file |
| Links and formats | Local Markdown links, JSON, XML and Python syntax checked |
| Selected credential patterns | No working private-key blocks, privileged tokens, original public keys or original cloud project connections in the export |

The export checker records exact file/note counts and hashes in EXPORT-MANIFEST.json and EXPORT-REPORT.json. Generated output and downloaded dependencies used for these checks are removed from the final folder and ZIP.

This run did not configure or contact the original live service, deploy SQL, activate signup admission, publish to GitHub, create a production signing key or test physical phones. Backend tests stub the external verification provider. The placeholder build is a compilation check; fill your own configuration before trying a real sign-in or push flow. The source's earlier broad Android test reports remain historical evidence and were not rerun in full for this packaging task.

The developer roadmap now contains setup/release, message-delivery, signup-activation and documentation diagrams. The current export checker verifies the updated Markdown links, payload hashes and per-file note coverage. This documentation update does not rerun or extend the earlier runtime test evidence.
