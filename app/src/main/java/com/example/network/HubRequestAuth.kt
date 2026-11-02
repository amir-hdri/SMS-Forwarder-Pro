package com.example.network

import android.util.Base64
import com.example.enrollment.DeviceBootstrapKey
import com.example.enrollment.HubInstallContract
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okio.Buffer
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/** Network interceptor signs the final bytes independently for each attempt, including OkHttp follow-ups. */
object HubRequestAuth {
    fun client(base: OkHttpClient, token: String, server: HttpUrl, key: DeviceBootstrapKey): OkHttpClient {
        if (!token.startsWith("HUB1_")) return base
        return base.newBuilder().followRedirects(false).followSslRedirects(false).addNetworkInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            val allowed = path == "/api/v1/otp/bootstrap/prove" ||
                Regex("/api/v1/otp/(sms-forwarder|sms-gateway)(/09[0-9]{9})?").matches(path)
            if (!HubInstallContract.TOKEN.matches(token) || !request.url.isHttps ||
                !HubInstallContract.sameOrigin(request.url, server) || request.url.query != null ||
                request.method != "POST" || !allowed) throw IOException("Scoped Hub request destination rejected")
            val body = Buffer().apply { request.body?.writeTo(this) }.readByteArray()
            val device = key.hubDeviceId()
            val timestamp = (System.currentTimeMillis() / 1000L).toString()
            val nonce = Base64.encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) },
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val hash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
            val message = "barpro-hub-request-v1\n$device\n${request.method}\n$path\n$timestamp\n$nonce\n$hash"
            val signed = request.newBuilder().header(BarProContract.TOKEN_HEADER, token)
                .header("X-OTP-Hub-Device", device).header("X-OTP-Hub-Timestamp", timestamp)
                .header("X-OTP-Hub-Nonce", nonce).header("X-OTP-Hub-Signature", key.sign(message.toByteArray(Charsets.UTF_8)))
                .build()
            chain.proceed(signed)
        }.build()
    }
}
