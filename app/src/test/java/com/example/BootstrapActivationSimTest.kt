package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import com.example.enrollment.BootstrapHttpException
import com.example.enrollment.BootstrapSms
import com.example.enrollment.BootstrapSmsNotSent
import com.example.utils.PermissionHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSubscriptionManager

/**
 * The carrier proves the SMS origin, so activation must leave from the SIM whose own
 * number is the entered driver number. Covers both the pure decision and the Android
 * SubscriptionManager / PermissionHelper integration under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class BootstrapActivationSimTest {
    private val driver = "09120000002"
    private val other = "09120000003"
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `only the SIM carrying the driver number is used, even when it is not first`() {
        val sims = listOf(1 to other, 2 to driver)
        assertEquals(listOf(2), BootstrapSms.activationCandidates(sims, fallback = listOf(1, 2), phone = driver))
    }

    @Test
    fun `a second SIM with the same number stays an allowed retry candidate`() {
        val sims = listOf(1 to other, 2 to driver, 3 to driver)
        assertEquals(listOf(2, 3), BootstrapSms.activationCandidates(sims, fallback = listOf(1, 2, 3), phone = driver))
    }

    @Test
    fun `all readable numbers without the entered number refuse without wireless settings hijack`() {
        val sims = listOf(1 to other, 2 to "09120000004")
        val error = runCatching {
            BootstrapSms.activationCandidates(sims, fallback = listOf(1, 2), phone = driver)
        }.exceptionOrNull()
        assertTrue(error is BootstrapSmsNotSent)
        val notSent = error as BootstrapSmsNotSent
        assertFalse("Missing SIM must not trigger Android wireless settings", notSent.needsSmsSelection)
        assertFalse("Missing SIM must not keep retrying in background", notSent.retryable)
        assertTrue(notSent.message.orEmpty().contains(driver))
    }

    @Test
    fun `an unreadable platform keeps the previous default-SMS order`() {
        val sims = listOf(1 to null, 2 to null)
        assertEquals(listOf(1, 2), BootstrapSms.activationCandidates(sims, fallback = listOf(1, 2), phone = driver))
    }

    @Test
    fun `a partially readable dual-SIM excludes the known-wrong SIM and tries only the unknown SIM`() {
        val sims = listOf(1 to null, 2 to other)
        assertEquals(
            listOf(1),
            BootstrapSms.activationCandidates(sims, fallback = listOf(2, 1), phone = driver)
        )
    }

    @Test
    fun `blank phone keeps legacy candidate order`() {
        val sims = listOf(1 to other, 2 to null)
        assertEquals(listOf(1, 2), BootstrapSms.activationCandidates(sims, fallback = listOf(1, 2), phone = ""))
    }

    @Test
    fun `numbers are compared after normalisation`() {
        val sims = listOf(1 to "+98 912 000 0002")
        assertEquals(listOf(1), BootstrapSms.matchingSimIds(sims, "09120000002"))
    }

    @Test
    fun `driver activation permissions request both READ_PHONE_NUMBERS and READ_PHONE_STATE`() {
        val activationPerms = PermissionHelper.getDriverActivationRuntimePermissions().toList()
        if (BuildConfig.APP_ROLE == "DRIVER") {
            assertTrue(activationPerms.contains(Manifest.permission.READ_PHONE_NUMBERS))
            assertTrue(activationPerms.contains(Manifest.permission.READ_PHONE_STATE))
        }
    }

    @Test
    fun `device subscription manager picks matching non-default SIM when phone permissions are granted`() {
        shadowOf(context).grantPermissions(
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PHONE_NUMBERS
        )
        val subMgr = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as android.telephony.SubscriptionManager
        val sim1 = ShadowSubscriptionManager.SubscriptionInfoBuilder.newBuilder()
            .setId(10).setSimSlotIndex(0).setNumber(other).buildSubscriptionInfo()
        val sim2 = ShadowSubscriptionManager.SubscriptionInfoBuilder.newBuilder()
            .setId(20).setSimSlotIndex(1).setNumber("+989120000002").buildSubscriptionInfo()
        shadowOf(subMgr).setActiveSubscriptionInfos(sim1, sim2)
        ShadowSubscriptionManager.setDefaultSmsSubscriptionId(10)

        assertEquals(listOf(20), BootstrapSms.activationSubscriptions(context, driver))
    }

    @Test
    fun `default SMS number fallback blocks known-wrong SIM even if READ_PHONE_STATE is denied`() {
        shadowOf(context).grantPermissions(
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_NUMBERS
        )
        shadowOf(context).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        ShadowSubscriptionManager.setDefaultSmsSubscriptionId(17)
        val subMgr = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as android.telephony.SubscriptionManager
        shadowOf(subMgr).setReadPhoneNumbersPermission(true)
        shadowOf(subMgr).setPhoneNumber(17, other)
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        shadowOf(tm).setLine1Number(other)
        shadowOf(tm).setTelephonyManagerForSubscriptionId(17, tm)

        val error = runCatching {
            BootstrapSms.activationSubscriptions(context, driver)
        }.exceptionOrNull()
        assertTrue("Known-wrong default SIM should be refused: $error", error is BootstrapSmsNotSent)
        val notSent = error as BootstrapSmsNotSent
        assertFalse(notSent.retryable)
        assertFalse(notSent.needsSmsSelection)
        assertFalse(
            "Partial enumeration must not falsely claim the phone lacks the SIM",
            notSent.message.orEmpty().contains("ندارد؛ سیم‌کارت همان شماره")
        )
        assertTrue(notSent.message.orEmpty().contains("وضعیت سیم‌کارت"))
    }

    @Test
    @Config(sdk = [25])
    fun `api 25 uses READ_PHONE_STATE to pick the matching non-default SIM`() {
        shadowOf(context).grantPermissions(
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE
        )
        val subMgr = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as android.telephony.SubscriptionManager
        val sim1 = ShadowSubscriptionManager.SubscriptionInfoBuilder.newBuilder()
            .setId(10).setSimSlotIndex(0).setNumber(other).buildSubscriptionInfo()
        val sim2 = ShadowSubscriptionManager.SubscriptionInfoBuilder.newBuilder()
            .setId(20).setSimSlotIndex(1).setNumber(driver).buildSubscriptionInfo()
        shadowOf(subMgr).setActiveSubscriptionInfos(sim1, sim2)
        ShadowSubscriptionManager.setDefaultSmsSubscriptionId(10)

        assertEquals(listOf(20), BootstrapSms.activationSubscriptions(context, driver))
    }

    @Test
    fun `carrier detector uses default SMS getPhoneNumber fallback when READ_PHONE_STATE is denied`() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_NUMBERS)
        shadowOf(context).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        ShadowSubscriptionManager.setDefaultSmsSubscriptionId(17)
        val subMgr = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as android.telephony.SubscriptionManager
        shadowOf(subMgr).setReadPhoneNumbersPermission(true)
        shadowOf(subMgr).setPhoneNumber(17, driver)

        assertEquals(driver, com.example.utils.CarrierDetector.detectPhoneNumberFromDevice(context))
    }

    @Test
    fun `403 message distinguishes unregistered challenge from SIM-origin prove failure`() {
        val challengeMsg = BootstrapHttpException(403, "challenge").message.orEmpty()
        val proveMsg = BootstrapHttpException(403, "prove").message.orEmpty()
        assertTrue(challengeMsg.contains("ثبت") || challengeMsg.contains("فعال"))
        assertFalse("Challenge 403 must not blame another SIM before SMS was sent", challengeMsg.contains("سیم‌کارت دیگری"))
        assertTrue(proveMsg.contains("سیم‌کارت دیگری"))
    }
}
