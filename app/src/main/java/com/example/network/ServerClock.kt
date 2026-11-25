package com.example.network

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Calibrates device clock skew against the BarPro server's HTTP Date header so phones affected by
 * Iranian DST timezone shifts (+1h / -1h) deliver fresh OTPs with true UTC timestamps instead of
 * triggering HTTP 410/422 rejections.
 */
object ServerClock {
    private const val PREFS_NAME = "barpro_server_clock"
    private const val KEY_SKEW_MS = "clock_skew_ms"
    private const val KEY_CALIBRATED_HOST = "calibrated_host"
    private const val MIN_SIGNIFICANT_SKEW_MS = 30_000L
    private const val RESET_SKEW_THRESHOLD_MS = 15_000L
    private const val MAX_ACCEPTED_SKEW_MS = 24L * 3600L * 1000L

    @Volatile
    private var memorySkewMs: Long = 0L

    @Volatile
    private var initializedFromPrefs: Boolean = false

    private val capturedElapsedByLogId = ConcurrentHashMap<Long, Long>()

    fun observeHttpDate(
        context: Context?,
        serverDateMs: Long?,
        host: String? = null,
        systemNowMs: Long = System.currentTimeMillis()
    ) {
        if (serverDateMs == null || serverDateMs <= 0L) return
        val delta = serverDateMs - systemNowMs
        if (abs(delta) > MAX_ACCEPTED_SKEW_MS) return

        val nextSkew = when {
            abs(delta) <= RESET_SKEW_THRESHOLD_MS -> 0L
            abs(delta) >= MIN_SIGNIFICANT_SKEW_MS -> delta
            else -> return
        }
        memorySkewMs = nextSkew
        if (context != null) {
            runCatching {
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_SKEW_MS, nextSkew)
                    .apply { if (!host.isNullOrBlank()) putString(KEY_CALIBRATED_HOST, host) }
                    .apply()
                initializedFromPrefs = true
            }
        }
    }

    fun currentSkewMs(context: Context? = null): Long {
        if (!initializedFromPrefs && context != null) {
            runCatching {
                val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                memorySkewMs = prefs.getLong(KEY_SKEW_MS, 0L)
                initializedFromPrefs = true
            }
        }
        return memorySkewMs
    }

    fun calibratedNow(context: Context? = null, systemNowMs: Long = System.currentTimeMillis()): Long =
        systemNowMs + currentSkewMs(context)

    /**
     * At broadcast arrival, compare the carrier/device SMS timestamp against the server-calibrated
     * clock. If an unpatched DST/SMSC offset puts the SMS timestamp >30s in the future or >=240s
     * behind calibrated arrival UTC, anchor to the calibrated real arrival instant so live OTPs
     * are not dropped as expired or future-dated.
     */
    fun resolveSmsArrivalTimestamp(
        context: Context?,
        smsTimestampMs: Long,
        systemArrivalMs: Long = System.currentTimeMillis()
    ): Long {
        val calibratedArrival = calibratedNow(context, systemArrivalMs)
        if (smsTimestampMs <= 0L) return calibratedArrival
        val ageRelativeToServer = calibratedArrival - smsTimestampMs
        return if (ageRelativeToServer < -MIN_SIGNIFICANT_SKEW_MS || ageRelativeToServer >= 240_000L) {
            calibratedArrival
        } else {
            smsTimestampMs
        }
    }

    fun markLogCaptured(logId: Long, elapsedRealtimeMs: Long = SystemClock.elapsedRealtime()) {
        if (logId > 0L) {
            if (capturedElapsedByLogId.size > 128) capturedElapsedByLogId.clear()
            capturedElapsedByLogId[logId] = elapsedRealtimeMs
        }
    }

    /**
     * Computes a server-aligned retry timestamp after HTTP 410/422 using elapsed monotonic time
     * since capture. Returns null if the message is genuinely older than the OTP TTL.
     */
    fun correctTimestampAfterClockRejection(
        logId: Long,
        originalTimestampMs: Long,
        serverDateMs: Long?,
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
        systemNowMs: Long = System.currentTimeMillis()
    ): Long? {
        if (serverDateMs == null || serverDateMs <= 0L) return null
        val skew = serverDateMs - systemNowMs
        if (abs(skew) < MIN_SIGNIFICANT_SKEW_MS || abs(skew) > MAX_ACCEPTED_SKEW_MS) return null
        val capturedElapsed = capturedElapsedByLogId[logId]
        val corrected = if (capturedElapsed != null && nowElapsedMs >= capturedElapsed) {
            val realAgeMs = nowElapsedMs - capturedElapsed
            if (realAgeMs >= BarProContract.OTP_TTL_MS - 15_000L) return null
            serverDateMs - realAgeMs
        } else {
            val shifted = originalTimestampMs + skew
            val serverAge = serverDateMs - shifted
            if (serverAge < -MIN_SIGNIFICANT_SKEW_MS || serverAge >= BarProContract.OTP_TTL_MS - 15_000L) return null
            minOf(shifted, serverDateMs)
        }
        return corrected
    }

    fun resetForTest(context: Context? = null) {
        memorySkewMs = 0L
        initializedFromPrefs = false
        capturedElapsedByLogId.clear()
        if (context != null) {
            runCatching {
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().clear().commit()
            }
        }
    }
}
