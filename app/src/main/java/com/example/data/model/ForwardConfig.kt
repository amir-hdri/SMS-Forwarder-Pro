package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class AuthType {
    NONE,
    BEARER_TOKEN,
    API_KEY_HEADER,
    CUSTOM_HEADER
}

enum class ForwardFilterMode {
    SPECIFIC_RULES_ONLY,  // Only forward if matched by one of the active filter rules
    ALL_MESSAGES          // Forward all incoming SMS messages
}

@Entity(tableName = "app_config")
data class ForwardConfig(
    @PrimaryKey
    val id: Int = 1, // Single row configuration
    val isMasterEnabled: Boolean = false,
    val endpointUrl: String = "",
    val authType: AuthType = AuthType.CUSTOM_HEADER,
    val authHeaderKey: String = "X-OTP-Webhook-Token",
    val authHeaderValue: String = "",
    val forwarderSecret: String = "",
    val isEncryptionEnabled: Boolean = false, // وب‌هوک اتوماسیون بارپرو با HTTPS و JSON استاندارد کار می‌کند
    val secretEncryptionKey: String = "",
    val filterMode: ForwardFilterMode = ForwardFilterMode.ALL_MESSAGES,
    val deviceIdentifier: String = "BarPro Terminal 01",
    val includeMetadata: Boolean = true,
    val showForegroundNotification: Boolean = true,
    val maxRetries: Int = 2,
    val timeoutSeconds: Int = 15,
    val enableHealthAlertNotification: Boolean = true,
    val healthCheckIntervalMinutes: Int = 5,
    val healthFailureThreshold: Int = 2,
    val notifyOnDisconnect: Boolean = true,
    val enableAutoOfflineSync: Boolean = true,
    val enableCommandPolling: Boolean = false,
    // BarPro Multi-driver and UTCMS automation settings:
    val driverId: String = "",
    val driverFullName: String = "",
    val driverPhone: String = "", // شماره سیم‌کارت دریافت‌کننده OTP
    val autoExtractOtp: Boolean = true,
    val autoExtractTrackingCode: Boolean = true,
    val filterUtcmsOnly: Boolean = true,
    val userConsentGiven: Boolean = false,
    val enableWorkManagerSync: Boolean = true, // صف‌بندی پس‌زمینه با WorkManager
    // Automatic SMS Fallback when internet is offline/weak:
    val enableSmsFallback: Boolean = false,
    val fallbackServerPhoneNumber: String = "",
    /**
     * Explicit operator acknowledgement that the endpoint is plain HTTP. Defaults to false so a
     * cleartext endpoint can never be used by accident: the setup screen must show the warning and
     * the operator must accept it. BarPro currently serves port 80 only, so this is normally on —
     * and should be turned back off the moment TLS is available.
     */
    val allowCleartextTransport: Boolean = false,
    /** Signed remote-config document URL. Empty disables the no-APK settings channel. */
    val configManifestUrl: String = "",
    /** Signed release-manifest URL. Empty disables self-update. */
    val updateManifestUrl: String = "",
    val autoUpdateEnabled: Boolean = true,
    /** Set once the first-run wizard has been completed, so it is not shown again. */
    val setupCompleted: Boolean = false
)
