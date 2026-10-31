package com.example

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.crypto.SecureStorageManager
import com.example.data.local.AppDatabase
import com.example.data.model.ForwardConfig
import com.example.data.model.ForwardStatus
import com.example.data.repository.SmsForwardRepository
import com.example.network.SmsForwarderClient
import com.example.utils.SmsDispatch
import com.example.utils.SmsFallbackEnvelope
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class NativeRelaySafetyTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun report(status: Int): Intent = Intent().putExtra("format", "3gpp").putExtra("pdu",
        ("0002010C918919020000106201034000000062010340100000" + "%02x".format(status))
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray())

    @Test fun realAndroidDecodesDeliveryStatusWithoutTreatingPendingOrFailureAsReceipt() {
        assertEquals(SmsDispatch.DeliveryReport.DELIVERED, SmsDispatch.deliveryReport(report(0x00)))
        assertEquals(SmsDispatch.DeliveryReport.PENDING, SmsDispatch.deliveryReport(report(0x20)))
        assertEquals(SmsDispatch.DeliveryReport.FAILED, SmsDispatch.deliveryReport(report(0x40)))
        assertEquals(SmsDispatch.DeliveryReport.FAILED, SmsDispatch.deliveryReport(report(0x60)))
        assertEquals(SmsDispatch.DeliveryReport.UNCONFIRMED, SmsDispatch.deliveryReport(report(0x01)))
        assertNull(SmsDispatch.deliveryReport(Intent()))
        assertNull(SmsDispatch.deliveryReport(Intent().putExtra("format", "3gpp").putExtra("pdu", byteArrayOf(0))))
    }

    @Test fun nativePendingIntentKeepsPduAcrossReportsAndCancellationRevokesBothCapabilities() {
        val logId = Long.MAX_VALUE - 314 // No matching outbox row, so this never transmits SMS/HTTP.
        val attempt = System.currentTimeMillis()
        val sent = SmsDispatch.resultIntent(context, logId, attempt, false)
        val delivery = SmsDispatch.resultIntent(context, logId, attempt, true)
        try {
            assertTrue(sent.isImmutable)
            assertFalse(delivery.isImmutable)
            for (status in listOf(0x20, 0x00)) {
                val observed = AtomicReference<Intent>()
                val complete = CountDownLatch(1)
                val fillIn = report(status)
                delivery.send(context, Activity.RESULT_OK, fillIn,
                    PendingIntent.OnFinished { _, intent, _, _, _ -> observed.set(intent); complete.countDown() },
                    Handler(Looper.getMainLooper()))
                assertTrue("The Android framework must complete each reusable delivery callback", complete.await(5, TimeUnit.SECONDS))
                assertArrayEquals(fillIn.getByteArrayExtra("pdu"), observed.get().getByteArrayExtra("pdu"))
                assertEquals(logId, observed.get().getLongExtra(SmsDispatch.LOG_ID, -1))
                assertEquals("barpro-sms://delivered/$logId/$attempt", observed.get().dataString)
            }
            SmsDispatch.cancelAttempt(context, logId, attempt)
            for (callback in listOf(sent, delivery)) assertTrue(
                runCatching { callback.send() }.exceptionOrNull() is PendingIntent.CanceledException)
        } finally { SmsDispatch.cancelAttempt(context, logId, attempt) }
    }

    @Test fun nativeCredentialStorageEncryptsValuesAndRemovesAStaleLegacyFallback() {
        val storage = SecureStorageManager.getInstance(context)
        val originalSecret = storage.getForwarderSecret()
        val originalKey = storage.getSecretEncryptionKey()
        try {
            storage.saveForwarderSecret("synthetic-native-webhook-secret")
            storage.saveSecretEncryptionKey("synthetic-native-encryption-key")
            val outboxKey = storage.getOrCreateOutboxKey()
            val legacy = context.getSharedPreferences("barpro_secure_storage_prefs_fallback", Context.MODE_PRIVATE)
            check(legacy.edit().putString("secure_forwarder_secret", "synthetic-stale-legacy-secret").commit())
            val reopened = SecureStorageManager(context)
            assertEquals("synthetic-native-webhook-secret", reopened.getForwarderSecret())
            assertEquals(outboxKey, reopened.getOrCreateOutboxKey())
            assertFalse(File(context.dataDir, "shared_prefs/barpro_secure_storage_prefs_fallback.xml").exists())
            val disk = File(context.dataDir, "shared_prefs/barpro_secure_storage_prefs.xml").readText()
            for (secret in listOf("synthetic-native-webhook-secret", "synthetic-native-encryption-key", outboxKey))
                assertFalse("Credential values must be encrypted in the actual Android preference file", disk.contains(secret))
        } finally {
            storage.saveForwarderSecret(originalSecret)
            storage.saveSecretEncryptionKey(originalKey)
        }
    }

    @Test fun nativeHubImmediatelyForwardsAValidScopedFrameToTheActualLocalHttpGateway() = runBlocking {
        assumeTrue(BuildConfig.APP_ROLE == "HUB")
        val permission = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} ${Manifest.permission.RECEIVE_SMS}")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(permission).use { it.readBytes() }
        val storage = SecureStorageManager.getInstance(context)
        val originalSecret = storage.getForwarderSecret()
        val originalKey = storage.getSecretEncryptionKey()
        val server = ServerSocket(0).apply { soTimeout = 5_000 }
        val request = AtomicReference<Pair<String, String>>()
        val accepted = CountDownLatch(1)
        val serverThread = thread(isDaemon = true) {
            server.accept().use { socket ->
                socket.soTimeout = 5_000
                val input = socket.getInputStream()
                val headers = StringBuilder()
                while (!headers.endsWith("\r\n\r\n")) {
                    val next = input.read(); check(next >= 0); headers.append(next.toChar()); check(headers.length < 8192)
                }
                val size = Regex("(?im)^Content-Length: (\\d+)").find(headers)?.groupValues?.get(1)?.toInt() ?: 0
                val body = ByteArray(size)
                for (index in body.indices) { val next = input.read(); check(next >= 0); body[index] = next.toByte() }
                request.set(headers.toString() to String(body, Charsets.UTF_8))
                accepted.countDown()
                val reply = "{\"success\":true,\"status\":\"success\",\"otp_detected\":true,\"is_duplicate\":false,\"received_at\":1793300000,\"message\":\"OTP accepted\"}"
                socket.getOutputStream().apply {
                    write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${reply.length}\r\nConnection: close\r\n\r\n$reply")
                        .toByteArray(Charsets.UTF_8)); flush()
                }
            }
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val sharedSecret = "b".repeat(64)
            val repo = SmsForwardRepository(db, SmsForwarderClient(), context)
            repo.saveConfig(ForwardConfig(isMasterEnabled = true, userConsentGiven = true,
                endpointUrl = "http://127.0.0.1:${server.localPort}/api/v1/otp/sms-forwarder",
                authHeaderValue = sharedSecret, allowCleartextTransport = true))
            val now = System.currentTimeMillis()
            val frame = SmsFallbackEnvelope.encode("09120000001", now, "12345",
                SmsFallbackEnvelope.deriveDriverKey(sharedSecret, "09120000001"))
            val started = System.nanoTime()
            val log = repo.processIncomingSms("09120000001", frame, now, "SIM 1")
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue(accepted.await(1, TimeUnit.SECONDS))
            assertEquals(ForwardStatus.SUCCESS, log.status)
            assertTrue("The immediate Hub path must complete within its 3-second deadline, took ${elapsed}ms", elapsed < 3_000)
            val (headers, body) = request.get()
            assertTrue(headers.startsWith("POST /api/v1/otp/sms-gateway HTTP/1.1\r\n"))
            assertTrue(headers.contains("X-OTP-Webhook-Token: $sharedSecret\r\n", ignoreCase = true))
            assertEquals("09120000001", JSONObject(body).getString("from"))
            assertEquals(frame, JSONObject(body).getString("text"))
        } finally {
            server.close(); serverThread.join(1_000); db.close()
            storage.saveForwarderSecret(originalSecret); storage.saveSecretEncryptionKey(originalKey)
        }
    }
}
