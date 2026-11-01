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
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.util.UUID

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
        val client = BootstrapClient(url.toHttpUrl())
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val deviceId = UUID.randomUUID().toString()
        var sends = 0
        var health = 0
        val coordinator = BootstrapCoordinator("https://fleet.example.test/".toHttpUrl(), DeviceBootstrapKey { pair },
            challenge = client::challenge, poll = client::poll,
            stage = storage::savePendingBootstrap, loadStage = storage::getPendingBootstrap,
            permissionsGranted = { true }, sendSms = { _, nonce ->
                sends++
                // Carrier hop is synthetic. Hub must use received origin, never a claimed body phone.
                val received = BootstrapSms.parse("+989120000001", BootstrapSms.message(nonce))!!
                runBlocking { client.prove(received.first, received.second, "synthetic-hub-secret-at-least-32-bytes") }
                true
            })
        val manager = EnrollmentManager("https://fleet.example.test/api/v1/otp/sms-forwarder", "DRIVER", { deviceId },
            redeem = { _, _ -> error("No link required") }, getConfig = repository::getConfig, saveConfig = repository::saveConfig,
            stage = storage::savePendingEnrollment, loadStage = storage::getPendingEnrollment,
            permissionsGranted = { true }, onActivated = { health++ },
            resolve = { _, _ -> error("Missing secret must bootstrap directly") }, bootstrap = coordinator::start)
        try {
            repository.saveConfig(ForwardConfig())
            manager.resolveDriverConfiguration("09120000001")
            assertEquals(manager.errorMessage.value, EnrollmentStatus.ACTIVE, manager.status.value)
            val active = repository.getConfig()
            assertEquals("09120000001", active.driverPhone)
            assertEquals("https://fleet.example.test/api/v1/otp/sms-forwarder/09120000001", active.endpointUrl)
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
