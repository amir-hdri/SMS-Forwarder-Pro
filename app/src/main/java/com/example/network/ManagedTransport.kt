package com.example.network

import com.example.data.model.ForwardConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Keep the selected Central HTTP origin separate from foreign transport and authority. */
object ManagedTransport {
    const val CENTRAL_HOST = "87.107.160.251"

    fun canonical(url: HttpUrl): HttpUrl =
        if (url.host == CENTRAL_HOST && url.scheme == "https" && url.port == 443)
            url.newBuilder().scheme("http").port(80).build()
        else url

    fun isSelectedHttp(url: HttpUrl): Boolean =
        url.scheme == "http" && url.host == CENTRAL_HOST && url.port == 80

    fun permitsForwarderTransport(url: HttpUrl): Boolean = url.isHttps || isSelectedHttp(url)

    fun endpoint(value: String): String = value.toHttpUrlOrNull()?.let { canonical(it).toString() } ?: value

    fun configuration(config: ForwardConfig): ForwardConfig {
        val effective = endpoint(config.endpointUrl)
        return config.copy(endpointUrl = effective,
            configManifestUrl = config.configManifestUrl.ifBlank { com.example.BuildConfig.DEFAULT_CONFIG_URL },
            updateManifestUrl = config.updateManifestUrl.ifBlank { com.example.BuildConfig.DEFAULT_UPDATE_URL })
    }

    fun initialConfiguration(): ForwardConfig = configuration(ForwardConfig(
        endpointUrl = com.example.BuildConfig.DEFAULT_ENDPOINT_URL.ifBlank { ForwardConfig().endpointUrl }))
}
