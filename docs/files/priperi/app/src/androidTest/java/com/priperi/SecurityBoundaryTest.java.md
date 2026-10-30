# SecurityBoundaryTest.java

Source: [priperi/app/src/androidTest/java/com/priperi/SecurityBoundaryTest.java](../../../../../../../../../priperi/app/src/androidTest/java/com/priperi/SecurityBoundaryTest.java)

Runs Android instrumentation checks for security boundary test. Read the listed test cases for the exact coverage.

Test cases:

- `dialogCannotOutliveThePrivateScreenGate`
- `everyDialogGetsItsOwnSecureWindow`
- `untrustedTlsProxyCannotReceiveTheRequest`
- `redirectCannotForwardBearerOrBodyToAnotherEndpoint`
- `lateDialogCannotAppearAboveAnAccountGate`

See [Tests](../../../../../../../../../docs/TESTING.md) before running it. Hosted Live tests need an explicitly chosen disposable service; local tests must not use an installation containing wanted accounts.

[Developer roadmap](../../../../../../../../../docs/PIPELINE.md) · [File index](../../../../../../../../../docs/FILE-INDEX.md)
