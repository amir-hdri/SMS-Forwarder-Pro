package com.example.enrollment

import android.app.Activity
import android.content.Context
import com.example.crypto.SecureStorageManager
import com.example.utils.SmsDispatch
import org.json.JSONObject

/** Activation has its own encrypted attempt record; it never enters the credential-gated OTP outbox. */
object BootstrapSmsDispatch {
    const val PROOF_TIMEOUT_MS = 90_000L
    data class State(val nonce: String, val attemptAt: Long, val attempts: Int,
        val status: String, val retryAt: Long, val message: String?)

    @Synchronized fun state(context: Context, nonce: String): State? {
        val document = SecureStorageManager.getInstance(context).getBootstrapSmsAttempt() ?: return null
        val json = JSONObject(document)
        if (json.getString("nonce") != nonce) return null
        return State(nonce, json.getLong("attempt_at"), json.getInt("attempts"), json.getString("status"),
            json.optLong("retry_at"), json.optString("message").takeIf { it.isNotEmpty() })
    }

    @Synchronized fun send(context: Context, destination: String, nonce: String): Boolean {
        val previous = state(context, nonce)
        if (previous != null && System.currentTimeMillis() < previous.retryAt) {
            return previous.status != "failed"
        }
        if ((previous?.attempts ?: 0) >= SmsDispatch.MAX_ATTEMPTS)
            throw BootstrapSmsNotSent("تلاش‌های محدود پیامک تمام شده است؛ پس از پایان مهلت، برنامه خودکار دوباره بررسی می‌کند.")
        val selected = BootstrapSms.selectedSubscription(context)
        SecureStorageManager.getInstance(context).getBootstrapSmsAttempt()?.let {
            val old = JSONObject(it)
            if (old.getString("nonce") != nonce)
                SmsDispatch.cancelBootstrapAttempt(context, old.getString("nonce"), old.getLong("attempt_at"))
        }
        if (previous != null) SmsDispatch.cancelBootstrapAttempt(context, nonce, previous.attemptAt)
        val attemptAt = maxOf(System.currentTimeMillis(), (previous?.attemptAt ?: 0) + 1)
        val pending = JSONObject().put("nonce", nonce).put("attempt_at", attemptAt)
            .put("attempts", (previous?.attempts ?: 0) + 1).put("status", "dispatching")
            .put("destination", destination).put("retry_at", attemptAt + SmsDispatch.RESULT_TIMEOUT_MS)
            .put("subscription_id", selected)
        val storage = SecureStorageManager.getInstance(context)
        storage.saveBootstrapSmsAttempt(pending.toString())
        val sent = SmsDispatch.bootstrapResultIntent(context, nonce, attemptAt, false)
        val delivered = SmsDispatch.bootstrapResultIntent(context, nonce, attemptAt, true)
        return try { BootstrapSms.handoff(context, destination, nonce, selected, sent, delivered) }
        catch (error: Exception) {
            SmsDispatch.cancelBootstrapAttempt(context, nonce, attemptAt)
            pending.put("status", "failed").put("retry_at", System.currentTimeMillis() + 30_000)
                .put("message", error.message ?: "ارسال پیامک انجام نشد.")
            // An unresolved default SIM never consumed a modem attempt.
            if (error is BootstrapSmsNotSent && error.needsSmsSelection)
                pending.put("attempts", previous?.attempts ?: 0)
            storage.saveBootstrapSmsAttempt(pending.toString())
            throw error
        }
    }

    @Synchronized fun handleResult(context: Context, nonce: String, attemptAt: Long, resultCode: Int,
        delivered: Boolean, report: SmsDispatch.DeliveryReport?): Boolean {
        val old = state(context, nonce) ?: return false
        if (old.attemptAt != attemptAt || old.status !in setOf("dispatching", "sent") ||
            (delivered && report == null) || (!delivered && old.status == "sent")) return false
        val failed = if (delivered) report == SmsDispatch.DeliveryReport.FAILED else resultCode != Activity.RESULT_OK
        val status = when {
            failed -> "failed"
            delivered && report == SmsDispatch.DeliveryReport.DELIVERED -> "delivered"
            else -> "sent"
        }
        val message = when {
            failed && delivered -> "شبکه تحویل پیامک تأیید به هاب را ناموفق اعلام کرد."
            failed -> SmsDispatch.errorMessage(resultCode)
            else -> "ارسال شبکه ثبت شد؛ فعال‌سازی فقط با پاسخ معتبر سرور تأیید می‌شود."
        }
        val json = JSONObject(SecureStorageManager.getInstance(context).getBootstrapSmsAttempt()!!)
            .put("status", status).put("message", message)
            .put("retry_at", if (failed) System.currentTimeMillis() + 30_000 else attemptAt + PROOF_TIMEOUT_MS)
        SecureStorageManager.getInstance(context).saveBootstrapSmsAttempt(json.toString())
        if (failed || status == "delivered") SmsDispatch.cancelBootstrapAttempt(context, nonce, attemptAt)
        return true
    }
}
