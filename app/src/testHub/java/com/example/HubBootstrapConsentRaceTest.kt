package com.example

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.SerialExecutorImpl
import androidx.work.impl.utils.taskexecutor.TaskExecutor
import com.example.data.local.AppDatabase
import com.example.data.model.AuthType
import com.example.data.model.ForwardConfig
import com.example.data.repository.SmsForwardRepository
import com.example.enrollment.HubBootstrapWorker
import com.example.network.BarProContract
import com.example.network.CellularEgress
import com.example.network.CellularEgressSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
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
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume

/** The real worker waits at its cellular acquisition boundary; only physical networking is substituted. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34],
    shadows = [PausedHubCellularAcquisition::class, OwnedHubNetworkBinding::class])
class HubBootstrapConsentRaceTest {
    @get:Rule val secureStorage = SecureStorageTestRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val repositoryField = SmsForwardRepository::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
    private var previousRepository: Any? = null
    private lateinit var database: AppDatabase
    private lateinit var repository: SmsForwardRepository
    private lateinit var endpoint: OwnedHubTlsServer
    private lateinit var original: ForwardConfig
    private val requests = CopyOnWriteArrayList<String>()

    @Before fun prepare() = runBlocking {
        previousRepository = repositoryField.get(null)
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = SmsForwardRepository(database, appContext = context)
        repositoryField.set(null, repository)
        endpoint = OwnedHubTlsServer()
        endpoint.server.createContext("/api/v1/otp/bootstrap/prove") { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            require(body.contains("09120000001") && body.contains("n".repeat(43)))
            requests.add(exchange.requestHeaders.getFirst(BarProContract.TOKEN_HEADER))
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("{}".toByteArray()) }
        }
        OwnedHubNetworkBinding.client = endpoint.client
        val network = Network::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(17)
        PausedHubCellularAcquisition.session = CellularEgressSession(
            context.getSystemService(ConnectivityManager::class.java), network,
            object : ConnectivityManager.NetworkCallback() {})
        PausedHubCellularAcquisition.reached = CountDownLatch(1)
        PausedHubCellularAcquisition.pending.set(null)
        original = ForwardConfig(endpointUrl = endpoint.root + "/api/v1/otp/sms-forwarder",
            authType = AuthType.CUSTOM_HEADER, authHeaderKey = BarProContract.TOKEN_HEADER,
            authHeaderValue = "synthetic-owned-hub-credential-only", forwarderSecret = "synthetic-owned-hub-credential-only",
            deviceIdentifier = "synthetic-hub-device-one", setupCompleted = true,
            isMasterEnabled = true, userConsentGiven = true)
        repository.saveConfig(original)
        original = repository.getConfig()
    }

    @After fun close() {
        PausedHubCellularAcquisition.release()
        endpoint.close()
        database.close()
        repositoryField.set(null, previousRepository)
        OwnedHubNetworkBinding.client = null
    }

    private fun parameters(): WorkerParameters {
        val executor = Executor { it.run() }
        val serial = SerialExecutorImpl(executor)
        val tasks = object : TaskExecutor {
            override fun getMainThreadExecutor() = executor
            override fun getSerialTaskExecutor() = serial
        }
        val input = Data.Builder().putString("origin", "09120000001").putString("nonce", "n".repeat(43))
            .putLong("expires", System.currentTimeMillis() + 900_000).build()
        return WorkerParameters::class.java.constructors.single().newInstance(UUID.randomUUID(), input,
            emptyList<String>(), WorkerParameters.RuntimeExtras(), 0, 0, executor, tasks,
            WorkerFactory.getDefaultWorkerFactory(), null, null) as WorkerParameters
    }

    private suspend fun revokeDuringAcquisition(updated: ForwardConfig) = coroutineScope {
        val worker = HubBootstrapWorker(context, parameters())
        val work = async(Dispatchers.IO) { worker.doWork() }
        try {
            assertTrue("The real worker must reach the paused cellular acquisition",
                PausedHubCellularAcquisition.reached.await(5, TimeUnit.SECONDS))
            repository.saveConfig(updated)
            val persisted = repository.getConfig()
            PausedHubCellularAcquisition.release()
            val result = withTimeout(5000) { work.await() }
            assertTrue("No cached Hub credential may reach proof transport after revocation", requests.isEmpty())
            assertEquals("A revoked Hub profile must not authorize a proof", ListenableWorker.Result.failure(), result)
            assertEquals("Proof cleanup must preserve the newer private profile", persisted, repository.getConfig())
        } finally {
            PausedHubCellularAcquisition.release()
            work.cancelAndJoin()
        }
    }

    @Test fun stoppingHubDuringCellularAcquisitionPreventsTheOldProof() = runBlocking {
        revokeDuringAcquisition(original.copy(isMasterEnabled = false, userConsentGiven = false))
    }

    @Test fun replacingCredentialDuringCellularAcquisitionPreventsTheOldProof() = runBlocking {
        revokeDuringAcquisition(original.copy(authHeaderValue = "synthetic-replacement-hub-credential-only",
            forwarderSecret = "synthetic-replacement-hub-credential-only"))
    }

    @Test fun replacingEndpointDuringCellularAcquisitionPreventsTheOldProof() = runBlocking {
        revokeDuringAcquisition(original.copy(endpointUrl = "https://replacement.example.test/api/v1/otp/sms-forwarder"))
    }

    @Test fun replacingDeviceDuringCellularAcquisitionPreventsTheOldProof() = runBlocking {
        revokeDuringAcquisition(original.copy(deviceIdentifier = "synthetic-hub-device-two"))
    }

    @Test fun unchangedConsentedHubCanProveWithoutChangingItsPrivateProfile() = runBlocking {
        val work = async(Dispatchers.IO) { HubBootstrapWorker(context, parameters()).doWork() }
        try {
            assertTrue(PausedHubCellularAcquisition.reached.await(5, TimeUnit.SECONDS))
            PausedHubCellularAcquisition.release()
            assertEquals(ListenableWorker.Result.success(), withTimeout(5000) { work.await() })
            assertEquals(listOf("synthetic-owned-hub-credential-only"), requests.toList())
            assertEquals(original, repository.getConfig())
        } finally { PausedHubCellularAcquisition.release(); work.cancelAndJoin() }
    }
}

/** Resume the actual acquire invocation only after the test commits the replacement/stop. */
@Implements(CellularEgress::class, isInAndroidSdk = false)
class PausedHubCellularAcquisition {
    @Implementation fun isVpnActive(context: android.content.Context): Boolean = true
    @Implementation fun acquire(context: android.content.Context, timeoutMs: Long,
        continuation: Continuation<CellularEgressSession?>): Any {
        check(timeoutMs == 4000L)
        check(pending.compareAndSet(null, continuation))
        reached.countDown()
        return COROUTINE_SUSPENDED
    }
    companion object {
        @JvmField var reached = CountDownLatch(1)
        @JvmField val pending = AtomicReference<Continuation<CellularEgressSession?>?>()
        lateinit var session: CellularEgressSession
        fun release() { pending.getAndSet(null)?.resume(session) }
    }
}

/** A granted physical-network binding still uses real TCP/TLS with certificate validation enabled. */
@Implements(CellularEgressSession::class, isInAndroidSdk = false)
class OwnedHubNetworkBinding {
    @Implementation fun bind(base: OkHttpClient): OkHttpClient = requireNotNull(client)
    companion object { @JvmField var client: OkHttpClient? = null }
}
