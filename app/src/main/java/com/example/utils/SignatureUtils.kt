package com.example.utils

import android.util.Base64
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Utility for generating cryptographic HMAC-SHA256 signatures for forwarded SMS messages.
 * Matches BarPro server validation format:
 * data = "$driverId\n$phoneNumber\n$messageBody\n$timestamp"
 */
object SignatureUtils {

    private const val HMAC_ALGORITHM = "HmacSHA256"

    /**
     * Generates a deterministic Base64-encoded HMAC-SHA256 signature including timestamp and nonce
     * to protect against header tampering and replay attacks.
     */
    fun generateSignature(
        driverId: String,
        phoneNumber: String,
        messageBody: String,
        timestamp: Long,
        nonce: String = "",
        secretKey: String
    ): String {
        val effectiveKey = secretKey.ifBlank { "barpro-default-secret-key-32bytes" }
        val data = if (nonce.isNotBlank()) {
            """
                $driverId
                $phoneNumber
                $messageBody
                $timestamp
                $nonce
            """.trimIndent()
        } else {
            """
                $driverId
                $phoneNumber
                $messageBody
                $timestamp
            """.trimIndent()
        }

        return try {
            val hmac = Mac.getInstance(HMAC_ALGORITHM)
            val keySpec = SecretKeySpec(effectiveKey.toByteArray(StandardCharsets.UTF_8), HMAC_ALGORITHM)
            hmac.init(keySpec)
            val hash = hmac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
            Base64.encodeToString(hash, Base64.NO_WRAP)
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Backward-compatible overload without nonce.
     */
    fun generateSignature(
        driverId: String,
        phoneNumber: String,
        messageBody: String,
        timestamp: Long,
        secretKey: String
    ): String = generateSignature(
        driverId = driverId,
        phoneNumber = phoneNumber,
        messageBody = messageBody,
        timestamp = timestamp,
        nonce = "",
        secretKey = secretKey
    )
}
