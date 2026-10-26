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
import com.example.data.repository.SmsForwardRepository
import com.example.network.SmsForwarderClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSmsManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class DriverSubscriptionRelayTest {
    private fun manager(context: Application, id: Int? = null): SmsManager =
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val default = context.getSystemService(SmsManager::class.java)
            if (id == null) default else default.createForSubscriptionId(id)
        } else {
            @Suppress("DEPRECATION")
            if (id == null) SmsManager.getDefault() else SmsManager.getSmsManagerForSubscriptionId(id)
        }

    @Before fun clearPreviousModemAndHealthObservations() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        ShadowSmsManager.setDefaultSmsSubscriptionId(1)
        context.getSharedPreferences("barpro_driver_health", Application.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("barpro_driver_subscription", Application.MODE_PRIVATE).edit().clear().commit()
        shadowOf(manager(context)).clearLastSentTextMessageParams()
        for (id in listOf(1, 17, 18)) shadowOf(manager(context, id)).clearLastSentTextMessageParams()
    }

    @Test @Config(sdk = [29, 34]) fun retryAndHealthKeepIncomingSubscriptionWithoutPhoneStateGrant() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS)
        shadowOf(context).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        ShadowSmsManager.setDefaultSmsSubscriptionId(1)
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repository = SmsForwardRepository(database, SmsForwarderClient(OkHttpClient.Builder().addInterceptor {
            throw AssertionError("Driver relay cannot reach HTTP")
        }.build()), context)
        try {
            repository.saveConfig(ForwardConfig(isMasterEnabled = true, userConsentGiven = true,
                setupCompleted = true, allowCleartextTransport = true, isEncryptionEnabled = false, driverPhone = "09120000001", authHeaderValue = "a".repeat(64),
                endpointUrl = "http://87.107.160.251/api/v1/otp/sms-forwarder/09120000001",
                fallbackServerPhoneNumber = "09120000002", hubIrancellPhoneNumber = "09350000003"))
            val storage = SecureStorageManager.getInstance(context)
            val body = AesEncryptionUtils.encrypt("کد تایید: 54321", storage.getOrCreateOutboxKey())
            val id = database.forwardLogDao().insertLog(ForwardLog(sender = "20007777", messageBody = "***",
                recipientPhone = "09120000001", receivedTimestamp = System.currentTimeMillis(),
                encryptedBody = "v2:${body.iv}:${body.ciphertext}", simSlot = "SIM 2 (Sub #17)"))
            val result = repository.transmitPendingLog(id)
            assertTrue(result.errorMessage, result.isSuccess)
            @Suppress("DEPRECATION") val selected = shadowOf(manager(context, 17))
            assertNotNull("OTP must leave its original subscription", selected.lastSentTextMessageParams)
            selected.clearLastSentTextMessageParams()
            repository.performHeartbeatAndCommandPoll()
            assertTrue("Health must use the same subscription as OTP", selected.lastSentTextMessageParams.text.contains("HEALTH_CHECK"))
            @Suppress("DEPRECATION") val other = shadowOf(manager(context))
            assertNull("Neither packet may fall back to the other SIM", other.lastSentTextMessageParams)
        } finally { database.close() }
    }
    @Test fun initialHealthUsesOsSelectedSmsSimUntilAnOtpSubscriptionIsKnown() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS)
        shadowOf(context).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        ShadowSmsManager.setDefaultSmsSubscriptionId(1)
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repository = SmsForwardRepository(database, SmsForwarderClient(OkHttpClient.Builder().addInterceptor {
            throw AssertionError("Initial Driver health cannot reach HTTP")
        }.build()), context)
        try {
            repository.saveConfig(ForwardConfig(isMasterEnabled = true, userConsentGiven = true,
                setupCompleted = true, allowCleartextTransport = true, isEncryptionEnabled = false,
                driverPhone = "09120000001", authHeaderValue = "a".repeat(64),
                endpointUrl = "http://87.107.160.251/api/v1/otp/sms-forwarder/09120000001",
                fallbackServerPhoneNumber = "09120000002", hubIrancellPhoneNumber = "09350000003"))
            repository.performHeartbeatAndCommandPoll()
            @Suppress("DEPRECATION") val selected = shadowOf(manager(context))
            assertNotNull("Initial health must use the default SMS manager", selected.lastSentTextMessageParams)
            assertTrue(selected.lastSentTextMessageParams.text.contains("HEALTH_CHECK"))
        } finally { database.close() }
    }

    @Test fun retryOfAnOlderOutboxCannotReplaceTheHealthSubscriptionLearnedFromNewerIntake() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS)
        shadowOf(context).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        ShadowSmsManager.setDefaultSmsSubscriptionId(1)
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repository = SmsForwardRepository(database, SmsForwarderClient(OkHttpClient.Builder().addInterceptor {
            throw AssertionError("Driver must use GSM")
        }.build()), context)
        try {
            repository.saveConfig(ForwardConfig(isMasterEnabled = true, userConsentGiven = true,
                setupCompleted = true, allowCleartextTransport = true, isEncryptionEnabled = false,
                driverPhone = "09120000001", authHeaderValue = "a".repeat(64),
                endpointUrl = "http://87.107.160.251/api/v1/otp/sms-forwarder/09120000001",
                fallbackServerPhoneNumber = "09120000002", hubIrancellPhoneNumber = "09350000003"))
            com.example.utils.DriverSmsSubscription.remember(context, "09120000001", "SIM 2 (Sub #18)")
            val storage = SecureStorageManager.getInstance(context)
            val body = AesEncryptionUtils.encrypt("کد تایید: 54321", storage.getOrCreateOutboxKey())
            val id = database.forwardLogDao().insertLog(ForwardLog(sender = "20007777", messageBody = "***",
                recipientPhone = "09120000001", receivedTimestamp = System.currentTimeMillis(),
                encryptedBody = "v2:${body.iv}:${body.ciphertext}", simSlot = "SIM 2 (Sub #17)"))
            assertTrue(repository.transmitPendingLog(id).isSuccess)
            assertNotNull(shadowOf(manager(context, 17)).lastSentTextMessageParams)
            repository.performHeartbeatAndCommandPoll()
            assertNotNull("Newer learned subscription must survive an older retry", shadowOf(manager(context, 18)).lastSentTextMessageParams)
        } finally { database.close() }
    }

    @Test fun notificationCaptureCannotConsumeTheAuthoritativeSmsSubscription() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS)
        shadowOf(context).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        ShadowSmsManager.setDefaultSmsSubscriptionId(1)
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repository = SmsForwardRepository(database, SmsForwarderClient(OkHttpClient.Builder().addInterceptor {
            throw AssertionError("Driver must wait for GSM subscription metadata")
        }.build()), context)
        try {
            repository.saveConfig(ForwardConfig(isMasterEnabled = true, userConsentGiven = true,
                setupCompleted = true, allowCleartextTransport = true, isEncryptionEnabled = false,
                filterMode = com.example.data.model.ForwardFilterMode.ALL_MESSAGES,
                driverPhone = "09120000001", authHeaderValue = "a".repeat(64),
                endpointUrl = "http://87.107.160.251/api/v1/otp/sms-forwarder/09120000001",
                fallbackServerPhoneNumber = "09120000002", hubIrancellPhoneNumber = "09350000003"))
            val time = System.currentTimeMillis()
            val notification = repository.processIncomingSms("20007777", "کد تایید: 67890", time, "Notification Listener")
            assertEquals("Notification capture must wait for SMS_RECEIVED", com.example.data.model.ForwardStatus.SKIPPED, notification.status)
            assertNull(shadowOf(manager(context)).lastSentTextMessageParams)
            val authoritative = repository.processIncomingSms("20007777", "کد تایید: 67890", time, "SIM 2 (Sub #17)")
            assertEquals(com.example.data.model.ForwardStatus.SUCCESS, authoritative.status)
            assertNotNull(shadowOf(manager(context, 17)).lastSentTextMessageParams)
        } finally { database.close() }
    }

}
