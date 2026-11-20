package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import com.example.crypto.SecureStorageManager
import com.example.data.local.AppDatabase
import com.example.data.model.ForwardConfig
import com.example.data.repository.SmsForwardRepository
import com.example.enrollment.DriverActivationRuntime
import com.example.enrollment.DriverBootstrapWorker
import com.example.enrollment.EnrollmentCredentials
import com.example.enrollment.EnrollmentManager
import com.example.enrollment.EnrollmentStatus
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Real Room/Keystore-adapter/WorkManager handoffs; modem, HTTP and worker execution remain absent. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DriverActivationOwnershipTest {
    @get:Rule val secureStorage = SecureStorageTestRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val repositoryField = SmsForwardRepository::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
    private val runtimeField = DriverActivationRuntime::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private val workManagerField = WorkManagerImpl::class.java.getDeclaredField("sDefaultInstance").apply { isAccessible = true }
    private var previousRepository: Any? = null
    private var previousRuntime: Any? = null
    private var previousWorkManager: Any? = null
    private lateinit var database: AppDatabase
    private lateinit var repository: SmsForwardRepository
    private lateinit var runtime: DriverActivationRuntime
    private var workManager: WorkManagerImpl? = null
    private val tasks = LinkedBlockingQueue<Runnable>()
    private val dispatcher = UnconfinedTestDispatcher()
    private val phone = "09120000001"
    private val endpoint = "https://87.107.160.251/api/v1/otp/sms-forwarder/$phone"

    @Before fun prepare() = runBlocking(Dispatchers.IO) {
        Dispatchers.setMain(dispatcher)
        previousRepository = repositoryField.get(null)
        previousRuntime = runtimeField.get(null)
        previousWorkManager = workManagerField.get(null)
        workManagerField.set(null, null)
        WorkManagerImpl.setDelegate(null)
        context.getSharedPreferences("barpro_device_identity", Context.MODE_PRIVATE).edit().clear()
            .putString("enrollment_device_id", "synthetic-device").commit()
        shadowOf(context).grantPermissions(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS)
        shadowOf(context.getSystemService(ConnectivityManager::class.java)).setActiveNetworkInfo(null)
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = SmsForwardRepository(database, appContext = context)
        repository.saveConfig(ForwardConfig(isMasterEnabled = false, userConsentGiven = false))
        repositoryField.set(null, repository)
        runtimeField.set(null, null)
        runtime = DriverActivationRuntime.get(context)
        installManager()
    }

    private fun installManager(getConfig: suspend () -> ForwardConfig = repository::getConfig,
        resolveCredentials: suspend (String, String, String) -> EnrollmentCredentials = { requestedPhone, _, token ->
            require(token == "a".repeat(64))
            credentials(requestedPhone)
        }) {
        val storage = SecureStorageManager.getInstance(context)
        val manager = EnrollmentManager(endpoint, "DRIVER", { "synthetic-device" },
            redeem = { _, _ -> error("No activation link") }, getConfig = getConfig,
            saveConfig = repository::saveConfig, stage = storage::savePendingEnrollment,
            loadStage = storage::getPendingEnrollment, permissionsGranted = { true }, onActivated = {},
            resolve = resolveCredentials,
            bootstrap = { provedPhone, _ -> credentials(provedPhone.ifBlank { phone }) })
        DriverActivationRuntime::class.java.getDeclaredField("enrollment").apply { isAccessible = true }.set(runtime, manager)
    }

    private fun credentials(provedPhone: String = phone) = EnrollmentCredentials(provedPhone,
        "https://87.107.160.251/api/v1/otp/sms-forwarder/$provedPhone", "a".repeat(64), "09120000002", "09350000003")

    @Test fun aDurableStopDuringRetainedValidationRejectsTheLateGrant() = runBlocking {
        withTimeout(5000) {
            repository.saveConfig(credentials().applyTo(ForwardConfig(), "synthetic-device"))
            val previous = repository.getConfig()
            val reached = CompletableDeferred<Unit>()
            val response = CompletableDeferred<EnrollmentCredentials>()
            installManager(resolveCredentials = { _, _, _ ->
                reached.complete(Unit)
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { response.await() }
            })
            val activation = launch(start = CoroutineStart.UNDISPATCHED) { runtime.activateAutomatically() }
            reached.await()
            runtime.stopPendingActivation()
            repository.updateConfig { it.copy(isMasterEnabled = false) }
            response.complete(credentials())
            activation.join()
            assertTrue(DriverActivationRuntime.isActivationStopped(context))
            assertEquals(previous.copy(isMasterEnabled = false), repository.getConfig())
            assertNull(SecureStorageManager.getInstance(context).getPendingEnrollment())
            assertEquals(EnrollmentStatus.IDLE, runtime.enrollment.status.value)
        }
    }

    @After fun close() {
        workManager?.workDatabase?.close()
        WorkManagerImpl.setDelegate(null)
        workManagerField.set(null, previousWorkManager)
        database.close()
        repositoryField.set(null, previousRepository)
        runtimeField.set(null, previousRuntime)
        context.getSharedPreferences("barpro_device_identity", Context.MODE_PRIVATE).edit().clear().commit()
        Dispatchers.resetMain()
    }

    private fun startWorkManager(): WorkManagerImpl {
        val executor = Executor { tasks.add(it) }
        WorkManager.initialize(context, Configuration.Builder().setTaskExecutor(executor)
            .setExecutor(Executor { /* Worker execution is deliberately paused. */ }).build())
        return (WorkManager.getInstance(context) as WorkManagerImpl).also { workManager = it; drainTasks() }
    }

    private fun drainTasks() {
        var count = 0
        while (true) {
            val task = tasks.poll() ?: return
            check(count++ < 1000) { "WorkManager task queue failed to settle" }
            task.run()
        }
    }

    private fun completeEnqueue(operation: Operation) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!operation.result.isDone && System.nanoTime() < deadline) tasks.poll(50, TimeUnit.MILLISECONDS)?.run()
        operation.result.get(3, TimeUnit.SECONDS)
    }

    @Test fun freshInstallBackgroundRecoveryDoesNotStartAnAnonymousActivation() = runBlocking(Dispatchers.IO) {
        runtime.activateAutomatically()
        val stored = repository.getConfig()
        assertFalse("Opening the app must leave the manual phone flow available", stored.setupCompleted)
        assertFalse(stored.isMasterEnabled)
        assertEquals("", stored.driverPhone)
        assertEquals(EnrollmentStatus.IDLE, runtime.enrollment.status.value)
    }

    @Test fun anInvalidNamedRequestCannotModifyTheRetainedPrivateProfile() = runBlocking(Dispatchers.IO) {
        val previous = repository.getConfig().copy(driverFullName = "Established driver")
        repository.saveConfig(previous)
        try { runtime.activate("0912", "Unproved entered name"); fail("Invalid phone must be rejected") }
        catch (_: IllegalArgumentException) { }
        assertEquals(previous, repository.getConfig())
    }

    @Test fun freshInstallResumeDoesNotEnqueueAnActivationWorker() = runBlocking(Dispatchers.IO) {
        val manager = startWorkManager()
        val operation = DriverBootstrapWorker.enqueue(context)
        if (operation != null) completeEnqueue(operation)
        assertTrue("No pending activation means no background owner", manager.workDatabase.workSpecDao().getAllWorkSpecIds().isEmpty())
    }

    @Test fun backgroundResumePreservesTheExistingRunningActivation() = runBlocking(Dispatchers.IO) {
        val manager = startWorkManager()
        SecureStorageManager.getInstance(context).savePendingBootstrap(JSONObject()
            .put("phone", phone).put("device_id", "synthetic-device").toString())
        completeEnqueue(requireNotNull(DriverBootstrapWorker.enqueue(context)))
        val original = manager.workDatabase.workSpecDao().getAllWorkSpecIds().single()
        manager.workDatabase.workSpecDao().setState(WorkInfo.State.RUNNING, original)
        completeEnqueue(requireNotNull(DriverBootstrapWorker.enqueue(context)))
        assertEquals("A lifecycle refresh must not cancel or restart the owning work", listOf(original),
            manager.workDatabase.workSpecDao().getAllWorkSpecIds())
        assertEquals(WorkInfo.State.RUNNING, manager.workDatabase.workSpecDao().getWorkSpec(original)!!.state)
    }

    @Test fun staleBackgroundProgressCannotDisableIdleManualActivation() {
        runtime.progress.value = "Synthetic stale background progress"
        val viewModel = MainViewModel(context)
        dispatcher.scheduler.runCurrent()
        assertFalse("Only an owned running activation may show loading", viewModel.driverActivationState.value.isLoading)
        assertNull("Idle onboarding must not display stale worker progress", viewModel.driverActivationState.value.progressMessage)
    }

    @Test fun aRestartCannotResumeAnotherPhonesGrantAfterAManualPhoneReplacement() = runBlocking(Dispatchers.IO) {
        withTimeout(5000) {
            val requestedPhone = "09350000004"
            val storage = SecureStorageManager.getInstance(context)
            storage.savePendingEnrollment(credentials().document())
            val reached = CompletableDeferred<Unit>()
            val response = CompletableDeferred<Unit>()
            installManager(getConfig = {
                reached.complete(Unit)
                response.await()
                repository.getConfig()
            })
            val foreground = launch(start = CoroutineStart.UNDISPATCHED) { runtime.activate(requestedPhone) }
            reached.await()
            foreground.cancelAndJoin()
            // Discard only process memory: real encrypted stages and Room state survive.
            runtimeField.set(null, null)
            runtime = DriverActivationRuntime.get(context)
            installManager()
            runtime.activateAutomatically()
            val stored = repository.getConfig()
            assertEquals("Restart recovery must continue the selected phone's proof, never the previous decoded grant",
                requestedPhone, stored.driverPhone)
            assertEquals(EnrollmentStatus.ACTIVE, runtime.enrollment.status.value)
        }
    }

    @Test fun explicitStopSurvivesRestartEvenWithARetainedDecodedGrant() = runBlocking(Dispatchers.IO) {
        val storage = SecureStorageManager.getInstance(context)
        repository.saveConfig(credentials().applyTo(ForwardConfig(driverFullName = "Synthetic Driver"), "synthetic-device"))
        val previous = repository.getConfig()
        runtime.reset()
        storage.savePendingEnrollment(credentials().document())
        runtimeField.set(null, null)
        runtime = DriverActivationRuntime.get(context)
        installManager()
        runtime.activateAutomatically()
        assertEquals("A stale grant cannot revoke the persisted user stop after process restart",
            previous.copy(setupCompleted = false, isMasterEnabled = false, userConsentGiven = false), repository.getConfig())
        assertEquals(EnrollmentStatus.IDLE, runtime.enrollment.status.value)
    }

    @Test fun restartCannotIgnoreTheExpectedNameBecauseAnOlderProfileIsEnrolled() = runBlocking(Dispatchers.IO) {
        val storage = SecureStorageManager.getInstance(context)
        val grant = EnrollmentCredentials(phone, endpoint, "a".repeat(64), "09120000002", "09350000003", "Registered driver")
        repository.saveConfig(grant.applyTo(ForwardConfig(), "synthetic-device"))
        val previous = repository.getConfig()
        storage.savePendingEnrollment(grant.document())
        storage.savePendingBootstrap(JSONObject().put("phone", phone).put("device_id", "synthetic-device")
            .put("requested_name", "Different driver").toString())
        runtime.activateAutomatically()
        assertEquals(previous, repository.getConfig())
        assertEquals(EnrollmentStatus.FAILED, runtime.enrollment.status.value)
        assertFalse(runtime.enrollment.failureRetryable)
    }

    @Test fun genuineSamePhonePendingGrantSurvivesProcessRestart() = runBlocking(Dispatchers.IO) {
        val storage = SecureStorageManager.getInstance(context)
        storage.savePendingEnrollment(credentials().document())
        runtimeField.set(null, null)
        runtime = DriverActivationRuntime.get(context)
        installManager()
        runtime.activateAutomatically()
        val stored = repository.getConfig()
        assertEquals(phone, stored.driverPhone)
        assertTrue(stored.isMasterEnabled)
        assertTrue(stored.userConsentGiven)
        assertNull(storage.getPendingEnrollment())
    }

    @Test fun explicitUserRetryReplacesItsExistingPendingWork() = runBlocking(Dispatchers.IO) {
        val manager = startWorkManager()
        SecureStorageManager.getInstance(context).savePendingBootstrap(JSONObject()
            .put("phone", phone).put("device_id", "synthetic-device").toString())
        completeEnqueue(requireNotNull(DriverBootstrapWorker.enqueue(context)))
        val original = manager.workDatabase.workSpecDao().getAllWorkSpecIds().single()
        manager.workDatabase.workSpecDao().setState(WorkInfo.State.RUNNING, original)
        completeEnqueue(requireNotNull(DriverBootstrapWorker.enqueue(context, userInitiated = true)))
        val pending = manager.workDatabase.workSpecDao().getAllWorkSpecIds()
            .map { manager.workDatabase.workSpecDao().getWorkSpec(it)!! }.filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(1, pending.size)
        assertNotEquals("Explicit retry must give the user's new request its own work", original, pending.single().id)
    }
}
