package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.ForwardConfig
import com.example.data.model.SmsType
import com.example.network.DeviceStatusHelper
import com.example.network.SmsForwarderClient
import com.example.utils.SmsFallbackEnvelope
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class GatewayAcknowledgementTest {
    private val config = ForwardConfig(endpointUrl = "https://synthetic.barpro.test/api/v1/otp/sms-forwarder",
        authHeaderValue = "synthetic-gateway-test-secret-only", driverPhone = "09120000001", maxRetries = 0)

    // Literal bodies below were captured from the actual Python gateway with
    // isolated Redis, synthetic phone and fixed server time in the audit fixtures.
    @Test fun numericOtpAndItsDuplicateUseTheActualOtpReceiptWithoutInventedPhoneFields() = runBlocking {
        for (duplicate in listOf(false, true)) {
            val body = """{"success":true,"status":"success","otp_detected":true,"is_duplicate":$duplicate,"received_at":1799999997.0,"message":"OTP accepted"}"""
            assertTrue(client(body).relayGatewaySms("09120000001", frame("12345", 1799999997000), config).isSuccess)
        }
    }

    @Test fun acceptedHealthAndDuplicateHealthReceiptsAreNotMisclassifiedAsFailedOtps() = runBlocking {
        for (duplicate in listOf(false, true)) {
            val body = """{"success":true,"status":"health_acknowledged","phone":"09120000001","probe_timestamp":1799999999000,"received_at":1800000000.0,"is_duplicate":$duplicate}"""
            assertTrue(client(body).relayGatewaySms("09120000001", frame("HEALTH_CHECK", 1799999999000), config).isSuccess)
        }
    }

    @Test fun acceptedTestAndDuplicateTestReceiptsUseTheProbeAcknowledgement() = runBlocking {
        for (duplicate in listOf(false, true)) {
            val body = """{"success":true,"status":"probe_acknowledged","phone":"09120000001","probe_timestamp":1799999998000,"received_at":1800000000.0,"is_duplicate":$duplicate}"""
            val result = client(body).relayGatewaySms("09120000001", frame("TEST", 1799999998000), config)
            assertTrue(result.isSuccess)
            assertEquals(SmsType.OTHER, result.smsType)
            assertNull(result.otpCode)
        }
    }

    @Test fun legacySecondTimestampMatchesTheServersMillisecondReceipt() = runBlocking {
        val body = """{"success":true,"status":"health_acknowledged","phone":"09120000001","probe_timestamp":1799999999000,"received_at":1800000000.0,"is_duplicate":false}"""
        assertTrue(client(body).relayGatewaySms("09120000001", frame("HEALTH_CHECK", 1799999999), config).isSuccess)
    }

    @Test fun anotherDriversReceiptOrAnotherProbeCannotCompleteThisFrame() = runBlocking {
        val valid = """{"success":true,"status":"health_acknowledged","phone":"09120000001","probe_timestamp":1799999999000,"received_at":1800000000.0,"is_duplicate":true}"""
        val rejected = listOf(
            valid.replace("09120000001", "09120000002"),
            valid.replace("1799999999000", "1799999998000"),
            valid.replace("1800000000.0", "0"),
            valid.replace("health_acknowledged", "probe_acknowledged"),
            valid.replace("\"success\":true", "\"success\":false")
        )
        for (body in rejected) assertFalse(client(body).relayGatewaySms("09120000001", frame("HEALTH_CHECK", 1799999999000), config).isSuccess)
        val missingOtpFlag = """{"success":true,"status":"success","received_at":1799999997.0}"""
        assertFalse(client(missingOtpFlag).relayGatewaySms("09120000001", frame("12345", 1799999997000), config).isSuccess)
    }

    @Test fun capturedGatewayFailuresStayFailedAndOnlyStorageUnavailableIsRetryable() = runBlocking {
        val failures = listOf(
            401 to "Invalid SMS signature", 401 to "Invalid webhook token",
            422 to "Invalid signed SMS envelope", 422 to "Probe timestamp is in the future; check device clock",
            410 to "SMS OTP has expired", 410 to "SMS probe has expired",
            409 to "A newer OTP has already been received", 413 to "Gateway payload is too large",
            503 to "OTP storage unavailable; retry delivery", 503 to "Probe storage unavailable"
        )
        for ((status, detail) in failures) {
            val result = client(JSONObject().put("detail", detail).toString(), status)
                .relayGatewaySms("09120000001", frame("12345", 1799999997000), config)
            assertFalse(result.isSuccess)
            assertEquals(status, result.httpStatusCode)
            assertEquals(status == 503, result.isRetryable)
        }
    }

    @Test fun acknowledgementChangesPreserveOriginalFrameAndGatewayRequestContract() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val body = """{"success":true,"status":"health_acknowledged","phone":"09120000001","probe_timestamp":1799999999000,"received_at":1800000000.0,"is_duplicate":false}"""
        val original = frame("HEALTH_CHECK", 1799999999000)
        assertTrue(client(body, requests = requests).relayGatewaySms("+989120000001", original, config).isSuccess)
        val request = requests.single()
        val buffer = Buffer(); request.body!!.writeTo(buffer)
        val payload = JSONObject(buffer.readUtf8())
        assertEquals("/api/v1/otp/sms-gateway", request.url.encodedPath)
        assertEquals("synthetic-gateway-test-secret-only", request.header("X-OTP-Webhook-Token"))
        assertEquals(setOf("from", "text"), payload.keys().asSequence().toSet())
        assertEquals("09120000001", payload.getString("from"))
        assertEquals(original, payload.getString("text"))
    }

    @Test fun heartbeatCarriesObservedPermissionsUsingOnlyTheDeployedKeys() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.RECEIVE_SMS)
        shadowOf(context).denyPermissions(Manifest.permission.SEND_SMS)
        shadowOf(context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .setIgnoringBatteryOptimizations(context.packageName, true)
        val requests = CopyOnWriteArrayList<Request>()
        val result = client("""{"success":true,"status":"ready"}""", requests = requests)
            .sendHeartbeat(config, DeviceStatusHelper.getDeviceStatus(context), 7)
        assertTrue(result.isSuccess)
        val buffer = Buffer(); requests.single().body!!.writeTo(buffer)
        val body = JSONObject(buffer.readUtf8())
        assertTrue("The real permission snapshot must reach the deployed health route", body.has("permissions"))
        val observations = body.getJSONObject("permissions")
        assertEquals(setOf("receive_sms", "send_sms", "battery_optimization_ignored"), observations.keys().asSequence().toSet())
        assertTrue(observations.getBoolean("receive_sms"))
        assertFalse(observations.getBoolean("send_sms"))
        assertTrue(observations.getBoolean("battery_optimization_ignored"))
    }

    @Test fun anEmptyOrUnacknowledgedCleartextEndpointCannotSendTheHubCredential() = runBlocking {
        for (badConfig in listOf(config.copy(endpointUrl = ""),
            config.copy(endpointUrl = "http://synthetic.barpro.test/api/v1/otp/sms-forwarder", allowCleartextTransport = false))) {
            val requests = CopyOnWriteArrayList<Request>()
            val result = client("{}", requests = requests)
                .relayGatewaySms("09120000001", frame("12345", 1799999997000), badConfig)
            assertFalse(result.isSuccess)
            assertTrue("No credential-bearing HTTP request may leave without a valid, consented endpoint", requests.isEmpty())
        }
    }

    private fun frame(code: String, timestamp: Long) = SmsFallbackEnvelope.encode("09120000001", timestamp, code, "synthetic-gateway-test-secret-only")

    private fun client(body: String, status: Int = 200, requests: MutableList<Request> = CopyOnWriteArrayList()) =
        SmsForwarderClient(OkHttpClient.Builder().addInterceptor { chain ->
            requests.add(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build())
}
