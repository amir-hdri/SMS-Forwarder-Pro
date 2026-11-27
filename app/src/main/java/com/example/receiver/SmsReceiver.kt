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
import com.example.utils.LogSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

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
            Log.e(TAG, LogSanitizer.sanitize("Error extracting SMS messages from intent: ${e.message}"), e)
            null
        }

        if (messages.isNullOrEmpty()) {
            Log.w(TAG, LogSanitizer.sanitize("Received SMS intent but messages array is null or empty"))
            return
        }

        // Group messages by sender in case multiple chunks arrived
        val sender = messages[0].originatingAddress ?: "Unknown"
        val fullMessageBody = StringBuilder()
        val timestamp = com.example.network.ServerClock.resolveSmsArrivalTimestamp(
            context.applicationContext,
            messages[0].timestampMillis
        )

        for (msg in messages) {
            msg.displayMessageBody?.let { fullMessageBody.append(it) }
                ?: msg.messageBody?.let { fullMessageBody.append(it) }
        }

        var slotIndex = intent.getIntExtra("slot", intent.getIntExtra("phone", intent.getIntExtra("simId", -1)))
        val subId = intent.getIntExtra(android.telephony.SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
            intent.getIntExtra("subscription", intent.getIntExtra("sub_id", -1)))
        if (slotIndex < 0 && subId >= 0) {
            slotIndex = com.example.utils.DriverSmsSubscription.detectSlotIndex(context.applicationContext, subId)
        }
        val simSlot = com.example.utils.DriverSmsSubscription.label(slotIndex, subId)

        val messageText = fullMessageBody.toString().trim()
        if (messageText.isBlank()) {
            Log.w(TAG, LogSanitizer.sanitize("Received blank SMS body, skipping"))
            return
        }

        // Activation is separate from OTP capture. All parts must carry the same actual carrier origin.
        if (messageText.startsWith(com.example.enrollment.BootstrapSms.PREFIX)) {
            if (com.example.BuildConfig.APP_ROLE == "HUB" && messages.all { it.originatingAddress == sender }) {
                enqueueActivation(context.applicationContext, sender, messageText)
            }
            return
        }

        Log.i(TAG, LogSanitizer.sanitize("Incoming SMS detected | Sender: $sender | Length: ${messageText.length} | Slot: $simSlot"))

        val pendingResult = goAsync()

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val wakeLock = powerManager?.newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK,
            "BarPro:SmsForwarderWakeLock"
        )?.apply {
            setReferenceCounted(false)
            acquire(10_000L)
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(8_000L) {
                val repository = SmsForwardRepository.getInstance(context.applicationContext)
                Log.d(TAG, LogSanitizer.sanitize("Processing incoming SMS from $sender..."))
                val log = repository.processIncomingSms(
                    sender = sender,
                    messageBody = messageText,
                    receivedTimestamp = timestamp,
                    simSlot = simSlot
                )
                Log.i(TAG, LogSanitizer.sanitize("SMS processed successfully | LogID: ${log.id} | Status: ${log.status} | HTTP: ${log.httpStatusCode} | Extracted OTP: ${log.otpCode ?: "None"}"))

                // Show notification if message was forwarded, failed, or dropped due to secure storage failure
                val config = runCatching { repository.getConfig() }.getOrNull()
                val storageFailure = log.status == ForwardStatus.SKIPPED &&
                    log.errorMessage?.contains("ذخیره‌سازی امن") == true
                if ((config?.showForegroundNotification == true &&
                        (log.status == ForwardStatus.SUCCESS || log.status == ForwardStatus.FAILED)) ||
                    storageFailure) {
                    showForwardNotification(context, sender, log)
                }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.e(TAG, LogSanitizer.sanitize("Exception while processing incoming SMS from $sender: ${e.message}"), e)
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

    /** Hold the broadcast through WorkManager's database handoff, never the HTTP worker. */
    internal fun enqueueActivation(context: Context, origin: String, message: String) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                com.example.enrollment.HubBootstrapWorker.enqueue(context, origin, message)
                    ?.result?.get(8, TimeUnit.SECONDS)
            } catch (error: Exception) {
                Log.e(TAG, LogSanitizer.sanitize("Activation enqueue failed: ${error.javaClass.simpleName}"))
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showForwardNotification(context: Context, sender: String, log: com.example.data.model.ForwardLog) {
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

        val status = log.status
        val storageFailure = status == ForwardStatus.SKIPPED &&
            log.errorMessage?.contains("ذخیره‌سازی امن") == true
        val serverConfirmed = status == ForwardStatus.SUCCESS && (log.httpStatusCode ?: 0) in 200..299
        val title = when {
            status == ForwardStatus.SUCCESS -> "پیامک با موفقیت ارسال شد"
            storageFailure -> "توقف امنیتی فورواردر بارپرو"
            else -> "خطا در انتقال پیامک"
        }
        val content = when {
            serverConfirmed -> "ثبت پیامک در سرور تأیید شد."
            status == ForwardStatus.SUCCESS && log.httpStatusCode == null ->
                "تحویل پیامک به اپراتور تأیید شد؛ دریافت سرور تأیید نشده است."
            storageFailure -> log.errorMessage ?: "ذخیره‌سازی امن از کار افتاد؛ برنامه را باز کنید."
            else -> "انتقال پیامک انجام نشد؛ وضعیت صف و شبکه را بررسی کنید."
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(com.example.R.drawable.ic_barpro_logo)
            .setContentTitle(title)
            .setContentText(content)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val compatManager = androidx.core.app.NotificationManagerCompat.from(context)
        if (!compatManager.areNotificationsEnabled()) return
        try {
            compatManager.notify((System.currentTimeMillis() % 10000).toInt(), notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and notify call.
        }
    }
}
