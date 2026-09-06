package com.example

import com.example.data.model.SmsType
import com.example.utils.SmsParser
import org.junit.Assert.*
import org.junit.Test

class SmsParserTest {

    @Test
    fun testNormalizeDigits_persianAndArabic() {
        val persian = "کد رهگیری: ۱۲۳۴۵۶۷۸"
        val normalized = SmsParser.normalizeDigits(persian)
        assertEquals("کد رهگیری: 12345678", normalized)

        val arabic = "رمز: ٩٨٧٦٥٤"
        val normalizedArabic = SmsParser.normalizeDigits(arabic)
        assertEquals("رمز: 987654", normalizedArabic)
    }

    @Test
    fun testIsUtcmsSms_validUtcmsMessage() {
        val sender = "10001234"
        val message = "بارنامه شماره ۹۸۷۶۵۴ در سامانه UTCMS با موفقیت صادر گردید."
        assertTrue(SmsParser.isUtcmsSms(sender, message))

        val randomSender = "09121111111"
        val utcmsMessage = "راننده گرامی کد رهگیری بارنامه شما 789123 است"
        assertTrue(SmsParser.isUtcmsSms(randomSender, utcmsMessage))

        val personalMessage = "سلام، فردا ساعت ۱۰ کجایی؟"
        assertFalse(SmsParser.isUtcmsSms(randomSender, personalMessage))
    }

    @Test
    fun testExtractTrackingCode() {
        val message1 = "بارنامه شما ثبت شد. کد رهگیری: 98765432"
        assertEquals("98765432", SmsParser.extractTrackingCode(message1))

        val message2 = "سامانه بارپرو: شماره بارنامه: ۴۵۶۷۸۹۰ ثبت گردید"
        assertEquals("4567890", SmsParser.extractTrackingCode(message2))
    }

    @Test
    fun testExtractOtp() {
        val message = "کد تایید ورود شما به سامانه بارپرو: 458921"
        assertEquals("458921", SmsParser.extractOtp(message))

        val messagePersian = "رمز یکبار مصرف شما: ۷۶۵۴۳۲"
        assertEquals("765432", SmsParser.extractOtp(messagePersian))
    }

    @Test
    fun testExtractOtp_pdfPlanCases() {
        // Case 1: English 5-digit code
        val msg1 = "کد تایید شما برای بارنامه: 48291"
        assertEquals("48291", SmsParser.extractOtp(msg1))

        // Case 2: Persian 5-digit code with multi-line message
        val msg2 = "سامانه بارنامه\nکد تایید: ۲۱۴۵۹\nاین کد را به راننده تحویل ندهید."
        assertEquals("21459", SmsParser.extractOtp(msg2))

        // Case 3: Municipal Waybill OTP from spec
        val msg3 = "سامانه بارنامه شهرداری: کد ورود شما ۳۹۱۸۲ می باشد"
        assertEquals("39182", SmsParser.extractOtp(msg3))

        // Case 4: Confirmation message with no OTP - should return null
        val msgNoOtp = "بارنامه با موفقیت ثبت شد"
        assertNull(SmsParser.extractOtp(msgNoOtp))
    }

    @Test
    fun testNormalizePhoneNumber_allFormats() {
        // International with +
        assertEquals("09333702137", SmsParser.normalizePhoneNumber("+989333702137"))
        // International without +
        assertEquals("09333702137", SmsParser.normalizePhoneNumber("989333702137"))
        // International with 00
        assertEquals("09333702137", SmsParser.normalizePhoneNumber("00989333702137"))
        // 10 digits without leading zero
        assertEquals("09333702137", SmsParser.normalizePhoneNumber("9333702137"))
        // Standard 11 digits
        assertEquals("09333702137", SmsParser.normalizePhoneNumber("09333702137"))
        // Persian digits
        assertEquals("09333702137", SmsParser.normalizePhoneNumber("۰۹۳۳۳۷۰۲۱۳۷"))
    }

    @Test
    fun testEveningOtpWindow() {
        val eveningCal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 18)
            set(java.util.Calendar.MINUTE, 0)
        }
        assertTrue(SmsParser.isEveningOtpWindow(eveningCal))

        val nightCal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 2)
            set(java.util.Calendar.MINUTE, 30)
        }
        assertTrue(SmsParser.isEveningOtpWindow(nightCal))

        val dayCal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 12)
            set(java.util.Calendar.MINUTE, 0)
        }
        assertFalse(SmsParser.isEveningOtpWindow(dayCal))
    }

    @Test
    fun testDetectSmsType() {
        val confirmMsg = "بارنامه شما در سامانه UTCMS صادر شد. کد رهگیری: 55443322"
        assertEquals(SmsType.UTCMS_CONFIRMATION, SmsParser.detectSmsType(confirmMsg))

        val otpMsg = "کد تایید احراز هویت راننده: 123456"
        assertEquals(SmsType.UTCMS_OTP, SmsParser.detectSmsType(otpMsg))

        val warnMsg = "هشدار: پایان اعتبار کارت هوشمند راننده و سهمیه سوخت"
        assertEquals(SmsType.UTCMS_WARNING, SmsParser.detectSmsType(warnMsg))

        val generalMsg = "به فروشگاه زنجیره‌ای خوش آمدید"
        assertEquals(SmsType.OTHER, SmsParser.detectSmsType(generalMsg))
    }
}
