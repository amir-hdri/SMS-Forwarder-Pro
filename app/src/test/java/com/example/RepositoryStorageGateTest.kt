package com.example

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.SecureStorageManager
import com.example.data.local.AppDatabase
import com.example.data.model.ForwardConfig
import com.example.data.model.ForwardLog
import com.example.data.repository.SmsForwardRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.security.KeyStoreException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RepositoryStorageGateTest {
    private val singleton = SecureStorageManager::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private lateinit var context: Application
    private lateinit var db: AppDatabase
    private lateinit var repository: SmsForwardRepository
    private val writesAvailable = AtomicBoolean(true)
    private val saved = ForwardConfig(isMasterEnabled = true, userConsentGiven = true, setupCompleted = true,
        driverPhone = "09120000001", authHeaderValue = "a".repeat(64),
        endpointUrl = "https://87.107.160.251/api/v1/otp/sms-forwarder/09120000001")

    @Before fun prepare() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        val persisted = context.getSharedPreferences("synthetic_repo_storage", Context.MODE_PRIVATE)
        val preferences = object : SharedPreferences by persisted {
            override fun edit(): SharedPreferences.Editor {
                val editor = persisted.edit()
                return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                    arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                    if (method.name == "commit" && !writesAvailable.get()) false
                    else {
                        val value = method.invoke(editor, *(args ?: emptyArray()))
                        if (value is SharedPreferences.Editor) proxy else value
                    }
                } as SharedPreferences.Editor
            }
        }
        singleton.set(null, SecureStorageManager(context) { preferences })
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = SmsForwardRepository(db, appContext = context)
        repository.saveConfig(saved)
        db.forwardLogDao().insertLog(ForwardLog(sender = "20007777", messageBody = "***",
            encryptedBody = "v2:synthetic-existing-iv:synthetic-existing-ciphertext"))
        Unit
    }

    @After fun close() { db.close(); singleton.set(null, null) }

    @Test fun secureCommitFailureStopsPersistedConsentAndBlocksTransportConfiguration() = runBlocking {
        val previous = db.forwardConfigDao().getConfig()!!
        writesAvailable.set(false)
        assertTrue(runCatching { repository.saveConfig(saved.copy(isMasterEnabled = false)) }.isFailure)
        val stopped = db.forwardConfigDao().getConfig()!!
        assertFalse("A failed secure write must stop forwarding beyond the UI", stopped.isMasterEnabled)
        assertFalse(stopped.userConsentGiven)
        assertEquals(previous.copy(isMasterEnabled = false, userConsentGiven = false), stopped)
        assertTrue("Receivers/workers must reject cached credentials until validated recovery",
            runCatching { repository.getConfig() }.isFailure)
        assertEquals("a".repeat(64), context.getSharedPreferences("synthetic_repo_storage", Context.MODE_PRIVATE)
            .getString("secure_forwarder_secret", null))
        assertEquals("v2:synthetic-existing-iv:synthetic-existing-ciphertext", db.forwardLogDao().getLatestLog()!!.encryptedBody)
    }

    @Test fun unreadableSecureStorageDisablesPersistedConfigurationWithoutReplacingIdentity() = runBlocking {
        val previous = db.forwardConfigDao().getConfig()!!
        singleton.set(null, SecureStorageManager(context) { throw KeyStoreException("synthetic unavailable provider") })
        // Reopen repository so its real lazy credential dependency observes the failing provider.
        val reopened = SmsForwardRepository(db, appContext = context)
        assertTrue(runCatching { reopened.getConfig() }.isFailure)
        val stopped = db.forwardConfigDao().getConfig()!!
        assertFalse(stopped.isMasterEnabled)
        assertFalse(stopped.userConsentGiven)
        assertEquals(previous.copy(isMasterEnabled = false, userConsentGiven = false), stopped)
    }

    @Test fun configCollectorCannotPairAnOldRoomSnapshotWithNewCredentials() = runBlocking {
        db.close()
        val queryReached = CountDownLatch(1)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryCallback(RoomDatabase.QueryCallback { sql, _ ->
                if (sql.startsWith("SELECT * FROM app_config")) queryReached.countDown()
            }, { it.run() }).build()
        db.forwardConfigDao().insertOrUpdate(saved.copy(authHeaderValue = ""))
        repository = SmsForwardRepository(db, appContext = context)
        val lock = SmsForwardRepository::class.java.getDeclaredField("configAccessLock").apply { isAccessible = true }
            .get(repository) as Mutex
        // Own the same critical section as save/recovery while the real Room flow reads its row.
        lock.lock()
        val observer = async(Dispatchers.IO) { repository.configFlow.filterNotNull().first() }
        try {
            assertTrue(queryReached.await(3, TimeUnit.SECONDS))
            delay(100) // Allow the completed one-row query to queue at the held config mutex.
            val latest = saved.copy(driverPhone = "09350000001", isMasterEnabled = false,
                userConsentGiven = false, authHeaderValue = "")
            SecureStorageManager.getInstance(context).saveForwarderSecret("b".repeat(64))
            db.forwardConfigDao().insertOrUpdate(latest)
            lock.unlock()
            val observed = observer.await()
            assertEquals("A Room invalidation must restore the current identity under the credential lock",
                "09350000001", observed.driverPhone)
            assertFalse(observed.isMasterEnabled)
            assertFalse(observed.userConsentGiven)
            assertEquals("b".repeat(64), observed.authHeaderValue)
        } finally {
            if (lock.isLocked) lock.unlock()
            observer.cancel()
        }
    }
}
