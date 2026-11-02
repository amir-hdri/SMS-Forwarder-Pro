package com.example.enrollment

import com.example.utils.CarrierDetector
import com.example.utils.SmsParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import org.json.JSONObject
import org.json.JSONArray

class BootstrapNeedsPermissions : IllegalStateException("برای تأیید سیم‌کارت، مجوزهای دریافت و ارسال پیامک را تأیید کنید.")
class BootstrapPending : IllegalStateException("در انتظار تأیید واقعی هاب؛ برنامه خودکار ادامه می‌دهد.")

/** One key-bound challenge survives restart; modem/proof deadlines govern bounded retransmission. */
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
    private val pause: suspend () -> Unit = { delay(3_000) },
    private val smsState: ((String) -> BootstrapSmsDispatch.State?)? = null
) {
    private val mutex = Mutex()

    suspend fun start(phone: String, deviceId: String): EnrollmentCredentials = mutex.withLock {
        val normalized = SmsParser.normalizePhoneNumber(phone)
        require(normalized.isEmpty() || normalized.matches(Regex("09[0-9]{9}")))
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
            val protocol = response.getString("protocol")
            require(protocol == if (normalized.isEmpty()) DeviceBootstrapKey.AUTOMATIC_PROTOCOL else DeviceBootstrapKey.PROTOCOL)
            val nonce = response.getString("nonce")
            require(nonce.matches(Regex("[A-Za-z0-9_-]{43}")))
            val ttl = response.getLong("expires_in")
            require(ttl in 1..900)
            fun hub(name: String): String = response.optString(name).also {
                require(it.isEmpty() || it.matches(Regex("09[0-9]{9}")))
            }
            val route = CarrierDetector.resolveRoute(normalized, hub("hub_phone_mci"), hub("hub_phone_irancell"))
            val destination = route.primaryNumber
            require(destination.matches(Regex("09[0-9]{9}")))
            pending.put("nonce", nonce).put("expires_at", now() + ttl * 1000).put("destination", destination)
                .put("public_key", publicKey).put("server", server.toString()).put("sent", false).put("protocol", protocol)
                .put("destinations", JSONArray(listOf(route.primaryNumber, route.failoverNumber).filter(String::isNotBlank).distinct()))
            stage(pending.toString())
        }
        require(pending.getString("public_key") == publicKey && pending.getString("server") == server.toString()) {
            "هویت امن دستگاه تغییر کرده؛ پس از پایان مهلت، فعال‌سازی را دوباره شروع کنید."
        }
        val nonce = pending.getString("nonce")
        val protocol = pending.optString("protocol", DeviceBootstrapKey.PROTOCOL)
        require(protocol == if (normalized.isEmpty()) DeviceBootstrapKey.AUTOMATIC_PROTOCOL else DeviceBootstrapKey.PROTOCOL)
        repeat(60) {
            if (now() >= pending.getLong("expires_at")) { stage(null); throw BootstrapHttpException(410) }
            val attempt = smsState?.invoke(nonce)
            val needsDispatch = if (smsState == null) !pending.getBoolean("sent")
                else attempt == null || (now() >= attempt.retryAt && attempt.attempts < com.example.utils.SmsDispatch.MAX_ATTEMPTS)
            if (needsDispatch) {
                progress("در حال ارسال پیامک تأیید از سیم‌کارت انتخاب‌شده…")
                pending.put("sent", true); stage(pending.toString())
                val destinations = pending.optJSONArray("destinations")
                val destination = if (destinations != null && destinations.length() > 0)
                    destinations.getString((attempt?.attempts ?: 0) % destinations.length()) else pending.getString("destination")
                val sent = try { sendSms(destination, nonce) }
                catch (error: Exception) {
                    pending.put("sent", false); stage(pending.toString()); throw error
                }
                if (!sent) {
                    pending.put("sent", false); stage(pending.toString())
                    error("ارسال پیامک انجام نشد؛ سیم‌کارت و اعتبار پیامک را بررسی کنید.")
                }
            }
            progress(attempt?.message ?: "در انتظار تأیید پیامک توسط هاب…")
            val grant = try { poll(nonce, deviceId, key.signature(nonce, deviceId, protocol)) }
            catch (error: BootstrapHttpException) {
                if (error.code == 410) stage(null)
                throw error
            }
            if (grant.optString("status") == "granted") {
                require(grant.getString("protocol") == protocol)
                val credential = EnrollmentContract.parseCredentials(key.decrypt(grant, nonce, deviceId), server)
                require(normalized.isEmpty() || credential.driverPhone == normalized) { "پاسخ فعال‌سازی متعلق به این شماره نیست." }
                return@withLock credential
            }
            require(grant.optString("status") == "pending")
            pause()
        }
        throw BootstrapPending()
    }

    fun pendingPhone(): String? = loadStage()?.let { JSONObject(it).optString("phone").takeIf(String::isNotBlank) }
}
