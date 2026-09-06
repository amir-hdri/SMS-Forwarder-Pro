package com.example.utils

import com.example.data.model.SmsType
import java.util.regex.Pattern

/**
 * Intelligent parser for SMS messages related to UTCMS, BarPro, and transportation systems.
 * Provides normalization for Persian/Arabic numerals, categorization into SmsType,
 * and robust extraction of tracking codes (کد رهگیری / ردیابی) and OTP codes (کد تایید / رمز یکبار مصرف).
 */
object SmsParser {

    private val UTCMS_NUMBERS = listOf(
        "10001234",
        "UTCMS",
        "UT CMS",
        "+9810001234",
        "3000",
        "2000",
        "1000",
        "BARPRO",
        "BAR PRO",
        "RMTO"
    )

    /**
     * Converts Persian (۰-۹) and Arabic (٠-٩) numerals to ASCII digits (0-9)
     * and normalizes invisible characters like zero-width non-joiners.
     */
    fun normalizeDigits(input: String): String {
        val builder = StringBuilder()
        for (ch in input) {
            when (ch) {
                in '۰'..'۹' -> builder.append((ch - '۰' + '0'.code).toChar())
                in '٠'..'٩' -> builder.append((ch - '٠' + '0'.code).toChar())
                '\u200C', '\u200B', '\u200D', '\uFEFF' -> builder.append(' ')
                '\u00A0' -> builder.append(' ')
                else -> builder.append(ch)
            }
        }
        return builder.toString()
    }

    private val TRACKING_PATTERNS = listOf(
        Pattern.compile("""(?:کد\s*ردیابی|کد\s*رهگیری|شماره\s*بارنامه|بارنامه\s*شماره|شناسه\s*بارنامه|Tracking\s*Code)\s*[:=؛\s-]*([0-9]{5,12})""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
        Pattern.compile("""بارنامه.*?کد.*?\s*([0-9]{5,12})""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
        Pattern.compile("""ثبت\s*(?:شد|گردید).*?([0-9]{5,12})""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
        Pattern.compile("""\b([0-9]{6,10})\b\s*(?:کد\s*رهگیری|کد\s*ردیابی|شماره\s*سند)""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
    )

    private val OTP_PATTERNS = listOf(
        Pattern.compile("""(?:کد\s*(?:تایید|تأیید|ورود|فعالسازی|فعال‌سازی|صحت‌سنجی|احراز\s*هویت|اعتبارسنجی|پویا|شما|بارپرو|بارنامه|OTP))\s*(?:است|:|=|\s|-)\s*([0-9]{4,6})""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
        Pattern.compile("""(?:رمز\s*(?:یکبار\s*مصرف|یک‌بار\s*مصرف|پویا|ورود|موقت|شما))\s*(?:است|:|=|\s|-)\s*([0-9]{4,6})""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
        Pattern.compile("""(?:کد|رمز|تایید|تأیید|otp)[^\d]*(\d{4,6})""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
        Pattern.compile("""\b([0-9]{4,6})\b\s*(?:کد\s*تایید|کد\s*تأیید|کد\s*ورود|رمز\s*یکبار\s*مصرف|رمز\s*پویا|جهت\s*ورود)""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
        Pattern.compile("""(?:OTP|Code)\s*[:=]\s*([0-9]{4,6})""", Pattern.CASE_INSENSITIVE)
    )

    private val WARNING_PATTERNS = listOf(
        Pattern.compile("""(?:سوخت|سهمیه|پایان\s*اعتبار|اخطار|هشدار|تخلف|مغایرت|اتمام|بدهی|غیرمجاز)""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
    )

    /**
     * Checks whether an SMS originated from or pertains to UTCMS, BarPro, or transportation authorities.
     */
    fun isUtcmsSms(phoneNumber: String, messageBody: String): Boolean {
        val normMsg = normalizeDigits(messageBody)
        val isSenderMatch = UTCMS_NUMBERS.any { phoneNumber.contains(it, ignoreCase = true) }
        val isBodyMatch = normMsg.contains("UTCMS", ignoreCase = true) ||
                normMsg.contains("بارنامه") ||
                normMsg.contains("باربرگ") ||
                normMsg.contains("راهداری") ||
                normMsg.contains("شهرداری") ||
                normMsg.contains("کد تایید") ||
                normMsg.contains("کد رهگیری") ||
                normMsg.contains("کد ردیابی") ||
                normMsg.contains("سهمیه سوخت") ||
                normMsg.contains("بارپرو") ||
                normMsg.contains("BarPro", ignoreCase = true)

        return isSenderMatch || isBodyMatch
    }

    /**
     * Categorizes an SMS into a specific SmsType.
     */
    fun detectSmsType(messageBody: String): SmsType {
        val normMsg = normalizeDigits(messageBody)
        val trackingCode = extractTrackingCode(messageBody)
        val hasConfirmWord = normMsg.contains("ثبت شد") || normMsg.contains("صادر شد") ||
                normMsg.contains("ثبت گردید") || normMsg.contains("کد رهگیری") ||
                normMsg.contains("کد ردیابی") || normMsg.contains("بارنامه شما")

        if (trackingCode != null && hasConfirmWord) {
            return SmsType.UTCMS_CONFIRMATION
        }

        val otp = extractOtp(messageBody)
        val hasOtpWord = normMsg.contains("کد تایید") || normMsg.contains("رمز یکبار") ||
                normMsg.contains("رمز یک‌بار") || normMsg.contains("OTP", ignoreCase = true) ||
                normMsg.contains("کد ورود") || normMsg.contains("احراز هویت")

        if (otp != null && (hasOtpWord || !hasConfirmWord)) {
            return SmsType.UTCMS_OTP
        }

        if (WARNING_PATTERNS.any { it.matcher(normMsg).find() }) {
            return SmsType.UTCMS_WARNING
        }

        if (trackingCode != null) {
            return SmsType.UTCMS_CONFIRMATION
        }

        return SmsType.OTHER
    }

    /**
     * Normalizes Iranian mobile phone numbers to the canonical 11-digit format starting with 09 (e.g. 09333702137).
     * Handles:
     * - Persian/Arabic numerals (۰۱۲۳۴۵۶۷۸۹ -> 0123456789)
     * - Leading country codes: +989..., 00989..., 989... -> 09...
     * - 10-digit formats without leading zero: 9333702137 -> 09333702137
     */
    fun normalizePhoneNumber(rawPhone: String): String {
        if (rawPhone.isBlank()) return ""
        val normalizedDigits = normalizeDigits(rawPhone)
        var digits = normalizedDigits.replace(Regex("""\D"""), "")
        if (digits.startsWith("0098")) {
            digits = "0" + digits.substring(4)
        } else if (digits.startsWith("98")) {
            digits = "0" + digits.substring(2)
        } else if (!digits.startsWith("0") && digits.length == 10) {
            digits = "0$digits"
        }
        return digits
    }

    /**
     * Checks if current time is within the evening UTCMS automated OTP issuance window (17:30 to 08:00).
     */
    fun isEveningOtpWindow(calendar: java.util.Calendar = java.util.Calendar.getInstance()): Boolean {
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = calendar.get(java.util.Calendar.MINUTE)
        val totalMinutes = hour * 60 + minute
        // Evening window: 17:30 (1050 mins) to 23:59, and 00:00 to 08:00 (480 mins)
        return totalMinutes >= 1050 || totalMinutes < 480
    }

    /**
     * Extracts a tracking / confirmation code (usually 5 to 10 digits).
     */
    fun extractTrackingCode(messageBody: String): String? {
        val normMsg = normalizeDigits(messageBody)
        for (pattern in TRACKING_PATTERNS) {
            val matcher = pattern.matcher(normMsg)
            if (matcher.find()) {
                val code = matcher.group(1)
                if (!code.isNullOrBlank()) return code
            }
        }
        return null
    }

    /**
     * Extracts a 4 to 6 digit verification code (OTP), strictly prioritizing 5-digit UTCMS codes.
     */
    fun extractOtp(messageBody: String): String? {
        // High-precision UTCMS OTP extraction (matches the BarPro Redis Vault specification)
        val utcmsOtp = com.example.otp.OtpExtractor.extractUtcMsOtp(messageBody)
        if (!utcmsOtp.isNullOrBlank()) {
            return utcmsOtp
        }

        // If it is a waybill confirmation without OTP keywords, return null
        val normMsg = normalizeDigits(messageBody)
        val isConfirmation = normMsg.contains("ثبت شد") || normMsg.contains("صادر شد") ||
                normMsg.contains("ثبت گردید") || normMsg.contains("کد رهگیری") ||
                normMsg.contains("کد ردیابی")
        val hasOtpKeyword = normMsg.contains("کد تایید") || normMsg.contains("کد تأیید") ||
                normMsg.contains("رمز یکبار") || normMsg.contains("رمز یک‌بار") ||
                normMsg.contains("رمز ورود") || normMsg.contains("OTP", ignoreCase = true) ||
                normMsg.contains("کد ورود")

        if (isConfirmation && !hasOtpKeyword) {
            return null
        }

        return com.example.otp.OtpExtractor.extractOtp(messageBody)
    }
}
