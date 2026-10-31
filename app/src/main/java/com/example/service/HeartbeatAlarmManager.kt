package com.example.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

object HeartbeatAlarmManager {
    private const val TAG = "HeartbeatAlarmManager"
    private const val REQUEST_CODE = 4001
    const val ACTION_HEARTBEAT_ALARM = "com.example.service.ACTION_HEARTBEAT_ALARM"

    fun scheduleNext(context: Context, intervalMinutes: Int? = null): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        val previousInterval = runCatching {
            context.getSharedPreferences("barpro_heartbeat_schedule", Context.MODE_PRIVATE).getInt("interval_minutes", 5)
        }.getOrDefault(5)
        val clampedMinutes = (intervalMinutes ?: previousInterval).coerceIn(1, 60)
        val triggerAtMillis = SystemClock.elapsedRealtime() + (clampedMinutes * 60 * 1000L)

        val intent = Intent(context, HeartbeatAlarmReceiver::class.java).apply {
            action = ACTION_HEARTBEAT_ALARM
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                var scheduled = false
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
                    try {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            triggerAtMillis,
                            pendingIntent
                        )
                        scheduled = true
                    } catch (secEx: SecurityException) {
                        Log.w(TAG, "Exact access unavailable; using idle-compatible wakeup")
                    }
                }
                if (!scheduled) {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                }
            } else {
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
            Log.d(TAG, "Heartbeat wakeup alarm scheduled in $clampedMinutes minutes")
            runCatching {
                context.getSharedPreferences("barpro_heartbeat_schedule", Context.MODE_PRIVATE)
                    .edit().putInt("interval_minutes", clampedMinutes).apply()
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule heartbeat wakeup: ${e.javaClass.simpleName}")
            return false
        }
    }

    fun cancel(context: Context) = runCatching {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return@runCatching
        val intent = Intent(context, HeartbeatAlarmReceiver::class.java).apply {
            action = ACTION_HEARTBEAT_ALARM
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.d(TAG, "Heartbeat wakeup alarm cancelled")
        }
    }.onFailure { Log.w(TAG, "Alarm cancellation failed: ${it.javaClass.simpleName}") }
}
