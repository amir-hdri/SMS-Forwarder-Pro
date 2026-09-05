package com.example.network

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager

data class DeviceStatus(
    val batteryPercent: Int,
    val isCharging: Boolean,
    val networkType: String,
    val isConnected: Boolean
)

object DeviceStatusHelper {

    fun getDeviceStatus(context: Context): DeviceStatus {
        // Battery status
        var batteryPercent = -1
        var isCharging = false

        try {
            val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryIntent = context.registerReceiver(null, batteryFilter)
            if (batteryIntent != null) {
                val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    batteryPercent = (level * 100) / scale
                }
                val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
            }
        } catch (e: Exception) {
            batteryPercent = 100
        }

        // Network status
        var networkType = "نامشخص"
        var isConnected = false

        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                val activeNetwork = cm.activeNetwork
                val caps = cm.getNetworkCapabilities(activeNetwork)
                if (caps != null) {
                    isConnected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    networkType = when {
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "اینترنت همراه (Mobile Data)"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                        else -> "شبکه متصل"
                    }
                } else {
                    networkType = "قطع ارتباط (Offline)"
                }
            }
        } catch (e: Exception) {
            networkType = "خطا در بررسی شبکه"
        }

        return DeviceStatus(
            batteryPercent = if (batteryPercent >= 0) batteryPercent else 100,
            isCharging = isCharging,
            networkType = networkType,
            isConnected = isConnected
        )
    }
}
