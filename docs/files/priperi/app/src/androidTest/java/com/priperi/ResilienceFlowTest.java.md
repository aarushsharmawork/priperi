# ResilienceFlowTest.java

Source: [priperi/app/src/androidTest/java/com/priperi/ResilienceFlowTest.java](../../../../../../../../../priperi/app/src/androidTest/java/com/priperi/ResilienceFlowTest.java)

Runs Android instrumentation checks for resilience flow test. Read the listed test cases for the exact coverage.

Test cases:

- `removedRecipientDoesNotStopSendingToOtherPeople`
- `blockedRecipientBacksOffWithoutDroppingOrReorderingTheirOutbox`
- `invalidRefreshReturnsToSignInWithoutLosingKeysOrMessages`
- `temporaryRefreshFailureKeepsTheValidSessionForRetry`
- `queuedChatLoadWhilePausedDoesNotMarkUnseenMessagesRead`
- `queuedGroupLoadWhilePausedKeepsItsUnreadCount`

See [Tests](../../../../../../../../../docs/TESTING.md) before running it. Hosted Live tests need an explicitly chosen disposable service; local tests must not use an installation containing wanted accounts.

[Developer roadmap](../../../../../../../../../docs/PIPELINE.md) · [File index](../../../../../../../../../docs/FILE-INDEX.md)
