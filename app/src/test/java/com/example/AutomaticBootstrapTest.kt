package com.example

import android.Manifest
import android.app.Application
import android.telephony.SmsManager
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.example.enrollment.*
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSubscriptionManager
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AutomaticBootstrapTest {
    @get:Rule val secureStorage = SecureStorageTestRule()
    private val nonce = "n".repeat(43)
    private val device = "automatic-device"
    private val protocol = "barpro-bootstrap-v2"
    private val server = "https://fleet.example.test/".toHttpUrl()
    private val credentials = JSONObject().put("protocol", "barpro-driver-v1").put("driver_phone", "09120000001")
        .put("endpoint_url", "https://fleet.example.test/api/v1/otp/sms-forwarder/09120000001")
        .put("webhook_token", "a".repeat(64)).put("hub_phone_mci", "09120000002").put("hub_phone_irancell", "09350000003")

    @Test fun anonymousChallengeDoesNotClaimADriverPhone() = runBlocking {
        var body: JSONObject? = null
        val client = BootstrapClient(server, OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer(); chain.request().body!!.writeTo(buffer); body = JSONObject(buffer.readUtf8())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("owned")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build())
        client.challenge("", device, "synthetic-public-key")
        assertFalse("Identity must come from carrier proof, not an empty/guessed phone claim", body!!.has("driver_phone"))
        assertEquals(device, body!!.getString("device_id"))
    }

    @Test fun deviceGrantDecryptsWithVersionTwoBindingAndRejectsVersionSubstitution() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val key = DeviceBootstrapKey { pair }
        val grant = grant(pair)
        assertEquals(credentials.toString(), key.decrypt(grant, nonce, device))
        grant.put("protocol", "barpro-bootstrap-v1")
        assertTrue("A protocol substitution must fail AEAD validation", runCatching { key.decrypt(grant, nonce, device) }.isFailure)
    }

    @Test fun actualBackendVersionTwoCryptoFixtureDecryptsThroughTheProductionClientKey() {
        val fixture = JSONObject(javaClass.classLoader!!.getResource("bootstrap-server-grant-v2.json")!!.readText())
        val factory = KeyFactory.getInstance("RSA")
        val pair = KeyPair(factory.generatePublic(X509EncodedKeySpec(Base64.decode(fixture.getString("public_key"), 0))),
            factory.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(fixture.getString("private_key"), 0))))
        val document = DeviceBootstrapKey { pair }.decrypt(fixture.getJSONObject("grant"),
            fixture.getString("nonce"), fixture.getString("device_id"))
        assertEquals(fixture.getJSONObject("credentials").toString(), JSONObject(document).toString())
    }

    @Test fun automaticCoordinatorUsesCarrierReturnedIdentityAndVersionTwoPollSignature() = runBlocking {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        var pending: String? = JSONObject().put("phone", "09120000009").put("device_id", device)
            .put("nonce", "o".repeat(43)).put("expires_at", System.currentTimeMillis() + 900_000)
            .put("protocol", "barpro-bootstrap-v1").toString()
        var sends = 0
        val coordinator = BootstrapCoordinator(server, DeviceBootstrapKey { pair },
            challenge = { phone, id, _ ->
                assertEquals("", phone); assertEquals(device, id)
                JSONObject().put("protocol", protocol).put("nonce", nonce).put("expires_in", 900)
                    .put("hub_phone_mci", "09120000002").put("hub_phone_irancell", "09350000003")
            }, poll = { actualNonce, id, signed ->
                assertEquals(nonce, actualNonce); assertEquals(device, id)
                assertTrue(Signature.getInstance("SHA256withRSA").apply {
                    initVerify(pair.public); update("$protocol\n$nonce\n$device".toByteArray())
                }.verify(Base64.decode(signed, Base64.NO_WRAP)))
                grant(pair)
            }, stage = { pending = it }, loadStage = { pending }, permissionsGranted = { true },
            sendSms = { _, n -> assertEquals(nonce, n); sends++; true })
        val result = coordinator.start("", device)
        assertEquals("09120000001", result.driverPhone)
        assertEquals(1, sends)
        assertEquals(protocol, JSONObject(pending!!).getString("protocol"))
    }

    @Test fun unexpectedModemExceptionCannotPermanentlyMarkTheNonceSent() = runBlocking {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        var pending: String? = null
        var sends = 0
        var challenges = 0
        fun coordinator() = BootstrapCoordinator(server, DeviceBootstrapKey { pair },
            challenge = { _, _, _ -> challenges++; JSONObject().put("protocol", "barpro-bootstrap-v1")
                .put("nonce", nonce).put("expires_in", 900).put("hub_phone_mci", "09120000002") },
            poll = { _, _, _ -> throw java.io.IOException("owned network loss") },
            stage = { pending = it }, loadStage = { pending }, permissionsGranted = { true },
            sendSms = { _, _ -> sends++; throw IllegalStateException("owned modem exception") })
        repeat(2) { assertTrue(runCatching { coordinator().start("09120000001", device) }.isFailure) }
        assertEquals("The same nonce must remain retryable after synchronous dispatch failure", 2, sends)
        assertEquals(1, challenges)
    }

    @Test @Config(sdk = [28, 34]) fun bootstrapModemAttemptHasResultAndDeliveryCapabilities() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.SEND_SMS)
        ShadowSubscriptionManager.setDefaultSmsSubscriptionId(17)
        assertTrue(BootstrapSms.send(context, "09120000002", nonce))
        val modem = if (android.os.Build.VERSION.SDK_INT >= 31)
            context.getSystemService(SmsManager::class.java).createForSubscriptionId(17)
        else { @Suppress("DEPRECATION") SmsManager.getSmsManagerForSubscriptionId(17) }
        val packet = shadowOf(modem).lastSentTextMessageParams
        assertNotNull("Activation must hear asynchronous modem failures", packet.sentIntent)
        assertNotNull("Activation must interpret actual carrier delivery reports", packet.deliveryIntent)
        assertNotEquals(packet.sentIntent, packet.deliveryIntent)
    }

    private fun grant(pair: KeyPair): JSONObject {
        val aes = ByteArray(32) { (it + 1).toByte() }; val iv = ByteArray(12) { (it + 3).toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, iv))
            updateAAD("$protocol\n$nonce\n$device".toByteArray())
        }.doFinal(credentials.toString().toByteArray())
        val wrapped = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").apply {
            init(Cipher.ENCRYPT_MODE, pair.public, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT))
        }.doFinal(aes)
        fun encoded(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return JSONObject().put("protocol", protocol).put("status", "granted").put("nonce", nonce).put("device_id", device)
            .put("wrapped_key", encoded(wrapped)).put("iv", encoded(iv)).put("ciphertext", encoded(cipher))
    }
}
