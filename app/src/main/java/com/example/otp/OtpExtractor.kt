package com.example.otp

import com.example.utils.SmsParser

data class OtpResult(
    val code: String?,
    val confidence: Float,
    val matchedPattern: String,
    val sender: String,
    val originalMessage: String,
    val timestamp: Long
)

/** Bounded extraction matching BarPro's OTP parser; tracking numbers are not OTPs. */
object OtpExtractor {
    fun normalizeDigits(input: String): String = SmsParser.normalizeDigits(input)

    private val keyword = """کد\s*(?:تایید|تأیید|ورود|فعالسازی|فعال\s*سازی|احراز|اعتبار|یک\s*بار\s*مصرف|یکبار\s*مصرف|امنیتی|مجوز|صدور|ثبت)|رمز\s*(?:یک\s*بار\s*مصرف|یکبار\s*مصرف|ورود|موقت|اعتبار|تایید|تأیید)|(?:verification|security|login|auth)\s*(?:otp\s*)?code|otp(?:\s*code)?"""
    private val forward = Regex("(?:" + keyword + """)[^0-9]{0,60}(?<![0-9])([0-9]{4,8})(?![0-9])""", RegexOption.IGNORE_CASE)
    private val reverse = Regex("""(?<![0-9])([0-9]{4,8})(?![0-9])[^0-9]{0,25}(?:""" + keyword + ")", RegexOption.IGNORE_CASE)

    fun extractUtcMsOtp(text: String, sender: String = ""): String? {
        if (SmsParser.isBankOrAdSender(sender)) return null
        val clean = normalizeDigits(text).replace("\u200f", "")
        if (SmsParser.isBankOrAdContent(clean) || clean.contains("رمز دوم")) return null
        forward.find(clean)?.let { return it.groupValues[1] }
        reverse.find(clean)?.let { return it.groupValues[1] }
        if (listOf("رهگیری", "ردیابی", "شماره بارنامه", "ثبت شد", "صادر شد", "ثبت گردید").any { clean.contains(it) })
            return null
        Regex("""کد\s*[:=]\s*([0-9]{4,8})(?![0-9])""").find(clean)?.let { return it.groupValues[1] }
        return clean.trim().takeIf { it.matches(Regex("[0-9]{4,8}")) }
    }

    fun extractOtp(rawMessage: String): String? = extractUtcMsOtp(rawMessage)

    fun extractOtp(sender: String, rawMessage: String, timestamp: Long = System.currentTimeMillis()): OtpResult {
        val code = extractUtcMsOtp(rawMessage, sender)
        return OtpResult(code, if (code != null) 0.95f else 0f, if (code != null) "Contextual OTP" else "None",
            sender, rawMessage, timestamp)
    }
}
