package com.example.network

import com.example.crypto.CryptoEngine
import com.example.data.model.AuthType
import com.example.data.model.ForwardConfig
import com.example.data.model.ForwardLog
import com.example.otp.OtpExtractor
import com.example.utils.SmsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class TransmissionResult(
    val isSuccess: Boolean,
    val httpStatusCode: Int?,
    val responseBody: String?,
    val errorMessage: String?,
    val payloadSent: String,
    val isEncrypted: Boolean,
    val durationMs: Long,
    val smsType: com.example.data.model.SmsType = com.example.data.model.SmsType.OTHER,
    val trackingCode: String? = null,
    val otpCode: String? = null,
    val signature: String? = null
)

data class ServerCommand(
    val id: String,
    val type: String, // e.g. "GET_LATEST_OTP", "PING", "RESEND_SMS", "SYNC_LOGS"
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

class SmsForwarderClient {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun buildOkHttpClient(timeoutSeconds: Int): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun applyAuthHeaders(builder: Request.Builder, config: ForwardConfig) {
        when (config.authType) {
            AuthType.BEARER_TOKEN -> {
                if (config.authHeaderValue.isNotBlank()) {
                    val token = config.authHeaderValue.trim()
                    val headerVal = if (token.startsWith("Bearer ", ignoreCase = true)) token else "Bearer $token"
                    builder.addHeader(config.authHeaderKey.ifBlank { "Authorization" }, headerVal)
                }
            }
            AuthType.API_KEY_HEADER -> {
                if (config.authHeaderValue.isNotBlank()) {
                    builder.addHeader(
                        config.authHeaderKey.ifBlank { "X-API-KEY" },
                        config.authHeaderValue.trim()
                    )
                }
            }
            AuthType.CUSTOM_HEADER -> {
                if (config.authHeaderKey.isNotBlank() && config.authHeaderValue.isNotBlank()) {
                    builder.addHeader(config.authHeaderKey.trim(), config.authHeaderValue.trim())
                }
            }
            AuthType.NONE -> {}
        }
    }

    suspend fun forwardMessage(
        sender: String,
        messageBody: String,
        timestamp: Long,
        config: ForwardConfig,
        matchedRule: com.example.data.model.FilterRule? = null,
        simSlot: String = "SIM 1"
    ): TransmissionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var payloadJsonString = ""
        var isEncrypted = false

        // Extract OTP and Tracking code and detect SMS type
        val smsType = com.example.utils.SmsParser.detectSmsType(messageBody)
        val trackingCode = com.example.utils.SmsParser.extractTrackingCode(messageBody)
        val extractedOtp = com.example.utils.SmsParser.extractOtp(messageBody)
        val signature = com.example.utils.SignatureUtils.generateSignature(
            driverId = config.driverId,
            phoneNumber = sender,
            messageBody = messageBody,
            timestamp = timestamp,
            secretKey = config.secretEncryptionKey
        )

        val ruleLabel = matchedRule?.label ?: (if (config.filterMode == com.example.data.model.ForwardFilterMode.ALL_MESSAGES) "All Messages" else "Unmatched")
        val rulePattern = matchedRule?.senderPattern ?: ""

        val normalizedDriverPhone = SmsParser.normalizePhoneNumber(
            config.driverPhone.ifBlank { "09333702137" }
        )

        // Build the inner raw SMS data with rich sender and routing metadata
        val rawData = JSONObject().apply {
            put("event", "SMS_RECEIVED")
            put("phone", normalizedDriverPhone)
            put("text", messageBody)
            put("driver_id", config.driverId)
            put("driver_name", config.driverFullName)
            put("driver_phone", normalizedDriverPhone)
            put("sender", sender)
            put("phone_number", sender)
            put("message", messageBody)
            put("message_body", messageBody)
            put("timestamp", timestamp)
            put("sim_slot", simSlot)
            put("device_id", config.deviceIdentifier)
            put("sms_type", smsType.name)
            if (trackingCode != null) {
                put("tracking_code", trackingCode)
            }
            if (extractedOtp != null) {
                put("otp_code", extractedOtp)
            }
            put("signature", signature)
            put("device_info", JSONObject().apply {
                put("imei", config.deviceIdentifier)
                put("model", android.os.Build.MODEL)
                put("os_version", "Android ${android.os.Build.VERSION.RELEASE}")
                put("app_version", "1.0")
            })
            put("matched_rule", JSONObject().apply {
                put("id", matchedRule?.id ?: 0)
                put("label", ruleLabel)
                put("pattern", rulePattern)
                put("match_type", matchedRule?.matchType?.name ?: "EXACT")
            })
            if (config.includeMetadata) {
                put("app_version", "1.0")
                put("os", "Android")
            }
        }

        // Encrypt if enabled
        val transmissionJson = JSONObject().apply {
            // Root-level fields strictly required by BarPro FastAPI SmsForwarderPayload
            put("phone", normalizedDriverPhone)
            put("text", messageBody)
            put("sender", sender)
            put("timestamp", timestamp)

            put("version", "1.0")
            put("type", "SMS_FORWARD")
            put("device_id", config.deviceIdentifier)
            put("driver_id", config.driverId)
            put("driver_name", config.driverFullName)
            put("driver_phone", normalizedDriverPhone)
            put("phone_number", sender)
            put("message_body", messageBody)
            put("sms_type", smsType.name)
            if (trackingCode != null) {
                put("tracking_code", trackingCode)
            }
            if (extractedOtp != null) {
                put("otp_code", extractedOtp)
            }
            put("signature", signature)
            put("matched_rule", ruleLabel)
            put("sim_slot", simSlot)

            if (config.isEncryptionEnabled && config.secretEncryptionKey.isNotBlank()) {
                isEncrypted = true
                val encResult = CryptoEngine.encrypt(rawData.toString(), config.secretEncryptionKey)
                put("encrypted", true)
                put("algorithm", "AES-256-GCM")
                put("iv", encResult.ivBase64)
                put("ciphertext", encResult.ciphertextBase64)
                put("signature", encResult.signatureHmac)
            } else {
                isEncrypted = false
                put("encrypted", false)
                put("data", rawData)
            }
        }

        payloadJsonString = transmissionJson.toString(2)
        val client = buildOkHttpClient(config.timeoutSeconds)
        val requestBody = transmissionJson.toString().toRequestBody(jsonMediaType)

        val forwarderSecret = when {
            config.forwarderSecret.isNotBlank() -> config.forwarderSecret.trim()
            config.authType == AuthType.CUSTOM_HEADER && config.authHeaderKey.equals("X-Forwarder-Secret", ignoreCase = true) -> config.authHeaderValue.trim()
            config.authType == AuthType.API_KEY_HEADER -> config.authHeaderValue.trim()
            config.secretEncryptionKey.isNotBlank() -> config.secretEncryptionKey.trim()
            else -> config.authHeaderValue.trim()
        }

        val requestBuilder = Request.Builder()
            .url(config.endpointUrl.trim())
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "BarPro-Forwarder-Android/1.0")
            .addHeader("X-Forwarder-Type", "SMS_FORWARD")
            .addHeader("X-Forwarder-Device", config.deviceIdentifier)
            .addHeader("X-Device-Id", config.deviceIdentifier)
            .addHeader("X-Driver-Id", config.driverId)
            .addHeader("X-Driver-Phone", normalizedDriverPhone)
            .addHeader("X-Signature", signature)
            .addHeader("X-SMS-Type", smsType.name)
            .addHeader("X-Forwarder-Sender", sender)
            .addHeader("X-Forwarder-Rule", ruleLabel)
            .addHeader("X-Forwarder-Pattern", rulePattern)
            .addHeader("X-Forwarder-Sim", simSlot)

        if (forwarderSecret.isNotBlank()) {
            requestBuilder.addHeader("X-Forwarder-Secret", forwarderSecret)
        }

        if (trackingCode != null) {
            requestBuilder.addHeader("X-Tracking-Code", trackingCode)
        }
        if (extractedOtp != null) {
            requestBuilder.addHeader("X-Forwarder-OTP", extractedOtp)
        }

        applyAuthHeaders(requestBuilder, config)
        val request = requestBuilder.build()

        // Resilient retry loop with exponential backoff
        val maxAttempts = (config.maxRetries.coerceAtLeast(0) + 1)
        var lastException: Exception? = null
        var lastResponseCode: Int? = null
        var lastResponseBody: String? = null

        for (attempt in 1..maxAttempts) {
            try {
                val response = client.newCall(request).execute()
                lastResponseCode = response.code
                val responseBody = response.body?.string() ?: ""
                lastResponseBody = if (responseBody.length > 500) responseBody.take(500) + "..." else responseBody

                if (response.isSuccessful) {
                    val duration = System.currentTimeMillis() - startTime
                    val bodyJson = try { JSONObject(responseBody) } catch (e: Exception) { null }
                    val serverExtractedCode = bodyJson?.optString("extracted_code", null) ?: bodyJson?.optString("otp_code", null)
                    val finalOtp = if (!serverExtractedCode.isNullOrBlank()) serverExtractedCode else extractedOtp
                    val isServerSuccess = bodyJson?.optBoolean("success", true) ?: true
                    val serverMsg = bodyJson?.optString("message", null)

                    return@withContext TransmissionResult(
                        isSuccess = isServerSuccess,
                        httpStatusCode = response.code,
                        responseBody = lastResponseBody,
                        errorMessage = if (!isServerSuccess) (serverMsg ?: "کد OTP در متن پیامک شناسایی نشد") else null,
                        payloadSent = payloadJsonString,
                        isEncrypted = isEncrypted,
                        durationMs = duration,
                        smsType = smsType,
                        trackingCode = trackingCode,
                        otpCode = finalOtp,
                        signature = signature
                    )
                }

                // If server error (5xx) and we have retries left, wait briefly and retry
                if (response.code >= 500 && attempt < maxAttempts) {
                    delay(800L * attempt)
                    continue
                }

                // Client error (4xx) - don't retry, return failure
                val duration = System.currentTimeMillis() - startTime
                val clientErrorMessage = when (response.code) {
                    401 -> "خطای احراز هویت (401 Unauthorized): کلید X-Forwarder-Secret با تنظیمات سرور مطابقت ندارد."
                    403 -> "دسترسی غیرمجاز (403 Forbidden): سرور اجازه انتقال پیامک را به این راننده نداد."
                    404 -> "مسیر وب‌هوک یافت نشد (404 Not Found): آدرس وب‌هوک /api/v1/rpa/sms-forwarder را بررسی فرمایید."
                    422 -> "خطای ساختار داده (422 Unprocessable Entity): پارامترهای ارسالی با ساختار SmsForwarderPayload همخوانی ندارد."
                    429 -> "محدودیت نرخ ارسال (429 Rate Limit): تعداد درخواست‌ها فراتر از سقف مجاز است."
                    else -> "خطای وب‌سرویس: کد ${response.code} (${response.message})"
                }
                return@withContext TransmissionResult(
                    isSuccess = false,
                    httpStatusCode = response.code,
                    responseBody = lastResponseBody,
                    errorMessage = clientErrorMessage,
                    payloadSent = payloadJsonString,
                    isEncrypted = isEncrypted,
                    durationMs = duration,
                    smsType = smsType,
                    trackingCode = trackingCode,
                    otpCode = extractedOtp,
                    signature = signature
                )
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxAttempts) {
                    delay(800L * attempt)
                }
            }
        }

        val duration = System.currentTimeMillis() - startTime
        val friendlyError = when {
            lastException?.message?.contains("Failed to connect", ignoreCase = true) == true ->
                "امکان برقراری اتصال به آدرس سرور وجود ندارد. لطفاً روشن بودن اینترنت یا آدرس URL را بررسی فرمایید."
            lastException?.message?.contains("timeout", ignoreCase = true) == true ->
                "مهلت زمانی ارتباط با سرور پایان یافت (Timeout)."
            else -> lastException?.localizedMessage ?: "خطای ناشناخته در ارسال به سرور"
        }

        TransmissionResult(
            isSuccess = false,
            httpStatusCode = lastResponseCode,
            responseBody = lastResponseBody,
            errorMessage = friendlyError,
            payloadSent = payloadJsonString,
            isEncrypted = isEncrypted,
            durationMs = duration,
            smsType = smsType,
            trackingCode = trackingCode,
            otpCode = extractedOtp,
            signature = signature
        )
    }

    suspend fun sendHeartbeat(
        config: ForwardConfig,
        deviceStatus: DeviceStatus,
        pendingFailedCount: Int
    ): HeartbeatResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            val heartbeatJson = JSONObject().apply {
                put("type", "HEARTBEAT")
                put("event", "HEARTBEAT")
                put("device_id", config.deviceIdentifier)
                put("timestamp", System.currentTimeMillis())
                put("battery_level", deviceStatus.batteryPercent)
                put("is_charging", deviceStatus.isCharging)
                put("network_type", deviceStatus.networkType)
                put("is_online", deviceStatus.isConnected)
                put("pending_offline_count", pendingFailedCount)
                put("app_version", "1.0")
            }

            val client = buildOkHttpClient(10)
            val requestBody = heartbeatJson.toString().toRequestBody(jsonMediaType)

            val requestBuilder = Request.Builder()
                .url(config.endpointUrl.trim())
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "BarPro-Forwarder-Android/1.0")
                .addHeader("X-Forwarder-Type", "HEARTBEAT")
                .addHeader("X-Forwarder-Device", config.deviceIdentifier)

            applyAuthHeaders(requestBuilder, config)

            val response = client.newCall(requestBuilder.build()).execute()
            val duration = System.currentTimeMillis() - startTime
            val responseBody = response.body?.string() ?: ""

            var pendingCommand: ServerCommand? = null
            if (response.isSuccessful && responseBody.isNotBlank()) {
                try {
                    val root = JSONObject(responseBody)
                    // Check if server returned a command for the app to execute
                    if (root.optBoolean("has_command", false) || root.has("command")) {
                        val cmdObj = root.optJSONObject("command") ?: root
                        val cmdId = cmdObj.optString("id", cmdObj.optString("command_id", "cmd_${System.currentTimeMillis()}"))
                        val cmdType = cmdObj.optString("type", cmdObj.optString("command_type", "UNKNOWN"))
                        val cmdSender = cmdObj.optString("sender", null)
                        val cmdTargetTime = if (cmdObj.has("target_timestamp")) cmdObj.optLong("target_timestamp") else null

                        pendingCommand = ServerCommand(
                            id = cmdId,
                            type = cmdType,
                            sender = cmdSender,
                            targetTimestamp = cmdTargetTime,
                            rawJson = cmdObj.toString()
                        )
                    }
                } catch (ignore: Exception) {}
            }

            HeartbeatResult(
                isSuccess = response.isSuccessful,
                httpCode = response.code,
                pendingCommand = pendingCommand,
                durationMs = duration,
                errorMessage = if (response.isSuccessful) null else "HTTP ${response.code}"
            )
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            HeartbeatResult(
                isSuccess = false,
                httpCode = null,
                pendingCommand = null,
                durationMs = duration,
                errorMessage = e.localizedMessage ?: "Network error"
            )
        }
    }

    suspend fun replyToCommand(
        commandId: String,
        commandType: String,
        status: String,
        resultData: JSONObject,
        config: ForwardConfig
    ): TransmissionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var payloadSent = ""
        try {
            val root = JSONObject().apply {
                put("type", "COMMAND_REPLY")
                put("event", "COMMAND_REPLY")
                put("command_id", commandId)
                put("command_type", commandType)
                put("status", status)
                put("device_id", config.deviceIdentifier)
                put("timestamp", System.currentTimeMillis())
                put("result", resultData)
            }
            payloadSent = root.toString(2)

            val client = buildOkHttpClient(12)
            val requestBody = root.toString().toRequestBody(jsonMediaType)
            val requestBuilder = Request.Builder()
                .url(config.endpointUrl.trim())
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Forwarder-Type", "COMMAND_REPLY")
                .addHeader("X-Forwarder-Command-Id", commandId)
                .addHeader("X-Forwarder-Device", config.deviceIdentifier)

            applyAuthHeaders(requestBuilder, config)

            val response = client.newCall(requestBuilder.build()).execute()
            val duration = System.currentTimeMillis() - startTime
            val body = response.body?.string() ?: ""

            TransmissionResult(
                isSuccess = response.isSuccessful,
                httpStatusCode = response.code,
                responseBody = body,
                errorMessage = if (response.isSuccessful) null else "HTTP error: ${response.code}",
                payloadSent = payloadSent,
                isEncrypted = false,
                durationMs = duration
            )
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            TransmissionResult(
                isSuccess = false,
                httpStatusCode = null,
                responseBody = null,
                errorMessage = e.localizedMessage ?: "Failed to reply to command",
                payloadSent = payloadSent,
                isEncrypted = false,
                durationMs = duration
            )
        }
    }

    suspend fun sendBatchSync(
        logs: List<ForwardLog>,
        config: ForwardConfig
    ): TransmissionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var payloadSent = ""
        try {
            val messagesArray = JSONArray()
            for (log in logs) {
                val item = JSONObject().apply {
                    put("id", log.id)
                    put("sender", log.sender)
                    put("message", log.messageBody)
                    put("received_timestamp", log.receivedTimestamp)
                    val otp = OtpExtractor.extractOtp(log.messageBody)
                    if (otp != null) {
                        put("otp_code", otp)
                    }
                    put("matched_rule", log.matchedRuleLabel ?: "")
                }
                messagesArray.put(item)
            }

            val root = JSONObject().apply {
                put("type", "BATCH_SYNC")
                put("event", "BATCH_OFFLINE_SYNC")
                put("device_id", config.deviceIdentifier)
                put("timestamp", System.currentTimeMillis())
                put("count", logs.size)
                put("messages", messagesArray)
            }
            payloadSent = root.toString(2)

            val client = buildOkHttpClient(config.timeoutSeconds)
            val requestBody = root.toString().toRequestBody(jsonMediaType)
            val requestBuilder = Request.Builder()
                .url(config.endpointUrl.trim())
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Forwarder-Type", "BATCH_SYNC")
                .addHeader("X-Forwarder-Batch-Size", logs.size.toString())
                .addHeader("X-Forwarder-Device", config.deviceIdentifier)

            applyAuthHeaders(requestBuilder, config)

            val response = client.newCall(requestBuilder.build()).execute()
            val duration = System.currentTimeMillis() - startTime
            val body = response.body?.string() ?: ""

            TransmissionResult(
                isSuccess = response.isSuccessful,
                httpStatusCode = response.code,
                responseBody = body,
                errorMessage = if (response.isSuccessful) null else "HTTP error ${response.code}",
                payloadSent = payloadSent,
                isEncrypted = false,
                durationMs = duration
            )
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            TransmissionResult(
                isSuccess = false,
                httpStatusCode = null,
                responseBody = null,
                errorMessage = e.localizedMessage ?: "Batch sync network error",
                payloadSent = payloadSent,
                isEncrypted = false,
                durationMs = duration
            )
        }
    }

    suspend fun testEndpoint(
        endpointUrl: String,
        authType: AuthType,
        authHeaderKey: String,
        authHeaderValue: String,
        isEncryptionEnabled: Boolean,
        secretKey: String,
        deviceIdentifier: String
    ): TransmissionResult = withContext(Dispatchers.IO) {
        val forwarderSecret = if (authHeaderKey.equals("X-Forwarder-Secret", ignoreCase = true) && authHeaderValue.isNotBlank()) {
            authHeaderValue
        } else if (secretKey.isNotBlank()) {
            secretKey
        } else {
            authHeaderValue
        }

        val testConfig = ForwardConfig(
            endpointUrl = endpointUrl,
            authType = authType,
            authHeaderKey = authHeaderKey,
            authHeaderValue = authHeaderValue,
            forwarderSecret = forwarderSecret,
            isEncryptionEnabled = isEncryptionEnabled,
            secretEncryptionKey = secretKey,
            deviceIdentifier = deviceIdentifier,
            timeoutSeconds = 10,
            maxRetries = 1
        )

        forwardMessage(
            sender = "10008545",
            messageBody = "سامانه بارنامه شهرداری: کد ورود شما ۳۹۱۸۲ می باشد.",
            timestamp = System.currentTimeMillis(),
            config = testConfig
        )
    }

    suspend fun sendOtpInquiryResponse(
        sender: String,
        otpCode: String,
        requestedTimestamp: Long,
        smsTimestamp: Long,
        rawMessage: String,
        matchedRuleLabel: String,
        config: ForwardConfig
    ): TransmissionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var payloadJsonString = ""
        var isEncrypted = false

        try {
            val delaySeconds = (smsTimestamp - requestedTimestamp) / 1000

            // Inner payload with OTP and query correlation
            val rawData = JSONObject().apply {
                put("type", "OTP_INQUIRY_RESPONSE")
                put("sender", sender)
                put("otp_code", otpCode)
                put("requested_timestamp", requestedTimestamp)
                put("sms_timestamp", smsTimestamp)
                put("delay_seconds", delaySeconds)
                put("matched_rule", matchedRuleLabel)
                put("raw_message", rawMessage)
                put("device_id", config.deviceIdentifier)
                put("timestamp", System.currentTimeMillis())
            }

            val rootJson = JSONObject().apply {
                put("version", "1.0")
                put("type", "OTP_INQUIRY_RESPONSE")
                put("timestamp", System.currentTimeMillis())
                put("device_id", config.deviceIdentifier)
                put("sender", sender)
                put("otp_code", otpCode)
                put("matched_rule", matchedRuleLabel)

                if (config.isEncryptionEnabled && config.secretEncryptionKey.isNotBlank()) {
                    isEncrypted = true
                    put("encrypted", true)
                    val rawJsonString = rawData.toString()
                    val cipherPackage = CryptoEngine.encrypt(rawJsonString, config.secretEncryptionKey)
                    put("iv", cipherPackage.ivBase64)
                    put("ciphertext", cipherPackage.ciphertextBase64)
                    put("signature", cipherPackage.signatureHmac)
                    put("algorithm", "AES-256-GCM")
                } else {
                    put("encrypted", false)
                    put("data", rawData)
                }
            }

            payloadJsonString = rootJson.toString(2)
            val body = payloadJsonString.toRequestBody(jsonMediaType)

            val requestBuilder = Request.Builder()
                .url(config.endpointUrl.trim())
                .post(body)
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "BarPro-Forwarder-Android/1.0")
                .addHeader("X-Forwarder-Device", config.deviceIdentifier)
                .addHeader("X-Forwarder-Type", "OTP_RESPONSE")
                .addHeader("X-Forwarder-Sender", sender)
                .addHeader("X-Forwarder-OTP", otpCode)
                .addHeader("X-Forwarder-Rule", matchedRuleLabel)
                .addHeader("X-Forwarder-Query-Time", requestedTimestamp.toString())

            applyAuthHeaders(requestBuilder, config)

            val client = buildOkHttpClient(config.timeoutSeconds)
            val request = requestBuilder.build()
            val response = client.newCall(request).execute()
            val duration = System.currentTimeMillis() - startTime
            val responseBody = response.body?.string() ?: ""

            TransmissionResult(
                isSuccess = response.isSuccessful,
                httpStatusCode = response.code,
                responseBody = if (responseBody.length > 500) responseBody.take(500) + "..." else responseBody,
                errorMessage = if (!response.isSuccessful) "HTTP error: ${response.code} ${response.message}" else null,
                payloadSent = payloadJsonString,
                isEncrypted = isEncrypted,
                durationMs = duration
            )
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            TransmissionResult(
                isSuccess = false,
                httpStatusCode = null,
                responseBody = null,
                errorMessage = e.localizedMessage ?: e.javaClass.simpleName,
                payloadSent = payloadJsonString,
                isEncrypted = isEncrypted,
                durationMs = duration
            )
        }
    }
}
