package com.example.service

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.example.data.repository.SmsForwardRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class HeartbeatAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != HeartbeatAlarmManager.ACTION_HEARTBEAT_ALARM &&
            intent.action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) return
        val appContext = context.applicationContext
        // Rearm before Room/Keystore access; a successfully read stop cancels below.
        HeartbeatAlarmManager.scheduleNext(appContext)
        val pendingResult = goAsync()
        var wakeLock: PowerManager.WakeLock? = null
        try {
            val power = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BarPro:HeartbeatWakeLock")
            wakeLock?.acquire(25_000L)
        } catch (error: Exception) {
            Log.w(TAG, "Wake lock unavailable: ${error.javaClass.simpleName}")
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(20_000L) {
                    val repository = SmsForwardRepository.getInstance(appContext)
                    val config = repository.getConfig()
                    if (!config.isMasterEnabled || !config.userConsentGiven) {
                        HeartbeatAlarmManager.cancel(appContext)
                        Log.i(TAG, "Forwarder disabled; heartbeat cancelled")
                        return@withTimeout
                    }
                    HeartbeatAlarmManager.scheduleNext(appContext, config.healthCheckIntervalMinutes)
                    try {
                        SmsForwarderService.startService(appContext)
                    } catch (error: Exception) {
                        Log.d(TAG, "Foreground service deferred: ${error.javaClass.simpleName}")
                    }
                    repository.performHeartbeatAndCommandPoll()
                    Log.i(TAG, "Heartbeat work finished")
                }
            } catch (cancelled: CancellationException) {
                Log.w(TAG, "Heartbeat execution cancelled; next wakeup preserved")
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Heartbeat execution failed: ${error.javaClass.simpleName}")
            } finally {
                try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) { }
                pendingResult?.finish()
            }
        }
    }
    companion object { private const val TAG = "HeartbeatAlarmReceiver" }
}
