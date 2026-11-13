package com.example.enrollment

import android.content.Context
import android.app.PendingIntent
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import com.example.utils.PermissionHelper
import com.example.utils.SmsParser

/** This prefix has no OTP or asserted phone; only the carrier supplies the origin. */
object BootstrapSms {
    const val PREFIX = "BPA1#"
    fun parse(origin: String, message: String): Pair<String, String>? {
        val match = Regex("BPA1#([A-Za-z0-9_-]{43})").matchEntire(message) ?: return null
        val phone = SmsParser.normalizePhoneNumber(origin)
        if (!phone.matches(Regex("09[0-9]{9}"))) return null
        return phone to match.groupValues[1]
    }
    fun message(nonce: String): String {
        require(nonce.matches(Regex("[A-Za-z0-9_-]{43}")))
        return PREFIX + nonce
    }

    fun send(context: Context, destination: String, nonce: String): Boolean = BootstrapSmsDispatch.send(context, destination, nonce)

    internal fun availableSubscriptions(context: Context): List<Int> {
        if (!PermissionHelper.isSendSmsGranted(context)) throw BootstrapSmsNotSent("مجوز ارسال پیامک را تأیید کنید.")
        val list = mutableListOf<Int>()
        try {
            val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            val active = subManager?.activeSubscriptionInfoList
            if (!active.isNullOrEmpty()) {
                for (info in active) {
                    if (info.subscriptionId >= 0 && !list.contains(info.subscriptionId)) {
                        list.add(info.subscriptionId)
                    }
                }
            }
        } catch (_: SecurityException) {}

        val defaultSub = SubscriptionManager.getDefaultSmsSubscriptionId()
        if (defaultSub >= 0 && defaultSub != Int.MAX_VALUE) {
            if (!list.contains(defaultSub)) {
                list.add(0, defaultSub)
            } else if (list.indexOf(defaultSub) > 0) {
                list.remove(defaultSub)
                list.add(0, defaultSub)
            }
        }
        return list
    }

    internal fun selectedSubscription(context: Context): Int {
        val available = availableSubscriptions(context)
        if (available.isNotEmpty()) return available.first()
        val selected = SubscriptionManager.getDefaultSmsSubscriptionId()
        // -1 is invalid; MAX_VALUE is the unresolved default-subscription sentinel.
        if (selected < 0 || selected == Int.MAX_VALUE) throw BootstrapSmsNotSent(
            "سیم‌کارت فعالی در دستگاه یافت نشد؛ سیم‌کارت و مجوزها را بررسی کنید.", needsSmsSelection = true)
        return selected
    }

    internal fun handoff(context: Context, destination: String, nonce: String,
        selected: Int, sent: PendingIntent, delivered: PendingIntent): Boolean {
        val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            context.getSystemService(SmsManager::class.java).createForSubscriptionId(selected)
        else {
            @Suppress("DEPRECATION")
            SmsManager.getSmsManagerForSubscriptionId(selected)
        }
        manager.sendTextMessage(destination, null, message(nonce), sent, delivered)
        return true // modem handoff only; the server's carrier proof determines activation
    }
}

class BootstrapSmsNotSent(message: String, val needsSmsSelection: Boolean = false) : IllegalStateException(message)
