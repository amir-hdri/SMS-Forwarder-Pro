package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R

object PermissionNotifier {

    const val CHANNEL_PERMISSION_ALERTS = "barpro_permission_channel"
    const val NOTIFICATION_ID_PERMISSION = 9015
    const val EXTRA_OPEN_PERMISSIONS = "extra_open_permissions"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_PERMISSION_ALERTS,
                "هشدارهای دسترسی سامانه بارپرو",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "نمایش اعلان در صورت لغو یا عدم دسترسی به پیامک و پس‌زمینه"
                enableVibration(true)
                setShowBadge(true)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun showMissingPermissionAlert(
        context: Context,
        missingPermissionTitle: String
    ) {
        ensureChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_PERMISSIONS, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            201,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_PERMISSION_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("نیازمند تایید دسترسی سامانه بارپرو")
            .setContentText("دسترسی «$missingPermissionTitle» فعال نیست. لطفاً جهت دریافت کدهای بارنامه تایید کنید.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "دسترسی «$missingPermissionTitle» در گوشی برقرار نیست. برای اینکه برنامه بتواند کدهای بارنامه را به سرور منتقل کند، وارد برنامه شده و این دسترسی را برقرار نمایید."
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID_PERMISSION, notification)
    }

    fun clearAlert(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID_PERMISSION)
    }
}
