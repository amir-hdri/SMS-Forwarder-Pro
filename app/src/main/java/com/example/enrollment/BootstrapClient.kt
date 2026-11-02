package com.example.enrollment

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class BootstrapClient(server: HttpUrl, client: OkHttpClient = OkHttpClient()) {
    private val base = com.example.network.ManagedTransport.canonical(server)
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(20, TimeUnit.SECONDS).build()

    init {
        require(base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null)
        // Localhost is reserved for owned protocol tests, never a deployment origin.
        require(base.isHttps || base.host in setOf("127.0.0.1", "::1", "localhost")) { "فعال‌سازی به اتصال امن نیاز دارد." }
    }

    suspend fun challenge(phone: String, deviceId: String, publicKey: String): JSONObject =
        request("challenge", JSONObject().apply {
            if (phone.isNotBlank()) put("driver_phone", phone)
            put("device_id", deviceId).put("public_key", publicKey)
        })

    suspend fun poll(nonce: String, deviceId: String, signature: String): JSONObject =
        request("poll", JSONObject().put("nonce", nonce).put("device_id", deviceId).put("signature", signature))

    suspend fun prove(origin: String, nonce: String, hubToken: String): JSONObject {
        com.example.network.BarProContract.webhookTokenError(hubToken)?.let { error(it) }
        return request("prove", JSONObject().put("origin", origin).put("nonce", nonce), hubToken)
    }

    private suspend fun request(path: String, payload: JSONObject, token: String = ""): JSONObject = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(base.newBuilder().encodedPath("/api/v1/otp/bootstrap/$path").build())
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
        if (token.isNotEmpty()) builder.header("X-OTP-Webhook-Token", token)
        http.newCall(builder.build()).execute().use { response ->
            if (response.code != 200) throw BootstrapHttpException(response.code)
            val body = requireNotNull(response.body)
            require(body.contentLength() <= 16_384)
            val source = body.source()
            require(!source.request(16_385))
            JSONObject(source.readByteArray().toString(Charsets.UTF_8))
        }
    }
}

class BootstrapHttpException(val code: Int) : IllegalStateException(when (code) {
    401, 403 -> "تأیید سیم‌کارت پذیرفته نشد؛ شماره ثبت‌شده و تنظیمات هاب را بررسی کنید."
    410 -> "مهلت فعال‌سازی تمام شده؛ دوباره تلاش کنید."
    429 -> "تعداد تلاش‌ها زیاد است؛ چند دقیقه دیگر دوباره تلاش کنید."
    else -> "ارتباط فعال‌سازی برقرار نشد؛ دوباره تلاش کنید."
})
