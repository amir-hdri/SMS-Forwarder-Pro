package com.example.enrollment

import com.example.utils.SmsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class EnrollmentClient(client: OkHttpClient = OkHttpClient()) {
    // Tickets are single use. Do not replay after an ambiguous connection failure or redirect.
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(20, TimeUnit.SECONDS).build()

    suspend fun redeem(link: EnrollmentLink, deviceId: String): EnrollmentCredentials = withContext(Dispatchers.IO) {
        require(deviceId.isNotBlank() && deviceId.length <= 128)
        val payload = JSONObject().put("ticket", link.ticket).put("device_id", deviceId)
        val request = Request.Builder().url(com.example.network.ManagedTransport.canonical(link.server).newBuilder().encodedPath("/api/v1/otp/enroll").build())
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            check(response.code == 200) { "Activation rejected; obtain a new link from BarPro" }
            val body = requireNotNull(response.body)
            require(body.contentLength() <= 16_384)
            // Limit chunked responses too, before decoding or persisting credentials.
            val source = body.source()
            require(!source.request(16_385))
            val bytes = source.readByteArray()
            EnrollmentContract.parseCredentials(bytes.toString(Charsets.UTF_8), link.server)
        }
    }

    /**
     * Resolves driver webhook endpoint and Hub configuration from BarPro by phone number.
     * Executes HTTP POST /api/v1/otp/resolve-driver with automatic GET fallback.
     */
    suspend fun resolve(
        driverPhone: String,
        serverUrl: String,
        deviceId: String,
        webhookToken: String = ""
    ): EnrollmentCredentials {
        val parsed = requireNotNull(serverUrl.toHttpUrlOrNull()) { "آدرس سرور نامعتبر است: $serverUrl" }
        return resolve(driverPhone, parsed, deviceId, webhookToken)
    }

    /**
     * Resolves driver webhook endpoint and Hub configuration from BarPro by phone number.
     * Executes HTTP POST /api/v1/otp/resolve-driver with automatic GET fallback.
     */
    suspend fun resolve(
        driverPhone: String,
        serverUrl: HttpUrl,
        deviceId: String,
        webhookToken: String = ""
    ): EnrollmentCredentials = withContext(Dispatchers.IO) {
        require(webhookToken.isNotBlank()) { "برای فعال‌سازی اولیه از لینک ثبت دستگاه در بارپرو استفاده کنید" }
        require(deviceId.isNotBlank() && deviceId.length <= 128) { "شناسه دستگاه نامعتبر است" }
        val cleanPhone = SmsParser.normalizePhoneNumber(driverPhone)
        require(cleanPhone.matches(Regex("09[0-9]{9}"))) {
            "شماره موبایل نامعتبر است (فرمت صحیح: 09xxxxxxxxx)"
        }

        val canonicalServer = com.example.network.ManagedTransport.canonical(serverUrl).newBuilder().encodedPath("/").build()

        // 1. Attempt POST /api/v1/otp/resolve-driver
        val postUrl = canonicalServer.newBuilder()
            .encodedPath("/api/v1/otp/resolve-driver")
            .build()
        val payload = JSONObject().apply {
            put("driver_phone", cleanPhone)
            put("device_id", deviceId)
        }
        val postRequest = Request.Builder()
            .url(postUrl)
            .header("Content-Type", "application/json")
            .header("X-Device-Id", deviceId)
            .header("X-OTP-Webhook-Token", webhookToken)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        var responseString: String? = null
        var lastError: Exception? = null

        try {
            http.newCall(postRequest).execute().use { response ->
                if (response.code == 200) {
                    val body = requireNotNull(response.body) { "پاسخ سرور خالی است" }
                    require(body.contentLength() <= 16_384) { "حجم پاسخ سرور بیش از حد مجاز است" }
                    val source = body.source()
                    require(!source.request(16_385)) { "حجم داده‌های پاسخ سرور بیش از 16KB است" }
                    responseString = source.readByteArray().toString(Charsets.UTF_8)
                } else {
                    val errorBody = response.body?.string() ?: ""
                    val detail = try {
                        JSONObject(errorBody).optString("detail", "")
                    } catch (_: Exception) { "" }
                    val errorMsg = if (detail.isNotBlank()) detail else when (response.code) {
                        404 -> "راننده‌ای با این شماره در ناوگان بارپرو یافت نشد"
                        403 -> "حساب کاربری راننده غیرفعال یا مسدود است"
                        400 -> "فرمت شماره موبایل ارسالی نامعتبر است"
                        else -> "خطای سرور بارپرو (کد ${response.code})"
                    }
                    lastError = IllegalStateException(errorMsg)
                }
            }
        } catch (networkErr: Exception) {
            lastError = networkErr
        }

        // 2. Fallback to GET /api/v1/otp/resolve-driver/{cleanPhone} if POST did not yield 200
        if (responseString == null) {
            val getUrl = canonicalServer.newBuilder()
                .encodedPath("/api/v1/otp/resolve-driver/$cleanPhone")
                .build()
            val getRequest = Request.Builder()
                .url(getUrl)
                .header("X-Device-Id", deviceId)
            .header("X-OTP-Webhook-Token", webhookToken)
                .get()
                .build()

            try {
                http.newCall(getRequest).execute().use { response ->
                    if (response.code == 200) {
                        val body = requireNotNull(response.body) { "پاسخ سرور خالی است" }
                        require(body.contentLength() <= 16_384) { "حجم پاسخ سرور بیش از حد مجاز است" }
                        val source = body.source()
                        require(!source.request(16_385)) { "حجم داده‌های پاسخ سرور بیش از 16KB است" }
                        responseString = source.readByteArray().toString(Charsets.UTF_8)
                    } else {
                        val errorBody = response.body?.string() ?: ""
                        val detail = try {
                            JSONObject(errorBody).optString("detail", "")
                        } catch (_: Exception) { "" }
                        val errorMsg = if (detail.isNotBlank()) detail else when (response.code) {
                            404 -> "راننده‌ای با این شماره در ناوگان بارپرو یافت نشد"
                            403 -> "حساب کاربری راننده غیرفعال یا مسدود است"
                            400 -> "فرمت شماره موبایل ارسالی نامعتبر است"
                            else -> (lastError?.message ?: "خطای سرور بارپرو (کد ${response.code})")
                        }
                        throw IllegalStateException(errorMsg)
                    }
                }
            } catch (getErr: Exception) {
                if (getErr is IllegalStateException) throw getErr
                throw IllegalStateException(
                    lastError?.message ?: getErr.message ?: "عدم برقراری ارتباط با سرور بارپرو",
                    getErr
                )
            }
        }

        val document = requireNotNull(responseString) { "پاسخ معتبری از سرور دریافت نشد" }
        EnrollmentContract.parseCredentials(document, canonicalServer)
    }
}
