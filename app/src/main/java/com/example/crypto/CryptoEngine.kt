package com.example.crypto

import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptionResult(
    val encrypted: Boolean,
    val ivBase64: String?,
    val ciphertextBase64: String?,
    val signatureHmac: String?,
    val algorithm: String = "AES-256-GCM"
)

data class DecryptionResult(
    val success: Boolean,
    val plaintext: String?,
    val errorMessage: String?
)

object CryptoEngine {
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val HMAC_ALGORITHM = "HmacSHA256"

    /**
     * Derives a 256-bit AES Key Spec from user secret string using SHA-256.
     */
    private fun deriveKey(secretKeyString: String): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(secretKeyString.toByteArray(StandardCharsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Encrypts plaintext string using AES-256-GCM and computes HMAC-SHA256.
     */
    fun encrypt(plaintext: String, secretKey: String): EncryptionResult {
        return try {
            val keySpec = deriveKey(secretKey)
            val iv = ByteArray(GCM_IV_LENGTH_BYTES)
            SecureRandom().nextBytes(iv)

            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

            val ciphertextBytes = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))

            val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val ciphertextBase64 = Base64.encodeToString(ciphertextBytes, Base64.NO_WRAP)

            // Compute HMAC over IV + Ciphertext
            val hmac = computeHmac("$ivBase64.$ciphertextBase64", secretKey)

            EncryptionResult(
                encrypted = true,
                ivBase64 = ivBase64,
                ciphertextBase64 = ciphertextBase64,
                signatureHmac = hmac
            )
        } catch (e: Exception) {
            EncryptionResult(
                encrypted = false,
                ivBase64 = null,
                ciphertextBase64 = null,
                signatureHmac = null
            )
        }
    }

    /**
     * Decrypts AES-256-GCM ciphertext using the given secret key.
     */
    fun decrypt(ivBase64: String, ciphertextBase64: String, secretKey: String): DecryptionResult {
        return try {
            val keySpec = deriveKey(secretKey)
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
            val ciphertextBytes = Base64.decode(ciphertextBase64, Base64.NO_WRAP)

            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

            val decryptedBytes = cipher.doFinal(ciphertextBytes)
            val plaintext = String(decryptedBytes, StandardCharsets.UTF_8)
            DecryptionResult(success = true, plaintext = plaintext, errorMessage = null)
        } catch (e: Exception) {
            DecryptionResult(
                success = false,
                plaintext = null,
                errorMessage = e.localizedMessage ?: "Decryption failed (invalid key or tampered data)"
            )
        }
    }

    /**
     * Computes HMAC-SHA256 signature for data integrity and authentication.
     */
    fun computeHmac(data: String, secretKey: String): String {
        return try {
            val secretKeyBytes = secretKey.toByteArray(StandardCharsets.UTF_8)
            val hmacKey = SecretKeySpec(secretKeyBytes, HMAC_ALGORITHM)
            val mac = Mac.getInstance(HMAC_ALGORITHM)
            mac.init(hmacKey)
            val hmacBytes = mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
            Base64.encodeToString(hmacBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Generates a cryptographically strong 32-character random key.
     */
    fun generateRandomKey(length: Int = 32): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#%^&*-_="
        val random = SecureRandom()
        val sb = StringBuilder(length)
        for (i in 0 until length) {
            sb.append(chars[random.nextInt(chars.length)])
        }
        return sb.toString()
    }
}
