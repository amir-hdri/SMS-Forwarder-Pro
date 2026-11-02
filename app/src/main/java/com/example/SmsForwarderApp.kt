package com.example

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import com.example.crypto.SecureStorageManager
import com.example.data.local.AppDatabase
import com.example.data.repository.SmsForwardRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SmsForwarderApp : Application(), Configuration.Provider {

    val repository: SmsForwardRepository by lazy {
        SmsForwardRepository.getInstance(this)
    }

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Offload database warm-up, crypto salt generation, and worker scheduling
        // to Dispatchers.IO to prevent Main Thread blocking and eliminate cold-start ANR.
        appScope.launch {
            // Recovery must not depend on successful configuration or crypto reads.
            runCatching { com.example.service.CleanupWorker.schedule(this@SmsForwarderApp) }
            runCatching { com.example.service.MaintenanceWorker.schedule(this@SmsForwarderApp) }
            runCatching { com.example.service.SmsSyncWorker.enqueueBatchSync(this@SmsForwarderApp) }
            runCatching { com.example.enrollment.DriverBootstrapWorker.enqueue(this@SmsForwarderApp) }
            runCatching { com.example.enrollment.HubInstallWorker.enqueue(this@SmsForwarderApp) }
            try {
                AppDatabase.getDatabase(this@SmsForwarderApp)
                SecureStorageManager.getInstance(this@SmsForwarderApp).getOrCreateSalt()
                SmsForwardRepository.getInstance(this@SmsForwarderApp)
            } catch (e: Exception) {
                Log.w("SmsForwarderApp", "Background warm-up failed: ${e.message}")
            }

            try {
                val repository = SmsForwardRepository.getInstance(this@SmsForwarderApp)
                val config = repository.getConfig()
                if (config.isMasterEnabled && config.userConsentGiven) {
                    com.example.service.HeartbeatAlarmManager.scheduleNext(this@SmsForwarderApp, config.healthCheckIntervalMinutes)
                } else {
                    com.example.service.HeartbeatAlarmManager.cancel(this@SmsForwarderApp)
                }
            } catch (e: Exception) {
                Log.w("SmsForwarderApp", "Failed to schedule background workers: ${e.message}")
            }
        }

        try {
            com.example.service.ServerHealthNotifier.ensureChannel(this)
        } catch (e: Exception) {
            Log.w("SmsForwarderApp", "Failed to ensure notification channel: ${e.message}")
        }
    }
}
