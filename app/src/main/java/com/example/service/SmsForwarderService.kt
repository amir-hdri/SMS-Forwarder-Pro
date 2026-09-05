package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.repository.SmsForwardRepository
import com.example.network.ServerHealthMonitor
import com.example.network.SmsForwarderClient

class SmsForwarderService : Service() {

    companion object {
        const val CHANNEL_ID = "sms_forwarder_foreground_channel"
        const val NOTIFICATION_ID = 9001
        const val ACTION_START = "ACTION_START_FORWARDING_SERVICE"
        const val ACTION_STOP = "ACTION_STOP_FORWARDING_SERVICE"

        fun startService(context: Context) {
            val intent = Intent(context, SmsForwarderService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, SmsForwarderService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
        }
    }

    private val client = SmsForwarderClient()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ServerHealthNotifier.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ServerHealthMonitor.stopPeriodicMonitoring(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val repository = SmsForwardRepository.getInstance(applicationContext)
        ServerHealthMonitor.startPeriodicMonitoring(
            context = applicationContext,
            configProvider = { repository.getConfig() },
            client = client,
            onPeriodicTick = {
                repository.performHeartbeatAndCommandPoll()
            }
        )

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        ServerHealthMonitor.stopPeriodicMonitoring(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "سرویس فوروارد BarPro",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "فعال نگه داشتن شنود و فوروارد پیامک و پایش سلامت سرور در پس‌زمینه"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("سرویس BarPro Forwarder فعال است")
            .setContentText("شنود پیامک‌ها و پایش خودکار سلامت سرور در پس‌زمینه")
            .setSmallIcon(R.drawable.ic_barpro_logo)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
