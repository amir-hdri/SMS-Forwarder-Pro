package com.example

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.model.AuthType
import com.example.data.model.ForwardConfig
import com.example.data.repository.SmsForwardRepository
import com.example.enrollment.BootstrapClient
import com.example.network.BarProContract
import com.example.network.SmsForwarderClient
import com.example.update.ConfigFetch
import com.example.update.RemoteConfigClient
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HubCredentialConfigurationTest {
    @get:Rule val secureStorage = SecureStorageTestRule()
    private val endpoint = "https://fleet.example.test/api/v1/otp/sms-forwarder"
    private val oldToken = "synthetic-existing-token-at-least-32-bytes"
    private val newToken = "synthetic-rotated-token-at-least-32-bytes"

    private fun capture(requests: MutableList<Request>): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            requests.add(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("owned fixture")
                .body("""{"success":true,"status":"ready"}""".toResponseBody("application/json".toMediaType())).build()
        }.build()

    @Test fun urlPastedAsTokenStopsHealthBeforeAnyRequest() = runBlocking {
        for (url in listOf("$endpoint/09120000001", "http://fleet.example.test/api/v1/otp/sms-forwarder")) {
            val requests = mutableListOf<Request>()
            val config = ForwardConfig(endpointUrl = endpoint, authHeaderValue = url, forwarderSecret = url)
            val result = SmsForwarderClient(capture(requests)).checkHealth(config)
            assertFalse("A webhook URL cannot authenticate the Hub", result.isSuccess)
            assertTrue("Invalid credentials must stop before transport", requests.isEmpty())
            assertTrue(result.errorMessage.orEmpty().contains("آدرس"))
            assertTrue(result.errorMessage.orEmpty().contains("کلید"))
        }
    }

    @Test fun urlPastedAsTokenCannotBeSentAsBootstrapProof() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = BootstrapClient("https://fleet.example.test/".toHttpUrl(), capture(requests))
        val failure = runCatching { client.prove("09120000001", "a".repeat(43), endpoint) }.exceptionOrNull()
        assertNotNull("Bootstrap must reject the URL locally", failure)
        assertTrue("No carrier proof request may carry a URL as its token", requests.isEmpty())
        assertTrue(failure?.message.orEmpty().contains("آدرس"))
    }

    @Test fun signedRotationChangesThePersistedHealthRequestTokenWithoutChangingAuthSemantics() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repository = SmsForwardRepository(db, appContext = context)
            for (auth in listOf(AuthType.CUSTOM_HEADER, AuthType.BEARER_TOKEN)) {
                val existing = ForwardConfig(endpointUrl = endpoint, authType = auth,
                    authHeaderKey = if (auth == AuthType.CUSTOM_HEADER) BarProContract.TOKEN_HEADER else "retained-header",
                    authHeaderValue = oldToken, forwarderSecret = oldToken)
                repository.saveConfig(existing)
                val applied = signedSnapshot(context.packageName, JSONObject().put("webhookToken", newToken))
                repository.saveConfig(applied.snapshot.applyTo(repository.getConfig()))
                val restored = repository.getConfig()
                val requests = mutableListOf<Request>()
                assertTrue(SmsForwarderClient(capture(requests)).checkHealth(restored).isSuccess)
                assertEquals(newToken, requests.single().header(BarProContract.TOKEN_HEADER))
                assertEquals(existing.authType, restored.authType)
                assertEquals(existing.authHeaderKey, restored.authHeaderKey)
                assertEquals(endpoint, requests.single().url.toString())
                assertEquals("", db.forwardConfigDao().getConfig()!!.authHeaderValue)
                assertEquals("", db.forwardConfigDao().getConfig()!!.forwarderSecret)
            }
        } finally { db.close() }
    }

    @Test fun signedOverlayWithoutTokenKeepsTheCurrentCredential() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val existing = ForwardConfig(endpointUrl = endpoint, authHeaderValue = oldToken, forwarderSecret = oldToken)
        val applied = signedSnapshot(context.packageName, JSONObject().put("timeoutSeconds", 20))
        val updated = applied.snapshot.applyTo(existing)
        val requests = mutableListOf<Request>()
        assertTrue(SmsForwarderClient(capture(requests)).checkHealth(updated).isSuccess)
        assertEquals(oldToken, requests.single().header(BarProContract.TOKEN_HEADER))
        assertEquals(20, updated.timeoutSeconds)
    }

    @Test fun opaquePrintableCredentialsAndDisabledSignedChannelsKeepTheirExistingContract() = runBlocking {
        val requests = mutableListOf<Request>()
        val opaque = "synthetic+/=:_opaque-token-at-least-32-bytes"
        assertTrue(SmsForwarderClient(capture(requests)).checkHealth(
            ForwardConfig(endpointUrl = endpoint, authHeaderValue = opaque)).isSuccess)
        assertEquals(opaque, requests.single().header(BarProContract.TOKEN_HEADER))
        val fetch = RemoteConfigClient("", capture(requests)).fetch("https://fleet.example.test/config", "owned", 0)
        assertTrue(fetch is ConfigFetch.Failed && fetch.isSecurityRejection)
        assertEquals("A missing trust key must not attempt a config fetch", 1, requests.size)
    }

    private fun signedSnapshot(audience: String, fields: JSONObject): ConfigFetch.Applied {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        fields.put("audience", audience).put("version", 1).put("issuedAt", System.currentTimeMillis() / 1000)
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(fields.toString().toByteArray())
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private); update(payload.toByteArray(Charsets.US_ASCII)); sign()
        }
        val document = payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
        val result = RemoteConfigClient(Base64.getEncoder().encodeToString(pair.public.encoded)).verify(document, audience, 0)
        assertTrue("The owned settings document must verify", result is ConfigFetch.Applied)
        return result as ConfigFetch.Applied
    }
}
