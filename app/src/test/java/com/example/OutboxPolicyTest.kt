package com.example

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.model.ForwardConfig
import com.example.data.model.ForwardLog
import com.example.data.model.ForwardStatus
import com.example.data.repository.SmsForwardRepository
import com.example.network.SmsForwarderClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class OutboxPolicyTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: SmsForwardRepository
    private lateinit var client: SmsForwarderClient
    private val config = ForwardConfig(isMasterEnabled = true, userConsentGiven = true,
        endpointUrl = "https://barpro.test/api/v1/otp/sms-forwarder",
        driverPhone = "09120000001", authHeaderValue = "test-webhook-token")

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        client = SmsForwarderClient(OkHttpClient.Builder().addInterceptor {
            throw AssertionError("A rejected outbox item must never reach the network")
        }.build())
        repository = SmsForwardRepository(db, client)
        repository.saveConfig(config)
    }

    @After fun close() { db.close() }

    private suspend fun insert(age: Long = 0, phone: String = config.driverPhone, status: ForwardStatus = ForwardStatus.PENDING): Long =
        db.forwardLogDao().insertLog(ForwardLog(sender = "20007777", messageBody = "کد تایید: ***",
            recipientPhone = phone, status = status, receivedTimestamp = System.currentTimeMillis() - age,
            encryptedBody = "invalid:encrypted-data"))

    @Test fun batchAndManualRetryBothRejectExpiredOtp() = runBlocking {
        val id = insert(age = 300_001)
        assertEquals(0, repository.syncOfflinePendingLogs())
        assertEquals(ForwardStatus.SKIPPED, repository.getLogById(id)!!.status)
        assertFalse(repository.transmitPendingLog(id).isSuccess)
    }

    @Test fun changingDriverCannotRerouteQueuedOtp() = runBlocking {
        val id = insert(phone = "09120000002")
        assertFalse(repository.transmitPendingLog(id).isSuccess)
        assertEquals(ForwardStatus.SKIPPED, repository.getLogById(id)!!.status)
    }

    @Test fun revokedConsentStopsPreviouslyQueuedWork() = runBlocking {
        val id = insert()
        repository.saveConfig(config.copy(userConsentGiven = false))
        assertFalse(repository.transmitPendingLog(id).isSuccess)
    }

    @Test fun successfulDeliveryIsNotSentAgain() = runBlocking {
        val id = insert(status = ForwardStatus.SUCCESS)
        assertTrue(repository.transmitPendingLog(id).isSuccess)
    }

    @Test fun corruptCiphertextIsNeverReplacedByRedactedLogText() = runBlocking {
        val id = insert()
        assertFalse(repository.transmitPendingLog(id).isSuccess)
        assertEquals(ForwardStatus.SKIPPED, repository.getLogById(id)!!.status)
    }

    @Test fun mandatoryUpdateBlocksPendingOutboxTransmission() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val updateStore = com.example.update.UpdateStore.getInstance(context)
        updateStore.blockedByMandatoryUpdate = true
        val repoWithContext = SmsForwardRepository(db, client, appContext = context)
        val id = insert()
        val result = repoWithContext.transmitPendingLog(id)
        assertFalse(result.isSuccess)
        assertEquals(ForwardStatus.SKIPPED, repoWithContext.getLogById(id)!!.status)
        assertTrue(repoWithContext.getLogById(id)!!.errorMessage!!.contains("به‌روزرسانی ضروری"))
        updateStore.blockedByMandatoryUpdate = false
    }

    @Test fun criticalPermissionsDoNotRequireUndeclaredReadSms() {
        val permissions = com.example.utils.PermissionHelper.getRequiredRuntimePermissions()
        assertFalse(permissions.contains(android.Manifest.permission.READ_SMS))
        assertTrue(permissions.contains(android.Manifest.permission.RECEIVE_SMS))
    }
}
