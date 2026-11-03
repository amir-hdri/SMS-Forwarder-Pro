package com.example

import android.app.Application
import com.example.update.ConfigFetch
import com.example.update.RemoteConfig
import com.example.update.RemoteConfigClient
import org.json.JSONObject
import org.junit.Assert.*
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
class DriverPublicConfigTest {
    private val audience = "ir.barpro.fleet.smsforwarder.driver"
    private fun payload() = JSONObject().put("audience", audience).put("version", 1).put("issuedAt", 1000)

    @Test fun driverPublicOverlayCannotChangeEndpointOrCredentialScope() {
        for ((field, value) in listOf("endpointUrl" to "https://fleet.example.test/api/v1/otp/sms-forwarder",
            "webhookToken" to "synthetic-public-token")) {
            assertThrows("$field must never replace enrolled Driver scope", RemoteConfig.RejectedException::class.java) {
                RemoteConfig.parse(payload().put(field, value), audience, 0, 1000)
            }
        }
    }

    @Test fun signedInvalidAudienceIsReportedAsARejectionRatherThanSteadyState() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val document = payload().put("audience", "another-application").toString()
        val segment = Base64.getUrlEncoder().withoutPadding().encodeToString(document.toByteArray())
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private); update(segment.toByteArray(Charsets.US_ASCII)); sign()
        }
        val signed = segment + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
        val result = RemoteConfigClient(Base64.getEncoder().encodeToString(pair.public.encoded)).verify(signed, audience, 0)
        assertTrue("A malformed signed configuration must have an actionable rejection", result is ConfigFetch.Failed)
        assertTrue((result as ConfigFetch.Failed).isSecurityRejection)
    }
}
