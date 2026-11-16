# Changelog

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
