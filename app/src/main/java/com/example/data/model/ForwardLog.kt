package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ForwardStatus {
    SUCCESS,
    FAILED,
    SKIPPED,
    PENDING
}

enum class SmsType {
    UTCMS_CONFIRMATION,  // تایید ثبت بارنامه با کد رهگیری/ردیابی
    UTCMS_OTP,           // کد تایید یکبار مصرف OTP جهت صدور شبانه
    UTCMS_WARNING,       // هشدارهای سامانه و سهمیه اعتباری سوخت
    OTHER                // سایر پیامک‌های دریافتی
}

@Entity(tableName = "forward_logs")
data class ForwardLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sender: String,
    val messageBody: String,
    val receivedTimestamp: Long = System.currentTimeMillis(),
    val forwardedTimestamp: Long? = null,
    val status: ForwardStatus = ForwardStatus.PENDING,
    val httpStatusCode: Int? = null,
    val responseSummary: String? = null,
    val errorMessage: String? = null,
    val matchedRuleLabel: String? = null,
    val isEncrypted: Boolean = false,
    val payloadPreview: String? = null,
    val endpointUrl: String = "",
    val durationMs: Long = 0L,
    val driverId: String = "",
    val smsType: SmsType = SmsType.OTHER,
    val trackingCode: String? = null,
    val otpCode: String? = null,
    val signature: String? = null,
    val retryCount: Int = 0,
    val lastRetryTimestamp: Long? = null,
    val simSlot: String = "SIM 1"
)
