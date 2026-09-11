package com.example.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.example.crypto.AesEncryptionUtils
import com.example.crypto.SecureStorageManager
import com.example.data.local.AppDatabase
import com.example.data.model.FilterRule
import com.example.data.model.ForwardConfig
import com.example.data.model.ForwardFilterMode
import com.example.data.model.ForwardLog
import com.example.data.model.ForwardStatus
import com.example.network.ServerHealthMonitor
import com.example.network.ServerHealthState
import com.example.network.SmsForwarderClient
import com.example.network.TransmissionResult
import com.example.utils.LogSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class OtpInquiryExecutionResult(
    val isSuccess: Boolean,
    val otpCode: String?,
    val matchedLog: ForwardLog?,
    val transmissionResult: TransmissionResult?,
    val message: String
)

class SmsForwardRepository(
    private val database: AppDatabase,
    private val client: SmsForwarderClient = SmsForwarderClient(),
    private val appContext: Context? = null
) {
    private val secureStorage by lazy {
        appContext?.let { SecureStorageManager.getInstance(it) }
    }

    init {
        // Wire salt provider for PBKDF2 key derivation from secure storage
        appContext?.let { ctx ->
            val storage = SecureStorageManager.getInstance(ctx)
            AesEncryptionUtils.saltProvider = { storage.getOrCreateSalt() }
        }
    }

    val allRules: Flow<List<FilterRule>> = database.filterRuleDao().getAllRules()
    val allLogs: Flow<List<ForwardLog>> = database.forwardLogDao().getAllLogs()

    val configFlow: Flow<ForwardConfig?> = database.forwardConfigDao().getConfigFlow().map { config ->
        if (config == null) null
        else {
            val storage = secureStorage
            if (storage != null) {
                val secret = storage.getForwarderSecret().ifBlank { config.forwarderSecret }
                val key = storage.getSecretEncryptionKey().ifBlank { config.secretEncryptionKey }
                config.copy(forwarderSecret = secret, secretEncryptionKey = key)
            } else {
                config
            }
        }
    }

    val serverHealthState: StateFlow<ServerHealthState> = ServerHealthMonitor.healthState

    val totalLogsCount: Flow<Int> = database.forwardLogDao().getTotalLogsCount()
    val successCount: Flow<Int> = database.forwardLogDao().getSuccessCount()
    val failedCount: Flow<Int> = database.forwardLogDao().getFailedCount()
    val skippedCount: Flow<Int> = database.forwardLogDao().getSkippedCount()
    val pendingCount: Flow<Int> = database.forwardLogDao().getPendingCount()
    val rulesCount: Flow<Int> = database.filterRuleDao().getRulesCount()

    suspend fun getConfig(): ForwardConfig {
        val dbConfig = database.forwardConfigDao().getConfig() ?: ForwardConfig()
        val storage = secureStorage
        if (storage != null) {
            val storedSecret = storage.getForwarderSecret()
            val storedKey = storage.getSecretEncryptionKey()

            val finalSecret = if (storedSecret.isNotBlank()) storedSecret else {
                if (dbConfig.forwarderSecret.isNotBlank()) {
                    storage.saveForwarderSecret(dbConfig.forwarderSecret)
                    dbConfig.forwarderSecret
                } else ""
            }

            val finalKey = if (storedKey.isNotBlank()) storedKey else {
                if (dbConfig.secretEncryptionKey.isNotBlank()) {
                    storage.saveSecretEncryptionKey(dbConfig.secretEncryptionKey)
                    dbConfig.secretEncryptionKey
                } else ""
            }

            return dbConfig.copy(
                forwarderSecret = finalSecret,
                secretEncryptionKey = finalKey
            )
        }
        return dbConfig
    }

    suspend fun saveConfig(config: ForwardConfig) = withContext(Dispatchers.IO) {
        secureStorage?.apply {
            saveForwarderSecret(config.forwarderSecret)
            saveSecretEncryptionKey(config.secretEncryptionKey)
        }
        database.forwardConfigDao().insertOrUpdate(config)
    }

    suspend fun insertRule(rule: FilterRule) = withContext(Dispatchers.IO) {
        database.filterRuleDao().insertRule(rule)
    }

    suspend fun updateRule(rule: FilterRule) = withContext(Dispatchers.IO) {
        database.filterRuleDao().updateRule(rule)
    }

    suspend fun deleteRule(rule: FilterRule) = withContext(Dispatchers.IO) {
        database.filterRuleDao().deleteRule(rule)
    }

    suspend fun deleteRuleById(id: Long) = withContext(Dispatchers.IO) {
        database.filterRuleDao().deleteRuleById(id)
    }

    suspend fun clearLogs() = withContext(Dispatchers.IO) {
        database.forwardLogDao().clearAllLogs()
    }

    suspend fun deleteLog(log: ForwardLog) = withContext(Dispatchers.IO) {
        database.forwardLogDao().deleteLog(log)
    }

    suspend fun checkServerHealth(context: Context): ServerHealthState {
        val config = getConfig()
        return ServerHealthMonitor.performHealthPing(context, config, client)
    }

    suspend fun triggerTestDisconnectAlert(context: Context) = withContext(Dispatchers.IO) {
        val config = getConfig()
        ServerHealthMonitor.triggerTestAlert(context, config.endpointUrl)
    }

    suspend fun testEndpoint(
        url: String,
        authType: com.example.data.model.AuthType,
        authHeaderKey: String,
        authHeaderValue: String,
        isEncryptionEnabled: Boolean,
        secretKey: String,
        deviceIdentifier: String
    ): TransmissionResult {
        val result = client.testEndpoint(
            endpointUrl = url,
            authType = authType,
            authHeaderKey = authHeaderKey,
            authHeaderValue = authHeaderValue,
            isEncryptionEnabled = isEncryptionEnabled,
            secretKey = secretKey,
            deviceIdentifier = deviceIdentifier
        )

        if (result.isSuccess) {
            ServerHealthMonitor.recordSuccess(appContext, url, result.durationMs)
        } else if (appContext != null) {
            val config = getConfig()
            ServerHealthMonitor.recordFailure(appContext, url, result.errorMessage, config)
        }

        return result
    }

    suspend fun getLogById(id: Long): ForwardLog? = withContext(Dispatchers.IO) {
        database.forwardLogDao().getLogById(id)
    }

    /**
     * Transmits a pending or retrying outbox log to the server.
     * Decrypts the raw SMS content from the securely stored outbox payload,
     * performs the HTTP forward request, updates the entity status to SUCCESS or FAILED,
     * and redacts sensitive data (OTP and phone numbers) in the persistent log.
     */
    suspend fun transmitPendingLog(logId: Long): TransmissionResult = withContext(Dispatchers.IO) {
        val log = database.forwardLogDao().getLogById(logId)
            ?: return@withContext TransmissionResult(
                isSuccess = false,
                httpStatusCode = null,
                responseBody = null,
                errorMessage = "Log with ID $logId not found",
                payloadSent = "",
                isEncrypted = false,
                durationMs = 0L
            )

        val config = getConfig()
        val outboxKey = config.secretEncryptionKey.ifBlank { "BarPro-Outbox-Key-2026" }

        // Decrypt raw message from encryptedBody if available
        val rawMessage = if (!log.encryptedBody.isNullOrBlank()) {
            val parts = log.encryptedBody.split(":")
            if (parts.size == 2) {
                try {
                    AesEncryptionUtils.decrypt(parts[0], parts[1], outboxKey)
                } catch (e: Exception) {
                    log.messageBody
                }
            } else log.messageBody
        } else log.messageBody

        val activeRules = database.filterRuleDao().getActiveRules()
        val matchedRule = activeRules.firstOrNull { it.label == log.matchedRuleLabel || it.matches(log.sender, rawMessage) }

        val result = client.forwardMessage(
            sender = log.sender,
            messageBody = rawMessage,
            timestamp = log.receivedTimestamp,
            config = config,
            matchedRule = matchedRule,
            simSlot = log.simSlot
        )

        if (result.isSuccess) {
            ServerHealthMonitor.recordSuccess(appContext, config.endpointUrl, result.durationMs)
        } else if (appContext != null) {
            ServerHealthMonitor.recordFailure(appContext, config.endpointUrl, result.errorMessage, config)
        }

        val updatedLog = log.copy(
            forwardedTimestamp = System.currentTimeMillis(),
            status = if (result.isSuccess) ForwardStatus.SUCCESS else ForwardStatus.FAILED,
            httpStatusCode = result.httpStatusCode,
            responseSummary = result.responseBody,
            errorMessage = result.errorMessage,
            isEncrypted = result.isEncrypted,
            payloadPreview = LogSanitizer.sanitize(result.payloadSent),
            endpointUrl = config.endpointUrl,
            durationMs = result.durationMs,
            driverId = config.driverId,
            smsType = result.smsType,
            trackingCode = result.trackingCode ?: log.trackingCode,
            otpCode = LogSanitizer.maskOtp(result.otpCode ?: log.otpCode),
            signature = result.signature ?: log.signature,
            retryCount = log.retryCount + 1,
            lastRetryTimestamp = System.currentTimeMillis()
        )
        database.forwardLogDao().updateLog(updatedLog)

        result
    }

    suspend fun updateLogStatus(logId: Long, status: ForwardStatus, errorMessage: String? = null) = withContext(Dispatchers.IO) {
        val log = database.forwardLogDao().getLogById(logId) ?: return@withContext
        val updated = log.copy(
            status = status,
            errorMessage = errorMessage ?: log.errorMessage,
            lastRetryTimestamp = System.currentTimeMillis()
        )
        database.forwardLogDao().updateLog(updated)
    }

    suspend fun retryForwardLog(log: ForwardLog): ForwardLog = withContext(Dispatchers.IO) {
        transmitPendingLog(log.id)
        database.forwardLogDao().getLogById(log.id) ?: log
    }

    /**
     * Transactional Outbox Pattern with In-Memory Sliding-Window Deduplication:
     * 1. Checks in-memory fingerprint cache to deduplicate simultaneous events from SmsReceiver and SmsNotificationListener.
     * 2. Inspects filtering criteria. If excluded or disabled, saves SKIPPED log.
     * 3. If valid for forwarding, inserts into Room with status PENDING inside database.withTransaction.
     * 4. Sanitizes visible log body and masks OTP.
     * 5. Enqueues SmsSyncWorker via WorkManager passing only the logId.
     */
    suspend fun processIncomingSms(
        sender: String,
        messageBody: String,
        receivedTimestamp: Long = System.currentTimeMillis(),
        simSlot: String = "SIM 1"
    ): ForwardLog = withContext(Dispatchers.IO) {
        val fingerprint = com.example.utils.SmsParser.computeFingerprint(sender, messageBody)
        val now = System.currentTimeMillis()

        synchronized(dedupLock) {
            val lastSeen = recentSmsTimestamps[fingerprint]
            if (lastSeen != null && (now - lastSeen) < DEDUP_WINDOW_MS) {
                val cached = recentSmsLogs[fingerprint]
                if (cached != null) {
                    android.util.Log.i(
                        TAG,
                        "Dual-capture redundant SMS ignored within ${now - lastSeen}ms | Sender: $sender | Cached Log ID: ${cached.id}"
                    )
                    return@withContext cached
                }
            }
            recentSmsTimestamps[fingerprint] = now
        }

        val config = getConfig()

        // 0. If filterUtcmsOnly is enabled, check if SMS is UTCMS/BarPro related
        if (config.filterUtcmsOnly && !com.example.utils.SmsParser.isUtcmsSms(sender, messageBody)) {
            val log = ForwardLog(
                sender = sender,
                messageBody = LogSanitizer.sanitize(messageBody),
                receivedTimestamp = receivedTimestamp,
                status = ForwardStatus.SKIPPED,
                errorMessage = "پیامک فیلتر شد: فیلتر هوشمند فقط پیامک‌های سامانه بارنامه و بارپرو را مجاز می‌داند",
                endpointUrl = config.endpointUrl,
                driverId = config.driverId,
                smsType = com.example.data.model.SmsType.OTHER,
                simSlot = simSlot,
                otpCode = LogSanitizer.maskOtp(com.example.utils.SmsParser.extractOtp(messageBody))
            )
            val id = database.forwardLogDao().insertLog(log)
            val resultLog = log.copy(id = id)
            synchronized(dedupLock) { recentSmsLogs[fingerprint] = resultLog }
            return@withContext resultLog
        }

        // 1. Check if master toggle is enabled
        if (!config.isMasterEnabled) {
            val log = ForwardLog(
                sender = sender,
                messageBody = LogSanitizer.sanitize(messageBody),
                receivedTimestamp = receivedTimestamp,
                status = ForwardStatus.SKIPPED,
                errorMessage = "سرویس فوروارد در تنظیمات غیرفعال است",
                endpointUrl = config.endpointUrl,
                driverId = config.driverId,
                smsType = com.example.utils.SmsParser.detectSmsType(messageBody),
                simSlot = simSlot,
                otpCode = LogSanitizer.maskOtp(com.example.utils.SmsParser.extractOtp(messageBody))
            )
            val id = database.forwardLogDao().insertLog(log)
            val resultLog = log.copy(id = id)
            synchronized(dedupLock) { recentSmsLogs[fingerprint] = resultLog }
            return@withContext resultLog
        }

        // 2. Check filter rules if in SPECIFIC_RULES_ONLY mode
        var matchedRule: FilterRule? = null
        if (config.filterMode == ForwardFilterMode.SPECIFIC_RULES_ONLY) {
            val activeRules = database.filterRuleDao().getActiveRules()
            matchedRule = activeRules.firstOrNull { it.matches(sender, messageBody) }

            if (matchedRule == null) {
                val log = ForwardLog(
                    sender = sender,
                    messageBody = LogSanitizer.sanitize(messageBody),
                    receivedTimestamp = receivedTimestamp,
                    status = ForwardStatus.SKIPPED,
                    errorMessage = "شماره فرستنده $sender با هیچ‌یک از قوانین فعال همخوانی ندارد",
                    endpointUrl = config.endpointUrl,
                    driverId = config.driverId,
                    smsType = com.example.utils.SmsParser.detectSmsType(messageBody),
                    simSlot = simSlot,
                    otpCode = LogSanitizer.maskOtp(com.example.utils.SmsParser.extractOtp(messageBody))
                )
                val id = database.forwardLogDao().insertLog(log)
                val resultLog = log.copy(id = id)
                synchronized(dedupLock) { recentSmsLogs[fingerprint] = resultLog }
                return@withContext resultLog
            }
        }

        val ruleLabel = matchedRule?.label ?: if (config.filterMode == ForwardFilterMode.ALL_MESSAGES) "تمام پیامک‌ها (All Messages)" else "قانون اختصاصی"
        val smsType = com.example.utils.SmsParser.detectSmsType(messageBody)
        val trackingCode = com.example.utils.SmsParser.extractTrackingCode(messageBody)
        val extractedOtp = com.example.utils.SmsParser.extractOtp(messageBody)
        val signature = com.example.utils.SignatureUtils.generateSignature(
            driverId = config.driverId,
            phoneNumber = sender,
            messageBody = messageBody,
            timestamp = receivedTimestamp,
            secretKey = config.secretEncryptionKey
        )

        // Encrypt raw message body for Outbox dispatch so plaintext SMS is never persisted in SQLite
        val outboxKey = config.secretEncryptionKey.ifBlank { "BarPro-Outbox-Key-2026" }
        val encryptedPayload = try {
            val enc = AesEncryptionUtils.encrypt(messageBody, outboxKey)
            "${enc.iv}:${enc.ciphertext}"
        } catch (e: Exception) {
            null
        }

        // Transactional Outbox Step 1: Insert into Room with PENDING status inside database.withTransaction
        val insertedLog = database.withTransaction {
            val initialLog = ForwardLog(
                sender = sender,
                messageBody = LogSanitizer.sanitize(messageBody), // Redacted for DB & UI
                receivedTimestamp = receivedTimestamp,
                forwardedTimestamp = null,
                status = ForwardStatus.PENDING,
                matchedRuleLabel = ruleLabel,
                endpointUrl = config.endpointUrl,
                driverId = config.driverId,
                smsType = smsType,
                trackingCode = trackingCode,
                otpCode = LogSanitizer.maskOtp(extractedOtp), // Redacted as ***
                signature = signature,
                retryCount = 0,
                simSlot = simSlot,
                encryptedBody = encryptedPayload
            )
            val id = database.forwardLogDao().insertLog(initialLog)
            initialLog.copy(id = id)
        }

        // Cache in deduplication map
        synchronized(dedupLock) {
            recentSmsLogs[fingerprint] = insertedLog
        }

        // Zero-Latency Direct Push (Fast-Path):
        // Since OTP codes have a strict 5-minute validity window, attempt immediate network transmission
        // right inside the active Coroutine / WakeLock. If successful within milliseconds, mark SUCCESS.
        // If device is currently offline or connection fails:
        // 1. If SMS Fallback is enabled, automatically relay the code via SMS to the server gateway immediately.
        // 2. WorkManager will handle guaranteed network transmission as outbox fallback.
        var finalLog = insertedLog
        try {
            val directResult = transmitPendingLog(insertedLog.id)
            if (directResult.isSuccess) {
                finalLog = database.forwardLogDao().getLogById(insertedLog.id) ?: insertedLog
                synchronized(dedupLock) { recentSmsLogs[fingerprint] = finalLog }
                android.util.Log.i(TAG, "Fast-path immediate delivery succeeded for Log #${insertedLog.id} in ${directResult.durationMs}ms")
            } else {
                // If offline or weak internet, trigger automated SMS Fallback Relay if configured
                val codeToSend = extractedOtp ?: trackingCode
                if (appContext != null && config.enableSmsFallback && !codeToSend.isNullOrBlank() && config.fallbackServerPhoneNumber.isNotBlank()) {
                    val smsSuccess = com.example.utils.SmsRelayHelper.sendFallbackSms(
                        context = appContext,
                        destinationPhone = config.fallbackServerPhoneNumber,
                        driverId = config.driverId,
                        driverPhone = config.driverPhone,
                        code = codeToSend,
                        smsType = smsType.name
                    )
                    if (smsSuccess) {
                        android.util.Log.i(TAG, "Automated SMS Fallback dispatched code $codeToSend to ${config.fallbackServerPhoneNumber}")
                    }
                }

                // Also enqueue guaranteed WorkManager sync for when connection restores
                if (appContext != null) {
                    com.example.service.SmsSyncWorker.enqueue(appContext, insertedLog.id)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Fast-path attempt failed, delegating to WorkManager: ${e.message}")
            // Fallback SMS in exception case
            val codeToSend = extractedOtp ?: trackingCode
            if (appContext != null && config.enableSmsFallback && !codeToSend.isNullOrBlank() && config.fallbackServerPhoneNumber.isNotBlank()) {
                com.example.utils.SmsRelayHelper.sendFallbackSms(
                    context = appContext,
                    destinationPhone = config.fallbackServerPhoneNumber,
                    driverId = config.driverId,
                    driverPhone = config.driverPhone,
                    code = codeToSend,
                    smsType = smsType.name
                )
            }
            if (appContext != null) {
                com.example.service.SmsSyncWorker.enqueue(appContext, insertedLog.id)
            }
        }

        finalLog
    }

    suspend fun queryAndSendOtpForServer(
        senderQuery: String,
        requestedTimestamp: Long = System.currentTimeMillis(),
        toleranceMinutes: Int = 15
    ): OtpInquiryExecutionResult = withContext(Dispatchers.IO) {
        val config = getConfig()
        val minTime = requestedTimestamp - (toleranceMinutes * 60 * 1000L)

        val candidateLog = database.forwardLogDao().getClosestLogForSender(senderQuery.trim(), requestedTimestamp)
            ?: database.forwardLogDao().getLogsForSenderSince(senderQuery.trim(), minTime).firstOrNull()
            ?: database.forwardLogDao().getLatestLog()

        if (candidateLog == null) {
            return@withContext OtpInquiryExecutionResult(
                isSuccess = false,
                otpCode = null,
                matchedLog = null,
                transmissionResult = null,
                message = "هیچ پیامکی از شماره یا سامانه «$senderQuery» در بازه زمانی درخواستی یافت نشد."
            )
        }

        // Recover raw message if available
        val outboxKey = config.secretEncryptionKey.ifBlank { "BarPro-Outbox-Key-2026" }
        val rawMessage = if (!candidateLog.encryptedBody.isNullOrBlank()) {
            val parts = candidateLog.encryptedBody.split(":")
            if (parts.size == 2) {
                try {
                    AesEncryptionUtils.decrypt(parts[0], parts[1], outboxKey)
                } catch (_: Exception) {
                    candidateLog.messageBody
                }
            } else candidateLog.messageBody
        } else candidateLog.messageBody

        val otpResult = com.example.otp.OtpExtractor.extractOtp(
            sender = candidateLog.sender,
            rawMessage = rawMessage,
            timestamp = candidateLog.receivedTimestamp
        )

        val otpCode = otpResult.code
        if (otpCode.isNullOrBlank()) {
            return@withContext OtpInquiryExecutionResult(
                isSuccess = false,
                otpCode = null,
                matchedLog = candidateLog,
                transmissionResult = null,
                message = "پیامک فرستنده یافت شد اما کد اعتبارسنجی (OTP) در متن آن شناسایی نشد."
            )
        }

        val transmission = client.sendOtpInquiryResponse(
            sender = candidateLog.sender,
            otpCode = otpCode,
            requestedTimestamp = requestedTimestamp,
            smsTimestamp = candidateLog.receivedTimestamp,
            rawMessage = rawMessage,
            matchedRuleLabel = candidateLog.matchedRuleLabel ?: "استعلام سرور (On-Demand)",
            config = config
        )

        if (transmission.isSuccess) {
            ServerHealthMonitor.recordSuccess(appContext, config.endpointUrl, transmission.durationMs)
        } else if (appContext != null) {
            ServerHealthMonitor.recordFailure(appContext, config.endpointUrl, transmission.errorMessage, config)
        }

        val otpLog = ForwardLog(
            sender = candidateLog.sender,
            messageBody = LogSanitizer.sanitize("[پاسخ استعلام OTP: ***] $rawMessage"),
            receivedTimestamp = candidateLog.receivedTimestamp,
            forwardedTimestamp = System.currentTimeMillis(),
            status = if (transmission.isSuccess) ForwardStatus.SUCCESS else ForwardStatus.FAILED,
            httpStatusCode = transmission.httpStatusCode,
            responseSummary = transmission.responseBody,
            errorMessage = transmission.errorMessage,
            matchedRuleLabel = "پاسخ استعلام OTP سرور",
            isEncrypted = transmission.isEncrypted,
            payloadPreview = LogSanitizer.sanitize(transmission.payloadSent),
            endpointUrl = config.endpointUrl,
            durationMs = transmission.durationMs,
            otpCode = LogSanitizer.maskOtp(otpCode)
        )
        database.forwardLogDao().insertLog(otpLog)

        OtpInquiryExecutionResult(
            isSuccess = transmission.isSuccess,
            otpCode = otpCode,
            matchedLog = candidateLog,
            transmissionResult = transmission,
            message = if (transmission.isSuccess) "کد اعتبارسنجی با موفقیت به سرور تحویل داده شد." else "خطا در ارسال به سرور: ${transmission.errorMessage}"
        )
    }

    suspend fun syncOfflinePendingLogs(): Int = withContext(Dispatchers.IO) {
        val config = getConfig()
        if (!config.isMasterEnabled || config.endpointUrl.isBlank()) return@withContext 0

        val pendingLogs = database.forwardLogDao().getPendingLogs(limit = 10)
        val failedLogs = database.forwardLogDao().getFailedLogs(limit = 10)
        val allLogsToSync = (pendingLogs + failedLogs).distinctBy { it.id }

        if (allLogsToSync.isEmpty()) return@withContext 0

        var successCount = 0
        for (log in allLogsToSync) {
            val result = transmitPendingLog(log.id)
            if (result.isSuccess) {
                successCount++
            }
        }
        successCount
    }

    suspend fun performHeartbeatAndCommandPoll(): com.example.network.HeartbeatResult = withContext(Dispatchers.IO) {
        val config = getConfig()
        val context = appContext ?: return@withContext com.example.network.HeartbeatResult(
            isSuccess = false,
            httpCode = null,
            pendingCommand = null,
            durationMs = 0L,
            errorMessage = "Context not available"
        )

        val deviceStatus = com.example.network.DeviceStatusHelper.getDeviceStatus(context)
        val pendingFailedCount = database.forwardLogDao().getFailedLogsCountDirect()

        val result = client.sendHeartbeat(config, deviceStatus, pendingFailedCount)
        if (result.isSuccess) {
            ServerHealthMonitor.recordSuccess(context, config.endpointUrl, result.durationMs)

            result.pendingCommand?.let { cmd ->
                handleServerCommand(cmd, config)
            }

            if (config.enableAutoOfflineSync && pendingFailedCount > 0) {
                syncOfflinePendingLogs()
            }
        } else {
            ServerHealthMonitor.recordFailure(context, config.endpointUrl, result.errorMessage, config)
        }

        result
    }

    private suspend fun handleServerCommand(cmd: com.example.network.ServerCommand, config: ForwardConfig) {
        when (cmd.type.uppercase()) {
            "GET_LATEST_OTP" -> {
                val sender = cmd.sender ?: ""
                val targetTime = cmd.targetTimestamp ?: System.currentTimeMillis()
                val candidateLog = if (sender.isNotBlank()) {
                    database.forwardLogDao().getClosestLogForSender(sender, targetTime)
                        ?: database.forwardLogDao().getLogsForSenderSince(sender, targetTime - 15 * 60 * 1000L).firstOrNull()
                } else {
                    database.forwardLogDao().getLatestLog()
                }

                val replyData = org.json.JSONObject()
                if (candidateLog != null) {
                    val outboxKey = config.secretEncryptionKey.ifBlank { "BarPro-Outbox-Key-2026" }
                    val rawMessage = if (!candidateLog.encryptedBody.isNullOrBlank()) {
                        val parts = candidateLog.encryptedBody.split(":")
                        if (parts.size == 2) {
                            try {
                                AesEncryptionUtils.decrypt(parts[0], parts[1], outboxKey)
                            } catch (_: Exception) {
                                candidateLog.messageBody
                            }
                        } else candidateLog.messageBody
                    } else candidateLog.messageBody

                    val otp = com.example.otp.OtpExtractor.extractOtp(rawMessage)
                    replyData.put("found", true)
                    replyData.put("otp_code", otp ?: "")
                    replyData.put("sender", candidateLog.sender)
                    replyData.put("received_timestamp", candidateLog.receivedTimestamp)
                    replyData.put("raw_message", LogSanitizer.sanitize(rawMessage))
                } else {
                    replyData.put("found", false)
                    replyData.put("message", "هیچ پیامکی در بازه مشخص یافت نشد")
                }

                client.replyToCommand(
                    commandId = cmd.id,
                    commandType = cmd.type,
                    status = if (candidateLog != null) "SUCCESS" else "NOT_FOUND",
                    resultData = replyData,
                    config = config
                )
            }
            "PING" -> {
                val replyData = org.json.JSONObject().apply {
                    put("status", "PONG")
                    put("device_id", config.deviceIdentifier)
                    put("server_time", System.currentTimeMillis())
                }
                client.replyToCommand(
                    commandId = cmd.id,
                    commandType = "PING",
                    status = "SUCCESS",
                    resultData = replyData,
                    config = config
                )
            }
            "SYNC_LOGS" -> {
                syncOfflinePendingLogs()
            }
        }
    }

    /**
     * Executes the retention policy manually or programmatically, deleting successful
     * forward logs older than the specified number of days (default 7 days).
     */
    suspend fun cleanOldSuccessfulLogs(daysOlderThan: Int = 7): Int = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(daysOlderThan.toLong())
        database.forwardLogDao().deleteOldSuccessfulLogs(cutoff)
    }

    companion object {
        private const val TAG = "SmsForwardRepository"
        private const val DEDUP_WINDOW_MS = 15_000L // 15 seconds window to absorb dual-capture broadcast + notification listener

        // Thread-safe sliding LRU caches for deduplication
        private val recentSmsTimestamps = object : LinkedHashMap<String, Long>(50, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
                return size > 50
            }
        }
        private val recentSmsLogs = object : LinkedHashMap<String, ForwardLog>(50, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ForwardLog>?): Boolean {
                return size > 50
            }
        }
        private val dedupLock = Any()

        @Volatile
        private var INSTANCE: SmsForwardRepository? = null

        fun getInstance(context: Context): SmsForwardRepository {
            return INSTANCE ?: synchronized(this) {
                val database = AppDatabase.getDatabase(context)
                val instance = SmsForwardRepository(database, appContext = context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
