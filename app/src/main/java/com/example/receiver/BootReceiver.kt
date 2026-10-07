package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.repository.SmsForwardRepository
import com.example.service.SmsForwarderService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val pendingResult = goAsync()
            val repository = SmsForwardRepository.getInstance(context)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val config = repository.getConfig()
                    if (config.isMasterEnabled) {
                        // Android 15+ forbids starting dataSync foreground services from boot.
                        com.example.service.MaintenanceWorker.schedule(context)
                        com.example.service.SmsSyncWorker.enqueueBatchSync(context)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
