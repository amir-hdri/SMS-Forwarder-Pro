package com.example.utils

import android.util.Log

/**
 * Route rule defining an Iranian mobile operator and its corresponding Hub SIM target.
 */
data class CarrierRoutingRule(
    val operatorName: String,
    val carrier: MobileCarrier,
    val targetHubCarrier: MobileCarrier,
    val prefixes: Set<String>
)

/**
 * Result of routing resolution including warning if prefix is unknown.
 */
data class RouteResolution(
    val detectedCarrier: MobileCarrier,
    val targetHubCarrier: MobileCarrier,
    val isUnknownPrefix: Boolean,
    val warning: String? = null
)

/**
 * Extensible Iranian operator routing table for SMS dispatch to Hub SIMs.
 * Implements Longest-Prefix Match (LPM) supporting both 4-digit and 5-digit prefixes.
 *
 * Rules:
 * - Hamrah-e Aval (MCI): 0910-0919, 0990-0994 (incl. 0994 Anarestan), 0996 -> Hub MCI SIM
 * - Talia: 0932 -> Hub MCI SIM (operates on MCI network)
 * - Kish Telecommunication (TKC): 0934, 093476 -> Hub MCI SIM (roams on MCI network)
 * - Spadan (MTCE): 0931 -> Hub MCI SIM
 * - Lotus Tel (Parsian): 09990 -> Hub MCI SIM (operates on MCI network)
 * - Aptel: 09991, 09993 -> Hub MCI SIM (operates on MCI network)
 * - MTN Irancell: 0930, 0933, 0935-0939, 0900-0905, 0941 -> Hub Irancell SIM
 * - RighTel: 0920-0923 -> Hub Irancell SIM (per system requirement)
 * - Shatel Mobile: 0998, 09981, 09982 -> Hub Irancell SIM
 * - Saman Tel: 0999, 09999, 09995 -> Hub Irancell SIM
 * - Other MVNOs on Irancell: Smartcomm (09988), Fanap (09992), ArianTel (09998), HiWeb (0995) -> Hub Irancell SIM
 * - Unknown prefix -> Default fallback to Hub MCI SIM with admin warning logged
 */
object CarrierRoutingTable {
    private const val TAG = "CarrierRoutingTable"

    // Canonical Iranian operator and MVNO prefix sets
    // Hamrah-e Aval (MCI) - 17 prefixes per persian-tools v4.0.4 + 0997
    val DEFAULT_MCI_PREFIXES: Set<String> = (0..9).map { "091$it" }.toSet() + setOf("0990", "0991", "0992", "0993", "0994", "0995", "0996", "0997")
    // MTN Irancell - 14 prefixes per persian-tools v4.0.4
    val DEFAULT_IRANCELL_PREFIXES: Set<String> = setOf("0930", "0933", "0935", "0936", "0937", "0938", "0939") +
            setOf("0900", "0901", "0902", "0903", "0904", "0905", "0941")
    // RighTel - 4 prefixes per persian-tools v4.0.4
    val DEFAULT_RIGHTEL_PREFIXES: Set<String> = setOf("0920", "0921", "0922", "0923")
    val DEFAULT_TALIYA_PREFIXES: Set<String> = setOf("0932")
    val DEFAULT_TKC_PREFIXES: Set<String> = setOf("0934", "093476")
    val DEFAULT_SPADAN_PREFIXES: Set<String> = setOf("0931")
    val DEFAULT_SHATEL_PREFIXES: Set<String> = setOf("0998", "09981", "09982")
    val DEFAULT_SAMANTEL_PREFIXES: Set<String> = setOf("0999", "09999", "09995")
    val DEFAULT_LOTUSTEL_PREFIXES: Set<String> = setOf("09990")
    val DEFAULT_APTEL_PREFIXES: Set<String> = setOf("09991", "09993")
    val DEFAULT_FANAP_PREFIXES: Set<String> = setOf("09992")
    val DEFAULT_ARIANTEL_PREFIXES: Set<String> = setOf("09998")
    val DEFAULT_SMARTCOMM_PREFIXES: Set<String> = setOf("09988")
    val DEFAULT_TOSAN_PREFIXES: Set<String> = setOf("09989")

    private val rules = mutableListOf<CarrierRoutingRule>()
    var defaultHubCarrier: MobileCarrier = MobileCarrier.MCI

    init {
        resetToDefaults()
    }

    @Synchronized
    fun resetToDefaults() {
        rules.clear()
        defaultHubCarrier = MobileCarrier.MCI

        // MVNO 5-digit rules registered first for higher specificity / priority
        rules.add(
            CarrierRoutingRule(
                operatorName = "LotusTel",
                carrier = MobileCarrier.LOTUSTEL,
                targetHubCarrier = MobileCarrier.MCI,
                prefixes = DEFAULT_LOTUSTEL_PREFIXES
            )
        )
        rules.add(
            CarrierRoutingRule(
                operatorName = "Aptel",
                carrier = MobileCarrier.APTEL,
                targetHubCarrier = MobileCarrier.MCI,
                prefixes = DEFAULT_APTEL_PREFIXES
            )
        )
        rules.add(
            CarrierRoutingRule(
                operatorName = "Shatel Mobile",
                carrier = MobileCarrier.SHATEL,
                targetHubCarrier = MobileCarrier.IRANCELL,
                prefixes = DEFAULT_SHATEL_PREFIXES
            )
        )
        rules.add(
            CarrierRoutingRule(
                operatorName = "SamanTel",
                carrier = MobileCarrier.SAMANTEL,
                targetHubCarrier = MobileCarrier.IRANCELL,
                prefixes = DEFAULT_SAMANTEL_PREFIXES
            )
        )
        rules.add(
            CarrierRoutingRule(
                operatorName = "Fanap Mobile",
                carrier = MobileCarrier.FANAP,
                targetHubCarrier = MobileCarrier.IRANCELL,
                prefixes = DEFAULT_FANAP_PREFIXES
            )
        )
        rules.add(
            CarrierRoutingRule(
                operatorName = "ArianTel",
                carrier = MobileCarrier.ARIANTEL,
                targetHubCarrier = MobileCarrier.IRANCELL,
                prefixes = DEFAULT_ARIANTEL_PREFIXES
            )
        )
        rules.add(
            CarrierRoutingRule(
                operatorName = "Smartcomm",
                carrier = MobileCarrier.SMARTCOMM,
                targetHubCarrier = MobileCarrier.IRANCELL,
                prefixes = DEFAULT_SMARTCOMM_PREFIXES
            )
        )
        rules.add(
            CarrierRoutingRule(
                operatorName = "Tosan",
                carrier = MobileCarrier.TOSAN,
                targetHubCarrier = MobileCarrier.IRANCELL,
                prefixes = DEFAULT_TOSAN_PREFIXES
            )
        )
        rules.add(
            CarrierRoutingRule(
                operatorName = "Taliya",
                carrier = MobileCarrier.TALIYA,
                targetHubCarrier = MobileCarrier.MCI,
                prefixes = DEFAULT_TALIYA_PREFIXES
            )
        )
        rules.add(
            CarrierRoutingRule(
                operatorName = "TeleKish (TKC)",
                carrier = MobileCarrier.TKC,
                targetHubCarrier = MobileCarrier.MCI,
                prefixes = DEFAULT_TKC_PREFIXES
            )
        )
        rules.add(
            CarrierRoutingRule(
                operatorName = "Spadan (MTCE)",
                carrier = MobileCarrier.SPADAN,
                targetHubCarrier = MobileCarrier.MCI,
                prefixes = DEFAULT_SPADAN_PREFIXES
            )
        )
        // Primary Operators
        rules.add(
            CarrierRoutingRule(
                operatorName = "Hamrah-e Aval (MCI)",
                carrier = MobileCarrier.MCI,
                targetHubCarrier = MobileCarrier.MCI,
                prefixes = DEFAULT_MCI_PREFIXES
            )
        )
        rules.add(
            CarrierRoutingRule(
                operatorName = "MTN Irancell",
                carrier = MobileCarrier.IRANCELL,
                targetHubCarrier = MobileCarrier.IRANCELL,
                prefixes = DEFAULT_IRANCELL_PREFIXES
            )
        )
        rules.add(
            CarrierRoutingRule(
                operatorName = "RighTel",
                carrier = MobileCarrier.RIGHTEL,
                targetHubCarrier = MobileCarrier.IRANCELL, // RighTel routes to Hub Irancell SIM per requirement
                prefixes = DEFAULT_RIGHTEL_PREFIXES
            )
        )
    }

    /**
     * Extensibility: Register or update a custom routing rule (added with highest priority).
     */
    @Synchronized
    fun registerRule(rule: CarrierRoutingRule) {
        rules.removeAll { it.carrier == rule.carrier && it.operatorName == rule.operatorName }
        rules.add(0, rule) // higher precedence
    }

    /**
     * Extensibility: Add extra prefixes to an existing carrier.
     */
    @Synchronized
    fun addPrefixes(carrier: MobileCarrier, extraPrefixes: Collection<String>) {
        val index = rules.indexOfFirst { it.carrier == carrier }
        if (index >= 0) {
            val old = rules[index]
            rules[index] = old.copy(prefixes = old.prefixes + extraPrefixes)
        }
    }

    /**
     * Resolves operator and target Hub SIM carrier for a phone number using Longest-Prefix Match (LPM).
     * If prefix is unknown, falls back to [defaultHubCarrier] and logs an alert.
     */
    @Synchronized
    fun resolveRoute(phoneNumber: String): RouteResolution {
        val norm = SmsParser.normalizePhoneNumber(phoneNumber)
        if (norm.length >= 4) {
            var bestMatchRule: CarrierRoutingRule? = null
            var bestMatchLength = 0

            for (rule in rules) {
                for (prefix in rule.prefixes) {
                    if (norm.startsWith(prefix) && prefix.length > bestMatchLength) {
                        bestMatchRule = rule
                        bestMatchLength = prefix.length
                    }
                }
            }

            if (bestMatchRule != null) {
                return RouteResolution(
                    detectedCarrier = bestMatchRule.carrier,
                    targetHubCarrier = bestMatchRule.targetHubCarrier,
                    isUnknownPrefix = false,
                    warning = null
                )
            }
        }

        val warnMsg = "Unknown carrier prefix for phone '${LogSanitizer.maskPhone(norm)}'. Falling back to default Hub SIM (${defaultHubCarrier.name})."
        try {
            Log.w(TAG, warnMsg)
        } catch (_: Throwable) {
            // JVM unit test fallback when android.util.Log is not mocked
        }
        return RouteResolution(
            detectedCarrier = MobileCarrier.UNKNOWN,
            targetHubCarrier = defaultHubCarrier,
            isUnknownPrefix = true,
            warning = warnMsg
        )
    }

    /**
     * Quick carrier lookup for phone number.
     */
    fun detectCarrier(phoneNumber: String): MobileCarrier {
        return resolveRoute(phoneNumber).detectedCarrier
    }

    /**
     * Checks if the phone number prefix is a recognized Iranian mobile network operator or MVNO.
     */
    fun isKnownOperator(phoneNumber: String): Boolean {
        return resolveRoute(phoneNumber).detectedCarrier != MobileCarrier.UNKNOWN
    }
}
