package com.example.utils

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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
}
