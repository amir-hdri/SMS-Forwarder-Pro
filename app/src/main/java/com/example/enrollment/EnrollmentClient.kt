package com.example.enrollment

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        val request = Request.Builder().url(link.server.newBuilder().encodedPath("/api/v1/otp/enroll").build())
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
}
