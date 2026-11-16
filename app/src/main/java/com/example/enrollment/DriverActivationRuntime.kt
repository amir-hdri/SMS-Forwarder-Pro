package com.example.enrollment

import android.content.Context
import com.example.BuildConfig
import com.example.crypto.SecureStorageManager
import com.example.data.model.ForwardConfig
import com.example.data.repository.SmsForwardRepository
import com.example.utils.PermissionHelper
import com.example.utils.SmsParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import org.json.JSONObject

/** One activation owner shared by UI, background work and process-restart recovery. */
class DriverActivationRuntime private constructor(private val context: Context) {
    private val repository = SmsForwardRepository.getInstance(context)
    private val storage = SecureStorageManager.getInstance(context)
    private val defaultEndpoint = BuildConfig.DEFAULT_ENDPOINT_URL.ifBlank { ForwardConfig().endpointUrl }
    private val server = EnrollmentContract.trustedServer(defaultEndpoint)
    private val client by lazy { BootstrapClient(server) }
    private val preferences = context.getSharedPreferences(IDENTITY_STORE, Context.MODE_PRIVATE)
    val progress = MutableStateFlow<String?>(null)
    private val automaticLock = Mutex()

    private suspend fun <T> withEgressClient(block: suspend (BootstrapClient) -> T): T {
        enrollment.withActiveOperation { Unit }
        if (com.example.network.CellularEgress.isVpnActive(context)) {
            val egress = com.example.network.CellularEgress.acquire(context, timeoutMs = 4000L)
            if (egress != null) {
                return egress.use { session ->
                    enrollment.withActiveOperation { Unit }
                    val boundClient = session.bind(okhttp3.OkHttpClient())
                    block(BootstrapClient(server, boundClient))
                }
            }
        }
        enrollment.withActiveOperation { Unit }
        return block(client)
    }

    private suspend fun challenge(phone: String, deviceId: String, publicKey: String): org.json.JSONObject =
        withEgressClient { it.challenge(phone, deviceId, publicKey) }

    private suspend fun poll(nonce: String, deviceId: String, signature: String): org.json.JSONObject =
        withEgressClient { it.poll(nonce, deviceId, signature) }

    val bootstrap: BootstrapCoordinator by lazy { BootstrapCoordinator(server, DeviceBootstrapKey(),
        challenge = ::challenge, poll = ::poll,
        stage = { value -> enrollment.withActiveOperation { storage.savePendingBootstrap(value) } },
        loadStage = { enrollment.withActiveOperation { storage.getPendingBootstrap() } },
        permissionsGranted = { PermissionHelper.areForwardingPermissionsGranted(context) },
        sendSms = { number, nonce -> enrollment.withActiveOperation { BootstrapSms.send(context, number, nonce) } },
        progress = { value -> enrollment.withActiveOperation { progress.value = value } },
        smsState = { nonce -> enrollment.withActiveOperation { BootstrapSmsDispatch.state(context, nonce) } }) }
    val enrollment: EnrollmentManager = EnrollmentManager(defaultEndpoint, BuildConfig.APP_ROLE,
        deviceId = ::deviceId, redeem = { link, id -> EnrollmentClient().redeem(link, id) },
        getConfig = repository::getConfig, saveConfig = repository::saveConfig,
        stage = storage::savePendingEnrollment, loadStage = storage::getPendingEnrollment,
        permissionsGranted = { PermissionHelper.areForwardingPermissionsGranted(context) },
        onActivated = ::onActivated,
        bootstrap = { phone, id -> bootstrap.start(phone, id) })

    private suspend fun onActivated() {
        enrollment.withActiveOperation {
            com.example.service.HeartbeatAlarmManager.scheduleNext(context, 5)
            runCatching { com.example.service.MaintenanceWorker.schedule(context) }
            runCatching { com.example.service.SmsForwarderService.startService(context) }
        }
        repository.performHeartbeatAndCommandPoll()
    }

    private fun deviceId(): String = preferences.getString("enrollment_device_id", null)
        ?: UUID.randomUUID().toString().also {
            check(preferences.edit().putString("enrollment_device_id", it).commit())
        }

    /** Only the user's activation action creates an intent; lifecycle recovery never invents one. */
    suspend fun activate(driverPhone: String) {
        require(BuildConfig.APP_ROLE == "DRIVER")
        val phone = SmsParser.normalizePhoneNumber(driverPhone)
        require(phone.isEmpty() || phone.matches(Regex("09[0-9]{9}")))
        val expected = enrollment.cancelActiveOperation()
        enrollment.withGeneration(expected) {
            // A process death during replacement must leave recovery stopped until the selected intent is durable.
            check(preferences.edit().putBoolean(STOPPED, true).commit())
            progress.value = null
            val id = deviceId()
            val pendingGrant = storage.getPendingEnrollment()?.let {
                EnrollmentContract.parseCredentials(it, server)
            }
            if (phone.isNotEmpty() && pendingGrant != null && pendingGrant.driverPhone != phone)
                storage.savePendingEnrollment(null)
            val pending = storage.getPendingBootstrap()?.let(::JSONObject)
            if (pending == null || pending.optString("phone") != phone || pending.optString("device_id") != id) {
                cancelBootstrapCallbacks()
                storage.saveBootstrapSmsAttempt(null)
                storage.savePendingBootstrap(JSONObject().put("phone", phone).put("device_id", id).toString())
            }
            check(preferences.edit().putBoolean(STOPPED, false).commit())
        }
        DriverBootstrapWorker.cancel(context)
        try {
            automaticLock.withLock {
                enrollment.withGeneration(expected) { Unit }
                enrollment.resolveDriverConfiguration(phone)
                finishActivation(expected)
            }
        } finally {
            recoverAfterForeground(expected)
        }
    }

    suspend fun receive(value: String) {
        require(BuildConfig.APP_ROLE == "DRIVER")
        val expected = enrollment.cancelActiveOperation()
        enrollment.withGeneration(expected) {
            progress.value = null
        }
        DriverBootstrapWorker.cancel(context)
        try {
            automaticLock.withLock {
                enrollment.withGeneration(expected) { Unit }
                enrollment.receive(value)
                enrollment.withGeneration(expected) {
                    if (enrollment.status.value in setOf(EnrollmentStatus.ACTIVE, EnrollmentStatus.NEEDS_PERMISSIONS))
                        check(preferences.edit().putBoolean(STOPPED, false).commit())
                }
                finishActivation(expected)
            }
        } finally {
            recoverAfterForeground(expected)
        }
    }

    suspend fun reset() = enrollment.reset(onRevoked = {
        progress.value = null
        check(preferences.edit().putBoolean(STOPPED, true).commit())
        DriverBootstrapWorker.cancel(context)
    }, onReset = {
        cancelBootstrapCallbacks()
        storage.savePendingBootstrap(null)
        storage.saveBootstrapSmsAttempt(null)
    })

    suspend fun activateAutomatically() {
        if (isActivationStopped(context)) return
        val expected = enrollment.currentGeneration()
        automaticLock.withLock {
            enrollment.withGeneration(expected) {
                if (isActivationStopped(context)) throw CancellationException("Activation was explicitly stopped")
            }
            val current = repository.getConfig()
            enrollment.withGeneration(expected) { Unit }
            if (EnrollmentContract.isEnrolledDriver(current)) {
                // A retained grant cannot undo a later explicit forwarding/consent stop.
                enrollment.resolveDriverConfiguration("")
                enrollment.withGeneration(expected) { storage.savePendingEnrollment(null) }
            } else if (storage.getPendingEnrollment() != null) {
                enrollment.resume()
            } else {
                val pending = storage.getPendingBootstrap()?.let(::JSONObject) ?: return@withLock
                val phone = pending.getString("phone")
                require(phone.isEmpty() || phone.matches(Regex("09[0-9]{9}"))) { "Invalid pending activation identity" }
                require(pending.getString("device_id") == deviceId()) { "Pending activation device changed" }
                enrollment.resolveDriverConfiguration(phone)
            }
            finishActivation(expected)
        }
    }

    private fun finishActivation(expected: Long) = enrollment.withGeneration(expected) {
        if (enrollment.status.value == EnrollmentStatus.ACTIVE) {
            cancelBootstrapCallbacks()
            storage.savePendingBootstrap(null)
            storage.saveBootstrapSmsAttempt(null)
        }
        progress.value = null
    }

    private fun recoverAfterForeground(expected: Long) {
        val pending = runCatching {
            enrollment.withGeneration(expected) {
                progress.value = null
                hasPendingActivation(context)
            }
        }.getOrDefault(false)
        if (pending) runCatching { DriverBootstrapWorker.enqueue(context, userInitiated = true) }
    }

    private fun cancelBootstrapCallbacks() {
        storage.getBootstrapSmsAttempt()?.let {
            val attempt = JSONObject(it)
            com.example.utils.SmsDispatch.cancelBootstrapAttempt(context, attempt.getString("nonce"), attempt.getLong("attempt_at"))
        }
    }

    companion object {
        private const val IDENTITY_STORE = "barpro_device_identity"
        private const val STOPPED = "driver_activation_stopped"
        @Volatile private var instance: DriverActivationRuntime? = null
        internal fun isActivationStopped(context: Context): Boolean =
            context.getSharedPreferences(IDENTITY_STORE, Context.MODE_PRIVATE).getBoolean(STOPPED, false)

        internal fun hasPendingActivation(context: Context): Boolean {
            if (isActivationStopped(context)) return false
            val storage = SecureStorageManager.getInstance(context)
            return storage.getPendingEnrollment() != null || storage.getPendingBootstrap() != null
        }

        fun get(context: Context): DriverActivationRuntime = synchronized(this) {
            val app = context.applicationContext
            instance?.takeIf { it.context === app } ?: DriverActivationRuntime(app).also { instance = it }
        }
    }
}
