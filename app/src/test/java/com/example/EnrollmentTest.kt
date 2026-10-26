package com.example

import android.app.Application
import android.content.Intent
import android.net.Uri
import com.example.data.model.ForwardConfig
import com.example.enrollment.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URLEncoder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EnrollmentTest {
    private val endpoint = "http://87.107.160.251/api/v1/otp/sms-forwarder"
    private val server = "http://87.107.160.251"
    private val ticket = "single-use-synthetic-ticket-123456"
    private val token = "a".repeat(64)
    private fun link(base: String = server, value: String = ticket): String =
        "barpro-forwarder://enroll#ticket=${URLEncoder.encode(value, "UTF-8")}&server=${URLEncoder.encode(base, "UTF-8")}"
    private fun document(phone: String = "09120000001", url: String = "$server/api/v1/otp/sms-forwarder/$phone", secret: String = token): String =
        JSONObject().put("driver_phone", phone).put("endpoint_url", url).put("webhook_token", secret)
            .put("hub_phone_mci", "09120000002").put("hub_phone_irancell", "09350000003")
            .put("protocol", "barpro-driver-v1").toString()
    private fun reject(block: () -> Unit) {
        try { block(); fail("Must reject invalid input") } catch (_: IllegalArgumentException) { }
    }

    @Test fun validFragmentPreservesOpaqueTicketAndPinsOrigin() {
        val parsed = EnrollmentContract.parseLink(link(value = "opaque-ticket-with-&=+/characters"), endpoint)
        assertEquals("opaque-ticket-with-&=+/characters", parsed.ticket)
        assertEquals("$server/", parsed.server.toString())
        assertFalse(parsed.toString().contains("opaque-ticket"))
    }

    @Test fun linksRejectUntrustedServersAndAmbiguousOrQueryCredentials() {
        for (base in listOf("https://87.107.160.251", "http://evil.test", "http://87.107.160.251.evil.test",
            "http://87.107.160.251:8080", "$server/path", "$server?secret=x", "$server#x", "http://user@87.107.160.251")) {
            reject { EnrollmentContract.parseLink(link(base), endpoint) }
        }
        for (value in listOf(link() + "&ticket=$ticket", link() + "&extra=x", link().replace("#", "?"),
            link().replace("//enroll", "//enroll/path"), link().replace("barpro-forwarder", "https"),
            link(value = "short"), link(value = "unsafe ticket with space"))) {
            reject { EnrollmentContract.parseLink(value, endpoint) }
        }
    }

    @Test fun malformedResponseCannotChangeConfigurationOrExposeGlobalSecret() {
        val trusted = EnrollmentContract.trustedServer(endpoint)
        for (value in listOf(document(secret = "legacy-shared-token"), document(secret = token.uppercase()),
            document(url = endpoint), document(url = "$server/api/v1/otp/sms-forwarder/09120000002"),
            document(url = "http://evil.test/api/v1/otp/sms-forwarder/09120000001"),
            document(url = "$server/api/v1/otp/sms-forwarder/09120000001?token=abc"),
            document(phone = "123"), document().replace("barpro-driver-v1", "unknown"))) {
            reject { EnrollmentContract.parseCredentials(value, trusted) }
        }
        val credential = EnrollmentContract.parseCredentials(document(), trusted)
        assertFalse(credential.toString().contains(token))
        val config = credential.applyTo(ForwardConfig(), "device-123")
        assertEquals("09120000001", config.driverPhone)
        assertTrue(config.isMasterEnabled && config.userConsentGiven && config.setupCompleted)
        assertTrue(config.primarySmsRelayEnabled)
        assertEquals(token, config.authHeaderValue)
    }

    @Test fun rolePermissionsFailClosedAndNotificationIsIndependent() {
        assertEquals("", ForwardConfig().authHeaderValue)
        assertEquals("", ForwardConfig().forwarderSecret)
        assertFalse(ForwardConfig().isMasterEnabled)
        for (receive in listOf(false, true)) for (send in listOf(false, true)) {
            assertEquals(receive && send, EnrollmentContract.permissionsAllowActivation("DRIVER", receive, send))
            assertEquals(receive, EnrollmentContract.permissionsAllowActivation("HUB", receive, send))
        }
    }

    @Test fun oneValidHubLegActivatesButMissingRoutesFailClosed() {
        val server = EnrollmentContract.trustedServer(endpoint)
        for (empty in listOf("hub_phone_mci", "hub_phone_irancell")) {
            val json = JSONObject(document()).put(empty, "")
            val credential = EnrollmentContract.parseCredentials(json.toString(), server)
            assertTrue(credential.hubMci.isNotEmpty() || credential.hubIrancell.isNotEmpty())
        }
        reject { EnrollmentContract.parseCredentials(JSONObject(document())
            .put("hub_phone_mci", "").put("hub_phone_irancell", "").toString(), server) }
    }

    @Test fun enrolledHubRoutesOverrideDifferingBuildDefaultsWhileLegacyKeepsThem() {
        val enrolled = EnrollmentContract.parseCredentials(document(), EnrollmentContract.trustedServer(endpoint))
            .applyTo(ForwardConfig(), "device-123")
        val defaults = com.example.utils.HubNumbers("09129999991", "09359999992")
        assertEquals(com.example.utils.HubNumbers(enrolled.fallbackServerPhoneNumber, enrolled.hubIrancellPhoneNumber),
            EnrollmentContract.hubNumbers(enrolled, defaults.mci, defaults.irancell))
        assertEquals(defaults, EnrollmentContract.hubNumbers(enrolled.copy(setupCompleted = false), defaults.mci, defaults.irancell))
        assertEquals(com.example.utils.HubNumbers("", enrolled.hubIrancellPhoneNumber),
            EnrollmentContract.hubNumbers(enrolled.copy(fallbackServerPhoneNumber = ""), defaults.mci, defaults.irancell))
    }

    @Test fun activationReplacesTooSlowLegacyHealthIntervalAndPreservesUnrelatedPreferences() {
        val previous = ForwardConfig(healthCheckIntervalMinutes = 60, timeoutSeconds = 27, maxRetries = 3)
        val activated = EnrollmentContract.parseCredentials(document(), EnrollmentContract.trustedServer(endpoint))
            .applyTo(previous, "device-123")
        assertEquals(5, activated.healthCheckIntervalMinutes)
        assertEquals(previous.timeoutSeconds, activated.timeoutSeconds)
        assertEquals(previous.maxRetries, activated.maxRetries)
    }

    @Test fun coldAndWarmViewIntentsUseOneConsumerAndRemoveBearerFragment() {
        val activated = mutableListOf<String>()
        for (name in listOf("cold", "warm")) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link(value = "$ticket-$name")))
            EnrollmentIntentRouter.consume(intent, activated::add)
            assertNull(intent.data)
            EnrollmentIntentRouter.consume(intent, activated::add)
        }
        assertEquals(2, activated.size)
        EnrollmentIntentRouter.consume(Intent(Intent.ACTION_MAIN, Uri.parse(link())), activated::add)
        EnrollmentIntentRouter.consume(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.test")), activated::add)
        assertEquals(2, activated.size)
    }

    private class Fixture(private val test: EnrollmentTest, role: String = "DRIVER") {
        var config = ForwardConfig(driverPhone = "09129999999", authHeaderValue = "previous-token", isMasterEnabled = true)
        var pending: String? = null
        var granted = false
        var writes = 0
        var redemptions = 0
        var healthDispatches = 0
        var failRedeem = false
        val manager = EnrollmentManager(test.endpoint, role, { "device-123" }, { _, _ ->
            redemptions++
            check(!failRedeem)
            EnrollmentContract.parseCredentials(test.document(), EnrollmentContract.trustedServer(test.endpoint))
        }, { config }, { config = it; writes++ }, { pending = it }, { pending }, { granted }, { healthDispatches++ })
    }

    @Test fun permissionDenialLeavesExistingConfigUntilGrantsThenSendsHealthOnce() = runBlocking {
        val fixture = Fixture(this@EnrollmentTest)
        val old = fixture.config
        fixture.manager.receive(link())
        assertEquals(EnrollmentStatus.NEEDS_PERMISSIONS, fixture.manager.status.value)
        assertEquals(old, fixture.config)
        assertEquals(0, fixture.writes)
        assertEquals(0, fixture.healthDispatches)
        fixture.manager.resume()
        assertEquals(old, fixture.config)
        fixture.granted = true
        fixture.manager.resume()
        assertEquals(EnrollmentStatus.ACTIVE, fixture.manager.status.value)
        assertEquals("09120000001", fixture.config.driverPhone)
        assertNull(fixture.pending)
        fixture.manager.resume()
        fixture.manager.receive(link())
        assertEquals(1, fixture.redemptions)
        assertEquals(1, fixture.writes)
        assertEquals(1, fixture.healthDispatches)
    }

    @Test fun rejectedOrReplayTicketCannotOverwriteOperationalConfig() = runBlocking {
        val fixture = Fixture(this@EnrollmentTest)
        val old = fixture.config
        fixture.failRedeem = true
        fixture.granted = true
        fixture.manager.receive(link())
        assertEquals(EnrollmentStatus.FAILED, fixture.manager.status.value)
        assertEquals(old, fixture.config)
        fixture.manager.receive(link())
        assertEquals(1, fixture.redemptions)
        assertEquals(0, fixture.writes)
        assertEquals(0, fixture.healthDispatches)
        val hub = Fixture(this@EnrollmentTest, "HUB")
        hub.manager.receive(link())
        assertEquals(0, hub.redemptions)
        assertEquals(EnrollmentStatus.FAILED, hub.manager.status.value)
    }

    @Test fun pendingEnrollmentRestoresAfterProcessRecreationWithoutReusingTicket() = runBlocking {
        val fixture = Fixture(this@EnrollmentTest)
        fixture.manager.receive(link())
        val restored = Fixture(this@EnrollmentTest)
        restored.pending = fixture.pending
        restored.granted = true
        restored.manager.resume()
        assertEquals(EnrollmentStatus.ACTIVE, restored.manager.status.value)
        assertEquals(0, restored.redemptions)
        assertEquals(1, restored.healthDispatches)
    }

    @Test fun httpExchangeContainsNoUrlOrHeaderSecretAndRejectsReplayRedirectsAndOversizedBodies() = runBlocking {
        val requests = mutableListOf<Request>()
        fun client(code: Int, body: String) = EnrollmentClient(OkHttpClient.Builder().addInterceptor { chain ->
            requests.add(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .header("Location", "http://evil.test/steal").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build())
        val parsed = EnrollmentContract.parseLink(link(), endpoint)
        val credential = client(200, document()).redeem(parsed, "device-123")
        assertEquals(token, credential.webhookToken)
        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("$server/api/v1/otp/enroll", request.url.toString())
        assertNull(request.url.query)
        assertNull(request.header("X-OTP-Webhook-Token"))
        val buffer = Buffer(); request.body!!.writeTo(buffer)
        val payload = JSONObject(buffer.readUtf8())
        assertEquals(ticket, payload.getString("ticket"))
        assertEquals("device-123", payload.getString("device_id"))
        for ((code, body) in listOf(409 to "{}", 410 to "{}", 302 to document(), 200 to "{}", 200 to "x".repeat(20_000))) {
            val before = requests.size
            try { client(code, body).redeem(parsed, "device-123"); fail("Must reject response") } catch (_: Exception) { }
            assertEquals(before + 1, requests.size)
        }
    }
}
