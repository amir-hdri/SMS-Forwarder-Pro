package com.example.network

import com.example.data.model.ForwardConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Upgrade only the verified deployment's legacy origin; never confer trust on another host. */
object ManagedTransport {
    const val CENTRAL_HOST = "87.107.160.251"

    fun canonical(url: HttpUrl): HttpUrl =
        if (url.host == CENTRAL_HOST && url.scheme == "http" && url.port == 80)
            url.newBuilder().scheme("https").port(443).build()
        else url

    fun endpoint(value: String): String = value.toHttpUrlOrNull()?.let { canonical(it).toString() } ?: value

    fun configuration(config: ForwardConfig): ForwardConfig {
        val effective = endpoint(config.endpointUrl)
        return config.copy(endpointUrl = effective,
            allowCleartextTransport = if (effective != config.endpointUrl) false else config.allowCleartextTransport,
            configManifestUrl = config.configManifestUrl.ifBlank { com.example.BuildConfig.DEFAULT_CONFIG_URL },
            updateManifestUrl = config.updateManifestUrl.ifBlank { com.example.BuildConfig.DEFAULT_UPDATE_URL })
    }

    fun initialConfiguration(): ForwardConfig = configuration(ForwardConfig(
        endpointUrl = com.example.BuildConfig.DEFAULT_ENDPOINT_URL.ifBlank { ForwardConfig().endpointUrl }))
}
