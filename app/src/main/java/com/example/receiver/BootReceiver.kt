package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.repository.SmsForwardRepository
import com.example.service.HeartbeatAlarmManager
import com.example.service.MaintenanceWorker
import com.example.service.SmsForwarderService
import com.example.service.SmsSyncWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON", Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val app = context.applicationContext
        HeartbeatAlarmManager.scheduleNext(app)
        runCatching { MaintenanceWorker.schedule(app) }
        runCatching { SmsSyncWorker.enqueueBatchSync(app) }
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(20_000L) {
                    val config = SmsForwardRepository.getInstance(app).getConfig()
                    if (!config.isMasterEnabled || !config.userConsentGiven) {
                        HeartbeatAlarmManager.cancel(app)
                        return@withTimeout
                    }
                    HeartbeatAlarmManager.scheduleNext(app, config.healthCheckIntervalMinutes)
                    runCatching { SmsForwarderService.startService(app) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("BootReceiver", "Boot recovery deferred: ${error.javaClass.simpleName}")
            } finally { pendingResult?.finish() }
        }
    }
}
