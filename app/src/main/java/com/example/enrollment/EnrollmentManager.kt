package com.example.enrollment

import com.example.data.model.ForwardConfig
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
    private val onActivated: suspend () -> Unit
) {
    private val mutex = Mutex()
    private val attempted = mutableSetOf<String>()
    private val mutableStatus = MutableStateFlow(EnrollmentStatus.IDLE)
    val status = mutableStatus.asStateFlow()

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
        } catch (_: Exception) {
            mutableStatus.value = EnrollmentStatus.FAILED
        }
    }

    suspend fun resume() = mutex.withLock {
        try {
            if (role == "DRIVER" && loadStage() != null) activatePending()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
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
