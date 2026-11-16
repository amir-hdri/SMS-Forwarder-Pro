package com.example

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.impl.WorkManagerImpl
import com.example.crypto.SecureStorageManager
import com.example.data.local.AppDatabase
import com.example.data.model.ForwardConfig
import com.example.data.repository.SmsForwardRepository
import com.example.enrollment.DriverActivationRuntime
import com.example.enrollment.EnrollmentCredentials
import com.example.enrollment.EnrollmentManager
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DriverActivationViewModelTest {
    @get:Rule val secureStorage = SecureStorageTestRule()

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var app: Application
    private lateinit var viewModel: MainViewModel
    private lateinit var database: AppDatabase
    private lateinit var repository: SmsForwardRepository
    private val store = ViewModelStore()
    private val repositoryField = SmsForwardRepository::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
    private val runtimeField = DriverActivationRuntime::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private val workManagerField = WorkManagerImpl::class.java.getDeclaredField("sDefaultInstance").apply { isAccessible = true }
    private var previousRepository: Any? = null
    private var previousRuntime: Any? = null
    private var previousWorkManager: Any? = null
    private val bootstrapReached = CompletableDeferred<Unit>()
    private val grant = CompletableDeferred<EnrollmentCredentials>()
    private var requestedPhone: String? = null

    @Before fun setUp() = runBlocking {
        Dispatchers.setMain(testDispatcher)
        app = ApplicationProvider.getApplicationContext()
        previousRepository = repositoryField.get(null)
        previousRuntime = runtimeField.get(null)
        previousWorkManager = workManagerField.get(null)
        workManagerField.set(null, null)
        app.getSharedPreferences("barpro_device_identity", Context.MODE_PRIVATE).edit().clear()
            .putString("enrollment_device_id", "synthetic-device").commit()
        database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        repository = SmsForwardRepository(database, appContext = app)
        repository.saveConfig(ForwardConfig(isMasterEnabled = false, userConsentGiven = false))
        repositoryField.set(null, repository)
        runtimeField.set(null, null)
        val runtime = DriverActivationRuntime.get(app)
        val storage = SecureStorageManager.getInstance(app)
        val manager = EnrollmentManager("https://87.107.160.251/api/v1/otp/sms-forwarder", "DRIVER", { "synthetic-device" },
            redeem = { _, _ -> error("No activation link") }, getConfig = repository::getConfig,
            saveConfig = repository::saveConfig, stage = storage::savePendingEnrollment,
            loadStage = storage::getPendingEnrollment, permissionsGranted = { true }, onActivated = {},
            bootstrap = { phone, _ ->
                requestedPhone = phone
                bootstrapReached.complete(Unit)
                grant.await()
            })
        DriverActivationRuntime::class.java.getDeclaredField("enrollment").apply { isAccessible = true }.set(runtime, manager)
        viewModel = MainViewModel(app)
        store.put("driver", viewModel)
    }

    private fun activationJob(): Job? = MainViewModel::class.java.getDeclaredField("driverActivationJob")
        .apply { isAccessible = true }.get(viewModel) as Job?

    @After fun tearDown() = runBlocking {
        activationJob()?.cancelAndJoin()
        store.clear()
        database.close()
        repositoryField.set(null, previousRepository)
        runtimeField.set(null, previousRuntime)
        workManagerField.set(null, previousWorkManager)
        app.getSharedPreferences("barpro_device_identity", Context.MODE_PRIVATE).edit().clear().commit()
        Dispatchers.resetMain()
    }

    private suspend fun completeActivation(rawPhone: String, expectedRequest: String) = withTimeout(5000) {
        viewModel.resolveAndActivateDriver(rawPhone)
        bootstrapReached.await()
        testDispatcher.scheduler.runCurrent()
        assertTrue("A pending grant must keep the requested activation loading", viewModel.driverActivationState.value.isLoading)
        assertNull(viewModel.driverActivationState.value.successMessage)
        assertFalse(repository.getConfig().setupCompleted)
        assertEquals(expectedRequest, requestedPhone)
        grant.complete(EnrollmentCredentials("09120000001", "https://87.107.160.251/api/v1/otp/sms-forwarder/09120000001",
            "a".repeat(64), "09120000002", "09350000003", driverName = "Synthetic Driver"))
        activationJob()!!.join()
        testDispatcher.scheduler.runCurrent()
        assertEquals("09120000001", repository.getConfig().driverPhone)
        assertTrue(repository.getConfig().setupCompleted)
        assertFalse(viewModel.driverActivationState.value.isLoading)
        assertNotNull(viewModel.driverActivationState.value.successMessage)
        assertEquals("Synthetic Driver", viewModel.driverActivationState.value.resolvedDriverName)
    }

    @Test fun invalidPhoneNumberRejectsImmediatelyWithHelpfulError() {
        viewModel.resolveAndActivateDriver("0912")
        val state = viewModel.driverActivationState.value
        assertFalse("State must not be loading for invalid phone", state.isLoading)
        assertEquals("شماره موبایل وارد شده نامعتبر است (فرمت صحیح: 09xxxxxxxxx)", state.errorMessage)
    }

    @Test fun nonIranianPhoneNumberRejectsImmediately() {
        viewModel.resolveAndActivateDriver("01234567890")
        val state = viewModel.driverActivationState.value
        assertFalse("State must not be loading for non-Iranian phone", state.isLoading)
        assertEquals("شماره موبایل وارد شده نامعتبر است (فرمت صحیح: 09xxxxxxxxx)", state.errorMessage)
    }

    @Test fun blankPhoneUsesOnlyTheReturnedCarrierIdentity() = runBlocking { completeActivation("", "") }

    @Test fun validPhoneWaitsForItsGrantBeforeClaimingActivation() = runBlocking { completeActivation("09120000001", "09120000001") }

    @Test fun persianDigitsReachTheOwnedActivationAsAnAsciiPhone() = runBlocking { completeActivation("۰۹۱۲۰۰۰۰۰۰۱", "09120000001") }

    @Test fun internationalFormatReachesTheOwnedActivationAsAnIranianPhone() = runBlocking { completeActivation("+989120000001", "09120000001") }

    @Test fun resetDriverActivationClearsUiStateAfterTheOwnerIsCancelled() = runBlocking {
        viewModel.resolveAndActivateDriver("09120000001")
        withTimeout(5000) { bootstrapReached.await(); viewModel.resetDriverActivation().join() }
        testDispatcher.scheduler.runCurrent()
        val state = viewModel.driverActivationState.value
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertNull(state.successMessage)
        assertFalse(repository.getConfig().setupCompleted)
        assertNull(SecureStorageManager.getInstance(app).getPendingBootstrap())
    }

    @Test fun normalizeActivationLinkHandlesStandardAndCustomFormats() {
        val standard = "barpro-forwarder://enroll#ticket=ticket12345678901234567890&server=https%3A%2F%2F87.107.160.251%2F"
        assertEquals(standard, com.example.ui.screens.normalizeActivationLink(standard))

        val httpsUrl = "https://87.107.160.251/enroll#ticket=abc12345678901234567890&server=https%3A%2F%2F87.107.160.251%2F"
        val normalizedFromHttps = com.example.ui.screens.normalizeActivationLink(httpsUrl)
        assertTrue(normalizedFromHttps.startsWith("barpro-forwarder://enroll#ticket=abc12345678901234567890"))

        val bareTicket = "single-use-ticket-sample-1234567890"
        val normalizedFromTicket = com.example.ui.screens.normalizeActivationLink(bareTicket)
        assertTrue(normalizedFromTicket.startsWith("barpro-forwarder://enroll#ticket=single-use-ticket-sample-1234567890"))
    }

    @Test fun bootstrapPendingSetsFailedStatusAndErrorMessage() = kotlinx.coroutines.runBlocking {
        var staged: String? = null
        val manager = com.example.enrollment.EnrollmentManager(
            defaultEndpoint = "https://87.107.160.251/api/v1/otp/sms-forwarder",
            role = "DRIVER",
            deviceId = { "dev-id" },
            redeem = { _, _ -> error("No link") },
            getConfig = { com.example.data.model.ForwardConfig() },
            saveConfig = {},
            stage = { staged = it },
            loadStage = { staged },
            permissionsGranted = { true },
            onActivated = {},
            resolve = { _, _ -> throw com.example.enrollment.ActivationRequiredException() },
            bootstrap = { _, _ -> throw com.example.enrollment.BootstrapPending() }
        )

        manager.resolveDriverConfiguration("09382173202")
        assertEquals(com.example.enrollment.EnrollmentStatus.FAILED, manager.status.value)
        assertNotNull(manager.errorMessage.value)
        assertTrue("Error message must inform driver about timeout", manager.errorMessage.value!!.contains("تأیید پیامک توسط هاب"))
        assertTrue("Failure must be retryable", manager.failureRetryable)
    }

    @Test fun freshManualPhoneRequiresCarrierProofBeforeActivation() = kotlinx.coroutines.runBlocking {
        var provedPhone: String? = null
        var stagedDoc: String? = null
        var savedConfig: com.example.data.model.ForwardConfig? = null

        val manager = com.example.enrollment.EnrollmentManager(
            defaultEndpoint = "https://87.107.160.251/api/v1/otp/sms-forwarder",
            role = "DRIVER",
            deviceId = { "dev-id-123" },
            redeem = { _, _ -> error("No link") },
            getConfig = { com.example.data.model.ForwardConfig() },
            saveConfig = { savedConfig = it },
            stage = { stagedDoc = it },
            loadStage = { stagedDoc },
            permissionsGranted = { true },
            onActivated = {},
            resolve = { _, _ -> error("A fresh install has no rediscovery credential") },
            bootstrap = { phone, _ ->
                provedPhone = phone
                com.example.enrollment.EnrollmentCredentials(
                    driverPhone = phone,
                    endpointUrl = "https://87.107.160.251/api/v1/otp/sms-forwarder/$phone",
                    webhookToken = "a".repeat(64),
                    hubMci = "09120000002",
                    hubIrancell = "09350000003",
                    driverName = "Synthetic Driver"
                )
            }
        )

        manager.resolveDriverConfiguration("09120000001")
        assertEquals(com.example.enrollment.EnrollmentStatus.ACTIVE, manager.status.value)
        assertEquals("09120000001", provedPhone)
        assertNotNull(savedConfig)
        assertEquals("09120000001", savedConfig!!.driverPhone)
        assertEquals("Synthetic Driver", savedConfig!!.driverFullName)
        assertTrue(savedConfig!!.isMasterEnabled)
        assertTrue(savedConfig!!.userConsentGiven)
    }
}
