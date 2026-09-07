package com.example.service

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.data.model.ForwardStatus
import com.example.data.repository.SmsForwardRepository
import com.example.utils.LogSanitizer
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Enterprise WorkManager Worker implementing the Transactional Outbox Pattern for SMS forwarding.
 * Features:
 * 1. Consumes log ID from Room outbox (avoiding payload bloat in WorkManager DB).
 * 2. Transmits SMS using configured HTTPS security and OkHttp client.
 * 3. Updates Room entity to SUCCESS or FAILED.
 * 4. Error classification: Fail-fast (Result.failure()) on 401 Unauthorized, 400 Bad Request,
 *    and other 4xx client errors; Exponential backoff retry (Result.retry()) on 5xx or IOExceptions.
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
                val log = repository.getLogById(logId)
                if (log == null) {
                    Log.w(TAG, "Worker executed for non-existent log ID: $logId")
                    return Result.failure()
                }

                // If already forwarded successfully, complete
                if (log.status == ForwardStatus.SUCCESS) {
                    return Result.success()
                }

                // Transmit pending log through repository outbox processor
                val result = repository.transmitPendingLog(logId)

                if (result.isSuccess) {
                    Log.i(TAG, "Outbox SMS transmission succeeded for log ID: $logId")
                    Result.success()
                } else {
                    val httpCode = result.httpStatusCode
                    Log.w(
                        TAG,
                        LogSanitizer.sanitize(
                            "Outbox transmission failed for log ID: $logId | HTTP: $httpCode | Error: ${result.errorMessage}"
                        )
                    )

                    // Strict Error Classification:
                    // If the server returns HTTP 400 Bad Request or 401 Unauthorized (e.g. invalid secret/payload),
                    // return Result.failure() immediately (DO NOT RETRY).
                    if (httpCode != null && (httpCode == 400 || httpCode == 401 || httpCode in 400..499)) {
                        Log.e(TAG, "Non-retryable client error ($httpCode: ${result.errorMessage}). Marking worker as failed immediately.")
                        Result.failure()
                    } else if (httpCode != null && httpCode >= 500) {
                        // 5xx Server Error -> Transient server error, retry with exponential backoff
                        Log.w(TAG, "Transient 5xx server error ($httpCode). Retrying attempt #${runAttemptCount + 1}")
                        if (runAttemptCount < MAX_RETRIES) {
                            Result.retry()
                        } else {
                            Result.failure()
                        }
                    } else {
                        // Network/IO issue during transmission -> Retry with exponential backoff
                        Log.w(TAG, "Network issue during transmission. Retrying attempt #${runAttemptCount + 1}")
                        if (runAttemptCount < MAX_RETRIES) {
                            Result.retry()
                        } else {
                            Result.failure()
                        }
                    }
                }
            } else {
                // Batch sync offline/failed messages
                val count = repository.syncOfflinePendingLogs()
                Result.success(workDataOf("synced_count" to count))
            }
        } catch (e: IOException) {
            Log.e(TAG, LogSanitizer.sanitize("IOException during SMS outbox sync: ${e.message}"), e)
            if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                Result.failure()
            }
        } catch (e: java.util.concurrent.TimeoutException) {
            Log.e(TAG, LogSanitizer.sanitize("TimeoutException during SMS outbox sync: ${e.message}"), e)
            if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                Result.failure()
            }
        } catch (e: Exception) {
            Log.e(TAG, LogSanitizer.sanitize("Unhandled exception during SMS sync: ${e.message}"), e)
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "SmsSyncWorker"
        const val KEY_LOG_ID = "log_id"
        private const val MAX_RETRIES = 5

        /**
         * Enqueues an outbox SMS transmission with guaranteed execution and exponential backoff.
         * Uses WorkManager.enqueueUniqueWork with ExistingWorkPolicy.KEEP to prevent duplicate workers.
         */
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
                .addTag("sms_forward_$logId")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "sms_sync_$logId",
                ExistingWorkPolicy.KEEP,
                request
            )
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
