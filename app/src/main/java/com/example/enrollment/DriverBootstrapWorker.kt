package com.example.enrollment

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.BuildConfig
import com.example.utils.PermissionHelper
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Internet is needed only for the initial key-bound grant; normal Driver relaying remains GSM. */
class DriverBootstrapWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (BuildConfig.APP_ROLE != "DRIVER") return Result.failure()
        return try {
            val runtime = DriverActivationRuntime.get(applicationContext)
            runtime.activateAutomatically()
            when (runtime.enrollment.status.value) {
                EnrollmentStatus.ACTIVE, EnrollmentStatus.NEEDS_PERMISSIONS -> Result.success()
                else -> if (runtime.enrollment.failureRetryable && runAttemptCount < 6) Result.retry() else Result.failure()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (runAttemptCount < 6) Result.retry() else Result.failure() }
    }

    companion object {
        fun enqueue(context: Context) {
            if (BuildConfig.APP_ROLE != "DRIVER" || !PermissionHelper.areForwardingPermissionsGranted(context)) return
            val request = OneTimeWorkRequestBuilder<DriverBootstrapWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("driver-device-bootstrap", ExistingWorkPolicy.KEEP, request)
        }
    }
}
