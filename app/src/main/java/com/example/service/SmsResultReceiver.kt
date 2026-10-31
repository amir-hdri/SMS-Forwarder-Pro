package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.repository.SmsForwardRepository
import com.example.utils.SmsDispatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Non-exported receiver for explicit per-attempt modem capabilities. */
class SmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val delivered = when (intent.action) {
            SmsDispatch.ACTION_SENT -> false
            SmsDispatch.ACTION_DELIVERED -> true
            else -> return
        }
        val logId = intent.getLongExtra(SmsDispatch.LOG_ID, -1)
        val attemptAt = intent.getLongExtra(SmsDispatch.ATTEMPT_AT, -1)
        val expected = "barpro-sms://${if (delivered) "delivered" else "sent"}/$logId/$attemptAt"
        if (logId <= 0 || attemptAt <= 0 || intent.dataString != expected) return
        val report = if (delivered) SmsDispatch.deliveryReport(intent) ?: return else null
        val code = resultCode
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(8_000) {
                    SmsForwardRepository.getInstance(context.applicationContext)
                        .handleSmsDispatchResult(logId, attemptAt, code, delivered, report)
                }
            } catch (cancelled: CancellationException) {
                // Durable in-flight state is recovered by the already-enqueued worker.
                throw cancelled
            } catch (_: Exception) {
                Log.w("SmsResultReceiver", "SMS result will be recovered from the durable outbox")
            } finally {
                pending.finish()
            }
        }
    }
}
