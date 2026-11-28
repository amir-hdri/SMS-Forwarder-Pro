package com.example

import android.Manifest
import android.app.Application
import android.content.Intent
import android.provider.Telephony
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.SecureStorageManager
import com.example.data.local.AppDatabase
import com.example.data.model.ForwardConfig
import com.example.data.model.ForwardStatus
import com.example.data.repository.SmsForwardRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.security.KeyStoreException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class StorageFailureSmsCaptureTest {
    private val storageSingleton = SecureStorageManager::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private val repositorySingleton = SmsForwardRepository::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
    private var previousStorage: Any? = null
    private var previousRepository: Any? = null
    private lateinit var context: Application
    private lateinit var db: AppDatabase

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context).grantPermissions(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS)
        previousStorage = storageSingleton.get(null)
        previousRepository = repositorySingleton.get(null)
        storageSingleton.set(null, SecureStorageManager(context) {
            throw KeyStoreException("synthetic invalidated Keystore key")
        })
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        db.forwardConfigDao().insertOrUpdate(
            ForwardConfig(
                isMasterEnabled = true,
                userConsentGiven = true,
                setupCompleted = true,
                driverPhone = "09120000001",
                endpointUrl = "https://87.107.160.251/api/v1/otp/sms-forwarder/09120000001"
            )
        )
        repositorySingleton.set(null, SmsForwardRepository(db, appContext = context))
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
        storageSingleton.set(null, previousStorage)
        repositorySingleton.set(null, previousRepository)
    }

    @Test
    fun keystoreFailureRecordsVisibleSkippedLogInsteadOfSilentCrash() = runBlocking {
        val repo = SmsForwardRepository.getInstance(context)
        val log = repo.processIncomingSms(
            sender = "7777000982",
            messageBody = "کد ورود: 123456",
            receivedTimestamp = System.currentTimeMillis(),
            simSlot = "SIM 1"
        )
        assertEquals(ForwardStatus.SKIPPED, log.status)
        assertTrue(
            "Expected visible storage failure reason in ForwardLog, got: ${log.errorMessage}",
            log.errorMessage.orEmpty().contains("ذخیره‌سازی امن")
        )
        val persisted = db.forwardLogDao().getLatestLog()
        assertNotNull("Skipped storage-failure log must be persisted to Room", persisted)
        assertEquals(ForwardStatus.SKIPPED, persisted!!.status)
    }
}
