package com.example.update

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface ConfigFetch {
    data class Applied(val snapshot: RemoteConfigSnapshot) : ConfigFetch
    /** The document verified but is not newer than what we already run — the steady state. */
    object NoChange : ConfigFetch
    data class Failed(val reasonFa: String, val isSecurityRejection: Boolean = false) : ConfigFetch
}

/**
 * Fetches and verifies the operator's signed settings document.
 *
 * A fetch failure or a signature rejection never degrades the device: the caller keeps whatever
 * configuration is already in place. There is deliberately no "unsigned fallback" — without a
 * valid signature the document is discarded, because this channel can move the OTP endpoint and is
 * therefore the most attractive thing on the wire to tamper with.
 */
class RemoteConfigClient(
    private val publicKeyBase64: String,
    private val httpClient: OkHttpClient = defaultClient()
) {

    suspend fun fetch(url: String, audience: String, knownVersion: Long): ConfigFetch =
        withContext(Dispatchers.IO) {
            if (publicKeyBase64.isBlank()) {
                return@withContext ConfigFetch.Failed(
                    "کلید عمومی امضای تنظیمات در این نسخه تنظیم نشده است.",
                    isSecurityRejection = true
                )
            }
            val document = try {
                val request = Request.Builder().url(url).header("User-Agent", AppUpdateManager.USER_AGENT).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext ConfigFetch.Failed("دریافت تنظیمات ناموفق بود (HTTP ${response.code}).")
                    }
                    response.peekBody(SignedPayload.MAX_DOCUMENT_BYTES.toLong()).string()
                }
            } catch (error: IOException) {
                return@withContext ConfigFetch.Failed("ارتباط با سرور تنظیمات برقرار نشد.")
            }
            if (document.isBlank()) {
                return@withContext ConfigFetch.Failed("پاسخ سرور تنظیمات خالی بود.")
            }
            verify(document, audience, knownVersion)
        }

    /** Split out so a stored last-known-good document can be re-verified without any network I/O. */
    fun verify(document: String, audience: String, knownVersion: Long): ConfigFetch {
        val payload = try {
            SignedPayload.verify(document, publicKeyBase64)
        } catch (error: SignedPayload.InvalidSignatureException) {
            return ConfigFetch.Failed(
                "امضای فایل تنظیمات معتبر نیست؛ تنظیمات قبلی حفظ شد.",
                isSecurityRejection = true
            )
        }
        return try {
            ConfigFetch.Applied(
                RemoteConfig.parse(
                    payload = payload,
                    audience = audience,
                    knownVersion = knownVersion,
                    nowSeconds = System.currentTimeMillis() / 1000L
                )
            )
        } catch (error: RemoteConfig.RejectedException) {
            // Most commonly "not newer than N", which is normal on every poll after the first.
            ConfigFetch.NoChange
        }
    }

    companion object {
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
