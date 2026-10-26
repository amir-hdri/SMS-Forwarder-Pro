package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import com.example.utils.CarrierDetector
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class PhoneIdentityFallbackTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private fun seedNumber() {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        shadowOf(telephony).setLine1Number("+989120000001")
    }

    @Test fun deniedLegacyPermissionCannotInferPhone() {
        seedNumber()
        shadowOf(context).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        assertEquals("", CarrierDetector.detectPhoneNumberFromDevice(context))
    }

    @Test fun revokingLegacyPermissionDisablesPreviouslyAvailableFallback() {
        seedNumber()
        shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        assertEquals("09120000001", CarrierDetector.detectPhoneNumberFromDevice(context))
        shadowOf(context).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        assertEquals("", CarrierDetector.detectPhoneNumberFromDevice(context))
    }

    @Test @Config(sdk = [34]) fun modernPhoneStateGrantCannotReplaceUndeclaredPhoneNumbersPermission() {
        seedNumber()
        shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        shadowOf(context).denyPermissions(Manifest.permission.READ_PHONE_NUMBERS)
        assertEquals("", CarrierDetector.detectPhoneNumberFromDevice(context))
    }
}
