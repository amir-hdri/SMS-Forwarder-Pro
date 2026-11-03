package com.example

import android.Manifest
import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import com.example.data.local.AppDatabase
import com.example.data.model.ForwardConfig
import com.example.data.model.ForwardStatus
import com.example.data.repository.SmsForwardRepository
import com.example.enrollment.DeviceBootstrapKey
import com.example.network.SmsForwarderClient
import com.example.utils.SmsFallbackEnvelope
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PrivateHubRelayTest {
    @get:Rule val secureStorage = SecureStorageTestRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var manager: WorkManagerImpl

    @Before fun prepare() {
        shadowOf(context).grantPermissions(Manifest.permission.RECEIVE_SMS)
        val queued = LinkedBlockingQueue<Runnable>()
        WorkManager.initialize(context, Configuration.Builder().setExecutor(Executor { queued.add(it) })
            .setTaskExecutor(Executor { queued.add(it) }).build())
        manager = WorkManager.getInstance(context) as WorkManagerImpl
    }
    @After fun close() { manager.workDatabase.close(); WorkManagerImpl.setDelegate(null) }

    @Test fun scopedHubRelaysDriverHmacThroughEncryptedOutboxToAuthoritativeTlsGateway() = runBlocking {
        OwnedHubTlsServer().use { owned ->
            val count = AtomicInteger()
            val shared = "synthetic-server-only-global-secret"
            val phone = "09120000001"
            val scopedToken = "HUB1_" + "a".repeat(16) + "_" + "b".repeat(64)
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            owned.server.createContext("/api/v1/otp/sms-gateway") { exchange ->
                val rawBody = exchange.requestBody.readBytes()
                val json = JSONObject(rawBody.toString(Charsets.UTF_8))
                require(exchange.requestHeaders.getFirst("X-OTP-Webhook-Token") == scopedToken)
                val hash = MessageDigest.getInstance("SHA-256").digest(rawBody).joinToString("") { "%02x".format(it) }
                val device = exchange.requestHeaders.getFirst("X-OTP-Hub-Device")
                val timestamp = exchange.requestHeaders.getFirst("X-OTP-Hub-Timestamp")
                val nonce = exchange.requestHeaders.getFirst("X-OTP-Hub-Nonce")
                val proof = "barpro-hub-request-v1\n$device\nPOST\n/api/v1/otp/sms-gateway\n$timestamp\n$nonce\n$hash"
                require(java.security.Signature.getInstance("SHA256withRSA").run {
                    initVerify(pair.public); update(proof.toByteArray(Charsets.UTF_8))
                    verify(android.util.Base64.decode(exchange.requestHeaders.getFirst("X-OTP-Hub-Signature"), android.util.Base64.NO_WRAP))
                })
                val text = json.getString("text")
                val derived = Mac.getInstance("HmacSHA256").run {
                    init(SecretKeySpec(shared.toByteArray(), "HmacSHA256")); doFinal("barpro-driver-v1:$phone".toByteArray())
                }.joinToString("") { "%02x".format(it) }
                val expected = Mac.getInstance("HmacSHA256").run {
                    init(SecretKeySpec(derived.toByteArray(), "HmacSHA256")); doFinal(text.substringBeforeLast('#').toByteArray())
                }.take(16).joinToString("") { "%02x".format(it) }
                val accepted = MessageDigest.isEqual(expected.toByteArray(), text.substringAfterLast('#').toByteArray())
                count.incrementAndGet()
                val body = if (accepted) "{\"success\":true,\"status\":\"success\",\"otp_detected\":true}" else "{}"
                exchange.sendResponseHeaders(if (accepted) 200 else 403, body.length.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
            val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            try {
                val repository = SmsForwardRepository(db, SmsForwarderClient(owned.client, DeviceBootstrapKey { pair }), context)
                repository.saveConfig(ForwardConfig(endpointUrl = owned.root + "/api/v1/otp/sms-forwarder",
                    authHeaderValue = scopedToken, forwarderSecret = scopedToken,
                    isMasterEnabled = true, userConsentGiven = true, setupCompleted = true, maxRetries = 0))
                val driverSecret = SmsFallbackEnvelope.deriveDriverKey(shared, phone)
                val envelope = SmsFallbackEnvelope.encode(phone, System.currentTimeMillis().coerceAtLeast(1), "54321", driverSecret)
                val success = repository.processIncomingSms(phone, envelope)
                assertEquals("A device-scoped Hub does not possess the Driver HMAC key; the server verifies it", ForwardStatus.SUCCESS, success.status)
                assertEquals(200, success.httpStatusCode)
                assertEquals(phone, success.recipientPhone)
                assertTrue(success.encryptedBody!!.startsWith("v2:"))
                val forged = envelope.substringBeforeLast('#') + "#" + "0".repeat(32)
                val rejection = repository.processIncomingSms(phone, forged)
                assertEquals(ForwardStatus.SKIPPED, rejection.status)
                assertEquals("The authoritative rejection must come from the server", 403, rejection.httpStatusCode)
                assertEquals(2, count.get())
            } finally { db.close() }
        }
    }
}
