package com.example.enrollment

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool

class BootstrapClient(
    server: HttpUrl,
    client: OkHttpClient = OkHttpClient(),
    private val hubKey: DeviceBootstrapKey = DeviceBootstrapKey(),
    poolConnections: Boolean = true
) {
    private val base = com.example.network.ManagedTransport.canonical(server)
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(20, TimeUnit.SECONDS).build()
    // Poll and prove are idempotent on the server; short keep-alive avoids Nginx 65s / carrier NAT stale EOFs.
    private val retryableHttp = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .connectionPool(
            if (poolConnections) ConnectionPool(2, 15, TimeUnit.SECONDS)
            else ConnectionPool(0, 1, TimeUnit.MILLISECONDS)
        )
        .callTimeout(20, TimeUnit.SECONDS).build()

    init {
        require(base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null)
        // Localhost is reserved for owned protocol tests, never a deployment origin.
        require(com.example.network.ManagedTransport.permitsForwarderTransport(base) || base.host in setOf("127.0.0.1", "::1", "localhost")) { "فعال‌سازی به اتصال مجاز نیاز دارد." }
    }

    suspend fun enrollHub(capability: String, deviceId: String, key: DeviceBootstrapKey): JSONObject =
        request("hub-enroll", JSONObject().put("capability", capability).put("device_id", deviceId)
            .put("public_key", key.publicKey()).put("signature", key.signature(capability, deviceId, DeviceBootstrapKey.AUTOMATIC_PROTOCOL)),
            prefix = "/api/v1/otp", closeConnection = true)

    suspend fun challenge(phone: String, deviceId: String, publicKey: String): JSONObject =
        request("challenge", JSONObject().apply {
            if (phone.isNotBlank()) put("driver_phone", phone)
            put("device_id", deviceId).put("public_key", publicKey)
        }, closeConnection = true)

    suspend fun poll(nonce: String, deviceId: String, signature: String): JSONObject =
        requestWithRetry("poll", JSONObject().put("nonce", nonce).put("device_id", deviceId).put("signature", signature))

    suspend fun prove(origin: String, nonce: String, hubToken: String): JSONObject {
        com.example.network.BarProContract.webhookTokenError(hubToken)?.let { error(it) }
        return requestWithRetry("prove", JSONObject().put("origin", origin).put("nonce", nonce), hubToken)
    }

    private suspend fun requestWithRetry(path: String, payload: JSONObject, token: String = "",
        prefix: String = "/api/v1/otp/bootstrap"): JSONObject = try {
        request(path, payload, token, prefix, clientOverride = retryableHttp, closeConnection = false)
    } catch (_: IOException) {
        // Retry once on a fresh TCP socket when a pooled HTTP/1.1 connection closes mid-stream (EOFException).
        request(path, payload, token, prefix, clientOverride = retryableHttp, closeConnection = true)
    }

    private suspend fun request(path: String, payload: JSONObject, token: String = "",
        prefix: String = "/api/v1/otp/bootstrap", clientOverride: OkHttpClient = http,
        closeConnection: Boolean = false): JSONObject = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(base.newBuilder().encodedPath("$prefix/$path").build())
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
        if (closeConnection) builder.header("Connection", "close")
        if (token.isNotEmpty()) builder.header("X-OTP-Webhook-Token", token)
        val builtRequest = builder.build()
        com.example.network.HubRequestAuth.client(clientOverride, token, base, hubKey).newCall(builtRequest).execute().use { response ->
            com.example.network.ServerClock.observeHttpDate(null, response.headers.getDate("Date")?.time, builtRequest.url.host)
            if (response.code != 200) throw BootstrapHttpException(response.code, path)
            val body = requireNotNull(response.body)
            require(body.contentLength() <= 16_384)
            val source = body.source()
            require(!source.request(16_385))
            JSONObject(source.readByteArray().toString(Charsets.UTF_8))
        }
    }
}

class BootstrapHttpException(val code: Int, val path: String = "") : IllegalStateException(when (code) {
    401 -> "توکن یا تنظیمات هاب پذیرفته نشد؛ نصب خصوصی هاب را بررسی کنید."
    403 -> when (path) {
        "challenge" -> "این شماره در ناوگان بارپرو ثبت یا فعال نیست؛ شمارهٔ ثبت‌شده را بررسی و با پشتیبانی هماهنگ کنید."
        // The server rejects a carrier proof whose SIM origin differs from the challenge:
        // the SMS left from another SIM, so the driver phone never proves.
        "prove" -> "پیامک تأیید از سیم‌کارت دیگری ارسال شده است؛ سیم‌کارت همان شمارهٔ واردشده باید پیامک را بفرستد."
        "poll" -> "تأیید دستگاه یا وضعیت ثبت‌نام راننده پذیرفته نشد؛ دوباره تلاش کنید."
        "hub-enroll" -> "مجوز نصب خصوصی هاب پذیرفته نشد؛ نصب خصوصی هاب را بررسی کنید."
        else -> "تأیید سیم‌کارت پذیرفته نشد؛ شماره ثبت‌شده و تنظیمات هاب را بررسی کنید."
    }
    410 -> "مهلت فعال‌سازی تمام شده؛ دوباره تلاش کنید."
    422 -> "درخواست فعال‌سازی نامعتبر است؛ شماره و نام راننده را بررسی و دوباره تلاش کنید."
    429 -> "تعداد تلاش‌ها زیاد است؛ چند دقیقه دیگر دوباره تلاش کنید."
    else -> "ارتباط فعال‌سازی برقرار نشد؛ دوباره تلاش کنید."
})
