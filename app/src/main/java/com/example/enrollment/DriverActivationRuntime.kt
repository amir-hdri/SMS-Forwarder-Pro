package com.example.enrollment

import android.content.Context
import com.example.BuildConfig
import com.example.crypto.SecureStorageManager
import com.example.data.model.ForwardConfig
import com.example.data.repository.SmsForwardRepository
import com.example.utils.PermissionHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** One activation owner shared by UI, background work and process-restart recovery. */
class DriverActivationRuntime private constructor(private val context: Context) {
    private val repository = SmsForwardRepository.getInstance(context)
    private val storage = SecureStorageManager.getInstance(context)
    private val defaultEndpoint = BuildConfig.DEFAULT_ENDPOINT_URL.ifBlank { ForwardConfig().endpointUrl }
    private val server = EnrollmentContract.trustedServer(defaultEndpoint)
    private val client = BootstrapClient(server)
    val progress = MutableStateFlow<String?>(null)
    private val automaticLock = Mutex()
    val bootstrap = BootstrapCoordinator(server, DeviceBootstrapKey(),
        challenge = client::challenge, poll = client::poll,
        stage = storage::savePendingBootstrap, loadStage = storage::getPendingBootstrap,
        permissionsGranted = { PermissionHelper.areForwardingPermissionsGranted(context) },
        sendSms = { number, nonce -> BootstrapSms.send(context, number, nonce) },
        progress = { progress.value = it }, smsState = { BootstrapSmsDispatch.state(context, it) })
    val enrollment = EnrollmentManager(defaultEndpoint, BuildConfig.APP_ROLE,
        deviceId = {
            val preferences = context.getSharedPreferences("barpro_device_identity", Context.MODE_PRIVATE)
            preferences.getString("enrollment_device_id", null) ?: UUID.randomUUID().toString().also {
                check(preferences.edit().putString("enrollment_device_id", it).commit())
            }
        }, redeem = { link, id -> EnrollmentClient().redeem(link, id) },
        getConfig = repository::getConfig, saveConfig = repository::saveConfig,
        stage = storage::savePendingEnrollment, loadStage = storage::getPendingEnrollment,
        permissionsGranted = { PermissionHelper.areForwardingPermissionsGranted(context) },
        onActivated = {
            com.example.service.HeartbeatAlarmManager.scheduleNext(context, 5)
            runCatching { com.example.service.MaintenanceWorker.schedule(context) }
            runCatching { com.example.service.SmsForwarderService.startService(context) }
            repository.performHeartbeatAndCommandPoll()
        }, bootstrap = bootstrap::start)

    suspend fun activateAutomatically() = automaticLock.withLock {
        if (EnrollmentContract.isEnrolledDriver(repository.getConfig())) {
            // A retained decoded stage cannot undo a later explicit forwarding/consent stop.
            enrollment.resolveDriverConfiguration("")
            storage.savePendingEnrollment(null)
        } else {
            enrollment.resume()
            if (enrollment.status.value != EnrollmentStatus.ACTIVE && storage.getPendingEnrollment() == null)
                enrollment.resolveDriverConfiguration("")
        }
        if (enrollment.status.value == EnrollmentStatus.ACTIVE) {
            storage.getBootstrapSmsAttempt()?.let {
                val attempt = org.json.JSONObject(it)
                com.example.utils.SmsDispatch.cancelBootstrapAttempt(context, attempt.getString("nonce"), attempt.getLong("attempt_at"))
            }
            storage.savePendingBootstrap(null)
            storage.saveBootstrapSmsAttempt(null)
        }
    }

    companion object {
        @Volatile private var instance: DriverActivationRuntime? = null
        fun get(context: Context): DriverActivationRuntime = synchronized(this) {
            val app = context.applicationContext
            instance?.takeIf { it.context === app } ?: DriverActivationRuntime(app).also { instance = it }
        }
    }
}
