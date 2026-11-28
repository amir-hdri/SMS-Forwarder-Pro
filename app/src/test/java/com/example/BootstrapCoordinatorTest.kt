package com.example

import android.app.Application
import com.example.enrollment.*
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BootstrapCoordinatorTest {
    @Test fun processRestartPollsSameChallengeWithoutSendingSecondSms() = runBlocking {
        var staged: String? = null
        var requests = 0
        var sends = 0
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        fun coordinator() = BootstrapCoordinator("https://fleet.example.test".toHttpUrl(), DeviceBootstrapKey { pair },
            challenge = { phone, device, _ ->
                assertEquals("09120000001", phone); assertEquals("synthetic-device", device)
                requests++
                JSONObject().put("protocol", "barpro-bootstrap-v1").put("nonce", "n".repeat(43)).put("expires_in", 900)
                    .put("hub_phone_mci", "09120000002").put("hub_phone_irancell", "09350000003")
            }, poll = { _, _, _ -> throw java.io.IOException("synthetic response loss") },
            stage = { staged = it }, loadStage = { staged }, permissionsGranted = { true },
            sendSms = { number, nonce, _ -> assertEquals("09120000002", number); assertEquals("n".repeat(43), nonce); sends++; true },
            pause = {})
        repeat(2) {
            try { coordinator().start("09120000001", "synthetic-device"); fail("Network failure expected") }
            catch (_: java.io.IOException) { }
        }
        assertEquals(1, requests)
        assertEquals(1, sends)
        assertTrue(JSONObject(staged!!).getBoolean("sent"))
    }

    @Test fun permissionsGatePersistsIntentWithoutSendingOrCreatingChallenge() = runBlocking {
        var staged: String? = null
        val coordinator = BootstrapCoordinator("https://fleet.example.test".toHttpUrl(), DeviceBootstrapKey(),
            challenge = { _, _, _ -> error("Permission gate must precede HTTP") }, poll = { _, _, _ -> error("No polling") },
            stage = { staged = it }, loadStage = { staged }, permissionsGranted = { false },
            sendSms = { _, _, _ -> error("No SMS without permission") })
        try { coordinator.start("09120000001", "synthetic-device"); fail("Permissions required") }
        catch (_: BootstrapNeedsPermissions) { }
        assertEquals("09120000001", JSONObject(staged!!).getString("phone"))
    }

    @Test fun pollRecoversFromTransientEofWithoutAbortingOrResendingSms() = runBlocking {
        var staged: String? = null
        var sends = 0
        var pollCalls = 0
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val coordinator = BootstrapCoordinator("https://fleet.example.test".toHttpUrl(), DeviceBootstrapKey { pair },
            challenge = { _, _, _ ->
                JSONObject().put("protocol", "barpro-bootstrap-v1").put("nonce", "n".repeat(43)).put("expires_in", 900)
                    .put("hub_phone_mci", "09120000002").put("hub_phone_irancell", "09350000003")
            },
            poll = { _, _, _ ->
                pollCalls++
                if (pollCalls <= 2) throw java.io.EOFException("unexpected end of stream on https://fleet.example.test/...")
                JSONObject().put("status", "pending")
            },
            stage = { staged = it }, loadStage = { staged }, permissionsGranted = { true },
            sendSms = { _, _, _ -> sends++; true }, pause = {})
        try {
            coordinator.start("09120000001", "synthetic-device")
            fail("Expected BootstrapPending after 60 pending polls")
        } catch (_: BootstrapPending) { }
        assertEquals(1, sends)
        assertEquals(60, pollCalls)
    }

    @Test fun bootstrapClientPollRetriesAfterPrematureStreamEndOnRealSocket() = runBlocking {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val key = DeviceBootstrapKey { pair }
        val connections = java.util.concurrent.atomic.AtomicInteger(0)
        val headersSeen = java.util.Collections.synchronizedList(mutableListOf<String>())
        val server = java.net.ServerSocket(0).apply { soTimeout = 4000 }
        val serverThread = kotlin.concurrent.thread(isDaemon = true) {
            repeat(3) {
                runCatching {
                    server.accept().use { socket ->
                        val count = connections.incrementAndGet()
                        val input = socket.getInputStream()
                        val buf = StringBuilder()
                        while (!buf.endsWith("\r\n\r\n")) {
                            val b = input.read(); if (b < 0) return@use
                            buf.append(b.toChar())
                        }
                        val headerBlock = buf.toString()
                        headersSeen.add(headerBlock)
                        val len = Regex("(?im)^Content-Length: (\\d+)").find(headerBlock)?.groupValues?.get(1)?.toInt() ?: 0
                        repeat(len) { input.read() }
                        if (count == 2) {
                            // First poll connection drops before response headers (unexpected end of stream).
                            return@use
                        }
                        val json = if (count == 1) {
                            """{"protocol":"barpro-bootstrap-v1","nonce":"${"n".repeat(43)}","expires_in":900,"hub_phone_mci":"09120000002","hub_phone_irancell":""}"""
                        } else {
                            """{"status":"pending"}"""
                        }
                        socket.getOutputStream().apply {
                            write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${json.length}\r\nConnection: close\r\n\r\n$json").toByteArray())
                            flush()
                        }
                    }
                }
            }
        }
        try {
            val client = BootstrapClient("http://127.0.0.1:${server.localPort}/".toHttpUrl(), okhttp3.OkHttpClient(), key)
            val challenge = client.challenge("09120000001", "synthetic-device", key.publicKey())
            assertEquals("n".repeat(43), challenge.getString("nonce"))
            assertTrue(headersSeen[0].contains("Connection: close", ignoreCase = true))
            val poll = client.poll("n".repeat(43), "synthetic-device", key.signature("n".repeat(43), "synthetic-device"))
            assertEquals("pending", poll.getString("status"))
            assertEquals(3, connections.get())
        } finally {
            server.close()
            serverThread.join(1000)
        }
    }

    @Test fun expiredChallengePreservesRequestedNameAcrossRenewal() = runBlocking {
        var staged: String? = JSONObject()
            .put("phone", "09120000001")
            .put("device_id", "synthetic-device")
            .put("requested_name", "علی رضایی")
            .put("nonce", "a".repeat(43))
            .put("expires_at", 100L)
            .toString()
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val coordinator = BootstrapCoordinator("https://fleet.example.test".toHttpUrl(), DeviceBootstrapKey { pair },
            challenge = { _, _, _ ->
                JSONObject().put("protocol", "barpro-bootstrap-v1").put("nonce", "b".repeat(43)).put("expires_in", 900)
                    .put("hub_phone_mci", "09120000002").put("hub_phone_irancell", "")
            },
            poll = { _, _, _ -> JSONObject().put("status", "pending") },
            stage = { staged = it }, loadStage = { staged }, permissionsGranted = { true },
            sendSms = { _, _, _ -> true }, now = { 200L }, pause = {})
        try {
            coordinator.start("09120000001", "synthetic-device")
            fail("Expected BootstrapPending")
        } catch (_: BootstrapPending) { }
        val updated = JSONObject(staged!!)
        assertEquals("b".repeat(43), updated.getString("nonce"))
        assertEquals("علی رضایی", updated.getString("requested_name"))
    }
}
