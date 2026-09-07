package com.example.utils

/**
 * Data Leakage Prevention (DLP) utility to sanitize sensitive operational logs,
 * preventing OTP verification codes, credentials, and customer/driver phone numbers
 * from leaking into Logcat, crash reports, Room DB, or bug reports.
 */
object LogSanitizer {

    // Regex for Iranian mobile phone numbers (09 followed by 9 digits)
    private val IRANIAN_PHONE_REGEX = Regex("""09\d{9}""")

    // Regex for strictly 5-digit OTP verification codes surrounded by word boundaries
    private val FIVE_DIGIT_OTP_REGEX = Regex("""\b\d{5}\b""")

    // Regex for Persian/Arabic digit variants
    private val PERSIAN_ARABIC_PHONE_REGEX = Regex("""(?:09|۰۹|٠٩)[\d\u06F0-\u06F9\u0660-\u0669]{9}""")
    private val PERSIAN_ARABIC_OTP_REGEX = Regex("""(?<![\d\u06F0-\u06F9\u0660-\u0669])[\d\u06F0-\u06F9\u0660-\u0669]{5}(?![\d\u06F0-\u06F9\u0660-\u0669])""")

    /**
     * Sanitizes any arbitrary log or payload string by masking 5-digit OTP codes
     * and Iranian mobile phone numbers.
     */
    fun sanitize(input: String?): String {
        if (input.isNullOrEmpty()) return ""

        var result = input

        // 1. Mask 5-digit OTPs (\b\d{5}\b -> ***)
        result = result.replace(FIVE_DIGIT_OTP_REGEX, "***")
        result = result.replace(PERSIAN_ARABIC_OTP_REGEX, "***")

        // 2. Mask Iranian phone numbers (09\d{9} -> 09***)
        result = result.replace(IRANIAN_PHONE_REGEX, "09***")
        result = result.replace(PERSIAN_ARABIC_PHONE_REGEX, "09***")

        return result
    }

    /**
     * Specifically masks an Iranian phone number, returning "09***"
     */
    fun maskPhone(phone: String?): String {
        if (phone.isNullOrBlank()) return ""
        val normalized = SmsParser.normalizeDigits(phone.trim())
        return if (normalized.matches(Regex("""09\d{9}"""))) {
            "09***"
        } else {
            sanitize(phone)
        }
    }

    /**
     * Specifically masks an OTP verification code, returning "***"
     */
    fun maskOtp(otp: String?): String {
        if (otp.isNullOrBlank()) return ""
        return "***"
    }
}
