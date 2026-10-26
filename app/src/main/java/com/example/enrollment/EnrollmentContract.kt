package com.example.enrollment

import com.example.data.model.AuthType
import com.example.data.model.ForwardConfig
import com.example.network.BarProContract
import com.example.utils.SmsParser
import java.net.URI
import java.net.URLDecoder
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

// Deliberately no data-class toString: neither a ticket nor a credential belongs in logs.
class EnrollmentLink(val ticket: String, val server: HttpUrl)
class EnrollmentCredentials(
    val driverPhone: String,
    val endpointUrl: String,
    val webhookToken: String,
    val hubMci: String,
    val hubIrancell: String
) {
    fun applyTo(config: ForwardConfig, deviceId: String): ForwardConfig = config.copy(
        driverPhone = driverPhone, endpointUrl = endpointUrl,
        authType = AuthType.CUSTOM_HEADER, authHeaderKey = BarProContract.TOKEN_HEADER,
        authHeaderValue = webhookToken, forwarderSecret = webhookToken,
        fallbackServerPhoneNumber = hubMci, hubIrancellPhoneNumber = hubIrancell,
        deviceIdentifier = deviceId, primarySmsRelayEnabled = true,
        isEncryptionEnabled = false, allowCleartextTransport = endpointUrl.startsWith("http://"),
        setupCompleted = true, userConsentGiven = true, isMasterEnabled = true,
        healthCheckIntervalMinutes = 5,
        showForegroundNotification = true
    )

    internal fun document(): String = JSONObject().apply {
        put("protocol", EnrollmentContract.PROTOCOL)
        put("driver_phone", driverPhone)
        put("endpoint_url", endpointUrl)
        put("webhook_token", webhookToken)
        put("hub_phone_mci", hubMci)
        put("hub_phone_irancell", hubIrancell)
    }.toString()
}

object EnrollmentContract {
    const val PROTOCOL = "barpro-driver-v1"

    /** Persisted activation identity distinguishes imported Hub routes from pre-enrollment defaults. */
    fun hubNumbers(config: ForwardConfig, buildMci: String, buildIrancell: String): com.example.utils.HubNumbers {
        val phone = SmsParser.normalizePhoneNumber(config.driverPhone)
        val enrolled = config.setupCompleted && BarProContract.token(config).matches(Regex("[0-9a-f]{64}")) &&
            config.endpointUrl.toHttpUrlOrNull()?.encodedPath == "${BarProContract.PATH}/$phone"
        return if (enrolled) com.example.utils.HubNumbers(config.fallbackServerPhoneNumber, config.hubIrancellPhoneNumber)
        else com.example.utils.CarrierDetector.resolveHubNumbers(buildMci, buildIrancell,
            config.fallbackServerPhoneNumber, config.hubIrancellPhoneNumber)
    }

    private fun origin(url: HttpUrl): String = "${url.scheme}://${url.host}:${url.port}"

    fun trustedServer(defaultEndpoint: String): HttpUrl {
        val url = requireNotNull(defaultEndpoint.toHttpUrlOrNull()) { "Enrollment unavailable" }
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
        return url.newBuilder().encodedPath("/").build()
    }

    fun parseLink(value: String, defaultEndpoint: String): EnrollmentLink {
        require(value.length <= 4096) { "Invalid activation link" }
        val uri = URI(value)
        require(uri.scheme == "barpro-forwarder" && uri.rawAuthority == "enroll" &&
            uri.rawPath.isNullOrEmpty() && uri.rawQuery == null) { "Invalid activation link" }
        val fields = requireNotNull(uri.rawFragment) { "Invalid activation link" }.split('&')
            .map { field ->
                val parts = field.split('=', limit = 2)
                require(parts.size == 2)
                URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
            }
        require(fields.size == 2 && fields.map { it.first }.toSet() == setOf("ticket", "server"))
        val ticket = fields.single { it.first == "ticket" }.second
        require(ticket.length in 20..1024 && ticket.all { it.code in 33..126 })
        val server = requireNotNull(fields.single { it.first == "server" }.second.toHttpUrlOrNull())
        require(server.username.isEmpty() && server.password.isEmpty() && server.query == null &&
            server.fragment == null && server.encodedPath == "/")
        require(origin(server) == origin(trustedServer(defaultEndpoint))) { "Untrusted activation server" }
        return EnrollmentLink(ticket, server)
    }

    fun parseCredentials(document: String, server: HttpUrl): EnrollmentCredentials {
        require(document.length <= 16_384)
        val json = JSONObject(document)
        require(json.getString("protocol") == PROTOCOL)
        val phone = SmsParser.normalizePhoneNumber(json.getString("driver_phone"))
        require(phone.matches(Regex("09[0-9]{9}")))
        val token = json.getString("webhook_token")
        require(token.matches(Regex("[0-9a-f]{64}")))
        val endpoint = requireNotNull(json.getString("endpoint_url").toHttpUrlOrNull())
        require(origin(endpoint) == origin(server) && endpoint.username.isEmpty() && endpoint.password.isEmpty() &&
            endpoint.query == null && endpoint.fragment == null)
        // Scoped credentials are never sent to a global or Hub webhook.
        require(endpoint.encodedPath == "${BarProContract.PATH}/$phone")
        fun hub(name: String): String {
            val raw = json.optString(name, "")
            if (raw.isEmpty()) return ""
            val normalized = SmsParser.normalizePhoneNumber(raw)
            require(normalized.matches(Regex("09[0-9]{9}")))
            return normalized
        }
        val mci = hub("hub_phone_mci")
        val irancell = hub("hub_phone_irancell")
        require(mci.isNotEmpty() || irancell.isNotEmpty()) { "No Hub SMS route available" }
        return EnrollmentCredentials(phone, endpoint.toString(), token, mci, irancell)
    }

    fun permissionsAllowActivation(role: String, receiveSms: Boolean, sendSms: Boolean): Boolean =
        receiveSms && (role != "DRIVER" || sendSms)
}
