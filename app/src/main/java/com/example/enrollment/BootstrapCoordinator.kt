package com.example.enrollment

import com.example.utils.CarrierDetector
import com.example.utils.SmsParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import org.json.JSONObject

class BootstrapNeedsPermissions : IllegalStateException("برای تأیید سیم‌کارت، مجوزهای دریافت و ارسال پیامک را تأیید کنید.")

/** A persisted sent marker is written before modem handoff; retries poll without resending SMS. */
class BootstrapCoordinator(
    private val server: HttpUrl,
    private val key: DeviceBootstrapKey,
    private val challenge: suspend (String, String, String) -> JSONObject,
    private val poll: suspend (String, String, String) -> JSONObject,
    private val stage: (String?) -> Unit,
    private val loadStage: () -> String?,
    private val permissionsGranted: () -> Boolean,
    private val sendSms: (String, String) -> Boolean,
    private val progress: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend () -> Unit = { delay(3_000) }
) {
    private val mutex = Mutex()

    suspend fun start(phone: String, deviceId: String): EnrollmentCredentials = mutex.withLock {
        val normalized = SmsParser.normalizePhoneNumber(phone)
        require(normalized.matches(Regex("09[0-9]{9}")))
        require(deviceId.matches(Regex("[A-Za-z0-9_-]{8,128}")))
        val pending = loadStage()?.let(::JSONObject)?.takeIf {
            it.optString("phone") == normalized && it.optString("device_id") == deviceId &&
                (it.optLong("expires_at", Long.MAX_VALUE) > now())
        } ?: JSONObject().put("phone", normalized).put("device_id", deviceId)
        stage(pending.toString())
        if (!permissionsGranted()) throw BootstrapNeedsPermissions()
        val publicKey = key.publicKey()
        if (!pending.has("nonce")) {
            progress("در حال دریافت درخواست تأیید سیم‌کارت…")
            val response = challenge(normalized, deviceId, publicKey)
            require(response.getString("protocol") == DeviceBootstrapKey.PROTOCOL)
            val nonce = response.getString("nonce")
            require(nonce.matches(Regex("[A-Za-z0-9_-]{43}")))
            val ttl = response.getLong("expires_in")
            require(ttl in 1..900)
            fun hub(name: String): String = response.optString(name).also {
                require(it.isEmpty() || it.matches(Regex("09[0-9]{9}")))
            }
            val destination = CarrierDetector.resolveRoute(normalized, hub("hub_phone_mci"), hub("hub_phone_irancell")).primaryNumber
            require(destination.matches(Regex("09[0-9]{9}")))
            pending.put("nonce", nonce).put("expires_at", now() + ttl * 1000).put("destination", destination)
                .put("public_key", publicKey).put("server", server.toString()).put("sent", false)
            stage(pending.toString())
        }
        require(pending.getString("public_key") == publicKey && pending.getString("server") == server.toString()) {
            "هویت امن دستگاه تغییر کرده؛ پس از پایان مهلت، فعال‌سازی را دوباره شروع کنید."
        }
        val nonce = pending.getString("nonce")
        if (!pending.getBoolean("sent")) {
            progress("در حال ارسال پیامک تأیید از سیم‌کارت انتخاب‌شده…")
            pending.put("sent", true)
            stage(pending.toString())
            val sent = try { sendSms(pending.getString("destination"), nonce) }
            catch (error: BootstrapSmsNotSent) {
                pending.put("sent", false); stage(pending.toString()); throw error
            }
            if (!sent) {
                pending.put("sent", false); stage(pending.toString())
                error("ارسال پیامک انجام نشد؛ سیم‌کارت و اعتبار پیامک را بررسی کنید.")
            }
        }
        progress("در انتظار تأیید پیامک توسط هاب…")
        repeat(60) {
            if (now() >= pending.getLong("expires_at")) throw BootstrapHttpException(410)
            val grant = poll(nonce, deviceId, key.signature(nonce, deviceId))
            if (grant.optString("status") == "granted") {
                val credential = EnrollmentContract.parseCredentials(key.decrypt(grant, nonce, deviceId), server)
                require(credential.driverPhone == normalized) { "پاسخ فعال‌سازی متعلق به این شماره نیست." }
                return@withLock credential
            }
            require(grant.optString("status") == "pending")
            pause()
        }
        error("تأیید هاب هنوز دریافت نشده؛ اتصال هاب را بررسی و برای ادامه دوباره تلاش کنید.")
    }

    fun pendingPhone(): String? = loadStage()?.let { JSONObject(it).optString("phone").takeIf(String::isNotBlank) }
}
