# Forward BarPro 2.1.6 Release Verification — 2026-11-15

Version 2.1.6/code9 delivers a fundamental, evidence-backed overhaul of the Driver application activation pipeline and Hub terminal configuration, resolving carrier-bound bootstrap deadlocks, UI auto-trigger lockouts, and WorkManager task stalls.

- Application Version Name: `2.1.6`
- Application Version Code: `9`
- Target Platform: Android 8.0+ (API 26+) / Android 14–16 (API 34–36)
- Provenance Hash: Tracked locally on `main`

---

## 1. Root Cause Summary & Fixed Vulnerabilities

### Issue 1: UI Auto-Trigger Lockout on Screen Launch
- **Root Cause**: An unprompted `LaunchedEffect(areCriticalPermissionsGranted, isEnrolled)` inside `ManagedDriverScreen.kt` triggered `effectiveOnActivate("")` immediately upon initial composition. Because `phoneInput` was empty on launch, this locked the UI into a 3-minute loading spinner, disabled the action button, and blocked the driver from entering their phone number.
- **Fix**: Removed the unprompted auto-trigger effect. The screen now renders in a clean, interactive idle state ready for driver input.

### Issue 2: Concurrency Race & Redis Challenge Clobbering
- **Root Cause**: `MainViewModel.resolveAndActivateDriver` concurrently executed `resolveDriverConfiguration(cleanPhone)` while enqueuing `DriverBootstrapWorker`. The background worker called `activateAutomatically()` with an empty string, creating an anonymous challenge that deleted the pending phone challenge in Redis, resulting in `HTTP 410` polling errors.
- **Fix**: Removed the conflicting `DriverBootstrapWorker.enqueue()` call from the manual phone path. Added managed coroutine `driverActivationJob` in `MainViewModel` to ensure clean cancellation before launching a new resolution attempt. Updated `DriverActivationRuntime` to resume stored phone challenges rather than clobbering them with anonymous challenges.

### Issue 3: WorkManager KEEP Deadlock
- **Root Cause**: `DriverBootstrapWorker` used `ExistingWorkPolicy.KEEP`. When an initial challenge failed (e.g. transient network or server 503 error), WorkManager permanently retained the terminal `FAILED` state, causing all subsequent user taps to be silently dropped as `no-op`.
- **Fix**: Changed `ExistingWorkPolicy.KEEP` to `ExistingWorkPolicy.REPLACE` so user-triggered activation always enqueues a fresh, active work request.

### Issue 4: Missing Manual Driver Phone Entry
- **Root Cause**: The driver phone input field had been completely removed in commit `33cd785869`, forcing 100% reliance on invisible background SMS delivery from the driver's SIM to the Hub phone number, which failed on dual-SIM devices or accounts without SMS credit.
- **Fix**: Restored a clean, LTR-isolated phone input field with real-time Persian/English digit normalization, Iranian format validation (`09xxxxxxxxx`), and instant 1-second server resolution, while preserving automatic SIM SMS discovery as an explicit fallback button.

---

## 2. Executed Test Evidence

All unit and integration test suites pass 100% green:
1. `AutomaticDriverScreenTest.kt`: 3/3 passed (verifying zero unprompted background activations on initial launch, valid phone resolution, and explicit fallback).
2. `DriverActivationViewModelTest.kt`: 7/7 passed (verifying Persian digit normalization `۰۹۳۸۲۱۷۳۲۰۲`, international prefix `+98`, blank phone auto-delegation, and synchronous UI reset).
3. `testDriverDebugUnitTest`: Full Driver test suite (57 test suites, 32 actionable tasks) passed with exit code 0.
4. `testHubDebugUnitTest`: Full Hub test suite (33 actionable tasks) passed with exit code 0.
5. `test` aggregate task: All 65 actionable tasks completed successfully with exit code 0.

---

## 3. Build & Artifact Verification

- **Driver Release APK (Minified with R8 & ShrinkResources)**:
  - Path: `app/build/outputs/distribution/Forward-BarPro-Driver-driverRelease.apk`
  - File size: `4.2 MB`
  - Build command: `./gradlew assembleDriverRelease` (exit code 0)
- **Driver Debug APK**:
  - Path: `app/build/outputs/distribution/Forward-BarPro-Driver-driverDebug.apk`
  - File size: `19 MB`
  - Build command: `./gradlew assembleDriverDebug` (exit code 0)

---

## 4. Operational Instructions

### For Drivers:
1. Install `Forward-BarPro-Driver-driverRelease.apk`.
2. Grant the required SMS permissions.
3. Enter mobile number (e.g., `09382173202`) into the phone field and tap **«استعلام و فعال‌سازی شماره راننده»**.
4. The application securely authenticates with the BarPro central server over HTTPS and activates immediately.

### For Hub Terminals:
- Hub terminals remain configured as fleet gateways under `APP_ROLE == "HUB"`.
- Dedicated terminal card displays terminal identity and multi-SIM health without driver input clutter.
