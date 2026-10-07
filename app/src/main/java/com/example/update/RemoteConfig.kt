package com.example.update

import com.example.network.BarProContract
import com.example.utils.SmsParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * An operator-published, signature-verified settings overlay.
 *
 * This is the "no APK, no driver action" update path: new UTCMS shortcodes, a moved endpoint,
 * rotated webhook token and tuning thresholds all ship as a signed document fetched on the
 * existing heartbeat. It deliberately carries **no regular expressions** — only literal sender
 * strings and literal keywords — so a published document can never introduce a catastrophic
 * backtracking pattern into the SMS hot path. The OTP extraction grammar itself stays in the APK
 * and changes only through a real release.
 *
 * Every field is optional. An absent field means "leave the device's current value alone", which
 * keeps documents small and makes partial rollouts safe.
 */
data class RemoteConfigSnapshot(
    /** Monotonic counter. A document whose version is not greater than the stored one is refused. */
    val version: Long,
    /** Publication time, epoch seconds. Used only to reject implausibly future-dated documents. */
    val issuedAt: Long,
    val endpointUrl: String? = null,
    val webhookToken: String? = null,
    val extraOtpSenders: List<String> = emptyList(),
    val extraUtcmsKeywords: List<String> = emptyList(),
    val healthCheckIntervalMinutes: Int? = null,
    val timeoutSeconds: Int? = null,
    val maxRetries: Int? = null,
    val filterUtcmsOnly: Boolean? = null,
    val enableSmsFallback: Boolean? = null,
    val fallbackServerPhoneNumber: String? = null,
    /** Below this versionCode the app refuses to forward and demands an update. 0 disables the gate. */
    val minSupportedVersionCode: Int = 0,
    /** Shown verbatim to the operator in the app; never used for control flow. */
    val operatorMessageFa: String? = null,
    /** Where to look for APK releases. Lets the update channel be moved without a new APK. */
    val updateManifestUrl: String? = null
)

object RemoteConfig {

    class RejectedException(message: String) : IllegalArgumentException(message)

    const val MAX_SENDERS = 32
    const val MAX_KEYWORDS = 64
    private const val MAX_SENDER_LENGTH = 32
    private const val MAX_KEYWORD_LENGTH = 64
    private const val MAX_MESSAGE_LENGTH = 500

    /** Generous: Iranian devices with stale tzdata can be a full hour off, and we only guard absurdity. */
    private const val MAX_FUTURE_SKEW_SECONDS = 48L * 3600L

    /** Literal shortcodes/alphanumeric sender IDs only — no pattern metacharacters survive this. */
    private val SENDER_SHAPE = Regex("""[A-Za-z0-9+ _.\-]{1,$MAX_SENDER_LENGTH}""")

    /**
     * Validates a signature-verified payload into a snapshot.
     *
     * @param payload the JSON returned by [SignedPayload.verify] — already proven authentic.
     * @param audience this build's applicationId; guards against replaying another tenant's document.
     * @param knownVersion the highest version already applied on this device (0 if none).
     * @throws RejectedException if the document is for another audience, stale, or malformed.
     */
    fun parse(payload: JSONObject, audience: String, knownVersion: Long, nowSeconds: Long): RemoteConfigSnapshot {
        val documentAudience = payload.optString("audience")
        if (documentAudience != audience) {
            throw RejectedException("Document audience '$documentAudience' is not '$audience'")
        }

        val version = payload.optLong("version", 0L)
        if (version <= 0L) throw RejectedException("Document is missing a positive version")
        // Anti-rollback: a captured older document must not be replayable over a newer one.
        if (version <= knownVersion) {
            throw RejectedException("Document version $version is not newer than $knownVersion")
        }

        val issuedAt = payload.optLong("issuedAt", 0L)
        if (issuedAt <= 0L) throw RejectedException("Document is missing issuedAt")
        if (issuedAt > nowSeconds + MAX_FUTURE_SKEW_SECONDS) {
            throw RejectedException("Document is dated too far in the future")
        }

        return RemoteConfigSnapshot(
            version = version,
            issuedAt = issuedAt,
            endpointUrl = optionalEndpoint(payload, "endpointUrl"),
            webhookToken = optionalToken(payload, "webhookToken"),
            extraOtpSenders = literalList(payload, "extraOtpSenders", MAX_SENDERS, MAX_SENDER_LENGTH, true),
            extraUtcmsKeywords = literalList(payload, "extraUtcmsKeywords", MAX_KEYWORDS, MAX_KEYWORD_LENGTH, false),
            healthCheckIntervalMinutes = optionalInt(payload, "healthCheckIntervalMinutes", 1, 60),
            timeoutSeconds = optionalInt(payload, "timeoutSeconds", 1, 60),
            maxRetries = optionalInt(payload, "maxRetries", 0, 3),
            filterUtcmsOnly = optionalBoolean(payload, "filterUtcmsOnly"),
            enableSmsFallback = optionalBoolean(payload, "enableSmsFallback"),
            fallbackServerPhoneNumber = optionalPhone(payload, "fallbackServerPhoneNumber"),
            minSupportedVersionCode = optionalInt(payload, "minSupportedVersionCode", 0, 1_000_000) ?: 0,
            operatorMessageFa = optionalText(payload, "operatorMessageFa", MAX_MESSAGE_LENGTH),
            updateManifestUrl = optionalEndpoint(payload, "updateManifestUrl", requireWebhookPath = false)
        )
    }

    /**
     * Installs the snapshot's literal sender/keyword additions into the SMS classifier.
     *
     * Separate from [parse] so validation stays pure and testable, and so a rejected document can
     * never partially mutate global matching state.
     */
    fun applyMatchingOverlay(snapshot: RemoteConfigSnapshot) {
        SmsParser.setRemoteOverlay(
            extraSenders = snapshot.extraOtpSenders,
            extraKeywords = snapshot.extraUtcmsKeywords
        )
    }

    private fun optionalEndpoint(
        payload: JSONObject,
        key: String,
        requireWebhookPath: Boolean = true
    ): String? {
        val raw = optionalText(payload, key, 2048) ?: return null
        val url = raw.toHttpUrlOrNull() ?: throw RejectedException("$key is not a valid URL")
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) {
            throw RejectedException("$key must not carry credentials or a fragment")
        }
        if (requireWebhookPath && !BarProContract.isPathValid(url.encodedPath)) {
            throw RejectedException("$key is not a BarPro OTP webhook path")
        }
        return url.toString()
    }

    private fun optionalToken(payload: JSONObject, key: String): String? {
        val raw = optionalText(payload, key, 512) ?: return null
        // The server compares the header bytes directly; anything unprintable cannot round-trip.
        if (raw.any { it.code !in 33..126 }) {
            throw RejectedException("$key must be printable ASCII without spaces")
        }
        return raw
    }

    private fun optionalPhone(payload: JSONObject, key: String): String? {
        val raw = optionalText(payload, key, 32) ?: return null
        val normalized = SmsParser.normalizePhoneNumber(raw)
        if (!normalized.matches(Regex("09[0-9]{9}"))) {
            throw RejectedException("$key is not a valid Iranian mobile number")
        }
        return normalized
    }

    private fun optionalText(payload: JSONObject, key: String, maxLength: Int): String? {
        if (!payload.has(key) || payload.isNull(key)) return null
        val value = payload.optString(key).trim()
        if (value.isEmpty()) return null
        if (value.length > maxLength) throw RejectedException("$key exceeds $maxLength characters")
        return value
    }

    private fun optionalInt(payload: JSONObject, key: String, min: Int, max: Int): Int? {
        if (!payload.has(key) || payload.isNull(key)) return null
        val value = payload.optInt(key, Int.MIN_VALUE)
        if (value == Int.MIN_VALUE) throw RejectedException("$key is not an integer")
        if (value < min || value > max) throw RejectedException("$key must be between $min and $max")
        return value
    }

    private fun optionalBoolean(payload: JSONObject, key: String): Boolean? {
        if (!payload.has(key) || payload.isNull(key)) return null
        return payload.optBoolean(key)
    }

    private fun literalList(
        payload: JSONObject,
        key: String,
        maxEntries: Int,
        maxLength: Int,
        shapeChecked: Boolean
    ): List<String> {
        if (!payload.has(key) || payload.isNull(key)) return emptyList()
        val array: JSONArray = payload.optJSONArray(key)
            ?: throw RejectedException("$key must be an array")
        if (array.length() > maxEntries) throw RejectedException("$key exceeds $maxEntries entries")
        val values = ArrayList<String>(array.length())
        for (index in 0 until array.length()) {
            val entry = array.optString(index).trim()
            if (entry.isEmpty()) continue
            if (entry.length > maxLength) throw RejectedException("$key[$index] exceeds $maxLength characters")
            if (shapeChecked && !SENDER_SHAPE.matches(entry)) {
                throw RejectedException("$key[$index] is not a literal sender id")
            }
            values.add(entry)
        }
        return values
    }
}
