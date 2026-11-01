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
            sendSms = { number, nonce -> assertEquals("09120000002", number); assertEquals("n".repeat(43), nonce); sends++; true },
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
            sendSms = { _, _ -> error("No SMS without permission") })
        try { coordinator.start("09120000001", "synthetic-device"); fail("Permissions required") }
        catch (_: BootstrapNeedsPermissions) { }
        assertEquals("09120000001", JSONObject(staged!!).getString("phone"))
    }
}
