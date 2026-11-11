# Forward BarPro 2.1.5 verification — 2026-11-11

Version 2.1.5/code8 fixes update recovery and truthful operational display for both Hub and Driver. Final local native and TLS receipts are complete. The public release was published on 2026-11-11 at 16:27:39UTC and independently downloaded and verified at 16:31:21UTC.

Public release: https://github.com/amir-hdri/SMS-Forwarder-Pro/releases/tag/v2.1.5. The release and tag bind application source `e00974ccd731c6fdb559a78e688c7ac63c264310`; subsequent documentation commits do not change that APK provenance. All seven public assets returned HTTP200 through the latest-download channel and matched their exact local SHA256 and size. Receipt: private `android-emulator/publication-2.1.5/published-verification.json`.

## Changes

- A manually installed compliant APK clears the accepted mandatory-version gate at startup without changing private identity, forwarding stop, consent or automatic-install preference. Config and release requirements retain their own signed revisions. A newer explicit zero may withdraw a requirement; an omitted config minimum preserves it and cannot resolve an unknown legacy gate.
- Legacy Boolean-only gates fetch authenticated release metadata even with automatic installation disabled. Actual installation still requires the latest preference before download and under the configuration lock immediately before PackageInstaller commit.
- Preference write failure keeps the update gate closed in memory and does not abort application recovery startup. Authentic malformed or wrong-audience release documents are rejected instead of reporting an up-to-date installation. Valid stale documents preserve rollback protection and normal steady state.
- Driver enrollment is labelled as stored identity, independently of current SMS/connection health. Unknown/checking server health is distinguished from disconnection. An explicitly stopped profile does not automatically request the background execution exemption; enabling forwarding rechecks that permission.

## Executed evidence

The complete JVM/lint/release/debug/androidTest build completed with exit0 in12m43s. Driver:278 tests,0 failures/errors,2 optional interop skips. Hub:249 tests,0 failures/errors,1 optional skip. All three optional cases then executed against the owned Python TCP/SQLite/Unix-socket Redis fixture: Driver2 and Hub1,0 failures/errors/skips. Carrier input was synthetic throughout. Release lint has0 errors/fatal and95 warnings per role; warnings are retained.

Actual Android16/API36 on the task-owned local emulator exercised RSA signing and OAEP/AES-GCM binding/tamper rejection through AndroidKeyStore for both packages; native receipt-status parsing, PendingIntent cancellation, encrypted storage, and the Hub's real local HTTP gateway path passed. Driver home/settings contained no operator text inputs. This does not measure genuine carrier delivery.

The initial nondebuggable code8 release candidates were actually installed over code7 with the retained Android signer. Both full configuration-row hashes, exact encrypted-preference files and the public-key marker were unchanged; the stored required-code8 gate changed true→false. Explicit forwarding, consent and automatic-install flags remained false. The final UI-only build completed with exit0 in6m46s; seven focused tests passed without failures/skips. Final release APKs were actually installed over the code8 candidate on both packages; encrypted preferences and the key marker again remained unchanged. Driver displayed stored identity rather than a live-connection claim, Hub showed the untested server state, and stopped profiles had no background-exemption prompt.

Final public Driver SHA256: `5fb546e5d039aaa3343f8be6e8c88731578c35217f5c060a22a817843d2d7f04`; Hub: `0ae338258eb1f570937ddb6c9c4ef1f203d2a02e1355dbdd811ed29d8514cf98`. Both remain nondebuggable and signed with the retained certificate.

Private evidence lives under the task's `remediation-2026-11-11/android-emulator` directory: full-check/RED/GREEN logs, 244-input source receipt, native class results, signed APK metadata, owned interop receipt and `actual-release-upgrade-receipt.json`. No private provisioning capability was redeemed. Public APKs have no private Hub asset or crypto test fixture. Android signer continuity is `e8a9dec1977df7b260a99979d3c58867d01a00f5dfc587034e33513d868549f5`; the separate retained ECDSA public-channel anchor is unchanged.

## Limits

Public Hub upgrades preserve an already authorized profile; a factory-clean real Hub still needs its separate organization-private installation capability. Initial internet, permissions and a selected SMS SIM remain necessary for Driver carrier-origin activation. Emulator inputs, a stored profile, a build or a displayed badge do not prove real handset health, receipt of genuine SMS, UTCMS issuance or accepted end of shipping. BarPro's runtime and three-witness settlement evidence are separate.

## Final device/network receipt

On2026-11-11 at14:52UTC the final debug/test variants repeated native relay safety, AndroidKeyStore crypto, private-identity/explicit-stop upgrade assertions for both roles and the Driver home/settings restriction. Every selected class passed; the Driver suite's Hub-only case is an explicit role skip. At15:07UTC both applications independently accepted the deployed HTTPS health connection using their embedded organization CA, with platform certificate verification enabled. The production APKs had separately passed actual release installation/UI and encrypted-file preservation at14:44UTC; debug fixtures are not public delivery artifacts.

The public v8 config/update channel remains optional. Config metadata now explicitly declares minimum0, matching the existing public channel's lack of a mandatory minimum and allowing a new client to resolve a Boolean-only legacy requirement from both authenticated sources. It changes no private identity, endpoint credential, consent or automatic-install choice. Signed update manifests bind the exact final nondebuggable APK hash/size and an immutable v2.1.5 download URL.
