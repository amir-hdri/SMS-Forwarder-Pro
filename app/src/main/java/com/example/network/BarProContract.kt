package com.example.network

import com.example.data.model.AuthType
import com.example.data.model.ForwardConfig
import com.example.utils.SmsParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Wire contract implemented by BarPro-main/app/api/routes/otp_forwarder.py. */
object BarProContract {
    const val PATH = "/api/v1/otp/sms-forwarder"
    const val WEBHOOK_ALIAS_PATH = "/api/v1/otp/webhook"
    const val GATEWAY_PATH = "/api/v1/otp/sms-gateway"
    const val TOKEN_HEADER = "X-OTP-Webhook-Token"
    const val DRIVER_PHONE_HEADER = "X-Driver-Phone"
    const val DEVICE_ID_HEADER = "X-Device-Id"
    const val OTP_TTL_MS = 300_000L
    const val IRAN_DST_OFFSET_MS = 3_600_000L
    const val MAX_CLOCK_SKEW_MS = 180_000L

    fun token(config: ForwardConfig): String = when {
        config.authType == AuthType.CUSTOM_HEADER &&
            config.authHeaderKey.equals(TOKEN_HEADER, ignoreCase = true) -> config.authHeaderValue.trim()
        else -> config.forwarderSecret.trim()
    }

    fun isPathValid(encodedPath: String): Boolean {
        if (encodedPath in setOf(PATH, WEBHOOK_ALIAS_PATH, GATEWAY_PATH)) return true
        val segments = encodedPath.trim('/').split('/')
        if (segments.size == 5 &&
            segments[0] == "api" &&
            segments[1] == "v1" &&
            segments[2] == "otp" &&
            (segments[3] == "sms-forwarder" || segments[3] == "webhook" || segments[3] == "sms-gateway")
        ) {
            val phone = SmsParser.normalizePhoneNumber(segments[4])
            return phone.matches(Regex("09[0-9]{9}"))
        }
        return false
    }

    /**
     * Checks if the given timestamp is acceptable for OTP processing.
     * Includes ±1 hour (3600s) compensation for unpatched Android devices affected by the Iranian DST shift.
     */
    fun isTimestampAcceptable(timestamp: Long, now: Long = System.currentTimeMillis()): Boolean {
        val age = now - timestamp
        if (age in -MAX_CLOCK_SKEW_MS until OTP_TTL_MS) return true

        val adjustedForFuture = age + IRAN_DST_OFFSET_MS
        if (adjustedForFuture in -MAX_CLOCK_SKEW_MS until OTP_TTL_MS) return true

        val adjustedForPast = age - IRAN_DST_OFFSET_MS
        if (adjustedForPast in -MAX_CLOCK_SKEW_MS until OTP_TTL_MS) return true

        return false
    }

    fun configurationError(config: ForwardConfig, requireRecipient: Boolean = true): String? {
        val url = config.endpointUrl.trim().toHttpUrlOrNull()
            ?: return "آدرس کامل وب‌هوک بارپرو را وارد کنید."
        // BarPro serves plain HTTP today, so cleartext is reachable — but only after the operator
        // has explicitly accepted it. Defaulting to "off" keeps an http:// endpoint from ever being
        // used by a typo, and keeps the warning in front of whoever configured it.
        if (!url.isHttps && !config.allowCleartextTransport)
            return "آدرس وارد‌شده HTTP است؛ ابتدا در تنظیمات گزینه «تأیید اتصال ناامن HTTP» را فعال کنید."
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null)
            return "آدرس وب‌هوک نباید شامل نام کاربری، رمز یا فرگمنت باشد."
        if (!isPathValid(url.encodedPath))
            return "مسیر وب‌هوک بارپرو باید $PATH ، $WEBHOOK_ALIAS_PATH یا $GATEWAY_PATH باشد."
        if (token(config).isBlank() || token(config) == "change-me-to-a-secure-random-token")
            return "کلید OTP_WEBHOOK_SECRET سرور را وارد کنید."
        if (token(config).any { it.code !in 33..126 })
            return "کلید وب‌هوک باید فقط از نویسه‌های قابل چاپ انگلیسی و بدون فاصله تشکیل شده باشد."
        if (config.isEncryptionEnabled)
            return "بارپرو بدنه JSON استاندارد را می‌پذیرد؛ رمزنگاری سفارشی بدنه پشتیبانی نمی‌شود."
        if (requireRecipient && !SmsParser.normalizePhoneNumber(config.driverPhone).matches(Regex("09[0-9]{9}")))
            return "شماره سیم‌کارت دریافت‌کننده OTP راننده را وارد کنید."
        return null
    }

    /** True when this endpoint sends the webhook token and OTP in the clear. Drives the UI warning. */
    fun isCleartext(config: ForwardConfig): Boolean =
        config.endpointUrl.trim().toHttpUrlOrNull()?.isHttps == false

    fun retryable(httpCode: Int?): Boolean =
        httpCode == null || httpCode == 408 || httpCode == 429 || httpCode in 500..599
}
