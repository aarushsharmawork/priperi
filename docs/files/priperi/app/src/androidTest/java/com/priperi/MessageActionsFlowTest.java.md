# MessageActionsFlowTest.java

Source: [priperi/app/src/androidTest/java/com/priperi/MessageActionsFlowTest.java](../../../../../../../../../priperi/app/src/androidTest/java/com/priperi/MessageActionsFlowTest.java)

Runs Android instrumentation checks for message actions flow test. Read the listed test cases for the exact coverage.

Test cases:

- `encryptedDirectEditReplyAndDeleteRoundTrip`
- `outOfOrderChangesRejectOtherAuthorsAndNeverResurrect`
- `localDeleteSurvivesOldBackupAndPendingOutboxCannotBeBypassed`
- `groupChangesRequireSameGroupVersionAndSender`
- `longPressReplyRotationEditAndLocalDeleteRemainUsable`
- `savedChangeStillSendsAfterLeavingChat`

See [Tests](../../../../../../../../../docs/TESTING.md) before running it. Hosted Live tests need an explicitly chosen disposable service; local tests must not use an installation containing wanted accounts.

[Developer roadmap](../../../../../../../../../docs/PIPELINE.md) · [File index](../../../../../../../../../docs/FILE-INDEX.md)
