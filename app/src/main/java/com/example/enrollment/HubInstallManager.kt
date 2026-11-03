package com.example.enrollment

import com.example.data.repository.SmsForwardRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** Shares one durable grant owner across foreground, WorkManager and restart recovery. */
class HubInstallManager(private val role: String, private val key: DeviceBootstrapKey,
    private val repository: SmsForwardRepository, private val profile: () -> HubInstallProfile?,
    private val redeem: suspend (HubInstallProfile, String, DeviceBootstrapKey) -> JSONObject,
    private val loadStage: () -> String?, private val saveStage: (String?) -> Unit,
    private val permissions: () -> Boolean, private val activated: suspend () -> Unit,
    private val completedHash: () -> String? = { null }, private val saveCompletedHash: (String) -> Unit = {}) {
    private val lock = Mutex()
    private var appliedCapabilityHash: String? = null
    private val mutableStatus = MutableStateFlow(EnrollmentStatus.IDLE)
    val status = mutableStatus.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    var retryable = true
        private set

    suspend fun activate() = lock.withLock {
        if (role != "HUB") return@withLock
        if (!permissions()) { mutableStatus.value = EnrollmentStatus.NEEDS_PERMISSIONS; return@withLock }
        try {
            val id = key.hubDeviceId()
            val current = repository.getConfig()
            val savedStage = loadStage()
            val installation = if (savedStage == null) profile() else null
            val alreadyUsed = installation?.let {
                HubInstallContract.capabilityHash(it.capability) == (completedHash() ?: appliedCapabilityHash)
            } ?: true
            if (savedStage == null && HubInstallContract.isEnrolled(current, id) && alreadyUsed) {
                mutableStatus.value = EnrollmentStatus.ACTIVE
                mutableError.value = null
                return@withLock
            }
            val stage = if (savedStage != null) JSONObject(savedStage) else {
                installation ?: return@withLock
                mutableStatus.value = EnrollmentStatus.ACTIVATING
                val grant = redeem(installation, id, key)
                JSONObject().put("server", installation.server.toString()).put("nonce", installation.capability)
                    .put("grant", grant).also { saveStage(it.toString()) }
            }
            mutableStatus.value = EnrollmentStatus.ACTIVATING
            val server = stage.getString("server").toHttpUrl()
            require(server.isHttps && server.encodedPath == "/" && server.query == null && server.fragment == null)
            val grant = stage.getJSONObject("grant")
            require(grant.getString("protocol") == DeviceBootstrapKey.AUTOMATIC_PROTOCOL)
            val credentials = HubInstallContract.parseCredentials(key.decrypt(grant, stage.getString("nonce"), id), server, id)
            if (!permissions()) { mutableStatus.value = EnrollmentStatus.NEEDS_PERMISSIONS; return@withLock }
            val updated = repository.updateConfig(credentials::applyTo)
            mutableStatus.value = EnrollmentStatus.ACTIVE
            mutableError.value = null
            retryable = true
            appliedCapabilityHash = HubInstallContract.capabilityHash(stage.getString("nonce"))
            saveCompletedHash(requireNotNull(appliedCapabilityHash))
            saveStage(null)
            if (updated.isMasterEnabled && updated.userConsentGiven) {
                try { activated() } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Existing maintenance recovers actual service/health failures. */ }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            retryable = error !is IllegalArgumentException && !(error is BootstrapHttpException &&
                error.code in setOf(400, 401, 403, 410, 422))
            mutableStatus.value = EnrollmentStatus.FAILED
            mutableError.value = if (error is BootstrapHttpException && error.code in setOf(400, 401, 403, 410, 422))
                "مجوز این بسته نصب هاب پذیرفته نشد؛ بسته نصب خصوصی تازه لازم است."
            else "فعال‌سازی امن هاب هنوز تأیید نشده است؛ اتصال اینترنت و مجوزهای برنامه را بررسی کنید."
        }
    }
}
