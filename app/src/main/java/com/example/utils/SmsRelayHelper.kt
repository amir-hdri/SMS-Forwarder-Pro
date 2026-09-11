package com.example.utils

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.util.Log

object SmsRelayHelper {
    private const val TAG = "SmsRelayHelper"

    /**
     * Sends an emergency fallback SMS containing driver ID and the 5-digit OTP/tracking code
     * to the designated server mobile number when mobile internet is offline or too weak.
     * Operates completely silently in the background without requiring driver interaction.
     */
    fun sendFallbackSms(
        context: Context,
        destinationPhone: String,
        driverId: String,
        driverPhone: String,
        code: String,
        smsType: String = "OTP",
        simSlot: Int = -1
    ): Boolean {
        if (destinationPhone.isBlank() || code.isBlank()) {
            Log.w(TAG, "Cannot send fallback SMS: empty destination ($destinationPhone) or code ($code)")
            return false
        }

        if (!PermissionHelper.isSendSmsGranted(context)) {
            Log.w(TAG, "Cannot send fallback SMS: SEND_SMS permission is not granted")
            return false
        }

        return try {
            var smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            // Multi-SIM support: if simSlot is provided, route through the designated subscription
            if (simSlot >= 0) {
                try {
                    val subManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? android.telephony.SubscriptionManager
                    @Suppress("MissingPermission")
                    val subInfoList = subManager?.activeSubscriptionInfoList
                    val matchedSub = subInfoList?.firstOrNull { it.simSlotIndex == simSlot }
                    if (matchedSub != null) {
                        smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            smsManager.createForSubscriptionId(matchedSub.subscriptionId)
                        } else {
                            @Suppress("DEPRECATION")
                            SmsManager.getSmsManagerForSubscriptionId(matchedSub.subscriptionId)
                        }
                        Log.d(TAG, "Routed fallback SMS to SIM Slot #$simSlot (SubId: ${matchedSub.subscriptionId})")
                    }
                } catch (subEx: Exception) {
                    Log.w(TAG, "Multi-SIM subscription resolution fallback to default: ${subEx.message}")
                }
            }

            // Compact standard payload formatted for automated SMS gateway reception:
            // e.g., "BARPRO#DRV-102938#09333702137#OTP#43210"
            val messageText = "BARPRO#$driverId#$driverPhone#$smsType#$code"
            
            smsManager.sendTextMessage(
                destinationPhone.trim(),
                null,
                messageText,
                null,
                null
            )
            Log.i(TAG, "Emergency fallback SMS dispatched automatically to $destinationPhone for Driver $driverId (Code: $code) via SIM Slot ${if (simSlot >= 0) simSlot else "default"}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch emergency fallback SMS: ${e.message}", e)
            false
        }
    }
}
