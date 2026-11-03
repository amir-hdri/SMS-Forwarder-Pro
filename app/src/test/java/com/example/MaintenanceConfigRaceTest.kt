package com.example

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.SerialExecutorImpl
import androidx.work.impl.utils.taskexecutor.TaskExecutor
import com.example.data.local.AppDatabase
import com.example.data.model.ForwardConfig
import com.example.data.repository.SmsForwardRepository
import com.example.service.HeartbeatAlarmManager
import com.example.service.MaintenanceWorker
import com.example.update.ConfigFetch
import com.example.update.RemoteConfigClient
import com.example.update.UpdateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation

/** Replacing the latest enrolled profile with the Worker's pre-fetch snapshot must fail this test. */
@RunWith(RobolectricTestRunner::class)
@Config(application = ForwarderRoleApplication::class, sdk = [34],
    shadows = [PausedRemoteConfigFetch::class])
class MaintenanceConfigRaceTest {
    @get:Rule val secureStorage = SecureStorageTestRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val dbSingleton = AppDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
    private val repoSingleton = SmsForwardRepository::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
    private val storeSingleton = UpdateStore::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private lateinit var db: AppDatabase
    private lateinit var repository: SmsForwardRepository
    private var previousDb: Any? = null
    private var previousRepo: Any? = null
    private var previousStore: Any? = null

    @Before fun prepare() = runBlocking {
        previousDb = dbSingleton.get(null)
        previousRepo = repoSingleton.get(null)
        previousStore = storeSingleton.get(null)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = SmsForwardRepository(db, appContext = context)
        dbSingleton.set(null, db)
        repoSingleton.set(null, repository)
        storeSingleton.set(null, null)
        context.getSharedPreferences("barpro_update_state", Context.MODE_PRIVATE).edit().clear().commit()
        PausedRemoteConfigFetch.reached = CountDownLatch(1)
        PausedRemoteConfigFetch.release = CountDownLatch(1)
        PausedRemoteConfigFetch.result = null
        repository.saveConfig(ForwardConfig(isMasterEnabled = false, userConsentGiven = false,
            configManifestUrl = "https://synthetic.example.test/config", autoUpdateEnabled = false))
    }

    @After fun close() {
        PausedRemoteConfigFetch.release.countDown()
        db.close()
        dbSingleton.set(null, previousDb)
        repoSingleton.set(null, previousRepo)
        storeSingleton.set(null, previousStore)
        HeartbeatAlarmManager.cancel(context)
    }

    @Test fun enrollmentCommittedWhileMaintenanceFetchesSurvivesTheSignedTuningOverlay() = runBlocking {
        val worker = MaintenanceWorker(context, workerParameters())
        val run = async(Dispatchers.IO) { worker.doWork() }
        try {
            assertTrue("Maintenance must reach the controlled remote fetch", PausedRemoteConfigFetch.reached.await(5, TimeUnit.SECONDS))
            val enrolled = ForwardConfig(isMasterEnabled = true, userConsentGiven = true, setupCompleted = true,
                driverPhone = "09350000001", authHeaderValue = "synthetic-private-enrollment-token",
                forwarderSecret = "synthetic-private-enrollment-token", secretEncryptionKey = "synthetic-private-key",
                endpointUrl = "https://fleet.example.test/api/v1/otp/sms-forwarder/09350000001",
                configManifestUrl = "https://fleet.example.test/current-config", autoUpdateEnabled = false,
                fallbackServerPhoneNumber = "09120000009", hubIrancellPhoneNumber = "09350000009")
            repository.saveConfig(enrolled)
            val durableProfile = repository.getConfig()
            PausedRemoteConfigFetch.release.countDown()
            assertEquals(ListenableWorker.Result.success(), withTimeout(5000) { run.await() })
            assertTrue("The real signature/audience verification must apply: ${PausedRemoteConfigFetch.result}",
                PausedRemoteConfigFetch.result is ConfigFetch.Applied)
            assertEquals("A signed tuning overlay must preserve the complete newly enrolled private profile",
                durableProfile.copy(healthCheckIntervalMinutes = 17, timeoutSeconds = 23, maxRetries = 2), repository.getConfig())
            assertEquals(1L, UpdateStore.getInstance(context).configVersion)
            assertEquals("Credentials remain outside Room", "", db.forwardConfigDao().getConfig()!!.authHeaderValue)
        } finally {
            PausedRemoteConfigFetch.release.countDown()
            run.cancel()
        }
    }

    private fun workerParameters(): WorkerParameters {
        val executor = Executor { it.run() }
        val serial = SerialExecutorImpl(executor)
        val tasks = object : TaskExecutor {
            override fun getMainThreadExecutor() = executor
            override fun getSerialTaskExecutor() = serial
        }
        // Scheduler-only collaborators are unused by doWork; keep the real Worker implementation.
        return WorkerParameters::class.java.constructors.single().newInstance(UUID.randomUUID(), Data.EMPTY,
            emptyList<String>(), WorkerParameters.RuntimeExtras(), 0, 0, executor, tasks,
            WorkerFactory.getDefaultWorkerFactory(), null, null) as WorkerParameters
    }
}

/** Only the remote wait is substituted. Signature, audience, parser, Worker, Room and secure writes are real. */
@Implements(RemoteConfigClient::class, isInAndroidSdk = false)
class PausedRemoteConfigFetch {
    @RealObject private lateinit var client: RemoteConfigClient

    @Implementation fun fetch(url: String, audience: String, knownVersion: Long,
        continuation: Continuation<ConfigFetch>): Any {
        check(url == "https://synthetic.example.test/config")
        reached.countDown()
        check(release.await(5, TimeUnit.SECONDS)) { "Controlled fetch release timed out" }
        val role = if (audience.endsWith(".driver")) "driver" else "hub"
        val document = javaClass.getResourceAsStream("/maintenance-overlay-$role.signed")!!.bufferedReader().use { it.readText() }
        return client.verify(document, audience, knownVersion).also { result = it }
    }

    companion object {
        lateinit var reached: CountDownLatch
        lateinit var release: CountDownLatch
        @Volatile var result: ConfigFetch? = null
    }
}

/** Robolectric otherwise assigns its generic test namespace, unlike the flavor application ID. */
class ForwarderRoleApplication : Application() {
    override fun getPackageName() = BuildConfig.APPLICATION_ID
}
