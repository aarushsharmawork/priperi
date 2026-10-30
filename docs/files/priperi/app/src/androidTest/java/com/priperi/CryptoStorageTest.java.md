# CryptoStorageTest.java

Source: [priperi/app/src/androidTest/java/com/priperi/CryptoStorageTest.java](../../../../../../../../../priperi/app/src/androidTest/java/com/priperi/CryptoStorageTest.java)

Runs Android instrumentation checks for crypto storage test. Read the listed test cases for the exact coverage.

Test cases:

- `hpkeRoundTripAuthenticatesSenderAndContext`
- `encryptedRecordsSurviveReopenAndDuplicateDelivery`
- `storageEncryptionRejectsTamperingAndCrossRecordSwaps`
- `freshGcmNoncesAndKeystoreKeysRemainNonexportable`
- `identityChangeIsRejectedAndDeletingDestroysKeys`

See [Tests](../../../../../../../../../docs/TESTING.md) before running it. Hosted Live tests need an explicitly chosen disposable service; local tests must not use an installation containing wanted accounts.

[Developer roadmap](../../../../../../../../../docs/PIPELINE.md) · [File index](../../../../../../../../../docs/FILE-INDEX.md)
