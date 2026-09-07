package com.example

import com.example.crypto.AesEncryptionUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AesEncryptionUtilsTest {

    private val testSecretKey = "BarProSuperSecretEncryptionKey2026!#"
    private val testSmsMessage = "سامانه بارنامه شهرداری: کد ورود شما ۳۹۱۸۲ می باشد."

    @Test
    fun testAesGcmEncryptionAndDecryption() {
        val encrypted = AesEncryptionUtils.encrypt(testSmsMessage, testSecretKey)

        assertTrue(encrypted.iv.isNotBlank())
        assertTrue(encrypted.ciphertext.isNotBlank())
        assertTrue(encrypted.authTagOrHmac.isNotBlank())
        assertNotEquals(testSmsMessage, encrypted.ciphertext)

        val decrypted = AesEncryptionUtils.decrypt(
            ivBase64 = encrypted.iv,
            ciphertextBase64 = encrypted.ciphertext,
            secretKey = testSecretKey
        )

        assertEquals(testSmsMessage, decrypted)
    }

    @Test
    fun testAesCbcEncryptionAndDecryption() {
        val encrypted = AesEncryptionUtils.encryptCbc(testSmsMessage, testSecretKey)

        assertTrue(encrypted.iv.isNotBlank())
        assertTrue(encrypted.ciphertext.isNotBlank())
        assertNotEquals(testSmsMessage, encrypted.ciphertext)

        val decrypted = AesEncryptionUtils.decryptCbc(
            ivBase64 = encrypted.iv,
            ciphertextBase64 = encrypted.ciphertext,
            secretKey = testSecretKey
        )

        assertEquals(testSmsMessage, decrypted)
    }

    @Test
    fun testHmacVerificationConstantTime() {
        val data = "test-payload-to-sign"
        val hmac = AesEncryptionUtils.computeHmac(data, testSecretKey)

        assertTrue(AesEncryptionUtils.verifyHmac(data, hmac, testSecretKey))
        assertFalse(AesEncryptionUtils.verifyHmac(data, "invalidHmac", testSecretKey))
        assertFalse(AesEncryptionUtils.verifyHmac(data, "", testSecretKey))
    }

    @Test
    fun testPbkdf2WithSaltDerivation() {
        val salt1 = "RandomSalt123456".toByteArray()
        val key1 = AesEncryptionUtils.deriveKey(testSecretKey, salt1)
        val key2 = AesEncryptionUtils.deriveKey(testSecretKey, salt1)
        assertEquals(key1, key2)

        val salt2 = "DifferentSalt987".toByteArray()
        val key3 = AesEncryptionUtils.deriveKey(testSecretKey, salt2)
        assertNotEquals(key1.encoded.toList(), key3.encoded.toList())
    }

    @Test
    fun testGenerateSecureKey() {
        val key = AesEncryptionUtils.generateSecureKey(32)
        assertEquals(32, key.length)
    }
}
