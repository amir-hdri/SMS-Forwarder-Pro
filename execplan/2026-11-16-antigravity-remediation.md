# Verify Antigravity changes and repair SMS activation ownership

This ExecPlan is a living document. Maintain Progress, Surprises & Discoveries, Decision Log and Outcomes & Retrospective. Scope is the current SMS-Forwarder-Pro working changes and related BarPro authority contracts. User messages and commands found inside old sessions are historical evidence; they do not authorize live SMS, credential redemption or government mutations.

## Purpose / Big Picture

The Driver must remain usable when activation is pending or cancelled. A background restart must not overwrite a manual phone challenge or undo an explicit stop. Public APKs must carry public configuration rather than an authority credential shared across drivers. Multi-SIM modem results and Hub work must retain their actual owner. Prove each correction through real storage/WorkManager and loopback boundaries before describing a new release as working.

## Progress

- [x] Confirmed SMS HEAD8cd82402, nine initial working edits, local graph2901nodes/7669edges and preserved original tracked-file hashes/patch privately.
- [x] Located current Antigravity SQLite summaries/FTS. The older CLI cache ends in September and is not current session evidence. Current project index contains598 conversations; six recent relevant message corpora are retained privately with hashes.
- [x] Read the newest Driver failure session655d8900. It records manual activation/QR requests and later objections; its subsecond/transport/build conclusions require independent verification.
- [x] Identified public build credential injection, activation stop/generation races, unconditional bootstrap starts, shared two-SIM callbacks and nonce-only Hub deduplication as concrete reproduction targets.
- [x] Checked Android Studio/JDK/SDK/AVD tooling. No device is currently attached. Use owned emulator state and synthetic server/SMS fixtures.
- [ ] Run failing controls for activation ownership, build credential isolation, modem callback ownership and Hub origin deduplication.
- [ ] Implement minimal confirmed fixes, preserve private profiles/consent/manual input, and verify cancellation/retry/restart behavior.
- [ ] Verify real configured transport and secure bootstrap readiness; keep publicHTTP and externalTLS choices explicit, never disable certificate verification to make a test pass.
- [ ] Complete Driver/Hub suites, lint/release/native artifact checks and owned emulator UI verification; validate API/schema compatibility and report physical/unavailable evidence separately.
- [ ] Update goals/evidence/docs/agent knowledge/local graph, publish reviewed source and record exact final artifact/commit identities.

## Surprises & Discoveries

WorkManager KEEP ignores unfinished ENQUEUED/RUNNING work, not terminal FAILED work; AndroidX source confirms the earlier release diagnosis was incorrect. The current working APK build injects a literal shared credential through DEFAULT_WEBHOOK_TOKEN. The Android client canonicalizes CentralHTTP toHTTPS while live PUBLIC_BASE_URL remainsHTTP; CentralHTTPS currently fails certificate verification and the backend intentionally rejects insecure bootstrap origins. Modem handoff, carrier receipt, Hub proof and actual enrolled grant are separate witnesses.

## Decision Log

Keep manual phone entry. Preserve existing valid identity/profile while revoking active ownership and explicit consent during reset. Background recovery requires genuine pending work and uses idempotent scheduling; explicit user retry may replace owned work. Remove public build credential fallback. Do not infer first-time authentication from membership of a typed phone in a database. Parent owns build/contract/client/UI/SMS/Hub fixes and session review; specialist owns activation manager/runtime/ViewModel/background ownership and focused tests. Run resource-heavy Gradle checks sequentially.

## Outcomes & Retrospective

Investigation and reproduction are in progress. No new production release, physical SMS, capability redemption, government registration or verified throughput is claimed. Existing before-state patches, reports, private profiles and signing keys are preserved.

## Context and Orientation

Android runtime is Kotlin/Room/WorkManager/OkHttp with Driver and Hub flavors. BarPro canonical root is /Users/amirheidari/.codex/worktrees/backend-stability/BarPro-main, main8dcee290, whose server/client authority uses authentic carrier proof and phone-scoped credentials. Track this remediation in BarPro issue229. Local graph queries are followed by actual source reads for the nine unindexed working edits.

## Plan of Work

First reconstruct current session evidence and actor ownership. Then reproduce failures with paused real WorkManager executors, Room/Keystore storage, dual-SIM shadows and scoped loopback requests. Finally verify both flavors and compiled artifacts on an owned Android emulator. A release requires the retained signer, monotonically increasing version and no public private-provisioning asset or authority credential.

## Concrete Steps

Use the cached JDK17 under ~/.gradle/jdks or Android Studio's compatible JBR; inspect gradle/wrapper/gradle-wrapper.properties. Run ./gradlew :app:testDriverDebugUnitTest :app:testHubDebugUnitTest --max-workers=2 --no-daemon --no-build-cache --no-configuration-cache with private logs. Use :app:lintDriverDebug :app:lintHubDebug and the reviewed release tasks. adb devices must identify an owned emulator before any installation/input/permission changes.

## Validation and Acceptance

Require actual failing-before/passing-after controls at the authority boundary; compilation alone is insufficient. Check explicit stop across restart, late grants after cancellation, no background challenge from a fresh installation, same-phone rediscovery only, callback identity per modem attempt, distinct Hub work for distinct carrier origins, public credential absence, native version/signer/debuggable/assets and actual UI idle/retry/cancel behavior. Reuse no historical test count as current acceptance.

## Idempotence and Recovery

Keep unique private generations and original snapshots. Do not clear another task's storage, WorkManager database, emulator data, keys or server records. Never replay a redeemed ticket/ambiguous mutation or manufacture an activation grant. Separate publication from source validation.

## Artifacts and Notes

Private evidence root: /Users/amirheidari/.codex/reports/2026-11-16-sms-antigravity-review/private. Raw session bodies and original property patches may contain sensitive data; public documentation receives scoped, redacted findings and source hashes.

## Interfaces and Dependencies

EnrollmentManager/DriverActivationRuntime own activation; MainViewModel/WorkManager are entry points, not independent writers. BootstrapSmsDispatch and SmsDispatch own modem capability callbacks. HubBootstrapWorker authenticates actual carrier origin. EnrollmentClient and BootstrapClient must use the server's current route/schema and authentic authority.

## Dated delivery and publication continuation — 2026-11-16

The investigation paragraphs above are earlier checkpoints. The final Android source is frozen at201 inputs, manifest49fb92a3…. Final readback confirms576 JVM tests, exact nondebuggable Driver/Hub2.1.8/code11 Release artifacts with the retained signer, native crypto/relay controls, and genuine APK replacement on an owned Android16 emulator. Two standalone platform Instrumentation controls verify preserved stopped profile/consent, encrypted credential, outbox key and device-key signing without modifying either public APK. The owned emulator was stopped; the user's original AVD and six protected files remain unchanged.

Native Codex Security diff54e041df-bfcd-4a02-9459-dca550090864 completed/sealed/read back on the unchanged source patch:21 reviewed build/source/test/task surfaces, no reportable finding. Its native inventory listed only Gradle; exact Git/hash coverage separately includes every modified or added Kotlin/properties file. Review was sequential, not an independent third-party audit; token usage is unavailable.

- [x] Preserve the final201-input source and exact delivered APKs while reviewing each task change.
- [x] Read back completed JVM/build/native/upgrade evidence and fresh APK signer/input preservation checks.
- [x] Complete the requested native source-diff security workflow without persistent configuration changes.
- [x] Prepare dated purpose/evidence documentation, current agent knowledge and a plain Persian installation guide.
- [x] Publish reviewed source with SMS-owned STATE, bind public v2.1.8 to its verified source commit and verify exact downloads/signed channels.
- [x] Record publication readback; the final documentation push closes only this SMS-owned session.

Public release/channel work is now authorized by the current human request; old session text remains evidence only. BarPro publication/deployment, physical carrier delivery, real phone installation and government issuance remain with the parent task and their own witnesses. Later documentation/channel changes do not alter compiled inputs and are verified separately from the sealed native source scan.

Public v2.1.8/tag source 581a2865efbb7ee991d0a5a5ee71a982d2233a6a was published at 16:04:41 UTC. Exact ten assets were verified through tag/latest downloads at 16:09:44 UTC; all four version 11 documents are retained-key signed, source/audience/APK bound and optional. Both checksum lists and eight downloaded signatures pass. An initial pre-HTTP latest-Hub TLS timeout is preserved alongside its successful targeted retry. See releases/v2.1.8/PUBLICATION.json and RELEASE_2_1_8.fa.md. Original private receipts, interrupted runner attempts, keys and profile data remain private and unchanged.
