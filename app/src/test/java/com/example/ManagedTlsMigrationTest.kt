package com.example

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.model.ForwardConfig
import com.example.data.repository.SmsForwardRepository
import com.example.enrollment.EnrollmentContract
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import java.net.URLEncoder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ManagedTlsMigrationTest {
    @get:org.junit.Rule val secureStorage = SecureStorageTestRule()
    @Test fun existingManagedEnrollmentUpgradesTransportWithoutChangingItsScopedIdentity() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = SmsForwardRepository(db, appContext = context)
            repo.saveConfig(ForwardConfig(driverPhone = "09120000001", authHeaderValue = "a".repeat(64),
                endpointUrl = "http://87.107.160.251/api/v1/otp/sms-forwarder/09120000001", allowCleartextTransport = true))
            val restored = repo.getConfig()
            assertEquals("https://87.107.160.251/api/v1/otp/sms-forwarder/09120000001", restored.endpointUrl)
            assertEquals("09120000001", restored.driverPhone)
            assertEquals("a".repeat(64), restored.authHeaderValue)
            assertFalse(restored.allowCleartextTransport)
        } finally { db.close() }
    }

    @Test fun previouslyIssuedHttpActivationLinksUseTheSameTrustedServerThroughHttps() {
        val server = URLEncoder.encode("http://87.107.160.251/", "UTF-8")
        val link = "barpro-forwarder://enroll#ticket=${"x".repeat(32)}&server=$server"
        val parsed = try {
            EnrollmentContract.parseLink(link, "https://87.107.160.251/api/v1/otp/sms-forwarder")
        } catch (error: IllegalArgumentException) {
            fail("The same server's issued activation link must survive the secure transport migration")
            throw error
        }
        assertEquals("https", parsed.server.scheme)
        assertEquals(443, parsed.server.port)
    }

    @Test fun legacyCredentialDocumentsKeepTheirDriverPathAndKeyOnTheSecureOrigin() {
        val document = JSONObject().put("protocol", "barpro-driver-v1").put("driver_phone", "09120000001")
            .put("webhook_token", "a".repeat(64)).put("hub_phone_mci", "09120000002")
            .put("endpoint_url", "http://87.107.160.251/api/v1/otp/sms-forwarder/09120000001").toString()
        val credentials = EnrollmentContract.parseCredentials(document, "http://87.107.160.251/".toHttpUrl())
        assertEquals("https://87.107.160.251/api/v1/otp/sms-forwarder/09120000001", credentials.endpointUrl)
        assertEquals("a".repeat(64), credentials.webhookToken)
    }

    @Test fun anOriginLookalikeCannotInheritTheManagedServerTrust() {
        val server = URLEncoder.encode("http://87.107.160.251.evil.test/", "UTF-8")
        val link = "barpro-forwarder://enroll#ticket=${"x".repeat(32)}&server=$server"
        assertThrows(IllegalArgumentException::class.java) {
            EnrollmentContract.parseLink(link, "https://87.107.160.251/api/v1/otp/sms-forwarder")
        }
    }
}
