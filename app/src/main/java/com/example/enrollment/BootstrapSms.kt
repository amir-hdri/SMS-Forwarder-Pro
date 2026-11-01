package com.example.enrollment

import android.content.Context
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

    fun send(context: Context, destination: String, nonce: String): Boolean {
        if (!PermissionHelper.isSendSmsGranted(context)) throw BootstrapSmsNotSent("مجوز ارسال پیامک را تأیید کنید.")
        val selected = SubscriptionManager.getDefaultSmsSubscriptionId()
        // -1 is invalid; MAX_VALUE is the unresolved default-subscription sentinel.
        if (selected < 0 || selected == Int.MAX_VALUE) throw BootstrapSmsNotSent(
            "سیم‌کارت همین شماره را در تنظیمات Android به‌عنوان سیم‌کارت پیش‌فرض پیامک انتخاب کنید.")
        val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            context.getSystemService(SmsManager::class.java).createForSubscriptionId(selected)
        else {
            @Suppress("DEPRECATION")
            SmsManager.getSmsManagerForSubscriptionId(selected)
        }
        manager.sendTextMessage(destination, null, message(nonce), null, null)
        return true // modem handoff only; the server's carrier proof determines activation
    }
}

class BootstrapSmsNotSent(message: String) : IllegalStateException(message)
