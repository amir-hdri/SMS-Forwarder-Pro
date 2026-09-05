# Project Directory Structure: BarPro SMS Forwarder

This document details the complete filesystem and codebase structure for the **BarPro SMS Forwarder** Android enterprise application.

```
barpro-sms-forwarder/
│
├── .build-outputs/                     # Compiled APK and test reporting outputs
├── .env.example                        # Environment variables template for API secrets
├── .gitignore                          # Git ignore rules for Android/Gradle/Kotlin projects
│
├── README.md                           # Comprehensive project overview, badges, features & setup guide
├── ARCHITECTURE.md                     # Clean Architecture, dual-path reception & data flow diagrams
├── API_DOCUMENTATION.md                # REST API contracts, JSON schemas, headers, & HMAC auth
├── USER_GUIDE.md                       # Driver & fleet operator manual for setup, permissions & usage
├── PRIVACY_POLICY.md                   # Compliance, data minimization, and privacy statements
├── PROJECT_STRUCTURE.md                # Detailed repository directory tree and file explanations (This file)
│
├── metadata.json                       # AI Studio project platform identity & configuration
├── settings.gradle.kts                 # Gradle project and plugin repository settings
├── build.gradle.kts                    # Root project build configuration
├── gradle.properties                   # JVM memory options and AndroidX build flags
│
├── gradle/
│   ├── libs.versions.toml              # Centralized version catalog for dependencies and plugins
│   └── wrapper/                        # Gradle wrapper binaries and properties
│
└── app/
    ├── build.gradle.kts                # App-level dependencies (Compose, Room, WorkManager, OkHttp)
    ├── proguard-rules.pro              # ProGuard / R8 obfuscation and optimization rules
    │
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml     # Permissions (SMS, Notif, Network, Boot), Receivers, Services
        │   │
        │   ├── java/com/example/
        │   │   ├── MainActivity.kt                 # Single-activity host with Compose navigation & dialogs
        │   │   ├── SmsForwarderApp.kt              # Android Application class (Notification channels init)
        │   │   │
        │   │   ├── crypto/
        │   │   │   └── CryptoEngine.kt             # AES-256-GCM symmetric encryption/decryption utilities
        │   │   │
        │   │   ├── data/
        │   │   │   ├── local/
        │   │   │   │   ├── AppDatabase.kt          # Room Database definition and schema migrations
        │   │   │   │   ├── FilterRuleDao.kt        # DAO for whitelist/blacklist rules
        │   │   │   │   ├── ForwardConfigDao.kt     # DAO for server URL, API keys, and active toggles
        │   │   │   │   └── ForwardLogDao.kt        # DAO for forwarded SMS records & offline queue queries
        │   │   │   │
        │   │   │   ├── model/
        │   │   │   │   ├── FilterRule.kt           # Entity: Sender prefixes, keywords, action whitelist/blacklist
        │   │   │   │   ├── ForwardConfig.kt        # Entity: Server endpoint, token, secret, driver ID, toggles
        │   │   │   │   └── ForwardLog.kt           # Entity: Stored SMS, timestamp, status (SENT/FAILED/PENDING)
        │   │   │   │
        │   │   │   └── repository/
        │   │   │       └── SmsForwardRepository.kt # Repository managing Room DB queries, state flows & logs
        │   │   │
        │   │   ├── network/
        │   │   │   ├── DeviceStatusHelper.kt       # Device info collector (Model, OS, Battery, Network type)
        │   │   │   ├── ServerHealthMonitor.kt      # Periodic server ping and latency measurement
        │   │   │   └── SmsForwarderClient.kt       # OkHttp REST client with HMAC signing & retries
        │   │   │
        │   │   ├── otp/
        │   │   │   └── OtpExtractor.kt             # Smart extraction algorithms for 4-8 digit OTP codes
        │   │   │
        │   │   ├── receiver/
        │   │   │   ├── BootReceiver.kt             # Auto-start foreground service on device boot completion
        │   │   │   └── SmsReceiver.kt              # BroadcastReceiver listening for Telephony.SMS_RECEIVED
        │   │   │
        │   │   ├── service/
        │   │   │   ├── ForegroundForwarderService.kt # Persistent Foreground Service with ongoing notification
        │   │   │   ├── ServerHealthNotifier.kt     # System notifications for connectivity drops & queue warnings
        │   │   │   ├── SmsNotificationListener.kt  # Backup NotificationListenerService for Android 11+
        │   │   │   └── SmsSyncWorker.kt            # WorkManager worker for offline-first exponential retry queue
        │   │   │
        │   │   ├── ui/
        │   │   │   ├── components/
        │   │   │   │   └── StatusBadge.kt          # Reusable status chip components (SENT, PENDING, FAILED)
        │   │   │   │
        │   │   │   ├── screens/
        │   │   │   │   ├── DashboardScreen.kt      # Main dashboard: Master toggle, health tiles, quick logs
        │   │   │   │   ├── LogsScreen.kt           # Detailed SMS history with search, filter, OTP copy & retry
        │   │   │   │   ├── RulesScreen.kt          # Rules management for sender prefixes and keyword filtering
        │   │   │   │   ├── ServerConfigScreen.kt   # Server endpoint, Driver ID, HMAC secret, encryption keys
        │   │   │   │   ├── PermissionManagerScreen.kt # Dedicated screen with category filters & M3 status icons
        │   │   │   │   ├── PermissionDialog.kt     # Full interactive permissions modal & checklist
        │   │   │   │   ├── OtpInquiryDialog.kt     # Fast lookup dialog for UTCMS waybill & OTP codes
        │   │   │   │   ├── TestSmsDialog.kt        # Simulator dialog for testing SMS forwarding without real SMS
        │   │   │   │   ├── ServerGuideSheet.kt     # Bottom sheet guide explaining server endpoint and HMAC auth
        │   │   │   │   └── BackgroundExecutionGuideSheet.kt # Battery optimization & autostart manufacturer guide
        │   │   │   │
        │   │   │   ├── theme/
        │   │   │   │   ├── Color.kt                # Material 3 dark/golden oceanic color palette
        │   │   │   │   ├── Theme.kt                # App-level Jetpack Compose MaterialTheme configuration
        │   │   │   │   └── Type.kt                 # Typography definitions with clean RTL/Persian support
        │   │   │   │
        │   │   │   └── viewmodel/
        │   │   │       └── MainViewModel.kt        # Central ViewModel orchestrating state flows and coroutines
        │   │   │
        │   │   └── utils/
        │   │       ├── PermissionHelper.kt         # Permission verification, battery intent & system settings
        │   │       ├── SecurityUtils.kt            # Root, su binary, emulator, and debug mode detection
        │   │       ├── SignatureUtils.kt           # HMAC-SHA256 digital signature generator for payloads
        │   │       └── SmsParser.kt                # Regex parser for UTCMS waybills, OTPs and Persian digits
        │   │
        │   └── res/
        │       ├── drawable/                       # Vector assets and custom launcher icon XMLs
        │       ├── mipmap-*/                       # Adaptive launcher icons for all screen densities
        │       └── values/
        │           ├── strings.xml                 # Persian localized strings and application name
        │           └── themes.xml                  # Android system window & splash themes
        │
        └── test/java/com/example/
            ├── ExampleUnitTest.kt                  # Standard JUnit local tests
            ├── ExampleRobolectricTest.kt           # Robolectric tests for PermissionHelper, rules, and parser
            ├── GreetingScreenshotTest.kt           # Roborazzi screenshot tests for Compose UI
            └── SmsParserTest.kt                    # Unit tests validating Persian number normalization and regex
```

---

## Key Modules Breakdown

### 1. Reception & Services (`com.example.receiver` & `com.example.service`)
- **Dual-Path Reception**: `SmsReceiver` catches incoming SMS broadcasts directly from the telephony stack. In modern Android versions where background broadcast execution may be delayed by aggressive OS battery policies, `SmsNotificationListener` acts as a parallel observer.
- **Service Continuity**: `ForegroundForwarderService` keeps an ongoing persistent notification to guarantee continuous operation in the background without getting killed by the Android low-memory killer.

### 2. Security & Parsing Engine (`com.example.crypto` & `com.example.utils`)
- **Normalized Regex**: Converts Persian/Arabic digits (۰-۹) into standard digits before parsing waybill numbers (`کد رهگیری`) and verification codes (`OTP`).
- **HMAC-SHA256 Signatures**: Every forwarded JSON payload carries an `X-Signature` header calculated using the driver's secret key to prevent man-in-the-middle tampering.
- **AES-256-GCM Encryption**: Optional symmetric encryption for sensitive payload data during transmission over untrusted networks.

### 3. Local Persistence & Offline Sync (`com.example.data` & `SmsSyncWorker`)
- **Room Database**: Complete relational schema storing server configurations, custom filter rules, and comprehensive audit logs of all processed messages.
- **WorkManager**: Background worker with exponential backoff strategy (`BackoffPolicy.EXPONENTIAL`) that automatically syncs pending or failed SMS packets whenever network connectivity is restored.

### 4. Jetpack Compose UI (`com.example.ui`)
- Built exclusively with **Material Design 3 (M3)** using a modern Oceanic & Golden palette.
- **PermissionManagerScreen**: Dedicated permission auditor with filter chips (`SMS`, `Notification`, `Background`) and visual Material 3 status badges (`CheckCircle` vs `Warning`).
- **RTL & Persian First**: Thoughtfully designed with Right-to-Left layout support and clear Persian typography.
