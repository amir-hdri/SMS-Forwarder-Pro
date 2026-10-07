package com.example.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import java.net.InetAddress
import java.net.Proxy
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.OkHttpClient

/**
 * Guarantees that UTCMS relay traffic egresses from the driver's REAL Iranian
 * cellular (non-VPN) network and never through a VPN/proxy the driver may have
 * enabled — so the egress IP cannot change to another country.
 *
 * Mechanism: request an [android.net.Network] that has
 * `TRANSPORT_CELLULAR + NET_CAPABILITY_INTERNET + NET_CAPABILITY_NOT_VPN`, then
 * bind the per-request OkHttp client's socket factory AND DNS resolver to that
 * physical network, with [Proxy.NO_PROXY]. Binding to the underlying NOT_VPN
 * network bypasses any app/always-on VPN overlay and any Wi-Fi HTTP proxy.
 *
 * Fail-closed contract: if no NOT_VPN cellular network can be acquired (airplane
 * mode, cellular off, or an always-on "block connections without VPN" lockdown),
 * [acquire] returns `null`. Callers MUST then abandon device submission and let
 * the server fall back to its own egress — we never silently leak through a VPN.
 * (Under VPN lockdown, acquisition may still succeed but the later socket connect
 * fails; the HTTP layer surfaces that error and the same fallback applies.)
 *
 * All APIs used here are available at minSdk 24. Requires the
 * `CHANGE_NETWORK_STATE` permission to request a specific network while on Wi-Fi.
 */
object CellularEgress {

    private const val TAG = "CellularEgress"
    const val DEFAULT_ACQUIRE_TIMEOUT_MS = 8_000L

    /**
     * Acquires a non-VPN cellular [CellularEgressSession] or returns `null`
     * (fail-closed) if none becomes available within [timeoutMs].
     *
     * The returned session holds the network callback open to keep the network
     * usable; callers MUST [CellularEgressSession.close] it (use `.use { }`).
     */
    suspend fun acquire(
        context: Context,
        timeoutMs: Long = DEFAULT_ACQUIRE_TIMEOUT_MS,
    ): CellularEgressSession? {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (cont.isActive) cont.resume(CellularEgressSession(cm, network, this))
                    }

                    override fun onUnavailable() {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                try {
                    cm.requestNetwork(request, callback)
                } catch (e: SecurityException) {
                    // CHANGE_NETWORK_STATE missing/denied — cannot bind, fail closed.
                    Log.w(TAG, "requestNetwork denied; cannot guarantee non-VPN egress: ${e.message}")
                    if (cont.isActive) cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                // On timeout/cancellation, release the pending request so we never leak it.
                cont.invokeOnCancellation {
                    runCatching { cm.unregisterNetworkCallback(callback) }
                }
            }
        }
    }

    /**
     * True when the device's CURRENT default network routes through a VPN (or
     * lacks the NOT_VPN capability). Advisory only — for driver-facing warnings
     * and diagnostics; the hard guarantee is [acquire]'s explicit NOT_VPN bind.
     */
    fun isVpnActive(context: Context): Boolean {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }
}

/**
 * A held non-VPN cellular network. Binds OkHttp sockets + DNS to this network so
 * every request leaves via the driver's real Iranian cellular IP. [close]
 * releases the underlying network request; always use within `.use { }`.
 */
class CellularEgressSession internal constructor(
    private val connectivityManager: ConnectivityManager,
    val network: Network,
    private val callback: ConnectivityManager.NetworkCallback,
) : AutoCloseable {

    /**
     * Returns a client derived from [base] whose sockets, DNS lookups, and proxy
     * are pinned to this cellular network. DNS is resolved THROUGH the network to
     * avoid a VPN DNS leak; proxy is forced to [Proxy.NO_PROXY] so a system/Wi-Fi
     * HTTP proxy is ignored.
     */
    fun bind(base: OkHttpClient): OkHttpClient =
        base.newBuilder()
            .socketFactory(network.socketFactory)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    network.getAllByName(hostname).toList()
            })
            .proxy(Proxy.NO_PROXY)
            .build()

    override fun close() {
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
    }
}
