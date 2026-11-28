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
        assertFalse("Uncoordinated sender must be rejected", SmsParser.isUtcmsSms(randomSender, utcmsMessage))

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
        assertEquals("458921", SmsParser.extractOtp(message, sender = "7777000982"))
        assertNull("Blank sender must never extract OTP", SmsParser.extractOtp(message))
        assertNull("Uncoordinated sender must never extract OTP", SmsParser.extractOtp(message, sender = "09121111111"))

        val messagePersian = "رمز یکبار مصرف شما: ۷۶۵۴۳۲"
        assertEquals("765432", SmsParser.extractOtp(messagePersian, sender = "20007777"))
    }

    @Test
    fun testExtractOtp_pdfPlanCases() {
        // Coordinated sender + 6-digit numeric OTPs (English, Persian, standalone)
        val msg1 = "کد تایید شما برای بارنامه: 482916"
        assertEquals("482916", SmsParser.extractOtp(msg1, sender = "20007777"))

        val msg2 = "سامانه بارنامه\nکد تایید: ۲۱۴۵۹۷\nاین کد را به راننده تحویل ندهید."
        assertEquals("214597", SmsParser.extractOtp(msg2, sender = "7777000982"))

        val msg3 = "سامانه بارنامه شهرداری: کد ورود شما ۳۹۱۸۲۴ می باشد"
        assertEquals("391824", SmsParser.extractOtp(msg3, sender = "UTCMS"))

        // Bare/keywordless 6-digit numeric code from coordinated sender is accepted as OTP
        assertEquals("833282", SmsParser.extractOtp("کد شما 833282 است", sender = "7777000982"))
        assertEquals("833282", SmsParser.extractOtp("833282", sender = "10008545"))
        assertNull(SmsParser.extractOtp("عدد مرجع 833282", sender = "10008545"))
        assertNull(SmsParser.extractOtp("سامانه بارنامه: 833282 456789", sender = "10008545"))

        // 4, 5, 7, 8-digit numbers must NOT be treated as OTPs
        assertNull(SmsParser.extractOtp("کد تایید: 48291", sender = "7777000982"))
        assertNull(SmsParser.extractOtp("کد تایید: 4829", sender = "7777000982"))
        assertNull(SmsParser.extractOtp("کد تایید: 1234567", sender = "7777000982"))

        // Case 4: Confirmation message with no OTP - should return null
        val msgNoOtp = "بارنامه با موفقیت ثبت شد"
        assertNull(SmsParser.extractOtp(msgNoOtp, sender = "20007777"))
        assertNull(SmsParser.extractOtp("بارنامه شماره 987654 صادر گردید", sender = "10001234"))
    }

    @Test
    fun testNormalizePhoneNumber_allFormats() {
        // Standard 11 digits
        assertEquals("09303653432", SmsParser.normalizePhoneNumber("09303653432"))
        // International with + and spaces
        assertEquals("09303653432", SmsParser.normalizePhoneNumber("+98 930 365 3432"))
        // International without spaces
        assertEquals("09303653432", SmsParser.normalizePhoneNumber("+989303653432"))
        // International with 00
        assertEquals("09303653432", SmsParser.normalizePhoneNumber("00989303653432"))
        assertEquals("09303653432", SmsParser.normalizePhoneNumber("0098 930 365 3432"))
        // Formatted with spaces and hyphens
        assertEquals("09303653432", SmsParser.normalizePhoneNumber("0930 365 3432"))
        assertEquals("09303653432", SmsParser.normalizePhoneNumber("0930-365-3432"))
        // Persian digits
        assertEquals("09303653432", SmsParser.normalizePhoneNumber("۰۹۳۰۳۶۵۳۴۳۲"))
        assertEquals("09303653432", SmsParser.normalizePhoneNumber(" ۰۹۳۰ ۳۶۵ ۳۴۳۲ "))
        // Arabic digits
        assertEquals("09303653432", SmsParser.normalizePhoneNumber("٠٩٣٠٣٦٥٣٤٣٢"))
        // 10 digits without leading zero
        assertEquals("09303653432", SmsParser.normalizePhoneNumber("9303653432"))
        // Edge cases: +98 and 0098 with leading zero (common user mistakes)
        assertEquals("09303653432", SmsParser.normalizePhoneNumber("+98 0930 365 3432"))
        assertEquals("09303653432", SmsParser.normalizePhoneNumber("+9809303653432"))
        assertEquals("09303653432", SmsParser.normalizePhoneNumber("009809303653432"))
        assertEquals("09303653432", SmsParser.normalizePhoneNumber("0098 0930 365 3432"))

        // Validation
        assertTrue(SmsParser.isValidIranianMobile("09303653432"))
        assertTrue(SmsParser.isValidIranianMobile("+98 930 365 3432"))
        assertTrue(SmsParser.isValidIranianMobile("۰۹۳۰۳۶۵۳۴۳۲"))
        assertFalse(SmsParser.isValidIranianMobile("12345"))
        assertFalse(SmsParser.isValidIranianMobile(""))
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
        assertEquals(SmsType.UTCMS_CONFIRMATION, SmsParser.detectSmsType(confirmMsg, sender = "UTCMS"))

        val otpMsg = "کد تایید احراز هویت راننده: 123456"
        assertEquals(SmsType.UTCMS_OTP, SmsParser.detectSmsType(otpMsg, sender = "7777000982"))

        val warnMsg = "هشدار: پایان اعتبار کارت هوشمند راننده و سهمیه سوخت"
        assertEquals(SmsType.UTCMS_WARNING, SmsParser.detectSmsType(warnMsg, sender = "10008545"))

        val generalMsg = "به فروشگاه زنجیره‌ای خوش آمدید"
        assertEquals(SmsType.OTHER, SmsParser.detectSmsType(generalMsg, sender = "50001234"))
    }

    @Test
    fun testIsBankOrAdSender() {
        // Banks
        assertTrue(SmsParser.isBankOrAdSender("TejaratBank"))
        assertTrue(SmsParser.isBankOrAdSender("MellatBank"))
        assertTrue(SmsParser.isBankOrAdSender("bankmelli"))
        assertTrue(SmsParser.isBankOrAdSender("بانک تجارت"))
        assertTrue(SmsParser.isBankOrAdSender("20004000"))

        // Advertising
        assertTrue(SmsParser.isBankOrAdSender("5000123456"))
        assertTrue(SmsParser.isBankOrAdSender("90007890"))
        assertTrue(SmsParser.isBankOrAdSender("تبلیغات فروشگاه"))

        // Legitimate Transit / UTCMS / BarPro
        assertFalse(SmsParser.isBankOrAdSender("10001234"))
        assertFalse(SmsParser.isBankOrAdSender("UTCMS"))
        assertFalse(SmsParser.isBankOrAdSender("BARPRO"))
        assertFalse(SmsParser.isBankOrAdSender("09123456789"))
    }

    @Test
    fun testExtractOtp_bankAndAdFiltering() {
        // Bank sender with code
        val bankMsg = "رمز پویا کارت شما: 12345"
        assertNull(SmsParser.extractOtp(bankMsg, sender = "TejaratBank"))
        assertNull(SmsParser.extractOtp(bankMsg, sender = "MellatBank"))

        // Bank transaction content
        val transactionMsg = "برداشت از حساب: 500,000 ریال. رمز پویا: 54321"
        assertNull(SmsParser.extractOtp(transactionMsg, sender = "09120000000"))

        // Advertising content
        val adMsg = "کد تخفیف ویژه خرید اول: 98765"
        assertNull(SmsParser.extractOtp(adMsg, sender = "50001234"))

        // Strong Transit / BarPro Waybill 6-digit OTP
        val validWaybillOtp = "کد تایید بارنامه شما در سامانه بارپرو: 741256"
        assertEquals("741256", SmsParser.extractOtp(validWaybillOtp, sender = "10001234"))

        val municipalOtp = "سامانه بارنامه شهرداری: کد ورود شما ۳۹۱۸۲۴ می باشد"
        assertEquals("391824", SmsParser.extractOtp(municipalOtp, sender = "UTCMS"))
    }

    @Test
    fun testWhitelistedGatewaySenders_andPhotoSamples() {
        // Numbers from user specification and screenshot:
        // +9830002128002150, +9850002710040127, 20007777, 30001923, 10008545
        assertFalse(SmsParser.isBankOrAdSender("+9830002128002150"))
        assertFalse(SmsParser.isBankOrAdSender("30002128002150"))
        assertFalse(SmsParser.isBankOrAdSender("+9850002710040127"))
        assertFalse(SmsParser.isBankOrAdSender("50002710040127"))
        assertFalse(SmsParser.isBankOrAdSender("20007777"))
        assertFalse(SmsParser.isBankOrAdSender("+9820007777"))
        assertFalse(SmsParser.isBankOrAdSender("30001923"))
        assertFalse(SmsParser.isBankOrAdSender("+9830001923"))
        assertFalse(SmsParser.isBankOrAdSender("10008545"))
        assertFalse(SmsParser.isBankOrAdSender("+9810008545"))

        assertFalse(SmsParser.isBankOrAdSender("7777000982"))
        assertFalse(SmsParser.isBankOrAdSender("+987777000982"))
        assertFalse(SmsParser.isBankOrAdSender("7777"))

        // Verification of isUtcmsSms (coordinated senders only)
        assertTrue(SmsParser.isUtcmsSms("+9830002128002150", "کد ورود: 489216"))
        assertTrue(SmsParser.isUtcmsSms("+9850002710040127", "کد ورود: 591234"))
        assertFalse(SmsParser.isUtcmsSms("randomSender", "ircars.vip کد ورود شما: 712345"))
        assertTrue(SmsParser.isUtcmsSms("20007777", "کد تایید صدور بارنامه: 981246"))
        assertTrue(SmsParser.isUtcmsSms("30001923", "کد ورود: 654321"))
        assertTrue(SmsParser.isUtcmsSms("10008545", "کد تأیید: 123456"))
        assertTrue(SmsParser.isUtcmsSms("7777000982", "کد ورود: 833282"))
        assertTrue(SmsParser.isUtcmsSms("+987777000982", "کد ورود: 833282"))
        assertTrue(SmsParser.isUtcmsSms("7777", "کد ورود: 833282"))

        // Extraction of 6-digit OTP from coordinated messages
        val msg1 = "کد ورود: 849201"
        assertEquals("849201", SmsParser.extractOtp(msg1, sender = "+9830002128002150"))

        val msg2 = "ircars.vip کد ورود: 849201"
        assertEquals("849201", SmsParser.extractOtp(msg2, sender = "+9830002128002150"))

        val msg3 = "کد ورود شما ۵۹۳۸۱۴ می باشد"
        assertEquals("593814", SmsParser.extractOtp(msg3, sender = "+9850002710040127"))

        val msg4 = "سامانه بارنامه - کد تایید: ۱۲۳۴۵۶"
        assertEquals("123456", SmsParser.extractOtp(msg4, sender = "20007777"))

        val msg5 = "کد ورود: 678901"
        assertEquals("678901", SmsParser.extractOtp(msg5, sender = "30001923"))

        val msg6 = "کد تایید شما: 135792"
        assertEquals("135792", SmsParser.extractOtp(msg6, sender = "10008545"))

        // Production real SMS case: 7777000982 / 6-digit OTP
        val msgReal = "کد ورود: 833282"
        assertEquals("833282", SmsParser.extractOtp(msgReal, sender = "7777000982"))
        assertEquals("833282", SmsParser.extractOtp(msgReal, sender = "+987777000982"))
        assertEquals("463295", SmsParser.extractOtp("کد ورود: 463295", sender = "7777000982"))
        assertEquals(com.example.data.model.SmsType.UTCMS_OTP, SmsParser.detectSmsType(msgReal, sender = "7777000982"))
    }

    @Test
    fun testUnlistedMobileOrTrunkWithOtpOrTransitKeywords() {
        // Uncoordinated mobile or unlisted 5000/9000 senders must be rejected to prevent SIM credit drain
        assertFalse(SmsParser.isUtcmsSms("09184110414", "کد تایید: 525456"))
        assertNull(SmsParser.extractOtp("کد تایید: 525456", sender = "09184110414"))

        assertFalse(SmsParser.isUtcmsSms("5000999888", "کد تایید بارنامه UTCMS: 543210"))
        assertNull(SmsParser.extractOtp("کد تایید بارنامه UTCMS: 543210", sender = "5000999888"))
        assertFalse(SmsParser.isUtcmsSms("90001234", "سامانه بارپرو - کد ورود: 678901"))
        assertNull(SmsParser.extractOtp("سامانه بارپرو - کد ورود: 678901", sender = "90001234"))

        // Promotional 5000/9000 SMS remains blocked
        assertFalse(SmsParser.isUtcmsSms("50001234", "کد تخفیف ویژه خرید اول: 987654"))
        assertNull(SmsParser.extractOtp("کد تخفیف ویژه خرید اول: 987654", sender = "50001234"))
    }
}

