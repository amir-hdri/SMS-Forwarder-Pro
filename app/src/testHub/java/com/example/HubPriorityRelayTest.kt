package com.example

import android.Manifest
import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.model.ForwardConfig
import com.example.data.model.ForwardStatus
import com.example.data.repository.SmsForwardRepository
import com.example.network.SmsForwarderClient
import com.example.utils.SmsFallbackEnvelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HubPriorityRelayTest {
    @get:org.junit.Rule val secureStorage = SecureStorageTestRule()
    @Test fun immediateDeadlineIncludesWaitingForTheSameLogLock() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.RECEIVE_SMS)
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            firstEntered.countDown()
            check(releaseFirst.await(5, TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                .body("{\"success\":true,\"status\":\"success\",\"otp_detected\":true}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repo = SmsForwardRepository(db, SmsForwarderClient(http), context)
        try {
            val secret = "a".repeat(64)
            repo.saveConfig(ForwardConfig(isMasterEnabled = true, userConsentGiven = true,
                endpointUrl = "https://barpro.test/api/v1/otp/sms-forwarder", authHeaderValue = secret))
            val now = System.currentTimeMillis()
            val packet = SmsFallbackEnvelope.encode("09120000001", now, "54321",
                SmsFallbackEnvelope.deriveDriverKey(secret, "09120000001"))
            val first = async(Dispatchers.IO) { repo.processIncomingSms("09120000001", packet, now) }
            assertTrue(firstEntered.await(3, TimeUnit.SECONDS))
            val log = db.forwardLogDao().getLatestLog()!!
            val waiting = withTimeoutOrNull(800) { repo.transmitPendingLog(log.id, fastTimeoutMs = 250) }
            assertNotNull("A fast deadline must bound lock wait as well as the HTTP attempt", waiting)
            assertFalse(waiting!!.isSuccess)
            assertTrue(waiting.isRetryable)
            releaseFirst.countDown()
            assertEquals(ForwardStatus.SUCCESS, first.await().status)
        } finally {
            releaseFirst.countDown()
            db.close()
            http.dispatcher.executorService.shutdown()
        }
    }

    @Test fun aSlowOlderRequestDoesNotBlockTheNextDriversCode() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.RECEIVE_SMS)
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondReachedServer = CountDownLatch(1)
        val calls = AtomicInteger()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            if (calls.incrementAndGet() == 1) {
                firstEntered.countDown()
                check(releaseFirst.await(5, TimeUnit.SECONDS))
            } else secondReachedServer.countDown()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                .body("{\"success\":true,\"status\":\"success\",\"otp_detected\":true}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repo = SmsForwardRepository(db, SmsForwarderClient(http), context)
        try {
            val secret = "a".repeat(64)
            repo.saveConfig(ForwardConfig(isMasterEnabled = true, userConsentGiven = true,
                endpointUrl = "https://barpro.test/api/v1/otp/sms-forwarder", authHeaderValue = secret))
            val now = System.currentTimeMillis()
            fun packet(phone: String, code: String) = SmsFallbackEnvelope.encode(phone, now, code,
                SmsFallbackEnvelope.deriveDriverKey(secret, phone))
            val first = async(Dispatchers.IO) { repo.processIncomingSms("09120000001", packet("09120000001", "54321"), now) }
            assertTrue("The first signed packet must reach HTTP", firstEntered.await(3, TimeUnit.SECONDS))
            val second = async(Dispatchers.IO) { repo.processIncomingSms("09350000001", packet("09350000001", "98765"), now) }
            assertTrue("Unrelated OTP must reach the server while the older request is stalled",
                secondReachedServer.await(2, TimeUnit.SECONDS))
            releaseFirst.countDown()
            assertEquals(ForwardStatus.SUCCESS, second.await().status)
            assertEquals(ForwardStatus.SUCCESS, first.await().status)
            assertEquals(2, calls.get())
        } finally {
            releaseFirst.countDown()
            db.close()
            http.dispatcher.executorService.shutdown()
        }
    }
}
