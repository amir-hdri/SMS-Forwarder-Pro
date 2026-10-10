package com.example.network

import com.example.data.model.AuthType
import com.example.data.model.FilterRule
import com.example.data.model.ForwardConfig
import com.example.data.model.SmsType
import com.example.utils.LogSanitizer
import com.example.utils.SmsParser
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import kotlin.coroutines.resumeWithException

data class TransmissionResult(
    val isSuccess: Boolean,
    val httpStatusCode: Int?,
    val responseBody: String?,
    val errorMessage: String?,
    val payloadSent: String,
    val isEncrypted: Boolean,
    val durationMs: Long,
    val smsType: SmsType = SmsType.OTHER,
    val trackingCode: String? = null,
    val otpCode: String? = null,
    val signature: String? = null,
    val isRetryable: Boolean = false
)

data class ServerCommand(
    val id: String,
    val type: String,
    val sender: String? = null,
    val targetTimestamp: Long? = null,
    val rawJson: String = ""
)

data class HeartbeatResult(
    val isSuccess: Boolean,
    val httpCode: Int?,
    val pendingCommand: ServerCommand?,
    val durationMs: Long,
    val errorMessage: String?
)

class SmsForwarderClient(val httpClient: OkHttpClient = OkHttpClient()) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun failure(message: String) = TransmissionResult(false, null, null, message, "", false, 0)

    suspend fun forwardMessage(
        sender: String,
        messageBody: String,
        timestamp: Long,
        config: ForwardConfig,
        matchedRule: FilterRule? = null,
        simSlot: String = "SIM 1",
        fastTimeoutMs: Long? = null,
        clientOverride: OkHttpClient? = null
    ): TransmissionResult = withContext(Dispatchers.IO) {
        BarProContract.configurationError(config)?.let { return@withContext failure(it) }
        if (!BarProContract.isTimestampAcceptable(timestamp))
            return@withContext failure("کد منقضی است یا ساعت دستگاه درست نیست.")
        val otp = SmsParser.extractOtp(messageBody, sender)
            ?: return@withContext failure("پیامک شامل کد OTP معتبر نیست.")
        val phone = SmsParser.normalizePhoneNumber(config.driverPhone)
        val payload = JSONObject().apply {
            put("event", "SMS_RECEIVED")
            put("driver_phone", phone)
            put("sender", sender)
            put("text", messageBody)
            put("timestamp", timestamp)
            put("device_id", config.deviceIdentifier)
            put("sim_slot", simSlot)
        }
        val extraHeaders = mutableMapOf<String, String>()
        if (phone.isNotBlank()) extraHeaders[BarProContract.DRIVER_PHONE_HEADER] = phone
        if (config.deviceIdentifier.isNotBlank()) extraHeaders[BarProContract.DEVICE_ID_HEADER] = config.deviceIdentifier

        post(
            payload, config, expectedStatus = "success",
            fastTimeoutMs = fastTimeoutMs, extraHeaders = extraHeaders, clientOverride = clientOverride
        ).copy(smsType = SmsType.UTCMS_OTP, otpCode = otp)
    }

    /** Authenticated storage probe; never inserts a synthetic driver OTP. */
    suspend fun checkHealth(
        config: ForwardConfig,
        permissions: Map<String, Boolean>? = null,
        clientOverride: OkHttpClient? = null
    ): TransmissionResult = withContext(Dispatchers.IO) {
        BarProContract.configurationError(config, requireRecipient = false)?.let {
            return@withContext failure(it)
        }
        val extraHeaders = mutableMapOf<String, String>()
        val phone = SmsParser.normalizePhoneNumber(config.driverPhone)
        if (phone.matches(Regex("09[0-9]{9}"))) extraHeaders[BarProContract.DRIVER_PHONE_HEADER] = phone
        if (config.deviceIdentifier.isNotBlank()) extraHeaders[BarProContract.DEVICE_ID_HEADER] = config.deviceIdentifier
        val payload = JSONObject().put("event", "HEALTH_CHECK")
        if (!permissions.isNullOrEmpty()) {
            val permJson = JSONObject()
            permissions.forEach { (k, v) -> permJson.put(k, v) }
            payload.put("permissions", permJson)
        }
        post(
            payload, config.copy(maxRetries = 0), "ready",
            extraHeaders = extraHeaders, clientOverride = clientOverride
        )
    }

    /** A read-only query: only the matching signed GSM receipt confirms this test. */
    suspend fun checkSmsProbeReceipt(config: ForwardConfig, timestamp: Long): TransmissionResult =
        withContext(Dispatchers.IO) {
            BarProContract.configurationError(config)?.let { return@withContext failure(it) }
            val phone = SmsParser.normalizePhoneNumber(config.driverPhone)
            post(
                JSONObject().put("event", "SMS_PROBE_STATUS").put("driver_phone", phone)
                    .put("probe_timestamp", timestamp),
                config.copy(maxRetries = 0), "probe_received", fastTimeoutMs = 3000,
                responseMatches = { json ->
                    json.optString("phone") == phone && json.optLong("probe_timestamp", -1) == timestamp &&
                        json.optDouble("received_at", 0.0) > 0.0
                }
            )
        }

    fun resolveGatewayUrl(endpointUrl: String): String {
        val trimmed = endpointUrl.trim().trimEnd('/')
        if (trimmed.isBlank()) return "https://api.barpro.ir" + BarProContract.GATEWAY_PATH
        val base = if (trimmed.substringAfterLast('/').matches(Regex("09[0-9]{9}"))) {
            trimmed.substringBeforeLast('/')
        } else {
            trimmed
        }
        return when {
            base.endsWith("/sms-gateway") -> base
            base.endsWith("/sms-forwarder") -> base.substringBeforeLast("/sms-forwarder") + "/sms-gateway"
            base.endsWith("/webhook") -> base.substringBeforeLast("/webhook") + "/sms-gateway"
            base.endsWith("/api/v1/otp") -> "$base/sms-gateway"
            base.contains("/api/v1/otp") -> "$base/sms-gateway"
            else -> "$base/api/v1/otp/sms-gateway"
        }
    }

    /** Relays a signed BP1#... envelope received on the Hub to /api/v1/otp/sms-gateway */
    suspend fun relayGatewaySms(
        sender: String,
        envelopeText: String,
        config: ForwardConfig,
        fastTimeoutMs: Long? = null,
        clientOverride: OkHttpClient? = null
    ): TransmissionResult = withContext(Dispatchers.IO) {
        val token = BarProContract.token(config)
        if (token.isBlank()) return@withContext failure("توکن وب‌هوک تنظیم نشده است.")
        val parsed = com.example.utils.SmsFallbackEnvelope.parse(envelopeText)
            ?: return@withContext failure("قالب پکت پیامک امضاشده معتبر نیست.")

        val targetUrl = resolveGatewayUrl(config.endpointUrl)
        val normSender = com.example.utils.SmsParser.normalizePhoneNumber(sender)
        val fromPhone = if (normSender.matches(Regex("09[0-9]{9}"))) normSender else parsed.phone
        val payload = JSONObject().apply {
            put("from", fromPhone)
            put("text", envelopeText.trim())
        }
        val extraHeaders = mutableMapOf<String, String>()
        if (config.deviceIdentifier.isNotBlank()) {
            extraHeaders[BarProContract.DEVICE_ID_HEADER] = config.deviceIdentifier
        }

        val gatewayConfig = config.copy(endpointUrl = targetUrl)
        post(
            payload,
            gatewayConfig,
            expectedStatus = "success",
            fastTimeoutMs = fastTimeoutMs,
            extraHeaders = extraHeaders,
            clientOverride = clientOverride,
            responseMatches = { json -> json.optBoolean("success", false) }
        ).copy(smsType = SmsType.UTCMS_OTP, otpCode = parsed.code)
    }

    private suspend fun post(
        payload: JSONObject,
        config: ForwardConfig,
        expectedStatus: String,
        fastTimeoutMs: Long? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        clientOverride: OkHttpClient? = null,
        responseMatches: (JSONObject) -> Boolean = { true }
    ): TransmissionResult {
        val started = System.nanoTime()
        val timeout = fastTimeoutMs?.coerceIn(250, 60_000)
            ?: (config.timeoutSeconds.coerceIn(1, 60) * 1000L)
        val baseClient = clientOverride ?: httpClient
        val client = baseClient.newBuilder()
            .callTimeout(timeout, TimeUnit.MILLISECONDS)
            .connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .readTimeout(timeout, TimeUnit.MILLISECONDS)
            .writeTimeout(timeout, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
        val requestBuilder = Request.Builder().url(config.endpointUrl.trim())
            .header(BarProContract.TOKEN_HEADER, BarProContract.token(config))
            .header("User-Agent", "BarPro-Forwarder-Android/1.0")
        extraHeaders.forEach { (key, value) ->
            if (value.isNotBlank()) requestBuilder.header(key, value)
        }
        val request = requestBuilder.post(payload.toString().toRequestBody(jsonMediaType)).build()
        // A fast attempt has ONE deadline, including DNS and reading the response.
        val attempts = if (fastTimeoutMs != null) 1 else config.maxRetries.coerceIn(0, 3) + 1
        var result = failure("ارتباط با سرور برقرار نشد.")
        repeat(attempts) { attempt ->
            try {
                client.newCall(request).await().use { response ->
                    val responseText = response.peekBody(16_384).string()
                    val json = try { JSONObject(responseText) } catch (_: Exception) { null }
                    val accepted = response.isSuccessful && json != null &&
                        json.optBoolean("success", false) && json.optString("status") == expectedStatus &&
                        (expectedStatus != "success" || json.optBoolean("otp_detected", false)) && responseMatches(json)
                    result = TransmissionResult(
                        isSuccess = accepted,
                        httpStatusCode = response.code,
                        responseBody = LogSanitizer.sanitize(responseText.take(1000)),
                        errorMessage = if (accepted) null else when (response.code) {
                            401 -> "کلید OTP_WEBHOOK_SECRET با سرور مطابقت ندارد."
                            404 -> "مسیر وب‌هوک بارپرو پیدا نشد."
                            409 -> "کد جدیدتری قبلاً به سرور رسیده است."
                            410 -> "اعتبار OTP به پایان رسیده است."
                            422 -> {
                                if (responseText.contains("AMBIGUOUS_OTP", ignoreCase = true)) {
                                    "چندین بارنامه همزمان منتظر OTP هستند؛ تعیین شماره موبایل راننده در تنظیمات الزامی است."
                                } else {
                                    "شماره گیرنده، زمان پیامک یا قالب درخواست معتبر نیست."
                                }
                            }
                            429 -> "محدودیت نرخ سرور؛ ارسال دوباره زمان‌بندی می‌شود."
                            in 500..599 -> "سرور یا ذخیره‌سازی OTP موقتاً در دسترس نیست."
                            else -> "سرور دریافت OTP را تأیید نکرد (HTTP " + response.code + ")."
                        },
                        payloadSent = LogSanitizer.sanitize(payload.toString()),
                        isEncrypted = false,
                        durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                        isRetryable = !accepted && BarProContract.retryable(response.code)
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                result = failure("ارتباط با سرور قطع شد یا مهلت آن تمام شد.").copy(
                    isRetryable = true,
                    durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                )
            }
            if (result.isSuccess || !result.isRetryable || attempt == attempts - 1) return result
            delay(800L * (attempt + 1))
        }
        return result
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }

    suspend fun sendHeartbeat(config: ForwardConfig, deviceStatus: DeviceStatus, pendingFailedCount: Int): HeartbeatResult {
        val result = checkHealth(config)
        return HeartbeatResult(result.isSuccess, result.httpStatusCode, null, result.durationMs, result.errorMessage)
    }

    suspend fun testEndpoint(
        endpointUrl: String, authType: AuthType, authHeaderKey: String, authHeaderValue: String,
        isEncryptionEnabled: Boolean, secretKey: String, deviceIdentifier: String
    ): TransmissionResult = checkHealth(ForwardConfig(
        endpointUrl = endpointUrl, authType = authType, authHeaderKey = authHeaderKey,
        authHeaderValue = authHeaderValue, forwarderSecret = authHeaderValue,
        isEncryptionEnabled = isEncryptionEnabled, deviceIdentifier = deviceIdentifier
    ))

    suspend fun sendOtpInquiryResponse(
        sender: String, otpCode: String, requestedTimestamp: Long, smsTimestamp: Long,
        rawMessage: String, matchedRuleLabel: String, config: ForwardConfig,
        clientOverride: OkHttpClient? = null
    ): TransmissionResult = forwardMessage(sender, rawMessage, smsTimestamp, config, clientOverride = clientOverride)

    suspend fun replyToCommand(
        commandId: String, commandType: String, status: String, resultData: JSONObject, config: ForwardConfig
    ): TransmissionResult = failure("نسخه فعلی بارپرو فرمان راه دور برای فوروارد پیامک ارائه نمی‌کند.")
}
