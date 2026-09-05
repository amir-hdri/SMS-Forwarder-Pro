package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.SmsForwarderApp
import com.example.crypto.CryptoEngine
import com.example.data.model.AuthType
import com.example.data.model.FilterRule
import com.example.data.model.ForwardConfig
import com.example.data.model.ForwardFilterMode
import com.example.data.model.ForwardLog
import com.example.data.model.ForwardStatus
import com.example.data.model.MatchType
import com.example.data.repository.SmsForwardRepository
import com.example.network.TransmissionResult
import com.example.service.SmsForwarderService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class EndpointTestState(
    val isLoading: Boolean = false,
    val result: TransmissionResult? = null
)

data class EncryptionSandboxState(
    val inputPlaintext: String = "Your verification OTP is 739201. Do not share.",
    val encryptedCiphertext: String = "",
    val iv: String = "",
    val signature: String = "",
    val decryptedText: String = "",
    val error: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: SmsForwardRepository =
        (application as? SmsForwarderApp)?.repository
            ?: SmsForwardRepository.getInstance(application)

    val rules: StateFlow<List<FilterRule>> = repository.allRules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val config: StateFlow<ForwardConfig> = repository.configFlow
        .combine(MutableStateFlow(ForwardConfig())) { cfg, fallback -> cfg ?: fallback }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ForwardConfig())

    // Log Filtering & Search
    val selectedLogFilter = MutableStateFlow<ForwardStatus?>(null)
    val selectedSmsTypeFilter = MutableStateFlow<com.example.data.model.SmsType?>(null)
    val logSearchQuery = MutableStateFlow("")

    val filteredLogs: StateFlow<List<ForwardLog>> = combine(
        repository.allLogs,
        selectedLogFilter,
        selectedSmsTypeFilter,
        logSearchQuery
    ) { logs, statusFilter, smsTypeFilter, query ->
        logs.filter { log ->
            val matchesStatus = (statusFilter == null || log.status == statusFilter)
            val matchesSmsType = (smsTypeFilter == null || log.smsType == smsTypeFilter)
            val matchesQuery = query.isBlank() ||
                    log.sender.contains(query, ignoreCase = true) ||
                    log.messageBody.contains(query, ignoreCase = true) ||
                    (log.trackingCode?.contains(query, ignoreCase = true) == true) ||
                    (log.otpCode?.contains(query, ignoreCase = true) == true) ||
                    (log.driverId.contains(query, ignoreCase = true)) ||
                    (log.matchedRuleLabel?.contains(query, ignoreCase = true) == true)
            matchesStatus && matchesSmsType && matchesQuery
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Security & WorkManager
    val securityReport = MutableStateFlow(com.example.utils.SecurityUtils.getSecurityReport(application))

    fun refreshSecurityReport() {
        securityReport.value = com.example.utils.SecurityUtils.getSecurityReport(getApplication())
    }

    fun triggerWorkManagerSync() {
        com.example.service.SmsSyncWorker.enqueueBatchSync(getApplication())
    }

    // Metrics & Counts
    val totalLogsCount: StateFlow<Int> = repository.totalLogsCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val successCount: StateFlow<Int> = repository.successCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val failedCount: StateFlow<Int> = repository.failedCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val skippedCount: StateFlow<Int> = repository.skippedCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val rulesCount: StateFlow<Int> = repository.rulesCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Current Navigation Tab
    val currentTab = MutableStateFlow(com.example.NavigationTab.DASHBOARD)

    fun selectTab(tab: com.example.NavigationTab) {
        currentTab.value = tab
    }

    // Server Health Monitoring
    val serverHealthState: StateFlow<com.example.network.ServerHealthState> = repository.serverHealthState

    // Test state
    private val _endpointTestState = MutableStateFlow(EndpointTestState())
    val endpointTestState: StateFlow<EndpointTestState> = _endpointTestState.asStateFlow()

    // Encryption sandbox state
    private val _cryptoSandbox = MutableStateFlow(EncryptionSandboxState())
    val cryptoSandbox: StateFlow<EncryptionSandboxState> = _cryptoSandbox.asStateFlow()

    // Simulation feedback
    private val _simulatedLogResult = MutableStateFlow<ForwardLog?>(null)
    val simulatedLogResult: StateFlow<ForwardLog?> = _simulatedLogResult.asStateFlow()

    // Server OTP Inquiry state
    private val _otpInquiryState = MutableStateFlow<com.example.data.repository.OtpInquiryExecutionResult?>(null)
    val otpInquiryState: StateFlow<com.example.data.repository.OtpInquiryExecutionResult?> = _otpInquiryState.asStateFlow()

    private val _isOtpInquiryLoading = MutableStateFlow(false)
    val isOtpInquiryLoading: StateFlow<Boolean> = _isOtpInquiryLoading.asStateFlow()

    fun toggleMasterSwitch(enabled: Boolean) {
        viewModelScope.launch {
            val current = config.value
            val updated = current.copy(isMasterEnabled = enabled)
            repository.saveConfig(updated)

            if (enabled && updated.showForegroundNotification) {
                SmsForwarderService.startService(getApplication())
            } else {
                SmsForwarderService.stopService(getApplication())
            }
        }
    }

    fun updateConfig(updatedConfig: ForwardConfig) {
        viewModelScope.launch {
            repository.saveConfig(updatedConfig)
        }
    }

    fun addRule(senderPattern: String, matchType: MatchType, label: String, keywordFilter: String) {
        viewModelScope.launch {
            val rule = FilterRule(
                senderPattern = senderPattern.trim(),
                matchType = matchType,
                label = label.trim().ifBlank { "Rule for $senderPattern" },
                keywordFilter = keywordFilter.trim(),
                isEnabled = true
            )
            repository.insertRule(rule)
        }
    }

    fun updateRule(rule: FilterRule) {
        viewModelScope.launch {
            repository.updateRule(rule)
        }
    }

    fun toggleRule(rule: FilterRule, enabled: Boolean) {
        viewModelScope.launch {
            repository.updateRule(rule.copy(isEnabled = enabled))
        }
    }

    fun deleteRule(rule: FilterRule) {
        viewModelScope.launch {
            repository.deleteRule(rule)
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            repository.clearLogs()
        }
    }

    fun deleteLog(log: ForwardLog) {
        viewModelScope.launch {
            repository.deleteLog(log)
        }
    }

    fun retryForward(log: ForwardLog) {
        viewModelScope.launch {
            repository.retryForwardLog(log)
        }
    }

    fun runEndpointTest(
        url: String,
        authType: AuthType,
        authHeaderKey: String,
        authHeaderValue: String,
        isEncryptionEnabled: Boolean,
        secretKey: String
    ) {
        viewModelScope.launch {
            _endpointTestState.value = EndpointTestState(isLoading = true, result = null)
            val result = repository.testEndpoint(
                url = url,
                authType = authType,
                authHeaderKey = authHeaderKey,
                authHeaderValue = authHeaderValue,
                isEncryptionEnabled = isEncryptionEnabled,
                secretKey = secretKey,
                deviceIdentifier = config.value.deviceIdentifier
            )
            _endpointTestState.value = EndpointTestState(isLoading = false, result = result)
        }
    }

    fun clearEndpointTestResult() {
        _endpointTestState.value = EndpointTestState()
    }

    fun simulateIncomingSms(sender: String, message: String) {
        viewModelScope.launch {
            val log = repository.processIncomingSms(sender.trim(), message.trim())
            _simulatedLogResult.value = log
        }
    }

    fun clearSimulatedResult() {
        _simulatedLogResult.value = null
    }

    fun executeServerOtpInquiry(senderQuery: String, requestedTimestamp: Long = System.currentTimeMillis()) {
        viewModelScope.launch {
            _isOtpInquiryLoading.value = true
            val result = repository.queryAndSendOtpForServer(senderQuery, requestedTimestamp)
            _otpInquiryState.value = result
            _isOtpInquiryLoading.value = false
        }
    }

    fun checkServerHealthNow() {
        viewModelScope.launch {
            repository.checkServerHealth(getApplication())
        }
    }

    fun triggerTestDisconnectAlert() {
        viewModelScope.launch {
            repository.triggerTestDisconnectAlert(getApplication())
        }
    }

    fun clearOtpInquiryResult() {
        _otpInquiryState.value = null
    }

    private val _isOfflineSyncing = MutableStateFlow(false)
    val isOfflineSyncing: StateFlow<Boolean> = _isOfflineSyncing.asStateFlow()

    private val _offlineSyncMessage = MutableStateFlow<String?>(null)
    val offlineSyncMessage: StateFlow<String?> = _offlineSyncMessage.asStateFlow()

    fun syncOfflineLogsNow() {
        viewModelScope.launch {
            _isOfflineSyncing.value = true
            val count = repository.syncOfflinePendingLogs()
            _offlineSyncMessage.value = if (count > 0) {
                "$count پیامک با موفقیت همگام‌سازی و به سرور تحویل داده شد."
            } else {
                "پیامک جدیدی در صف ارسال وجود نداشت یا اتصال سرور برقرار نشد."
            }
            _isOfflineSyncing.value = false
        }
    }

    fun clearOfflineSyncMessage() {
        _offlineSyncMessage.value = null
    }

    fun pollServerCommandsNow() {
        viewModelScope.launch {
            repository.performHeartbeatAndCommandPoll()
        }
    }

    fun generateNewKey(): String {
        return CryptoEngine.generateRandomKey(32)
    }

    fun testCryptoSandbox(plaintext: String, secretKey: String) {
        if (plaintext.isBlank() || secretKey.isBlank()) return
        val enc = CryptoEngine.encrypt(plaintext, secretKey)
        if (enc.encrypted && enc.ivBase64 != null && enc.ciphertextBase64 != null) {
            val dec = CryptoEngine.decrypt(enc.ivBase64, enc.ciphertextBase64, secretKey)
            _cryptoSandbox.value = EncryptionSandboxState(
                inputPlaintext = plaintext,
                encryptedCiphertext = enc.ciphertextBase64,
                iv = enc.ivBase64,
                signature = enc.signatureHmac ?: "",
                decryptedText = dec.plaintext ?: "",
                error = dec.errorMessage
            )
        } else {
            _cryptoSandbox.value = EncryptionSandboxState(
                inputPlaintext = plaintext,
                error = "Encryption failed"
            )
        }
    }
}
