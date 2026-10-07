package com.example.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.R

/**
 * Driver-facing notifications for the update channel.
 *
 * Deliberately low-key: a silent update produces no notification at all, because the point of the
 * silent path is that the driver never has to think about it. Only the one-tap confirmation and a
 * blocking mandatory update interrupt them.
 */
object UpdateNotifier {

    const val CHANNEL_ID = "barpro_app_update"
    private const val ID_CONFIRM_NEEDED = 9301
    private const val ID_MANDATORY = 9302

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "به‌روزرسانی برنامه",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "اطلاع‌رسانی نسخه‌ی جدید برنامه فورواردر بارپرو"
            setShowBadge(true)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /**
     * The platform refused a silent install and wants the driver's confirmation. [confirmIntent]
     * comes from the installer itself; we can only surface it, because an app cannot launch an
     * activity from the background on Android 10+.
     */
    fun showConfirmNeeded(context: Context, confirmIntent: Intent, versionName: String?) {
        ensureChannel(context)
        confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getActivity(context, ID_CONFIRM_NEEDED, confirmIntent, flags)

        val subtitle = versionName?.let { "نسخه $it آماده نصب است." }
            ?: "نسخه‌ی جدید آماده نصب است."
        notify(
            context,
            ID_CONFIRM_NEEDED,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_barpro_logo)
                .setContentTitle("به‌روزرسانی فورواردر بارپرو")
                .setContentText("$subtitle برای تکمیل، یک بار تأیید کنید.")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "$subtitle برنامه حذف نمی‌شود و تنظیمات و مجوزها حفظ می‌شوند؛ " +
                            "فقط کافی است روی این پیام بزنید و گزینه‌ی نصب را تأیید کنید."
                    )
                )
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
        )
    }

    /** A release the operator marked mandatory: forwarding is paused until it is installed. */
    fun showMandatory(context: Context, versionName: String?, reasonFa: String?) {
        ensureChannel(context)
        val openApp = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getActivity(context, ID_MANDATORY, openApp, flags)

        val detail = reasonFa
            ?: "تا نصب این نسخه، ارسال کد تأیید به سرور متوقف می‌ماند."
        notify(
            context,
            ID_MANDATORY,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_barpro_logo)
                .setContentTitle("به‌روزرسانی ضروری" + (versionName?.let { " — نسخه $it" } ?: ""))
                .setContentText(detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                .setContentIntent(pending)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
        )
    }

    fun clearAll(context: Context) {
        NotificationManagerCompat.from(context).apply {
            cancel(ID_CONFIRM_NEEDED)
            cancel(ID_MANDATORY)
        }
    }

    private fun notify(context: Context, id: Int, notification: android.app.Notification) {
        val manager = NotificationManagerCompat.from(context)
        // POST_NOTIFICATIONS may be denied; notify() would throw rather than no-op.
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call; nothing useful to do.
        }
    }
}
