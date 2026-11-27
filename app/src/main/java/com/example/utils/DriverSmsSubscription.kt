package com.example.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.example.data.model.ForwardConfig

/** Keep Android's receiving subscription in the existing durable outbox SIM label. */
object DriverSmsSubscription {
    private val subscription = Regex("(?:^|\\()Sub #(\\d+)\\)?$")
    private val explicitSlot = Regex("^\\s*SIM\\s+([1-9]\\d*)\\b", RegexOption.IGNORE_CASE)

    fun label(slot: Int, id: Int): String = when {
        id >= 0 && slot >= 0 -> "SIM ${slot + 1} (Sub #$id)"
        id >= 0 -> "Sub #$id"
        slot >= 0 -> "SIM ${slot + 1}"
        else -> "Unknown SIM"
    }

    fun id(label: String): Int = subscription.find(label)?.groupValues?.get(1)?.toIntOrNull() ?: -1

    /** Extract only an explicit leading "SIM <n>" index (1-based), never digits inside "Sub #n". */
    fun slotNumber(label: String): Int? =
        explicitSlot.find(label)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it >= 1 }

    @Suppress("DEPRECATION")
    fun detectSlotIndex(context: Context, subId: Int): Int {
        if (subId < 0) return -1
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return -1
        }
        return try {
            val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            val info = subManager?.getActiveSubscriptionInfo(subId)
            info?.simSlotIndex?.takeIf { it >= 0 } ?: -1
        } catch (_: SecurityException) {
            -1
        } catch (_: Exception) {
            -1
        }
    }

    @Suppress("DEPRECATION")
    fun detectCarrierForSubscription(context: Context, subId: Int): MobileCarrier {
        if (subId < 0) return MobileCarrier.UNKNOWN
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            try {
                val subManager =
                    context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                val info = subManager?.getActiveSubscriptionInfo(subId)
                if (info != null) {
                    val mccmnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        (info.mccString ?: "") + (info.mncString ?: "")
                    } else {
                        "${info.mcc}${info.mnc}"
                    }
                    carrierFromOperator(mccmnc, info.carrierName?.toString() ?: "")
                        .takeIf { it != MobileCarrier.UNKNOWN }
                        ?.let { return it }
                }
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
        }
        return try {
            val baseTm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val scopedTm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                baseTm?.createForSubscriptionId(subId)
            } else {
                baseTm
            }
            carrierFromOperator(scopedTm?.simOperator ?: "", scopedTm?.simOperatorName ?: "")
        } catch (_: SecurityException) {
            MobileCarrier.UNKNOWN
        } catch (_: Exception) {
            MobileCarrier.UNKNOWN
        }
    }

    private fun carrierFromOperator(operatorCode: String, operatorName: String): MobileCarrier {
        return when (operatorCode.trim()) {
            "43211", "43219" -> MobileCarrier.MCI
            "43235" -> MobileCarrier.IRANCELL
            "43220", "43221" -> MobileCarrier.RIGHTEL
            else -> {
                val name = operatorName.lowercase()
                when {
                    name.contains("mci") || name.contains("hamrah") || name.contains("همراه") -> MobileCarrier.MCI
                    name.contains("irancell") || name.contains("mtn") || name.contains("ایرانسل") -> MobileCarrier.IRANCELL
                    name.contains("rightel") || name.contains("رایتل") -> MobileCarrier.RIGHTEL
                    else -> MobileCarrier.UNKNOWN
                }
            }
        }
    }

    /**
     * Resolves which configured Hub SIM number received the direct SMS using subscription phone/carrier,
     * independent of physical SIM slot order or missing Android 13+ slot extras.
     */
    @Suppress("DEPRECATION")
    fun resolveHubTargetPhone(context: Context, simSlot: String, config: ForwardConfig): String? {
        val mci = SmsParser.normalizePhoneNumber(config.fallbackServerPhoneNumber)
        val irancell = SmsParser.normalizePhoneNumber(config.hubIrancellPhoneNumber)
        if (mci.isBlank() && irancell.isBlank()) return null
        if (mci.isNotBlank() && (irancell.isBlank() || irancell == mci)) return mci
        if (irancell.isNotBlank() && mci.isBlank()) return irancell

        val subId = id(simSlot)
        if (subId < 0) return null

        val hasPhonePerm = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) ==
                PackageManager.PERMISSION_GRANTED)
        if (hasPhonePerm) {
            try {
                val subManager =
                    context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                val subNumber = subManager?.getActiveSubscriptionInfo(subId)?.number
                val normSubNumber = SmsParser.normalizePhoneNumber(subNumber ?: "")
                if (normSubNumber == mci) return mci
                if (normSubNumber == irancell) return irancell
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
        }

        return when (detectCarrierForSubscription(context, subId)) {
            MobileCarrier.MCI -> mci
            MobileCarrier.IRANCELL -> irancell
            else -> null
        }
    }

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
