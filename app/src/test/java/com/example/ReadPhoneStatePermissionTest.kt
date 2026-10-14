package com.example

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.utils.PermissionCategory
import com.example.utils.PermissionHelper
import com.example.utils.PermissionType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TDD RED test for the proven READ_PHONE_STATE defect:
 * SmsRelayHelper (multi-SIM subscription resolution) and
 * CarrierDetector.detectPhoneNumberFromDevice (line1Number) both need
 * READ_PHONE_STATE, but the manifest declares 0 hits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ReadPhoneStatePermissionTest {

    private fun requestedPermissions(): Array<String> {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        }
        return info.requestedPermissions ?: emptyArray()
    }

    @Test
    fun `manifest declares READ_PHONE_STATE`() {
        val perms = requestedPermissions().toList()
        assertTrue(
            "AndroidManifest.xml must declare READ_PHONE_STATE " +
                "(SmsRelayHelper multi-SIM + CarrierDetector line1Number need it). Found: $perms",
            perms.contains(Manifest.permission.READ_PHONE_STATE)
        )
    }

    @Test
    fun `helper exposes READ_PHONE_STATE check-request path like SEND_SMS`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val list = PermissionHelper.getAllPermissionsStatus(context)
        val item = list.firstOrNull { it.manifestPermission == Manifest.permission.READ_PHONE_STATE }
        assertTrue(
            "PermissionHelper.getAllPermissionsStatus must expose a READ_PHONE_STATE item " +
                "(RUNTIME_PERMISSION request path for PermissionManagerScreen). Ids found: ${list.map { it.id }}",
            item != null
        )
        assertTrue(
            "READ_PHONE_STATE item must be a RUNTIME_PERMISSION so the existing " +
                "singlePermissionLauncher path can request it",
            item!!.permissionType == PermissionType.RUNTIME_PERMISSION
        )
        // Same lazy/optional precedent as SEND_SMS: requested where the SMS
        // fallback/relay is enabled, never as an install-time critical permission.
        assertFalse(
            "READ_PHONE_STATE must be optional (isRequired=false), same as SEND_SMS precedent",
            item.isRequired
        )
        assertTrue(
            "READ_PHONE_STATE item must live in the SMS category, same as SEND_SMS",
            item.category == PermissionCategory.SMS
        )
    }

    @Test
    fun `helper check mirrors SEND_SMS precedent without crashing`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        // Same ContextCompat.checkSelfPermission pattern as isSendSmsGranted;
        // under Robolectric both are denied by default — the point is the
        // check path exists, is callable, and agrees with the status list.
        val direct = PermissionHelper.isReadPhoneStateGranted(context)
        val item = PermissionHelper.getAllPermissionsStatus(context)
            .first { it.manifestPermission == Manifest.permission.READ_PHONE_STATE }
        assertTrue(
            "isReadPhoneStateGranted must agree with the read_phone_state status item",
            direct == item.isGranted
        )
    }

    @Test
    fun `READ_PHONE_STATE stays lazy like SEND_SMS - not in critical required set`() {        val required = PermissionHelper.getRequiredRuntimePermissions().toList()
        assertFalse(
            "SEND_SMS precedent: optional relay permission must not be in getRequiredRuntimePermissions. Found: $required",
            required.contains(Manifest.permission.SEND_SMS)
        )
        assertFalse(
            "READ_PHONE_STATE must stay lazy like SEND_SMS (requested where fallback/relay " +
                "is enabled), not in getRequiredRuntimePermissions. Found: $required",
            required.contains(Manifest.permission.READ_PHONE_STATE)
        )
    }
}
