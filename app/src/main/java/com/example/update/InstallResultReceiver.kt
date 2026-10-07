package com.example.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.IntentCompat

/**
 * Terminal callback for a [PackageInstaller] session committed by [AppUpdateManager].
 *
 * Three outcomes matter:
 *  - `STATUS_SUCCESS` — the silent path worked. Nothing is shown to the driver; the system restarts
 *    the app and `MY_PACKAGE_REPLACED` brings the forwarding service back up.
 *  - `STATUS_PENDING_USER_ACTION` — the platform declined to install silently (pre-API-31, or an
 *    OEM that allowlists silent installers). The installer supplies its own confirmation intent,
 *    which we can only surface through a notification: an app cannot start an activity from the
 *    background on Android 10+.
 *  - anything else — a real failure. The staged APK is dropped so the next check starts clean.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val versionCode = intent.getIntExtra(EXTRA_TARGET_VERSION_CODE, 0)
        val versionName = intent.getStringExtra(EXTRA_TARGET_VERSION_NAME)
        val store = UpdateStore.getInstance(context)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) {
                    store.lastStatusFa = "سیستم تأیید نصب خواست ولی صفحه‌ی تأیید را ارائه نکرد."
                    Log.w(TAG, "PENDING_USER_ACTION without EXTRA_INTENT")
                    return
                }
                store.lastStatusFa = "در انتظار تأیید نصب توسط راننده."
                UpdateNotifier.showConfirmNeeded(context, confirm, versionName)
            }

            PackageInstaller.STATUS_SUCCESS -> {
                store.lastStatusFa = "نسخه‌ی " + (versionName ?: versionCode.toString()) + " با موفقیت نصب شد."
                store.blockedByMandatoryUpdate = false
                store.stagedVersionCode = 0
                UpdateNotifier.clearAll(context)
                // The archive is dead weight once installed, and it is the largest file we keep.
                AppUpdateManager(context, publicKeyBase64 = "").clearStaged()
                Log.i(TAG, "Self-update installed: versionCode=$versionCode")
            }

            else -> {
                // Keep the platform's reason: INSTALL_FAILED_UPDATE_INCOMPATIBLE here means the
                // downloaded APK was signed with a different key, which is the control that makes
                // a plain-HTTP download safe.
                store.lastStatusFa = "نصب ناموفق بود" + (message?.let { " ($it)" } ?: "") + "."
                store.stagedVersionCode = 0
                AppUpdateManager(context, publicKeyBase64 = "").clearStaged()
                Log.w(TAG, "Self-update failed: status=$status message=$message")
            }
        }
    }

    companion object {
        private const val TAG = "InstallResultReceiver"
        const val ACTION_INSTALL_STATUS = "com.example.update.ACTION_INSTALL_STATUS"
        const val EXTRA_TARGET_VERSION_CODE = "target_version_code"
        const val EXTRA_TARGET_VERSION_NAME = "target_version_name"
    }
}
