package com.example

import android.app.Application
import com.example.data.model.ForwardConfig
import com.example.enrollment.HubInstallContract
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HubInstallContractTest {
    private val server = "https://87.107.160.251/".toHttpUrl()
    private val id = "hub_" + "a".repeat(32)
    private fun profile() = JSONObject().put("protocol", "barpro-hub-install-v1")
        .put("server", server.toString()).put("capability", "A".repeat(43)).put("expires_at", 1100)
    private fun credentials() = JSONObject().put("protocol", "barpro-hub-v1").put("device_id", id)
        .put("endpoint_url", "https://87.107.160.251/api/v1/otp/sms-forwarder")
        .put("webhook_token", "HUB1_" + "b".repeat(16) + "_" + "c".repeat(64))
        .put("hub_phone_mci", "09120000001").put("hub_phone_irancell", "09350000001")

    @Test fun validPrivateHubProfileAndGrantActivateAFactoryCleanProfile() {
        val parsed = HubInstallContract.parseProfile(profile().toString(), "HUB", server, 1000)
        assertEquals("A".repeat(43), parsed.capability)
        val grant = HubInstallContract.parseCredentials(credentials().toString(), server, id)
        val configured = grant.applyTo(ForwardConfig())
        assertTrue(configured.setupCompleted)
        assertTrue(configured.userConsentGiven)
        assertTrue(configured.isMasterEnabled)
        assertFalse(configured.allowCleartextTransport)
        assertEquals(id, configured.deviceIdentifier)
        assertEquals("09120000001", configured.fallbackServerPhoneNumber)
        assertEquals("09350000001", configured.hubIrancellPhoneNumber)
    }

    @Test fun privateProfileCannotCrossRoleOriginOrExpiryBoundaries() {
        for ((doc, role) in listOf(profile() to "DRIVER", profile().put("expires_at", 1000) to "HUB",
            profile().put("server", "https://other.example.test/") to "HUB",
            profile().put("server", "http://87.107.160.251/") to "HUB",
            profile().put("capability", "x".repeat(42)) to "HUB")) {
            assertThrows(IllegalArgumentException::class.java) {
                HubInstallContract.parseProfile(doc.toString(), role, server, 1000)
            }
        }
    }

    @Test fun grantCannotReplaceAnotherDeviceOrSendItsScopedCredentialElsewhere() {
        for (doc in listOf(credentials().put("device_id", "hub_" + "d".repeat(32)),
            credentials().put("webhook_token", "d".repeat(64)),
            credentials().put("endpoint_url", "https://other.example.test/api/v1/otp/sms-forwarder"),
            credentials().put("endpoint_url", "https://87.107.160.251/api/v1/otp/sms-forwarder?leak=true"))) {
            assertThrows(IllegalArgumentException::class.java) {
                HubInstallContract.parseCredentials(doc.toString(), server, id)
            }
        }
    }

    @Test fun scopedUpgradePreservesExistingExplicitStopAndConsent() {
        val grant = HubInstallContract.parseCredentials(credentials().toString(), server, id)
        val stopped = ForwardConfig(setupCompleted = true, authHeaderValue = "synthetic-existing-secret",
            isMasterEnabled = false, userConsentGiven = false, healthCheckIntervalMinutes = 13)
        val updated = grant.applyTo(stopped)
        assertFalse(updated.isMasterEnabled)
        assertFalse(updated.userConsentGiven)
        assertEquals(13, updated.healthCheckIntervalMinutes)
        assertEquals("HUB1_" + "b".repeat(16) + "_" + "c".repeat(64), updated.authHeaderValue)
        assertEquals(updated.authHeaderValue, updated.forwarderSecret)
    }
}
