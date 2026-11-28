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
        "7777000982",
        "+987777000982",
        "7777",
        "+987777",
        "20007777",
        "+9820007777",
        "30001923",
        "+9830001923",
        "10008545",
        "+9810008545",
        "30002128002150",
        "+9830002128002150",
        "50002710040127",
        "+9850002710040127",
        "10001234",
        "+9810001234",
        "UTCMS",
        "UT CMS",
        "BARPRO",
        "BAR PRO",
        "RMTO",
        "ircars.vip",
        "ircars"
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
     * Literal senders and keywords added by a signature-verified remote config document, so a new
     * UTCMS shortcode can be rolled out without shipping an APK. Deliberately literals only — see
     * [com.example.update.RemoteConfig]. Volatile because the SMS broadcast path reads this on a
     * different thread from the one that applies config.
     */
    @Volatile
    private var remoteSenders: List<String> = emptyList()

    @Volatile
    private var remoteKeywords: List<String> = emptyList()

    /** Replaces the overlay wholesale; an empty pair restores pure built-in matching. */
    fun setRemoteOverlay(extraSenders: List<String>, extraKeywords: List<String>) {
        remoteSenders = extraSenders.toList()
        remoteKeywords = extraKeywords.toList()
    }

    private fun stripIranCountryPrefix(digits: String): String = when {
        digits.startsWith("0098") && digits.length > 4 -> digits.substring(4)
        digits.startsWith("98") && digits.length > 4 -> digits.substring(2)
        else -> digits
    }

    /**
     * Checks if a sender matches any known authorized/coordinated gateway numbers or identifiers.
     */
    fun isWhitelistedSender(sender: String): Boolean {
        if (sender.isBlank()) return false
        val cleanSender = normalizeDigits(sender).trim().lowercase()
        val digitsOnly = cleanSender.replace(Regex("""\D"""), "")
        val normSender = stripIranCountryPrefix(digitsOnly)
        val allWhitelisted = UTCMS_NUMBERS + remoteSenders
        return allWhitelisted.any { pattern ->
            val cleanPattern = normalizeDigits(pattern).trim().lowercase()
            val patternDigits = cleanPattern.replace(Regex("""\D"""), "")
            if (patternDigits.isNotBlank()) {
                if (digitsOnly.isBlank()) return@any false
                val normPattern = stripIranCountryPrefix(patternDigits)
                digitsOnly == patternDigits ||
                    normSender == normPattern ||
                    normSender == patternDigits ||
                    digitsOnly == normPattern ||
                    (normPattern == "7777" && normSender.startsWith("7777"))
            } else {
                Regex("(?<![a-z0-9])${Regex.escape(cleanPattern)}(?![a-z0-9])").containsMatchIn(cleanSender)
            }
        }
    }

    /**
     * Checks if a sender is a known bank shortcode/name or a promotional textual sender
     * (excluding bare 5000/9000 numeric trunks, which are handled contextually).
     */
    private fun isHardBankOrAdSender(sender: String): Boolean {
        if (sender.isBlank()) return false
        val cleanSender = normalizeDigits(sender).trim().lowercase()
        val digitsOnly = cleanSender.replace(Regex("""\D"""), "")

        // Never filter authentic coordinated BarPro, UTCMS or RMTO senders
        if (isWhitelistedSender(sender)) {
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

        // 2. Ad / Promotional textual senders
        val adKeywords = listOf("تبلیغ", "tabligh", "adv", "off", "تخفیف", "discount", "فروشگاه", "shop", "prize", "قرعه")
        if (adKeywords.any { cleanSender.contains(it) }) return true

        return false
    }

    private fun isBulkSmsTrunkPrefix(sender: String): Boolean {
        if (sender.isBlank()) return false
        if (isWhitelistedSender(sender)) {
            return false
        }
        val cleanSender = normalizeDigits(sender).trim().lowercase()
        val digitsOnly = cleanSender.replace(Regex("""\D"""), "")
        return digitsOnly.startsWith("5000") || digitsOnly.startsWith("9000")
    }

    /**
     * Checks if a sender is a known bank shortcode/name or a generic promotional advertising sender.
     */
    fun isBankOrAdSender(sender: String): Boolean {
        return isHardBankOrAdSender(sender) || isBulkSmsTrunkPrefix(sender)
    }

    /**
     * Detects bank transactional messages, commercial advertising, or non-transit consumer app content.
     */
    fun isBankOrAdContent(normalizedText: String): Boolean {
        val bankPhrases = listOf(
            "برداشت از حساب", "واریز به حساب", "انتقال وجه", "مانده حساب",
            "کارت به کارت", "خرید اینترنتی", "رمز دوم پویا", "رمز پویای کارت",
            "رمز دوم یکبار مصرف"
        )
        if (bankPhrases.any { normalizedText.contains(it) }) return true

        val adPhrases = listOf(
            "کد تخفیف", "تخفیف ویژه", "فروش ویژه", "قرعه کشی", "جایزه نقدی",
            "اپلیکیشن خرید", "خرید آنلاین", "فروشگاه اینترنتی", "سبد خرید"
        )
        if (adPhrases.any { normalizedText.contains(it) }) return true

        return false
    }

    private fun hasExplicitTransitKeywords(normMsg: String): Boolean =
        normMsg.contains("UTCMS", ignoreCase = true) ||
            normMsg.contains("بارنامه") ||
            normMsg.contains("باربرگ") ||
            normMsg.contains("راهداری") ||
            normMsg.contains("شهرداری") ||
            normMsg.contains("سهمیه سوخت") ||
            normMsg.contains("بارپرو") ||
            normMsg.contains("BarPro", ignoreCase = true) ||
            normMsg.contains("ircars", ignoreCase = true) ||
            remoteKeywords.any { normMsg.contains(it, ignoreCase = true) }

    private fun isBlockedSenderForMessage(sender: String, normMsg: String): Boolean {
        if (sender.isBlank()) return true
        if (isHardBankOrAdSender(sender)) return true
        return !isWhitelistedSender(sender)
    }

    fun isAllowedSenderForContent(sender: String, normalizedMessage: String): Boolean =
        sender.isNotBlank() && isWhitelistedSender(sender) && !isBankOrAdContent(normalizedMessage)

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
     * Checks whether an SMS originated from a coordinated UTCMS/BarPro sender and is not bank/ad content.
     */
    fun isUtcmsSms(phoneNumber: String, messageBody: String): Boolean {
        if (phoneNumber.isBlank() || !isWhitelistedSender(phoneNumber)) {
            return false
        }
        val normMsg = normalizeDigits(messageBody)
        return !isBankOrAdContent(normMsg)
    }

    /**
     * Categorizes an SMS into a specific SmsType.
     */
    fun detectSmsType(messageBody: String, sender: String = ""): SmsType {
        val normMsg = normalizeDigits(messageBody)
        if (isBankOrAdContent(normMsg)) {
            return SmsType.OTHER
        }
        if (isBlockedSenderForMessage(sender, normMsg)) {
            return SmsType.OTHER
        }

        val otp = extractOtp(messageBody, sender)
        val trackingCode = extractTrackingCode(messageBody)
        val hasConfirmWord = normMsg.contains("ثبت شد") || normMsg.contains("صادر شد") ||
                normMsg.contains("ثبت گردید") || normMsg.contains("کد رهگیری") ||
                normMsg.contains("کد ردیابی") || normMsg.contains("بارنامه شما")

        if (trackingCode != null && hasConfirmWord && otp == null) {
            return SmsType.UTCMS_CONFIRMATION
        }

        if (otp != null) {
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
     * Normalizes Iranian mobile phone numbers to the canonical 11-digit format starting with 09 (e.g. 09303653432).
     * Handles Persian/Arabic numerals, spaces, hyphens, and international prefixes (+98 / 0098).
     */
    fun normalizePhoneNumber(rawPhone: String): String {
        if (rawPhone.isBlank()) return ""
        val normalizedDigits = normalizeDigits(rawPhone)
        var digits = normalizedDigits.replace(Regex("""\D"""), "")
        if (digits.startsWith("0098") && digits.length >= 14) {
            val rem = digits.substring(4)
            digits = if (rem.startsWith("0")) rem else "0$rem"
        } else if (digits.startsWith("98") && digits.length >= 12) {
            val rem = digits.substring(2)
            digits = if (rem.startsWith("0")) rem else "0$rem"
        } else if (!digits.startsWith("0") && digits.length == 10 && digits.startsWith("9")) {
            digits = "0$digits"
        }
        return digits
    }

    /**
     * Validates if the given raw phone number normalizes to an 11-digit Iranian mobile number.
     */
    fun isValidIranianMobile(rawPhone: String): Boolean {
        val norm = normalizePhoneNumber(rawPhone)
        return norm.matches(Regex("""09\d{9}"""))
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
     * Extracts a 6-digit numeric OTP code strictly from coordinated UTCMS/BarPro senders.
     * Rejects uncoordinated/blank senders, bank/ad content, and non-6-digit numbers.
     */
    fun extractOtp(messageBody: String, sender: String = ""): String? {
        val normMsg = normalizeDigits(messageBody)
        if (!isAllowedSenderForContent(sender, normMsg)) {
            return null
        }

        val hasStrongOtpKeyword = normMsg.contains("کد تایید") ||
                normMsg.contains("کد تأیید") ||
                normMsg.contains("کد تائید") ||
                normMsg.contains("رمز یکبار مصرف") ||
                normMsg.contains("رمز یکبارمصرف") ||
                normMsg.contains("رمز یک‌بار مصرف") ||
                normMsg.contains("کد یکبار مصرف") ||
                normMsg.contains("کد یکبارمصرف") ||
                normMsg.contains("کد امنیتی") ||
                normMsg.contains("کد مجوز") ||
                normMsg.contains("کد صدور") ||
                normMsg.contains("کد ثبت") ||
                normMsg.contains("رمز تایید") ||
                normMsg.contains("رمز تأیید") ||
                normMsg.contains("رمز تائید") ||
                normMsg.contains("رمز اعتبار") ||
                normMsg.contains("رمز ورود") ||
                normMsg.contains("کد ورود") ||
                normMsg.contains("احراز هویت") ||
                normMsg.contains("اعتبارسنجی") ||
                normMsg.contains("کد فعالسازی") ||
                normMsg.contains("کد فعال‌سازی") ||
                normMsg.contains("صحت سنجی") ||
                normMsg.contains("صحت‌سنجی") ||
                normMsg.contains("OTP", ignoreCase = true)

        val isConfirmation = normMsg.contains("ثبت شد") || normMsg.contains("صادر شد") ||
                normMsg.contains("ثبت گردید") || normMsg.contains("صادر گردید") ||
                normMsg.contains("کد رهگیری") || normMsg.contains("کد ردیابی") ||
                normMsg.contains("شماره بارنامه") || normMsg.contains("بارنامه شماره")

        if (isConfirmation && !hasStrongOtpKeyword) {
            return null
        }

        return com.example.otp.OtpExtractor.extractUtcMsOtp(messageBody, sender)
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
