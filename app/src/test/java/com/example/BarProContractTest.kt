package com.example

import android.app.Application
import com.example.data.model.ForwardConfig
import com.example.network.BarProContract
import com.example.network.SmsForwarderClient
import com.example.utils.SmsFallbackEnvelope
import com.example.utils.SmsParser
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BarProContractTest {
    private val config = ForwardConfig(
        endpointUrl = "https://barpro.test/api/v1/otp/sms-forwarder",
        authHeaderValue = "test-secret-32-bytes-for-forwarder", driverPhone = "+989120000001", maxRetries = 0
    )

    private fun client(body: String, code: Int = 200, requests: MutableList<Request> = mutableListOf()): SmsForwarderClient =
        SmsForwarderClient(OkHttpClient.Builder().addInterceptor { chain ->
            requests.add(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build())

    @Test fun canonicalPayloadSeparatesRecipientFromGatewaySender() = runBlocking {
        val requests = mutableListOf<Request>()
        val result = client("""{"success":true,"status":"success","otp_detected":true}""", requests = requests)
            .forwardMessage("20007777", "کد تایید بارنامه: ۱۲۳۴۵۶", System.currentTimeMillis(), config)
        assertTrue(result.isSuccess)
        val request = requests.single()
        assertEquals(config.authHeaderValue, request.header(BarProContract.TOKEN_HEADER))
        assertEquals("09120000001", request.header(BarProContract.DRIVER_PHONE_HEADER))
        assertNull(request.header("X-Forwarder-Secret"))
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        val json = JSONObject(buffer.readUtf8())
        assertEquals("09120000001", json.getString("driver_phone"))
        assertEquals("20007777", json.getString("sender"))
        assertFalse(json.has("phone_number"))
        assertFalse(result.payloadSent.contains("۱۲۳۴۵۶"))
    }

    @Test fun pathBasedDriverPhoneEndpointIsAccepted() {
        val pathConfig = config.copy(endpointUrl = "https://barpro.test/api/v1/otp/sms-forwarder/09120000001")
        assertNull(BarProContract.configurationError(pathConfig))
        val webhookAliasConfig = config.copy(endpointUrl = "https://barpro.test/api/v1/otp/webhook/09120000001")
        assertNull(BarProContract.configurationError(webhookAliasConfig))
        val gatewayConfig = config.copy(endpointUrl = "https://barpro.test/api/v1/otp/sms-gateway", driverPhone = "")
        assertNull(BarProContract.configurationError(gatewayConfig, requireRecipient = false))
        assertTrue(BarProContract.isPathValid("/api/v1/otp/sms-gateway"))
        val invalidPhonePath = config.copy(endpointUrl = "https://barpro.test/api/v1/otp/sms-forwarder/12345")
        assertNotNull(BarProContract.configurationError(invalidPhonePath))
    }

    @Test fun iranianDstShiftIsTolerated() = runBlocking {
        val now = System.currentTimeMillis()
        val oneHourPast = now - 3_605_000L
        assertTrue(BarProContract.isTimestampAcceptable(oneHourPast, now))

        val oneHourFuture = now + 3_595_000L
        assertTrue(BarProContract.isTimestampAcceptable(oneHourFuture, now))

        val trulyExpired = now - 4_000_000L
        assertFalse(BarProContract.isTimestampAcceptable(trulyExpired, now))
    }

    @Test fun ambiguousOtpErrorMessage() = runBlocking {
        val ambiguousResponse = """{"detail":"AMBIGUOUS_OTP: Multiple pending waybills awaiting OTP; driver_phone is required to disambiguate"}"""
        val result = client(ambiguousResponse, code = 422)
            .forwardMessage("20007777", "کد تایید: 12345", System.currentTimeMillis(), config)
        assertFalse(result.isSuccess)
        assertEquals(422, result.httpStatusCode)
        assertTrue(result.errorMessage?.contains("چندین بارنامه همزمان منتظر OTP هستند") == true)
    }

    @Test fun http200AloneIsNotAnOtpReceipt() = runBlocking {
        for (body in listOf("<html>login</html>", "{}", """{"status":"ignored"}""", """{"success":false}""")) {
            assertFalse(client(body).forwardMessage("UTCMS", "کد تایید: 12345", System.currentTimeMillis(), config).isSuccess)
        }
    }

    @Test fun healthProbeContainsNoSyntheticCodeOrRecipient() = runBlocking {
        val requests = mutableListOf<Request>()
        val result = client("""{"success":true,"status":"ready"}""", requests = requests).checkHealth(config)
        assertTrue(result.isSuccess)
        val buffer = Buffer()
        requests.single().body!!.writeTo(buffer)
        assertEquals("""{"event":"HEALTH_CHECK"}""", buffer.readUtf8())
    }

    @Test fun staleSmsNeverReachesNetwork() = runBlocking {
        val requests = mutableListOf<Request>()
        val result = client("{}", requests = requests).forwardMessage("UTCMS", "کد تایید: 12345",
            System.currentTimeMillis() - 300_001, config)
        assertFalse(result.isSuccess)
        assertTrue(requests.isEmpty())
    }

    @Test fun rateLimitRetriesButAuthenticationAndExpiredCodesDoNot() {
        assertTrue(BarProContract.retryable(429))
        assertTrue(BarProContract.retryable(503))
        assertFalse(BarProContract.retryable(401))
        assertFalse(BarProContract.retryable(410))
    }

    @Test fun rejectUnsafeAndIncompleteConfiguration() {
        assertNotNull(BarProContract.configurationError(ForwardConfig()))
        assertNotNull(BarProContract.configurationError(config.copy(driverPhone = "")))
        assertNotNull(BarProContract.configurationError(config.copy(endpointUrl = "http://barpro.test/api/v1/otp/sms-forwarder")))
        assertNotNull(BarProContract.configurationError(config.copy(isEncryptionEnabled = true)))
        assertNull(BarProContract.configurationError(config))
    }

    @Test fun parsingDoesNotTruncatePhonesOrPreferTrackingNumbers() {
        for (text in listOf("کد تایید: 123456789", "کد ورود: 09123456789", "کد رهگیری بارنامه: 12345"))
            assertNull(text, SmsParser.extractOtp(text))
        assertEquals("876543", SmsParser.extractOtp("کد رهگیری 12345 و کد تایید بارنامه: 876543"))
        assertFalse(SmsParser.isUtcmsSms("300012345", "کد تایید اپلیکیشن خرید: 12345"))
    }

    @Test fun expandedOtpKeywordsMatchingBarProBackend() {
        assertEquals("84920", SmsParser.extractOtp("کد امنیتی: 84920"))
        assertEquals("53124", SmsParser.extractOtp("کد صدور بارنامه 53124"))
        assertEquals("92831", SmsParser.extractOtp("رمز یکبارمصرف: 92831"))
        assertEquals("61524", SmsParser.extractOtp("کد مجوز ورود به بارپرو 61524"))
        assertEquals("73921", SmsParser.extractOtp("UTCMS auth code: 73921"))
        assertEquals("42109", SmsParser.extractOtp("کد ثبت سامانه بارنامه: 42109"))
    }
    @Test fun probeReceiptRequiresExactPhoneTimestampAndPositiveReceiptTime() = runBlocking {
        val timestamp = System.currentTimeMillis()
        for ((body, expected) in listOf(
            """{"success":true,"status":"probe_received","phone":"09120000001","probe_timestamp":$timestamp,"received_at":1800000000.25}""" to true,
            """{"success":true,"status":"probe_received","phone":"09120000002","probe_timestamp":$timestamp,"received_at":1800000000.25}""" to false,
            """{"success":true,"status":"probe_received","phone":"09120000001","probe_timestamp":${timestamp - 1},"received_at":1800000000.25}""" to false,
            """{"success":true,"status":"probe_pending"}""" to false,
            """{"success":true,"status":"ready"}""" to false,
            """{"success":true,"status":"probe_received"}""" to false
        )) {
            val requests = mutableListOf<Request>()
            val result = client(body, requests = requests).checkSmsProbeReceipt(config, timestamp)
            assertEquals(body, expected, result.isSuccess)
            val request = requests.single()
            assertEquals("test-secret-32-bytes-for-forwarder", request.header(BarProContract.TOKEN_HEADER))
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            val payload = JSONObject(buffer.readUtf8())
            assertEquals("SMS_PROBE_STATUS", payload.getString("event"))
            assertEquals(timestamp, payload.getLong("probe_timestamp"))
            assertEquals("09120000001", payload.getString("driver_phone"))
            assertFalse(payload.has("text"))
        }
    }

    @Test fun testRelayGatewaySmsDispatchesToSmsGatewayEndpoint() = runBlocking {
        val requests = mutableListOf<Request>()
        val envelope = SmsFallbackEnvelope.encode("09120000001", 1800000000000, "54321", config.authHeaderValue)
        val result = client("""{"success":true,"status":"success","otp_detected":true,"is_duplicate":false}""", requests = requests)
            .relayGatewaySms(sender = "09120000001", envelopeText = envelope, config = config)

        assertTrue(result.isSuccess)
        val request = requests.single()
        assertEquals("https://barpro.test/api/v1/otp/sms-gateway", request.url.toString())
        assertEquals(config.authHeaderValue, request.header(BarProContract.TOKEN_HEADER))

        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        val json = JSONObject(buffer.readUtf8())
        assertEquals("09120000001", json.getString("from"))
        assertEquals(envelope, json.getString("text"))
        assertEquals("54321", result.otpCode)
    }

    @Test fun testResolveGatewayUrlHandlesStandardAndPathBasedEndpoints() {
        val c = client("{}")
        assertEquals("https://barpro.test/api/v1/otp/sms-gateway", c.resolveGatewayUrl("https://barpro.test/api/v1/otp/sms-forwarder"))
        assertEquals("https://barpro.test/api/v1/otp/sms-gateway", c.resolveGatewayUrl("https://barpro.test/api/v1/otp/sms-forwarder/09120000001"))
        assertEquals("https://barpro.test/api/v1/otp/sms-gateway", c.resolveGatewayUrl("https://barpro.test/api/v1/otp/webhook/09120000001"))
        assertEquals("https://barpro.test/api/v1/otp/sms-gateway", c.resolveGatewayUrl("https://barpro.test/api/v1/otp/webhook"))
        assertEquals("https://barpro.test/api/v1/otp/sms-gateway", c.resolveGatewayUrl("https://barpro.test/api/v1/otp/sms-gateway"))
        assertEquals("https://barpro.test/api/v1/otp/sms-gateway", c.resolveGatewayUrl("https://barpro.test/api/v1/otp"))
        assertEquals("https://barpro.test/api/v1/otp/sms-gateway", c.resolveGatewayUrl("https://barpro.test"))
        assertEquals("https://barpro.test/api/v1/otp/sms-gateway", c.resolveGatewayUrl("https://barpro.test/"))
        assertEquals("http://192.168.1.100:8000/api/v1/otp/sms-gateway", c.resolveGatewayUrl("http://192.168.1.100:8000"))
        assertEquals("https://api.barpro.ir/api/v1/otp/sms-gateway", c.resolveGatewayUrl(""))
    }

    @Test fun testRelayGatewaySmsNormalizesInternationalSenderNumber() = runBlocking {
        val requests = mutableListOf<Request>()
        val envelope = SmsFallbackEnvelope.encode("09120000001", 1800000000000, "54321", config.authHeaderValue)
        val pathConfig = config.copy(endpointUrl = "https://barpro.test/api/v1/otp/sms-forwarder/09120000001")
        val result = client("""{"success":true,"status":"success","otp_detected":true,"is_duplicate":false}""", requests = requests)
            .relayGatewaySms(sender = "+989120000001", envelopeText = envelope, config = pathConfig)

        assertTrue(result.isSuccess)
        val request = requests.single()
        assertEquals("https://barpro.test/api/v1/otp/sms-gateway", request.url.toString())
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        val json = JSONObject(buffer.readUtf8())
        assertEquals("09120000001", json.getString("from"))
    }
}
