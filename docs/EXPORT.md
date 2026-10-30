# What this export includes

[Overview](../README.md) · [Developer roadmap](PIPELINE.md) · [File index](FILE-INDEX.md)

The folder contains the current Android source and resources, all backend runtime modules, migrations 001–017, public test fixtures, deployment policy files, build wrapper, dependency lockfiles, security reviews and portable build/test/export tools. Previously private reusable regression settings are included as portable tools. Private cloud configuration has example files showing where replacements belong.

The export does not contain a database dump, user profile/message snapshot, account keyset, session token, device proof, notification token, recovery words, encrypted user backup, service-account credential or APK signing key. Old APKs, cached builds, downloaded dependencies, screenshots, recordings, raw logs and duplicate historical source snapshots are not needed to build and are left out. Screenshots are excluded even when they were labelled synthetic, so no display name or message from an image is accidentally published. Git history is also excluded.

Private owner checkpoints mixed operational notes with machine paths and live state. Their reusable setup and remaining-work information is covered in the new guides. Historical one-off patch generators, copied deployment editors and post-build evidence scripts are replaced by current source, committed migrations, the portable regression runner and the export checker. They are not deployment dependencies.

This is an export operation. It does not erase the original project's user data, alter the running cloud services, change credentials or publish to GitHub. Upload this folder as a new repository; do not add the original workspace's Git history or private directories.

`EXPORT-MANIFEST.json` contains payload paths, hashes, whether each original file was sanitized, documentation coverage and excluded categories. It does not include private file names or their contents. The source inventory is checked before packaging. If source changes during export, packaging stops.

## Updating the export

Run `python3 tools/prepare-github.py` from a checked-out source folder. It replaces only that tool's `github-ready/Priperi/` output. It does not edit Android or backend source. Your own configured app connection fields are blanked in the copy. User data and credentials belong in excluded locations, never in source files.

Keep new source components inside the normal source folders or extend the export's component list. The completeness check fails for unknown top-level entries, so a newly added folder cannot be silently missed. New source files automatically get a file note; add a plain-language description to the generator's description table when a new module needs a better explanation.

The automated credential scan checks selected credential formats and known original-project identifiers. It cannot recognize every possible secret format or decide whether arbitrary prose is personal data. Review newly added files before making the repository public.
