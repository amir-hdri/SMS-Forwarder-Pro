package com.example.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.data.repository.SmsForwardRepository
import com.example.utils.SmsParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Redundant dual-capture NotificationListenerService for Android 11+
 * captures SMS notifications posted by default messaging apps (e.g. Google Messages, Samsung Messages)
 * ensuring zero dropped OTPs or waybill confirmations even under stringent background restrictions.
 */
class SmsNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val pkg = sbn.packageName ?: ""
        val isSmsApp = pkg.contains("messaging", ignoreCase = true) ||
                pkg.contains("mms", ignoreCase = true) ||
                pkg == "com.google.android.apps.messaging" ||
                pkg == "com.samsung.android.messaging" ||
                pkg == "com.android.mms"

        if (!isSmsApp) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: text

        val fullText = if (bigText.isNotBlank()) bigText else text
        if (fullText.isBlank()) return

        // Verify if it is UTCMS or BarPro related
        if (SmsParser.isUtcmsSms(title, fullText)) {
            val timestamp = if (sbn.postTime > 0) sbn.postTime else System.currentTimeMillis()
            val sender = title.ifBlank { "پیامک سیستم" }

            scope.launch {
                try {
                    val repository = SmsForwardRepository.getInstance(applicationContext)
                    repository.processIncomingSms(
                        sender = sender,
                        messageBody = fullText,
                        receivedTimestamp = timestamp,
                        simSlot = "Notification Listener"
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}
