package com.example

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.SecureStorageManager
import com.example.data.local.AppDatabase
import com.example.data.model.ForwardConfig
import com.example.data.repository.SmsForwardRepository
import com.example.enrollment.*
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.Proxy
import java.net.InetSocketAddress
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.util.UUID
import okhttp3.OkHttpClient
import org.json.JSONObject

/** Optional owned Python fixture: python -m tests.bootstrap_loopback_server /tmp/ready.json. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BootstrapLoopbackTest {
    @get:org.junit.Rule val secureStorage = SecureStorageTestRule()

    @Test fun actualHttpAndSyntheticCarrierActivateOnlyEncryptedPhoneScopedRoomConfiguration() = runBlocking {
        val url = System.getenv("BARPRO_BOOTSTRAP_TEST_URL")
        assumeTrue("Start the owned backend interop fixture for real HTTP verification", !url.isNullOrBlank())
        require(url!!.startsWith("http://127.0.0.1:"))
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repository = SmsForwardRepository(db, appContext = context)
        val storage = SecureStorageManager.getInstance(context)
        val owned = url.toHttpUrl()
        val selected = "http://87.107.160.251/".toHttpUrl()
        val transport = OkHttpClient.Builder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", owned.port))).build()
        val hubPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val hubKey = DeviceBootstrapKey { hubPair }
        val client = BootstrapClient(selected, transport, hubKey)
        val capability = JSONObject(requireNotNull(System.getenv("BARPRO_BOOTSTRAP_TEST_PROFILES")))
            .getJSONObject(BuildConfig.APP_ROLE).getString("capability")
        val hubGrant = client.enrollHub(capability, hubKey.hubDeviceId(), hubKey)
        val hubToken = JSONObject(hubKey.decrypt(hubGrant, capability, hubKey.hubDeviceId())).getString("webhook_token")
        assertTrue(hubToken.startsWith("HUB1_"))
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val deviceId = UUID.randomUUID().toString()
        var sends = 0
        var health = 0
        val coordinator = BootstrapCoordinator(selected, DeviceBootstrapKey { pair },
            challenge = client::challenge, poll = client::poll,
            stage = storage::savePendingBootstrap, loadStage = storage::getPendingBootstrap,
            permissionsGranted = { true }, sendSms = { _, nonce ->
                sends++
                // Carrier hop is synthetic. Hub must use received origin, never a claimed body phone.
                val received = BootstrapSms.parse("+989120000001", BootstrapSms.message(nonce))!!
                runBlocking {
                    val rejected = runCatching { client.prove(received.first, received.second, "synthetic-hub-secret-at-least-32-bytes") }.exceptionOrNull()
                    assertTrue("Shared root cannot authenticate native carrier proof", rejected is BootstrapHttpException && rejected.code == 401)
                    client.prove(received.first, received.second, hubToken)
                }
                true
            })
        val manager = EnrollmentManager("http://87.107.160.251/api/v1/otp/sms-forwarder", "DRIVER", { deviceId },
            redeem = { _, _ -> error("No link required") }, getConfig = repository::getConfig, saveConfig = repository::saveConfig,
            stage = storage::savePendingEnrollment, loadStage = storage::getPendingEnrollment,
            permissionsGranted = { true }, onActivated = { health++ },
            resolve = { _, _, _ -> error("Missing secret must bootstrap directly") }, bootstrap = coordinator::start)
        try {
            repository.saveConfig(ForwardConfig())
            manager.resolveDriverConfiguration("09120000001")
            assertEquals(manager.errorMessage.value, EnrollmentStatus.ACTIVE, manager.status.value)
            val active = repository.getConfig()
            assertEquals("09120000001", active.driverPhone)
            assertEquals("http://87.107.160.251/api/v1/otp/sms-forwarder/09120000001", active.endpointUrl)
            assertTrue(active.forwarderSecret.matches(Regex("[0-9a-f]{64}")))
            assertNotEquals("synthetic-hub-secret-at-least-32-bytes", active.forwarderSecret)
            assertEquals("", db.forwardConfigDao().getConfig()!!.forwarderSecret)
            assertEquals("", db.forwardConfigDao().getConfig()!!.authHeaderValue)
            assertEquals(1, sends)
            assertEquals(1, health)
            assertNull(storage.getPendingEnrollment())
        } finally { db.close() }
    }
}
