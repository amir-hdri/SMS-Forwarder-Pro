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
import com.example.utils.SmsDispatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
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
    private val deliveryLocks = com.example.utils.KeyedMutex<Long>()
    private val configAccessLock = Mutex()
    @Volatile private var secureStorageBlocked = false

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

    val configFlow: Flow<ForwardConfig?> = database.forwardConfigDao().getConfigFlow().map {
        // Room's emission is an invalidation signal: pair current identity with current secrets.
        configAccessLock.withLock {
            database.forwardConfigDao().getConfig()?.let { restoreCheckedSecrets(it) }
        }
    }.flowOn(Dispatchers.IO)

    private fun restoreSecrets(config: ForwardConfig): ForwardConfig {
        val managed = com.example.network.ManagedTransport.configuration(config)
        val storage = secureStorage ?: return managed
        val secret = storage.getForwarderSecret().ifBlank {
            config.authHeaderValue.ifBlank { config.forwarderSecret }
        }
        val key = storage.getSecretEncryptionKey().ifBlank { config.secretEncryptionKey }
        return managed.copy(forwarderSecret = secret, authHeaderValue = secret, secretEncryptionKey = key)
    }

    private suspend fun stopForStorageFailure() {
        secureStorageBlocked = true
        // Only change stop/consent flags, never copy redacted UI defaults over fleet identity.
        try {
            database.forwardConfigDao().disableForStorageFailure()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.w(TAG, "Persisting secure-storage stop deferred: ${error.javaClass.simpleName}")
        }
        appContext?.let { context ->
            runCatching { com.example.service.HeartbeatAlarmManager.cancel(context) }
            runCatching { com.example.service.SmsForwarderService.stopService(context) }
        }
    }

    private suspend fun restoreCheckedSecrets(config: ForwardConfig): ForwardConfig {
        check(!secureStorageBlocked) { "Secure configuration requires validated recovery" }
        return try {
            restoreSecrets(config)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            stopForStorageFailure()
            throw error
        }
    }

    val serverHealthState: StateFlow<ServerHealthState> = ServerHealthMonitor.healthState

    val totalLogsCount: Flow<Int> = database.forwardLogDao().getTotalLogsCount()
    val successCount: Flow<Int> = database.forwardLogDao().getSuccessCount()
    val failedCount: Flow<Int> = database.forwardLogDao().getFailedCount()
    val skippedCount: Flow<Int> = database.forwardLogDao().getSkippedCount()
    val pendingCount: Flow<Int> = database.forwardLogDao().getPendingCount()
    val rulesCount: Flow<Int> = database.filterRuleDao().getRulesCount()

    suspend fun getConfig(): ForwardConfig = withContext(Dispatchers.IO) {
        configAccessLock.withLock { getClaimedConfig() }
    }

    /** Caller owns configAccessLock; public reads/writes must not reacquire its non-reentrant mutex. */
    private suspend fun getClaimedConfig(): ForwardConfig {
        val persisted = database.forwardConfigDao().getConfig() ?: com.example.network.ManagedTransport.initialConfiguration()
        val restored = restoreCheckedSecrets(persisted)
        // Migrate previous plaintext config fields into the Keystore-backed store.
        if (secureStorage != null && (persisted.forwarderSecret.isNotEmpty() ||
                persisted.authHeaderValue.isNotEmpty() || persisted.secretEncryptionKey.isNotEmpty())) {
            saveClaimedConfig(restored)
        }
        return restored
    }

    /** Patch the latest private profile in the same critical section as its read and secure save. */
    suspend fun updateConfig(patch: (ForwardConfig) -> ForwardConfig): ForwardConfig = withContext(Dispatchers.IO) {
        configAccessLock.withLock {
            val updated = patch(getClaimedConfig())
            saveClaimedConfig(updated)
            updated
        }
    }

    suspend fun saveConfig(config: ForwardConfig) = withContext(Dispatchers.IO) {
        configAccessLock.withLock {
            check(!secureStorageBlocked) { "Secure configuration requires validated recovery" }
            saveClaimedConfig(config)
        }
    }

    private suspend fun saveClaimedConfig(config: ForwardConfig) {
        try {
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            stopForStorageFailure()
            throw error
        }
    }

    /** Validate both reads and durable writes; recovery never restores revoked consent. */
    suspend fun retrySecureStorage(): ForwardConfig = withContext(Dispatchers.IO) { configAccessLock.withLock {
        try {
            val persisted = database.forwardConfigDao().getConfig() ?: ForwardConfig()
            val restored = restoreSecrets(persisted)
            secureStorage?.let { storage ->
                storage.saveForwarderSecret(BarProContract.token(restored))
                storage.saveSecretEncryptionKey(restored.secretEncryptionKey)
                if (persisted.forwarderSecret.isNotEmpty() || persisted.authHeaderValue.isNotEmpty() ||
                    persisted.secretEncryptionKey.isNotEmpty()) {
                    database.forwardConfigDao().insertOrUpdate(restored.copy(
                        forwarderSecret = "", authHeaderValue = "", secretEncryptionKey = ""))
                }
            }
            secureStorageBlocked = false
            restored
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            stopForStorageFailure()
            throw error
        }
    } }

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
        if (com.example.BuildConfig.APP_ROLE == "DRIVER") {
            sendDriverLiveness(context, config)
            return serverHealthState.value.copy(
                status = com.example.network.ServerHealthStatus.UNKNOWN,
                lastErrorMessage = "تأیید دریافت پیامک سلامت را در پنل بارپرو بررسی کنید."
            )
        }
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
        deviceIdentifier: String,
        allowCleartextTransport: Boolean = false,
        driverPhone: String = "",
        fallbackServerPhoneNumber: String = "",
        hubIrancellPhoneNumber: String = ""
    ): TransmissionResult {
        val result = client.testEndpoint(
            endpointUrl = url,
            authType = authType,
            authHeaderKey = authHeaderKey,
            authHeaderValue = authHeaderValue,
            isEncryptionEnabled = isEncryptionEnabled,
            secretKey = secretKey,
            deviceIdentifier = deviceIdentifier,
            allowCleartextTransport = allowCleartextTransport,
            driverPhone = driverPhone,
            fallbackServerPhoneNumber = fallbackServerPhoneNumber,
            hubIrancellPhoneNumber = hubIrancellPhoneNumber
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
    suspend fun transmitPendingLog(logId: Long, fastTimeoutMs: Long? = null): TransmissionResult {
        return if (fastTimeoutMs == null) deliveryLocks.withLock(logId) { transmitClaimedLog(logId, null) }
        else kotlinx.coroutines.withTimeoutOrNull(fastTimeoutMs.coerceIn(250, 60_000)) {
            deliveryLocks.withLock(logId) { transmitClaimedLog(logId, fastTimeoutMs) }
        } ?: TransmissionResult(false, null, null, "ارسال فوری تمام نشد؛ صف پایدار دوباره تلاش می‌کند.", "", false, fastTimeoutMs, isRetryable = true)
    }

    private suspend fun dispatchGsmRelay(
        logId: Long,
        log: ForwardLog,
        config: ForwardConfig,
        code: String,
        isFallback: Boolean = false
    ): TransmissionResult {
        val context = appContext
        if (context == null) {
            updateClaimedLogStatus(logId, ForwardStatus.SKIPPED, "این پیامک برای رله GSM راننده معتبر نیست.")
            return TransmissionResult(false, null, null, "Context در دسترس نیست", "", false, 0)
        }
        if (log.status == ForwardStatus.SMS_SENT) {
            return TransmissionResult(true, null, null, null, "", false, 0)
        }
        val waiting = log.status == ForwardStatus.SMS_DISPATCHED &&
            System.currentTimeMillis() - (log.lastRetryTimestamp ?: 0) in 0 until com.example.utils.SmsDispatch.RESULT_TIMEOUT_MS
        if (waiting) return TransmissionResult(false, null, null,
            "در انتظار نتیجه ارسال شبکه سیم‌کارت", "", false, 0, isRetryable = true)
        if (log.retryCount >= com.example.utils.SmsDispatch.MAX_ATTEMPTS) {
            database.forwardLogDao().updateLog(log.copy(errorMessage =
                "تلاش‌های محدود پایان یافت؛ نتیجه آخرین ارسال هنوز تأیید نشده است."))
            return TransmissionResult(false, null, null, "تلاش‌های GSM پایان یافت", "", false, 0)
        }
        if (log.status == ForwardStatus.SMS_DISPATCHED && log.lastRetryTimestamp != null) {
            com.example.utils.SmsDispatch.cancelAttempt(context, logId, log.lastRetryTimestamp)
        }
        val hubs = com.example.enrollment.EnrollmentContract.hubNumbers(config,
            com.example.BuildConfig.DEFAULT_HUB_PHONE_MCI, com.example.BuildConfig.DEFAULT_HUB_PHONE_IRANCELL)
        val route = com.example.utils.CarrierDetector.resolveRoute(log.recipientPhone, hubs.mci, hubs.irancell, context)
        val destinations = listOf(route.primaryNumber, route.failoverNumber).filter { it.isNotBlank() }.distinct()
        com.example.utils.DriverSmsSubscription.seedIfUnknown(context, log.recipientPhone, log.simSlot)
        var attemptLog = log
        var handedToModem = false
        repeat(minOf(destinations.size, com.example.utils.SmsDispatch.MAX_ATTEMPTS - log.retryCount)) {
            if (!handedToModem) {
                val attemptAt = maxOf(System.currentTimeMillis(), (attemptLog.lastRetryTimestamp ?: 0) + 1)
                val destination = destinations[attemptLog.retryCount % destinations.size]
                val summaryText = if (isFallback) "ارسال وب‌هوک ناموفق بود؛ در انتظار نتیجه ارسال پیامک اضطراری به هاب"
                    else "در انتظار نتیجه ارسال پیامک"
                attemptLog = attemptLog.copy(status = ForwardStatus.SMS_DISPATCHED,
                    retryCount = attemptLog.retryCount + 1, lastRetryTimestamp = attemptAt,
                    responseSummary = summaryText, errorMessage = null)
                database.forwardLogDao().updateLog(attemptLog)
                handedToModem = com.example.utils.SmsRelayHelper.sendFallbackSms(
                    context = context, destinationPhone = destination, driverId = config.driverId,
                    driverPhone = log.recipientPhone, code = code, receivedTimestamp = log.receivedTimestamp,
                    webhookSecret = BarProContract.token(config),
                    simSlot = Regex("SIM (\\d+)").find(log.simSlot)?.groupValues?.get(1)?.toIntOrNull()?.minus(1) ?: -1,
                    subscriptionId = com.example.utils.DriverSmsSubscription.id(log.simSlot),
                    sentIntent = com.example.utils.SmsDispatch.resultIntent(context, logId, attemptAt, false),
                    deliveryIntent = com.example.utils.SmsDispatch.resultIntent(context, logId, attemptAt, true))
                if (!handedToModem) {
                    com.example.utils.SmsDispatch.cancelAttempt(context, logId, attemptAt)
                    attemptLog = attemptLog.copy(status = ForwardStatus.FAILED,
                        errorMessage = "ارسال GSM انجام نشد؛ پوشش و شارژ سیم‌کارت را بررسی کنید.")
                    database.forwardLogDao().updateLog(attemptLog)
                }
            }
        }
        com.example.service.SmsSyncWorker.enqueue(context, logId,
            if (handedToModem) com.example.utils.SmsDispatch.RESULT_TIMEOUT_MS else 0)
        return TransmissionResult(handedToModem, null, null,
            if (handedToModem) null else "ارسال GSM انجام نشد", "", false, 0,
            isRetryable = attemptLog.retryCount < com.example.utils.SmsDispatch.MAX_ATTEMPTS)
    }

    private suspend fun transmitClaimedLog(logId: Long, fastTimeoutMs: Long?): TransmissionResult {
        return withContext(Dispatchers.IO) {
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
        val earlyStopReason = when {
            updateStore?.blockedByMandatoryUpdate == true -> "ارسال پیامک به دلیل نیاز به به‌روزرسانی ضروری متوقف شده است."
            !config.isMasterEnabled || !config.userConsentGiven -> "ارسال پیامک غیرفعال است."
            appContext != null && !com.example.utils.PermissionHelper.areForwardingPermissionsGranted(appContext) -> "مجوزهای لازم پیامک داده نشده است."
            log.status == ForwardStatus.SKIPPED -> "این پیامک برای ارسال مجاز نیست."
            !BarProContract.isTimestampAcceptable(log.receivedTimestamp) -> "اعتبار OTP به پایان رسیده یا ساعت دستگاه نادرست است."
            log.recipientPhone.isBlank() -> "گیرنده این پیامک قدیمی ثبت نشده؛ ارسال امن ممکن نیست."
            else -> null
        }
        if (earlyStopReason != null) {
            updateClaimedLogStatus(logId, ForwardStatus.SKIPPED, earlyStopReason)
            return@withContext TransmissionResult(false, null, null, earlyStopReason, "", false, 0)
        }

        val rawMessage = try {
            decryptOutbox(log, config)
        } catch (_: Exception) {
            updateClaimedLogStatus(logId, ForwardStatus.SKIPPED, "بازکردن پیامک رمزنگاری‌شده ممکن نیست.")
            return@withContext TransmissionResult(false, null, null, "خطای رمزگشایی پیامک", "", false, 0)
        }

        val relayEnvelope = com.example.utils.SmsFallbackEnvelope.parse(rawMessage)
        val isRelay = relayEnvelope != null
        val isHubApp = com.example.BuildConfig.APP_ROLE == "HUB"

        val recipientStopReason = when {
            !isRelay && !isHubApp && log.recipientPhone != com.example.utils.SmsParser.normalizePhoneNumber(config.driverPhone) -> "شماره راننده از زمان دریافت پیامک تغییر کرده است."
            else -> BarProContract.configurationError(config, requireRecipient = !isRelay && !isHubApp)
        }
        if (recipientStopReason != null) {
            updateClaimedLogStatus(logId, ForwardStatus.SKIPPED, recipientStopReason)
            return@withContext TransmissionResult(false, null, null, recipientStopReason, "", false, 0)
        }

        val activeRules = database.filterRuleDao().getActiveRules()
        val matchedRule = activeRules.firstOrNull { it.label == log.matchedRuleLabel || it.matches(log.sender, rawMessage) }

        // 1. Cellular Health Check or In-Flight/Sent GSM packets: Handled by GSM relay
        val isGsmPacket = com.example.BuildConfig.APP_ROLE == "DRIVER" &&
            (rawMessage in setOf("HEALTH_CHECK", "TEST") ||
             log.sender == "HEARTBEAT" ||
             log.status in setOf(ForwardStatus.SMS_DISPATCHED, ForwardStatus.SMS_SENT))

        if (isGsmPacket) {
            val code = if (rawMessage in setOf("HEALTH_CHECK", "TEST")) rawMessage
                else com.example.utils.SmsParser.extractOtp(rawMessage, log.sender) ?: rawMessage
            return@withContext dispatchGsmRelay(logId, log, config, code, isFallback = false)
        }

        // 2. HTTP Forwarding (with X-Webhook-Token header support):
        val token = BarProContract.token(config)
        val driverHttpClient = client.httpClient.newBuilder().apply {
            interceptors().add(0, okhttp3.Interceptor { chain ->
                val orig = chain.request()
                val req = orig.newBuilder().header("X-Webhook-Token", token).build()
                chain.proceed(req)
            })
        }.build()

        // Cellular Egress Guard (Fail-closed against foreign VPN leak):
        // If device has an active VPN, route the forward request through the physical
        // Iranian cellular network using CellularEgress.
        val vpnActive = appContext != null && com.example.network.CellularEgress.isVpnActive(appContext)
        val result = try {
            if (vpnActive) {
                val egressSession = com.example.network.CellularEgress.acquire(appContext!!, timeoutMs = 4000L)
                if (egressSession != null) {
                    egressSession.use { session ->
                        if (isRelay) {
                            client.relayGatewaySms(
                                sender = log.sender,
                                envelopeText = rawMessage,
                                config = config,
                                fastTimeoutMs = fastTimeoutMs,
                                clientOverride = session.bind(driverHttpClient)
                            )
                        } else {
                            client.forwardMessage(
                                sender = log.sender,
                                messageBody = rawMessage,
                                timestamp = log.receivedTimestamp,
                                config = config,
                                matchedRule = matchedRule,
                                simSlot = log.simSlot,
                                fastTimeoutMs = fastTimeoutMs,
                                clientOverride = session.bind(driverHttpClient)
                            )
                        }
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
                if (isRelay) {
                    client.relayGatewaySms(
                        sender = log.sender,
                        envelopeText = rawMessage,
                        config = config,
                        fastTimeoutMs = fastTimeoutMs,
                        clientOverride = driverHttpClient
                    )
                } else {
                    client.forwardMessage(
                        sender = log.sender,
                        messageBody = rawMessage,
                        timestamp = log.receivedTimestamp,
                        config = config,
                        matchedRule = matchedRule,
                        simSlot = log.simSlot,
                        fastTimeoutMs = fastTimeoutMs,
                        clientOverride = driverHttpClient
                    )
                }
            }
        } catch (t: Throwable) {
            TransmissionResult(
                isSuccess = false,
                httpStatusCode = null,
                responseBody = null,
                errorMessage = t.message ?: "Network error",
                payloadSent = "",
                isEncrypted = false,
                durationMs = 0L,
                isRetryable = true
            )
        }

        // 3. Cellular SMS Relay Fallback for Drivers if HTTP fails
        if (!result.isSuccess && com.example.BuildConfig.APP_ROLE == "DRIVER" &&
            (config.primarySmsRelayEnabled || config.enableSmsFallback)) {
            val otpCode = com.example.utils.SmsParser.extractOtp(rawMessage, log.sender)
            if (otpCode != null && appContext != null &&
                com.example.utils.PermissionHelper.isSendSmsGranted(appContext)) {
                val fallbackResult = dispatchGsmRelay(logId, log, config, otpCode, isFallback = true)
                if (fallbackResult.isSuccess) {
                    return@withContext fallbackResult
                }
            }
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

    suspend fun handleSmsDispatchResult(
        logId: Long, attemptAt: Long, resultCode: Int, delivered: Boolean,
        deliveryReport: SmsDispatch.DeliveryReport? = null
    ): Boolean {
        if (delivered && deliveryReport == null) return false
        var retry = false
        var retryImmediately = false
        var revokeAttempt = false
        val handled = deliveryLocks.withLock(logId) {
            withContext(Dispatchers.IO) {
                val log = database.forwardLogDao().getLogById(logId) ?: return@withContext false
                if (log.lastRetryTimestamp != attemptAt ||
                    log.status !in setOf(ForwardStatus.SMS_DISPATCHED, ForwardStatus.SMS_SENT) ||
                    (!delivered && log.status == ForwardStatus.SMS_SENT)) return@withContext false
                val expired = !BarProContract.isTimestampAcceptable(log.receivedTimestamp)
                val failed = if (delivered) deliveryReport == SmsDispatch.DeliveryReport.FAILED
                    else resultCode != android.app.Activity.RESULT_OK
                val status = when {
                    expired -> ForwardStatus.SKIPPED
                    failed -> ForwardStatus.FAILED
                    delivered && deliveryReport == SmsDispatch.DeliveryReport.DELIVERED -> ForwardStatus.SUCCESS
                    else -> ForwardStatus.SMS_SENT
                }
                val summary = when {
                    expired || failed -> null
                    status == ForwardStatus.SUCCESS -> "شبکه تحویل پیامک به گوشی هاب را تأیید کرد؛ ثبت سرور را در پنل بررسی کنید."
                    deliveryReport == SmsDispatch.DeliveryReport.PENDING -> "مرکز پیامک در حال تلاش برای تحویل به هاب است؛ رسید نهایی هنوز نرسیده است."
                    deliveryReport == SmsDispatch.DeliveryReport.UNCONFIRMED -> "وضعیت شبکه دریافت توسط هاب را تأیید نمی‌کند؛ ثبت سرور را در پنل بررسی کنید."
                    else -> "شبکه ارسال پیامک را تأیید کرد؛ در انتظار رسید تحویل هاب."
                }
                val error = when {
                    expired -> "مهلت کد پایان یافته است."
                    failed && delivered -> "شبکه تحویل پیامک به هاب را ناموفق اعلام کرد."
                    failed -> SmsDispatch.errorMessage(resultCode)
                    else -> null
                }
                val recorded = database.forwardLogDao().recordSmsResult(logId, attemptAt, status,
                    System.currentTimeMillis(), summary, error) == 1
                if (recorded && !failed && !expired && log.sender == "HEARTBEAT") {
                    appContext?.getSharedPreferences("barpro_driver_health", Context.MODE_PRIVATE)?.edit()
                        ?.putLong("last_modem_dispatch_ms", System.currentTimeMillis())?.apply()
                }
                revokeAttempt = recorded && (expired || failed || status == ForwardStatus.SUCCESS)
                retry = recorded && failed && !expired && log.retryCount < com.example.utils.SmsDispatch.MAX_ATTEMPTS
                retryImmediately = retry && !com.example.utils.SmsDispatch.isNetworkUnavailable(resultCode) &&
                    (delivered || log.retryCount == 1)
                recorded
            }
        }
        if (revokeAttempt) {
            appContext?.let { com.example.utils.SmsDispatch.cancelAttempt(it, logId, attemptAt) }
        }
        if (retry) {
            // Origin-wide loss of radio/service must wait for recovery, not burn every attempt.
            if (retryImmediately) transmitPendingLog(logId)
            appContext?.let { com.example.service.SmsSyncWorker.enqueue(it, logId, initialDelayMs = 10_000) }
        }
        return handled
    }

    /** Generic recovery errors may only affect work that has not entered the modem. */
    suspend fun updateLogStatus(logId: Long, status: ForwardStatus, errorMessage: String? = null) = deliveryLocks.withLock(logId) {
        withContext(Dispatchers.IO) {
            val log = database.forwardLogDao().getLogById(logId) ?: return@withContext
            if (log.status in setOf(ForwardStatus.PENDING, ForwardStatus.FAILED)) {
                updateClaimedLogStatus(logId, status, errorMessage)
            }
        }
    }

    /** Caller owns this log's delivery lock; expiry cannot race an SMS callback. */
    private suspend fun updateClaimedLogStatus(logId: Long, status: ForwardStatus, errorMessage: String? = null) = withContext(Dispatchers.IO) {
        val log = database.forwardLogDao().getLogById(logId) ?: return@withContext
        val updated = log.copy(
            status = status,
            errorMessage = errorMessage ?: log.errorMessage
        )
        database.forwardLogDao().updateLog(updated)
        if (status == ForwardStatus.SKIPPED) log.lastRetryTimestamp?.let { attempt ->
            appContext?.let { SmsDispatch.cancelAttempt(it, logId, attempt) }
        }
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
    private data class CapturedSms(val log: ForwardLog, val created: Boolean)

    suspend fun processIncomingSms(
        sender: String, messageBody: String,
        receivedTimestamp: Long = System.currentTimeMillis(), simSlot: String = "SIM 1"
    ): ForwardLog = withContext(Dispatchers.IO) {
        val captured = captureIncomingSms(sender, messageBody, receivedTimestamp, simSlot)
        if (!captured.created) return@withContext captured.log
        if (com.example.BuildConfig.APP_ROLE == "DRIVER") appContext?.let {
            com.example.utils.DriverSmsSubscription.remember(it, captured.log.recipientPhone, simSlot)
        }
        // Durable recovery precedes the fast attempt, but starts after it normally completes.
        appContext?.let { com.example.service.SmsSyncWorker.enqueue(it, captured.log.id, initialDelayMs = 5_000) }
        try {
            transmitPendingLog(captured.log.id, fastTimeoutMs = 3_000L)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            updateLogStatus(captured.log.id, ForwardStatus.FAILED, "ارسال در صف پایدار باقی ماند؛ دوباره تلاش می‌شود.")
        }
        val result = database.forwardLogDao().getLogById(captured.log.id) ?: captured.log
        synchronized(dedupLock) { recentSmsLogs[captured.log.messageFingerprint] = result }
        result
    }

    private suspend fun captureIncomingSms(
        sender: String,
        messageBody: String,
        receivedTimestamp: Long = System.currentTimeMillis(),
        simSlot: String = "SIM 1"
    ): CapturedSms = ingestionMutex.withLock {
        withContext(Dispatchers.IO) {
        // Notification listeners and other captures are not carrier proof. Never enqueue activation as OTP.
        if (messageBody.startsWith(com.example.enrollment.BootstrapSms.PREFIX)) {
            return@withContext CapturedSms(ForwardLog(sender = sender, messageBody = "[SIM activation]",
                receivedTimestamp = receivedTimestamp, status = ForwardStatus.SKIPPED,
                errorMessage = "تأیید سیم‌کارت از مسیر مستقل دریافت پیامک انجام می‌شود."), false)
        }
        // Driver activation grants RECEIVE_SMS. Notifications lack subscription
        // identity and must not win deduplication over the authoritative receiver.
        if (com.example.BuildConfig.APP_ROLE == "DRIVER" && simSlot == "Notification Listener") {
            return@withContext CapturedSms(ForwardLog(sender = sender, messageBody = LogSanitizer.sanitize(messageBody),
                receivedTimestamp = receivedTimestamp, status = ForwardStatus.SKIPPED,
                errorMessage = "در انتظار دریافت مستقیم پیامک و شناسه سیم‌کارت"), false)
        }
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
                    return@withContext CapturedSms(cached, false)
                }
            }
            recentSmsTimestamps[fingerprint] = now
        }

        val config = getConfig()
        val relayEnvelope = com.example.utils.SmsFallbackEnvelope.parse(messageBody)
        val isRelay = relayEnvelope != null

        // A structurally valid envelope whose HMAC does not verify is a forgery attempt, not a
        // delivery. Drop it here so it never occupies an outbox slot or a network round-trip; the
        // server performs the same check authoritatively with hmac.compare_digest.
        if (relayEnvelope != null) {
            val relaySecret = BarProContract.token(config)
            if (!com.example.utils.SmsFallbackEnvelope.verifyForHub(relayEnvelope, relaySecret)
            ) {
                val log = ForwardLog(
                    sender = sender,
                    messageBody = LogSanitizer.sanitize(messageBody),
                    receivedTimestamp = receivedTimestamp,
                    status = ForwardStatus.SKIPPED,
                    errorMessage = "امضای پاکت BP1 معتبر نیست؛ پیامک رله نادیده گرفته شد.",
                    endpointUrl = config.endpointUrl,
                    driverId = config.driverId,
                    smsType = com.example.data.model.SmsType.OTHER,
                    simSlot = simSlot,
                    otpCode = null
                )
                val id = database.forwardLogDao().insertLog(log)
                val resultLog = log.copy(id = id)
                synchronized(dedupLock) { recentSmsLogs[fingerprint] = resultLog }
                android.util.Log.w(TAG, "Rejected BP1 envelope with invalid HMAC from $sender")
                return@withContext CapturedSms(resultLog, false)
            }
        }

        val recipient = if (isRelay) relayEnvelope!!.phone else com.example.utils.SmsParser.normalizePhoneNumber(config.driverPhone)
        database.forwardLogDao().findRecentCapture(fingerprint, recipient, receivedTimestamp - DEDUP_WINDOW_MS)
            ?.let { return@withContext CapturedSms(it, false) }

        // 0. If filterUtcmsOnly is enabled, check if SMS is UTCMS/BarPro related (or valid signed relay envelope)
        if (config.filterUtcmsOnly && !isRelay && !com.example.utils.SmsParser.isUtcmsSms(sender, messageBody)) {
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
            return@withContext CapturedSms(resultLog, false)
        }

        // 1. Check if master toggle is enabled
        if (!config.isMasterEnabled || !config.userConsentGiven ||
            (appContext != null && !com.example.utils.PermissionHelper.areForwardingPermissionsGranted(appContext))) {
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
            return@withContext CapturedSms(resultLog, false)
        }

        // 2. Check filter rules if in SPECIFIC_RULES_ONLY mode
        var matchedRule: FilterRule? = null
        if (config.filterMode == ForwardFilterMode.SPECIFIC_RULES_ONLY && !isRelay) {
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
                return@withContext CapturedSms(resultLog, false)
            }
        }

        val ruleLabel = if (isRelay) "رله درگاه هاب (Relay Gateway)" else (matchedRule?.label ?: if (config.filterMode == ForwardFilterMode.ALL_MESSAGES) "تمام پیامک‌ها (All Messages)" else "قانون اختصاصی")
        val smsType = if (isRelay) com.example.data.model.SmsType.UTCMS_OTP else com.example.utils.SmsParser.detectSmsType(messageBody, sender)
        val trackingCode = if (isRelay) null else com.example.utils.SmsParser.extractTrackingCode(messageBody)
        val extractedOtp = if (isRelay) relayEnvelope?.code else com.example.utils.SmsParser.extractOtp(messageBody, sender)
        if (extractedOtp == null) {
            val skipped = ForwardLog(sender = sender, messageBody = LogSanitizer.sanitize(messageBody),
                receivedTimestamp = receivedTimestamp, status = ForwardStatus.SKIPPED,
                errorMessage = "این پیامک کد OTP معتبر برای بارپرو ندارد.", smsType = smsType)
            return@withContext CapturedSms(skipped.copy(id = database.forwardLogDao().insertLog(skipped)), false)
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

        CapturedSms(insertedLog, true)
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

        if (com.example.BuildConfig.APP_ROLE == "DRIVER") {
            val handedToModem = sendDriverLiveness(context, config)
            return@withContext com.example.network.HeartbeatResult(
                isSuccess = false, httpCode = null, pendingCommand = null, durationMs = 0L,
                errorMessage = if (handedToModem) "پیامک سلامت تحویل مودم شد؛ دریافت سرور تأیید نشده است."
                    else "پیامک سلامت ارسال نشد؛ مجوزها و تنظیمات هاب را بررسی کنید."
            )
        }
        val deviceStatus = com.example.network.DeviceStatusHelper.getDeviceStatus(context)
        val pendingFailedCount = database.forwardLogDao().getFailedLogsCountDirect()

        val result = client.sendHeartbeat(config, deviceStatus, pendingFailedCount)
        if (result.isSuccess) {
            ServerHealthMonitor.recordSuccess(context, config.endpointUrl, result.durationMs)

            if (config.enableAutoOfflineSync && pendingFailedCount > 0) {
                com.example.service.SmsSyncWorker.enqueueBatchSync(context)
            }
        } else {
            ServerHealthMonitor.recordFailure(context, config.endpointUrl, result.errorMessage, config)
        }

        result
    }

    private val driverHealthMutex = Mutex()
    private suspend fun sendDriverLiveness(context: Context, config: ForwardConfig): Boolean = driverHealthMutex.withLock {
        if (!config.isMasterEnabled || !config.userConsentGiven ||
            !com.example.utils.PermissionHelper.areForwardingPermissionsGranted(context) ||
            BarProContract.configurationError(config) != null) return@withLock false
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences("barpro_driver_health", Context.MODE_PRIVATE)
        val previous = prefs.getLong("last_modem_dispatch_ms", 0)
        val minimum = config.healthCheckIntervalMinutes.coerceIn(1, 60) * 60_000L - 30_000L
        if (now - previous in 0 until minimum) return@withLock false
        val phone = com.example.utils.SmsParser.normalizePhoneNumber(config.driverPhone)
        val existing = database.forwardLogDao().getRecentDriverHeartbeat(phone, now - BarProContract.OTP_TTL_MS)
        if (existing != null && existing.status in setOf(ForwardStatus.PENDING, ForwardStatus.FAILED, ForwardStatus.SMS_DISPATCHED)) {
            return@withLock transmitPendingLog(existing.id).isSuccess
        }
        val enc = AesEncryptionUtils.encrypt("HEALTH_CHECK", requireNotNull(secureStorage).getOrCreateOutboxKey())
        val subscription = com.example.utils.DriverSmsSubscription.known(context, phone)
        val log = ForwardLog(sender = "HEARTBEAT", messageBody = "پیامک سلامت راننده", receivedTimestamp = now,
            recipientPhone = phone, matchedRuleLabel = "سلامت راننده", simSlot = if (subscription >= 0) "SIM (Sub #$subscription)" else "SIM -1",
            encryptedBody = "v2:${enc.iv}:${enc.ciphertext}",
            messageFingerprint = com.example.utils.SmsParser.computeFingerprint("HEARTBEAT", "$phone:$now"))
        val id = database.forwardLogDao().insertLog(log)
        transmitPendingLog(id).isSuccess
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
