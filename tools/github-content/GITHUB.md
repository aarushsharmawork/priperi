# Put this folder on GitHub

[Overview](../README.md) · [Developer roadmap](PIPELINE.md) · [File index](FILE-INDEX.md)

Use the exported `github-ready/Priperi/` folder, or extract `Priperi-source.zip`. The ZIP contains one top-level `Priperi/` folder. Do not upload the original workspace or its Git history.

Read README.md, fill your own configuration privately and review EXPORT-MANIFEST.json. It lists the exact source files and their hashes. docs/FILE-INDEX.md links to one note for each source, configuration, test, asset and authored guide. Private generated files and credentials are ignored by .gitignore.

For a new repository, create an empty GitHub repository and initialize Git in this exported folder. For an existing repository you own, use a separate clone, copy the exported folder into it while preserving its `.git` directory, and remove only superseded tracked files after reviewing the diff. Make a normal commit and push; do not force-push or copy the private workspace’s Git directory. Use your own configured Git identity and SSH authentication. No remote is embedded in the source export.

Keep service-account files, release keys, filled env files, account backups, test recovery files and output folders out of commits. If a real secret is ever published, removing it from the latest file is not enough; revoke it with the provider and review the repository history.

Use docs/BUILD.md to build the APK and docs/CLOUD-SETUP.md to create your own backend. Existing original-project deployment notes are historical. Do not run them against an old project as a reset script.
