package com.example

import com.example.crypto.AesEncryptionUtils
import org.junit.Assert.assertEquals
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
    fun testHmacVerification() {
        val data = "test-payload-to-sign"
        val hmac = AesEncryptionUtils.computeHmac(data, testSecretKey)

        assertTrue(AesEncryptionUtils.verifyHmac(data, hmac, testSecretKey))
        org.junit.Assert.assertFalse(AesEncryptionUtils.verifyHmac(data, "invalidHmac", testSecretKey))
    }

    @Test
    fun testGenerateSecureKey() {
        val key = AesEncryptionUtils.generateSecureKey(32)
        assertEquals(32, key.length)
    }
}
