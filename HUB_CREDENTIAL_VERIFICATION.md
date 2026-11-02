# Hub credential prevention — 2026-11-02

Source base: `6a6cc6b811c7dccb08a9dcd4d6f43e014e852bb8` (`origin/main`, fetched before isolation).

## Behavior and evidence

- `BarProContract.webhookTokenError` rejects an HTTP/HTTPS webhook URL in the credential field. The Hub settings field displays that error; `configurationError` and `BootstrapClient.prove` stop the invalid credential before a request.
- `RemoteConfigSnapshot.applyTo`, used by `MaintenanceWorker`, updates both `authHeaderValue` and `forwarderSecret` when a verified overlay contains `webhookToken`. Existing authentication type and header name are preserved; an omitted token preserves the credential.
- `HubCredentialConfigurationTest` verifies outgoing health/bootstrap boundaries and signed-document verification → production overlay application → repository save/readback → outgoing health token. Room columns remain redacted. Its secure-storage rule substitutes a synthetic preferences provider because Robolectric does not implement AndroidKeyStore; this is not handset encryption evidence.
- The regression also verifies printable opaque tokens containing `+/=:_` remain accepted and that an empty signing key produces a security rejection without fetching a settings document.

## RED / GREEN

Executed with `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`:

```bash
./gradlew testHubDebugUnitTest --tests com.example.HubCredentialConfigurationTest --console=plain
```

Before the fixes, the existing worker mapping was moved unchanged into the production `applyTo` method to exercise it without a test-only hook. The same five tests then executed with **three failures, zero errors, zero skipped** (`BUILD FAILED in 53s`):

- `urlPastedAsTokenStopsHealthBeforeAnyRequest`: “A webhook URL cannot authenticate the Hub”.
- `urlPastedAsTokenCannotBeSentAsBootstrapProof`: “Bootstrap must reject the URL locally”.
- `signedRotationChangesThePersistedHealthRequestTokenWithoutChangingAuthSemantics`: outgoing header contained the existing synthetic token instead of the rotated one.

After shared token validation and synchronizing the overlay's credential fields, all **five tests passed, zero failures/errors/skipped** (`BUILD SUCCESSFUL in 1m 28s`). Local command logs: `/tmp/hub-token-red.log` and `/tmp/hub-token-green.log`; XML results are emitted under `app/build/test-results/testHubDebugUnitTest/`.

## Full checks

Executed on the final source with the same JDK21 selection:

```bash
./gradlew testDriverDebugUnitTest testHubDebugUnitTest \
  assembleDriverDebug assembleHubDebug \
  compileDriverReleaseKotlin compileHubReleaseKotlin \
  --no-build-cache --console=plain
```

Result: `BUILD SUCCESSFUL in 4m 50s`; 114 actionable tasks, 58 executed and 56 up-to-date. Both full unit-test tasks executed with build-cache restoration disabled. Driver: **233 tests, zero failures/errors, one skipped**. Hub: **201 tests, zero failures/errors, one skipped**. Each skip is `BootstrapLoopbackTest.actualHttpAndSyntheticCarrierActivateOnlyEncryptedPhoneScopedRoomConfiguration`, whose optional owned backend fixture was not started. The five new regressions passed in both flavors.

Both debug APK builds and both release Kotlin compilations completed. Generated debug metadata retains version2.1.2 and code5 for the separate `.driver`/`.hub` application IDs. These are local build checks, not a signed production release. `git diff --check` returned exit0. The build reported SDK schema, manifest-removal, native-symbol stripping and existing deprecated-API warnings; no build task failed. Complete local log: `/tmp/hub-token-full-checks.log`.

## Release and runtime limits

No version, signing key, signing-channel URL, public key, TLS trust anchor, or cleartext policy is changed. The checked-in version remains 2.1.2/code5, with the signing channels disabled by their empty defaults. No new release is published by this change.

A signature authenticates a settings document but does not encrypt its base64url payload. A global Hub secret belongs in private operator provisioning, not a public signed document. Driver enrollment links cannot provision Hub role (`EnrollmentManager.receive` requires `DRIVER`).

An operator must still place the genuine Hub credential in its token field, retain the verified HTTPS endpoint, confirm Hub connectivity, and resume Driver activation. This verification did not send SMS, modify server settings, or establish the installed handset version, successful handset activation, or live OTP delivery.
