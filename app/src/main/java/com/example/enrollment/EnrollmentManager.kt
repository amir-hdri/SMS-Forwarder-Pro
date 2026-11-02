package com.example.enrollment

import com.example.data.model.ForwardConfig
import com.example.utils.SmsParser
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
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
    private val resolve: suspend (phone: String, deviceId: String) -> EnrollmentCredentials = { phone, devId ->
        EnrollmentClient().resolve(phone, EnrollmentContract.trustedServer(defaultEndpoint), devId, getConfig().let { it.forwarderSecret.ifBlank { it.authHeaderValue } })
    },
    private val bootstrap: (suspend (String, String) -> EnrollmentCredentials)? = null
) {
    private val mutex = Mutex()
    private val attempted = mutableSetOf<String>()
    private val mutableStatus = MutableStateFlow(EnrollmentStatus.IDLE)
    val status = mutableStatus.asStateFlow()
    private val mutableErrorMessage = MutableStateFlow<String?>(null)
    val errorMessage = mutableErrorMessage.asStateFlow()
    private val mutableNeedsSmsSelection = MutableStateFlow(false)
    val needsSmsSelection = mutableNeedsSmsSelection.asStateFlow()
    var failureRetryable: Boolean = true
        private set

    /**
     * Automated resolution triggered by entering the driver's phone number.
     * Queries BarPro /api/v1/otp/resolve-driver, parses credentials, stages to Keystore,
     * and atomically activates forwarding.
     */
    suspend fun resolveDriverConfiguration(driverPhone: String, baseUrl: String? = null) = mutex.withLock {
        try {
            require(role == "DRIVER") { "تنها در حالت راننده (DRIVER) امکان فعال‌سازی وجود دارد." }
            val cleanPhone = SmsParser.normalizePhoneNumber(driverPhone)
            require(cleanPhone.isEmpty() || cleanPhone.matches(Regex("09[0-9]{9}"))) {
                "شماره موبایل وارد شده معتبر نمی‌باشد (فرمت صحیح: 09xxxxxxxxx)"
            }

            mutableErrorMessage.value = null
            mutableNeedsSmsSelection.value = false
            failureRetryable = true
            if (cleanPhone.isEmpty() && EnrollmentContract.isEnrolledDriver(getConfig())) {
                mutableStatus.value = EnrollmentStatus.ACTIVE
                return@withLock
            }
            mutableStatus.value = EnrollmentStatus.ACTIVATING

            val id = deviceId()
            val credential = try {
                if ((cleanPhone.isEmpty() || getConfig().let { it.forwarderSecret.ifBlank { it.authHeaderValue } }.isBlank()) && bootstrap != null)
                    bootstrap.invoke(cleanPhone, id)
                else resolve(cleanPhone, id)
            } catch (required: ActivationRequiredException) {
                bootstrap?.invoke(cleanPhone, id) ?: throw required
            }
            require(cleanPhone.isEmpty() || credential.driverPhone == cleanPhone) { "پاسخ فعال‌سازی متعلق به این شماره نیست." }
            // Pending secrets use Keystore storage; Room stays untouched until activation
            stage(credential.document())
            activatePending()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (required: BootstrapNeedsPermissions) {
            mutableErrorMessage.value = required.message
            mutableStatus.value = EnrollmentStatus.NEEDS_PERMISSIONS
        } catch (_: BootstrapPending) {
            mutableErrorMessage.value = null
            mutableStatus.value = EnrollmentStatus.ACTIVATING
        } catch (error: Exception) {
            mutableNeedsSmsSelection.value = error is BootstrapSmsNotSent && error.needsSmsSelection
            failureRetryable = !mutableNeedsSmsSelection.value &&
                !(error is BootstrapHttpException && error.code in setOf(400, 401, 403, 422))
            mutableErrorMessage.value = error.message ?: "خطا در استعلام مشخصات راننده از سرور"
            mutableStatus.value = EnrollmentStatus.FAILED
        }
    }

    suspend fun receive(value: String) = mutex.withLock {
        try {
            require(role == "DRIVER")
            val link = EnrollmentContract.parseLink(value, defaultEndpoint)
            val hash = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
                .joinToString("") { "%02x".format(it) }
            if (!attempted.add(hash)) return@withLock
            mutableStatus.value = EnrollmentStatus.ACTIVATING
            val credential = redeem(link, deviceId())
            // Pending secrets use the same Keystore storage; Room and the working config stay untouched.
            stage(credential.document())
            activatePending()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableErrorMessage.value = error.message
            mutableStatus.value = EnrollmentStatus.FAILED
        }
    }

    suspend fun resume() = mutex.withLock {
        try {
            if (role == "DRIVER" && loadStage() != null) activatePending()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableErrorMessage.value = error.message
            mutableStatus.value = EnrollmentStatus.FAILED
        }
    }

    private suspend fun activatePending() {
        val document = loadStage() ?: return
        val credentials = EnrollmentContract.parseCredentials(document, EnrollmentContract.trustedServer(defaultEndpoint))
        if (!permissionsGranted()) {
            mutableStatus.value = EnrollmentStatus.NEEDS_PERMISSIONS
            return
        }
        val previous = getConfig()
        try {
            saveConfig(credentials.applyTo(previous, deviceId()))
        } catch (error: Exception) {
            saveConfig(previous)
            throw error
        }
        mutableStatus.value = EnrollmentStatus.ACTIVE
        mutableErrorMessage.value = null
        try { stage(null) } catch (_: Exception) {
            // A retained stage can be recovered; the validated configuration is already installed.
        }
        // Activation succeeded even if Android delays the service or modem rejects this health SMS.
        try {
            onActivated()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Scheduled maintenance retries liveness; do not misreport preserved old configuration.
        }
    }
}
