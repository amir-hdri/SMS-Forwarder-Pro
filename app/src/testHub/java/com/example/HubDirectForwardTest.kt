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
import com.example.utils.DriverSmsSubscription
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
                    sender = "30001923",
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

    @Test fun structuredSimIndexIgnoresSubIdDigitsAndExtractsExplicitSlot() = runBlocking {
        assertEquals(1, DriverSmsSubscription.slotNumber("SIM 1 (Sub #2)"))
        assertEquals(1, DriverSmsSubscription.slotNumber("SIM 1 (Sub #12)"))
        assertEquals(2, DriverSmsSubscription.slotNumber("SIM 2 (Sub #1)"))
        assertEquals(2, DriverSmsSubscription.slotNumber("SIM 2 (Sub #22)"))
        assertNull(DriverSmsSubscription.slotNumber("Sub #2"))
        assertNull(DriverSmsSubscription.slotNumber("Sub #4"))
        assertNull(DriverSmsSubscription.slotNumber("Unknown SIM"))

        OwnedHubTlsServer().use { owned ->
            val bodies = mutableListOf<JSONObject>()
            val scopedToken = "HUB1_" + "a".repeat(16) + "_" + "b".repeat(64)
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val deviceId = "hub_" + MessageDigest.getInstance("SHA-256").digest(pair.public.encoded)
                .joinToString("") { "%02x".format(it) }.take(32)

            owned.server.createContext("/api/v1/otp/sms-forwarder") { exchange ->
                val rawBody = exchange.requestBody.readBytes()
                bodies.add(JSONObject(rawBody.toString(Charsets.UTF_8)))
                val body = """{"success":true,"status":"success","otp_detected":true}"""
                exchange.sendResponseHeaders(200, body.length.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }

            val client = SmsForwarderClient(owned.client, DeviceBootstrapKey { pair }, context)
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

            val unlisted = client.forwardMessage(
                sender = "09120000002",
                messageBody = "کد تایید: 112233",
                timestamp = System.currentTimeMillis(),
                config = hubConfig,
                simSlot = "SIM 1"
            )
            assertFalse("A direct OTP requires a coordinated sender", unlisted.isSuccess)
            val wrongLength = client.forwardMessage(
                sender = "30001923",
                messageBody = "کد تایید: 1122337",
                timestamp = System.currentTimeMillis(),
                config = hubConfig,
                simSlot = "SIM 1"
            )
            assertFalse("A direct OTP requires six digits", wrongLength.isSuccess)
            assertTrue("Rejected messages must never reach the server", bodies.isEmpty())

            // Case 1: SIM 1 (Sub #12) sends sim_index=1 (must NOT treat "12" as slot 2!)
            val res1 = client.forwardMessage(
                sender = "30001923",
                messageBody = "کد تایید: 112233",
                timestamp = System.currentTimeMillis(),
                config = hubConfig,
                simSlot = "SIM 1 (Sub #12)"
            )
            assertTrue("SIM 1 (Sub #12) should succeed: ${res1.errorMessage}", res1.isSuccess)
            assertEquals("SIM 1 (Sub #12)", bodies.last().optString("sim_slot"))
            assertEquals(1, bodies.last().optInt("sim_index", -1))

            // Case 2: Sub #2 without slot extra must omit sim_index rather than guessing 2
            val res2 = client.forwardMessage(
                sender = "30001923",
                messageBody = "کد تایید: 445566",
                timestamp = System.currentTimeMillis(),
                config = hubConfig,
                simSlot = "Sub #2"
            )
            assertTrue("Sub #2 should succeed: ${res2.errorMessage}", res2.isSuccess)
            assertEquals("Sub #2", bodies.last().optString("sim_slot"))
            assertFalse("Sub #2 without slot prefix must not emit sim_index", bodies.last().has("sim_index"))

            // Case 3: Irancell SIM resolved via SubscriptionManager emits hub_target_phone even for Sub #4 (no slot extra)
            shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
            val tm = context.getSystemService(android.content.Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            shadowOf(tm).setSimOperator("43235")
            shadowOf(tm).setSimOperatorName("MTN Irancell")
            val subMgr = context.getSystemService(android.content.Context.TELEPHONY_SUBSCRIPTION_SERVICE) as android.telephony.SubscriptionManager
            val subInfo = org.robolectric.shadows.ShadowSubscriptionManager.SubscriptionInfoBuilder.newBuilder()
                .setId(4)
                .setSimSlotIndex(1)
                .setCarrierName("Irancell")
                .setMcc("432")
                .setMnc("35")
                .buildSubscriptionInfo()
            shadowOf(subMgr).setActiveSubscriptionInfos(subInfo)

            val res3 = client.forwardMessage(
                sender = "30001923",
                messageBody = "کد تایید: 778899",
                timestamp = System.currentTimeMillis(),
                config = hubConfig,
                simSlot = "Sub #4"
            )
            assertTrue("Sub #4 with SubscriptionManager info should succeed: ${res3.errorMessage}", res3.isSuccess)
            assertEquals(2, bodies.last().optInt("sim_index", -1))
            assertEquals("09047119812", bodies.last().optString("hub_target_phone"))
        }
    }
}
