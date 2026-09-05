package com.example.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import java.io.File

/**
 * Security auditing utilities to check device environment integrity
 * (Root, Debug mode, Emulator environment) in compliance with BarPro enterprise policies.
 */
object SecurityUtils {

    data class DeviceSecurityReport(
        val isRooted: Boolean,
        val isDebuggable: Boolean,
        val isEmulator: Boolean,
        val securityStatusText: String
    )

    fun getSecurityReport(context: Context): DeviceSecurityReport {
        val rooted = isDeviceRooted()
        val debuggable = isDebugMode(context)
        val emulator = isEmulator()

        val statusText = when {
            rooted -> "دستگاه روت شده (ریسک امنیتی)"
            emulator -> "محیط شبیه‌ساز (توسعه/تست)"
            debuggable -> "حالت اشکال‌زدایی فعال"
            else -> "امنیت محیط تایید شده (تولید)"
        }

        return DeviceSecurityReport(
            isRooted = rooted,
            isDebuggable = debuggable,
            isEmulator = emulator,
            securityStatusText = statusText
        )
    }

    fun isDeviceRooted(): Boolean {
        return checkBuildTags() || checkSuFiles()
    }

    private fun checkBuildTags(): Boolean {
        val buildTags = Build.TAGS
        return buildTags != null && buildTags.contains("test-keys")
    }

    private fun checkSuFiles(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su"
        )
        return paths.any {
            try {
                File(it).exists()
            } catch (_: Exception) {
                false
            }
        }
    }

    fun isDebugMode(context: Context): Boolean {
        return 0 != (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
    }

    fun isEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || "google_sdk" == Build.PRODUCT)
    }
}
