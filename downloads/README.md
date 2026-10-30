# Priperi Android test APK

[Download Priperi2.15.0 secure-test APK](Priperi-2.15.0-secure-test.apk?raw=true).

This is the optimized internal test build (version code47), signed with the existing development certificate. It is not the production-signed release. Normal device, TLS and non-debuggable restrictions remain enabled.

Install it OVER a matching existing Priperi development installation to preserve local keys and chats. Save and activate a recovery kit before uninstalling or clearing app data. Android must accept the package/signing identity for an in-place update.

`SHA256SUMS.txt` contains the APK hash. The APK is a separate download artifact; the source export's `EXPORT-MANIFEST.json` describes source files and file notes, not this binary. Clean source ZIPs intentionally omit APKs.

Backend signup admission017 remains prepared, not activated on the hosted project. This upload does not deploy backend changes. See the [release review](../docs/RELEASE-REVIEW.md) and [source build guide](../docs/BUILD.md) for remaining production conditions and reproducible build instructions.
