# Driver activation from BarPro

## Automatic SIM activation — 2026-11-01, version2.1.2/code5

The default Driver screen now accepts the registered phone directly. Install the updated Driver and Hub APKs, keep the configured Hub online, grant receive/send SMS permissions, and choose the entered SIM as Android’s default SMS subscription. Initial activation requires Driver internet and one SMS to the Hub. No activation link or global credential is required from the Driver. Existing protected rediscovery remains supported; missing credentials or HTTP401/403 choose this flow. Only HTTP405 may negotiate legacy GET. Arbitrary HTTP error bodies are not shown.

`BootstrapCoordinator` persists its phone/device/challenge and modem-handoff marker in `SecureStorageManager` encrypted preferences. `DeviceBootstrapKey` generates RSA2048 in AndroidKeyStore with sign/decrypt purposes; production private key bytes are non-exportable. After process death the same challenge is polled without automatically sending a second SMS. A known pre-handoff failure can retry dispatch. An ambiguous dispatch remains sent until expiry; after900seconds a new attempt can create a challenge. A foreground attempt polls at most60times, three seconds apart; the retry button resumes pending proof. Neither timeout nor a malformed grant replaces a working configuration.

The Hub `SmsReceiver` extracts `SmsMessage.originatingAddress`, requires matching origins across segments, and sends `BPA1#<nonce>` messages to `HubBootstrapWorker`. Notification-listener and ordinary repository capture cannot grant a SIM identity, and activation messages are excluded from OTP/outbox capture. The worker uses the Hub’s existing global credential over HTTPS and retries transient failures through WorkManager within the900second horizon. SIM proof alone is never a forwarding-health or permissions witness.

After device-signature verification the server returns only encrypted phone-scoped credentials. The Driver decrypts, validates `EnrollmentContract`, and stages with the existing `EnrollmentManager` before persisting through `SmsForwardRepository`. Room stores redacted credential columns; the secure credential store holds secrets. Normal signed health runs only after successful activation and required SMS permissions. Modem handoff or an APK build is not evidence of physical carrier delivery, handset installation, or current server health.

### Stable wire contract

All paths are POST beneath `/api/v1/otp/bootstrap/` and successful responses carry `Cache-Control: no-store`. Provisioning uses the configured HTTPS origin. The public challenge is limited to three attempts per phone and device,20per IP and1000global per900seconds; counters are atomic and Redis failure refuses admission. Device IDs match `[A-Za-z0-9_-]{8,128}`. A new challenge supersedes that device’s prior nonce. Registered driver identity/status is rechecked before any grant, including cached delivery.

| Path | Request | Response |
|---|---|---|
| `challenge` | `driver_phone`, `device_id`, `public_key` (canonical RSA2048 SPKI DER standard Base64, exponent65537) | `protocol=barpro-bootstrap-v1`,43character URL-safe `nonce`, `expires_in=900`, `hub_phone_mci`, `hub_phone_irancell` |
| `prove` | `origin` (carrier address), `nonce`; global Hub `X-OTP-Webhook-Token` | `status=proved`; repeated same proof is idempotent without extending expiry; scoped Driver credentials rejected |
| `poll` | `nonce`, `device_id`, `signature` | `status=pending`, or encrypted grant fields below |

Signature bytes are RSA PKCS1v1.5 SHA256 (`SHA256withRSA`) over UTF8 `barpro-bootstrap-v1\n<nonce>\n<device_id>`, with actual LF characters and no final LF. The encrypted response is `{status:granted, protocol, nonce, device_id, wrapped_key, iv, ciphertext}`. Its wrapping is RSA-OAEP SHA256 / MGF1-SHA1 / empty label, explicitly matching AndroidKeyStore. The unwrapped key is AES256; GCM uses a12byte IV and128bit tag appended to ciphertext. The exact signature binding bytes are GCM AAD. All binary fields use standard Base64. Concurrent/repeated signed polls receive the same cached encrypted grant until original expiry; proof or polling does not extend TTL. Wrong origin/device/signature and expired or superseded challenges fail closed.

The decrypted document retains `barpro-driver-v1`, the normalized phone, origin-pinned phone-specific webhook URL,64hex phone-scoped token and public Hub routes. The global Hub secret is never returned. The fixture `app/src/test/resources/bootstrap-server-grant.json` contains only a generated test private key and synthetic credentials, produced by actual Python bootstrap routes with owned Redis. `BootstrapEnrollmentIntegrationTest` decrypts it independently through JCA and validates Room staging; `BootstrapLoopbackTest` additionally runs against `tests/bootstrap_loopback_server.py` in BarPro, using actual HTTP and a synthetic carrier adapter.

### Local verification receipt —2026-11-01

`JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home BARPRO_BOOTSTRAP_TEST_URL=http://127.0.0.1:61842 ./gradlew --max-workers=2 --continue testDriverDebugUnitTest testHubDebugUnitTest lintDriverRelease lintHubRelease assembleDriverRelease assembleHubRelease --console=plain` completed **BUILD SUCCESSFUL in9m3s**. JUnit XML:Driver226,Hub194,total420;0failures,0errors,0skips. Both release lint reports:0errors,88warnings. The selected-SIM modem control coveredSDK28/34. Initial release lint detected an API29-only validity method; the numeric sentinel check now supports minSdk24 without suppressing the lint check. Earlier failures and corrective receipts remain recorded separately.

Both minified, non-debuggable release APKs verify with certificate SHA256 `e8a9dec1977df7b260a99979d3c58867d01a00f5dfc587034e33513d868549f5`, matching the prior local2.1.0/code3 artifacts. This proves signer continuity with those artifacts, not the signer installed on inaccessible remote phones. Both packages report2.1.2/code5.

- Driver APK SHA256: `599bb10ab066b2f16535b51b7771a40a0e3b6b4d171859819c8e5da2c4d56e4b`.
- Hub APK SHA256: `0e7e09b8151299168b409067dc100ab68366f8a435af83b0a778c578cdcd98a6`.

The backend related suite passed151tests in23.62s, including independent phone/device/IP budget controls. Python source lint/format checks passed, and scoped mypy reported no issues in the2source files. The owned local HTTP/Redis/SQLite fixture was stopped after the run. No real GSM, OTP or portal traffic was used. These APKs were built from the reviewed working tree before the root agent's publication commit; source hashes are recorded alongside build receipts.

### Verification scope

Local release checks and artifact hashes are recorded in the implementation receipt. Robolectric substitutes AndroidKeyStore storage only at the test provider boundary; software RSA/AES, actual protocol clients/coordinator, Room, selected-SIM modem shadows and backend Redis/SQLite are exercised. Physical AndroidKeyStore/OEM behavior, real SMS arrival and installation on remote phones remain unverified until the user updates those devices and genuine signed health is observed. The older link and HTTP statements below are historical snapshots superseded by this release and the already-restored app-scoped HTTPS CA.


Implementation began 2026-10-25; final local verification completed 2026-10-26. This documents source behavior; a successful live activation or SMS delivery still requires server evidence.

Install the Driver APK, then open the driver's dedicated activation link from the BarPro panel on that handset. The app imports the assigned phone, webhook endpoint, individual credential and Hub numbers. The driver grants Android SMS permissions; no settings need to be typed. A failed/expired link requires a fresh link from the panel. An existing working configuration remains in place when link validation or ticket exchange fails, or while permissions are denied.

The activation URI is `barpro-forwarder://enroll#ticket=<encoded-ticket>&server=<encoded-origin>`. Both cold launch and an already-open Activity use the same consumer, which removes the URI from the Activity intent. App enrollment code does not log tickets, URLs containing tickets, response bodies or credentials. The server origin is pinned to `BuildConfig.DEFAULT_ENDPOINT_URL`, falling back to the public endpoint in `ForwardConfig`; a link cannot introduce a different scheme, host or port. Redirects and automatic request retries are disabled. See `EnrollmentIntentRouter.kt`, `EnrollmentContract.kt` and `EnrollmentClient.kt`.

The app posts `{ticket, device_id}` to `/api/v1/otp/enroll`. The backend issues a 15-minute, single-use ticket. The response must specify `protocol: barpro-driver-v1`, a valid Iranian driver phone, a phone-specific `/api/v1/otp/sms-forwarder/<phone>` endpoint at the trusted origin, a lowercase 64-character hexadecimal credential and at least one valid Hub phone number. Missing both Hub routes rejects activation. Imported Hub routes override APK defaults after enrollment; pre-enrollment installations keep the legacy default precedence. The credential is HMAC-SHA256 of `barpro-driver-v1:<normalized phone>` under the server's fleet key, encoded as lowercase hex. The Driver APK contains no fleet key. Hub gateways retain their separately provisioned fleet credential; Driver enrollment is rejected by the Hub flavor.

Validated credentials wait in Keystore-backed encrypted preferences until `RECEIVE_SMS` and `SEND_SMS` are granted. Enrollment refuses plaintext fallback when encrypted storage is unavailable. The active configuration is persisted through `SmsForwardRepository`, keeping its credential fields empty in Room. Staged credentials survive process recreation without redeeming the ticket again. A delayed first-run default insert uses conflict-ignore so it cannot overwrite activation. See `EnrollmentManager.kt`, `SecureStorageManager.kt` and `ForwardConfigDao.kt`.

After activation, Driver OTP and automatic health traffic use GSM SMS to the carrier-matched Hub; mobile internet is required for the initial ticket exchange. Driver automatic HTTP heartbeat, HTTP outbox replay and remote-manifest polling are disabled. Failed Driver OTPs retry through GSM with the original timestamp and recipient, without a network constraint; expired OTPs are rejected before retry. Health uses the signed single-SMS BP1 code `HEALTH_CHECK` and is emitted only when both SMS runtime grants are present. The Hub verifies both legacy fleet signatures and individual driver signatures, then relays the unchanged envelope to the server. Server freshness and replay checks determine liveness. Health messages carry no OTP and are not classified as OTPs by the relay client.

Periodic foreground monitoring uses `healthCheckIntervalMinutes` (default 5, clamped to 1–60 minutes), independent of the health-notification toggle. Android may stop a dataSync foreground service; WorkManager maintenance then retries at its 15-minute interval. Handing a message to the modem does not confirm delivery or server receipt. The driver checks actual receipt in the BarPro panel. Enrollment binds the panel's assigned phone to this installation; it does not prove that the handset's physical SIM owns that phone. Optional SIM-number lookup returns empty when its SDK-specific permission is denied or revoked; API30+ requires READ_PHONE_NUMBERS, which this app does not request.

Current BarPro transport is HTTP on port 80. Pinning the origin prevents link-directed credential exfiltration but does not encrypt the initial activation exchange. The individual Driver credential limits exposure compared with distributing the shared Hub/server credential.

Verification sources are `EnrollmentTest.kt`, `SmsFallbackEnvelopeTest.kt`, `DatabaseMigrationTest.kt` and the on-device `EnrollmentIntentE2ETest.kt`. These use synthetic tickets and keys. No test requires a production credential or a live UTCMS operation.

## Local verification — 2026-10-26

With `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`, the final source run of `./gradlew --max-workers=1 --continue :app:testDriverDebugUnitTest :app:testHubDebugUnitTest :app:assembleDriverDebug :app:assembleHubDebug :app:lintDriverDebug :app:lintHubDebug` completed `BUILD SUCCESSFUL` in 4m40s. JUnit XML recorded 122 tests in each flavor, zero failures/errors/skips. Both lint reports recorded zero errors and 81 warnings; warnings remain. After the device test fixture was corrected, both lint gates ran again and completed `BUILD SUCCESSFUL` in 15s.

The actual Android16 `medium_phone` emulator ran `./gradlew --max-workers=1 :app:connectedDriverDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.EnrollmentIntentE2ETest`: 4 tests, zero failures/errors/skips, `BUILD SUCCESSFUL` in 1m3s. Tests exercised cold and warm URI delivery, preserved prior config on rejection, activated staged credentials without typing, checked empty credential fields in Room, and exercised GSM retry with an interceptor that fails if HTTP is reached. They do not establish physical SIM ownership, actual GSM delivery or a live server receipt.

Earlier device runs had two lifecycle failures. AndroidX Test 1.7's `ActivityScenario` discards lifecycle callbacks when the Activity's action/data no longer match its original intent; the app intentionally consumes enrollment data and replaces the warm intent. Actual logs showed `ignored because the intent does not match`. Those tests now use Instrumentation and observe the real Activity instance through the lifecycle registry, including a `DESTROYED` assertion. An emulator System UI ANR was also observed and dismissed; it was not the remaining intent-matcher failure. The final test run passed after both were addressed. Test setup/restoration preserved the emulator's prior configuration and staged credentials; the agent-started emulator was shut down afterward.

Evidence logs: `/tmp/barpro-android-enrollment-complete-verification.log`, `/tmp/barpro-android-enrollment-device-final.log`, `/tmp/barpro-android-enrollment-final-lint.log`; machine-readable reports remain under `app/build/test-results/`, `app/build/reports/lint-results-*.xml` and `app/build/outputs/androidTest-results/`. The built Driver manifest confirmed `MainActivity`, `launchMode=singleTop`, `ACTION_VIEW`, `BROWSABLE`, scheme `barpro-forwarder` and host `enroll`.

Debug artifacts from that earlier verification were `app/build/outputs/distribution/Forward-BarPro-Driver-driverDebug.apk` (SHA256 `04cd999512fe28b079944bc8661f7d056ccf63e8239b958dd8ef658d381606ac`) and `Forward-BarPro-Hub-hubDebug.apk` (SHA256 `03931c4b45472c3d3727de636822448e41a14580ce0ab84f61abc68fb5bec21f`). Existing release artifacts were not regenerated by these commands.

## Receiving subscription and readiness — 2026-10-26

Driver OTP reception now preserves the Android subscription ID in the existing durable SIM label, including when READ_PHONE_STATE is denied. OTP/retry selects that subscription directly; Driver health uses the last learned subscription for that assigned phone. This does not read a hardware phone number or prove SIM ownership. Before the first received OTP supplies that identity, initial health uses the OS-selected default SMS SIM; on a dual-SIM Driver handset the enrolled phone must be on that default SIM for initial health to reach the Hub with the correct sender. A known subscription that fails to send does not fall back to a different subscription. Existing records without a subscription ID retain the legacy slot resolution.

## Pre-merge completion checks — 2026-10-26

After the OTP/health subscription repairs and both dedup/cache edge fixes, final JVM/build/lint completed BUILD SUCCESSFUL in1m50s: Driver128tests and Hub123tests, zero failures/errors/skips. Final actual Android16 EnrollmentIntentE2ETest ran4tests with zero failures/errors/skips and BUILD SUCCESSFUL in1m11s. DriverSubscriptionRelayTest ran5modem-shadow controls across SDK29/34, with no PHONE_STATE permission, covering initialdefault, original retry/health identity, notification-before-receiver and older retry versus newer health identity. The observed RED wrong-subscription case and both edge failures preceded their fixes; test-fixture API/cache/setup errors are recorded separately in /tmp/barpro-phase16-merge logs.

Driver notification-listener captures wait for the required direct SMS receiver so a capture without subscription cannot suppress the authoritative packet. An older outbox retry keeps its original sending subscription and seeds health only if no learned subscription exists. Hub capture remains compatible. Lint reports retain81warnings per flavor and zero errors. These are local build and synthetic-boundary observations; they do not confirm real carrier delivery, production activation or live UTCMS operations.

- `app/build/outputs/distribution/Forward-BarPro-Driver-driverDebug.apk` SHA256 `04350378fa0ba17b706b723a7b783b0532de77e98e9f0afd3899eaf8a73b744e`

- `app/build/outputs/distribution/Forward-BarPro-Hub-hubDebug.apk` SHA256 `cb4fd9f26e4d893b970fa01fa5189245da3b54090d1c19cfeed552333b21aa67`

## Android-to-backend wire contract — 2026-10-26

A fifth actual emulator test generated phone-scoped BP1 health and OTP packets with the Android implementation and verified both through the real Hub verification helper. The generated packets were then accepted by the local BarPro HTTP handler and stored in isolated Redis: health200/active=true, OTP200/durable=true; a different sender422 and a forged signature401 were refused. The positive-path checker was deliberately made red on the wrong-sender packet before its valid run. The modem/carrier hop was simulated by transferring the generated bytes; no real GSM delivery or production server receipt is claimed. Full on-device EnrollmentIntentE2ETest5passed in1m18s; the additional export rerun passed1test. Both lint gates passed again in14s. The initial artifact export failed because Gradle removed the Driver test package; manual installation/instrumentation exported it correctly, and that setup error is excluded from behavioral evidence.

## Atomic health subscription seeding — 2026-10-26

A final actual repository concurrency regression demonstrated fresh subscription18 being overwritten by old retry17 between its unknown-cache check and write. Fresh remember and retry seedIfUnknown now share one synchronized critical section; the old OTP stays on17 and later health stays on18. RED1behavioral failure preceded GREEN6modem-shadow controls. Final JVM/build/lint passed129Driver/123Hub tests, zero failures/errors/skips, and final actual emulator5tests passed in53s. Lint has0errors and83warnings per flavor in this run. Evidence /tmp/barpro-phase16-merge/cache-concurrency-review.md and android170-cache-concurrency-full.log.

## Historical authenticated rediscovery — 2026-11-01 (superseded by SIM bootstrap above)

First activation uses the operator-issued single-use enrollment link. Driver
rediscovery requires the stored phone-scoped credential; both POST and GET
send it in X-OTP-Webhook-Token. A missing credential fails before a network
request and directs the operator to enrollment. MainViewModel and the default
EnrollmentManager resolver read the credential from existing configuration.

DriverResolutionAdversarialChallengeTest now supplies synthetic enrolled
credentials when testing input, response and network failures. EnrollmentTest
checks anonymous refusal and headers on both fallback methods. Driver-only GSM
fallback assertions are paired with Hub assertions that reject driver relay
traffic instead of recursively relaying to the Hub itself.

Validation before the version-only increment: Driver217 and Hub185 JVM tests,
zero failures/errors/skips; both debug APKs built. These are synthetic tests,
and establish neither a physical SIM receipt nor live UTCMS/OTP success.
Release2.1.1 (versionCode4) is verified again by the release build/test receipts.


## Final release2.1.2 verification — 2026-11-01

After the durable Hub enqueue correction, both flavors completed JVM/lint/release builds: **Driver228 + Hub196 =424tests**, zero failures/errors/skips; release lint**0errors/88warnings** each. Paused WorkManager controls hold the SMS broadcast until its database enqueue finishes, bounded by an eight-second persistence wait. This does not wait for HTTP delivery.

Signed nondebuggable versionCode5 APK SHA256: Driver`e4ae57b23e283f6608422b6c9acf251f7315e210a66144cf50c06b4de5ac6d93`; Hub`4dbbe850cefa08823c9554acb56e8ae56f6b6cb56ceaf8237c9fb140636ca9ca`. Both signer digests match prior locally retained releases: `e8a9dec1977df7b260a99979d3c58867d01a00f5dfc587034e33513d868549f5`. Installed remote handset version/signature, carrier delivery and genuine server health remain unverified. Initial activation requires internet plus one SMS and a configured online Hub; subsequent Driver OTP/health uses GSM.
