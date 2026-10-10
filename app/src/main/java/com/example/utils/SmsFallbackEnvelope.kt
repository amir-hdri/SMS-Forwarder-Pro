package com.example.utils

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class ParsedEnvelope(
    val phone: String,
    val timestamp: Long,
    val code: String,
    val signature: String
)

/** One ASCII SMS; the original SMS time binds retries to the HTTP delivery identity. */
object SmsFallbackEnvelope {
    fun encode(phone: String, timestamp: Long, code: String, secret: String): String {
        val recipient = SmsParser.normalizePhoneNumber(phone)
        require(recipient.matches(Regex("09[0-9]{9}")))
        require(code.matches(Regex("([0-9]{4,8}|TEST)")))
        require(timestamp > 0 && secret.isNotBlank())
        val payload = "BP1#" + recipient + "#" + timestamp + "#" + code
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = mac.doFinal(payload.toByteArray(Charsets.US_ASCII))
            .take(16).joinToString("") { "%02x".format(it) }
        return payload + "#" + signature
    }

    fun parse(text: String): ParsedEnvelope? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("BP1#")) return null
        val parts = trimmed.split("#")
        if (parts.size != 5 || parts[0] != "BP1") return null
        val phone = SmsParser.normalizePhoneNumber(parts[1])
        if (!phone.matches(Regex("09[0-9]{9}"))) return null
        val ts = parts[2].toLongOrNull() ?: return null
        if (ts <= 0) return null
        val code = parts[3]
        if (!code.matches(Regex("([0-9]{4,8}|TEST)"))) return null
        val signature = parts[4].lowercase()
        if (signature.length != 32 || !signature.matches(Regex("[0-9a-f]{32}"))) return null
        return ParsedEnvelope(phone, ts, code, signature)
    }

    /**
     * Recomputes the envelope HMAC and compares it in constant time.
     *
     * The server (`/api/v1/otp/sms-gateway`, `hmac.compare_digest`) remains the authority; this is
     * the Hub-side pre-filter that drops forged envelopes before they consume a network round-trip
     * and an outbox slot.
     */
    fun verify(envelope: ParsedEnvelope, secret: String): Boolean {
        if (secret.isBlank()) return false
        val payload = "BP1#" + envelope.phone + "#" + envelope.timestamp + "#" + envelope.code
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val expected = mac.doFinal(payload.toByteArray(Charsets.US_ASCII))
            .take(16).joinToString("") { "%02x".format(it) }
        return constantTimeEquals(expected, envelope.signature.lowercase())
    }

    /**
     * Length-aware, data-independent comparison backed by [MessageDigest.isEqual], which the JDK
     * documents as not leaking timing information about the contents. Avoids `String.equals`, which
     * short-circuits on the first differing character and would reveal how much of a forged
     * signature was correct. Signature length is fixed at 32 hex chars by [parse], so the
     * length check leaks nothing.
     */
    internal fun constantTimeEquals(a: String, b: String): Boolean = MessageDigest.isEqual(
        a.toByteArray(Charsets.US_ASCII),
        b.toByteArray(Charsets.US_ASCII)
    )
}
