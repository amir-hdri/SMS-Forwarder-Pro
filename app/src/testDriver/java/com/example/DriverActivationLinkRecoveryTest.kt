package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.SecureStorageManager
import com.example.data.local.AppDatabase
import com.example.data.model.ForwardConfig
import com.example.data.repository.SmsForwardRepository
import com.example.enrollment.ActivationRequiredException
import com.example.enrollment.BootstrapNeedsPermissions
import com.example.enrollment.DriverActivationRuntime
import com.example.enrollment.EnrollmentCredentials
import com.example.enrollment.EnrollmentLink
import com.example.enrollment.EnrollmentManager
import com.example.enrollment.EnrollmentStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

/** Real activation/Room/secure-stage ownership; only server and carrier boundaries are synthetic. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DriverActivationLinkRecoveryTest {
    @get:Rule val secureStorage = SecureStorageTestRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val repositoryField = SmsForwardRepository::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
    private val runtimeField = DriverActivationRuntime::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private var previousRepository: Any? = null
    private var previousRuntime: Any? = null
    private lateinit var database: AppDatabase
    private lateinit var repository: SmsForwardRepository
    private lateinit var runtime: DriverActivationRuntime
    private val storage get() = SecureStorageManager.getInstance(context)
    private val phoneA = "09120000001"
    private val phoneB = "09350000004"
    private val device = "synthetic-device"
    private val endpoint = "https://87.107.160.251/api/v1/otp/sms-forwarder/$phoneA"
    private val link = "barpro-forwarder://enroll#ticket=single-use-synthetic-ticket-123456&server=" +
        java.net.URLEncoder.encode("https://87.107.160.251", "UTF-8")
    private var permissions = false

    @Before fun prepare() = runBlocking(Dispatchers.IO) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        previousRepository = repositoryField.get(null)
        previousRuntime = runtimeField.get(null)
        context.getSharedPreferences("barpro_device_identity", Context.MODE_PRIVATE).edit().clear()
            .putString("enrollment_device_id", device).commit()
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

    private fun credentials(phone: String = phoneB) = EnrollmentCredentials(phone,
        "https://87.107.160.251/api/v1/otp/sms-forwarder/$phone", "a".repeat(64), "09120000002", "09350000003")

    private fun installManager(
        redeem: suspend (EnrollmentLink, String) -> EnrollmentCredentials = { _, id ->
            require(id == device)
            credentials()
        },
        resolve: suspend (String, String, String) -> EnrollmentCredentials = { phone, id, token ->
            require(id == device && token == "a".repeat(64))
            credentials(phone)
        },
        bootstrap: suspend (String, String) -> EnrollmentCredentials = { phone, id ->
            require(id == device)
            credentials(phone.ifBlank { phoneA })
        }
    ) {
        val manager = EnrollmentManager(endpoint, "DRIVER", { device }, redeem,
            repository::getConfig, repository::saveConfig, storage::savePendingEnrollment,
            storage::getPendingEnrollment, { permissions }, {}, resolve, bootstrap)
        DriverActivationRuntime::class.java.getDeclaredField("enrollment").apply { isAccessible = true }.set(runtime, manager)
    }

    private fun oldPhoneIntent() = storage.savePendingBootstrap(
        JSONObject().put("phone", phoneA).put("device_id", device).put("requested_name", "").toString())

    private fun restart() {
        runtimeField.set(null, null)
        runtime = DriverActivationRuntime.get(context)
    }

    @After fun close() {
        database.close()
        repositoryField.set(null, previousRepository)
        runtimeField.set(null, previousRuntime)
        context.getSharedPreferences("barpro_device_identity", Context.MODE_PRIVATE).edit().clear().commit()
        Dispatchers.resetMain()
    }

    @Test fun aNewLinkKeepsItsPhoneAfterPermissionsEvenWithADisabledOlderProfile() = runBlocking(Dispatchers.IO) {
        repository.saveConfig(credentials(phoneA).applyTo(ForwardConfig(), device)
            .copy(isMasterEnabled = false, userConsentGiven = false))
        val previous = repository.getConfig()
        oldPhoneIntent()
        runtime.receive(link)
        assertEquals(EnrollmentStatus.NEEDS_PERMISSIONS, runtime.enrollment.status.value)
        assertEquals(previous, repository.getConfig())
        assertEquals(phoneB, JSONObject(requireNotNull(storage.getPendingEnrollment())).getString("driver_phone"))
        permissions = true
        runtime.activateAutomatically()
        assertEquals("The explicit link's proved phone must outrank the retired phone intent", phoneB, repository.getConfig().driverPhone)
        assertEquals(EnrollmentStatus.ACTIVE, runtime.enrollment.status.value)
        assertTrue(repository.getConfig().userConsentGiven)
        assertNull(storage.getPendingBootstrap())
    }

    @Test fun anExactRepeatedPendingLinkSurvivesPermissionsAndProcessRestart() = runBlocking(Dispatchers.IO) {
        var redemptions = 0
        var validations = 0
        installManager(redeem = { _, _ -> redemptions++; credentials() },
            resolve = { phone, id, token ->
                require(phone == phoneB && id == device && token == "a".repeat(64))
                validations++
                credentials()
            })
        oldPhoneIntent()
        runtime.receive(link)
        runtime.receive(link)
        assertEquals("A second accepted link must remain resumable before permissions", EnrollmentStatus.NEEDS_PERMISSIONS,
            runtime.enrollment.status.value)
        assertFalse(DriverActivationRuntime.isActivationStopped(context))
        assertEquals(1, redemptions)
        assertEquals(1, validations)
        restart()
        installManager(redeem = { _, _ -> error("A used ticket must not be redeemed again after restart") },
            resolve = { phone, id, token ->
                require(phone == phoneB && id == device && token == "a".repeat(64))
                credentials()
            })
        runtime.receive(link)
        assertEquals(EnrollmentStatus.NEEDS_PERMISSIONS, runtime.enrollment.status.value)
        permissions = true
        runtime.activateAutomatically()
        assertEquals(phoneB, repository.getConfig().driverPhone)
        assertEquals(EnrollmentStatus.ACTIVE, runtime.enrollment.status.value)
    }

    @Test fun aFailedNewLinkPreservesTheProfileButCannotRecoverTheRetiredIntent() = runBlocking(Dispatchers.IO) {
        repository.saveConfig(credentials(phoneA).applyTo(ForwardConfig(), device))
        val previous = repository.getConfig()
        storage.savePendingEnrollment(credentials(phoneA).document())
        val previousStage = storage.getPendingEnrollment()
        oldPhoneIntent()
        installManager(redeem = { _, _ -> throw java.io.IOException("Synthetic unavailable redemption") })
        runtime.receive(link)
        assertEquals(EnrollmentStatus.FAILED, runtime.enrollment.status.value)
        assertEquals(previous, repository.getConfig())
        assertEquals(previousStage, storage.getPendingEnrollment())
        assertTrue(DriverActivationRuntime.isActivationStopped(context))
        assertNull(storage.getPendingBootstrap())
        permissions = true
        restart()
        installManager(resolve = { _, _, _ -> error("A failed link cannot resume the retired request") })
        runtime.activateAutomatically()
        assertEquals(previous, repository.getConfig())
        assertEquals(EnrollmentStatus.IDLE, runtime.enrollment.status.value)
    }

    @Test fun aStopDuringRedemptionWinsOverANonCooperativeLateGrant() = runBlocking(Dispatchers.IO) {
        withTimeout(5000) {
            val previous = repository.getConfig()
            val reached = CompletableDeferred<Unit>()
            val response = CompletableDeferred<EnrollmentCredentials>()
            installManager(redeem = { _, _ ->
                reached.complete(Unit)
                withContext(NonCancellable) { response.await() }
            })
            val activation = launch(start = CoroutineStart.UNDISPATCHED) { runtime.receive(link) }
            reached.await()
            runtime.stopPendingActivation()
            response.complete(credentials())
            activation.join()
            assertTrue(DriverActivationRuntime.isActivationStopped(context))
            assertEquals(previous, repository.getConfig())
            assertNull(storage.getPendingEnrollment())
            assertNull(storage.getPendingBootstrap())
            assertEquals(EnrollmentStatus.IDLE, runtime.enrollment.status.value)
        }
    }

    @Test fun aRepeatedLinkCannotResumeADifferentPhonesSubstitutedStage() = runBlocking(Dispatchers.IO) {
        runtime.receive(link)
        storage.savePendingEnrollment(credentials(phoneA).document())
        permissions = true
        runtime.receive(link)
        runtime.activateAutomatically()
        assertEquals("", repository.getConfig().driverPhone)
        assertFalse(repository.getConfig().setupCompleted)
        assertTrue(DriverActivationRuntime.isActivationStopped(context))
    }

    @Test fun aRepeatedRevokedGrantKeepsOnlyItsOwnCarrierRecoveryIntent() = runBlocking(Dispatchers.IO) {
        runtime.receive(link)
        installManager(resolve = { phone, id, token ->
            require(phone == phoneB && id == device && token == "a".repeat(64))
            throw ActivationRequiredException()
        }, bootstrap = { phone, id ->
            require(phone == phoneB && id == device)
            if (!permissions) throw BootstrapNeedsPermissions()
            credentials()
        })
        runtime.receive(link)
        assertEquals(EnrollmentStatus.NEEDS_PERMISSIONS, runtime.enrollment.status.value)
        assertNull("A revoked grant cannot remain a usable credential", storage.getPendingEnrollment())
        assertFalse("An explicit accepted phone intent may continue genuine carrier recovery",
            DriverActivationRuntime.isActivationStopped(context))
        assertEquals(phoneB, JSONObject(requireNotNull(storage.getPendingBootstrap())).getString("phone"))
        permissions = true
        runtime.activateAutomatically()
        assertEquals(phoneB, repository.getConfig().driverPhone)
        assertEquals(EnrollmentStatus.ACTIVE, runtime.enrollment.status.value)
    }

    @Test fun aDuplicateAlreadyActiveLinkPreservesAuthenticatedProfileRecovery() = runBlocking(Dispatchers.IO) {
        permissions = true
        runtime.receive(link)
        val previous = repository.getConfig()
        runtime.receive(link)
        assertEquals(previous, repository.getConfig())
        assertFalse("A no-op repeated active link must not stop the working profile's recovery",
            DriverActivationRuntime.isActivationStopped(context))
        runtime.activateAutomatically()
        assertEquals(EnrollmentStatus.ACTIVE, runtime.enrollment.status.value)
        assertEquals(previous, repository.getConfig())
    }
}
