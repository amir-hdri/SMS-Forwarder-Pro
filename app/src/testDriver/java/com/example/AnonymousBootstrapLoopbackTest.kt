package com.example

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.SecureStorageManager
import com.example.data.local.AppDatabase
import com.example.data.repository.SmsForwardRepository
import com.example.enrollment.*
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.util.UUID

/** Real owned TCP/SQLite/Redis/crypto; the carrier hop is explicitly synthetic, never a live SIM proof. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AnonymousBootstrapLoopbackTest {
    @get:Rule val secureStorage = SecureStorageTestRule()

    @Test fun anonymousRealTcpProtocolCommitsOnlyTheEncryptedCarrierScopedGrant() = runBlocking {
        val url = System.getenv("BARPRO_BOOTSTRAP_TEST_URL")
        assumeTrue("Start the owned backend interop fixture", !url.isNullOrBlank())
        require(url!!.startsWith("http://127.0.0.1:"))
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repository = SmsForwardRepository(db, appContext = context)
        val storage = SecureStorageManager.getInstance(context)
        var anonymousRequests = 0
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.encodedPath.endsWith("challenge")) {
                val buffer = Buffer(); chain.request().body!!.writeTo(buffer)
                assertFalse(JSONObject(buffer.readUtf8()).has("driver_phone")); anonymousRequests++
            }
            chain.proceed(chain.request())
        }.build()
        val client = BootstrapClient(url.toHttpUrl(), transport)
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        var sends = 0
        val coordinator = BootstrapCoordinator("https://fleet.example.test/".toHttpUrl(), DeviceBootstrapKey { pair },
            challenge = client::challenge, poll = client::poll,
            stage = storage::savePendingBootstrap, loadStage = storage::getPendingBootstrap,
            permissionsGranted = { true }, sendSms = { _, nonce ->
                sends++
                // This adapter models carrier-origin intake in the owned fixture, not a physical SMS.
                val proof = BootstrapSms.parse("+989120000001", BootstrapSms.message(nonce))!!
                runBlocking { client.prove(proof.first, proof.second, "synthetic-hub-secret-at-least-32-bytes") }
                true
            })
        val device = UUID.randomUUID().toString()
        val manager = EnrollmentManager("https://fleet.example.test/api/v1/otp/sms-forwarder", "DRIVER", { device },
            redeem = { _, _ -> error("No links") }, getConfig = repository::getConfig, saveConfig = repository::saveConfig,
            stage = storage::savePendingEnrollment, loadStage = storage::getPendingEnrollment,
            permissionsGranted = { true }, onActivated = {}, bootstrap = coordinator::start)
        try {
            manager.resolveDriverConfiguration("")
            assertEquals(manager.errorMessage.value, EnrollmentStatus.ACTIVE, manager.status.value)
            val installed = repository.getConfig()
            assertEquals("09120000001", installed.driverPhone)
            assertEquals(device, installed.deviceIdentifier)
            assertEquals("https://fleet.example.test/api/v1/otp/sms-forwarder/09120000001", installed.endpointUrl)
            assertTrue(installed.forwarderSecret.matches(Regex("[0-9a-f]{64}")))
            assertNotEquals("synthetic-hub-secret-at-least-32-bytes", installed.forwarderSecret)
            assertEquals("", db.forwardConfigDao().getConfig()!!.authHeaderValue)
            assertEquals(1, sends)
            assertEquals(1, anonymousRequests)
        } finally { db.close() }
    }
}
