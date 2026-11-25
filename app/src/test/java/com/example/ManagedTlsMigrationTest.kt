package com.example

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.model.ForwardConfig
import com.example.data.repository.SmsForwardRepository
import com.example.enrollment.EnrollmentContract
import com.example.enrollment.BootstrapClient
import com.example.enrollment.DeviceBootstrapKey
import com.example.enrollment.HubInstallContract
import com.example.enrollment.HubInstallManager
import com.example.enrollment.EnrollmentStatus
import com.example.network.BarProContract
import com.example.network.HubRequestAuth
import com.example.network.ManagedTransport
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import java.net.URLEncoder
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ManagedTlsMigrationTest {
    @get:org.junit.Rule val secureStorage = SecureStorageTestRule()
    @Test fun existingManagedEnrollmentKeepsSelectedHttpWithoutChangingItsScopedIdentity() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = SmsForwardRepository(db, appContext = context)
            repo.saveConfig(ForwardConfig(driverPhone = "09120000001", authHeaderValue = "a".repeat(64),
                endpointUrl = "http://87.107.160.251/api/v1/otp/sms-forwarder/09120000001", allowCleartextTransport = true))
            val restored = repo.getConfig()
            assertEquals("http://87.107.160.251/api/v1/otp/sms-forwarder/09120000001", restored.endpointUrl)
            assertEquals("09120000001", restored.driverPhone)
            assertEquals("a".repeat(64), restored.authHeaderValue)
            assertTrue(restored.allowCleartextTransport)
        } finally { db.close() }
    }

    @Test fun previouslyIssuedHttpActivationLinksUseTheSelectedHttpServer() {
        val server = URLEncoder.encode("http://87.107.160.251/", "UTF-8")
        val link = "barpro-forwarder://enroll#ticket=${"x".repeat(32)}&server=$server"
        val parsed = try {
            EnrollmentContract.parseLink(link, "https://87.107.160.251/api/v1/otp/sms-forwarder")
        } catch (error: IllegalArgumentException) {
            fail("The same server's issued activation link must survive the selected transport migration")
            throw error
        }
        assertEquals("http", parsed.server.scheme)
        assertEquals(80, parsed.server.port)
    }

    @Test fun legacyCredentialDocumentsKeepTheirDriverPathAndKeyOnTheSelectedOrigin() {
        val document = JSONObject().put("protocol", "barpro-driver-v1").put("driver_phone", "09120000001")
            .put("webhook_token", "a".repeat(64)).put("hub_phone_mci", "09120000002")
            .put("endpoint_url", "http://87.107.160.251/api/v1/otp/sms-forwarder/09120000001").toString()
        val credentials = EnrollmentContract.parseCredentials(document, "http://87.107.160.251/".toHttpUrl())
        assertEquals("http://87.107.160.251/api/v1/otp/sms-forwarder/09120000001", credentials.endpointUrl)
        assertEquals("a".repeat(64), credentials.webhookToken)
    }

    @Test fun anOriginLookalikeCannotInheritTheManagedServerTrust() {
        val server = URLEncoder.encode("http://87.107.160.251.evil.test/", "UTF-8")
        val link = "barpro-forwarder://enroll#ticket=${"x".repeat(32)}&server=$server"
        assertThrows(IllegalArgumentException::class.java) {
            EnrollmentContract.parseLink(link, "https://87.107.160.251/api/v1/otp/sms-forwarder")
        }
    }

    @Test fun historicalHttpsProfileMigratesWithoutRestoringStoppedConsentOrReplacingIdentity() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = SmsForwardRepository(db, appContext = context)
            val original = ForwardConfig(driverPhone = "09120000001", authHeaderValue = "a".repeat(64),
                forwarderSecret = "a".repeat(64), deviceIdentifier = "retained-device", setupCompleted = true,
                isMasterEnabled = false, userConsentGiven = false, allowCleartextTransport = false,
                configManifestUrl = "https://existing.example.test/config", updateManifestUrl = "https://existing.example.test/update",
                endpointUrl = "https://87.107.160.251/api/v1/otp/sms-forwarder/09120000001")
            repo.saveConfig(original)
            val migrated = repo.getConfig()
            assertEquals(original.copy(endpointUrl = "http://87.107.160.251/api/v1/otp/sms-forwarder/09120000001"), migrated)
        } finally { db.close() }
    }

    @Test fun historicalPrivateProfileAndGrantKeepTheirCapabilityAndDeviceOnSelectedHttp() {
        val root = "http://87.107.160.251/".toHttpUrl()
        val profile = JSONObject().put("protocol", "barpro-hub-install-v1")
            .put("server", "https://87.107.160.251/").put("capability", "A".repeat(43)).put("expires_at", 1100)
        val parsed = HubInstallContract.parseProfile(profile.toString(), "HUB", root, 1000)
        assertEquals("http://87.107.160.251/", parsed.server.toString())
        assertEquals("A".repeat(43), parsed.capability)
        val id = "hub_" + "a".repeat(32)
        val token = "HUB1_" + "b".repeat(16) + "_" + "c".repeat(64)
        val credentials = JSONObject().put("protocol", "barpro-hub-v1").put("device_id", id)
            .put("endpoint_url", "https://87.107.160.251/api/v1/otp/sms-forwarder")
            .put("webhook_token", token).put("hub_phone_mci", "09120000001").put("hub_phone_irancell", "09350000001")
        val granted = HubInstallContract.parseCredentials(credentials.toString(), root, id)
        assertEquals("http://87.107.160.251/api/v1/otp/sms-forwarder", granted.endpoint)
        assertEquals(id, granted.deviceId)
        assertEquals(token, granted.token)
    }

    @Test fun selectedHttpPolicyDoesNotGrantForeignOrAlternatePortAuthority() {
        for (origin in listOf("http://87.107.160.251:81/", "http://87.107.160.251.evil.test/", "http://foreign.example.test/", "http://localhost/")) {
            val profile = JSONObject().put("protocol", "barpro-hub-install-v1").put("server", origin)
                .put("capability", "A".repeat(43)).put("expires_at", 1100)
            assertThrows(IllegalArgumentException::class.java) {
                HubInstallContract.parseProfile(profile.toString(), "HUB", origin.toHttpUrl(), 1000)
            }
        }
        for (origin in listOf("https://foreign.example.test/", "https://87.107.160.251:8443/")) {
            assertEquals(origin, ManagedTransport.canonical(origin.toHttpUrl()).toString())
        }
        assertNull(BarProContract.configurationError(ForwardConfig(driverPhone = "09120000001",
            endpointUrl = "http://87.107.160.251/api/v1/otp/sms-forwarder/09120000001", authHeaderValue = "a".repeat(64))))
        for (cleartext in listOf(false, true)) {
            assertNotNull(BarProContract.configurationError(ForwardConfig(driverPhone = "09120000001",
                endpointUrl = "http://87.107.160.251:81/api/v1/otp/sms-forwarder/09120000001", authHeaderValue = "a".repeat(64),
                allowCleartextTransport = cleartext)))
        }
    }

    @Test fun stagedHistoricalHttpsGrantResumesWithoutRedeemingOrRestoringConsent() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val key = DeviceBootstrapKey { pair }
            val id = key.hubDeviceId()
            val token = "HUB1_" + "b".repeat(16) + "_" + "c".repeat(64)
            val cap = "A".repeat(43)
            val repository = SmsForwardRepository(db, appContext = context)
            repository.saveConfig(ForwardConfig(endpointUrl = "https://87.107.160.251/api/v1/otp/sms-forwarder",
                authHeaderValue = token, forwarderSecret = token, deviceIdentifier = id, setupCompleted = true,
                isMasterEnabled = false, userConsentGiven = false))
            var stage: String? = JSONObject().put("server", "https://87.107.160.251/").put("nonce", cap)
                .put("grant", encryptedHubGrant(pair, id, cap, token)).toString()
            var completed: String? = null
            val manager = HubInstallManager("HUB", key, repository,
                { error("A staged grant must not read the original private asset") },
                { _, _, _ -> error("A staged grant must not redeem its consumed capability") },
                { stage }, { stage = it }, { true }, { error("Stopped forwarding must not be activated") },
                { completed }, { completed = it })
            manager.activate()
            assertEquals(EnrollmentStatus.ACTIVE, manager.status.value)
            val configured = repository.getConfig()
            assertEquals("http://87.107.160.251/api/v1/otp/sms-forwarder", configured.endpointUrl)
            assertEquals(token, configured.authHeaderValue)
            assertEquals(id, configured.deviceIdentifier)
            assertFalse(configured.isMasterEnabled)
            assertFalse(configured.userConsentGiven)
            assertNull(stage)
            assertEquals(HubInstallContract.capabilityHash(cap), completed)
        } finally { db.close() }
    }

    @Test fun realHttpHubRequestsKeepFreshExactDeviceProofAndRejectOtherDestinations() {
        OwnedHttpProxy().use { owned ->
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val key = DeviceBootstrapKey { pair }
            val token = "HUB1_" + "b".repeat(16) + "_" + "c".repeat(64)
            val client = HubRequestAuth.client(owned.client, token, "https://87.107.160.251/".toHttpUrl(), key)
            val path = "/api/v1/otp/bootstrap/prove"
            val payload = "{\"origin\":\"09120000001\",\"nonce\":\"${"A".repeat(43)}\"}"
            repeat(2) {
                val request = Request.Builder().url("http://87.107.160.251$path")
                    .post(payload.toRequestBody("application/json".toMediaType())).build()
                try { client.newCall(request).execute().use { response -> assertEquals(200, response.code) } }
                catch (error: java.io.IOException) { fail("The selected HTTP origin must retain RSA proof: ${error.message}") }
            }
            assertEquals(2, owned.requests.size)
            val nonces = owned.requests.map { it.headers["x-otp-hub-nonce"] }
            assertEquals(2, nonces.filterNotNull().toSet().size)
            for (received in owned.requests) {
                assertEquals("POST", received.method)
                assertEquals(path, received.path)
                assertEquals("87.107.160.251", received.headers["host"])
                assertEquals(payload, received.body.toString(Charsets.UTF_8))
                assertEquals(token, received.headers["x-otp-webhook-token"])
                assertEquals(key.hubDeviceId(), received.headers["x-otp-hub-device"])
                val stamp = requireNotNull(received.headers["x-otp-hub-timestamp"])
                assertTrue(kotlin.math.abs(stamp.toLong() - System.currentTimeMillis() / 1000L) <= 5)
                val hash = MessageDigest.getInstance("SHA-256").digest(received.body).joinToString("") { "%02x".format(it) }
                val proof = "barpro-hub-request-v1\n${key.hubDeviceId()}\nPOST\n$path\n$stamp\n${received.headers["x-otp-hub-nonce"]}\n$hash"
                assertTrue(Signature.getInstance("SHA256withRSA").run {
                    initVerify(pair.public); update(proof.toByteArray(Charsets.UTF_8))
                    verify(Base64.decode(received.headers["x-otp-hub-signature"], Base64.NO_WRAP))
                })
            }
            for (url in listOf("http://foreign.example.test$path", "http://87.107.160.251:81$path",
                "http://87.107.160.251$path?leak=true", "http://87.107.160.251/api/v1/otp/latest")) {
                assertThrows(java.io.IOException::class.java) {
                    client.newCall(Request.Builder().url(url).post(payload.toRequestBody("application/json".toMediaType())).build()).execute().close()
                }
            }
            assertEquals("Rejected origins and paths must never receive a signed request", 2, owned.requests.size)
        }
    }

    @Test fun bootstrapUsesSelectedCentralHttpThroughAnOwnedProxy() = runBlocking {
        OwnedHttpProxy().use { owned ->
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val response = try {
                BootstrapClient("https://87.107.160.251/".toHttpUrl(), owned.client)
                    .challenge("09120000001", "retained-device", DeviceBootstrapKey { pair }.publicKey())
            } catch (error: java.io.IOException) { fail("Central bootstrap must use selected HTTP: ${error.message}"); throw error }
            assertTrue(response.getBoolean("success"))
            val sent = owned.requests.single()
            assertEquals("/api/v1/otp/bootstrap/challenge", sent.path)
            assertEquals("09120000001", JSONObject(sent.body.toString(Charsets.UTF_8)).getString("driver_phone"))
        }
    }

    private fun encryptedHubGrant(pair: KeyPair, id: String, cap: String, token: String): JSONObject {
        val document = JSONObject().put("protocol", "barpro-hub-v1").put("device_id", id)
            .put("endpoint_url", "https://87.107.160.251/api/v1/otp/sms-forwarder").put("webhook_token", token)
            .put("hub_phone_mci", "09120000001").put("hub_phone_irancell", "09350000001").toString()
        val aes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val encrypted = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, iv))
            updateAAD("barpro-bootstrap-v2\n$cap\n$id".toByteArray())
        }.doFinal(document.toByteArray())
        val wrapped = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").apply {
            init(Cipher.ENCRYPT_MODE, pair.public, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT))
        }.doFinal(aes)
        return JSONObject().put("protocol", "barpro-bootstrap-v2").put("status", "granted").put("nonce", cap).put("device_id", id)
            .put("wrapped_key", Base64.encodeToString(wrapped, Base64.NO_WRAP)).put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
    }

    private data class ReceivedHttp(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    /** Requests retain the production URL while this explicit proxy confines TCP to owned loopback. */
    private class OwnedHttpProxy : AutoCloseable {
        val requests = CopyOnWriteArrayList<ReceivedHttp>()
        private val executor = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@OwnedHttpProxy.executor
            createContext("/") { exchange ->
                val bytes = exchange.requestBody.readBytes()
                requests.add(ReceivedHttp(exchange.requestMethod, exchange.requestURI.path,
                    exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.single() }, bytes))
                val body = "{\"success\":true,\"status\":\"ready\"}".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        val client = OkHttpClient.Builder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", server.address.port)))
            .callTimeout(3, TimeUnit.SECONDS).build()
        override fun close() { server.stop(0); executor.shutdownNow() }
    }
}
