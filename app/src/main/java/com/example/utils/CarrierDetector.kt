package com.example.utils

import android.content.Context
import android.telephony.TelephonyManager

enum class MobileCarrier {
    MCI,       // همراه اول (Hamrah-e Aval)
    IRANCELL,  // ایرانسل (MTN Irancell)
    RIGHTEL,   // رایتل (RighTel)
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

    private val MCI_PREFIXES = listOf(
        "0910", "0911", "0912", "0913", "0914", "0915", "0916", "0917", "0918", "0919",
        "0990", "0991", "0992", "0993", "0994", "0996"
    )

    private val IRANCELL_PREFIXES = listOf(
        "0930", "0933", "0935", "0936", "0937", "0938", "0939",
        "0900", "0901", "0902", "0903", "0904", "0905", "0941"
    )

    private val RIGHTEL_PREFIXES = listOf(
        "0920", "0921", "0922", "0923"
    )

    fun detectCarrierFromPhone(phoneNumber: String): MobileCarrier {
        val norm = SmsParser.normalizePhoneNumber(phoneNumber)
        if (norm.length < 4) return MobileCarrier.UNKNOWN
        val prefix = norm.take(4)
        return when {
            MCI_PREFIXES.contains(prefix) -> MobileCarrier.MCI
            IRANCELL_PREFIXES.contains(prefix) -> MobileCarrier.IRANCELL
            RIGHTEL_PREFIXES.contains(prefix) -> MobileCarrier.RIGHTEL
            else -> MobileCarrier.UNKNOWN
        }
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
     * - If Driver is MCI -> Hub MCI is primary, Hub Irancell is failover.
     * - If Driver is Irancell/RighTel -> Hub Irancell is primary, Hub MCI is failover.
     * - If only one is configured, uses that for both.
     */
    fun resolveRoute(
        driverPhone: String,
        hubMciNumber: String,
        hubIrancellNumber: String,
        context: Context? = null
    ): HubTargetRoute {
        var carrier = detectCarrierFromPhone(driverPhone)
        if (carrier == MobileCarrier.UNKNOWN && context != null) {
            carrier = detectCarrierFromDevice(context)
        }

        val cleanMci = hubMciNumber.trim()
        val cleanIrancell = hubIrancellNumber.trim()

        return when {
            cleanMci.isNotBlank() && cleanIrancell.isNotBlank() -> {
                if (carrier == MobileCarrier.MCI) {
                    HubTargetRoute(primaryNumber = cleanMci, failoverNumber = cleanIrancell, carrier = carrier)
                } else {
                    HubTargetRoute(primaryNumber = cleanIrancell, failoverNumber = cleanMci, carrier = carrier)
                }
            }
            cleanMci.isNotBlank() -> HubTargetRoute(primaryNumber = cleanMci, failoverNumber = cleanMci, carrier = carrier)
            cleanIrancell.isNotBlank() -> HubTargetRoute(primaryNumber = cleanIrancell, failoverNumber = cleanIrancell, carrier = carrier)
            else -> HubTargetRoute(primaryNumber = "", failoverNumber = "", carrier = carrier)
        }
    }
}
