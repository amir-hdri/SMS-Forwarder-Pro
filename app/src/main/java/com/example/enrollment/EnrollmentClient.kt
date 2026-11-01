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
     * Executes HTTP POST /api/v1/otp/resolve-driver with GET fallback only for HTTP 405.
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
     * Executes HTTP POST /api/v1/otp/resolve-driver with GET fallback only for HTTP 405.
     */
    suspend fun resolve(
        driverPhone: String,
        serverUrl: HttpUrl,
        deviceId: String,
        webhookToken: String = ""
    ): EnrollmentCredentials = withContext(Dispatchers.IO) {
        if (webhookToken.isBlank()) throw ActivationRequiredException()
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

        fun execute(request: Request): Pair<Int, String?> = http.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw ActivationRequiredException()
            if (response.code != 200) return@use response.code to null
            val body = requireNotNull(response.body)
            require(body.contentLength() <= 16_384) { "حجم پاسخ سرور بیش از حد مجاز است" }
            val source = body.source()
            require(!source.request(16_385)) { "حجم داده‌های پاسخ سرور بیش از 16KB است" }
            response.code to source.readByteArray().toString(Charsets.UTF_8)
        }
        val (status, document) = execute(postRequest)
        // Only method negotiation may use the legacy GET endpoint; auth/network errors cannot.
        val resolved = if (status == 405) {
            val getRequest = postRequest.newBuilder().url(canonicalServer.newBuilder()
                .encodedPath("/api/v1/otp/resolve-driver/$cleanPhone").build()).get().build()
            execute(getRequest).second
        } else document
        check(resolved != null) {
            if (status == 404) "راننده‌ای با این شماره در ناوگان بارپرو یافت نشد"
            else "خطای سرور بارپرو (کد $status)"
        }
        EnrollmentContract.parseCredentials(resolved, canonicalServer)
    }
}

class ActivationRequiredException : IllegalArgumentException("تأیید سیم‌کارت برای فعال‌سازی لازم است.")
