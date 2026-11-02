# Automatic activation candidate 2.1.3 — 2026-11-03

This source implements the approved no-typed-phone Driver flow. It does not establish installation, real modem transmission, genuine carrier receipt, live activation or OTP delivery on either handset.

## Driver flow

1. Android grants the SMS/runtime permissions. The UI and restart recovery enqueue one unique `DriverBootstrapWorker`; initial internet is required.
2. The installation keeps its UUID and AndroidKeyStore RSA key. `BootstrapClient.challenge` omits `driver_phone` for automatic activation. The server returns `barpro-bootstrap-v2`, a nonce, TTL and public Hub routes, with no driver identity before proof.
3. `BootstrapSms` uses Android's selected SMS subscription to send only `BPA1#<nonce>`. It never guesses an MSISDN. If Android has no selected SMS SIM, the app opens system settings once and explains the required SIM selection.
4. The trusted Hub's existing receiver submits the actual carrier origin. The backend identifies the uniquely approved registration and returns a grant encrypted for the bound installation.
5. The Driver signs polling and authenticates AES-GCM using `barpro-bootstrap-v2\n<nonce>\n<device_id>`. Only a valid decrypted `barpro-driver-v1` credential document installs the actual phone-scoped endpoint/token and activates forwarding.

`BootstrapCoordinator` retains its explicit phone-bound v1 interface and old v1 crypto defaults. Automatic recovery starts v2 for an unfinished typed-phone stage; an already valid enrolled Driver keeps its private configuration and explicit forwarding/consent flags.

## Durable modem states

Activation uses a separate encrypted attempt record in `SecureStorageManager`, not the credential-gated BP1 OTP outbox. `SmsDispatch` supplies distinct per-attempt capabilities to the non-exported `SmsResultReceiver`; the existing carrier status-report parser is reused.

| Observation | Meaning |
|---|---|
| Method handoff / `dispatching` | An attempt was persisted and handed to Android; radio send remains unconfirmed. |
| Sent callback / `sent` | Android acknowledged network transmission; no server enrollment is implied. |
| Valid delivered PDU / `delivered` | Carrier delivery was acknowledged; no server enrollment is implied. |
| Authenticated encrypted grant / `ACTIVE` | The registered carrier-origin identity was installed successfully. |

The 45-second missing-callback deadline, 90-second missing-proof deadline and maximum five attempts per nonce are named constants in `SmsDispatch`/`BootstrapSmsDispatch`. Failed attempts wait before retry; stale capabilities cannot replace a current attempt. Retries retain the same nonce and device binding. A server/client expiry clears the challenge so a subsequent bounded attempt can obtain a new one. Successful activation clears the pending attempt and revokes its capabilities.

## Hub and trust limits

The upgraded Hub preserves its existing secure credential, endpoint, routes and consent. No global secret is bundled or put into public documents; the advanced token field is masked. Fresh public defaults keep an unprovisioned configuration disabled with an empty credential (`ManagedDefaultsTest`).

There is no existing anonymous Hub enrollment or Android managed-profile provisioning implementation in this app. Android permissions alone cannot create trusted Hub authority. This candidate supports the user's already configured Hub; a fresh Hub without an authorized private profile remains unprovisioned. No enrollment link is required by the automatic Driver flow.

## Public update channels

The first persistent ECDSA-P256 channel public key is embedded in the candidate. The private channel key stays outside the repository. Role-specific defaults are:

- `barpro-driver.config.signed` / `barpro-driver.update.signed`
- `barpro-hub.config.signed` / `barpro-hub.update.signed`

They are fetched over HTTPS from this repository's latest-release assets. Existing custom channel URLs are preserved. Driver documents are rejected if they try to replace `endpointUrl` or `webhookToken`; public release/config documents must contain no credentials. Invalid audience/schema errors produce a security rejection; a genuinely stale configuration produces `NoChange`. The applied configuration version is recorded after the secure save succeeds.

A cached, authenticated release document remains available for download retries until its APK is installed. The same document/version may be retried; a different stale document cannot bypass the existing monotonic-version gate (`ReleaseDownloadRecoveryTest`).

The previously installed 2.1.2 build had no channel key/URLs and cannot acquire these changes by itself. It needs an initial APK upgrade, with any Android installer consent the platform requires. Later update/config polling uses the new public channels. No release upload is included in this verification.

## Local evidence

Before implementation, `AutomaticBootstrapTest` executed six failing cases: anonymous body omitted-phone contract, v2 crypto binding, anonymous coordinator identity/signature, synchronous modem exception recovery, and modem capabilities on SDK28/34. The same controls then passed. Additional RED runs covered no-input automatic UI/manager activation, duplicate in-flight dispatch, missing public defaults, invalid Driver config reporting, and retrying a failed same-release download.

The backend-generated `bootstrap-server-grant-v2.json` is synthetic test data produced with the real Python crypto. Android independently decrypts it. `AnonymousBootstrapLoopbackTest` adds real owned TCP/SQLite/Redis/crypto verification with an explicitly synthetic carrier hop. These tests are not physical SIM evidence.

Retained 2.1.2 release artifact hashes match the earlier receipts: Driver `e4ae57b23e283f6608422b6c9acf251f7315e210a66144cf50c06b4de5ac6d93`, Hub `4dbbe850cefa08823c9554acb56e8ae56f6b6cb56ceaf8237c9fb140636ca9ca`. Their Android Debug signer certificate SHA256 is `e8a9dec1977df7b260a99979d3c58867d01a00f5dfc587034e33513d868549f5`; this existing signer is preserved, not replaced. It is distinct from the new ECDSA manifest-signing key.

## Initial verification receipt — 2026-11-03

With `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`:

```bash
./gradlew --max-workers=2 --continue testDriverDebugUnitTest testHubDebugUnitTest \
  lintDriverRelease lintHubRelease assembleDriverRelease assembleHubRelease \
  --no-build-cache --console=plain
```

`BUILD SUCCESSFUL in 10m 9s`; 165 actionable tasks, 105 executed and 60 up-to-date. Full Driver XML: **247 tests, zero failures/errors, one optional loopback skip**. Full Hub XML: **219 tests, zero failures/errors, one optional loopback skip**. Release lint: Driver **0 errors/90 warnings**, Hub **0 errors/77 warnings**. SDK/manifest/deprecated-API warnings remain visible in the log.

The owned loopback fixture was then started at `http://127.0.0.1:60268` with synthetic SQLite/Redis/carrier data. The supplemental command was:

```bash
BARPRO_BOOTSTRAP_TEST_URL=http://127.0.0.1:60268 ./gradlew --max-workers=2 \
  testDriverDebugUnitTest --tests com.example.BootstrapLoopbackTest \
    --tests com.example.AnonymousBootstrapLoopbackTest \
  testHubDebugUnitTest --tests com.example.BootstrapLoopbackTest \
  --no-build-cache --console=plain
```

`BUILD SUCCESSFUL in 25s`: Driver **2 tests**, Hub **1 test**, zero failures/errors/skips. This executed both previously optional v1 interop cases and the added anonymous-v2 Driver case over actual owned TCP/SQLite/Redis/crypto. The carrier input was synthetic throughout; no live SMS or production API mutation occurred.

`apksigner verify --print-certs` verified both candidate APKs with the retained signer above. `apkanalyzer apk summary` reports `2.1.3-driver` / `2.1.3-hub`, versionCode **6**, application IDs `ir.barpro.fleet.smsforwarder.driver` / `.hub`; `apkanalyzer manifest debuggable` returned **false** for both.

Initial, superseded build SHA256: Driver `1bd3799295c9c33c1d5c91b63d24b22bb81296fb19b8bf604c302840882f0643`; Hub `8aa334650dbd8996a2d214ac050ad15c055412bbb310b8c056fe53d7c25aebcc`. The final consent-recovery correction below supersedes those artifacts. Build outputs are under `app/build/outputs/distribution/`. No release was uploaded and no handset was installed or tested.

Local logs: `/tmp/automatic-bootstrap-red.log`, `/tmp/automatic-bootstrap-green.log`, `/tmp/automatic-onboarding-red.log`, `/tmp/bootstrap-dispatch-red.log`, `/tmp/managed-defaults-red.log`, `/tmp/release-recovery-red.log`, `/tmp/automatic-driver-full-checks.log`, `/tmp/automatic-driver-live-loopback.log`. The full and supplemental XML totals were separately preserved in `/tmp/automatic-driver-full-receipt.json` and `/tmp/automatic-driver-loopback-receipt.json` before later filtering could overwrite them. `git diff --check` returned exit0.

## Historical consent-recovery verification — 2026-11-03

`AutomaticEnrollmentRecoveryTest.retainedGrantCannotReenableAnAlreadyEnrolledExplicitlyStoppedDriver` first failed with “A retained stage must not undo explicit forwarding stop” (`BUILD FAILED in 20s`, one behavioral failure). The shared runtime now recognizes an enrolled private profile before resuming a retained decoded grant; the Driver UI delegates recovery to that owner. Explicit forwarding/consent stop is preserved and no liveness SMS is dispatched by that recovery.

The same full JVM/lint/release command then completed **BUILD SUCCESSFUL in 7m 3s**, 165 actionable tasks, 38 executed and 127 up-to-date. Final Driver XML: **249 tests, zero failures/errors, two optional interop skips**; Hub: **219 tests, zero failures/errors, one optional interop skip**. Driver release lint: **0 errors/91 warnings**; Hub: **0 errors/88 warnings**. The added anonymous-v2 interop test and both optional v1 interop cases had separately passed over the owned TCP fixture as recorded above; this correction changes the recovery owner, not that protocol.

Historical log: `/tmp/automatic-driver-final-checks.log`; the consent RED log is `/tmp/automatic-optout-red.log`. The previous candidate was built after source freeze `33cd785869a5cfd39ee7d19d8089b43604627d76`, and its external `release-receipt.json` records the source SHA, APK hashes, signer, package/version and check totals. That provenance is external: AGP emitted `generate_error_reason: NO_VALID_GIT_FOUND` in both APKs' `META-INF/version-control-info.textproto`, so embedded source-SHA provenance was not established. The earlier embedded-metadata claim was inaccurate. Those test totals and APK hashes are historical and do not verify later source changes. Publication, installation and genuine handset/carrier activation remain unverified.

## Maintenance configuration race correction — 2026-11-03

`MaintenanceConfigRaceTest` pauses remote fetching in the real Worker, commits a newly enrolled profile through the real Room/secure-storage repository, and then releases a signature-verified tuning overlay. Both flavors first failed because the stale pre-fetch snapshot erased the new phone, private credential, consent and forwarding state (`/tmp/maintenance-config-race-red.log`, `BUILD FAILED in 9s`, two behavioral failures). Only the remote wait is substituted; ECDSA verification, audience parsing, Worker, database and secure writes execute production code. The fixtures are synthetic tuning-only test documents with epoch publication time for Robolectric's clock; they are not release-channel assets.

`SmsForwardRepository.updateConfig` now reads, patches and securely saves the latest profile under one `configAccessLock` acquisition. The private read/save helpers avoid reacquiring the non-reentrant mutex. `MaintenanceWorker` applies the snapshot through that atomic patch and records the version after successful save. Public Driver endpoint/token rejection remains in `RemoteConfig.parse`; legitimate Hub token rotation still updates both credential fields through `RemoteConfigSnapshot.applyTo`.

Reviewed candidates are produced separately from the earlier candidate and its Downloads copies. Their exact source freeze, artifact hashes, embedded-metadata result and fresh verification totals belong in the reviewed directory's external `release-receipt.json` and `verification.md`. No credential provisioning is added for a factory-clean Hub.
