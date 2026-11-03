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
import com.example.crypto.SecureStorageManager
import com.example.utils.PermissionHelper
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class HubInstallWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (BuildConfig.APP_ROLE != "HUB") return Result.failure()
        return try {
            val manager = HubInstallRuntime.get(applicationContext).manager
            manager.activate()
            if (manager.status.value != EnrollmentStatus.FAILED) Result.success()
            else if (manager.retryable && runAttemptCount < 6) Result.retry() else Result.failure()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (runAttemptCount < 6) Result.retry() else Result.failure() }
    }

    companion object {
        fun enqueue(context: Context) {
            if (BuildConfig.APP_ROLE != "HUB" || !PermissionHelper.areForwardingPermissionsGranted(context)) return
            val pending = runCatching { SecureStorageManager.getInstance(context).getPendingHubInstall() }.getOrNull()
            if (!BuildConfig.HAS_PRIVATE_HUB_PROFILE && pending == null) return
            val request = OneTimeWorkRequestBuilder<HubInstallWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("hub-private-install", ExistingWorkPolicy.KEEP, request)
        }
    }
}
