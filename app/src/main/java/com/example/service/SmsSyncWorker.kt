package com.example.service

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.data.model.ForwardStatus
import com.example.data.repository.SmsForwardRepository
import java.util.concurrent.TimeUnit

/**
 * Robust WorkManager worker for guaranteed background transmission and retries
 * when device is offline or experiencing intermittent connectivity.
 */
class SmsSyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val logId = inputData.getLong(KEY_LOG_ID, -1L)
        val repository = SmsForwardRepository.getInstance(applicationContext)

        return try {
            if (logId != -1L) {
                val log = repository.getLogById(logId) ?: return Result.failure()
                if (log.status == ForwardStatus.SUCCESS) {
                    return Result.success()
                }

                val updated = repository.retryForwardLog(log)
                if (updated.status == ForwardStatus.SUCCESS) {
                    Result.success()
                } else {
                    if (runAttemptCount < 4) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                }
            } else {
                // Batch sync offline/failed messages
                val count = repository.syncOfflinePendingLogs()
                Result.success(workDataOf("synced_count" to count))
            }
        } catch (e: Exception) {
            if (runAttemptCount < 4) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    companion object {
        const val KEY_LOG_ID = "log_id"

        fun enqueue(context: Context, logId: Long) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<SmsSyncWorker>()
                .setInputData(workDataOf(KEY_LOG_ID to logId))
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }

        fun enqueueBatchSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<SmsSyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
