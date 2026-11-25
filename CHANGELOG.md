# Changelog

## 2.1.11 — 2026-11-26

- Add monotonic `ServerClock` HTTP `Date` synchronization and automatic monotonic retry on clock-skew `410`/`422` rejections without relaxing backend `otp_delivery.py` bounds.
- Persist signed `RemoteConfig` JSON in `UpdateStore` across app/repository restarts and wire `remoteKeywords` directly into `SmsParser.extractOtp`.
- Preserve `SENT_VIA_SMS_PENDING_SYNC` outbox state on Driver fast GSM fallback failures, bound fast cellular acquisition/timeout, and cache PBKDF2-derived `AesEncryptionUtils` keys.
- Allow single-SIM Hub enrollment (`HubInstallContract`), clean default endpoint strings across `ServerConfigScreen`/`SmsForwarderClient`/`MainActivity`, check notification permission, notify missing `canInstallPackages` permission in `AppUpdateManager`/`UpdateNotifier`, and prompt optional `READ_PHONE_STATE` when `needsSmsSelection` triggers (`versionCode=14`, 670/673 JVM passes + 3 optional loopback skips, 4 lint variants with 0 errors, live Android 16 `emulator-5556` in-place upgrade & SMS capture verified).
- Remove superseded `v2.1.10` release files (`releases/v2.1.10/`, `RELEASE_2_1_10.fa.md`, `INSTALL_2_1_10.fa.md`) so only `v2.1.11` remains.

## 2.1.10 — 2026-11-25 (superseded / deleted)

- Switch Driver, Hub, and release update/config channels to central HTTP (`http://87.107.160.251`) and align `SmsReceiver` delivery feedback so non-2xx relay failures are not falsely reported as delivered.
- Enforce atomic `STOP`, consent, credential, and mandatory-update guards across Worker, Manager, and Runtime bootstrap paths (`versionCode=13`, 663 JVM passes: Driver 361/361 + Hub 302/302, 4 lint variants with 0 errors).
- Retain only `v2.1.10` (`RELEASE_2_1_10.fa.md`, `INSTALL_2_1_10.fa.md`, `releases/v2.1.10/`) and remove superseded `releases/v2.1.8` and `releases/v2.1.9`.

## 2.1.9 — 2026-11-21

- Authenticate cached registered profiles/grants with their own scoped credential. Preserve request generation, STOP, consent, RSA and the proved profile after awaits. Accepted duplicate links resume the selected phone without redeeming a consumed ticket.
- Public APKs exclude shared-root authority and private installer assets; retained signer and nondebuggable code12 packages are verified.
- Recorded acceptance:612 JVM passes/three optional skips, four lint variants with zero errors/82 warnings each, and194 standalone-backend controls (159native +35independent). That adapter is not BarPro production.
- Publish immutable [v2.1.9](https://github.com/amir-hdri/SMS-Forwarder-Pro/releases/tag/v2.1.9) at26e0800d: ten assets, 20 exact-byte HTTP200 tag/latest reads, eight signed documents and four tamper negatives. Optional update/minimum0 and retained channel keys remain.
- Clarify203 as the frozen base/test input set, with R8 separately bound in [BUILD_INPUT_SUPPLEMENT.json](releases/v2.1.9/BUILD_INPUT_SUPPLEMENT.json). Immutable assets/tag are unchanged.
- Build and privately retain the one-use Hub after cutover; correct its filename in [installation steps](INSTALL_2_1_9.fa.md). It is unredeemed/uninstalled; handset/SMS/new issuance remain unverified. [Measured limits](RELEASE_2_1_9.fa.md).

## 2.1.8 — 2026-11-16

- Keep manual phone entry and require an authentic carrier-origin, device-bound grant for first activation; rediscovery reuses only the same registered phone's scoped credential.
- Share one activation owner across UI, background work and late callbacks. Persist the selected phone/device and explicit stop across restart; reset keeps the private profile while revoking readiness and consent.
- Use WorkManager KEEP for recovery of existing pending work and REPLACE for an explicit retry. One modem owns each SMS attempt; Hub work binds actual carrier origin plus nonce and rechecks current consent/profile after network acquisition.
- Keep the proved driver's name with its identity, show loading only for a current activation, and provide retry/reset/cancel actions. Network selection does not prove an Iranian egress or successful carrier delivery.
- Reject public webhook authority in build properties and require the retained release signer. Both nondebuggable Release APKs are code11, version2.1.8, and byte-identical to the delivered files.
- Read back 576 passing JVM tests, exact Release builds/owned Android16 installations, native controls and upgrade preservation. Native Codex Security diff review is sealed with 21 reviewed surfaces and no reportable finding; later documentation/channel publication is verified separately.
- Publish [v2.1.8](https://github.com/amir-hdri/SMS-Forwarder-Pro/releases/tag/v2.1.8) at source `581a2865efbb7ee991d0a5a5ee71a982d2233a6a`. All ten public assets match through both tag/latest URLs; one initial latest-Hub TLS timeout passed its targeted retry. Both checksum lists and all eight downloaded signed documents verify. Version 11 channels remain optional with minimum 0.
- Purpose, dated evidence and limits: [RELEASE_2_1_8.fa.md](RELEASE_2_1_8.fa.md). Installation steps: [INSTALL_2_1_8.fa.md](INSTALL_2_1_8.fa.md). Earlier latency and unconditional WorkManager claims below are historical statements, not acceptance for this release.

## 2.1.6 — 2026-11-15

- Restore direct manual driver phone entry in `ManagedDriverScreen.kt` with LTR isolation, Persian digit normalization (`۰۹...`), and 1-tap instant resolution, eliminating carrier-bound bootstrap deadlocks.
- Fix WorkManager unique work scheduling in `DriverBootstrapWorker.kt` by switching from `ExistingWorkPolicy.KEEP` to `ExistingWorkPolicy.REPLACE`, resolving no-op UI lockouts after transient network failures.
- Eliminate unprompted auto-trigger loop on initial launch, ensuring UI remains responsive and interactive immediately upon grant of SMS permissions.
- Resolve concurrency race between manual phone resolution coroutines and background worker dispatch in `MainViewModel.kt`, preventing pending phone challenge clobbering in Redis and `HTTP 410` errors.
- Synchronously reset UI state and cancel ongoing activation coroutines on `resetDriverActivation()`.
- Refactor `ServerConfigScreen.kt` to present a dedicated Hub terminal card for `APP_ROLE == "HUB"` while cleanly delegating `APP_ROLE == "DRIVER"` to `ManagedDriverScreen.kt`.
- All 65 test tasks pass 100% green (`testDriverDebugUnitTest` 57 test suites, `testHubDebugUnitTest` 33 tasks).
- Produce optimized, R8-minified production Driver release APK (`4.2 MB`) under `app/build/outputs/distribution/Forward-BarPro-Driver-driverRelease.apk`.
- Full evidence and verification details: [RELEASE_2_1_6.md](RELEASE_2_1_6.md).

## 2.1.4 — 2026-11-03

- Publish the reviewed code7 Driver/Hub APKs and signed update manifests before the public tuning overlays; all six latest-download URLs returned HTTP200 with exact artifact hashes.
- Deliver the organization-private Hub installer separately with an external, 24-hour one-use capability and device-key binding. Driver and private Hub activation require installation, Android permissions and initial internet; an unset SMS SIM still requires Android system selection.
- Preserve secure credentials and explicit stop/consent across recovery, retain both signing identities and report source/build evidence separately from unobserved real handset/carrier delivery.
- Normalize local/origin Android branches to main after verified backups and semantic review; preserve earlier tracked, untracked and detached-checkout edits privately.

Exact artifact hashes, commands/results and limits: [RELEASE_2_1_4.md](RELEASE_2_1_4.md).

## 2.1.3 candidate — 2026-11-03

- Start Driver activation automatically after Android permissions, with no typed phone, enrollment link or technical fields; derive registration from authenticated Hub carrier-origin proof and a v2 device-bound encrypted grant.
- Persist activation SMS attempts and use the existing modem callback/PDU machinery for bounded same-nonce retries; local radio/delivery acknowledgements do not mark enrollment active.
- Preserve an existing Hub private profile and enrolled Driver settings while seeding public role-specific config/update channels. Reject public Driver endpoint/token overrides and recover a failed download of the same authenticated release.
- Keep the existing HTTPS CA and Android APK signer. Establish the first persistent ECDSA public-channel trust key. This is a source/build candidate, not a handset installation or a published release.

Evidence and runtime limits: [AUTOMATIC_ACTIVATION.md](AUTOMATIC_ACTIVATION.md).
