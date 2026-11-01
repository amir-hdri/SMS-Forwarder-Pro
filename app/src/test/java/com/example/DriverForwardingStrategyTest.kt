package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.AesEncryptionUtils
import com.example.crypto.SecureStorageManager
import com.example.data.local.AppDatabase
import com.example.data.model.AuthType
import com.example.data.model.ForwardConfig
import com.example.data.model.ForwardLog
import com.example.data.model.ForwardStatus
import com.example.data.model.SmsType
import com.example.data.repository.SmsForwardRepository
import com.example.network.BarProContract
import com.example.network.SmsForwarderClient
import com.example.service.SmsResultReceiver
import com.example.utils.SmsDispatch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSmsManager
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Unit tests validating driver forwarding strategy:
 * 1. HTTP POST forwarding with X-Webhook-Token header and JSON payload for incoming OTP SMS.
 * 2. Graceful cellular GSM relay fallback upon HTTP network failure.
 * 3. Periodic cellular health checks bypassing HTTP calls and routing directly to SMS modem.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DriverForwardingStrategyTest {

    @get:Rule
    val secureStorage = SecureStorageTestRule()

    private lateinit var context: Application
    private lateinit var db: AppDatabase
    private val callbackReceiver = SmsResultReceiver()
    private val webhookToken = "a".repeat(64)
    private val driverPhone = "09120000001"
    private val hubMci = "09120000002"
    private val hubIrancell = "09350000003"
    private val endpointUrl = "http://87.107.160.251/api/v1/otp/sms-forwarder/$driverPhone"

    private fun modem(): SmsManager = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(SmsManager::class.java).createForSubscriptionId(17)
    } else {
        @Suppress("DEPRECATION")
        SmsManager.getSmsManagerForSubscriptionId(17)
    }

    private val baseConfig = ForwardConfig(
        isMasterEnabled = true,
        userConsentGiven = true,
        setupCompleted = true,
        allowCleartextTransport = true,
        primarySmsRelayEnabled = true,
        enableSmsFallback = true,
        driverPhone = driverPhone,
        authType = AuthType.CUSTOM_HEADER,
        authHeaderKey = BarProContract.TOKEN_HEADER,
        authHeaderValue = webhookToken,
        forwarderSecret = webhookToken,
        endpointUrl = endpointUrl,
        fallbackServerPhoneNumber = hubMci,
        hubIrancellPhoneNumber = hubIrancell
    )

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val filter = IntentFilter().apply {
            addAction(SmsDispatch.ACTION_SENT)
            addAction(SmsDispatch.ACTION_DELIVERED)
        }
        shadowOf(context).grantPermissions("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        ContextCompat.registerReceiver(context, callbackReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        shadowOf(context).grantPermissions(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS)
        ShadowSmsManager.setDefaultSmsSubscriptionId(1)
        shadowOf(modem()).clearLastSentTextMessageParams()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        try {
            context.unregisterReceiver(callbackReceiver)
        } catch (_: Exception) {}
        db.close()
    }

    private suspend fun insertEncryptedLog(
        sender: String = "20007777",
        rawMessage: String = "کد ورود به سامانه بارپرو: 78912",
        recipient: String = driverPhone
    ): Long {
        val outboxKey = SecureStorageManager.getInstance(context).getOrCreateOutboxKey()
        val encrypted = AesEncryptionUtils.encrypt(rawMessage, outboxKey)
        return db.forwardLogDao().insertLog(
            ForwardLog(
                sender = sender,
                messageBody = "کد ورود به سامانه بارپرو: ***",
                recipientPhone = recipient,
                receivedTimestamp = System.currentTimeMillis(),
                smsType = SmsType.UTCMS_OTP,
                simSlot = "SIM 2 (Sub #17)",
                encryptedBody = "v2:${encrypted.iv}:${encrypted.ciphertext}"
            )
        )
    }

    @Test
    fun testHttpForwardingAttachesWebhookTokenHeaderAndSucceeds() = runBlocking {
        val capturedHeader = AtomicReference<String?>()
        val capturedUrl = AtomicReference<String?>()
        val requestIntercepted = AtomicBoolean(false)

        val httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                capturedHeader.set(request.header("X-Webhook-Token"))
                capturedUrl.set(request.url.toString())
                requestIntercepted.set(true)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"success":true,"status":"success","otp_detected":true}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val repository = SmsForwardRepository(db, SmsForwarderClient(httpClient), context)
        repository.saveConfig(baseConfig)

        val logId = insertEncryptedLog()
        val result = repository.transmitPendingLog(logId)

        assertTrue("HTTP forward should succeed with 200 response: ${result.errorMessage}", result.isSuccess)
        assertTrue("HTTP request should be intercepted", requestIntercepted.get())
        assertEquals("X-Webhook-Token must match configured secret", webhookToken, capturedHeader.get())
        assertNotNull(capturedUrl.get())
        assertTrue("Request URL should target endpoint", capturedUrl.get()!!.contains("/api/v1/otp/sms-forwarder"))

        val updatedLog = db.forwardLogDao().getLogById(logId)
        assertNotNull(updatedLog)
        assertEquals("Log status must be SUCCESS", ForwardStatus.SUCCESS, updatedLog!!.status)
    }

    @Test
    fun testHttpFailureTriggersGracefulCellularGsmRelayFallback() = runBlocking {
        val httpAttempted = AtomicBoolean(false)

        val failingClient = OkHttpClient.Builder()
            .addInterceptor { _ ->
                httpAttempted.set(true)
                throw IOException("Network unreachable: simulated offline connection")
            }
            .build()

        val repository = SmsForwardRepository(db, SmsForwarderClient(failingClient), context)
        repository.saveConfig(baseConfig)

        val logId = insertEncryptedLog()
        val result = repository.transmitPendingLog(logId)

        assertTrue("HTTP request must be attempted first", httpAttempted.get())
        if (BuildConfig.APP_ROLE == "HUB") {
            assertFalse("Hub must retain failure rather than recursively relay to itself", result.isSuccess)
            assertNull("Hub must not dispatch a driver relay", shadowOf(modem()).lastSentTextMessageParams)
            return@runBlocking
        }
        assertTrue("GSM fallback transmission must report success when handed to modem: ${result.errorMessage}", result.isSuccess)

        val updatedLog = db.forwardLogDao().getLogById(logId)
        assertNotNull(updatedLog)
        assertEquals("Status must transition to SMS_DISPATCHED", ForwardStatus.SMS_DISPATCHED, updatedLog!!.status)

        val lastSent = shadowOf(modem()).lastSentTextMessageParams
        assertNotNull("Modem should have received the fallback SMS", lastSent)
        assertEquals("Fallback SMS should be addressed to MCI hub for 0912 driver", hubMci, lastSent!!.destinationAddress)
        assertTrue("Fallback SMS payload must start with BP1# signature prefix", lastSent.text.startsWith("BP1#$driverPhone#"))
        assertTrue("Fallback SMS payload must contain extracted OTP 78912", lastSent.text.contains("78912"))
    }

    @Test
    fun testCellularHealthCheckBypassesHttpCompletely() = runBlocking {
        val httpClient = OkHttpClient.Builder()
            .addInterceptor { _ ->
                fail("Health check must not make HTTP calls")
                throw AssertionError("Unreachable")
            }
            .build()

        val repository = SmsForwardRepository(db, SmsForwarderClient(httpClient), context)
        repository.saveConfig(baseConfig)

        val logId = insertEncryptedLog(
            sender = "HEARTBEAT",
            rawMessage = "HEALTH_CHECK"
        )

        val result = repository.transmitPendingLog(logId)

        if (BuildConfig.APP_ROLE == "HUB") {
            assertFalse("Driver GSM heartbeat is not a Hub OTP", result.isSuccess)
            assertNull("Hub must not dispatch a driver heartbeat", shadowOf(modem()).lastSentTextMessageParams)
            return@runBlocking
        }
        assertTrue("Health check GSM dispatch should succeed: ${result.errorMessage}", result.isSuccess)

        val updatedLog = db.forwardLogDao().getLogById(logId)
        assertNotNull(updatedLog)
        assertEquals("Status should be SMS_DISPATCHED", ForwardStatus.SMS_DISPATCHED, updatedLog!!.status)

        val lastSent = shadowOf(modem()).lastSentTextMessageParams
        assertNotNull("Modem should have dispatched health check SMS", lastSent)
        assertEquals("Health check should route to hub MCI", hubMci, lastSent!!.destinationAddress)
        assertTrue("Health check payload must start with BP1#", lastSent.text.startsWith("BP1#$driverPhone#"))
        assertTrue("Health check payload must encode HEALTH_CHECK code", lastSent.text.contains("HEALTH_CHECK"))
    }
}
