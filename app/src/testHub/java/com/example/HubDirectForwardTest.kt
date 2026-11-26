package com.example

import android.Manifest
import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import com.example.data.local.AppDatabase
import com.example.data.model.AuthType
import com.example.data.model.ForwardConfig
import com.example.data.model.ForwardStatus
import com.example.data.repository.SmsForwardRepository
import com.example.enrollment.DeviceBootstrapKey
import com.example.network.BarProContract
import com.example.network.SmsForwarderClient
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HubDirectForwardTest {
    @get:Rule val secureStorage = SecureStorageTestRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var manager: WorkManagerImpl

    @Before fun prepare() {
        shadowOf(context).grantPermissions(Manifest.permission.RECEIVE_SMS)
        val queued = LinkedBlockingQueue<Runnable>()
        WorkManager.initialize(
            context,
            Configuration.Builder()
                .setExecutor(Executor { queued.add(it) })
                .setTaskExecutor(Executor { queued.add(it) })
                .build()
        )
        manager = WorkManager.getInstance(context) as WorkManagerImpl
    }

    @After fun close() {
        manager.workDatabase.close()
        WorkManagerImpl.setDelegate(null)
    }

    @Test fun scopedHubForwardsDirectOtpWithoutDriverPhoneAndSendsHubNumbers() = runBlocking {
        OwnedHubTlsServer().use { owned ->
            val capturedBody = AtomicReference<JSONObject>()
            val capturedRoleHeader = AtomicReference<String?>()
            val scopedToken = "HUB1_" + "a".repeat(16) + "_" + "b".repeat(64)
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val deviceId = "hub_" + MessageDigest.getInstance("SHA-256").digest(pair.public.encoded)
                .joinToString("") { "%02x".format(it) }.take(32)

            owned.server.createContext("/api/v1/otp/sms-forwarder") { exchange ->
                val rawBody = exchange.requestBody.readBytes()
                val json = JSONObject(rawBody.toString(Charsets.UTF_8))
                capturedBody.set(json)
                capturedRoleHeader.set(exchange.requestHeaders.getFirst("X-App-Role"))
                require(exchange.requestHeaders.getFirst("X-OTP-Webhook-Token") == scopedToken)
                val hash = MessageDigest.getInstance("SHA-256").digest(rawBody).joinToString("") { "%02x".format(it) }
                val device = exchange.requestHeaders.getFirst("X-OTP-Hub-Device")
                val timestamp = exchange.requestHeaders.getFirst("X-OTP-Hub-Timestamp")
                val nonce = exchange.requestHeaders.getFirst("X-OTP-Hub-Nonce")
                val proof = "barpro-hub-request-v1\n$device\nPOST\n/api/v1/otp/sms-forwarder\n$timestamp\n$nonce\n$hash"
                require(java.security.Signature.getInstance("SHA256withRSA").run {
                    initVerify(pair.public)
                    update(proof.toByteArray(Charsets.UTF_8))
                    verify(android.util.Base64.decode(exchange.requestHeaders.getFirst("X-OTP-Hub-Signature"), android.util.Base64.NO_WRAP))
                })
                val body = """{"success":true,"status":"success","otp_detected":true}"""
                exchange.sendResponseHeaders(200, body.length.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }

            val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            try {
                val client = SmsForwarderClient(owned.client, DeviceBootstrapKey { pair }, context)
                val repository = SmsForwardRepository(db, client, context)
                val hubConfig = ForwardConfig(
                    endpointUrl = owned.root + "/api/v1/otp/sms-forwarder",
                    authType = AuthType.CUSTOM_HEADER,
                    authHeaderKey = BarProContract.TOKEN_HEADER,
                    authHeaderValue = scopedToken,
                    forwarderSecret = scopedToken,
                    deviceIdentifier = deviceId,
                    driverPhone = "",
                    fallbackServerPhoneNumber = "09966567754",
                    hubIrancellPhoneNumber = "09047119812",
                    isMasterEnabled = true,
                    userConsentGiven = true,
                    setupCompleted = true,
                    maxRetries = 0
                )
                repository.saveConfig(hubConfig)

                val log = repository.processIncomingSms(
                    sender = "09184110414",
                    messageBody = "کد تایید: 525456",
                    receivedTimestamp = System.currentTimeMillis(),
                    simSlot = "SIM 1"
                )

                assertEquals("Direct OTP on Hub must succeed instead of SKIPPED: ${log.errorMessage}", ForwardStatus.SUCCESS, log.status)
                assertEquals(200, log.httpStatusCode)
                val sent = capturedBody.get()
                assertNotNull("HTTP request must actually reach server", sent)
                assertEquals("HUB", capturedRoleHeader.get())
                assertEquals("HUB", sent.optString("app_role"))
                assertEquals("", sent.optString("driver_phone"))
                val hubNumbers = sent.optJSONArray("hub_numbers")
                assertNotNull("Hub direct forward must carry hub_numbers", hubNumbers)
                assertEquals(listOf("09966567754", "09047119812"), (0 until hubNumbers!!.length()).map { hubNumbers.getString(it) })
            } finally {
                db.close()
            }
        }
    }
}
