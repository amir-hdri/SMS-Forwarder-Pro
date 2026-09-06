package com.example.crypto

import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Data container representing the encrypted SMS payload output.
 */
data class EncryptedSmsPayload(
    val iv: String,
    val ciphertext: String,
    val authTagOrHmac: String,
    val algorithm: String = AesEncryptionUtils.ALGORITHM_GCM
)

/**
 * Encryption utility class implementing AES-256 (in both GCM authenticated mode and CBC mode)
 * to securely encrypt and decrypt SMS content, sensitive verification codes, and transmission payloads.
 */
object AesEncryptionUtils {

    const val ALGORITHM_GCM = "AES/GCM/NoPadding"
    const val ALGORITHM_CBC = "AES/CBC/PKCS5Padding"
    private const val AES = "AES"
    private const val HMAC_SHA256 = "HmacSHA256"

    private const val GCM_IV_LENGTH = 12 // 96-bit recommended IV for AES-GCM
    private const val GCM_TAG_LENGTH_BITS = 128 // 128-bit authentication tag
    private const val CBC_IV_LENGTH = 16 // 128-bit IV for AES-CBC

    /**
     * Derives a compliant 256-bit (32-byte) AES SecretKeySpec from any passphrase or secret key
     * using cryptographic SHA-256 hashing.
     */
    fun deriveKey(passphrase: String): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(passphrase.toByteArray(StandardCharsets.UTF_8))
        return SecretKeySpec(keyBytes, AES)
    }

    /**
     * Encrypts plaintext SMS content using AES-256-GCM (Galois/Counter Mode).
     * Provides authenticated encryption (confidentiality + integrity).
     *
     * @param plainText The SMS message or payload string to encrypt.
     * @param secretKey The secret key or passphrase.
     * @return [EncryptedSmsPayload] containing Base64 encoded IV, ciphertext, and HMAC signature.
     */
    fun encrypt(plainText: String, secretKey: String): EncryptedSmsPayload {
        require(secretKey.isNotBlank()) { "Secret key cannot be empty" }

        val keySpec = deriveKey(secretKey)
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance(ALGORITHM_GCM)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

        val ciphertextBytes = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))

        val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val ciphertextBase64 = Base64.encodeToString(ciphertextBytes, Base64.NO_WRAP)
        val hmac = computeHmac("$ivBase64.$ciphertextBase64", secretKey)

        return EncryptedSmsPayload(
            iv = ivBase64,
            ciphertext = ciphertextBase64,
            authTagOrHmac = hmac,
            algorithm = ALGORITHM_GCM
        )
    }

    /**
     * Decrypts an AES-256-GCM encrypted SMS payload.
     *
     * @param ivBase64 Base64 encoded initialization vector (IV).
     * @param ciphertextBase64 Base64 encoded ciphertext including the GCM authentication tag.
     * @param secretKey The secret key or passphrase used during encryption.
     * @return The original decrypted plaintext SMS.
     * @throws Exception if key is invalid, data is corrupted, or authentication fails.
     */
    fun decrypt(ivBase64: String, ciphertextBase64: String, secretKey: String): String {
        require(secretKey.isNotBlank()) { "Secret key cannot be empty" }

        val keySpec = deriveKey(secretKey)
        val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
        val ciphertextBytes = Base64.decode(ciphertextBase64, Base64.NO_WRAP)

        val cipher = Cipher.getInstance(ALGORITHM_GCM)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

        val decryptedBytes = cipher.doFinal(ciphertextBytes)
        return String(decryptedBytes, StandardCharsets.UTF_8)
    }

    /**
     * Encrypts SMS content using AES-256-CBC with PKCS5 padding for legacy server compatibility.
     */
    fun encryptCbc(plainText: String, secretKey: String): EncryptedSmsPayload {
        require(secretKey.isNotBlank()) { "Secret key cannot be empty" }

        val keySpec = deriveKey(secretKey)
        val iv = ByteArray(CBC_IV_LENGTH)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance(ALGORITHM_CBC)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(iv))

        val ciphertextBytes = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val ciphertextBase64 = Base64.encodeToString(ciphertextBytes, Base64.NO_WRAP)
        val hmac = computeHmac("$ivBase64.$ciphertextBase64", secretKey)

        return EncryptedSmsPayload(
            iv = ivBase64,
            ciphertext = ciphertextBase64,
            authTagOrHmac = hmac,
            algorithm = ALGORITHM_CBC
        )
    }

    /**
     * Decrypts AES-256-CBC encrypted ciphertext.
     */
    fun decryptCbc(ivBase64: String, ciphertextBase64: String, secretKey: String): String {
        require(secretKey.isNotBlank()) { "Secret key cannot be empty" }

        val keySpec = deriveKey(secretKey)
        val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
        val ciphertextBytes = Base64.decode(ciphertextBase64, Base64.NO_WRAP)

        val cipher = Cipher.getInstance(ALGORITHM_CBC)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))

        val decryptedBytes = cipher.doFinal(ciphertextBytes)
        return String(decryptedBytes, StandardCharsets.UTF_8)
    }

    /**
     * Computes HMAC-SHA256 signature for transmission tamper-detection.
     */
    fun computeHmac(data: String, secretKey: String): String {
        val secretKeyBytes = secretKey.toByteArray(StandardCharsets.UTF_8)
        val hmacKey = SecretKeySpec(secretKeyBytes, HMAC_SHA256)
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(hmacKey)
        val hmacBytes = mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(hmacBytes, Base64.NO_WRAP)
    }

    /**
     * Verifies that the given HMAC signature matches the calculated signature for the data.
     */
    fun verifyHmac(data: String, receivedHmac: String, secretKey: String): Boolean {
        val calculated = computeHmac(data, secretKey)
        return calculated == receivedHmac
    }

    /**
     * Generates a cryptographically secure 256-bit AES random key encoded in Base64 or alphanumeric.
     */
    fun generateSecureKey(length: Int = 32): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#%*-_=+"
        val random = SecureRandom()
        val sb = StringBuilder(length)
        for (i in 0 until length) {
            sb.append(chars[random.nextInt(chars.length)])
        }
        return sb.toString()
    }
}
