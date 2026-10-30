# BackupCodecTest.java

Source: [priperi/app/src/test/java/com/priperi/BackupCodecTest.java](../../../../../../../../../priperi/app/src/test/java/com/priperi/BackupCodecTest.java)

Runs JVM unit checks for backup codec test. Read the listed test cases for the exact coverage.

Test cases:

- `segmentedRoundTripChecksSignatureBeforeUseAndHidesMessageText`
- `wrongPasswordKeysTamperingTruncationAndTrailingBytesAreRejected`
- `aPasswordHolderCannotSubstituteAccountPhoneSignatureOrOversizedFrames`
- `passwordDerivationIsFixedCostSaltedAndReproducible`

See [Tests](../../../../../../../../../docs/TESTING.md) before running it. Hosted Live tests need an explicitly chosen disposable service; local tests must not use an installation containing wanted accounts.

[Developer roadmap](../../../../../../../../../docs/PIPELINE.md) · [File index](../../../../../../../../../docs/FILE-INDEX.md)
