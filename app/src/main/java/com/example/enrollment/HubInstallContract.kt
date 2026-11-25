package com.example.enrollment

import com.example.data.model.AuthType
import com.example.data.model.ForwardConfig
import com.example.network.BarProContract
import com.example.network.ManagedTransport
import com.example.utils.SmsParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

// Deliberately no data-class toString: the private capability and credential do not belong in logs.
class HubInstallProfile(val server: HttpUrl, val capability: String, val expiresAt: Long)
class HubInstallCredentials(val deviceId: String, val endpoint: String, val token: String,
    val hubMci: String, val hubIrancell: String) {
    fun applyTo(config: ForwardConfig): ForwardConfig {
        val authorizedBefore = config.setupCompleted || BarProContract.token(config).isNotBlank()
        return config.copy(endpointUrl = endpoint, authType = AuthType.CUSTOM_HEADER,
            authHeaderKey = BarProContract.TOKEN_HEADER, authHeaderValue = token, forwarderSecret = token,
            deviceIdentifier = deviceId, driverPhone = "", driverId = "",
            fallbackServerPhoneNumber = hubMci, hubIrancellPhoneNumber = hubIrancell,
            isEncryptionEnabled = false, allowCleartextTransport = false, setupCompleted = true,
            isMasterEnabled = if (authorizedBefore) config.isMasterEnabled else true,
            userConsentGiven = if (authorizedBefore) config.userConsentGiven else true)
    }
}

object HubInstallContract {
    const val ASSET_NAME = "barpro_hub_install.json"
    val TOKEN = Regex("HUB1_[0-9a-f]{16}_[0-9a-f]{64}")
    val DEVICE = Regex("hub_[0-9a-f]{32}")
    private val CAPABILITY = Regex("[A-Za-z0-9_-]{43}")

    fun capabilityHash(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it) }

    fun sameOrigin(left: HttpUrl, right: HttpUrl): Boolean =
        left.scheme == right.scheme && left.host == right.host && left.port == right.port

    private fun secureRoot(value: String): HttpUrl {
        val url = ManagedTransport.canonical(requireNotNull(value.toHttpUrlOrNull()))
        require(ManagedTransport.permitsForwarderTransport(url) && url.username.isEmpty() && url.password.isEmpty() &&
            url.query == null && url.fragment == null && url.encodedPath == "/")
        return url
    }

    fun parseProfile(document: String, role: String, trustedServer: HttpUrl, nowSeconds: Long): HubInstallProfile {
        require(role == "HUB" && document.length <= 4096)
        val json = JSONObject(document)
        require(json.keys().asSequence().toSet() == setOf("protocol", "server", "capability", "expires_at"))
        require(json.getString("protocol") == "barpro-hub-install-v1")
        val server = secureRoot(json.getString("server"))
        val trusted = ManagedTransport.canonical(trustedServer)
        require(ManagedTransport.permitsForwarderTransport(trusted) && sameOrigin(server, trusted))
        val capability = json.getString("capability")
        require(CAPABILITY.matches(capability))
        val expires = json.getLong("expires_at")
        require(expires > nowSeconds && expires - nowSeconds <= 86400L)
        return HubInstallProfile(server, capability, expires)
    }

    fun parseCredentials(document: String, server: HttpUrl, deviceId: String): HubInstallCredentials {
        require(document.length <= 16384 && DEVICE.matches(deviceId))
        val json = JSONObject(document)
        require(json.getString("protocol") == "barpro-hub-v1" && json.getString("device_id") == deviceId)
        val token = json.getString("webhook_token")
        require(TOKEN.matches(token))
        val endpoint = ManagedTransport.canonical(requireNotNull(json.getString("endpoint_url").toHttpUrlOrNull()))
        require(ManagedTransport.permitsForwarderTransport(endpoint) && sameOrigin(endpoint, ManagedTransport.canonical(server)) && endpoint.username.isEmpty() && endpoint.password.isEmpty() &&
            endpoint.query == null && endpoint.fragment == null && endpoint.encodedPath == BarProContract.PATH)
        fun phone(name: String): String {
            val raw = json.optString(name, "")
            if (raw.isBlank()) return ""
            val normalized = SmsParser.normalizePhoneNumber(raw)
            require(normalized.matches(Regex("09[0-9]{9}")))
            return normalized
        }
        val mci = phone("hub_phone_mci")
        val irancell = phone("hub_phone_irancell")
        require(mci.isNotEmpty() || irancell.isNotEmpty())
        return HubInstallCredentials(deviceId, endpoint.toString(), token, mci, irancell)
    }

    fun isEnrolled(config: ForwardConfig, deviceId: String): Boolean = config.setupCompleted &&
        config.deviceIdentifier == deviceId && TOKEN.matches(BarProContract.token(config))
}
