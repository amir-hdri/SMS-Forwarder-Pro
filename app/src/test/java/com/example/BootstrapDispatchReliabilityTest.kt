package com.example

import android.Manifest
import android.app.Activity
import android.app.Application
import android.telephony.SmsManager
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.SecureStorageManager
import com.example.enrollment.BootstrapSms
import com.example.enrollment.BootstrapSmsDispatch
import com.example.utils.SmsDispatch
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSubscriptionManager

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class BootstrapDispatchReliabilityTest {
    @get:Rule val secureStorage = SecureStorageTestRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val nonce = "r".repeat(43)
    @Before fun permission() {
        shadowOf(context).grantPermissions(Manifest.permission.SEND_SMS)
        ShadowSubscriptionManager.setDefaultSmsSubscriptionId(17)
    }

    @Test fun duplicateEntryCannotReplaceAnInFlightNonceAttempt() {
        assertTrue(BootstrapSms.send(context, "09120000002", nonce))
        val first = BootstrapSmsDispatch.state(context, nonce)!!
        assertTrue(BootstrapSms.send(context, "09120000002", nonce))
        assertEquals("An in-flight attempt must remain one modem handoff", first, BootstrapSmsDispatch.state(context, nonce))
    }

    @Test fun radioFailureIsRecordedWithoutActivatingOrLosingTheNonce() {
        BootstrapSms.send(context, "09120000002", nonce)
        val first = BootstrapSmsDispatch.state(context, nonce)!!
        assertTrue(BootstrapSmsDispatch.handleResult(context, nonce, first.attemptAt, SmsManager.RESULT_ERROR_NO_SERVICE, false, null))
        val failed = BootstrapSmsDispatch.state(context, nonce)!!
        assertEquals("failed", failed.status)
        assertEquals(nonce, failed.nonce)
        assertEquals(1, failed.attempts)
        assertTrue(failed.retryAt > System.currentTimeMillis())
        assertEquals("", SecureStorageManager.getInstance(context).getForwarderSecret())
    }

    @Test fun confirmedNetworkDeliveryCannotBeDowngradedAndIsNotAnEnrollmentGrant() {
        BootstrapSms.send(context, "09120000002", nonce)
        val first = BootstrapSmsDispatch.state(context, nonce)!!
        assertTrue(BootstrapSmsDispatch.handleResult(context, nonce, first.attemptAt, Activity.RESULT_OK, true,
            SmsDispatch.DeliveryReport.DELIVERED))
        assertFalse(BootstrapSmsDispatch.handleResult(context, nonce, first.attemptAt, Activity.RESULT_OK, false, null))
        assertEquals("delivered", BootstrapSmsDispatch.state(context, nonce)!!.status)
        assertEquals("", SecureStorageManager.getInstance(context).getForwarderSecret())
    }

    @Test fun missingCallbackRecoversFromAnEncryptedPersistedDeadline() {
        val expiredAttempt = System.currentTimeMillis() - 50_000
        SecureStorageManager.getInstance(context).saveBootstrapSmsAttempt(JSONObject().put("nonce", nonce)
            .put("attempt_at", expiredAttempt).put("attempts", 1).put("status", "dispatching")
            .put("retry_at", expiredAttempt + 45_000).toString())
        assertTrue(BootstrapSms.send(context, "09120000002", nonce))
        val retry = BootstrapSmsDispatch.state(context, nonce)!!
        assertEquals(2, retry.attempts)
        assertTrue(retry.attemptAt > expiredAttempt)
        assertFalse(BootstrapSmsDispatch.handleResult(context, nonce, expiredAttempt, Activity.RESULT_OK, false, null))
        val modem = context.getSystemService(SmsManager::class.java).createForSubscriptionId(17)
        assertEquals("BPA1#$nonce", shadowOf(modem).lastSentTextMessageParams.text)
    }

    @Test fun deliveryWithoutAValidatedStatusReportDoesNotConfirmAnything() {
        BootstrapSms.send(context, "09120000002", nonce)
        val first = BootstrapSmsDispatch.state(context, nonce)!!
        assertFalse(BootstrapSmsDispatch.handleResult(context, nonce, first.attemptAt, Activity.RESULT_OK, true, null))
        assertEquals("dispatching", BootstrapSmsDispatch.state(context, nonce)!!.status)
    }
}
