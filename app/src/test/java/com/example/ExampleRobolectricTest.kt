package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.CryptoEngine
import com.example.data.model.FilterRule
import com.example.data.model.MatchType
import com.example.otp.OtpExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `verify app name resource is BarPro Forwarder`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("BarPro Forwarder", appName)
    }

    @Test
    fun `verify OTP extractor extracts Persian and English codes`() {
        // Persian digits test
        val persianMsg = "کد ورود به سامانه بارپرو (BarPro): ۸۴۹۲۰۱ اعتبار ۲ دقیقه"
        val otp1 = OtpExtractor.extractOtp(persianMsg)
        assertEquals("849201", otp1)

        // English digits test
        val englishMsg = "Your security OTP code is 738192. Do not share with anyone."
        val otp2 = OtpExtractor.extractOtp(englishMsg)
        assertEquals("738192", otp2)

        // Mixed test
        val mixedMsg = "رمز یکبار مصرف شما: 58219"
        val otp3 = OtpExtractor.extractOtp(mixedMsg)
        assertEquals("58219", otp3)
    }

    @Test
    fun `verify AES-256-GCM encryption and decryption roundtrip`() {
        val plainText = """{"sender":"BAR PRO","otp":"849201","status":"CONFIRMED"}"""
        val secretKey = "BarProSecretKey2026AESGCM!"

        val encryptedPackage = CryptoEngine.encrypt(plainText, secretKey)
        assertNotNull(encryptedPackage.ivBase64)
        assertNotNull(encryptedPackage.ciphertextBase64)
        assertNotNull(encryptedPackage.signatureHmac)

        val decrypted = CryptoEngine.decrypt(
            ivBase64 = encryptedPackage.ivBase64!!,
            ciphertextBase64 = encryptedPackage.ciphertextBase64!!,
            secretKey = secretKey
        )
        assertTrue(decrypted.success)
        assertEquals(plainText, decrypted.plaintext)
    }

    @Test
    fun `verify filter rules matching logic`() {
        val barProRule = FilterRule(
            senderPattern = "BAR PRO",
            matchType = MatchType.CONTAINS,
            label = "سامانه بارپرو",
            isEnabled = true
        )
        assertTrue(barProRule.matches("BAR PRO", "پیامک سامانه"))

        val prefixRule = FilterRule(
            senderPattern = "2000",
            matchType = MatchType.PREFIX,
            label = "سرشماره ۲۰۰۰",
            isEnabled = true
        )
        assertTrue(prefixRule.matches("20008492", "کد ورود: 948102"))
    }

    @Test
    fun `verify permission manager categories and required items`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val list = com.example.utils.PermissionHelper.getAllPermissionsStatus(context)
        
        // Ensure items exist
        assertTrue(list.isNotEmpty())

        // Check categories
        val hasSms = list.any { it.category == com.example.utils.PermissionCategory.SMS }
        val hasNotification = list.any { it.category == com.example.utils.PermissionCategory.NOTIFICATION }
        val hasBackground = list.any { it.category == com.example.utils.PermissionCategory.BACKGROUND }

        assertTrue("Should include SMS category permissions", hasSms)
        assertTrue("Should include Notification category permissions", hasNotification)
        assertTrue("Should include Background category permissions", hasBackground)

        // Verify required permissions
        val requiredItems = list.filter { it.isRequired }
        assertTrue(requiredItems.any { it.id == "receive_sms" })
        assertTrue(requiredItems.any { it.id == "read_sms" })
    }
}
