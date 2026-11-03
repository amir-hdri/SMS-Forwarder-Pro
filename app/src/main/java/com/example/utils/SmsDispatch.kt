package com.example.utils

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SmsMessage
import com.example.service.SmsResultReceiver

/** Explicit per-attempt capabilities; delivery reports need the framework's PDU fill-in. */
object SmsDispatch {
    const val ACTION_SENT = "com.example.service.SMS_SENT"
    const val ACTION_DELIVERED = "com.example.service.SMS_DELIVERED"
    const val LOG_ID = "log_id"
    const val ATTEMPT_AT = "attempt_at_ms"
    const val BOOTSTRAP_NONCE = "bootstrap_nonce"
    const val RESULT_TIMEOUT_MS = 45_000L
    const val MAX_ATTEMPTS = 5

    enum class DeliveryReport { DELIVERED, PENDING, FAILED, UNCONFIRMED }

    /** RESULT_OK reports transport of a status report, not its TP-status. */
    fun deliveryReport(intent: Intent): DeliveryReport? {
        val pdu = intent.getByteArrayExtra("pdu")?.takeIf { it.isNotEmpty() } ?: return null
        val format = intent.getStringExtra("format")?.takeIf { it == "3gpp" || it == "3gpp2" } ?: return null
        val message = try { SmsMessage.createFromPdu(pdu, format) } catch (_: RuntimeException) { null }
        if (message == null || !message.isStatusReportMessage) return null
        val status = message.status
        return if (format == "3gpp") when (status) {
            0x00 -> DeliveryReport.DELIVERED // TS 23.040: received by the SME.
            in 0x20..0x3f -> DeliveryReport.PENDING // SMSC is still retrying.
            in 0x40..0x7f -> DeliveryReport.FAILED // Permanent error or SMSC stopped retrying.
            else -> DeliveryReport.UNCONFIRMED // Forwarded/replaced/reserved is not a receipt.
        } else when {
            status == (2 shl 16) -> DeliveryReport.DELIVERED // C.S0015-B: delivered.
            ((status ushr 24) and 3) == 2 -> DeliveryReport.PENDING
            ((status ushr 24) and 3) == 3 -> DeliveryReport.FAILED
            else -> DeliveryReport.UNCONFIRMED
        }
    }

    private fun callbackIntent(context: Context, logId: Long, attemptAt: Long, delivered: Boolean): Intent {
        val identity = Uri.Builder().scheme("barpro-sms").authority(if (delivered) "delivered" else "sent")
            .appendPath(logId.toString()).appendPath(attemptAt.toString()).build()
        return Intent(context, SmsResultReceiver::class.java)
            .setAction(if (delivered) ACTION_DELIVERED else ACTION_SENT).setData(identity)
            .putExtra(LOG_ID, logId).putExtra(ATTEMPT_AT, attemptAt)
    }

    // ONE_SHOT and mutability are part of PendingIntent identity even for FLAG_NO_CREATE.
    private fun identityFlags(delivered: Boolean): Int = if (!delivered) {
        PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
    } else if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0

    fun resultIntent(context: Context, logId: Long, attemptAt: Long, delivered: Boolean): PendingIntent {
        require(logId > 0 && attemptAt > 0)
        return PendingIntent.getBroadcast(context, (logId xor attemptAt).toInt(),
            callbackIntent(context, logId, attemptAt, delivered),
            PendingIntent.FLAG_UPDATE_CURRENT or identityFlags(delivered))
    }

    private fun bootstrapIntent(context: Context, nonce: String, attemptAt: Long, delivered: Boolean): Intent =
        Intent(context, SmsResultReceiver::class.java)
            .setAction(if (delivered) ACTION_DELIVERED else ACTION_SENT)
            .setData(Uri.parse("barpro-bootstrap-sms://${if (delivered) "delivered" else "sent"}/$nonce/$attemptAt"))
            .putExtra(BOOTSTRAP_NONCE, nonce).putExtra(ATTEMPT_AT, attemptAt)

    fun bootstrapResultIntent(context: Context, nonce: String, attemptAt: Long, delivered: Boolean): PendingIntent {
        require(nonce.matches(Regex("[A-Za-z0-9_-]{43}")) && attemptAt > 0)
        return PendingIntent.getBroadcast(context, nonce.hashCode() xor attemptAt.toInt(),
            bootstrapIntent(context, nonce, attemptAt, delivered), PendingIntent.FLAG_UPDATE_CURRENT or identityFlags(delivered))
    }

    fun cancelBootstrapAttempt(context: Context, nonce: String, attemptAt: Long) {
        for (delivered in listOf(false, true)) {
            PendingIntent.getBroadcast(context, nonce.hashCode() xor attemptAt.toInt(),
                bootstrapIntent(context, nonce, attemptAt, delivered), PendingIntent.FLAG_NO_CREATE or identityFlags(delivered))?.cancel()
        }
    }

    fun errorMessage(result: Int): String = when (result) {
        SmsManager.RESULT_ERROR_NO_SERVICE -> "شبکه سیم‌کارت برای ارسال پیامک در دسترس نیست."
        SmsManager.RESULT_ERROR_RADIO_OFF -> "رادیوی سیم‌کارت خاموش است."
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "محدودیت ارسال پیامک سیم‌کارت اعمال شده است."
        else -> "ارسال یا تحویل پیامک توسط شبکه تأیید نشد؛ شارژ و پوشش سیم‌کارت را بررسی کنید."
    }

    fun isNetworkUnavailable(result: Int): Boolean = result in setOf(
        SmsManager.RESULT_ERROR_NO_SERVICE, SmsManager.RESULT_ERROR_RADIO_OFF, SmsManager.RESULT_ERROR_LIMIT_EXCEEDED)

    fun cancelAttempt(context: Context, logId: Long, attemptAt: Long) {
        for (delivered in listOf(false, true)) {
            PendingIntent.getBroadcast(context, (logId xor attemptAt).toInt(),
                callbackIntent(context, logId, attemptAt, delivered),
                PendingIntent.FLAG_NO_CREATE or identityFlags(delivered))?.cancel()
        }
    }
}
