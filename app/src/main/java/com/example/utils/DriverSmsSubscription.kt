package com.example.utils

import android.content.Context

/** Keep Android's receiving subscription in the existing durable outbox SIM label. */
object DriverSmsSubscription {
    private val subscription = Regex("(?:^|\\()Sub #(\\d+)\\)?$")

    fun label(slot: Int, id: Int): String = when {
        id >= 0 && slot >= 0 -> "SIM ${slot + 1} (Sub #$id)"
        id >= 0 -> "Sub #$id"
        slot >= 0 -> "SIM ${slot + 1}"
        else -> "Unknown SIM"
    }

    fun id(label: String): Int = subscription.find(label)?.groupValues?.get(1)?.toIntOrNull() ?: -1

    @Synchronized
    fun remember(context: Context, phone: String, label: String) {
        val id = id(label)
        if (id >= 0) context.getSharedPreferences("barpro_driver_subscription", Context.MODE_PRIVATE)
            .edit().putInt(SmsParser.normalizePhoneNumber(phone), id).apply()
    }

    /** Retry seeding shares the fresh-intake lock, so an older ID cannot overwrite it. */
    @Synchronized
    fun seedIfUnknown(context: Context, phone: String, label: String) {
        if (known(context, phone) < 0) remember(context, phone, label)
    }

    // Before receiving an OTP, the OS-selected SMS SIM is the only available identity.
    fun known(context: Context, phone: String): Int =
        context.getSharedPreferences("barpro_driver_subscription", Context.MODE_PRIVATE)
            .getInt(SmsParser.normalizePhoneNumber(phone), -1)
}
