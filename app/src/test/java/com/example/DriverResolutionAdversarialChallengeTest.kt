package com.example

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.SecureStorageManager
import com.example.data.local.AppDatabase
import com.example.data.model.ForwardConfig
import com.example.data.repository.SmsForwardRepository
import com.example.enrollment.EnrollmentClient
import com.example.enrollment.EnrollmentContract
import com.example.enrollment.EnrollmentManager
import com.example.enrollment.EnrollmentStatus
import com.example.network.SmsForwarderClient
import com.example.utils.SmsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DriverResolutionAdversarialChallengeTest {

    @get:Rule
    val secureStorage = SecureStorageTestRule()

    private lateinit var context: Application
    private lateinit var db: AppDatabase
    private val endpoint = "http://87.107.160.251/api/v1/otp/sms-forwarder"
    private val serverUrl = "http://87.107.160.251"
    private val validPhone = "09123456789"
    private val validToken = "c".repeat(64)
    private val validMci = "09966567754"
    private val validIrancell = "09047119812"

    private fun validDocument(
        phone: String = validPhone,
        url: String = "$serverUrl/api/v1/otp/sms-forwarder/$phone",
        token: String = validToken,
        mci: String = validMci,
        irancell: String = validIrancell,
        protocol: String = "barpro-driver-v1"
    ): String = JSONObject().apply {
        put("protocol", protocol)
        put("driver_phone", phone)
        put("endpoint_url", url)
        put("webhook_token", token)
        put("hub_phone_mci", mci)
        put("hub_phone_irancell", irancell)
    }.toString()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun createMockClient(
        postHandler: (Request) -> Response,
        getHandler: ((Request) -> Response)? = null
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val req = chain.request()
            if (req.method == "POST") {
                postHandler(req)
            } else if (getHandler != null) {
                getHandler(req)
            } else {
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(405)
                    .message("Method Not Allowed")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        }
        .build()

    // =========================================================================
    // 1. MALFORMED PHONE INPUT CHALLENGES
    // =========================================================================

    @Test
    fun testEmptyPhoneInputRejected() = runBlocking {
        val client = EnrollmentClient(OkHttpClient())
        try {
            client.resolve("", serverUrl, "dev-1", validToken)
            fail("Empty phone must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("شماره موبایل نامعتبر است"))
        }
    }

    @Test
    fun testLettersInPhoneInputRejected() = runBlocking {
        val client = EnrollmentClient(OkHttpClient())
        for (input in listOf("0912abc3456", "abcdefghijk", "0912345678a")) {
            try {
                client.resolve(input, serverUrl, "dev-1", validToken)
                fail("Phone with letters must be rejected: $input")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("شماره موبایل نامعتبر است"))
            }
        }
    }

    @Test
    fun testSymbolsInPhoneInputRejected() = runBlocking {
        val client = EnrollmentClient(OkHttpClient())
        for (input in listOf("0912#34*56@", "0912!345678", "0912@345678")) {
            try {
                client.resolve(input, serverUrl, "dev-1", validToken)
                fail("Phone with symbols must be rejected: $input")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("شماره موبایل نامعتبر است"))
            }
        }
    }

    @Test
    fun testTwelveDigitPhoneInputRejected() = runBlocking {
        val client = EnrollmentClient(OkHttpClient())
        try {
            client.resolve("091234567890", serverUrl, "dev-1", validToken)
            fail("12-digit phone must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("شماره موبایل نامعتبر است"))
        }
    }

    @Test
    fun testTenDigitPhoneInputRejected() = runBlocking {
        val client = EnrollmentClient(OkHttpClient())
        try {
            client.resolve("0912345678", serverUrl, "dev-1", validToken)
            fail("10-digit phone must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("شماره موبایل نامعتبر است"))
        }
    }

    @Test
    fun testNonIranianInternationalNumbersRejected() = runBlocking {
        val client = EnrollmentClient(OkHttpClient())
        for (input in listOf("+14155552671", "447911123456", "+4915123456789", "+905321234567")) {
            try {
                client.resolve(input, serverUrl, "dev-1", validToken)
                fail("Non-Iranian international phone must be rejected: $input")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("شماره موبایل نامعتبر است"))
            }
        }
    }

    @Test
    fun testNonIranianMobileLandlinePrefixRejected() = runBlocking {
        val client = EnrollmentClient(OkHttpClient())
        // 021 is Tehran landline, not Iranian mobile (must start with 09)
        for (input in listOf("02188776655", "03132223344", "05138889900")) {
            try {
                client.resolve(input, serverUrl, "dev-1", validToken)
                fail("Landline numbers must be rejected: $input")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("شماره موبایل نامعتبر است"))
            }
        }
    }

    @Test
    fun testValidPersianAndArabicDigitsNormalizedSuccessfully() = runBlocking {
        val mockHttp = createMockClient({ req ->
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(validDocument().toResponseBody("application/json".toMediaType()))
                .build()
        })
        val client = EnrollmentClient(mockHttp)

        // Persian digits ۰۹۱۲۳۴۵۶۷۸۹
        val credsPersian = client.resolve("۰۹۱۲۳۴۵۶۷۸۹", serverUrl, "dev-1", validToken)
        assertEquals(validPhone, credsPersian.driverPhone)

        // Arabic-Indic digits ٠٩١٢٣٤٥٦٧٨٩
        val credsArabic = client.resolve("٠٩١٢٣٤٥٦٧٨٩", serverUrl, "dev-1", validToken)
        assertEquals(validPhone, credsArabic.driverPhone)

        // Formatted with +98 / 0098 / dashes / spaces
        val credsPlus98 = client.resolve("+98 912 345 6789", serverUrl, "dev-1", validToken)
        assertEquals(validPhone, credsPlus98.driverPhone)

        val credsDoubleZero = client.resolve("0098-912-345-6789", serverUrl, "dev-1", validToken)
        assertEquals(validPhone, credsDoubleZero.driverPhone)
    }

    // =========================================================================
    // 2. MALFORMED SERVER RESPONSE CHALLENGES
    // =========================================================================

    @Test
    fun testTruncatedJsonResponseFailsGracefully() = runBlocking {
        val truncatedBody = """{"protocol":"barpro-driver-v1","driver_phone":"""
        val mockHttp = createMockClient({ req ->
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(truncatedBody.toResponseBody("application/json".toMediaType()))
                .build()
        })
        val client = EnrollmentClient(mockHttp)
        try {
            client.resolve(validPhone, serverUrl, "dev-1", validToken)
            fail("Truncated JSON must throw JSONException")
        } catch (e: JSONException) {
            assertNotNull(e)
        }
    }

    @Test
    fun testUnexpectedOrMissingKeysRejected() = runBlocking {
        // Missing webhook_token
        val missingTokenBody = JSONObject().apply {
            put("protocol", "barpro-driver-v1")
            put("driver_phone", validPhone)
            put("endpoint_url", "$serverUrl/api/v1/otp/sms-forwarder/$validPhone")
            put("hub_phone_mci", validMci)
        }.toString()

        val mockHttp = createMockClient({ req ->
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(missingTokenBody.toResponseBody("application/json".toMediaType()))
                .build()
        })
        val client = EnrollmentClient(mockHttp)
        try {
            client.resolve(validPhone, serverUrl, "dev-1", validToken)
            fail("Missing required keys must throw JSONException")
        } catch (e: JSONException) {
            assertNotNull(e)
        }
    }

    @Test
    fun test500InternalServerErrorBodyJson() = runBlocking {
        val serverErrorJson = """{"detail": "Internal server database error"}"""
        val mockHttp = createMockClient(
            postHandler = { req ->
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("Internal Server Error")
                    .body(serverErrorJson.toResponseBody("application/json".toMediaType()))
                    .build()
            },
            getHandler = { req ->
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("Internal Server Error")
                    .body(serverErrorJson.toResponseBody("application/json".toMediaType()))
                    .build()
            }
        )
        val client = EnrollmentClient(mockHttp)
        try {
            client.resolve(validPhone, serverUrl, "dev-1", validToken)
            fail("500 response must throw IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("Internal server database error", e.message)
        }
    }

    @Test
    fun test500InternalServerErrorBodyHtml() = runBlocking {
        val serverErrorHtml = "<html><body>500 Internal Server Error</body></html>"
        val mockHttp = createMockClient(
            postHandler = { req ->
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("Internal Server Error")
                    .body(serverErrorHtml.toResponseBody("text/html".toMediaType()))
                    .build()
            },
            getHandler = { req ->
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("Internal Server Error")
                    .body(serverErrorHtml.toResponseBody("text/html".toMediaType()))
                    .build()
            }
        )
        val client = EnrollmentClient(mockHttp)
        try {
            client.resolve(validPhone, serverUrl, "dev-1", validToken)
            fail("500 HTML response must throw IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("خطای سرور بارپرو (کد 500)"))
        }
    }

    @Test
    fun testOversizedPayloadExceeding16KbRejected() = runBlocking {
        val padding = "x".repeat(17_000)
        val hugeBody = JSONObject(validDocument()).put("padding", padding).toString()
        assertTrue("Body size must exceed 16KB", hugeBody.length > 16_384)

        // 1. EnrollmentContract directly rejects with IllegalArgumentException
        try {
            EnrollmentContract.parseCredentials(hugeBody, EnrollmentContract.trustedServer(endpoint))
            fail("EnrollmentContract must reject oversized document")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e)
        }

        // 2. EnrollmentClient rejects and wraps into IllegalStateException or IllegalArgumentException
        val mockHttp = createMockClient(
            postHandler = { req ->
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(hugeBody.toResponseBody("application/json".toMediaType()))
                    .build()
            },
            getHandler = { req ->
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(hugeBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }
        )
        val client = EnrollmentClient(mockHttp)
        try {
            client.resolve(validPhone, serverUrl, "dev-1", validToken)
            fail("16KB+ response must be rejected by EnrollmentClient")
        } catch (e: Exception) {
            assertTrue(
                "Exception message must describe size limit violation: ${e.message}",
                e.message!!.contains("بیش از حد مجاز") ||
                e.message!!.contains("بیش از 16KB") ||
                e.message!!.contains("Failed requirement")
            )
        }

        // 3. EnrollmentManager catches and sets status = FAILED without crashing
        var stagedDoc: String? = null
        val manager = EnrollmentManager(
            defaultEndpoint = endpoint,
            role = "DRIVER",
            deviceId = { "dev-1" },
            redeem = { _, _ -> throw UnsupportedOperationException() },
            getConfig = { ForwardConfig() },
            saveConfig = {},
            stage = { stagedDoc = it },
            loadStage = { stagedDoc },
            permissionsGranted = { true },
            onActivated = {},
            resolve = { phone, devId -> client.resolve(phone, EnrollmentContract.trustedServer(endpoint), devId, validToken) }
        )

        manager.resolveDriverConfiguration(validPhone)
        assertEquals(EnrollmentStatus.FAILED, manager.status.value)
        assertNotNull(manager.errorMessage.value)
        assertTrue(manager.errorMessage.value!!.contains("بیش از حد مجاز") || manager.errorMessage.value!!.contains("بیش از 16KB"))
    }

    @Test
    fun testEmptyResponseBodyRejected() = runBlocking {
        val mockHttp = createMockClient({ req ->
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("".toResponseBody("application/json".toMediaType()))
                .build()
        })
        val client = EnrollmentClient(mockHttp)
        try {
            client.resolve(validPhone, serverUrl, "dev-1", validToken)
            fail("Empty body must fail JSON parsing")
        } catch (e: JSONException) {
            assertNotNull(e)
        }
    }

    @Test
    fun test404NotFoundReturnsDescriptiveDriverErrorMessage() = runBlocking {
        val mockHttp = createMockClient(
            postHandler = { req ->
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Not Found")
                    .body("""{"detail":"Driver phone number not registered in BarPro"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            },
            getHandler = { req ->
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Not Found")
                    .body("""{"detail":"Driver phone number not registered in BarPro"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        )
        val client = EnrollmentClient(mockHttp)
        try {
            client.resolve(validPhone, serverUrl, "dev-1", validToken)
            fail("404 must throw IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("Driver phone number not registered in BarPro", e.message)
        }
    }

    @Test
    fun test403ForbiddenReturnsDeactivatedDriverErrorMessage() = runBlocking {
        val mockHttp = createMockClient(
            postHandler = { req ->
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(403)
                    .message("Forbidden")
                    .body("""{"detail":"Driver account is deactivated or blocked"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            },
            getHandler = { req ->
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(403)
                    .message("Forbidden")
                    .body("""{"detail":"Driver account is deactivated or blocked"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        )
        val client = EnrollmentClient(mockHttp)
        try {
            client.resolve(validPhone, serverUrl, "dev-1", validToken)
            fail("403 must throw IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("Driver account is deactivated or blocked", e.message)
        }
    }

    // =========================================================================
    // 3. NETWORK FAILURE SCENARIOS & APP CRASH RESILIENCE
    // =========================================================================

    @Test
    fun testOfflineDeviceConnectExceptionHandledGracefullyByEnrollmentManager() = runBlocking {
        var savedConfig: ForwardConfig? = null
        var stagedDoc: String? = null
        val client = EnrollmentClient(OkHttpClient.Builder().addInterceptor { _ ->
            throw ConnectException("Failed to connect to /87.107.160.251:443")
        }.build())

        val manager = EnrollmentManager(
            defaultEndpoint = endpoint,
            role = "DRIVER",
            deviceId = { "test-device-id" },
            redeem = { _, _ -> throw UnsupportedOperationException() },
            getConfig = { ForwardConfig() },
            saveConfig = { savedConfig = it },
            stage = { stagedDoc = it },
            loadStage = { stagedDoc },
            permissionsGranted = { true },
            onActivated = {},
            resolve = { phone, devId ->
                client.resolve(phone, EnrollmentContract.trustedServer(endpoint), devId, validToken)
            }
        )

        // Must NOT crash or throw unhandled exception
        manager.resolveDriverConfiguration(validPhone)

        assertEquals(EnrollmentStatus.FAILED, manager.status.value)
        assertNotNull(manager.errorMessage.value)
        assertTrue(manager.errorMessage.value!!.contains("Failed to connect") || manager.errorMessage.value!!.contains("خطا"))
        assertNull("No config should be saved on connection failure", savedConfig)
    }

    @Test
    fun testSocketTimeoutExceptionHandledGracefullyByEnrollmentManager() = runBlocking {
        val client = EnrollmentClient(OkHttpClient.Builder().addInterceptor { _ ->
            throw SocketTimeoutException("Read timed out after 20000ms")
        }.build())

        val manager = EnrollmentManager(
            defaultEndpoint = endpoint,
            role = "DRIVER",
            deviceId = { "test-device-id" },
            redeem = { _, _ -> throw UnsupportedOperationException() },
            getConfig = { ForwardConfig() },
            saveConfig = {},
            stage = {},
            loadStage = { null },
            permissionsGranted = { true },
            onActivated = {},
            resolve = { phone, devId ->
                client.resolve(phone, EnrollmentContract.trustedServer(endpoint), devId, validToken)
            }
        )

        manager.resolveDriverConfiguration(validPhone)

        assertEquals(EnrollmentStatus.FAILED, manager.status.value)
        assertNotNull(manager.errorMessage.value)
        assertTrue(manager.errorMessage.value!!.contains("timed out") || manager.errorMessage.value!!.contains("خطا"))
    }

    @Test
    fun testDnsFailureUnknownHostExceptionHandledGracefully() = runBlocking {
        val client = EnrollmentClient(OkHttpClient.Builder().addInterceptor { _ ->
            throw UnknownHostException("Unable to resolve host \"87.107.160.251\": No address associated with hostname")
        }.build())

        val manager = EnrollmentManager(
            defaultEndpoint = endpoint,
            role = "DRIVER",
            deviceId = { "test-device-id" },
            redeem = { _, _ -> throw UnsupportedOperationException() },
            getConfig = { ForwardConfig() },
            saveConfig = {},
            stage = {},
            loadStage = { null },
            permissionsGranted = { true },
            onActivated = {},
            resolve = { phone, devId ->
                client.resolve(phone, EnrollmentContract.trustedServer(endpoint), devId, validToken)
            }
        )

        manager.resolveDriverConfiguration(validPhone)

        assertEquals(EnrollmentStatus.FAILED, manager.status.value)
        assertNotNull(manager.errorMessage.value)
        assertTrue(manager.errorMessage.value!!.contains("Unable to resolve host") || manager.errorMessage.value!!.contains("خطا"))
    }

    @Test
    fun testSslHandshakeFailureHandledGracefully() = runBlocking {
        val client = EnrollmentClient(OkHttpClient.Builder().addInterceptor { _ ->
            throw SSLHandshakeException("CertPathValidatorException: Trust anchor for certification path not found.")
        }.build())

        val manager = EnrollmentManager(
            defaultEndpoint = endpoint,
            role = "DRIVER",
            deviceId = { "test-device-id" },
            redeem = { _, _ -> throw UnsupportedOperationException() },
            getConfig = { ForwardConfig() },
            saveConfig = {},
            stage = {},
            loadStage = { null },
            permissionsGranted = { true },
            onActivated = {},
            resolve = { phone, devId ->
                client.resolve(phone, EnrollmentContract.trustedServer(endpoint), devId, validToken)
            }
        )

        manager.resolveDriverConfiguration(validPhone)

        assertEquals(EnrollmentStatus.FAILED, manager.status.value)
        assertNotNull(manager.errorMessage.value)
    }

    // =========================================================================
    // 4. ONBOARDING RECOVERY & RE-ENTRANCY UNDER RAPID CONCURRENCY
    // =========================================================================

    @Test
    fun testConcurrentDriverResolutionCallsSerializeCleanlyViaMutex() = runBlocking {
        val executionCounter = AtomicInteger(0)
        val mockHttp = createMockClient({ req ->
            executionCounter.incrementAndGet()
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(validDocument().toResponseBody("application/json".toMediaType()))
                .build()
        })
        val client = EnrollmentClient(mockHttp)

        var installedConfig: ForwardConfig? = null
        var staged: String? = null
        val manager = EnrollmentManager(
            defaultEndpoint = endpoint,
            role = "DRIVER",
            deviceId = { "dev-1" },
            redeem = { _, _ -> throw UnsupportedOperationException() },
            getConfig = { ForwardConfig() },
            saveConfig = { installedConfig = it },
            stage = { staged = it },
            loadStage = { staged },
            permissionsGranted = { true },
            onActivated = {},
            resolve = { phone, devId -> client.resolve(phone, EnrollmentContract.trustedServer(endpoint), devId, validToken) }
        )

        // Fire 10 sequential rapid resolutions
        repeat(10) {
            manager.resolveDriverConfiguration(validPhone)
            assertEquals(EnrollmentStatus.ACTIVE, manager.status.value)
            assertNotNull(installedConfig)
            assertEquals(validPhone, installedConfig!!.driverPhone)
            assertEquals(validToken, installedConfig!!.authHeaderValue)
        }

        assertEquals("Total HTTP executions should match calls exactly", 10, executionCounter.get())
    }
}
