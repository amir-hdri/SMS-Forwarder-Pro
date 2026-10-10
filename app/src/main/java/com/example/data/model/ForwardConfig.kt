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
    val endpointUrl: String = "https://api.barpro.ir/api/v1/otp/sms-forwarder",
    val authType: AuthType = AuthType.CUSTOM_HEADER,
    val authHeaderKey: String = "X-OTP-Webhook-Token",
    val authHeaderValue: String = "barpro-fleet-secure-token",
    val forwarderSecret: String = "",
    val isEncryptionEnabled: Boolean = false, // وب‌هوک اتوماسیون بارپرو بدنه JSON استاندارد می‌پذیرد
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
    // Automatic SMS Fallback / Primary SMS Relay to server:
    val enableSmsFallback: Boolean = false,
    val primarySmsRelayEnabled: Boolean = true,
    /**
     * Hub SIM that receives the relayed `BP1#...` envelope. Historically the single "server gateway"
     * number; it is now the MCI (همراه اول) leg of the two-SIM Hub so carrier-matched delivery has a
     * concrete primary. Still the only mandatory number — an Irancell leg is optional.
     */
    val fallbackServerPhoneNumber: String = "",
    /**
     * Second Hub SIM (ایرانسل). When set, [com.example.utils.CarrierDetector.resolveRoute] can send
     * on-net (MCI→MCI, Irancell→Irancell) and fail over to the other SIM. Left blank the relay still
     * works, but degrades to single-number delivery with no failover.
     */
    val hubIrancellPhoneNumber: String = "",
    /**
     * Explicit operator acknowledgement that the endpoint is plain HTTP.
     *
     * HTTP on port 80 is the chosen transport for the BarPro deployment, so this is normally on in
     * production. It still defaults to false so a cleartext endpoint can never be reached by a
     * typo: the setup screen must show the warning and the operator must accept it. The `BP1#...`
     * envelope stays HMAC-authenticated either way, but the webhook token and the OTP are not
     * encrypted in transit — restricting network access to the gateway is part of the design.
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
