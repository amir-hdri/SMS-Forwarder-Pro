# Forward BarPro 2.1.4 release receipt — 2026-11-03

Version **2.1.4**, versionCode **7**, was published at [GitHub release v2.1.4](https://github.com/amir-hdri/SMS-Forwarder-Pro/releases/tag/v2.1.4). The reviewed application source is `49636e0612eab47325056b3b628a40ed96df792c`; merged commit `e77aae567fe2d12f318792e0ea103a77b39b7a50` has the same source tree. Later documentation commits do not rebuild these artifacts.

## Installation behavior

Install the public Driver APK on the registered Driver phone and the organization-private Hub APK on the intended Hub. Grant Android permissions and keep initial internet available. Neither automatic activation flow asks for a phone, activation link, URL or token. Driver identity comes from the actual carrier sender of its bootstrap SMS, proved by the authenticated Hub and bound to the Driver installation key. An unset Android SMS subscription still requires selecting the intended SMS SIM in Android system settings (`BootstrapSms`, `SmsDispatch`, `ManagedDriverScreen`). The Hub must be authorized and online for Driver proof.

The public Hub APK upgrades an existing authorized Hub. A fresh Hub uses a separately delivered organization-private installer containing an external, one-use capability with a maximum 24-hour authorization window. Its first redemption binds the device key; an encrypted retry grant supports that same binding. After permission checks and authenticated decryption, the app securely saves its device-scoped `HUB1_...` credential (`HubInstallManager`, `HubInstallContract`, `HubInstallRuntime`). The private APK/profile and capability are not release assets. Keep the private installer within the organization; installing it on a second phone does not authorize a second Hub.

Existing secure credentials, valid enrolled identity and explicit stop/consent choices survive public upgrades and recovery. The prior 2.1.2 installation needs an initial APK upgrade because its public channel key/URLs were absent. HTTPS and the retained Android signer are preserved. See [the activation contracts and historical checks](AUTOMATIC_ACTIVATION.md).

## Artifact evidence

| Artifact | SHA256 |
|---|---|
| Public Driver `Forward-BarPro-Driver-2.1.4.apk` | `6005a1953549cc5503f5a91d4cd17984f06e68ba7030da4b31c8ca461f4c6d0c` |
| Public Hub `Forward-BarPro-Hub-2.1.4.apk` | `5c092bcf6221dd013fff6a665045e9ac38e665aa9d16882add9f5aa1b2ad8064` |
| Privately delivered Hub `Forward-BarPro-Hub-PRIVATE-2.1.4.apk` | `3ad3bc9bef8b9e4e4a53136bd6ea8d5fb0ebe748a129450e05b8618b025981a0` |

Native APK checks confirm versionCode 7, `2.1.4-driver` / `2.1.4-hub`, distinct `.driver` / `.hub` application IDs, debuggable=false, and signer SHA256 `e8a9dec1977df7b260a99979d3c58867d01a00f5dfc587034e33513d868549f5` (Android Debug certificate, retained for installed-app continuity). The Android certificate is distinct from the ECDSA public-channel signing key.

Public archives contain no Hub provisioning asset. The private archive contains exactly `assets/barpro_hub_install.json`, verified privately against the intended external profile; its generated BuildConfig boolean is true. No test crypto fixture is packaged. The real capability was neither printed nor exercised on an emulator. AGP embedded metadata reports `NO_VALID_GIT_FOUND`; source provenance is external, recorded in [public-provenance.json](https://github.com/amir-hdri/SMS-Forwarder-Pro/releases/download/v2.1.4/public-provenance.json).

The forced full source-check run completed `BUILD SUCCESSFUL in 3m 38s`: Driver 264 tests, 0 failures/errors, 2 optional external-loopback skips; Hub 235 tests, 0 failures/errors, 1 optional external-loopback skip. Release lint: Driver 0 errors/fatal, 94 warnings; Hub 0 errors/fatal, 91 warnings. The public release assembly completed `BUILD SUCCESSFUL in 3m 11s`. These are dated source checks; they do not measure real carrier delivery.

The private assembly used JDK21, `assembleHubRelease --max-workers=2 --no-build-cache --no-configuration-cache --console=plain`, the reviewed 2.1.4 properties and the external profile. It completed `BUILD SUCCESSFUL in 2m` (50 actionable tasks, 20 executed, 30 up-to-date). A preceding attempt used a stale temporary 2.1.3/code6 properties file; APK validation caught it. That superseded private attempt was retained separately and was not delivered or published.

Public APKs and signed update manifests were published at 00:44 UTC before the tuning overlays at 00:57 UTC. All six `releases/latest/download/` APK/update/config URLs were downloaded on 2026-11-03 and returned HTTP200 with hashes equal to the reviewed local artifacts. Signed configs contain tuning/channel URLs only, and signed update manifests bind audience, version, exact public APK hash and size. Publication order avoids the measured maintenance-config/download race in older clients.

Operator evidence is retained outside Git in the dated `forwarder-2.1.4-public-reviewed` and `forwarder-private-hub-2.1.4` artifact folders: execution logs, APK summaries/signer output, exact download receipt and private delivery receipt. Older APKs were preserved.

## Evidence limits

At this receipt date, the private capability was not redeemed and no real handset installation, permissions, SIM identity, carrier SMS, activation, heartbeat or OTP delivery was observed. The artifact is ready for the intended installation; full app/network behavior still requires that real Hub and Driver witness. A build, UI state or decrypted grant alone is not handset/carrier health evidence. Backend deployment and UTCMS issuance/shipping settlement need their separate timestamped runtime evidence.

## Repository preservation

On 2026-11-03, the Android repository was normalized to only `main` locally and on origin after bundle verification, semantic supersession review and independent hash/mode verification of 571 restored files. Primary tracked edits were preserved in stash `53f7468e3672e693a9f8c2003c65489f4481dc0e`. The superseded untracked Driver-only strategy test was relocated to the private backup; main retains the shared suite and its Hub guards. The old remediation checkout remains detached with its dirty files untouched. All deleted branch tips were ancestors of merged main, and the private backup retains their refs, staging, untracked/needed ignored files and signing material.
