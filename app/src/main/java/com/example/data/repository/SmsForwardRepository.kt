package com.example.data.repository

import android.content.Context
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
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
    val allRules: Flow<List<FilterRule>> = database.filterRuleDao().getAllRules()
    val allLogs: Flow<List<ForwardLog>> = database.forwardLogDao().getAllLogs()
    val configFlow: Flow<ForwardConfig?> = database.forwardConfigDao().getConfigFlow()
    val serverHealthState: StateFlow<ServerHealthState> = ServerHealthMonitor.healthState

    val totalLogsCount: Flow<Int> = database.forwardLogDao().getTotalLogsCount()
    val successCount: Flow<Int> = database.forwardLogDao().getSuccessCount()
    val failedCount: Flow<Int> = database.forwardLogDao().getFailedCount()
    val skippedCount: Flow<Int> = database.forwardLogDao().getSkippedCount()
    val rulesCount: Flow<Int> = database.filterRuleDao().getRulesCount()

    suspend fun getConfig(): ForwardConfig {
        return database.forwardConfigDao().getConfig() ?: ForwardConfig()
    }

    suspend fun saveConfig(config: ForwardConfig) = withContext(Dispatchers.IO) {
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

    suspend fun retryForwardLog(log: ForwardLog): ForwardLog = withContext(Dispatchers.IO) {
        val config = getConfig()
        val activeRules = database.filterRuleDao().getActiveRules()
        val matchedRule = activeRules.firstOrNull { it.label == log.matchedRuleLabel || it.matches(log.sender, log.messageBody) }

        val result = client.forwardMessage(
            sender = log.sender,
            messageBody = log.messageBody,
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
            payloadPreview = result.payloadSent,
            endpointUrl = config.endpointUrl,
            durationMs = result.durationMs,
            driverId = config.driverId,
            smsType = result.smsType,
            trackingCode = result.trackingCode ?: log.trackingCode,
            otpCode = result.otpCode ?: log.otpCode,
            signature = result.signature ?: log.signature,
            retryCount = log.retryCount + 1,
            lastRetryTimestamp = System.currentTimeMillis()
        )
        database.forwardLogDao().updateLog(updatedLog)
        updatedLog
    }

    suspend fun processIncomingSms(
        sender: String,
        messageBody: String,
        receivedTimestamp: Long = System.currentTimeMillis(),
        simSlot: String = "SIM 1"
    ): ForwardLog = withContext(Dispatchers.IO) {
        val config = getConfig()

        // 0. If filterUtcmsOnly is enabled, check if SMS is UTCMS/BarPro related
        if (config.filterUtcmsOnly && !com.example.utils.SmsParser.isUtcmsSms(sender, messageBody)) {
            val log = ForwardLog(
                sender = sender,
                messageBody = messageBody,
                receivedTimestamp = receivedTimestamp,
                status = ForwardStatus.SKIPPED,
                errorMessage = "پیامک فیلتر شد: فیلتر هوشمند فقط پیامک‌های سامانه بارنامه و بارپرو را مجاز می‌داند",
                endpointUrl = config.endpointUrl,
                driverId = config.driverId,
                smsType = com.example.data.model.SmsType.OTHER,
                simSlot = simSlot
            )
            val id = database.forwardLogDao().insertLog(log)
            return@withContext log.copy(id = id)
        }

        // 1. Check if master toggle is enabled
        if (!config.isMasterEnabled) {
            val log = ForwardLog(
                sender = sender,
                messageBody = messageBody,
                receivedTimestamp = receivedTimestamp,
                status = ForwardStatus.SKIPPED,
                errorMessage = "سرویس فوروارد در تنظیمات غیرفعال است",
                endpointUrl = config.endpointUrl,
                driverId = config.driverId,
                smsType = com.example.utils.SmsParser.detectSmsType(messageBody),
                simSlot = simSlot
            )
            val id = database.forwardLogDao().insertLog(log)
            return@withContext log.copy(id = id)
        }

        // 2. Check filter rules if in SPECIFIC_RULES_ONLY mode
        var matchedRule: FilterRule? = null
        if (config.filterMode == ForwardFilterMode.SPECIFIC_RULES_ONLY) {
            val activeRules = database.filterRuleDao().getActiveRules()
            matchedRule = activeRules.firstOrNull { it.matches(sender, messageBody) }

            if (matchedRule == null) {
                // Not in filter list -> Skip
                val log = ForwardLog(
                    sender = sender,
                    messageBody = messageBody,
                    receivedTimestamp = receivedTimestamp,
                    status = ForwardStatus.SKIPPED,
                    errorMessage = "شماره فرستنده $sender با هیچ‌یک از قوانین فعال همخوانی ندارد",
                    endpointUrl = config.endpointUrl,
                    driverId = config.driverId,
                    smsType = com.example.utils.SmsParser.detectSmsType(messageBody),
                    simSlot = simSlot
                )
                val id = database.forwardLogDao().insertLog(log)
                return@withContext log.copy(id = id)
            }
        }

        // 3. Matched or ALL mode -> Forward to server
        val result = client.forwardMessage(
            sender = sender,
            messageBody = messageBody,
            timestamp = receivedTimestamp,
            config = config,
            matchedRule = matchedRule,
            simSlot = simSlot
        )

        if (result.isSuccess) {
            ServerHealthMonitor.recordSuccess(appContext, config.endpointUrl, result.durationMs)
        } else if (appContext != null) {
            ServerHealthMonitor.recordFailure(appContext, config.endpointUrl, result.errorMessage, config)
        }

        val log = ForwardLog(
            sender = sender,
            messageBody = messageBody,
            receivedTimestamp = receivedTimestamp,
            forwardedTimestamp = System.currentTimeMillis(),
            status = if (result.isSuccess) ForwardStatus.SUCCESS else ForwardStatus.FAILED,
            httpStatusCode = result.httpStatusCode,
            responseSummary = result.responseBody,
            errorMessage = result.errorMessage,
            matchedRuleLabel = matchedRule?.label ?: if (config.filterMode == ForwardFilterMode.ALL_MESSAGES) "تمام پیامک‌ها (All Messages)" else "قانون اختصاصی",
            isEncrypted = result.isEncrypted,
            payloadPreview = result.payloadSent,
            endpointUrl = config.endpointUrl,
            durationMs = result.durationMs,
            driverId = config.driverId,
            smsType = result.smsType,
            trackingCode = result.trackingCode,
            otpCode = result.otpCode,
            signature = result.signature,
            retryCount = 0,
            simSlot = simSlot
        )

        val id = database.forwardLogDao().insertLog(log)
        val savedLog = log.copy(id = id)

        // If transmission failed and WorkManager sync is enabled, enqueue for reliable background retry
        if (!result.isSuccess && config.enableWorkManagerSync && appContext != null) {
            com.example.service.SmsSyncWorker.enqueue(appContext, id)
        }

        savedLog
    }

    suspend fun queryAndSendOtpForServer(
        senderQuery: String,
        requestedTimestamp: Long = System.currentTimeMillis(),
        toleranceMinutes: Int = 15
    ): OtpInquiryExecutionResult = withContext(Dispatchers.IO) {
        val config = getConfig()
        val minTime = requestedTimestamp - (toleranceMinutes * 60 * 1000L)

        // 1. Try finding closest log for this sender
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

        // 2. Extract OTP
        val otpResult = com.example.otp.OtpExtractor.extractOtp(
            sender = candidateLog.sender,
            rawMessage = candidateLog.messageBody,
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

        // 3. Send OTP Response to server
        val transmission = client.sendOtpInquiryResponse(
            sender = candidateLog.sender,
            otpCode = otpCode,
            requestedTimestamp = requestedTimestamp,
            smsTimestamp = candidateLog.receivedTimestamp,
            rawMessage = candidateLog.messageBody,
            matchedRuleLabel = candidateLog.matchedRuleLabel ?: "استعلام سرور (On-Demand)",
            config = config
        )

        if (transmission.isSuccess) {
            ServerHealthMonitor.recordSuccess(appContext, config.endpointUrl, transmission.durationMs)
        } else if (appContext != null) {
            ServerHealthMonitor.recordFailure(appContext, config.endpointUrl, transmission.errorMessage, config)
        }

        // Record a log for this OTP response
        val otpLog = ForwardLog(
            sender = candidateLog.sender,
            messageBody = "[پاسخ استعلام OTP: $otpCode] ${candidateLog.messageBody}",
            receivedTimestamp = candidateLog.receivedTimestamp,
            forwardedTimestamp = System.currentTimeMillis(),
            status = if (transmission.isSuccess) ForwardStatus.SUCCESS else ForwardStatus.FAILED,
            httpStatusCode = transmission.httpStatusCode,
            responseSummary = transmission.responseBody,
            errorMessage = transmission.errorMessage,
            matchedRuleLabel = "پاسخ استعلام OTP سرور",
            isEncrypted = transmission.isEncrypted,
            payloadPreview = transmission.payloadSent,
            endpointUrl = config.endpointUrl,
            durationMs = transmission.durationMs
        )
        database.forwardLogDao().insertLog(otpLog)

        OtpInquiryExecutionResult(
            isSuccess = transmission.isSuccess,
            otpCode = otpCode,
            matchedLog = candidateLog,
            transmissionResult = transmission,
            message = if (transmission.isSuccess) "کد $otpCode با موفقیت بر اساس استعلام زمانی به سرور ارسال شد." else "کد استخراج شد اما ارسال به سرور با خطا مواجه گردید: ${transmission.errorMessage}"
        )
    }

    suspend fun syncOfflinePendingLogs(): Int = withContext(Dispatchers.IO) {
        val config = getConfig()
        if (!config.isMasterEnabled || config.endpointUrl.isBlank()) return@withContext 0

        val failedLogs = database.forwardLogDao().getFailedLogs(limit = 20)
        if (failedLogs.isEmpty()) return@withContext 0

        var successCount = 0
        for (log in failedLogs) {
            val updated = retryForwardLog(log)
            if (updated.status == ForwardStatus.SUCCESS) {
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

            // If there's an incoming command from server, execute it!
            result.pendingCommand?.let { cmd ->
                handleServerCommand(cmd, config)
            }

            // If there are pending failed logs and auto sync is enabled, sync them
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
                    val otp = com.example.otp.OtpExtractor.extractOtp(candidateLog.messageBody)
                    replyData.put("found", true)
                    replyData.put("otp_code", otp ?: "")
                    replyData.put("sender", candidateLog.sender)
                    replyData.put("received_timestamp", candidateLog.receivedTimestamp)
                    replyData.put("raw_message", candidateLog.messageBody)
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

    companion object {
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
