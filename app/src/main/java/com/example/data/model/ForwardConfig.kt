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
    val isMasterEnabled: Boolean = true,
    val endpointUrl: String = "https://api.barpro.ir/api/v1/rpa/sms-forwarder",
    val authType: AuthType = AuthType.CUSTOM_HEADER,
    val authHeaderKey: String = "X-Forwarder-Secret",
    val authHeaderValue: String = "change-me-to-a-secure-random-token",
    val forwarderSecret: String = "change-me-to-a-secure-random-token", // کلید امنیتی اختصاصی X-Forwarder-Secret
    val isEncryptionEnabled: Boolean = false, // وب‌هوک اتوماسیون بارپرو با HTTPS و JSON استاندارد کار می‌کند
    val secretEncryptionKey: String = "sms-forwarder-secure-key-2026",
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
    val enableCommandPolling: Boolean = true,
    // BarPro Multi-driver and UTCMS automation settings:
    val driverId: String = "DRV-102938", // کد ملی یا شناسه اختصاصی راننده
    val driverFullName: String = "راننده ناوگان بارپرو",
    val driverPhone: String = "09333702137", // شماره سیم‌کارت راننده جهت احراز هویت در وب‌هوک و ردیس
    val autoExtractOtp: Boolean = true,
    val autoExtractTrackingCode: Boolean = true,
    val filterUtcmsOnly: Boolean = false, // فیلتر هوشمند پیامک‌ها (فقط بارنامه و OTP)
    val userConsentGiven: Boolean = true, // تاییدیه و رضایت‌نامه رسمی حریم خصوصی
    val enableWorkManagerSync: Boolean = true // صف‌بندی پس‌زمینه با WorkManager
)
