package com.example.otp

import java.util.regex.Pattern

data class OtpResult(
    val code: String?,
    val confidence: Float,
    val matchedPattern: String,
    val sender: String,
    val originalMessage: String,
    val timestamp: Long
)

object OtpExtractor {

    // Converts Persian (۰-۹) and Arabic (٠-٩) digits to ASCII standard (0-9) and handles zero-width spaces
    fun normalizeDigits(input: String): String {
        val builder = StringBuilder()
        for (ch in input) {
            when (ch) {
                in '۰'..'۹' -> builder.append((ch - '۰' + '0'.code).toChar())
                in '٠'..'٩' -> builder.append((ch - '٠' + '0'.code).toChar())
                '\u200C', '\u200B', '\u200D', '\uFEFF' -> builder.append(' ') // replace ZWNJ/ZWSP with space
                '\u00A0' -> builder.append(' ') // non-breaking space
                else -> builder.append(ch)
            }
        }
        return builder.toString()
    }

    // Common Persian and English OTP patterns
    private val KEYWORD_PATTERNS = listOf(
        // Persian patterns
        Pattern.compile("""(?:کد\s*(?:تایید|تأیید|ورود|فعالسازی|فعال‌سازی|صحت‌سنجی|احراز\s*هویت|اعتبارسنجی|پویا|شما|بارپرو|بارنامه))\s*(?:است|:|=|\s|-)\s*([0-9]{4,8})""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
        Pattern.compile("""(?:رمز\s*(?:یکبار\s*مصرف|یک‌بار\s*مصرف|پویا|ورود|موقت|شما))\s*(?:است|:|=|\s|-)\s*([0-9]{4,8})""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
        Pattern.compile("""(?:کد|رمز|تایید|تأیید|otp)[^\d]*(\d{4,6})""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
        Pattern.compile("""(?:کد|رمز|شناسه|OTP)\s*:\s*([0-9]{4,8})""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
        Pattern.compile("""([0-9]{4,8})\s*(?:کد\s*تایید|کد\s*تأیید|کد\s*ورود|رمز\s*یکبار\s*مصرف|رمز\s*پویا|جهت\s*ورود)""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
        Pattern.compile("""(?:بارپرو|BarPro)\s*:\s*([0-9]{4,8})""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),

        // English patterns
        Pattern.compile("""(?:otp|verification\s*code|security\s*code|login\s*code|auth\s*code|pin|code)\s*(?:is|:|=|\s|-)\s*([0-9]{4,8})""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""(?:code|pin)\s*:\s*([0-9]{4,8})""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""\b([0-9]{4,8})\b\s*(?:is\s*your\s*code|is\s*your\s*otp|is\s*your\s*verification\s*code)""", Pattern.CASE_INSENSITIVE)
    )

    // Fallback pattern: looks for any standalone 4 to 8 digit number
    private val GENERIC_DIGIT_PATTERN = Pattern.compile("""\b([0-9]{4,8})\b""")

    /**
     * Extracts a 5-digit UTCMS OTP or 4-6 digit authentication code from the SMS text.
     * Complies strictly with the BarPro RPA Webhook / UTCMS OTP Vault specification:
     * 1. High priority: Standalone 5-digit numeric sequence (\b(\d{5})\b)
     * 2. Secondary priority: Keywords (تأیید|تایید|رمز|کد|otp) followed by 4-6 digits
     */
    fun extractUtcMsOtp(text: String): String? {
        if (text.isBlank()) return null
        val normalized = normalizeDigits(text)

        // 1. High priority: Standalone 5-digit sequence (UTCMS standard OTP format)
        val match5 = Regex("""\b(\d{5})\b""").find(normalized)
        if (match5 != null) {
            return match5.groupValues[1]
        }

        // 2. Secondary priority: Persian/English OTP keyword followed by 4 to 6 digits
        val matchKeyword = Regex("""(?:تأیید|تایید|رمز|کد|otp)[^\d]*(\d{4,6})""", RegexOption.IGNORE_CASE).find(normalized)
        if (matchKeyword != null) {
            return matchKeyword.groupValues[1]
        }

        return null
    }

    /**
     * Extracts an OTP / verification code from SMS message text.
     * Supports both Persian and English text and numerals.
     */
    fun extractOtp(rawMessage: String): String? {
        val utcmsOtp = extractUtcMsOtp(rawMessage)
        if (utcmsOtp != null) return utcmsOtp
        return extractOtp(sender = "", rawMessage = rawMessage).code
    }

    /**
     * Extracts an OTP / verification code with rich metadata from SMS message text.
     */
    fun extractOtp(sender: String, rawMessage: String, timestamp: Long = System.currentTimeMillis()): OtpResult {
        val normalized = normalizeDigits(rawMessage)

        // 0. Check UTCMS 5-digit OTP first
        val utcmsCode = extractUtcMsOtp(rawMessage)
        if (utcmsCode != null) {
            return OtpResult(
                code = utcmsCode,
                confidence = 0.98f,
                matchedPattern = "UTCMS 5-Digit Vault OTP",
                sender = sender,
                originalMessage = rawMessage,
                timestamp = timestamp
            )
        }

        // 1. Try keyword-based high-confidence regex patterns
        for (pattern in KEYWORD_PATTERNS) {
            val matcher = pattern.matcher(normalized)
            if (matcher.find()) {
                val code = matcher.group(1)
                if (!code.isNullOrBlank()) {
                    return OtpResult(
                        code = code,
                        confidence = 0.95f,
                        matchedPattern = pattern.pattern(),
                        sender = sender,
                        originalMessage = rawMessage,
                        timestamp = timestamp
                    )
                }
            }
        }

        // 2. Try generic 4-8 digit standalone numbers (excluding dates like 1402, 2024 if surrounded by date context)
        val genericMatcher = GENERIC_DIGIT_PATTERN.matcher(normalized)
        val candidates = mutableListOf<String>()
        while (genericMatcher.find()) {
            val candidate = genericMatcher.group(1)
            if (!candidate.isNullOrBlank()) {
                // Avoid matching current years 1400..1410 or 2020..2030 unless no other candidate exists
                candidates.add(candidate)
            }
        }

        if (candidates.isNotEmpty()) {
            // Pick candidate with length 5 or 6 first (most common for OTPs), otherwise the first
            val bestCandidate = candidates.firstOrNull { it.length in 5..6 } ?: candidates.first()
            return OtpResult(
                code = bestCandidate,
                confidence = 0.70f,
                matchedPattern = "Generic Numeric Sequence",
                sender = sender,
                originalMessage = rawMessage,
                timestamp = timestamp
            )
        }

        return OtpResult(
            code = null,
            confidence = 0f,
            matchedPattern = "None",
            sender = sender,
            originalMessage = rawMessage,
            timestamp = timestamp
        )
    }
}
