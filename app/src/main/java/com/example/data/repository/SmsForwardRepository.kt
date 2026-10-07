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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.example.network.BarProContract

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
            AesEncryptionUtils.saltProvider = { SecureStorageManager.getInstance(ctx).getOrCreateSalt() }
        }
    }

    val allRules: Flow<List<FilterRule>> = database.filterRuleDao().getAllRules()
    val allLogs: Flow<List<ForwardLog>> = database.forwardLogDao().getAllLogs()

    val configFlow: Flow<ForwardConfig?> = database.forwardConfigDao().getConfigFlow().map { config ->
        config?.let { restoreSecrets(it) }
    }

    private fun restoreSecrets(config: ForwardConfig): ForwardConfig {
        val storage = secureStorage ?: return config
        val secret = storage.getForwarderSecret().ifBlank {
            config.authHeaderValue.ifBlank { config.forwarderSecret }
        }
        val key = storage.getSecretEncryptionKey().ifBlank { config.secretEncryptionKey }
        return config.copy(forwarderSecret = secret, authHeaderValue = secret, secretEncryptionKey = key)
    }

    val serverHealthState: StateFlow<ServerHealthState> = ServerHealthMonitor.healthState

    val totalLogsCount: Flow<Int> = database.forwardLogDao().getTotalLogsCount()
    val successCount: Flow<Int> = database.forwardLogDao().getSuccessCount()
    val failedCount: Flow<Int> = database.forwardLogDao().getFailedCount()
    val skippedCount: Flow<Int> = database.forwardLogDao().getSkippedCount()
    val pendingCount: Flow<Int> = database.forwardLogDao().getPendingCount()
    val rulesCount: Flow<Int> = database.filterRuleDao().getRulesCount()

    suspend fun getConfig(): ForwardConfig {
        val persisted = database.forwardConfigDao().getConfig() ?: ForwardConfig()
        val restored = restoreSecrets(persisted)
        // Migrate previous plaintext config fields into the Keystore-backed store.
        if (secureStorage != null && (persisted.forwarderSecret.isNotEmpty() ||
                persisted.authHeaderValue.isNotEmpty() || persisted.secretEncryptionKey.isNotEmpty())) {
            saveConfig(restored)
        }
        return restored
    }

    suspend fun saveConfig(config: ForwardConfig) = withContext(Dispatchers.IO) {
        val storage = secureStorage
        if (storage == null) {
            // Used only by repositories without an Android context (unit tests).
            database.forwardConfigDao().insertOrUpdate(config)
        } else {
            storage.saveForwarderSecret(BarProContract.token(config))
            storage.saveSecretEncryptionKey(config.secretEncryptionKey)
            database.forwardConfigDao().insertOrUpdate(config.copy(
                forwarderSecret = "", authHeaderValue = "", secretEncryptionKey = ""
            ))
        }
    }

    private fun decryptOutbox(log: ForwardLog, config: ForwardConfig): String {
        val parts = requireNotNull(log.encryptedBody) { "Missing encrypted outbox" }.split(":")
        return if (parts.size == 3 && parts[0] == "v2") {
            val key = requireNotNull(secureStorage) { "Secure storage unavailable" }.getOrCreateOutboxKey()
            AesEncryptionUtils.decrypt(parts[1], parts[2], key)
        } else {
            require(parts.size == 2) { "Invalid encrypted outbox" }
            // Read existing v1 records only; new records never use a shared/default key.
            val key = config.secretEncryptionKey.ifBlank { "BarPro-Outbox-Key-2026" }
            AesEncryptionUtils.decrypt(parts[0], parts[1], key)
        }
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
    suspend fun transmitPendingLog(logId: Long, fastTimeoutMs: Long? = null): TransmissionResult = transmissionMutex.withLock {
        withContext(Dispatchers.IO) {
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
        if (log.status == ForwardStatus.SUCCESS) {
            return@withContext TransmissionResult(true, log.httpStatusCode, null, null, "", false, 0)
        }
        val age = System.currentTimeMillis() - log.receivedTimestamp
        val updateStore = appContext?.let { com.example.update.UpdateStore.getInstance(it) }
        val stopReason = when {
            updateStore?.blockedByMandatoryUpdate == true -> "ارسال پیامک به دلیل نیاز به به‌روزرسانی ضروری متوقف شده است."
            !config.isMasterEnabled || !config.userConsentGiven -> "ارسال پیامک غیرفعال است."
            log.status == ForwardStatus.SKIPPED -> "این پیامک برای ارسال مجاز نیست."
            !BarProContract.isTimestampAcceptable(log.receivedTimestamp) -> "اعتبار OTP به پایان رسیده یا ساعت دستگاه نادرست است."
            log.recipientPhone.isBlank() -> "گیرنده این پیامک قدیمی ثبت نشده؛ ارسال امن ممکن نیست."
            log.recipientPhone != com.example.utils.SmsParser.normalizePhoneNumber(config.driverPhone) -> "شماره راننده از زمان دریافت پیامک تغییر کرده است."
            else -> BarProContract.configurationError(config)
        }
        if (stopReason != null) {
            updateLogStatus(logId, ForwardStatus.SKIPPED, stopReason)
            return@withContext TransmissionResult(false, null, null, stopReason, "", false, 0)
        }
        val rawMessage = try {
            decryptOutbox(log, config)
        } catch (_: Exception) {
            updateLogStatus(logId, ForwardStatus.SKIPPED, "بازکردن پیامک رمزنگاری‌شده ممکن نیست.")
            return@withContext TransmissionResult(false, null, null, "خطای رمزگشایی پیامک", "", false, 0)
        }

        val activeRules = database.filterRuleDao().getActiveRules()
        val matchedRule = activeRules.firstOrNull { it.label == log.matchedRuleLabel || it.matches(log.sender, rawMessage) }

        // Cellular Egress Guard (Fail-closed against foreign VPN leak):
        // If device has an active VPN, route the forward request through the physical
        // Iranian cellular network using CellularEgress.
        val vpnActive = appContext != null && com.example.network.CellularEgress.isVpnActive(appContext)
        val result = if (vpnActive) {
            val egressSession = com.example.network.CellularEgress.acquire(appContext!!, timeoutMs = 4000L)
            if (egressSession != null) {
                egressSession.use { session ->
                    client.forwardMessage(
                        sender = log.sender,
                        messageBody = rawMessage,
                        timestamp = log.receivedTimestamp,
                        config = config,
                        matchedRule = matchedRule,
                        simSlot = log.simSlot,
                        fastTimeoutMs = fastTimeoutMs,
                        clientOverride = session.bind(client.httpClient)
                    )
                }
            } else {
                TransmissionResult(
                    isSuccess = false,
                    httpStatusCode = null,
                    responseBody = null,
                    errorMessage = "فیلترشکن روی گوشی فعال است و امکان اتصال مستقیم به شبکه سلولار ایران میسر نشد.",
                    payloadSent = "",
                    isEncrypted = false,
                    durationMs = 0L,
                    isRetryable = true
                )
            }
        } else {
            client.forwardMessage(
                sender = log.sender,
                messageBody = rawMessage,
                timestamp = log.receivedTimestamp,
                config = config,
                matchedRule = matchedRule,
                simSlot = log.simSlot,
                fastTimeoutMs = fastTimeoutMs
            )
        }

        if (result.isSuccess) {
            ServerHealthMonitor.recordSuccess(appContext, config.endpointUrl, result.durationMs)
        } else if (appContext != null) {
            ServerHealthMonitor.recordFailure(appContext, config.endpointUrl, result.errorMessage, config)
        }

        val updatedLog = log.copy(
            forwardedTimestamp = System.currentTimeMillis(),
            status = if (result.isSuccess) ForwardStatus.SUCCESS else if (result.isRetryable) ForwardStatus.FAILED else ForwardStatus.SKIPPED,
            httpStatusCode = result.httpStatusCode,
            responseSummary = LogSanitizer.sanitize(result.responseBody),
            errorMessage = LogSanitizer.sanitize(result.errorMessage),
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
    ): ForwardLog = ingestionMutex.withLock {
        withContext(Dispatchers.IO) {
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
        val recipient = com.example.utils.SmsParser.normalizePhoneNumber(config.driverPhone)
        database.forwardLogDao().findRecentCapture(fingerprint, recipient, receivedTimestamp - DEDUP_WINDOW_MS)
            ?.let { return@withContext it }

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
        if (!config.isMasterEnabled || !config.userConsentGiven) {
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
        val smsType = com.example.utils.SmsParser.detectSmsType(messageBody, sender)
        val trackingCode = com.example.utils.SmsParser.extractTrackingCode(messageBody)
        val extractedOtp = com.example.utils.SmsParser.extractOtp(messageBody, sender)
        if (extractedOtp == null) {
            val skipped = ForwardLog(sender = sender, messageBody = LogSanitizer.sanitize(messageBody),
                receivedTimestamp = receivedTimestamp, status = ForwardStatus.SKIPPED,
                errorMessage = "این پیامک کد OTP معتبر برای بارپرو ندارد.", smsType = smsType)
            return@withContext skipped.copy(id = database.forwardLogDao().insertLog(skipped))
        }
        val outboxKey = requireNotNull(secureStorage) { "Secure storage unavailable" }.getOrCreateOutboxKey()
        val enc = AesEncryptionUtils.encrypt(messageBody, outboxKey)
        val encryptedPayload = "v2:" + enc.iv + ":" + enc.ciphertext

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
                signature = null,
                retryCount = 0,
                simSlot = simSlot,
                encryptedBody = encryptedPayload,
                recipientPhone = recipient,
                messageFingerprint = fingerprint
            )
            val id = database.forwardLogDao().insertLog(initialLog)
            initialLog.copy(id = id)
        }

        // Cache in deduplication map
        synchronized(dedupLock) {
            recentSmsLogs[fingerprint] = insertedLog
        }

        // Persist work before the fast attempt so process death cannot strand the outbox.
        val ctx = appContext
        if (ctx != null) com.example.service.SmsSyncWorker.enqueue(ctx, insertedLog.id)

        // Zero-Latency Direct Push (Fast-Path):
        // Since OTP codes have a strict 5-minute validity window, attempt immediate network transmission
        // right inside the active Coroutine / WakeLock.
        // 1. Proactively check if valid internet is available via ConnectivityManager.
        // 2. If completely offline or unvalidated, bypass socket delay and dispatch SMS Fallback in 0ms!
        // 3. If online, attempt HTTP forward with a strict Fast-Path timeout (1,500ms).
        // 4. If HTTP fails or times out, immediately relay code via SMS to the server GSM modem.
        var finalLog = insertedLog
        val codeToSend = extractedOtp
        val slotIndex = if (simSlot.contains("2")) 1 else if (simSlot.contains("1")) 0 else -1

        // Check active network capability
        val cm = ctx?.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val activeNet = cm?.activeNetwork
        val netCaps = cm?.getNetworkCapabilities(activeNet)
        val isFastOnline = netCaps != null &&
                netCaps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                netCaps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        if (!isFastOnline) {
            // IMMEDIATE OFFLINE FALLBACK (Zero socket delay)
            android.util.Log.i(TAG, "Device is offline/unvalidated. Triggering instantaneous SMS Fallback...")
            if (ctx != null) {
                if (config.enableSmsFallback && !codeToSend.isNullOrBlank() && config.fallbackServerPhoneNumber.isNotBlank()) {
                    val smsSuccess = com.example.utils.SmsRelayHelper.sendFallbackSms(
                        context = ctx,
                        destinationPhone = config.fallbackServerPhoneNumber,
                        driverId = config.driverId,
                        driverPhone = config.driverPhone,
                        code = codeToSend,
                        smsType = smsType.name,
                        simSlot = slotIndex,
                        receivedTimestamp = receivedTimestamp,
                        webhookSecret = BarProContract.token(config)
                    )
                    if (smsSuccess) {
                        android.util.Log.i(TAG, "SMS fallback handed to the device modem; delivery unconfirmed")
                    }
                }
                // Enqueue WorkManager for guaranteed HTTP outbox sync once internet reconnects
                com.example.service.SmsSyncWorker.enqueue(ctx, insertedLog.id)
            }
        } else {
            // FAST ONLINE ATTEMPT (Strict 1.5s timeout)
            try {
                val directResult = transmitPendingLog(insertedLog.id, fastTimeoutMs = 1500L)
                if (directResult.isSuccess) {
                    finalLog = database.forwardLogDao().getLogById(insertedLog.id) ?: insertedLog
                    synchronized(dedupLock) { recentSmsLogs[fingerprint] = finalLog }
                    android.util.Log.i(TAG, "Fast-path immediate delivery succeeded for Log #${insertedLog.id} in ${directResult.durationMs}ms")
                } else {
                    // HTTP failed or timed out: trigger immediate SMS Fallback Relay
                    android.util.Log.w(TAG, "Fast-path HTTP failed (${directResult.errorMessage}). Relaying emergency SMS...")
                    if (config.enableSmsFallback && !codeToSend.isNullOrBlank() && config.fallbackServerPhoneNumber.isNotBlank()) {
                        val smsSuccess = com.example.utils.SmsRelayHelper.sendFallbackSms(
                            context = ctx,
                            destinationPhone = config.fallbackServerPhoneNumber,
                            driverId = config.driverId,
                            driverPhone = config.driverPhone,
                            code = codeToSend,
                            smsType = smsType.name,
                            simSlot = slotIndex,
                            receivedTimestamp = receivedTimestamp,
                            webhookSecret = BarProContract.token(config)
                        )
                        if (smsSuccess) {
                            android.util.Log.i(TAG, "SMS fallback handed to the device modem; delivery unconfirmed")
                        }
                    }
                    com.example.service.SmsSyncWorker.enqueue(ctx, insertedLog.id)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Fast-path attempt failed (${e.message}), triggering immediate SMS Fallback...")
                if (config.enableSmsFallback && !codeToSend.isNullOrBlank() && config.fallbackServerPhoneNumber.isNotBlank()) {
                    com.example.utils.SmsRelayHelper.sendFallbackSms(
                        context = ctx,
                        destinationPhone = config.fallbackServerPhoneNumber,
                        driverId = config.driverId,
                        driverPhone = config.driverPhone,
                        code = codeToSend,
                        smsType = smsType.name,
                        simSlot = slotIndex,
                        receivedTimestamp = receivedTimestamp,
                        webhookSecret = BarProContract.token(config)
                    )
                }
                com.example.service.SmsSyncWorker.enqueue(ctx, insertedLog.id)
            }
        }

        finalLog
        }
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


        if (candidateLog == null) {
            return@withContext OtpInquiryExecutionResult(
                isSuccess = false,
                otpCode = null,
                matchedLog = null,
                transmissionResult = null,
                message = "هیچ پیامکی از شماره یا سامانه «$senderQuery» در بازه زمانی درخواستی یافت نشد."
            )
        }

        if (!config.isMasterEnabled || !config.userConsentGiven || candidateLog.status == ForwardStatus.SKIPPED ||
            candidateLog.recipientPhone != com.example.utils.SmsParser.normalizePhoneNumber(config.driverPhone) ||
            System.currentTimeMillis() - candidateLog.receivedTimestamp >= BarProContract.OTP_TTL_MS) {
            return@withContext OtpInquiryExecutionResult(false, null, candidateLog, null, "پیامک مجاز و تازه برای این گیرنده یافت نشد.")
        }
        val rawMessage = try {
            decryptOutbox(candidateLog, config)
        } catch (_: Exception) {
            return@withContext OtpInquiryExecutionResult(false, null, candidateLog, null, "رمزگشایی پیامک ممکن نیست.")
        }

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

        val vpnActive = appContext != null && com.example.network.CellularEgress.isVpnActive(appContext)
        val transmission = if (vpnActive) {
            val egressSession = com.example.network.CellularEgress.acquire(appContext!!, timeoutMs = 4000L)
            if (egressSession != null) {
                egressSession.use { session ->
                    client.sendOtpInquiryResponse(
                        sender = candidateLog.sender,
                        otpCode = otpCode,
                        requestedTimestamp = requestedTimestamp,
                        smsTimestamp = candidateLog.receivedTimestamp,
                        rawMessage = rawMessage,
                        matchedRuleLabel = candidateLog.matchedRuleLabel ?: "استعلام سرور (On-Demand)",
                        config = config,
                        clientOverride = session.bind(client.httpClient)
                    )
                }
            } else {
                client.sendOtpInquiryResponse(
                    sender = candidateLog.sender,
                    otpCode = otpCode,
                    requestedTimestamp = requestedTimestamp,
                    smsTimestamp = candidateLog.receivedTimestamp,
                    rawMessage = rawMessage,
                    matchedRuleLabel = candidateLog.matchedRuleLabel ?: "استعلام سرور (On-Demand)",
                    config = config
                )
            }
        } else {
            client.sendOtpInquiryResponse(
                sender = candidateLog.sender,
                otpCode = otpCode,
                requestedTimestamp = requestedTimestamp,
                smsTimestamp = candidateLog.receivedTimestamp,
                rawMessage = rawMessage,
                matchedRuleLabel = candidateLog.matchedRuleLabel ?: "استعلام سرور (On-Demand)",
                config = config
            )
        }

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

            if (config.enableAutoOfflineSync && pendingFailedCount > 0) {
                syncOfflinePendingLogs()
            }
        } else {
            ServerHealthMonitor.recordFailure(context, config.endpointUrl, result.errorMessage, config)
        }

        result
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
        private val ingestionMutex = Mutex()
        private val transmissionMutex = Mutex()

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
