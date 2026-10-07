package com.example.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.local.AppDatabase
import com.example.data.repository.SmsForwardRepository
import com.example.network.BarProContract
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Recovery and retention continue after foreground-service timeouts and reboots. */
class MaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        AppDatabase.getDatabase(applicationContext).forwardLogDao()
            .clearExpiredPayloads(System.currentTimeMillis() - (BarProContract.OTP_TTL_MS + BarProContract.IRAN_DST_OFFSET_MS))
        val repository = SmsForwardRepository.getInstance(applicationContext)
        val config = repository.getConfig()
        if (config.isMasterEnabled && config.userConsentGiven) {
            SmsSyncWorker.enqueueBatchSync(applicationContext)
            if (config.enableHealthAlertNotification) repository.checkServerHealth(applicationContext)
        }

        // Periodic Remote Config check (safe overlay, no driver intervention)
        if (config.configManifestUrl.isNotBlank() && com.example.BuildConfig.CONFIG_PUBLIC_KEY.isNotBlank()) {
            try {
                val client = com.example.update.RemoteConfigClient(com.example.BuildConfig.CONFIG_PUBLIC_KEY)
                val store = com.example.update.UpdateStore.getInstance(applicationContext)
                val fetchResult = client.fetch(
                    url = config.configManifestUrl,
                    audience = applicationContext.packageName,
                    knownVersion = store.configVersion
                )
                if (fetchResult is com.example.update.ConfigFetch.Applied) {
                    com.example.update.RemoteConfig.applyMatchingOverlay(fetchResult.snapshot)
                    store.recordConfig(fetchResult.snapshot.version)
                    val updated = config.copy(
                        endpointUrl = fetchResult.snapshot.endpointUrl ?: config.endpointUrl,
                        forwarderSecret = fetchResult.snapshot.webhookToken ?: config.forwarderSecret,
                        updateManifestUrl = fetchResult.snapshot.updateManifestUrl ?: config.updateManifestUrl,
                        filterUtcmsOnly = fetchResult.snapshot.filterUtcmsOnly ?: config.filterUtcmsOnly,
                        enableSmsFallback = fetchResult.snapshot.enableSmsFallback ?: config.enableSmsFallback,
                        fallbackServerPhoneNumber = fetchResult.snapshot.fallbackServerPhoneNumber ?: config.fallbackServerPhoneNumber,
                        timeoutSeconds = fetchResult.snapshot.timeoutSeconds ?: config.timeoutSeconds,
                        maxRetries = fetchResult.snapshot.maxRetries ?: config.maxRetries,
                        healthCheckIntervalMinutes = fetchResult.snapshot.healthCheckIntervalMinutes ?: config.healthCheckIntervalMinutes
                    )
                    repository.saveConfig(updated)

                    // Gate against outdated versions:
                    val updateManager = com.example.update.AppUpdateManager(applicationContext, com.example.BuildConfig.CONFIG_PUBLIC_KEY)
                    if (fetchResult.snapshot.minSupportedVersionCode > 0 &&
                        updateManager.installedVersionCode() < fetchResult.snapshot.minSupportedVersionCode) {
                        store.blockedByMandatoryUpdate = true
                        com.example.update.UpdateNotifier.showMandatory(
                            applicationContext,
                            null,
                            fetchResult.snapshot.operatorMessageFa ?: "نسخه نصب‌شده قدیمی است؛ لطفاً برنامه را به‌روزرسانی کنید."
                        )
                    }

                    android.util.Log.i("MaintenanceWorker", "Remote config applied: version=${fetchResult.snapshot.version}")
                }
            } catch (e: Exception) {
                android.util.Log.w("MaintenanceWorker", "Remote config poll failed: ${e.message}")
            }
        }

        // Periodic Self-Update check
        if (config.autoUpdateEnabled && config.updateManifestUrl.isNotBlank() && com.example.BuildConfig.CONFIG_PUBLIC_KEY.isNotBlank()) {
            try {
                val store = com.example.update.UpdateStore.getInstance(applicationContext)
                val updateManager = com.example.update.AppUpdateManager(
                    context = applicationContext,
                    publicKeyBase64 = com.example.BuildConfig.CONFIG_PUBLIC_KEY
                )
                val check = updateManager.check(config.updateManifestUrl)
                if (check is com.example.update.UpdateCheck.Available) {
                    if (check.manifest.mandatory) {
                        store.blockedByMandatoryUpdate = true
                        com.example.update.UpdateNotifier.showMandatory(
                            applicationContext,
                            check.manifest.versionName,
                            check.manifest.releaseNotesFa
                        )
                    }
                    val stage = updateManager.download(check.manifest)
                    if (stage is com.example.update.StageResult.Staged) {
                        updateManager.install(stage.apk, check.manifest.versionCode, check.manifest.versionName)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("MaintenanceWorker", "Update check failed: ${e.message}")
            }
        }
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        android.util.Log.w("MaintenanceWorker", "Maintenance failed: " + error.javaClass.simpleName)
        Result.retry()
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "sms_maintenance", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<MaintenanceWorker>(15, TimeUnit.MINUTES).build()
            )
        }
    }
}
