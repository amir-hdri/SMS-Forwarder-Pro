package com.example

import com.sun.net.httpserver.HttpsServer
import com.sun.net.httpserver.HttpsConfigurator
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Owned TCP/TLS fixture. Its synthetic certificate is trusted explicitly; verification remains enabled. */
class OwnedHubTlsServer : AutoCloseable {
    private val password = "owned-synthetic-fixture".toCharArray()
    private val keys = KeyStore.getInstance("PKCS12").apply {
        load(OwnedHubTlsServer::class.java.getResourceAsStream("/hub-owned-loopback.p12"), password)
    }
    private val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
    private val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }
    private val tls = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, trust.trustManagers, null) }
    private val executor = Executors.newCachedThreadPool()
    val server: HttpsServer = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        httpsConfigurator = HttpsConfigurator(tls)
        this.executor = this@OwnedHubTlsServer.executor
        start()
    }
    val root: String get() = "https://127.0.0.1:${server.address.port}"
    val client: OkHttpClient = OkHttpClient.Builder().sslSocketFactory(tls.socketFactory,
        trust.trustManagers.single() as X509TrustManager).build()
    override fun close() { server.stop(0); executor.shutdownNow() }
}
