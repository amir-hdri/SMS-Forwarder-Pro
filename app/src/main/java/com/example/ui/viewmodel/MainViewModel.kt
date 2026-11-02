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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EndpointTestState(
    val isLoading: Boolean = false,
    val result: TransmissionResult? = null
)

data class DriverActivationUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val progressMessage: String? = null,
    val resolvedDriverName: String? = null,
    val needsSmsSelection: Boolean = false
)

data class EncryptionSandboxState(
    val inputPlaintext: String = "Your verification OTP is 739201. Do not share.",
    val encryptedCiphertext: String = "",
    val iv: String = "",
    val signature: String = "",
    val decryptedText: String = "",
    val error: String? = null
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: SmsForwardRepository =
        (application as? SmsForwarderApp)?.repository
            ?: SmsForwardRepository.getInstance(application)

    private val activationRuntime by lazy { com.example.enrollment.DriverActivationRuntime.get(application) }
    private val enrollment get() = activationRuntime.enrollment
    val enrollmentStatus get() = enrollment.status
    val enrollmentError get() = enrollment.errorMessage

    private val _driverActivationState = MutableStateFlow(DriverActivationUiState())
    val driverActivationState: StateFlow<DriverActivationUiState> = _driverActivationState.asStateFlow()

    fun resolveDriver(phone: String) {
        resolveAndActivateDriver(phone)
    }

    fun resolveAndActivateDriver(rawPhone: String) {
        if (rawPhone.isBlank()) {
            if (_secureStorageError.value != null) return
            activationRuntime.progress.value = "در انتظار اینترنت اولیه و تأیید واقعی سیم‌کارت…"
            com.example.enrollment.DriverBootstrapWorker.enqueue(getApplication())
            return
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val cleanPhone = com.example.utils.SmsParser.normalizePhoneNumber(rawPhone).filter { it.isDigit() }
            if (!cleanPhone.matches(Regex("^09[0-9]{9}$"))) {
                _driverActivationState.value = DriverActivationUiState(
                    errorMessage = "شماره موبایل وارد شده نامعتبر است (فرمت صحیح: 09xxxxxxxxx)"
                )
                return@launch
            }

            _driverActivationState.value = DriverActivationUiState(isLoading = true)

            try {
                enrollment.resolveDriverConfiguration(cleanPhone)
                when (enrollment.status.value) {
                    com.example.enrollment.EnrollmentStatus.ACTIVE -> {
                        com.example.crypto.SecureStorageManager.getInstance(getApplication()).savePendingBootstrap(null)
                        _driverActivationState.value = DriverActivationUiState(
                            isLoading = false,
                            successMessage = "فعال‌سازی با موفقیت انجام شد."
                        )
                    }
                    com.example.enrollment.EnrollmentStatus.NEEDS_PERMISSIONS -> {
                        _driverActivationState.value = DriverActivationUiState(
                            isLoading = false,
                            errorMessage = "برای تکمیل فعال‌سازی، مجوزهای پیامک را تأیید کنید."
                        )
                    }
                    else -> {
                        _driverActivationState.value = DriverActivationUiState(
                            isLoading = false,
                            errorMessage = enrollment.errorMessage.value ?: "خطا در استعلام مشخصات راننده از سرور"
                        )
                    }
                }
            } catch (error: Exception) {
                _driverActivationState.value = DriverActivationUiState(
                    isLoading = false,
                    errorMessage = error.message ?: "خطا در برقراری ارتباط با سرور بارپرو"
                )
            }
        }
    }

    fun resetDriverActivation() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val current = repository.getConfig()
            val resetConfig = current.copy(
                driverPhone = "",
                driverFullName = "",
                setupCompleted = false,
                isMasterEnabled = false,
                userConsentGiven = false
            )
            repository.saveConfig(resetConfig)
            com.example.crypto.SecureStorageManager.getInstance(getApplication()).savePendingBootstrap(null)
            _driverActivationState.value = DriverActivationUiState()
        }
    }

    fun activateFromLink(value: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { enrollment.receive(value) }
    }

    fun refreshEnrollmentPermissions() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                if (com.example.BuildConfig.APP_ROLE != "DRIVER") enrollment.resume()
                com.example.enrollment.DriverBootstrapWorker.enqueue(getApplication())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showSecureStorageFailure(error)
            }
        }
    }

    fun refreshBackgroundRecovery() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.example.service.MaintenanceWorker.schedule(getApplication()) }
            val saved = refreshSecureConfiguration(retry = _secureStorageError.value != null) ?: return@launch
            try {
                if (saved.isMasterEnabled && saved.userConsentGiven) {
                    com.example.service.HeartbeatAlarmManager.scheduleNext(getApplication(), saved.healthCheckIntervalMinutes)
                    runCatching { SmsForwarderService.startService(getApplication()) }
                } else {
                    com.example.service.HeartbeatAlarmManager.cancel(getApplication())
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("MainViewModel", "Background recovery deferred: ${error.javaClass.simpleName}")
            }
        }
    }

    val rules: StateFlow<List<FilterRule>> = repository.allRules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _secureStorageError = MutableStateFlow<String?>(null)
    val secureStorageError: StateFlow<String?> = _secureStorageError.asStateFlow()

    init {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(enrollment.status, enrollment.errorMessage,
                activationRuntime.progress, enrollment.needsSmsSelection) { status, error, progress, selection ->
                DriverActivationUiState(isLoading = status == com.example.enrollment.EnrollmentStatus.ACTIVATING ||
                    (status == com.example.enrollment.EnrollmentStatus.IDLE && progress != null),
                    errorMessage = error, progressMessage = progress, needsSmsSelection = selection)
            }.collect { _driverActivationState.value = it }
        }
    }
    private val configRefresh = MutableStateFlow(0L)
    private val lastReadableConfig = MutableStateFlow(ForwardConfig())

    private fun stoppedUiConfig(value: ForwardConfig) = value.copy(
        isMasterEnabled = false, userConsentGiven = false,
        authHeaderValue = "", forwarderSecret = "", secretEncryptionKey = ""
    )

    private fun showSecureStorageFailure(error: Exception) {
        android.util.Log.w("MainViewModel", "Secure configuration unavailable: ${error.javaClass.simpleName}")
        _secureStorageError.value = "ذخیره‌سازی امن در دسترس نیست؛ ارسال پیامک متوقف است."
    }

    val config: StateFlow<ForwardConfig> = configRefresh.flatMapLatest {
        repository.configFlow.map { cfg ->
            (cfg ?: ForwardConfig()).also { lastReadableConfig.value = it }
        }.catch { error ->
            if (error is CancellationException) throw error
            if (error !is Exception) throw error
            showSecureStorageFailure(error)
            emit(stoppedUiConfig(lastReadableConfig.value))
        }
    }.combine(_secureStorageError) { cfg, error -> if (error == null) cfg else stoppedUiConfig(cfg) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ForwardConfig())

    /** Recovery verifies secure reads and writes; persisted forwarding stays disabled. */
    fun retrySecureStorage() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { refreshSecureConfiguration(retry = true) }
    }

    private suspend fun refreshSecureConfiguration(retry: Boolean): ForwardConfig? = try {
        val restored = if (retry) repository.retrySecureStorage() else repository.getConfig()
        lastReadableConfig.value = restored
        _secureStorageError.value = null
        configRefresh.update { it + 1 }
        restored
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        showSecureStorageFailure(error)
        null
    }

    private suspend fun saveConfigSafely(value: ForwardConfig): Boolean {
        // A redacted UI representation must never replace the stored enrollment identity.
        if (_secureStorageError.value != null) return false
        return try {
            repository.saveConfig(value)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            showSecureStorageFailure(error)
            false
        }
    }

    private fun launchSecureAction(operation: suspend () -> Unit) {
        viewModelScope.launch {
            if (_secureStorageError.value != null) return@launch
            try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showSecureStorageFailure(error)
            }
        }
    }

    // Navigation trigger from notifications or external intents
    val showServerSettings = MutableStateFlow(false)
    val showPermissionsDialog = MutableStateFlow(false)

    fun openServerSettings() {
        showServerSettings.value = true
    }

    fun closeServerSettings() {
        showServerSettings.value = false
    }

    fun openPermissions() {
        showPermissionsDialog.value = true
    }

    fun closePermissions() {
        showPermissionsDialog.value = false
    }

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

    val pendingCount: StateFlow<Int> = repository.pendingCount
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
            if (enabled && !com.example.utils.PermissionHelper.areForwardingPermissionsGranted(getApplication())) {
                openPermissions()
                return@launch
            }
            // This explicit switch is the user's opt-in to forwarding.
            val updated = current.copy(isMasterEnabled = enabled, userConsentGiven = enabled)
            if (!saveConfigSafely(updated)) return@launch

            if (enabled) {
                com.example.service.HeartbeatAlarmManager.scheduleNext(getApplication(), updated.healthCheckIntervalMinutes)
                runCatching { SmsForwarderService.startService(getApplication()) }
            } else {
                com.example.service.HeartbeatAlarmManager.cancel(getApplication())
                SmsForwarderService.stopService(getApplication())
            }
        }
    }

    fun updateConfig(updatedConfig: ForwardConfig) {
        viewModelScope.launch {
            saveConfigSafely(updatedConfig)
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
        launchSecureAction {
            repository.retryForwardLog(log)
        }
    }

    fun runEndpointTest(
        url: String,
        authType: AuthType,
        authHeaderKey: String,
        authHeaderValue: String,
        isEncryptionEnabled: Boolean,
        secretKey: String,
        allowCleartextTransport: Boolean? = null
    ) {
        viewModelScope.launch {
            _endpointTestState.value = EndpointTestState(isLoading = true, result = null)
            val current = config.value
            val cleartext = allowCleartextTransport ?: current.allowCleartextTransport
            val result = repository.testEndpoint(
                url = url,
                authType = authType,
                authHeaderKey = authHeaderKey,
                authHeaderValue = authHeaderValue,
                isEncryptionEnabled = isEncryptionEnabled,
                secretKey = secretKey,
                deviceIdentifier = current.deviceIdentifier,
                allowCleartextTransport = cleartext,
                driverPhone = current.driverPhone,
                fallbackServerPhoneNumber = current.fallbackServerPhoneNumber,
                hubIrancellPhoneNumber = current.hubIrancellPhoneNumber
            )
            _endpointTestState.value = EndpointTestState(isLoading = false, result = result)
        }
    }

    fun clearEndpointTestResult() {
        _endpointTestState.value = EndpointTestState()
    }

    fun simulateIncomingSms(sender: String, message: String) {
        launchSecureAction {
            val log = repository.processIncomingSms(sender.trim(), message.trim())
            _simulatedLogResult.value = log
        }
    }

    fun clearSimulatedResult() {
        _simulatedLogResult.value = null
    }

    fun executeServerOtpInquiry(senderQuery: String, requestedTimestamp: Long = System.currentTimeMillis()) {
        launchSecureAction {
            _isOtpInquiryLoading.value = true
            try {
                val result = repository.queryAndSendOtpForServer(senderQuery, requestedTimestamp)
                _otpInquiryState.value = result
            } finally {
                _isOtpInquiryLoading.value = false
            }
        }
    }

    fun checkServerHealthNow() {
        launchSecureAction {
            repository.checkServerHealth(getApplication())
        }
    }

    fun triggerTestDisconnectAlert() {
        launchSecureAction {
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
        launchSecureAction {
            _isOfflineSyncing.value = true
            try {
                val count = repository.syncOfflinePendingLogs()
                _offlineSyncMessage.value = if (count > 0) {
                    "$count پیامک با موفقیت همگام‌سازی و به سرور تحویل داده شد."
                } else {
                    "پیامک جدیدی در صف ارسال وجود نداشت یا اتصال سرور برقرار نشد."
                }
            } finally {
                _isOfflineSyncing.value = false
            }
        }
    }

    fun clearOfflineSyncMessage() {
        _offlineSyncMessage.value = null
    }

    fun pollServerCommandsNow() {
        launchSecureAction {
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
