package com.example.utils

import com.example.data.model.SmsType
import java.util.regex.Pattern

/**
 * Intelligent parser for SMS messages related to UTCMS, BarPro, and transportation systems.
 * Provides normalization for Persian/Arabic numerals, categorization into SmsType,
 * smart anti-false-positive filtering for banks and advertising senders,
 * and robust extraction of tracking codes and waybill OTPs.
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

    private val KNOWN_BANK_SENDERS = setOf(
        "tejaratbank", "tejarat", "mellatbank", "mellat", "bankmelli", "melli", "bmi",
        "saderat", "saderatbank", "bsi", "sepah", "banksepah", "parsian", "bankparsian",
        "pasargad", "bpi", "saman", "samanbank", "keshavarzi", "bki", "refah", "bankrefah",
        "maskan", "bankmaskan", "shahr", "shahrbank", "dey", "deybank", "sina", "sinabank",
        "karafarin", "ayandeh", "bankayandeh", "postbank", "sarmayeh", "taavon", "ttbank", "resalat",
        "rqbank", "mehr", "qmb", "blubank", "blu", "shaparak", "sadad", "behpardakht",
        "asanpardakht", "vandar", "zibal", "idpay", "zarinpal"
    )

    private val KNOWN_BANK_PERSIAN_KEYWORDS = listOf(
        "بانک", "تجارت", "ملت", "ملی", "صادرات", "سپه", "پارسیان", "پاسارگاد",
        "سامان", "کشاورزی", "رفاه", "مسکن", "شهر", "دی", "سینا", "کارآفرین",
        "آینده", "پست بانک", "سرمایه", "توسعه تعاون", "رسالت", "مهر ایران", "شاپرک", "سداد", "به‌پرداخت", "به پرداخت"
    )

    private val KNOWN_BANK_NUMBERS = setOf(
        "20004000", "200021", "10008588", "20001", "300060", "200073", "200096"
    )

    /**
     * Checks if a sender is a known bank shortcode/name or a generic promotional advertising sender.
     */
    fun isBankOrAdSender(sender: String): Boolean {
        if (sender.isBlank()) return false
        val cleanSender = normalizeDigits(sender).trim().lowercase()
        val digitsOnly = cleanSender.replace(Regex("""\D"""), "")

        // Never filter authentic BarPro, UTCMS or RMTO senders
        if (cleanSender.contains("barpro") || cleanSender.contains("utcms") || cleanSender.contains("rmto") || digitsOnly == "10001234") {
            return false
        }

        // 1. Direct Bank Name or Code match
        if (KNOWN_BANK_SENDERS.any { bank ->
            if (bank.length <= 3) {
                cleanSender == bank || cleanSender.startsWith("$bank ") || cleanSender.endsWith(" $bank")
            } else {
                cleanSender.contains(bank)
            }
        }) return true
        if (KNOWN_BANK_NUMBERS.contains(digitsOnly)) return true
        if (KNOWN_BANK_PERSIAN_KEYWORDS.any { cleanSender.contains(it) }) return true

        // 2. Advertising numbers (Iran standard bulk SMS trunks 5000..., 9000...)
        if (digitsOnly.startsWith("5000") || digitsOnly.startsWith("9000")) return true

        // 3. Ad / Promotional textual senders
        val adKeywords = listOf("تبلیغ", "tabligh", "adv", "off", "تخفیف", "discount", "فروشگاه", "shop", "prize", "قرعه")
        if (adKeywords.any { cleanSender.contains(it) }) return true

        return false
    }

    /**
     * Detects bank transactional messages or commercial advertising content.
     */
    fun isBankOrAdContent(normalizedText: String): Boolean {
        val bankPhrases = listOf(
            "برداشت از حساب", "واریز به حساب", "انتقال وجه", "مانده حساب",
            "کارت به کارت", "خرید اینترنتی", "رمز دوم پویا", "رمز پویای کارت",
            "رمز دوم یکبار مصرف"
        )
        if (bankPhrases.any { normalizedText.contains(it) }) return true

        val adPhrases = listOf(
            "کد تخفیف", "تخفیف ویژه", "فروش ویژه", "قرعه کشی", "جایزه نقدی"
        )
        if (adPhrases.any { normalizedText.contains(it) }) return true

        return false
    }

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

    private val WARNING_PATTERNS = listOf(
        Pattern.compile("""(?:سوخت|سهمیه|پایان\s*اعتبار|اخطار|هشدار|تخلف|مغایرت|اتمام|بدهی|غیرمجاز)""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
    )

    /**
     * Checks whether an SMS originated from or pertains to UTCMS, BarPro, or transportation authorities.
     * Rejects bank shortcodes and bulk promotional advertising messages.
     */
    fun isUtcmsSms(phoneNumber: String, messageBody: String): Boolean {
        if (isBankOrAdSender(phoneNumber)) {
            return false
        }
        val normMsg = normalizeDigits(messageBody)
        if (isBankOrAdContent(normMsg)) {
            return false
        }

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
    fun detectSmsType(messageBody: String, sender: String = ""): SmsType {
        if (isBankOrAdSender(sender)) {
            return SmsType.OTHER
        }

        val normMsg = normalizeDigits(messageBody)
        if (isBankOrAdContent(normMsg)) {
            return SmsType.OTHER
        }

        val trackingCode = extractTrackingCode(messageBody)
        val hasConfirmWord = normMsg.contains("ثبت شد") || normMsg.contains("صادر شد") ||
                normMsg.contains("ثبت گردید") || normMsg.contains("کد رهگیری") ||
                normMsg.contains("کد ردیابی") || normMsg.contains("بارنامه شما")

        if (trackingCode != null && hasConfirmWord) {
            return SmsType.UTCMS_CONFIRMATION
        }

        val otp = extractOtp(messageBody, sender)
        val hasOtpWord = normMsg.contains("کد تایید بارنامه") ||
                normMsg.contains("کد تأیید بارنامه") ||
                normMsg.contains("سامانه بارپرو") ||
                normMsg.contains("کد تایید") ||
                normMsg.contains("کد تأیید") ||
                normMsg.contains("رمز یکبار") ||
                normMsg.contains("رمز یک‌بار") ||
                normMsg.contains("OTP", ignoreCase = true) ||
                normMsg.contains("کد ورود") ||
                normMsg.contains("احراز هویت")

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
     * Rejects known bank shortcodes and bulk promotional advertising senders.
     * Requires stronger contextual keywords (e.g., "کد تایید بارنامه", "سامانه بارپرو")
     * rather than generic isolated words like "کد" or "رمز".
     */
    fun extractOtp(messageBody: String, sender: String = ""): String? {
        if (sender.isNotBlank() && isBankOrAdSender(sender)) {
            return null
        }
        val normMsg = normalizeDigits(messageBody)
        if (isBankOrAdContent(normMsg)) {
            return null
        }

        // Strong contextual validation
        val hasTransitContext = normMsg.contains("بارنامه") || normMsg.contains("بارپرو") ||
                normMsg.contains("UTCMS", ignoreCase = true) || normMsg.contains("راهداری") ||
                normMsg.contains("شهرداری") || normMsg.contains("باربرگ") || normMsg.contains("راننده")

        val hasStrongOtpKeyword = normMsg.contains("کد تایید بارنامه") ||
                normMsg.contains("کد تأیید بارنامه") ||
                normMsg.contains("سامانه بارپرو") ||
                normMsg.contains("کد تایید") ||
                normMsg.contains("کد تأیید") ||
                normMsg.contains("رمز یکبار مصرف") ||
                normMsg.contains("رمز یک‌بار مصرف") ||
                normMsg.contains("رمز اعتبار") ||
                normMsg.contains("کد ورود") ||
                normMsg.contains("احراز هویت") ||
                normMsg.contains("اعتبارسنجی") ||
                normMsg.contains("صحت سنجی") ||
                normMsg.contains("صحت‌سنجی") ||
                (normMsg.contains("OTP", ignoreCase = true) && hasTransitContext)

        if (!hasStrongOtpKeyword && !hasTransitContext) {
            return null
        }

        // Check if message is purely a waybill confirmation without any OTP request
        val isConfirmation = normMsg.contains("ثبت شد") || normMsg.contains("صادر شد") ||
                normMsg.contains("ثبت گردید") || normMsg.contains("کد رهگیری") ||
                normMsg.contains("کد ردیابی")

        if (isConfirmation && !hasStrongOtpKeyword) {
            return null
        }

        // High-precision UTCMS OTP extraction
        val utcmsOtp = com.example.otp.OtpExtractor.extractUtcMsOtp(messageBody, sender)
        if (!utcmsOtp.isNullOrBlank()) {
            return utcmsOtp
        }

        return com.example.otp.OtpExtractor.extractOtp(sender = sender, rawMessage = messageBody).code
    }

    /**
     * Computes a deterministic SHA-256 fingerprint for incoming SMS.
     * Normalizes digits, sender format, and whitespace.
     * Used by repository for sliding-window deduplication between dual-path receivers.
     */
    fun computeFingerprint(sender: String, messageBody: String): String {
        val normSender = normalizePhoneNumber(sender).ifBlank { normalizeDigits(sender).trim().lowercase() }
        val normBody = normalizeDigits(messageBody).trim().replace(Regex("""\s+"""), " ")
        val seed = "$normSender:$normBody"
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(seed.toByteArray(Charsets.UTF_8))
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            seed
        }
    }
}
