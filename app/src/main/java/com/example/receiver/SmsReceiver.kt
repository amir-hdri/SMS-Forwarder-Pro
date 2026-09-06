package com.example.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.model.ForwardStatus
import com.example.data.repository.SmsForwardRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION &&
            intent.action != "android.provider.Telephony.SMS_RECEIVED"
        ) {
            return
        }

        val messages: Array<SmsMessage>? = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting SMS messages from intent", e)
            null
        }

        if (messages.isNullOrEmpty()) {
            Log.w(TAG, "Received SMS intent but messages array is null or empty")
            return
        }

        // Group messages by sender in case multiple chunks arrived
        val sender = messages[0].displayOriginatingAddress ?: messages[0].originatingAddress ?: "Unknown"
        val fullMessageBody = StringBuilder()
        var timestamp = messages[0].timestampMillis
        if (timestamp <= 0) timestamp = System.currentTimeMillis()

        for (msg in messages) {
            msg.displayMessageBody?.let { fullMessageBody.append(it) }
                ?: msg.messageBody?.let { fullMessageBody.append(it) }
        }

        val slotIndex = intent.getIntExtra("slot", intent.getIntExtra("phone", intent.getIntExtra("simId", -1)))
        val subId = intent.getIntExtra("subscription", intent.getIntExtra("sub_id", -1))
        val simSlot = when {
            slotIndex >= 0 -> "SIM ${slotIndex + 1}"
            subId >= 0 -> "Sub #$subId"
            else -> "SIM 1"
        }

        val messageText = fullMessageBody.toString()
        Log.i(TAG, "Incoming SMS detected | Sender: $sender | Length: ${messageText.length} | Slot: $simSlot")

        val pendingResult = goAsync()
        val repository = SmsForwardRepository.getInstance(context)

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val wakeLock = powerManager?.newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK,
            "BarPro:SmsForwarderWakeLock"
        )?.apply {
            setReferenceCounted(false)
            acquire(15_000L)
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Processing incoming SMS from $sender...")
                val log = repository.processIncomingSms(
                    sender = sender,
                    messageBody = messageText,
                    receivedTimestamp = timestamp,
                    simSlot = simSlot
                )
                Log.i(TAG, "SMS processed successfully | LogID: ${log.id} | Status: ${log.status} | HTTP: ${log.httpStatusCode} | Extracted OTP: ${log.otpCode ?: "None"}")

                // Show notification if message was forwarded or failed and notifications are enabled
                val config = repository.getConfig()
                if (config.showForegroundNotification && (log.status == ForwardStatus.SUCCESS || log.status == ForwardStatus.FAILED)) {
                    showForwardNotification(context, sender, log.status)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception while processing incoming SMS from $sender", e)
            } finally {
                try {
                    if (wakeLock?.isHeld == true) {
                        wakeLock.release()
                    }
                } catch (_: Exception) {}
                pendingResult.finish()
            }
        }
    }

    private fun showForwardNotification(context: Context, sender: String, status: ForwardStatus) {
        val channelId = "sms_forward_events"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "اطلاعیه‌های انتقال پیامک BarPro",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "اطلاع‌رسانی وضعیت ارسال پیامک به سرور اختصاصی"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (status == ForwardStatus.SUCCESS) "پیامک با موفقیت ارسال شد" else "خطا در انتقال پیامک"
        val content = if (status == ForwardStatus.SUCCESS) {
            "پیامک رمزنگاری‌شده از $sender با موفقیت به وب‌سرویس تحویل داده شد."
        } else {
            "ارسال پیامک از $sender به سرور با شکست مواجه شد. گزارش‌ها را بررسی کنید."
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(com.example.R.drawable.ic_barpro_logo)
            .setContentTitle(title)
            .setContentText(content)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify((System.currentTimeMillis() % 10000).toInt(), notification)
    }
}
