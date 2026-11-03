package com.example

import android.app.Application
import com.example.data.model.ForwardConfig
import com.example.network.SmsForwarderClient
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HubScopedRequestTest {
    @Test fun scopedCredentialsNeverFollowACrossOriginRedirect() = runBlocking {
        OwnedHubTlsServer().use { origin -> OwnedHubTlsServer().use { other ->
            val leaks = java.util.concurrent.atomic.AtomicInteger()
            other.server.createContext("/api/v1/otp/sms-forwarder") { exchange ->
                leaks.incrementAndGet(); exchange.sendResponseHeaders(200, 2); exchange.responseBody.use { it.write("{}".toByteArray()) }
            }
            origin.server.createContext("/api/v1/otp/sms-forwarder") { exchange ->
                exchange.requestBody.readBytes()
                exchange.responseHeaders.add("Location", other.root + "/api/v1/otp/sms-forwarder")
                exchange.sendResponseHeaders(302, -1); exchange.close()
            }
            val pair = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val token = "HUB1_" + "a".repeat(16) + "_" + "b".repeat(64)
            val result = SmsForwarderClient(origin.client, com.example.enrollment.DeviceBootstrapKey { pair }).checkHealth(
                ForwardConfig(endpointUrl = origin.root + "/api/v1/otp/sms-forwarder", authHeaderValue = token))
            assertFalse(result.isSuccess)
            assertEquals(302, result.httpStatusCode)
            assertEquals("A credential must not reach the redirect origin", 0, leaks.get())
        } }
    }

    @Test fun hubEnrollmentDoesNotTrustAnUnrecognizedTlsCertificate() = runBlocking {
        OwnedHubTlsServer().use { owned ->
            val requests = java.util.concurrent.atomic.AtomicInteger()
            owned.server.createContext("/api/v1/otp/hub-enroll") { exchange -> requests.incrementAndGet(); exchange.close() }
            val pair = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            try {
                com.example.enrollment.BootstrapClient(owned.root.toHttpUrl())
                    .enrollHub("A".repeat(43), "hub_" + "a".repeat(32), com.example.enrollment.DeviceBootstrapKey { pair })
                fail("An unknown owned test CA must be rejected by the production client")
            } catch (_: javax.net.ssl.SSLHandshakeException) { }
            assertEquals(0, requests.get())
        }
    }

    @Test fun aScopedHubCredentialHasFreshDeviceProofForEveryActualTlsRetry() = runBlocking {
        OwnedHubTlsServer().use { owned ->
            owned.server.createContext("/test") { exchange ->
                exchange.sendResponseHeaders(200, 2); exchange.responseBody.use { it.write("ok".toByteArray()) }
            }
            owned.client.newCall(okhttp3.Request.Builder().url(owned.root + "/test").build()).execute().use {
                assertEquals("The owned TLS fixture must complete certificate validation", 200, it.code)
            }
            val attempts = CopyOnWriteArrayList<Map<String, String>>()
            val bodies = CopyOnWriteArrayList<ByteArray>()
            owned.server.createContext("/api/v1/otp/sms-forwarder") { exchange ->
                bodies.add(exchange.requestBody.readBytes())
                attempts.add(exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.single() })
                val body = if (attempts.size == 1) "{}" else "{\"success\":true,\"status\":\"ready\"}"
                if (attempts.size == 1) exchange.responseHeaders.add("Retry-After", "0")
                exchange.sendResponseHeaders(if (attempts.size == 1) 503 else 200, body.length.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
            val token = "HUB1_" + "a".repeat(16) + "_" + "b".repeat(64)
            val config = ForwardConfig(endpointUrl = owned.root + "/api/v1/otp/sms-forwarder",
                authHeaderValue = token, forwarderSecret = token, maxRetries = 1)
            val pair = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val client = SmsForwarderClient(owned.client, com.example.enrollment.DeviceBootstrapKey { pair })
            val second = client.checkHealth(config)
            assertTrue("Owned HTTP acknowledgement must succeed: $second; requests=${attempts.size}", second.isSuccess)
            assertEquals(2, attempts.size)
            val nonces = attempts.map { it["x-otp-hub-nonce"] }
            assertTrue("Every scoped Hub HTTP attempt needs a bound device proof", attempts.all {
                it["x-otp-hub-device"]?.matches(Regex("hub_[0-9a-f]{32}")) == true &&
                it["x-otp-hub-signature"].orEmpty().isNotBlank()
            })
            assertEquals("A nonce cannot be reused after OkHttp's automatic follow-up", 2, nonces.filterNotNull().toSet().size)
            attempts.forEachIndexed { index, headers ->
                val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bodies[index]).joinToString("") { "%02x".format(it) }
                val message = "barpro-hub-request-v1\n${headers["x-otp-hub-device"]}\nPOST\n/api/v1/otp/sms-forwarder\n" +
                    "${headers["x-otp-hub-timestamp"]}\n${headers["x-otp-hub-nonce"]}\n$hash"
                assertTrue("The RSA proof must bind the exact body, method, path, timestamp and nonce", java.security.Signature.getInstance("SHA256withRSA").run {
                    initVerify(pair.public); update(message.toByteArray(Charsets.UTF_8))
                    verify(android.util.Base64.decode(headers["x-otp-hub-signature"], android.util.Base64.NO_WRAP))
                })
            }
        }
    }
}
