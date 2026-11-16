package com.example.enrollment

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.BuildConfig
import com.example.data.model.ForwardConfig
import com.example.data.repository.SmsForwardRepository
import com.example.network.BarProContract
import com.example.network.ManagedTransport
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest

/** Only SmsReceiver enqueues this work; notification text cannot assert a carrier origin. */
class HubBootstrapWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (BuildConfig.APP_ROLE != "HUB") return Result.failure()
        if (System.currentTimeMillis() >= inputData.getLong("expires", 0)) return Result.failure()
        val origin = inputData.getString("origin") ?: return Result.failure()
        val nonce = inputData.getString("nonce") ?: return Result.failure()
        return try {
            val repository = SmsForwardRepository.getInstance(applicationContext)
            val config = repository.getConfig()
            if (!config.isMasterEnabled || !config.userConsentGiven) return Result.failure()
            if (com.example.network.CellularEgress.isVpnActive(applicationContext)) {
                val egress = com.example.network.CellularEgress.acquire(applicationContext, timeoutMs = 4000L)
                if (egress != null) {
                    egress.use { session ->
                        if (!hasCurrentAuthority(repository, config)) return Result.failure()
                        BootstrapClient(EnrollmentContract.trustedServer(config.endpointUrl), session.bind(okhttp3.OkHttpClient()))
                            .prove(origin, nonce, BarProContract.token(config))
                    }
                    return Result.success()
                }
            }
            if (!hasCurrentAuthority(repository, config)) return Result.failure()
            BootstrapClient(EnrollmentContract.trustedServer(config.endpointUrl))
                .prove(origin, nonce, BarProContract.token(config))
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: BootstrapHttpException) {
            if (error.code in setOf(401, 403, 410, 422)) Result.failure() else Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    /** Network acquisition can suspend while the operator stops forwarding or replaces the private Hub profile. */
    private suspend fun hasCurrentAuthority(repository: SmsForwardRepository, expected: ForwardConfig): Boolean {
        val current = repository.getConfig()
        return current.isMasterEnabled && current.userConsentGiven &&
            current.deviceIdentifier == expected.deviceIdentifier &&
            BarProContract.token(current) == BarProContract.token(expected) &&
            ManagedTransport.endpoint(current.endpointUrl) == ManagedTransport.endpoint(expected.endpointUrl)
    }

    companion object {
        fun enqueue(context: Context, origin: String, message: String): Operation? {
            val proof = BootstrapSms.parse(origin, message) ?: return null
            // A typed-phone challenge may arrive first from the wrong SIM.
            // Preserve each actual carrier origin independently, while the
            // same normalized origin/nonce remains an idempotent handoff.
            val id = MessageDigest.getInstance("SHA-256").digest("${proof.first}\n${proof.second}".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val request = OneTimeWorkRequestBuilder<HubBootstrapWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf("origin" to proof.first, "nonce" to proof.second,
                    "expires" to System.currentTimeMillis() + 900_000)).build()
            return WorkManager.getInstance(context).enqueueUniqueWork("hub-bootstrap-$id", ExistingWorkPolicy.KEEP, request)
        }
    }
}
