package com.example

import android.Manifest
import android.app.Application
import android.telephony.SmsManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.AesEncryptionUtils
import com.example.crypto.SecureStorageManager
import com.example.data.local.AppDatabase
import com.example.data.model.ForwardConfig
import com.example.data.model.ForwardLog
import com.example.data.model.ForwardStatus
import com.example.data.repository.SmsForwardRepository
import com.example.network.SmsForwarderClient
import com.example.update.UpdateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Removing the post-suspension ownership check must permit the forbidden handoff in these races. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ForwardingOwnershipRaceTest {
    @get:Rule val secureStorage = SecureStorageTestRule()
    private lateinit var context: Application
    private lateinit var db: AppDatabase
    private lateinit var repository: SmsForwardRepository
    private val queryReached = CountDownLatch(1)
    private val queryRelease = CountDownLatch(1)
    private val pauseQuery = AtomicBoolean(false)
    private var pausedSql = "filter_rules WHERE isEnabled"
    private val requests = AtomicInteger()

    @Before fun prepare() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context).grantPermissions(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS)
        context.getSharedPreferences("barpro_update_state", 0).edit().clear().commit()
        UpdateStore.getInstance(context).blockedByMandatoryUpdate = false
        shadowOf(context.getSystemService(SmsManager::class.java).createForSubscriptionId(17)).clearLastSentTextMessageParams()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryCallback({ sql, _ ->
                if (sql.contains(pausedSql) && pauseQuery.compareAndSet(true, false)) {
                    queryReached.countDown()
                    check(queryRelease.await(8, TimeUnit.SECONDS))
                }
            }, Executor { it.run() }).build()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{\"success\":true,\"status\":\"success\",\"otp_detected\":true}".toResponseBody()).build()
        }.build()
        repository = SmsForwardRepository(db, SmsForwarderClient(http), context)
        repository.saveConfig(ForwardConfig(isMasterEnabled = true, userConsentGiven = true,
            driverPhone = "09120000001", deviceIdentifier = "synthetic-device",
            authHeaderValue = "a".repeat(64), forwarderSecret = "a".repeat(64),
            endpointUrl = "http://87.107.160.251/api/v1/otp/sms-forwarder/09120000001",
            primarySmsRelayEnabled = false, enableSmsFallback = false, maxRetries = 0))
    }

    @After fun close() {
        queryRelease.countDown()
        UpdateStore.getInstance(context).blockedByMandatoryUpdate = false
        context.getSharedPreferences("barpro_update_state", 0).edit().clear().commit()
        db.close()
    }

    private suspend fun insert(): Long {
        val encrypted = AesEncryptionUtils.encrypt("کد تایید: 543210", SecureStorageManager.getInstance(context).getOrCreateOutboxKey())
        return db.forwardLogDao().insertLog(ForwardLog(sender = "20007777", messageBody = "کد تایید: ***",
            recipientPhone = "09120000001", receivedTimestamp = System.currentTimeMillis(),
            simSlot = "SIM 2 (Sub #17)", encryptedBody = "v2:${encrypted.iv}:${encrypted.ciphertext}"))
    }

    private fun revokedWhileSuspended(change: suspend () -> Unit) = runBlocking {
        supervisorScope {
            val id = insert()
            val original = repository.getLogById(id)!!
            pauseQuery.set(true)
            val work = async(Dispatchers.IO) { repository.transmitPendingLog(id) }
            try {
                assertTrue("Delivery must suspend after its first configuration read", queryReached.await(5, TimeUnit.SECONDS))
                change()
                queryRelease.countDown()
                val failure = runCatching { withTimeout(5000) { work.await() } }.exceptionOrNull()
                assertEquals("Revoked credentials cannot enter HTTP", 0, requests.get())
                assertTrue("Revocation must interrupt the old delivery", failure is CancellationException)
                assertEquals("The original durable outbox must survive revocation", original, repository.getLogById(id))
            } finally { queryRelease.countDown(); work.cancel() }
        }
    }

    @Test fun stopDuringRuleReadPreventsHttp() = revokedWhileSuspended {
        repository.updateConfig { it.copy(isMasterEnabled = false) }
    }
    @Test fun withdrawnConsentDuringRuleReadPreventsHttp() = revokedWhileSuspended {
        repository.updateConfig { it.copy(userConsentGiven = false) }
    }
    @Test fun replacedTokenDuringRuleReadPreventsHttp() = revokedWhileSuspended {
        repository.updateConfig { it.copy(forwarderSecret = "b".repeat(64), authHeaderValue = "b".repeat(64)) }
    }
    @Test fun replacedPhoneDuringRuleReadPreventsHttp() = revokedWhileSuspended {
        repository.updateConfig { it.copy(driverPhone = "09350000001") }
    }
    @Test fun replacedOriginDuringRuleReadPreventsHttp() = revokedWhileSuspended {
        repository.updateConfig { it.copy(endpointUrl = "https://foreign.example.test/api/v1/otp/sms-forwarder/09120000001") }
    }
    @Test fun replacedDeviceDuringRuleReadPreventsHttp() = revokedWhileSuspended {
        repository.updateConfig { it.copy(deviceIdentifier = "replacement-device") }
    }
    @Test fun requiredUpdateDuringRuleReadPreventsHttp() = revokedWhileSuspended {
        UpdateStore.getInstance(context).blockedByMandatoryUpdate = true
    }

    @Test fun actualHttpBackoffCannotUseAStoppedProfileOrInitiateGsmFallback() = runBlocking {
        supervisorScope {
            OwnedHttpProxy().use { wire ->
                repository.updateConfig { it.copy(maxRetries = 1, enableSmsFallback = true) }
                repository = SmsForwardRepository(db, SmsForwarderClient(wire.client), context)
                val id = insert()
                val original = repository.getLogById(id)
                val work = async(Dispatchers.IO) { repository.transmitPendingLog(id) }
                try {
                    assertTrue("The first real TCP request must receive HTTP503", wire.firstReceived.await(5, TimeUnit.SECONDS))
                    repository.updateConfig { it.copy(isMasterEnabled = false, userConsentGiven = false) }
                    val failure = runCatching { withTimeout(5000) { work.await() } }.exceptionOrNull()
                    assertTrue("The real authority interceptor must propagate revocation through OkHttp", failure is CancellationException)
                    assertEquals("Only the pre-STOP request may reach the owned TCP server", 1, wire.requests.get())
                    assertEquals("STOP during backoff must retain original outbox and attempt clock", original, repository.getLogById(id))
                    assertNull("HTTP cancellation cannot initiate GSM fallback", shadowOf(context.getSystemService(SmsManager::class.java)
                        .createForSubscriptionId(17)).lastSentTextMessageParams)
                } finally { work.cancel() }
            }
        }
    }

    @Test fun actualHttpCoroutineCancellationRetainsTheOutbox() = runBlocking {
        supervisorScope {
            OwnedHttpProxy(holdResponse = true).use { wire ->
                repository = SmsForwardRepository(db, SmsForwarderClient(wire.client), context)
                val id = insert()
                val original = repository.getLogById(id)
                val work = async(Dispatchers.IO) { repository.transmitPendingLog(id) }
                try {
                    assertTrue(wire.firstReceived.await(5, TimeUnit.SECONDS))
                    work.cancel(CancellationException("User stopped the owned pending HTTP call"))
                    wire.releaseResponse.countDown()
                    assertTrue(runCatching { work.await() }.exceptionOrNull() is CancellationException)
                    assertEquals(original, repository.getLogById(id))
                    assertEquals(1, wire.requests.get())
                } finally { wire.releaseResponse.countDown(); work.cancel() }
            }
        }
    }

    private fun inquiryRevoked(change: suspend () -> Unit) = runBlocking {
        supervisorScope {
            val id = insert()
            val original = repository.getLogById(id)
            pausedSql = "ORDER BY ABS(receivedTimestamp"
            pauseQuery.set(true)
            val work = async(Dispatchers.IO) { repository.queryAndSendOtpForServer("20007777") }
            try {
                assertTrue(queryReached.await(5, TimeUnit.SECONDS))
                change()
                queryRelease.countDown()
                val failure = runCatching { withTimeout(5000) { work.await() } }.exceptionOrNull()
                assertEquals("A revoked on-demand query cannot send OTP", 0, requests.get())
                assertTrue("The old inquiry must be cancelled", failure is CancellationException)
                assertEquals(original, repository.getLogById(id))
                assertEquals("No fabricated inquiry receipt", 1, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM forward_logs").use { it.moveToFirst(); it.getInt(0) })
            } finally { queryRelease.countDown(); work.cancel() }
        }
    }

    @Test fun inquiryStopDuringSqlReadPreventsHttp() = inquiryRevoked {
        repository.updateConfig { it.copy(isMasterEnabled = false) }
    }
    @Test fun inquiryConsentDuringSqlReadPreventsHttp() = inquiryRevoked {
        repository.updateConfig { it.copy(userConsentGiven = false) }
    }
    @Test fun inquiryTokenDuringSqlReadPreventsHttp() = inquiryRevoked {
        repository.updateConfig { it.copy(forwarderSecret = "b".repeat(64), authHeaderValue = "b".repeat(64)) }
    }
    @Test fun inquiryUpdateDuringSqlReadPreventsHttp() = inquiryRevoked {
        UpdateStore.getInstance(context).blockedByMandatoryUpdate = true
    }

    @Test fun stoppedHeartbeatDoesNotContactServerOrModem() = runBlocking {
        repository.updateConfig { it.copy(isMasterEnabled = false, userConsentGiven = false) }
        repository.performHeartbeatAndCommandPoll()
        assertEquals(0, requests.get())
        assertNull(shadowOf(context.getSystemService(SmsManager::class.java).createForSubscriptionId(17)).lastSentTextMessageParams)
    }

    @Test fun stoppedExplicitHealthCheckDoesNotContactServer() = runBlocking {
        repository.updateConfig { it.copy(userConsentGiven = false) }
        repository.checkServerHealth(context)
        assertEquals(0, requests.get())
    }

    /** Real TCP retains the selected Central URL; every socket goes only to our explicit loopback proxy. */
    private class OwnedHttpProxy(private val holdResponse: Boolean = false) : AutoCloseable {
        val requests = AtomicInteger()
        val firstReceived = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        private val executor = java.util.concurrent.Executors.newCachedThreadPool()
        private val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@OwnedHttpProxy.executor
            createContext("/") { exchange ->
                exchange.requestBody.use { it.readBytes() }
                requests.incrementAndGet()
                firstReceived.countDown()
                if (holdResponse) releaseResponse.await(5, TimeUnit.SECONDS)
                try {
                    val body = "{\"success\":false,\"status\":\"unavailable\"}".toByteArray()
                    exchange.sendResponseHeaders(503, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                } catch (_: java.io.IOException) { /* The cancelled owned socket may already be closed. */ }
            }
            start()
        }
        val client = OkHttpClient.Builder().proxy(java.net.Proxy(java.net.Proxy.Type.HTTP,
            java.net.InetSocketAddress("127.0.0.1", server.address.port))).callTimeout(5, TimeUnit.SECONDS).build()
        override fun close() { releaseResponse.countDown(); server.stop(0); executor.shutdownNow() }
    }
}
