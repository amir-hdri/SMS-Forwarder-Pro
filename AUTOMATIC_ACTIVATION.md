# Automatic activation candidate 2.1.4 — 2026-11-03

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

The public Hub APK preserves an already authorized profile. A factory-clean Hub can now use the approved organization-private one-use install capability described below; Android permissions alone do not create Hub authority. No enrollment link or typed phone, URL or token is required by either automatic flow.

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

Reviewed candidates are produced separately from the earlier candidate and its Downloads copies. Their exact source freeze, artifact hashes, embedded-metadata result and fresh verification totals belong in the reviewed directory's external `release-receipt.json` and `verification.md`. The separately approved private Hub provisioning flow below uses this same atomic patch.


## Approved private Hub installation — 2026-11-03

The user explicitly approved the limited exception to the no-hardcoded-credential rule: one short-lived, single-use provisioning capability may be packaged only inside an organization-private Hub APK. The global Hub secret stays server-side. Real provisioning capabilities and Hub credentials stay outside tracked source and public Hub/Driver APKs, config and release assets; tracked protocol fixtures use synthetic test values. Possession of the private APK before redemption permits first claim; keep it private.

`-PBARPRO_HUB_PROVISIONING_PROFILE_FILE=<absolute external private file>` supplies JSON with exactly `protocol=barpro-hub-install-v1`, trusted HTTPS root `server`, 43-character URL-safe `capability`, and integer epoch `expires_at`. Build with `--no-build-cache`; the asset task reads the current invocation and opts out of configuration cache so a prior cached flag cannot authorize a later private build. `HubProvisioningAssets` validates the profile, generates only the Hub variant's `assets/barpro_hub_install.json`, and removes prior generated assets on every invocation. A subsequent public build generates an empty asset directory. No profile value is embedded in tracked Gradle properties, manifests or BuildConfig; the latter contains only a boolean indicating the private asset.

After Android permissions and initial internet, one unique `HubInstallWorker` uses the existing AndroidKeyStore RSA key. The device ID is `hub_` plus the first 32 lowercase hex characters of SHA256(DER public key). `POST /api/v1/otp/hub-enroll` sends the capability, key-derived ID, public key and the existing v2 binding signature. The HTTPS transport disables redirects and retains CA/hostname verification. The backend binds the first device/key atomically and caches its encrypted grant for retry; another binding is refused. The capability remains a 24-hour server authorization, not a reusable global secret.

The encrypted grant uses `barpro-bootstrap-v2`, nonce equal to the capability, matching device ID and the existing RSA-OAEP/AES-GCM envelope. The decrypted `barpro-hub-v1` document includes that device ID, the same-origin global webhook endpoint, `HUB1_<16hex credential ID>_<64hex secret>`, and both Hub SMS routes. `HubInstallContract` rejects wrong role, device, credential format, HTTP, query, origin and expiry. The credential and pending encrypted grant live in `SecureStorageManager`; Room stores a redacted private profile. A completed capability hash prevents repeat redemption and allows a newly packaged capability to rotate credentials without restoring prior explicit stop/consent. Public upgrades preserve the stored private identity and can resume a staged encrypted grant without a private asset.

`HubRequestAuth` signs each actual scoped-credential network attempt independently. The exact RSA message is `barpro-hub-request-v1\n<device_id>\n<METHOD>\n<ENCODED_PATH>\n<timestamp>\n<nonce>\n<SHA256(raw body bytes)>`; the 16-byte nonce is freshly generated for every network attempt, including OkHttp follow-ups. Authentication is restricted to same-origin HTTPS POSTs at sms-forwarder/sms-gateway (optionally phone-scoped) and bootstrap/prove, without query strings. Public config/update fetching and legacy global/Driver credentials are unaffected.

A device-scoped Hub cannot derive a Driver's BP1 HMAC key. It accepts only structurally valid, bounded one-SMS envelopes through the existing deduplication, consent, encrypted outbox and retry controls; its authenticated server verifies the Driver HMAC. Legacy Hubs retain local HMAC filtering. Malformed/oversized BP1 input does not enter the outbox, and a forged valid-shape envelope remains unsuccessful after the server's rejection. Receiving a grant is not a health, modem, SIM, carrier or OTP-delivery witness.

Behavioral RED logs: `/tmp/private-hub-activation-red.log` (missing device proof, profile/activation gaps), `/tmp/private-hub-relay-red.log` (valid Driver envelope skipped and oversized parsing), `/tmp/private-hub-capability-rotation-red.log` (new capability ignored). Focused GREEN logs are dated verification snapshots; complete results and artifact provenance are recorded separately in the reviewed candidate receipt after full checks. `HubPythonInteropTest` consumes production Python crypto with a clearly synthetic test-only RSA key/capability/token. `OwnedHubTlsServer` uses a synthetic test certificate trusted explicitly by the injected test client; the untrusted-CA negative exercises the default production client. No production mutation, SMS send, handset installation or publication is part of these local controls.

Final review candidate version: **2.1.4 / versionCode 7**. Earlier 2.1.3 APKs are preserved separately; the existing Android signing certificate and persistent public channel key are retained. Source freeze and complete fresh validation belong to the reviewed candidate receipt.


## Review freeze controls — 2026-11-03

With the same JDK21 and `--max-workers=2 --continue --no-build-cache --console=plain`, the focused review command executed real maintenance-race, private Hub contract/activation, TLS request, encrypted relay and Python interop controls. `/tmp/private-hub-review-focus.log` reports **BUILD SUCCESSFUL in 1m 14s**; Driver **21 tests**, Hub **27 tests**, zero failures/errors/skips. These are focused totals, not the complete app suite; `/tmp/private-hub-review-focus-receipt.json` preserves the XML totals before full checks.

`python3 tools/verify-private-hub-assets.py` executed **7 actual Gradle controls**: private generation, changed capability, public-after-private purge, equivalent root spelling, cache-enabled refusal, wrong-origin refusal and expiry refusal. An intentional purge omission first produced the expected public asset leak failure (`/tmp/private-hub-asset-purge-red.log`). A separate run exposed a stale configuration-cache build-cache flag; the task now checks the current invocation outside configuration cache. The final corrected result is in `/tmp/private-hub-asset-controls-green.log`.

The final source uses version 2.1.4/code7. Independent source review precedes final public/private APK copies. Full app JVM/lint checks and APK signer/hash/asset verification need their own fresh execution receipts; the prior 2.1.3 check totals and artifacts are not reused for this source.
