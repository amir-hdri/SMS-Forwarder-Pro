package com.example.otp

import com.example.utils.SmsParser
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
        return SmsParser.normalizeDigits(input)
    }

    // Common Persian and English OTP patterns requiring strong contextual phrases
    private val KEYWORD_PATTERNS = listOf(
        // Strong waybill / UTCMS / BarPro context
        Pattern.compile("""(?:کد\s*(?:تأیید\s*بارنامه|تایید\s*بارنامه|بارپرو|ورود|فعالسازی|صحت‌سنجی|احراز\s*هویت|اعتبارسنجی|شما))\s*(?:است|:|=|\s|-)\s*([0-9]{4,8})""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
        Pattern.compile("""(?:رمز\s*(?:یکبار\s*مصرف|یک‌بار\s*مصرف|ورود|موقت|شما))\s*(?:است|:|=|\s|-)\s*([0-9]{4,8})""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
        Pattern.compile("""(?:کد\s*تأیید|کد\s*تایید|سامانه\s*بارپرو)[^\d]*(\d{4,6})""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
        Pattern.compile("""(?:کد\s*تأیید|کد\s*تایید|رمز\s*یکبار\s*مصرف|رمز\s*یک‌بار\s*مصرف|OTP)\s*:\s*([0-9]{4,8})""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
        Pattern.compile("""([0-9]{4,8})\s*(?:کد\s*تایید|کد\s*تأیید|کد\s*ورود|رمز\s*یکبار\s*مصرف|جهت\s*ورود)""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
        Pattern.compile("""(?:بارپرو|BarPro)\s*:\s*([0-9]{4,8})""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),

        // English patterns
        Pattern.compile("""(?:otp\s*code|security\s*otp(?:\s*code)?|otp|verification\s*code|security\s*code|login\s*code|auth\s*code)\s*(?:is|:|=|\s|-)\s*([0-9]{4,8})""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""\b([0-9]{4,8})\b\s*(?:is\s*your\s*code|is\s*your\s*otp|is\s*your\s*verification\s*code)""", Pattern.CASE_INSENSITIVE)
    )

    /**
     * Extracts a 5-digit UTCMS OTP or authentication code from the SMS text.
     * Complies strictly with the BarPro RPA Webhook / UTCMS OTP Vault specification:
     * - Rejects bank shortcodes and bulk promotional advertising senders.
     * - Requires strong contextual keywords (e.g. "کد تایید بارنامه", "سامانه بارپرو") rather than isolated "کد"/"رمز".
     * - High priority: Contextual 5-digit numeric sequence.
     * - Secondary priority: Contextual 4-6 digits.
     */
    fun extractUtcMsOtp(text: String, sender: String = ""): String? {
        if (text.isBlank()) return null
        if (sender.isNotBlank() && SmsParser.isBankOrAdSender(sender)) return null

        val normalized = normalizeDigits(text)
        if (SmsParser.isBankOrAdContent(normalized)) return null

        val hasTransitContext = normalized.contains("بارنامه") || normalized.contains("بارپرو") ||
                normalized.contains("UTCMS", ignoreCase = true) || normalized.contains("راهداری") ||
                normalized.contains("شهرداری") || normalized.contains("باربرگ") || normalized.contains("راننده")

        val hasStrongOtpKeyword = normalized.contains("کد تایید بارنامه") ||
                normalized.contains("کد تأیید بارنامه") ||
                normalized.contains("سامانه بارپرو") ||
                normalized.contains("کد تایید") ||
                normalized.contains("کد تأیید") ||
                normalized.contains("رمز یکبار مصرف") ||
                normalized.contains("رمز یک‌بار مصرف") ||
                normalized.contains("رمز اعتبار") ||
                normalized.contains("کد ورود") ||
                normalized.contains("احراز هویت") ||
                normalized.contains("اعتبارسنجی")

        if (!hasStrongOtpKeyword && !hasTransitContext) {
            return null
        }

        // 1. High priority: Contextual 5-digit sequence (e.g. کد تایید: 12345, بارنامه: 12345)
        val matchContextual5 = Regex("""(?:کد\s*تأیید\s*بارنامه|کد\s*تایید\s*بارنامه|سامانه\s*بارپرو|سامانه\s*بارنامه|کد\s*تأیید|کد\s*تایید|رمز\s*یکبار\s*مصرف|رمز\s*یک‌بار\s*مصرف|رمز\s*اعتبار|کد\s*ورود|کد\s*اعتبارسنجی|کد\s*احراز|otp)[^\d]*(\d{5})(?!\d)""", RegexOption.IGNORE_CASE).find(normalized)
        if (matchContextual5 != null) {
            return matchContextual5.groupValues[1]
        }

        // 2. Reverse Contextual 5-digit sequence (e.g. ۳۹۱۸۲ :کد تایید شما)
        val matchReverse5 = Regex("""(?<!\d)(\d{5})[^\d]*(?:کد\s*تأیید\s*بارنامه|کد\s*تایید\s*بارنامه|سامانه\s*بارپرو|کد\s*تأیید|کد\s*تایید|رمز\s*یکبار\s*مصرف|رمز\s*یک‌بار\s*مصرف|رمز\s*اعتبار|کد\s*ورود)""", RegexOption.IGNORE_CASE).find(normalized)
        if (matchReverse5 != null) {
            return matchReverse5.groupValues[1]
        }

        // 3. Standalone 5-digit sequence ONLY if strong transit context is confirmed
        if (hasTransitContext) {
            val match5 = Regex("""(?<!\d)(\d{5})(?!\d)""").find(normalized)
            if (match5 != null) {
                return match5.groupValues[1]
            }
        }

        // 4. Secondary priority: Persian/English OTP keyword followed by 4 to 6 digits
        val matchKeyword = Regex("""(?:کد\s*تأیید\s*بارنامه|کد\s*تایید\s*بارنامه|سامانه\s*بارپرو|کد\s*تأیید|کد\s*تایید|رمز\s*یکبار\s*مصرف|رمز\s*یک‌بار\s*مصرف|رمز\s*اعتبار|کد\s*ورود)[^\d]*(\d{4,6})""", RegexOption.IGNORE_CASE).find(normalized)
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

        // Ignore bank and advertising senders immediately
        if (sender.isNotBlank() && SmsParser.isBankOrAdSender(sender)) {
            return OtpResult(
                code = null,
                confidence = 0f,
                matchedPattern = "IgnoredBankOrAdSender",
                sender = sender,
                originalMessage = rawMessage,
                timestamp = timestamp
            )
        }

        if (SmsParser.isBankOrAdContent(normalized)) {
            return OtpResult(
                code = null,
                confidence = 0f,
                matchedPattern = "IgnoredBankOrAdContent",
                sender = sender,
                originalMessage = rawMessage,
                timestamp = timestamp
            )
        }

        // 0. Check UTCMS 5-digit OTP first
        val utcmsCode = extractUtcMsOtp(rawMessage, sender)
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
