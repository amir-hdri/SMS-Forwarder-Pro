package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import android.telephony.SmsManager
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.SecureStorageManager
import com.example.data.local.AppDatabase
import com.example.data.model.ForwardConfig
import com.example.data.model.ForwardStatus
import com.example.data.repository.SmsForwardRepository
import com.example.enrollment.*
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSubscriptionManager
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/** The grant fixture is produced by the real Python routes/Redis test, not by a Kotlin encryption oracle. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BootstrapEnrollmentIntegrationTest {
    @get:org.junit.Rule val secureStorage = SecureStorageTestRule()
    private fun fixture() = JSONObject(javaClass.classLoader!!.getResource("bootstrap-server-grant.json")!!.readText())

    @Test fun backendGrantFlowsThroughRealClientCoordinatorValidationAndRoomStaging() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val store = SecureStorageManager.getInstance(context)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repository = SmsForwardRepository(db, appContext = context)
        val previous = ForwardConfig(driverPhone = "09120000009", endpointUrl = "https://fleet.example.test/api/v1/otp/sms-forwarder/09120000009",
            authHeaderValue = "b".repeat(64), forwarderSecret = "b".repeat(64), userConsentGiven = true,
            isMasterEnabled = true, setupCompleted = true)
        repository.saveConfig(previous)
        val fixture = fixture()
        val keyFactory = KeyFactory.getInstance("RSA")
        val pair = KeyPair(keyFactory.generatePublic(X509EncodedKeySpec(Base64.decode(fixture.getString("public_key"), 0))),
            keyFactory.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(fixture.getString("private_key"), 0))))
        var sent = 0
        var health = 0
        var permitted = true
        var tampered = true
        val requests = mutableListOf<String>()
        val client = BootstrapClient("https://fleet.example.test".toHttpUrl(), OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertNull(request.header("X-OTP-Webhook-Token"))
            requests.add(request.url.encodedPath)
            val body = if (request.url.encodedPath.endsWith("challenge")) fixture.getJSONObject("challenge")
                else JSONObject(fixture.getJSONObject("grant").toString()).apply {
                    if (tampered) put("ciphertext", Base64.encodeToString(ByteArray(32), Base64.NO_WRAP))
                }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("synthetic transport")
                .body(body.toString().toResponseBody("application/json".toMediaType())).build()
        }.build())
        val coordinator = BootstrapCoordinator("https://fleet.example.test".toHttpUrl(), DeviceBootstrapKey { pair },
            challenge = client::challenge, poll = client::poll,
            stage = store::savePendingBootstrap, loadStage = store::getPendingBootstrap,
            permissionsGranted = { true }, sendSms = { _, _ -> sent++; true })
        val manager = EnrollmentManager("https://fleet.example.test/api/v1/otp/sms-forwarder", "DRIVER",
            { fixture.getString("device_id") }, redeem = { _, _ -> error("No activation link") },
            getConfig = repository::getConfig, saveConfig = repository::saveConfig,
            stage = store::savePendingEnrollment, loadStage = store::getPendingEnrollment,
            permissionsGranted = { permitted }, onActivated = { health++ },
            resolve = { _, _ -> throw ActivationRequiredException() }, bootstrap = coordinator::start)
        try {
            manager.resolveDriverConfiguration(fixture.getString("phone"))
            assertEquals(EnrollmentStatus.FAILED, manager.status.value)
            assertEquals(previous.driverPhone, repository.getConfig().driverPhone)
            assertNull(store.getPendingEnrollment())
            assertEquals(0, health)
            tampered = false
            permitted = false
            manager.resolveDriverConfiguration(fixture.getString("phone"))
            assertEquals(EnrollmentStatus.NEEDS_PERMISSIONS, manager.status.value)
            assertEquals(previous.driverPhone, repository.getConfig().driverPhone)
            permitted = true
            manager.resume()
            assertEquals(EnrollmentStatus.ACTIVE, manager.status.value)
            assertEquals("09120000001", repository.getConfig().driverPhone)
            assertEquals(fixture.getJSONObject("credentials").getString("webhook_token"), repository.getConfig().forwarderSecret)
            assertEquals(1, health)
            assertEquals(1, sent)
            assertEquals(1, requests.count { it.endsWith("challenge") })
            assertNull(store.getPendingEnrollment())
            val captured = repository.processIncomingSms("09120000001", "BPA1#" + fixture.getString("nonce"))
            assertEquals(ForwardStatus.SKIPPED, captured.status)
            assertEquals(0L, captured.id)
            assertNull(captured.otpCode)
        } finally { db.close() }
    }

    @Test @Config(sdk = [28, 34]) fun activationSmsUsesOsSelectedSubscriptionAndDistinctMessage() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.SEND_SMS)
        ShadowSubscriptionManager.setDefaultSmsSubscriptionId(17)
        val modem = if (android.os.Build.VERSION.SDK_INT >= 31)
            context.getSystemService(SmsManager::class.java).createForSubscriptionId(17)
        else {
            @Suppress("DEPRECATION")
            SmsManager.getSmsManagerForSubscriptionId(17)
        }
        shadowOf(modem).clearLastSentTextMessageParams()
        assertTrue(BootstrapSms.send(context, "09120000002", "n".repeat(43)))
        val sent = shadowOf(modem).lastSentTextMessageParams
        assertEquals("09120000002", sent.destinationAddress)
        assertEquals("BPA1#" + "n".repeat(43), sent.text)
    }
}
