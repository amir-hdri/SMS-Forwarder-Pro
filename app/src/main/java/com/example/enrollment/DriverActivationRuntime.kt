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
import java.security.MessageDigest
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
    suspend fun activate(driverPhone: String, driverFullName: String = "") {
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
            var pending = storage.getPendingBootstrap()?.let(::JSONObject)
            if (pending == null || pending.optString("phone") != phone || pending.optString("device_id") != id) {
                cancelBootstrapCallbacks()
                storage.saveBootstrapSmsAttempt(null)
                pending = JSONObject().put("phone", phone).put("device_id", id)
            }
            // This is a request constraint, not a proved profile field. It survives a settings trip/restart.
            storage.savePendingBootstrap(requireNotNull(pending).put("requested_name", driverFullName.trim()).toString())
            check(preferences.edit().putBoolean(STOPPED, false).commit())
        }
        DriverBootstrapWorker.cancel(context)
        try {
            automaticLock.withLock {
                enrollment.withGeneration(expected) { Unit }
                enrollment.resolveDriverConfiguration(phone, driverName = driverFullName.trim())
                finishActivation(expected)
            }
        } finally {
            recoverAfterForeground(expected)
        }
    }

    suspend fun receive(value: String) {
        require(BuildConfig.APP_ROLE == "DRIVER")
        val linkHash = if (value.length <= 4096) MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).joinToString("") { "%02x".format(it) } else ""
        val expected = enrollment.cancelActiveOperation()
        val (selected, recoverWorkingProfile) = enrollment.withGeneration(expected) {
            val wasStopped = isActivationStopped(context)
            // A process death or unreadable stage during replacement must leave the older intent stopped.
            check(preferences.edit().putBoolean(STOPPED, true).commit())
            val pending = storage.getPendingBootstrap()?.let(::JSONObject)
            val document = storage.getPendingEnrollment()
            val grant = document?.let { runCatching { EnrollmentContract.parseCredentials(it, server) }.getOrNull() }
            val accepted = pending?.takeIf {
                linkHash.isNotEmpty() && it.optString("accepted_link_sha256") == linkHash &&
                    it.optString("device_id") == deviceId() && it.optString("phone").matches(Regex("09[0-9]{9}")) &&
                    (document == null || grant?.driverPhone == it.optString("phone"))
            }
            val recoverProfile = pending == null && document == null && !wasStopped
            // A new link replaces the previous request, even across process death or a permission trip.
            progress.value = null
            cancelBootstrapCallbacks()
            storage.savePendingBootstrap(accepted?.toString())
            storage.saveBootstrapSmsAttempt(null)
            // An exact accepted link retains only its selected phone/device intent. Its own token or
            // genuine carrier proof must still be validated before forwarding can become active.
            if (accepted != null) check(preferences.edit().putBoolean(STOPPED, false).commit())
            accepted to recoverProfile
        }
        DriverBootstrapWorker.cancel(context)
        try {
            automaticLock.withLock {
                enrollment.withGeneration(expected) { Unit }
                if (selected != null) enrollment.resolveDriverConfiguration(selected.getString("phone"))
                else enrollment.receive(value)
                val current = if (enrollment.status.value == EnrollmentStatus.IDLE && recoverWorkingProfile)
                    repository.getConfig() else null
                enrollment.withGeneration(expected) {
                    if (enrollment.status.value == EnrollmentStatus.NEEDS_PERMISSIONS) {
                        val document = storage.getPendingEnrollment()
                        if (document != null) {
                            val grant = EnrollmentContract.parseCredentials(document, server)
                            // Only successful redemption may bind a new link to a durable selected phone.
                            storage.savePendingBootstrap(JSONObject().put("phone", grant.driverPhone)
                                .put("device_id", deviceId()).put("requested_name", "")
                                .put("accepted_link_sha256", linkHash).toString())
                        } else check(selected != null) { "Missing accepted activation intent" }
                    }
                    if (enrollment.status.value in setOf(EnrollmentStatus.ACTIVE, EnrollmentStatus.NEEDS_PERMISSIONS) ||
                        (current != null && EnrollmentContract.isEnrolledDriver(current) &&
                            current.isMasterEnabled && current.userConsentGiven))
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

    /** A forwarding stop revokes outstanding activation without rewriting the private profile. */
    internal fun stopPendingActivation() {
        val expected = enrollment.cancelActiveOperation()
        enrollment.withGeneration(expected) {
            check(preferences.edit().putBoolean(STOPPED, true).commit())
            progress.value = null
            DriverBootstrapWorker.cancel(context)
            cancelBootstrapCallbacks()
        }
    }

    suspend fun activateAutomatically() {
        if (isActivationStopped(context)) return
        val expected = enrollment.currentGeneration()
        automaticLock.withLock {
            enrollment.withGeneration(expected) {
                if (isActivationStopped(context)) throw CancellationException("Activation was explicitly stopped")
            }
            val current = repository.getConfig()
            enrollment.withGeneration(expected) { Unit }
            val pending = enrollment.withGeneration(expected) { storage.getPendingBootstrap()?.let(::JSONObject) }
            if (pending != null) {
                val phone = pending.getString("phone")
                require(phone.isEmpty() || phone.matches(Regex("09[0-9]{9}"))) { "Invalid pending activation identity" }
                require(pending.getString("device_id") == deviceId()) { "Pending activation device changed" }
                enrollment.resolveDriverConfiguration(phone, driverName = pending.optString("requested_name", ""))
            } else if (EnrollmentContract.isEnrolledDriver(current)) {
                // A retained grant cannot undo a later explicit forwarding/consent stop.
                if (!current.isMasterEnabled || !current.userConsentGiven) return@withLock
                enrollment.resolveDriverConfiguration("")
                enrollment.withGeneration(expected) { storage.savePendingEnrollment(null) }
            } else if (storage.getPendingEnrollment() != null) {
                enrollment.resume()
            } else return@withLock
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
