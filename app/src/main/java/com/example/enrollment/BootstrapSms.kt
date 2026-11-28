package com.example.enrollment

import android.annotation.SuppressLint
import android.content.Context
import android.app.PendingIntent
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
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

    fun send(context: Context, destination: String, nonce: String, driverPhone: String = ""): Boolean =
        BootstrapSmsDispatch.send(context, destination, nonce, driverPhone)

    /**
     * SIMs whose own number is the driver phone.
     *
     * The carrier proves the SMS origin, so a proof from another SIM can never match
     * the challenge. An empty result is ambiguous only when the platform hid every
     * number; [readableSubscriptionNumbers] distinguishes the two cases.
     */
    internal fun subscriptionsForPhone(context: Context, phone: String): List<Int> {
        if (!PermissionHelper.isSendSmsGranted(context)) throw BootstrapSmsNotSent("مجوز ارسال پیامک را تأیید کنید.")
        return matchingSimIds(simSnapshot(context).numbers, phone)
    }

    /** How many active SIMs this app is actually allowed to read a number from. */
    internal fun readableSubscriptionNumbers(context: Context): Int =
        simSnapshot(context).numbers.count { it.second != null }

    /**
     * Ordered SIM candidates for one activation attempt.
     *
     * A SIM that carries the entered number is the only one whose carrier proof can
     * match the activation. When this phone has readable numbers and none of them is
     * the entered number, the attempt stops with an explicit message instead of
     * sending a proof the server must refuse. An unreadable platform (no permission
     * or no numbers exposed) keeps the previous default-SMS order so the driver is
     * not blocked by something the app cannot observe.
     */
    internal fun activationSubscriptions(context: Context, driverPhone: String): List<Int> {
        if (!PermissionHelper.isSendSmsGranted(context)) throw BootstrapSmsNotSent("مجوز ارسال پیامک را تأیید کنید.")
        val snapshot = simSnapshot(context)
        return activationCandidates(snapshot.numbers, availableSubscriptions(context), driverPhone, snapshot.allEnumerated)
    }

    /** Pure SIM decision, unit-tested without a device: ids paired with each SIM's own number. */
    internal fun matchingSimIds(numbers: List<Pair<Int, String?>>, phone: String): List<Int> {
        val normalized = SmsParser.normalizePhoneNumber(phone)
        if (!normalized.matches(Regex("09[0-9]{9}"))) return emptyList()
        return numbers.filter { (id, raw) ->
            id >= 0 && SmsParser.normalizePhoneNumber(raw.orEmpty()) == normalized
        }.map { it.first }
    }

    internal fun activationCandidates(
        numbers: List<Pair<Int, String?>>,
        fallback: List<Int>,
        phone: String,
        allSimsEnumerated: Boolean = true
    ): List<Int> {
        val matching = matchingSimIds(numbers, phone)
        if (matching.isNotEmpty()) return matching
        if (phone.isNotBlank() && numbers.isNotEmpty()) {
            if (numbers.all { it.second != null }) {
                if (!allSimsEnumerated) {
                    throw BootstrapSmsNotSent(
                        "سیم‌کارت پیش‌فرض پیامک با شمارهٔ $phone مطابقت ندارد؛ برای تشخیص سایر سیم‌کارت‌ها مجوز «وضعیت سیم‌کارت» را تأیید کنید یا سیم‌کارت پیش‌فرض را تغییر دهید.",
                        needsSmsSelection = false,
                        retryable = false
                    )
                }
                throw BootstrapSmsNotSent(
                    "این گوشی سیم‌کارت $phone را ندارد؛ سیم‌کارت همان شمارهٔ واردشده را در دستگاه بگذارید.",
                    needsSmsSelection = false,
                    retryable = false)
            }
            val knownMismatchIds = numbers.filter { it.second != null }.map { it.first }.toSet()
            if (knownMismatchIds.isNotEmpty()) {
                val unknownOnly = fallback.filterNot { it in knownMismatchIds }
                if (unknownOnly.isNotEmpty()) return unknownOnly
                return numbers.filter { it.second == null }.map { it.first }
            }
        }
        return fallback
    }

    private data class SimSnapshot(val numbers: List<Pair<Int, String?>>, val allEnumerated: Boolean)

    private fun canReadOwnNumber(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            PermissionHelper.isReadPhoneNumbersGranted(context)
        } else {
            PermissionHelper.isReadPhoneNumbersGranted(context) || PermissionHelper.isReadPhoneStateGranted(context)
        }

    private fun simSnapshot(context: Context): SimSnapshot {
        val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
        val canRead = canReadOwnNumber(context)
        val active = activeSubscriptions(subManager)
        if (active != null) {
            return SimSnapshot(
                numbers = active.filter { it.subscriptionId >= 0 }.map { it.subscriptionId to ownNumber(subManager, it, canRead) },
                allEnumerated = true
            )
        }
        if (canRead) {
            val defaultSub = SubscriptionManager.getDefaultSmsSubscriptionId()
            if (defaultSub >= 0 && defaultSub != Int.MAX_VALUE) {
                val number = readPhoneNumberForSub(context, subManager, defaultSub)
                if (number != null) return SimSnapshot(listOf(defaultSub to number), allEnumerated = false)
            }
        }
        return SimSnapshot(emptyList(), allEnumerated = false)
    }

    private fun activeSubscriptions(subManager: SubscriptionManager?): List<SubscriptionInfo>? = try {
        subManager?.activeSubscriptionInfoList?.takeIf { it.isNotEmpty() }
    } catch (_: SecurityException) { null } catch (_: Exception) { null }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun ownNumber(subManager: SubscriptionManager?, info: SubscriptionInfo, canRead: Boolean): String? {
        // A caller must prove the permission first; an OEM that returns null is not a match.
        if (!canRead) return null
        val modern = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try { subManager?.getPhoneNumber(info.subscriptionId) } catch (_: SecurityException) { null } catch (_: Exception) { null }
        } else null
        val raw = modern?.takeIf { it.isNotBlank() }
            ?: try { info.number } catch (_: SecurityException) { null } catch (_: Exception) { null }
        return SmsParser.normalizePhoneNumber(raw.orEmpty())
            .takeIf { it.matches(Regex("09[0-9]{9}")) }
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    @Suppress("DEPRECATION")
    private fun readPhoneNumberForSub(context: Context, subManager: SubscriptionManager?, subId: Int): String? {
        val modern = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try { subManager?.getPhoneNumber(subId) } catch (_: SecurityException) { null } catch (_: Exception) { null }
        } else null
        val raw = modern?.takeIf { it.isNotBlank() } ?: try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            tm?.createForSubscriptionId(subId)?.line1Number
        } catch (_: SecurityException) { null } catch (_: Exception) { null }
        return SmsParser.normalizePhoneNumber(raw.orEmpty())
            .takeIf { it.matches(Regex("09[0-9]{9}")) }
    }

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

    internal fun selectedSubscription(context: Context, phone: String = ""): Int {
        val matching = subscriptionsForPhone(context, phone)
        if (matching.isNotEmpty()) return matching.first()
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
            context.getSystemService(SmsManager::class.java)?.createForSubscriptionId(selected)
                ?: throw BootstrapSmsNotSent("سرویس پیامک در این دستگاه در دسترس نیست.")
        else {
            @Suppress("DEPRECATION")
            SmsManager.getSmsManagerForSubscriptionId(selected)
        }
        manager.sendTextMessage(destination, null, message(nonce), sent, delivered)
        return true // modem handoff only; the server's carrier proof determines activation
    }
}

class BootstrapSmsNotSent(
    message: String,
    val needsSmsSelection: Boolean = false,
    val retryable: Boolean = true
) : IllegalStateException(message)
