package com.example

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.crypto.SecureStorageManager
import com.example.data.model.ForwardConfig
import com.example.data.repository.SmsForwardRepository
import com.example.enrollment.EnrollmentContract
import com.example.enrollment.EnrollmentStatus
import com.example.service.SmsForwarderService
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EnrollmentIntentE2ETest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository get() = SmsForwardRepository.getInstance(context)
    private val storage get() = SecureStorageManager.getInstance(context)
    private var previousConfig: ForwardConfig? = null
    private var previousPending: String? = null

    @Before fun prepare() = runBlocking {
        assumeTrue(BuildConfig.APP_ROLE == "DRIVER")
        previousConfig = repository.getConfig()
        previousPending = storage.getPendingEnrollment()
        SmsForwarderService.stopService(context)
        for (permission in listOf("RECEIVE_SMS", "SEND_SMS", "POST_NOTIFICATIONS")) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} android.permission.$permission"
            ).use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        }
        storage.savePendingEnrollment(null)
        repository.saveConfig(ForwardConfig(driverPhone = "09129999999", authHeaderValue = "synthetic-old-key",
            healthCheckIntervalMinutes = 60))
    }

    @After fun cleanup() = runBlocking {
        storage.savePendingEnrollment(previousPending)
        previousConfig?.let { repository.saveConfig(it) }
        SmsForwarderService.stopService(context)
    }

    private fun invalidLink(): Intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
        .setData(Uri.parse("barpro-forwarder://enroll#ticket=synthetic-untrusted-ticket-123456&server=http%3A%2F%2Fevil.test"))

    private fun awaitStatus(scenario: ActivityScenario<MainActivity>, expected: EnrollmentStatus) {
        val deadline = System.nanoTime() + 10_000_000_000L
        var actual: EnrollmentStatus? = null
        while (System.nanoTime() < deadline) {
            scenario.onActivity { actual = ViewModelProvider(it)[MainViewModel::class.java].enrollmentStatus.value }
            if (actual == expected) return
            Thread.sleep(50)
        }
        assertEquals(expected, actual)
    }

    private fun awaitStatus(activity: MainActivity, expected: EnrollmentStatus) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = System.nanoTime() + 10_000_000_000L
        var actual: EnrollmentStatus? = null
        while (System.nanoTime() < deadline) {
            instrumentation.runOnMainSync { actual = ViewModelProvider(activity)[MainViewModel::class.java].enrollmentStatus.value }
            if (actual == expected) return
            Thread.sleep(50)
        }
        assertEquals(expected, actual)
    }

    private fun finishActivity(activity: MainActivity) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { activity.finish() }
        val deadline = System.nanoTime() + 10_000_000_000L
        var stage: Stage? = null
        while (System.nanoTime() < deadline) {
            instrumentation.runOnMainSync {
                stage = ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity)
            }
            if (stage == Stage.DESTROYED) return
            Thread.sleep(50)
        }
        assertEquals(Stage.DESTROYED, stage)
    }

    @Test fun coldLinkRejectsUntrustedOriginAndStripsFragment() = runBlocking {
        // ActivityScenario matches lifecycle callbacks by original action/data. Enrollment
        // intentionally consumes both, so observe this actual instance instead of that matcher.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(invalidLink().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            awaitStatus(activity, EnrollmentStatus.FAILED)
            instrumentation.runOnMainSync { assertNull(activity.intent.data) }
            assertEquals("09129999999", repository.getConfig().driverPhone)
            assertEquals(60, repository.getConfig().healthCheckIntervalMinutes)
        } finally { finishActivity(activity) }
    }

    @Test fun warmLinkReachesOnNewIntentAndPreservesConfiguration() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            instrumentation.runOnMainSync {
                assertEquals(EnrollmentStatus.IDLE, ViewModelProvider(activity)[MainViewModel::class.java].enrollmentStatus.value)
                activity.startActivity(invalidLink().addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            awaitStatus(activity, EnrollmentStatus.FAILED)
            instrumentation.runOnMainSync {
                assertEquals(Intent.ACTION_VIEW, activity.intent.action)
                assertNull(activity.intent.data)
            }
            assertEquals("synthetic-old-key", repository.getConfig().authHeaderValue)
        } finally { finishActivity(activity) }
    }

    @Test fun stagedEnrollmentActivatesWithoutTypingAndRoomNeverStoresToken() = runBlocking {
        val endpoint = BuildConfig.DEFAULT_ENDPOINT_URL.ifBlank { ForwardConfig().endpointUrl }
        val base = EnrollmentContract.trustedServer(endpoint).toString().trimEnd('/')
        storage.savePendingEnrollment(JSONObject().put("protocol", "barpro-driver-v1")
            .put("driver_phone", "09120000001").put("endpoint_url", "$base/api/v1/otp/sms-forwarder/09120000001")
            .put("webhook_token", "a".repeat(64)).put("hub_phone_mci", "09120000002").put("hub_phone_irancell", "09350000003").toString())
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            awaitStatus(scenario, EnrollmentStatus.ACTIVE)
            val config = repository.getConfig()
            assertTrue(config.isMasterEnabled && config.userConsentGiven && config.setupCompleted)
            assertEquals("09120000001", config.driverPhone)
            assertEquals(5, config.healthCheckIntervalMinutes)
            assertEquals("a".repeat(64), config.authHeaderValue)
            val persisted = com.example.data.local.AppDatabase.getDatabase(context).forwardConfigDao().getConfig()!!
            assertEquals("", persisted.authHeaderValue)
            assertEquals("", persisted.forwarderSecret)
            assertNull(storage.getPendingEnrollment())
        }
    }

    @Test fun driverOutboxRetryUsesGsmAndCannotReachHttp() = runBlocking {
        val endpoint = BuildConfig.DEFAULT_ENDPOINT_URL.ifBlank { ForwardConfig().endpointUrl }
        val base = EnrollmentContract.trustedServer(endpoint).toString().trimEnd('/')
        repository.saveConfig(ForwardConfig(isMasterEnabled = true, userConsentGiven = true,
            driverPhone = "09120000001", authHeaderValue = "a".repeat(64),
            endpointUrl = "$base/api/v1/otp/sms-forwarder/09120000001", allowCleartextTransport = true,
            fallbackServerPhoneNumber = "09120000002", hubIrancellPhoneNumber = "09350000003"))
        val database = com.example.data.local.AppDatabase.getDatabase(context)
        val encryption = com.example.crypto.AesEncryptionUtils.encrypt("کد تایید: 54321", storage.getOrCreateOutboxKey())
        val id = database.forwardLogDao().insertLog(com.example.data.model.ForwardLog(
            sender = "20007777", messageBody = "کد تایید: ***", recipientPhone = "09120000001",
            receivedTimestamp = System.currentTimeMillis(), encryptedBody = "v2:${encryption.iv}:${encryption.ciphertext}"))
        val rejectingHttp = com.example.network.SmsForwarderClient(okhttp3.OkHttpClient.Builder().addInterceptor {
            throw AssertionError("Driver GSM retry must never reach HTTP")
        }.build())
        val isolated = SmsForwardRepository(database, rejectingHttp, context)
        val result = isolated.transmitPendingLog(id)
        assertNull(result.httpStatusCode)
        assertTrue("Modem accepts the message or leaves a retryable GSM failure", result.isSuccess || result.isRetryable)
        assertNotEquals(com.example.data.model.ForwardStatus.SKIPPED, isolated.getLogById(id)!!.status)
    }
    @Test fun driverPacketsVerifyAtTheHubAndCanBeCheckedByTheLocalBackend() {
        val phone = "09129990001"
        val shared = "phase16-loopback-hub-synthetic-key-32"
        val scoped = com.example.utils.SmsFallbackEnvelope.deriveDriverKey(shared, phone)
        val stamp = System.currentTimeMillis()
        val health = com.example.utils.SmsFallbackEnvelope.encode(phone, stamp, "HEALTH_CHECK", scoped)
        val otp = com.example.utils.SmsFallbackEnvelope.encode(phone, stamp, "54321", scoped)
        for (packet in listOf(health, otp)) {
            val parsed = com.example.utils.SmsFallbackEnvelope.parse(packet)!!
            assertTrue(com.example.utils.SmsFallbackEnvelope.verifyForHub(parsed, shared))
        }
        context.openFileOutput("phase16-wire-packets.json", android.content.Context.MODE_PRIVATE).use {
            it.write(JSONObject().put("phone", phone).put("health", health).put("otp", otp)
                .toString().toByteArray(Charsets.UTF_8))
        }
    }

}
