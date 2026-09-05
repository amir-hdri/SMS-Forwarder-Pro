package com.example.network

import android.content.Context
import com.example.data.model.ForwardConfig
import com.example.service.ServerHealthNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ServerHealthStatus {
    CONNECTED,      // Server is online, responding with 200 OK
    CHECKING,       // Health check ping in progress
    DISCONNECTED,   // Server is unreachable, network error or timeout
    UNKNOWN         // Not yet tested
}

data class ServerHealthState(
    val status: ServerHealthStatus = ServerHealthStatus.UNKNOWN,
    val lastSuccessTimestamp: Long = 0L,
    val lastCheckedTimestamp: Long = 0L,
    val consecutiveFailures: Int = 0,
    val lastLatencyMs: Long = 0L,
    val lastErrorMessage: String? = null,
    val endpointUrl: String = "",
    val isAlertActive: Boolean = false
)

object ServerHealthMonitor {

    private val _healthState = MutableStateFlow(ServerHealthState())
    val healthState: StateFlow<ServerHealthState> = _healthState.asStateFlow()

    private var periodicJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun recordSuccess(context: Context?, endpointUrl: String, latencyMs: Long = 0L) {
        val currentState = _healthState.value
        val wasAlertActive = currentState.isAlertActive

        _healthState.value = currentState.copy(
            status = ServerHealthStatus.CONNECTED,
            lastSuccessTimestamp = System.currentTimeMillis(),
            lastCheckedTimestamp = System.currentTimeMillis(),
            consecutiveFailures = 0,
            lastLatencyMs = latencyMs,
            lastErrorMessage = null,
            endpointUrl = endpointUrl,
            isAlertActive = false
        )

        if (wasAlertActive && context != null) {
            ServerHealthNotifier.showRecoveryNotification(context, endpointUrl)
        }
    }

    fun recordFailure(
        context: Context,
        endpointUrl: String,
        errorMessage: String?,
        config: ForwardConfig
    ) {
        val currentState = _healthState.value
        val newFailures = currentState.consecutiveFailures + 1
        val shouldShowAlert = config.enableHealthAlertNotification &&
                config.isMasterEnabled &&
                newFailures >= config.healthFailureThreshold

        _healthState.value = currentState.copy(
            status = ServerHealthStatus.DISCONNECTED,
            lastCheckedTimestamp = System.currentTimeMillis(),
            consecutiveFailures = newFailures,
            lastErrorMessage = errorMessage,
            endpointUrl = endpointUrl,
            isAlertActive = shouldShowAlert || currentState.isAlertActive
        )

        if (shouldShowAlert) {
            ServerHealthNotifier.showDisconnectedAlert(
                context = context,
                endpointUrl = endpointUrl,
                consecutiveFailures = newFailures,
                errorMessage = errorMessage,
                lastSuccessTimestamp = currentState.lastSuccessTimestamp
            )
        }
    }

    suspend fun performHealthPing(
        context: Context,
        config: ForwardConfig,
        client: SmsForwarderClient
    ): ServerHealthState = withContext(Dispatchers.IO) {
        if (!config.isMasterEnabled || config.endpointUrl.isBlank()) {
            return@withContext _healthState.value
        }

        _healthState.value = _healthState.value.copy(status = ServerHealthStatus.CHECKING)

        val result = client.testEndpoint(
            endpointUrl = config.endpointUrl,
            authType = config.authType,
            authHeaderKey = config.authHeaderKey,
            authHeaderValue = config.authHeaderValue,
            isEncryptionEnabled = config.isEncryptionEnabled,
            secretKey = config.secretEncryptionKey,
            deviceIdentifier = config.deviceIdentifier
        )

        if (result.isSuccess) {
            recordSuccess(context, config.endpointUrl, result.durationMs)
        } else {
            recordFailure(context, config.endpointUrl, result.errorMessage, config)
        }

        _healthState.value
    }

    fun startPeriodicMonitoring(
        context: Context,
        configProvider: suspend () -> ForwardConfig,
        client: SmsForwarderClient,
        onPeriodicTick: (suspend () -> Unit)? = null
    ) {
        periodicJob?.cancel()
        periodicJob = scope.launch {
            while (isActive) {
                val config = configProvider()
                if (config.isMasterEnabled) {
                    if (config.enableHealthAlertNotification) {
                        performHealthPing(context, config, client)
                    }
                    try {
                        onPeriodicTick?.invoke()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                val intervalMinutes = config.healthCheckIntervalMinutes.coerceIn(1, 60)
                delay(intervalMinutes * 60 * 1000L)
            }
        }
    }

    fun stopPeriodicMonitoring(context: Context? = null) {
        periodicJob?.cancel()
        periodicJob = null
        if (context != null) {
            ServerHealthNotifier.dismissDisconnectedAlert(context)
        }
    }

    fun triggerTestAlert(context: Context, endpointUrl: String) {
        ServerHealthNotifier.showDisconnectedAlert(
            context = context,
            endpointUrl = endpointUrl,
            consecutiveFailures = 3,
            errorMessage = "تست آزمایشی نوتیفیکیشن قطعی اتصال توسط کاربر",
            lastSuccessTimestamp = System.currentTimeMillis() - 1000 * 60 * 12
        )
    }
}
