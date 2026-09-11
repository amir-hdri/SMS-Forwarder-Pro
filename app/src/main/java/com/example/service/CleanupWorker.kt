package com.example.service

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.local.AppDatabase
import java.util.concurrent.TimeUnit

/**
 * Enterprise WorkManager Periodic Worker implementing retention policy for SMS logs.
 * Deletes ForwardLog entities older than 7 days that have status 'SUCCESS'
 * to preserve device storage.
 */
class CleanupWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val database = AppDatabase.getDatabase(applicationContext)
            val sevenDaysMillis = TimeUnit.DAYS.toMillis(7)
            val cutoffTimestamp = System.currentTimeMillis() - sevenDaysMillis

            val deletedCount = database.forwardLogDao().deleteOldSuccessfulLogs(cutoffTimestamp)
            Log.i(
                TAG,
                "Retention cleanup policy executed: purged $deletedCount successful forward logs older than 7 days."
            )
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute retention cleanup worker: ${e.message}", e)
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "CleanupWorker"
        const val WORK_NAME = "periodic_sms_cleanup_work"

        /**
         * Schedules the cleanup worker to run periodically (e.g. daily) when battery is not low.
         * Uses ExistingPeriodicWorkPolicy.KEEP to prevent duplicate schedules.
         */
        fun schedule(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()

                val cleanupRequest = PeriodicWorkRequestBuilder<CleanupWorker>(1, TimeUnit.DAYS)
                    .setConstraints(constraints)
                    .addTag("retention_cleanup")
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    cleanupRequest
                )
                Log.i(TAG, "Enqueued periodic 24-hour retention cleanup worker with ExistingPeriodicWorkPolicy.KEEP")
            } catch (e: Exception) {
                Log.w(TAG, "WorkManager schedule skipped or failed: ${e.message}")
            }
        }
    }
}
