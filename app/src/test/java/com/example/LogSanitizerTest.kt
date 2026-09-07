package com.example

import com.example.utils.LogSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSanitizerTest {

    @Test
    fun testOtpMasking() {
        val input = "کد ورود شما به سامانه بارنامه 49201 می باشد."
        val sanitized = LogSanitizer.sanitize(input)
        assertFalse(sanitized.contains("49201"))
        assertTrue(sanitized.contains("***"))
        assertEquals("کد ورود شما به سامانه بارنامه *** می باشد.", sanitized)
    }

    @Test
    fun testIranianPhoneMasking() {
        val input = "ارسال بارنامه برای راننده 09123456789 با موفقیت انجام شد."
        val sanitized = LogSanitizer.sanitize(input)
        assertFalse(sanitized.contains("09123456789"))
        assertTrue(sanitized.contains("09***"))
        assertEquals("ارسال بارنامه برای راننده 09*** با موفقیت انجام شد.", sanitized)
    }

    @Test
    fun testDirectMaskHelpers() {
        assertEquals("09***", LogSanitizer.maskPhone("09333702137"))
        assertEquals("***", LogSanitizer.maskOtp("12345"))
    }
}
