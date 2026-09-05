package com.example.utils

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

enum class PermissionType {
    RUNTIME_PERMISSION,
    BATTERY_OPTIMIZATION,
    NOTIFICATION_LISTENER
}

enum class PermissionCategory(val faTitle: String, val enTitle: String) {
    SMS("پیامک", "SMS"),
    NOTIFICATION("اعلان‌ها", "Notification"),
    BACKGROUND("پس‌زمینه", "Background")
}

data class PermissionItemInfo(
    val id: String,
    val title: String,
    val description: String,
    val isGranted: Boolean,
    val isRequired: Boolean,
    val permissionType: PermissionType,
    val manifestPermission: String? = null,
    val category: PermissionCategory = PermissionCategory.SMS
)

object PermissionHelper {

    fun isReceiveSmsGranted(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isReadSmsGranted(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isPostNotificationGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        } else {
            true
        }
    }

    fun isNotificationListenerEnabled(context: Context): Boolean {
        return try {
            val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(context)
            enabledListeners.contains(context.packageName)
        } catch (_: Exception) {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            )
            flat?.contains(context.packageName) == true
        }
    }

    fun areCriticalPermissionsGranted(context: Context): Boolean {
        return isReceiveSmsGranted(context) &&
                isReadSmsGranted(context) &&
                isPostNotificationGranted(context)
    }

    fun getRequiredRuntimePermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions.toTypedArray()
    }

    fun getAllPermissionsStatus(context: Context): List<PermissionItemInfo> {
        val list = mutableListOf<PermissionItemInfo>()

        list.add(
            PermissionItemInfo(
                id = "receive_sms",
                title = "دریافت پیامک (RECEIVE_SMS)",
                description = "دریافت بلادرنگ پیامک‌های حاوی کد بارنامه و رمز یکبار مصرف به محض رسیدن به گوشی.",
                isGranted = isReceiveSmsGranted(context),
                isRequired = true,
                permissionType = PermissionType.RUNTIME_PERMISSION,
                manifestPermission = Manifest.permission.RECEIVE_SMS,
                category = PermissionCategory.SMS
            )
        )

        list.add(
            PermissionItemInfo(
                id = "read_sms",
                title = "خواندن پیامک (READ_SMS)",
                description = "پردازش و یکپارچه‌سازی متون پیامک‌های طولانی یا چندبخشی سامانه باربری.",
                isGranted = isReadSmsGranted(context),
                isRequired = true,
                permissionType = PermissionType.RUNTIME_PERMISSION,
                manifestPermission = Manifest.permission.READ_SMS,
                category = PermissionCategory.SMS
            )
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(
                PermissionItemInfo(
                    id = "post_notifications",
                    title = "ارسال اعلان‌ها (POST_NOTIFICATIONS)",
                    description = "فعال ماندن سرویس دائم فورگراند در پس‌زمینه و نمایش اعلان وضعیت ارسال پیامک.",
                    isGranted = isPostNotificationGranted(context),
                    isRequired = true,
                    permissionType = PermissionType.RUNTIME_PERMISSION,
                    manifestPermission = Manifest.permission.POST_NOTIFICATIONS,
                    category = PermissionCategory.NOTIFICATION
                )
            )
        }

        list.add(
            PermissionItemInfo(
                id = "battery_optimization",
                title = "استثنای بهینه‌سازی باتری (Doze Mode)",
                description = "جلوگیری از توقف خودکار سرویس فوروارد توسط سیستم‌عامل در سفرهای طولانی جاده‌ای.",
                isGranted = isBatteryOptimizationIgnored(context),
                isRequired = false,
                permissionType = PermissionType.BATTERY_OPTIMIZATION,
                category = PermissionCategory.BACKGROUND
            )
        )

        list.add(
            PermissionItemInfo(
                id = "notification_listener",
                title = "سرویس شنود اعلان‌ها (Notification Listener)",
                description = "مسیر پشتیبان دریافت پیامک در شرایط محدودیت شدید پس‌زمینه در اندروید ۱۱ و بالاتر.",
                isGranted = isNotificationListenerEnabled(context),
                isRequired = false,
                permissionType = PermissionType.NOTIFICATION_LISTENER,
                category = PermissionCategory.NOTIFICATION
            )
        )

        return list
    }

    fun requestBatteryOptimization(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {
                    openAppSettings(context)
                }
            }
        }
    }

    fun openNotificationListenerSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            openAppSettings(context)
        }
    }

    fun openAppSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            // Ignore if settings cannot be opened
        }
    }
}
