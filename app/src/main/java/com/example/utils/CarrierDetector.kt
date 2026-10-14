package com.example.utils

import android.content.Context
import android.telephony.TelephonyManager

enum class MobileCarrier {
    MCI,       // همراه اول (Hamrah-e Aval)
    IRANCELL,  // ایرانسل (MTN Irancell)
    RIGHTEL,   // رایتل (RighTel)
    TALIYA,    // تالیا (Taliya)
    TKC,       // تله‌کیش (TeleKish / TKC)
    SPADAN,    // اسپادان (Spadan / MTCE)
    SHATEL,    // شاتل موبایل (Shatel Mobile)
    SAMANTEL,  // سامانتل (SamanTel)
    LOTUSTEL,  // لوتوس‌تل (Lotus Tel)
    APTEL,     // آپتل (Aptel)
    FANAP,     // فناپ موبایل (Fanap Mobile)
    ARIANTEL,  // آرین‌تل (ArianTel)
    SMARTCOMM, // هوشمند امین (Smartcomm)
    HIWEB,     // های‌وب (HiWeb)
    TOSAN,     // همراه هوشمند آینده - توسن (Tosan)
    UNKNOWN
}

data class HubTargetRoute(
    val primaryNumber: String,
    val failoverNumber: String,
    val carrier: MobileCarrier
)

/**
 * The two Hub SIM numbers actually available at runtime, after build-time defaults and
 * operator configuration have been reconciled.
 *
 * Exists as a separate pure value so the precedence rule is unit-testable: the previous
 * inline version silently left [irancell] empty in every shipped APK, which collapsed
 * [CarrierDetector.resolveRoute] to single-number delivery and made SIM failover dead code.
 */
data class HubNumbers(
    val mci: String,
    val irancell: String
) {
    /** True when both legs are present, i.e. on-net matching and failover can actually happen. */
    val isDualSim: Boolean get() = mci.isNotBlank() && irancell.isNotBlank()
}

object CarrierDetector {

    fun detectCarrierFromPhone(phoneNumber: String): MobileCarrier {
        return CarrierRoutingTable.detectCarrier(phoneNumber)
    }

    fun detectCarrierFromDevice(context: Context): MobileCarrier {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val simOperator = tm?.simOperator ?: ""
            when (simOperator) {
                "43211", "43219" -> MobileCarrier.MCI
                "43235" -> MobileCarrier.IRANCELL
                "43220", "43221" -> MobileCarrier.RIGHTEL
                else -> {
                    val simName = (tm?.simOperatorName ?: "").lowercase()
                    when {
                        simName.contains("mci") || simName.contains("hamrah") -> MobileCarrier.MCI
                        simName.contains("irancell") || simName.contains("mtn") -> MobileCarrier.IRANCELL
                        simName.contains("rightel") -> MobileCarrier.RIGHTEL
                        else -> MobileCarrier.UNKNOWN
                    }
                }
            }
        } catch (_: Exception) {
            MobileCarrier.UNKNOWN
        }
    }

    /**
     * Attempts to read the device's own mobile phone number from the hardware SIM / TelephonyManager
     * as a zero-configuration fallback when [driverPhone] has not yet been saved in config.
     */
    @Suppress("DEPRECATION")
    fun detectPhoneNumberFromDevice(context: Context): String {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val line1 = tm?.line1Number ?: ""
            val norm = SmsParser.normalizePhoneNumber(line1)
            if (norm.matches(Regex("09[0-9]{9}"))) norm else ""
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Reconciles build-time Hub defaults with operator-entered configuration.
     *
     * Precedence per leg: a non-blank build default wins (fleet-provisioned APK), otherwise the
     * operator's configured value is used. Both legs honour configuration, so an APK built without
     * `BARPRO_HUB_PHONE_*` Gradle properties — which is the normal case — still reaches dual-SIM
     * operation once the operator fills the two fields in.
     */
    fun resolveHubNumbers(
        buildDefaultMci: String,
        buildDefaultIrancell: String,
        configuredMci: String,
        configuredIrancell: String
    ): HubNumbers = HubNumbers(
        mci = buildDefaultMci.trim().ifBlank { configuredMci.trim() },
        irancell = buildDefaultIrancell.trim().ifBlank { configuredIrancell.trim() }
    )

    /**
     * Resolves the optimal Hub SIM destination:
     * - Auto-detects and aligns the actual carrier of the Hub SIMs even if the operator swapped the fields.
     * - If Driver is MCI -> Hub MCI is primary, Hub Irancell is failover.
     * - If Driver is Irancell/RighTel -> Hub Irancell is primary, Hub MCI is failover.
     * - If Driver carrier is UNKNOWN -> Defaults to Hub MCI as primary (Hamrah-e Aval provides widest Iranian highway coverage).
     * - If only one Hub number is configured, uses that for both.
     */
    fun resolveRoute(
        driverPhone: String,
        hubMciNumber: String,
        hubIrancellNumber: String,
        context: Context? = null
    ): HubTargetRoute {
        val resolution = CarrierRoutingTable.resolveRoute(driverPhone)
        var carrier = resolution.detectedCarrier
        if (carrier == MobileCarrier.UNKNOWN && context != null) {
            carrier = detectCarrierFromDevice(context)
        }

        val rawMci = hubMciNumber.trim()
        val rawIrancell = hubIrancellNumber.trim()

        val normMci = SmsParser.normalizePhoneNumber(rawMci)
        val normIrancell = SmsParser.normalizePhoneNumber(rawIrancell)
        val validMci = if (normMci.isNotBlank()) normMci else rawMci
        val validIrancell = if (normIrancell.isNotBlank()) normIrancell else rawIrancell

        // Operator mistake tolerance: If the operator swapped the two fields,
        // align them by detected carrier.
        val cleanMci: String
        val cleanIrancell: String
        if (detectCarrierFromPhone(validMci) == MobileCarrier.IRANCELL &&
            detectCarrierFromPhone(validIrancell) == MobileCarrier.MCI
        ) {
            cleanMci = validIrancell
            cleanIrancell = validMci
        } else {
            cleanMci = validMci
            cleanIrancell = validIrancell
        }

        val targetHubCarrier = if (carrier == resolution.detectedCarrier) {
            resolution.targetHubCarrier
        } else {
            // Overridden by device context
            when (carrier) {
                MobileCarrier.IRANCELL, MobileCarrier.RIGHTEL, MobileCarrier.SHATEL,
                MobileCarrier.SAMANTEL, MobileCarrier.FANAP, MobileCarrier.ARIANTEL,
                MobileCarrier.SMARTCOMM, MobileCarrier.HIWEB, MobileCarrier.TOSAN -> MobileCarrier.IRANCELL
                MobileCarrier.MCI, MobileCarrier.TALIYA, MobileCarrier.TKC,
                MobileCarrier.SPADAN, MobileCarrier.LOTUSTEL, MobileCarrier.APTEL -> MobileCarrier.MCI
                else -> resolution.targetHubCarrier
            }
        }

        return when {
            cleanMci.isNotBlank() && cleanIrancell.isNotBlank() -> {
                if (targetHubCarrier == MobileCarrier.IRANCELL) {
                    HubTargetRoute(primaryNumber = cleanIrancell, failoverNumber = cleanMci, carrier = carrier)
                } else {
                    // MCI or default: Hamrah-e Aval (MCI) is primary due to extensive intercity road coverage
                    HubTargetRoute(primaryNumber = cleanMci, failoverNumber = cleanIrancell, carrier = carrier)
                }
            }
            cleanMci.isNotBlank() -> HubTargetRoute(primaryNumber = cleanMci, failoverNumber = cleanMci, carrier = carrier)
            cleanIrancell.isNotBlank() -> HubTargetRoute(primaryNumber = cleanIrancell, failoverNumber = cleanIrancell, carrier = carrier)
            else -> HubTargetRoute(primaryNumber = "", failoverNumber = "", carrier = carrier)
        }
    }
}
