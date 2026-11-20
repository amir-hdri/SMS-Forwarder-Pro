package com.example.enrollment

import com.example.data.model.ForwardConfig
import com.example.utils.SmsParser
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class EnrollmentStatus { IDLE, ACTIVATING, NEEDS_PERMISSIONS, ACTIVE, FAILED }

/** One state machine shared by cold and warm intents; pending credentials survive permission/settings trips. */
class EnrollmentManager(
    private val defaultEndpoint: String,
    private val role: String,
    private val deviceId: () -> String,
    private val redeem: suspend (EnrollmentLink, String) -> EnrollmentCredentials,
    private val getConfig: suspend () -> ForwardConfig,
    private val saveConfig: suspend (ForwardConfig) -> Unit,
    private val stage: (String?) -> Unit,
    private val loadStage: () -> String?,
    private val permissionsGranted: () -> Boolean,
    private val onActivated: suspend () -> Unit,
    private val resolve: suspend (phone: String, deviceId: String, token: String) -> EnrollmentCredentials = { phone, devId, token ->
        EnrollmentClient().resolve(phone, EnrollmentContract.trustedServer(defaultEndpoint), devId, token)
    },
    private val bootstrap: (suspend (String, String) -> EnrollmentCredentials)? = null
) {
    private val mutex = Mutex()
    private val ownershipLock = Any()
    private var generation = 0L
    private class Owner(val generation: Long, val job: Job?)
    private var activeOwner: Owner? = null
    private val attempted = mutableSetOf<String>()
    private val mutableStatus = MutableStateFlow(EnrollmentStatus.IDLE)
    val status = mutableStatus.asStateFlow()
    private val mutableErrorMessage = MutableStateFlow<String?>(null)
    val errorMessage = mutableErrorMessage.asStateFlow()
    private val mutableNeedsSmsSelection = MutableStateFlow(false)
    val needsSmsSelection = mutableNeedsSmsSelection.asStateFlow()
    var failureRetryable: Boolean = true
        private set

    /** Revoke before waiting for the operation mutex; an HTTP/modem callback can finish after cancellation. */
    internal fun cancelActiveOperation(): Long {
        val revoked: Pair<Long, Job?> = synchronized(ownershipLock) {
            generation++
            val job = activeOwner?.job
            mutableStatus.value = EnrollmentStatus.IDLE
            mutableErrorMessage.value = null
            mutableNeedsSmsSelection.value = false
            failureRetryable = true
            generation to job
        }
        revoked.second?.cancel()
        return revoked.first
    }

    internal fun currentGeneration(): Long = synchronized(ownershipLock) { generation }

    internal fun <T> withGeneration(expected: Long, action: () -> T): T = synchronized(ownershipLock) {
        if (generation != expected) throw CancellationException("Activation ownership changed")
        action()
    }

    /** Runtime bootstrap callbacks share the currently registered operation's authority. */
    internal fun <T> withActiveOperation(action: () -> T): T = synchronized(ownershipLock) {
        val owner = activeOwner ?: throw CancellationException("Activation has no owner")
        checkOwner(owner)
        action()
    }

    private fun checkOwner(owner: Owner) {
        if (generation != owner.generation || activeOwner !== owner || owner.job?.isActive == false)
            throw CancellationException("Activation ownership changed")
    }

    private fun <T> writeOwned(owner: Owner, action: () -> T): T = synchronized(ownershipLock) {
        checkOwner(owner)
        action()
    }

    private suspend fun <T> owned(expected: Long = currentGeneration(), action: suspend (Owner) -> T): T = mutex.withLock {
        currentCoroutineContext().ensureActive()
        val owner = Owner(expected, currentCoroutineContext()[Job])
        withGeneration(expected) { activeOwner = owner }
        try {
            action(owner)
        } catch (cancelled: CancellationException) {
            synchronized(ownershipLock) {
                if (generation == expected && activeOwner === owner) {
                    mutableStatus.value = EnrollmentStatus.IDLE
                    mutableErrorMessage.value = null
                    mutableNeedsSmsSelection.value = false
                }
            }
            throw cancelled
        } finally {
            synchronized(ownershipLock) { if (activeOwner === owner) activeOwner = null }
        }
    }

    /**
     * Automated resolution triggered by entering the driver's phone number and full name.
     * Queries BarPro /api/v1/otp/resolve-driver, parses credentials, stages to Keystore,
     * and atomically activates forwarding.
     */
    suspend fun resolveDriverConfiguration(driverPhone: String, baseUrl: String? = null, driverName: String = "") = owned { owner ->
        try {
            require(role == "DRIVER") { "تنها در حالت راننده (DRIVER) امکان فعال‌سازی وجود دارد." }
            val cleanPhone = SmsParser.normalizePhoneNumber(driverPhone)
            require(cleanPhone.isEmpty() || cleanPhone.matches(Regex("09[0-9]{9}"))) {
                "شماره موبایل وارد شده معتبر نمی‌باشد (فرمت صحیح: 09xxxxxxxxx)"
            }

            val current = getConfig()
            writeOwned(owner) {
                mutableErrorMessage.value = null
                mutableNeedsSmsSelection.value = false
                failureRetryable = true
            }
            writeOwned(owner) { mutableStatus.value = EnrollmentStatus.ACTIVATING }
            val staged = writeOwned(owner) { loadStage() }
            if (staged != null) {
                val pending = EnrollmentContract.parseCredentials(staged, EnrollmentContract.trustedServer(defaultEndpoint))
                if (cleanPhone.isEmpty() || pending.driverPhone == cleanPhone) {
                    activatePending(owner, driverName)
                    return@owned
                }
                writeOwned(owner) { stage(null) }
            }
            val effectiveToken = current.let { it.forwarderSecret.ifBlank { it.authHeaderValue } }
            val existingPhone = SmsParser.normalizePhoneNumber(current.driverPhone)
            val retained = cleanPhone.isEmpty() && driverName.isBlank() && EnrollmentContract.isEnrolledDriver(current)
            val requestedPhone = if (retained) existingPhone else cleanPhone
            val resolution = resolveWithCarrierRecovery(owner, requestedPhone,
                effectiveToken.takeIf { requestedPhone.isNotEmpty() && requestedPhone == existingPhone }.orEmpty())
            val credential = resolution.credentials
            if (retained && !resolution.carrierProof) {
                // Read-only authentication of a working profile preserves consent, flags and modem cadence.
                writeOwned(owner) { mutableStatus.value = EnrollmentStatus.ACTIVE }
                return@owned
            }
            require(cleanPhone.isEmpty() || credential.driverPhone == cleanPhone) { "پاسخ فعال‌سازی متعلق به این شماره نیست." }
            EnrollmentContract.requireNameMatch(credential, driverName)
            // Pending secrets use Keystore storage; Room stays untouched until activation
            writeOwned(owner) { stage(credential.document()) }
            activatePending(owner, driverName, validateCached = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (required: BootstrapNeedsPermissions) {
            writeOwned(owner) {
                mutableErrorMessage.value = required.message
                mutableStatus.value = EnrollmentStatus.NEEDS_PERMISSIONS
            }
        } catch (_: BootstrapPending) {
            writeOwned(owner) {
                mutableErrorMessage.value = "تأیید پیامک توسط هاب هنوز دریافت نشده است؛ می‌توانید دوباره تلاش کنید."
                mutableStatus.value = EnrollmentStatus.FAILED
                failureRetryable = true
            }
        } catch (error: Exception) {
            writeOwned(owner) {
                mutableNeedsSmsSelection.value = error is BootstrapSmsNotSent && error.needsSmsSelection
                failureRetryable = !mutableNeedsSmsSelection.value && error !is DriverNameMismatchException &&
                    !(error is BootstrapHttpException && error.code in setOf(400, 401, 403, 422))
                mutableErrorMessage.value = error.message ?: "خطا در استعلام مشخصات راننده از سرور"
                mutableStatus.value = EnrollmentStatus.FAILED
            }
        }
    }

    suspend fun receive(value: String) = owned { owner ->
        val link = try {
            EnrollmentContract.parseLink(value, defaultEndpoint)
        } catch (error: Exception) {
            writeOwned(owner) {
                mutableErrorMessage.value = error.message ?: "لینک فعال‌سازی نامعتبر است"
                mutableStatus.value = EnrollmentStatus.FAILED
            }
            return@owned
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
        if (!attempted.add(hash)) return@owned
        try {
            require(role == "DRIVER")
            writeOwned(owner) {
                mutableErrorMessage.value = null
                mutableStatus.value = EnrollmentStatus.ACTIVATING
            }
            val credential = redeem(link, deviceId())
            // Pending secrets use the same Keystore storage; Room and the working config stay untouched.
            writeOwned(owner) { stage(credential.document()) }
            activatePending(owner, validateCached = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            writeOwned(owner) {
                mutableErrorMessage.value = error.message
                mutableStatus.value = EnrollmentStatus.FAILED
            }
        }
    }

    suspend fun reset(onRevoked: () -> Unit = {}, onReset: () -> Unit = {}) {
        val expected = cancelActiveOperation()
        withGeneration(expected, onRevoked)
        owned(expected) { owner ->
            val previous = getConfig()
            writeOwned(owner) { Unit }
            // The explicit stop changes readiness/consent, while private identity remains available for authenticated rediscovery.
            saveConfig(previous.copy(setupCompleted = false, isMasterEnabled = false, userConsentGiven = false))
            writeOwned(owner) {
                stage(null)
                onReset()
                mutableStatus.value = EnrollmentStatus.IDLE
                mutableErrorMessage.value = null
                mutableNeedsSmsSelection.value = false
                failureRetryable = true
            }
        }
    }

    suspend fun resume(driverName: String = "") = owned { owner ->
        try {
            if (role == "DRIVER" && writeOwned(owner) { loadStage() } != null) activatePending(owner, driverName)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (required: BootstrapNeedsPermissions) {
            writeOwned(owner) {
                mutableErrorMessage.value = required.message
                mutableStatus.value = EnrollmentStatus.NEEDS_PERMISSIONS
            }
        } catch (error: Exception) {
            writeOwned(owner) {
                mutableErrorMessage.value = error.message
                mutableStatus.value = EnrollmentStatus.FAILED
                failureRetryable = error !is DriverNameMismatchException
            }
        }
    }

    private class CredentialResolution(val credentials: EnrollmentCredentials, val carrierProof: Boolean)

    private suspend fun resolveWithCarrierRecovery(owner: Owner, phone: String, token: String): CredentialResolution {
        writeOwned(owner) { Unit }
        val id = deviceId()
        val resolution = try {
            if (token.isNotBlank()) CredentialResolution(resolve(phone, id, token), false)
            else if (bootstrap != null) CredentialResolution(bootstrap.invoke(phone, id), true)
            else CredentialResolution(resolve(phone, id, token), false)
        } catch (required: ActivationRequiredException) {
            // Revocation closes only the rejected stage; the private working profile remains intact.
            writeOwned(owner) { stage(null) }
            CredentialResolution(bootstrap?.invoke(phone, id) ?: throw required, true)
        }
        writeOwned(owner) { Unit }
        require(phone.isEmpty() || resolution.credentials.driverPhone == phone) { "پاسخ فعال‌سازی متعلق به این شماره نیست." }
        return resolution
    }

    private suspend fun activatePending(owner: Owner, requestedName: String = "", validateCached: Boolean = true) {
        val document = writeOwned(owner) { loadStage() } ?: return
        val pending = EnrollmentContract.parseCredentials(document, EnrollmentContract.trustedServer(defaultEndpoint))
        val credentials = if (validateCached) {
            writeOwned(owner) { mutableStatus.value = EnrollmentStatus.ACTIVATING }
            // Authenticate the candidate's own phone/token, never an unrelated retained profile's token.
            resolveWithCarrierRecovery(owner, pending.driverPhone, pending.webhookToken).credentials.also {
                EnrollmentContract.requireNameMatch(it, requestedName)
                writeOwned(owner) { stage(it.document()) }
            }
        } else pending
        EnrollmentContract.requireNameMatch(credentials, requestedName)
        if (!permissionsGranted()) {
            writeOwned(owner) { mutableStatus.value = EnrollmentStatus.NEEDS_PERMISSIONS }
            return
        }
        val previous = getConfig()
        writeOwned(owner) { Unit }
        try {
            saveConfig(credentials.applyTo(previous, deviceId()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            writeOwned(owner) { Unit }
            saveConfig(previous)
            throw error
        }
        writeOwned(owner) {
            mutableStatus.value = EnrollmentStatus.ACTIVE
            mutableErrorMessage.value = null
        }
        try { writeOwned(owner) { stage(null) } } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A retained stage can be recovered; the validated configuration is already installed.
        }
        // Activation succeeded even if Android delays the service or modem rejects this health SMS.
        try {
            writeOwned(owner) { Unit }
            onActivated()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Scheduled maintenance retries liveness; do not misreport preserved old configuration.
        }
    }
}
