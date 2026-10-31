package com.example

import android.app.Application
import com.example.data.model.ForwardConfig
import com.example.network.SmsForwarderClient
import com.example.utils.SmsFallbackEnvelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HttpCancellationTest {
    // Resuming the caller at headers would detach cancellation before this
    // body read finishes. Observe the actual read and TCP close, not just Job state.
    @Test fun callerCancellationClosesTheResponseWhileItsBodyIsStalled() = runBlocking {
        val bodyReadStarted = CountDownLatch(1)
        val callCancelled = CountDownLatch(1)
        val connectionClosed = CountDownLatch(1)
        val stalledSocket = AtomicReference<Socket>()
        val server = ServerSocket(0).apply { soTimeout = 4000 }
        val serverThread = thread(isDaemon = true) {
            try {
                server.accept().also { stalledSocket.set(it) }.use { socket ->
                    socket.soTimeout = 10_000
                    val input = socket.getInputStream()
                    val headers = StringBuilder()
                    while (!headers.endsWith("\r\n\r\n")) {
                        val byte = input.read(); if (byte < 0) return@thread
                        headers.append(byte.toChar())
                        check(headers.length < 8192)
                    }
                    val size = Regex("(?im)^Content-Length: (\\d+)").find(headers)?.groupValues?.get(1)?.toInt() ?: 0
                    repeat(size) { if (input.read() < 0) return@thread }
                    socket.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                            "Content-Length: 256\r\nConnection: close\r\n\r\n{").toByteArray(Charsets.US_ASCII))
                        flush()
                    }
                    // Send no more body bytes until client cancellation closes the socket.
                    if (input.read() == -1) connectionClosed.countDown()
                }
            } catch (_: Exception) { }
        }
        val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun responseBodyStart(call: Call) { bodyReadStarted.countDown() }
            override fun canceled(call: Call) { callCancelled.countDown() }
        }).build()
        var pending: Job? = null
        try {
            val config = ForwardConfig(endpointUrl = "http://127.0.0.1:${server.localPort}/api/v1/otp/sms-forwarder",
                authHeaderValue = "synthetic-cancellation-token", allowCleartextTransport = true,
                timeoutSeconds = 10, maxRetries = 0)
            val frame = SmsFallbackEnvelope.encode("09120000001", System.currentTimeMillis(), "12345", "synthetic-cancellation-token")
            val request = async(Dispatchers.IO) { SmsForwarderClient(http).relayGatewaySms("09120000001", frame, config) }
            pending = request
            assertTrue("The client must begin reading the real response body before cancellation", bodyReadStarted.await(4, TimeUnit.SECONDS))
            request.cancel()
            assertTrue("Cancellation after headers must reach the body-reading OkHttp Call", callCancelled.await(2, TimeUnit.SECONDS))
            val completed = withTimeoutOrNull(2000) { request.join(); true } ?: false
            assertTrue("Cancellation must finish the body-reading coroutine promptly", completed)
            assertTrue("The peer must see the stalled response connection close", connectionClosed.await(2, TimeUnit.SECONDS))
        } finally {
            pending?.cancel()
            http.dispatcher.cancelAll()
            // A headers-complete Call may no longer belong to the dispatcher.
            // Close the accepted peer too, and never replace a primary assertion.
            runCatching { stalledSocket.get()?.close() }
            withTimeoutOrNull(2000) { pending?.join() }
            server.close()
            serverThread.join(1000)
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }

    // A real TCP request must be outstanding when cancellation occurs. This
    // catches removal of Call.cancel(), even if the coroutine still reports cancelled.
    @Test fun callerCancellationClosesTheOutstandingOkHttpRequest() = runBlocking {
        val requestArrived = CountDownLatch(1)
        val callCancelled = CountDownLatch(1)
        val connectionClosed = CountDownLatch(1)
        val server = ServerSocket(0).apply { soTimeout = 4000 }
        val serverThread = thread(isDaemon = true) {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 4000
                    val input = socket.getInputStream()
                    val headers = StringBuilder()
                    while (!headers.endsWith("\r\n\r\n")) {
                        val byte = input.read(); if (byte < 0) return@thread
                        headers.append(byte.toChar())
                        check(headers.length < 8192)
                    }
                    val size = Regex("(?im)^Content-Length: (\\d+)").find(headers)?.groupValues?.get(1)?.toInt() ?: 0
                    repeat(size) { if (input.read() < 0) return@thread }
                    requestArrived.countDown()
                    if (input.read() == -1) connectionClosed.countDown()
                }
            } catch (_: Exception) { }
        }
        val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { callCancelled.countDown() }
        }).build()
        try {
            val config = ForwardConfig(endpointUrl = "http://127.0.0.1:${server.localPort}/api/v1/otp/sms-forwarder",
                authHeaderValue = "synthetic-cancellation-token", allowCleartextTransport = true, maxRetries = 0)
            val frame = SmsFallbackEnvelope.encode("09120000001", System.currentTimeMillis(), "12345", "synthetic-cancellation-token")
            val pending = async(Dispatchers.IO) { SmsForwarderClient(http).relayGatewaySms("09120000001", frame, config) }
            assertTrue("The controlled server must receive the actual request before cancellation", requestArrived.await(4, TimeUnit.SECONDS))
            pending.cancelAndJoin()
            assertTrue("Cancellation must reach the actual outstanding OkHttp Call", callCancelled.await(2, TimeUnit.SECONDS))
            assertTrue("The peer must see its pending TCP connection close", connectionClosed.await(2, TimeUnit.SECONDS))
        } finally {
            server.close();serverThread.join(1000)
            http.connectionPool.evictAll();http.dispatcher.executorService.shutdown()
        }
    }
}
