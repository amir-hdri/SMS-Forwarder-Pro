package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ServerHealthNotifier {

    const val CHANNEL_HEALTH_ALERTS = "barpro_server_health_channel"
    const val NOTIFICATION_ID_HEALTH_ALERT = 9002
    const val NOTIFICATION_ID_RECOVERY = 9003
    const val EXTRA_NAVIGATE_TAB = "extra_navigate_tab"
    const val TAB_SERVER = "SERVER"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_HEALTH_ALERTS,
                "هشدار وضعیت اتصال به سرور (BarPro Health)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "نمایش هشدار در صورت قطعی یا عدم پاسخگویی سرور وب‌هوک"
                enableVibration(true)
                setShowBadge(true)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun showDisconnectedAlert(
        context: Context,
        endpointUrl: String,
        consecutiveFailures: Int,
        errorMessage: String?,
        lastSuccessTimestamp: Long
    ) {
        ensureChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAVIGATE_TAB, TAB_SERVER)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            101,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val lastSuccessStr = if (lastSuccessTimestamp > 0) {
            timeFormatter.format(Date(lastSuccessTimestamp))
        } else {
            "ثبت نشده"
        }

        val reasonText = when {
            errorMessage.isNullOrBlank() -> "عدم دریافت پاسخ (Timeout / Network Error)"
            errorMessage.contains("Failed to connect", ignoreCase = true) -> "عدم امکان اتصال به سرور (Connection Refused)"
            errorMessage.contains("timeout", ignoreCase = true) -> "پایان مهلت زمان اتصال (Timeout)"
            errorMessage.contains("404", ignoreCase = true) -> "آدرس وب‌هوک یافت نشد (404 Not Found)"
            errorMessage.contains("401", ignoreCase = true) || errorMessage.contains("403", ignoreCase = true) -> "خطای احراز هویت توکن یا کلید API"
            errorMessage.contains("500", ignoreCase = true) || errorMessage.contains("502", ignoreCase = true) || errorMessage.contains("503", ignoreCase = true) -> "خطای داخلی سمت سرور ($errorMessage)"
            else -> errorMessage
        }

        val bigText = buildString {
            append("ارتباط برنامه BarPro با سرور مقصد قطع شده است.\n\n")
            append("🌐 آدرس سرور: $endpointUrl\n")
            append("⚠️ خطاهای متوالی: $consecutiveFailures بار\n")
            append("⏱ آخرین اتصال موفق: $lastSuccessStr\n")
            append("🔍 علت: $reasonText\n\n")
            append("جهت جلوگیری از تاخیر در فوروارد پیامک‌ها و استعلام OTP، لطفاً وضعیت سرور و اینترنت دستگاه را بررسی کنید.")
        }

        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val notification = NotificationCompat.Builder(context, CHANNEL_HEALTH_ALERTS)
            .setSmallIcon(R.drawable.ic_barpro_logo)
            .setContentTitle("⚠️ هشدار قطعی اتصال به سرور BarPro")
            .setContentText("سرور پاسخگو نیست ($consecutiveFailures خطای متوالی). جهت بررسی لمس کنید.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSound(alarmSound)
            .setAutoCancel(false)
            .setOngoing(true) // Keeps prominent until connection recovers or user interacts
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_manage,
                "بررسی وضعیت و تست اتصال",
                pendingIntent
            )
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID_HEALTH_ALERT, notification)
    }

    fun dismissDisconnectedAlert(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID_HEALTH_ALERT)
    }

    fun showRecoveryNotification(context: Context, endpointUrl: String) {
        ensureChannel(context)
        dismissDisconnectedAlert(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAVIGATE_TAB, TAB_SERVER)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            102,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_HEALTH_ALERTS)
            .setSmallIcon(R.drawable.ic_barpro_logo)
            .setContentTitle("✅ اتصال به سرور BarPro برقرار شد")
            .setContentText("ارتباط با وب‌هوک $endpointUrl با موفقیت بازیابی شد.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setTimeoutAfter(8000) // Auto dismiss after 8s
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID_RECOVERY, notification)
    }
}
