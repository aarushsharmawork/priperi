# ContactTrustTest.java

Source: [priperi/app/src/androidTest/java/com/priperi/ContactTrustTest.java](../../../../../../../../../priperi/app/src/androidTest/java/com/priperi/ContactTrustTest.java)

Runs Android instrumentation checks for contact trust test. Read the listed test cases for the exact coverage.

Test cases:

- `serverFlagsCannotEstablishTrustOrOverwriteLocalReadAndBlockState`
- `explicitVerificationPersistsAndChangedOrSubstitutedKeysFailClosed`
- `legacyFlagsStayUnverifiedButDoNotBlockQueuedMessages`
- `optionalGroupChecksDoNotPreventNormalSending`
- `directUiCanSendWithoutQrAndOptionalComparisonStillNeedsConsent`
- `unverifiedContactsStillRespectBlocksAndQueueLimit`
- `publicCodeRejectsDirectorySubstitutionBeforeEstablishingLocalTrust`

See [Tests](../../../../../../../../../docs/TESTING.md) before running it. Hosted Live tests need an explicitly chosen disposable service; local tests must not use an installation containing wanted accounts.

[Developer roadmap](../../../../../../../../../docs/PIPELINE.md) · [File index](../../../../../../../../../docs/FILE-INDEX.md)
