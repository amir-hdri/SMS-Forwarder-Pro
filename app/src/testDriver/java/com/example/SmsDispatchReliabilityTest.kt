package com.example

import android.Manifest
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import android.os.Build
import android.telephony.SmsManager
import androidx.room.Room
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.AesEncryptionUtils
import com.example.crypto.SecureStorageManager
import com.example.data.local.AppDatabase
import com.example.data.model.ForwardConfig
import com.example.data.model.ForwardLog
import com.example.data.model.ForwardStatus
import com.example.data.model.SmsType
import com.example.data.repository.SmsForwardRepository
import com.example.network.SmsForwarderClient
import com.example.service.SmsResultReceiver
import com.example.utils.SmsDispatch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSmsManager
import org.robolectric.shadows.ShadowBroadcastPendingResult
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34], application = Application::class)
class SmsDispatchReliabilityTest {
    @get:org.junit.Rule val secureStorage = SecureStorageTestRule()
    private lateinit var context: Application
    private lateinit var db: AppDatabase
    private lateinit var repository: SmsForwardRepository
    private val callbackReceiver = SmsResultReceiver()
    private val singleton = SmsForwardRepository::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }

    private fun modem(): SmsManager = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(SmsManager::class.java).createForSubscriptionId(17)
    } else {
        @Suppress("DEPRECATION")
        SmsManager.getSmsManagerForSubscriptionId(17)
    }

    @Before fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        val filter = IntentFilter().apply { addAction(SmsDispatch.ACTION_SENT); addAction(SmsDispatch.ACTION_DELIVERED) }
        shadowOf(context).grantPermissions("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        ContextCompat.registerReceiver(context, callbackReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        shadowOf(context).grantPermissions(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS)
        ShadowSmsManager.setDefaultSmsSubscriptionId(1)
        shadowOf(modem()).clearLastSentTextMessageParams()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = SmsForwardRepository(db, SmsForwarderClient(OkHttpClient.Builder().addInterceptor {
            throw AssertionError("Driver OTP transport must never call HTTP")
        }.build()), context)
        singleton.set(null, repository)
        repository.saveConfig(ForwardConfig(isMasterEnabled = true, userConsentGiven = true,
            setupCompleted = true, isEncryptionEnabled = false, allowCleartextTransport = true,
            driverPhone = "09120000001", authHeaderValue = "a".repeat(64),
            endpointUrl = "http://87.107.160.251/api/v1/otp/sms-forwarder/09120000001",
            fallbackServerPhoneNumber = "09120000002", hubIrancellPhoneNumber = "09350000003"))
    }

    @After fun close() { context.unregisterReceiver(callbackReceiver); singleton.set(null, null); db.close() }

    private suspend fun insert(): Long {
        val body = AesEncryptionUtils.encrypt("کد تایید: 54321", SecureStorageManager.getInstance(context).getOrCreateOutboxKey())
        return db.forwardLogDao().insertLog(ForwardLog(sender = "20007777", messageBody = "کد تایید: ***",
            recipientPhone = "09120000001", receivedTimestamp = System.currentTimeMillis(),
            smsType = SmsType.UTCMS_OTP, simSlot = "SIM 2 (Sub #17)", encryptedBody = "v2:${body.iv}:${body.ciphertext}"))
    }

    @Test fun handingToModemDoesNotFinishPersistentOutbox() = runBlocking {
        val id = insert()
        assertTrue(repository.transmitPendingLog(id).isSuccess)
        assertNotNull(shadowOf(modem()).lastSentTextMessageParams)
        assertNotEquals("A later radio error must remain recoverable", ForwardStatus.SUCCESS, repository.getLogById(id)!!.status)
    }

    @Test fun realModemDispatchHasDistinctImmutableResultCallbacks() = runBlocking {
        val id = insert()
        repository.transmitPendingLog(id)
        val send = shadowOf(modem()).lastSentTextMessageParams
        assertNotNull(send)
        assertNotNull("A radio send failure must reach the persistent outbox", send.sentIntent)
        assertNotNull("Carrier delivery is a separate observation", send.deliveryIntent)
        assertNotEquals(send.sentIntent, send.deliveryIntent)
        val intent = shadowOf(send.sentIntent).savedIntent
        assertEquals(id, intent.getLongExtra("log_id", -1))
        assertTrue(intent.getLongExtra("attempt_at_ms", 0) > 0)
        assertNotNull(intent.component)
        assertNotNull(intent.data)
        if (Build.VERSION.SDK_INT >= 31) assertTrue(send.sentIntent.isImmutable)
    }

    @Test fun aSecondWorkerCannotSendAnAlreadyInFlightPacket() = runBlocking {
        val id = insert()
        repository.transmitPendingLog(id)
        val first = repository.getLogById(id)!!
        val waiting = repository.transmitPendingLog(id)
        assertFalse(waiting.isSuccess)
        assertTrue(waiting.isRetryable)
        assertEquals(first.lastRetryTimestamp, repository.getLogById(id)!!.lastRetryTimestamp)
        assertEquals(1, repository.getLogById(id)!!.retryCount)
    }

    @Test fun aRealRadioFailureImmediatelyUsesTheAlternateHubWithTheSamePacketIdentity() = runBlocking {
        val id = insert()
        repository.transmitPendingLog(id)
        val first = repository.getLogById(id)!!
        val packet = shadowOf(modem()).lastSentTextMessageParams
        assertTrue(repository.handleSmsDispatchResult(id, first.lastRetryTimestamp!!,
            SmsManager.RESULT_ERROR_GENERIC_FAILURE, false))
        val second = repository.getLogById(id)!!
        val retry = shadowOf(modem()).lastSentTextMessageParams
        assertEquals(ForwardStatus.SMS_DISPATCHED, second.status)
        assertEquals(2, second.retryCount)
        assertNotEquals(first.lastRetryTimestamp, second.lastRetryTimestamp)
        assertNotEquals(packet.destinationAddress, retry.destinationAddress)
        assertEquals(packet.text, retry.text)
        assertEquals(first.receivedTimestamp, second.receivedTimestamp)
    }

    @Test fun staleCallbacksCannotConfirmAReplacementAttempt() = runBlocking {
        val id = insert()
        repository.transmitPendingLog(id)
        val first = repository.getLogById(id)!!
        repository.handleSmsDispatchResult(id, first.lastRetryTimestamp!!, SmsManager.RESULT_ERROR_GENERIC_FAILURE, false)
        val second = repository.getLogById(id)!!
        assertFalse(repository.handleSmsDispatchResult(id, first.lastRetryTimestamp, android.app.Activity.RESULT_OK, true, SmsDispatch.DeliveryReport.DELIVERED))
        assertEquals(ForwardStatus.SMS_DISPATCHED, repository.getLogById(id)!!.status)
        assertTrue(repository.handleSmsDispatchResult(id, second.lastRetryTimestamp!!, android.app.Activity.RESULT_OK, false))
        assertEquals(ForwardStatus.SMS_SENT, repository.getLogById(id)!!.status)
        assertTrue(repository.handleSmsDispatchResult(id, second.lastRetryTimestamp, android.app.Activity.RESULT_OK, true, SmsDispatch.DeliveryReport.DELIVERED))
        assertEquals(ForwardStatus.SUCCESS, repository.getLogById(id)!!.status)
    }

    @Test fun aDeliveryReportArrivingFirstCannotBeDowngradedByTheSentCallback() = runBlocking {
        val id = insert()
        repository.transmitPendingLog(id)
        val attempt = repository.getLogById(id)!!.lastRetryTimestamp!!
        assertTrue(repository.handleSmsDispatchResult(id, attempt, android.app.Activity.RESULT_OK, true, SmsDispatch.DeliveryReport.DELIVERED))
        assertFalse(repository.handleSmsDispatchResult(id, attempt, android.app.Activity.RESULT_OK, false))
        assertEquals(ForwardStatus.SUCCESS, repository.getLogById(id)!!.status)
    }

    @Test fun noServiceWaitsForRadioRecoveryInsteadOfBurningEverySendAttempt() = runBlocking {
        val id = insert()
        repository.transmitPendingLog(id)
        val first = repository.getLogById(id)!!
        assertTrue(repository.handleSmsDispatchResult(id, first.lastRetryTimestamp!!, SmsManager.RESULT_ERROR_NO_SERVICE, false))
        val failed = repository.getLogById(id)!!
        assertEquals(ForwardStatus.FAILED, failed.status)
        assertEquals(1, failed.retryCount)
        assertEquals(first.lastRetryTimestamp, failed.lastRetryTimestamp)
    }

    @Test fun missingCallbackRecoversFromThePersistedDeadlineAndKeepsOriginalTimestamp() = runBlocking {
        val id = insert()
        repository.transmitPendingLog(id)
        val first = repository.getLogById(id)!!
        db.forwardLogDao().updateLog(first.copy(lastRetryTimestamp = System.currentTimeMillis() - 46_000))
        assertTrue(repository.transmitPendingLog(id).isSuccess)
        val recovered = repository.getLogById(id)!!
        assertEquals(2, recovered.retryCount)
        assertEquals(first.receivedTimestamp, recovered.receivedTimestamp)
        assertEquals(ForwardStatus.SMS_DISPATCHED, recovered.status)
    }

    // Hand-encoded TS 23.040 SMS-STATUS-REPORT: no SMSC, TP-MTI=2, TP-MR=1,
    // recipient +989120000001, two valid seven-byte timestamps and TP-ST as the last byte.
    private fun report(status: Int): Intent = Intent().putExtra("format", "3gpp")
        .putExtra("pdu", ("0002010C918919020000106201034000000062010340100000" +
            "%02x".format(status)).chunked(2).map { it.toInt(16).toByte() }.toByteArray())

    private suspend fun deliver(callback: PendingIntent, fillIn: Intent) {
        callback.send(context, android.app.Activity.RESULT_OK, fillIn)
        // Robolectric materializes explicit manifest receivers on the first send.
        val receivers = shadowOf(context).getReceiversForIntent(shadowOf(callback).savedIntent)
            .filterIsInstance<SmsResultReceiver>()
        assertTrue("The real manifest receiver must be present", receivers.isNotEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        val completions = receivers.mapNotNull { shadowOf(it).originalPendingResult }
            .map { Shadow.extract<ShadowBroadcastPendingResult>(it).future }
        withTimeout(4_000) {
            while (completions.any { !it.isDone }) { shadowOf(Looper.getMainLooper()).idle(); delay(10) }
        }
    }

    @Test fun deliveryCallbackAcceptsFrameworkPduAndRemainsReusable() = runBlocking {
        val id = insert()
        repository.transmitPendingLog(id)
        val send = shadowOf(modem()).lastSentTextMessageParams
        if (Build.VERSION.SDK_INT >= 31) {
            assertTrue(send.sentIntent.isImmutable)
            assertFalse("Delivery reports need the framework's pdu/format fill-in", send.deliveryIntent.isImmutable)
        }
        deliver(send.deliveryIntent, report(0x20))
        assertNotEquals("A temporary pending report is not receipt by the Hub", ForwardStatus.SUCCESS, repository.getLogById(id)!!.status)
        deliver(send.deliveryIntent, report(0x00))
        assertEquals(ForwardStatus.SUCCESS, repository.getLogById(id)!!.status)
    }

    @Test fun permanentDeliveryFailureUsesAlternateHubInsteadOfRecordingSuccess() = runBlocking {
        val id = insert()
        repository.transmitPendingLog(id)
        val first = repository.getLogById(id)!!
        val send = shadowOf(modem()).lastSentTextMessageParams
        deliver(send.deliveryIntent, report(0x40))
        withTimeout(3_000) {
            while (repository.getLogById(id)!!.let { it.retryCount == first.retryCount && it.status != ForwardStatus.SUCCESS }) delay(10)
        }
        val retried = repository.getLogById(id)!!
        assertEquals("A permanent TP-status failure must remain recoverable; attempts=${retried.retryCount}, error=${retried.errorMessage}", ForwardStatus.SMS_DISPATCHED, retried.status)
        assertEquals(2, retried.retryCount)
        assertNotEquals(first.lastRetryTimestamp, retried.lastRetryTimestamp)
        assertNotEquals(send.destinationAddress, shadowOf(modem()).lastSentTextMessageParams.destinationAddress)
    }

    @Test fun missingDeliveryPduCannotConfirmHubReceipt() = runBlocking {
        val id = insert()
        repository.transmitPendingLog(id)
        deliver(shadowOf(modem()).lastSentTextMessageParams.deliveryIntent, Intent())
        assertNotEquals("RESULT_OK alone is not a delivery status", ForwardStatus.SUCCESS, repository.getLogById(id)!!.status)
    }

    @Test fun cancellationRevokesBothAttemptCapabilities() = runBlocking {
        val id = insert()
        repository.transmitPendingLog(id)
        val attempt = repository.getLogById(id)!!.lastRetryTimestamp!!
        val send = shadowOf(modem()).lastSentTextMessageParams
        SmsDispatch.cancelAttempt(context, id, attempt)
        for (callback in listOf(send.sentIntent, send.deliveryIntent)) {
            assertTrue("Superseded callbacks must be revoked, including their identity flags",
                runCatching { callback.send() }.exceptionOrNull() is PendingIntent.CanceledException)
        }
    }

    @Test fun finalUnconfirmedAttemptRetainsItsLateSentCallbackWithinOtpValidity() = runBlocking {
        val id = insert()
        val attempt = System.currentTimeMillis() - 46_000
        val fixture = repository.getLogById(id)!!.copy(status = ForwardStatus.SMS_DISPATCHED,
            retryCount = 5, lastRetryTimestamp = attempt)
        db.forwardLogDao().updateLog(fixture)
        val lateSent = SmsDispatch.resultIntent(context, id, attempt, false)
        assertFalse(repository.transmitPendingLog(id).isRetryable)
        assertEquals("Exhausting attempts cannot invalidate the last authentic callback", attempt, repository.getLogById(id)!!.lastRetryTimestamp)
        assertEquals(ForwardStatus.SMS_DISPATCHED, repository.getLogById(id)!!.status)
        assertTrue(repository.handleSmsDispatchResult(id, attempt, android.app.Activity.RESULT_OK, false))
        assertEquals(ForwardStatus.SMS_SENT, repository.getLogById(id)!!.status)
        assertFalse(runCatching { lateSent.send() }.exceptionOrNull() is PendingIntent.CanceledException)
    }

    @Test fun aLateGenericExpiryWriterCannotDowngradeConfirmedDelivery() = runBlocking {
        val id = insert()
        repository.transmitPendingLog(id)
        val attempt = repository.getLogById(id)!!.lastRetryTimestamp!!
        repository.handleSmsDispatchResult(id, attempt, android.app.Activity.RESULT_OK, true, SmsDispatch.DeliveryReport.DELIVERED)
        assertEquals(ForwardStatus.SUCCESS, repository.getLogById(id)!!.status)
        repository.updateLogStatus(id, ForwardStatus.FAILED, "stale worker expiry snapshot")
        assertEquals("Expiry/error writers must reread and preserve acknowledged success", ForwardStatus.SUCCESS, repository.getLogById(id)!!.status)
    }
}
