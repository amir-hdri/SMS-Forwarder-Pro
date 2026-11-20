package com.example

import android.app.Application
import com.example.data.model.ForwardConfig
import com.example.enrollment.EnrollmentCredentials
import com.example.enrollment.EnrollmentManager
import com.example.enrollment.EnrollmentStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Delayed external results exercise the real staging/configuration owner; no network or SMS runs. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class EnrollmentOwnershipTest {
    private val phone = "09120000001"
    private val endpoint = "https://87.107.160.251/api/v1/otp/sms-forwarder/$phone"
    private fun credentials() = EnrollmentCredentials(phone, endpoint, "a".repeat(64), "09120000002", "09350000003")

    @Test fun cancellingARequestRejectsItsNonCooperativeLateCredential() = runBlocking {
        withTimeout(5000) {
            var config = ForwardConfig()
            var pending: String? = null
            val reached = CompletableDeferred<Unit>()
            val response = CompletableDeferred<EnrollmentCredentials>()
            val manager = EnrollmentManager(endpoint, "DRIVER", { "synthetic-device" },
                redeem = { _, _ -> error("No activation link") }, getConfig = { config }, saveConfig = { config = it },
                stage = { pending = it }, loadStage = { pending }, permissionsGranted = { true }, onActivated = {},
                bootstrap = { _, _ ->
                    reached.complete(Unit)
                    try { response.await() } catch (_: CancellationException) { credentials() }
                })
            val activation = launch(start = CoroutineStart.UNDISPATCHED) { manager.resolveDriverConfiguration(phone) }
            reached.await()
            activation.cancel()
            activation.join()
            assertFalse("A cancelled result must not enable SMS forwarding", config.isMasterEnabled)
            assertFalse(config.setupCompleted)
            assertEquals("", config.driverPhone)
            assertNull("A cancelled result must not stage reusable credentials", pending)
            assertEquals("Cancellation must release the loading state", EnrollmentStatus.IDLE, manager.status.value)
        }
    }

    @Test fun resetRevokesTheInflightOwnerBeforeItsGrantCanActivate() = runBlocking {
        withTimeout(5000) {
            var config = ForwardConfig()
            var pending: String? = null
            val reached = CompletableDeferred<Unit>()
            val response = CompletableDeferred<EnrollmentCredentials>()
            val manager = EnrollmentManager(endpoint, "DRIVER", { "synthetic-device" },
                redeem = { _, _ -> error("No activation link") }, getConfig = { config }, saveConfig = { config = it },
                stage = { pending = it }, loadStage = { pending }, permissionsGranted = { true }, onActivated = {},
                bootstrap = { _, _ ->
                    reached.complete(Unit)
                    try { response.await() } catch (_: CancellationException) { credentials() }
                })
            val activation = launch(start = CoroutineStart.UNDISPATCHED) { manager.resolveDriverConfiguration(phone) }
            reached.await()
            val reset = launch(start = CoroutineStart.UNDISPATCHED) { manager.reset() }
            response.complete(credentials())
            activation.join()
            reset.join()
            assertFalse("Reset must win over a delayed credential", config.isMasterEnabled)
            assertFalse(config.userConsentGiven)
            assertFalse(config.setupCompleted)
            assertNull(pending)
            assertEquals(EnrollmentStatus.IDLE, manager.status.value)
        }
    }

    @Test fun resetStopsForwardingWhileRetainingTheValidPrivateProfile() = runBlocking {
        var config = credentials().applyTo(ForwardConfig(driverFullName = "Synthetic Driver"), "synthetic-device")
        val previous = config
        var pending: String? = credentials().document()
        val manager = EnrollmentManager(endpoint, "DRIVER", { "synthetic-device" },
            redeem = { _, _ -> error("No activation link") }, getConfig = { config }, saveConfig = { config = it },
            stage = { pending = it }, loadStage = { pending }, permissionsGranted = { true }, onActivated = {})
        manager.reset()
        assertEquals("Reset must not erase the existing private identity or routes",
            previous.copy(setupCompleted = false, isMasterEnabled = false, userConsentGiven = false), config)
        assertNull(pending)
    }

    @Test fun anotherPhonesStoredCredentialCannotInterceptManualCarrierActivation() = runBlocking {
        val requestedPhone = "09350000004"
        val requestedEndpoint = "https://87.107.160.251/api/v1/otp/sms-forwarder/$requestedPhone"
        var config = credentials().applyTo(ForwardConfig(), "synthetic-device")
        var pending: String? = null
        val manager = EnrollmentManager(endpoint, "DRIVER", { "synthetic-device" },
            redeem = { _, _ -> error("No activation link") }, getConfig = { config }, saveConfig = { config = it },
            stage = { pending = it }, loadStage = { pending }, permissionsGranted = { true }, onActivated = {},
            resolve = { _, _, _ -> throw java.io.IOException("Synthetic unavailable rediscovery route for the other phone") },
            bootstrap = { phone, _ ->
                require(phone == requestedPhone)
                EnrollmentCredentials(requestedPhone, requestedEndpoint, "b".repeat(64), "09120000002", "09350000003", "Entered name for new phone")
            })
        manager.resolveDriverConfiguration(requestedPhone, driverName = "Entered name for new phone")
        assertEquals("A different entered phone requires its carrier proof, rather than reusing another phone's credential",
            requestedPhone, config.driverPhone)
        assertEquals(requestedEndpoint, config.endpointUrl)
        assertEquals(EnrollmentStatus.ACTIVE, manager.status.value)
    }

    @Test fun failedNamedActivationPreservesThePreviouslyProvedProfile() = runBlocking {
        var config = credentials().applyTo(ForwardConfig(), "synthetic-device").copy(driverFullName = "Established driver")
        val previous = config
        var pending: String? = null
        val manager = EnrollmentManager(endpoint, "DRIVER", { "synthetic-device" },
            redeem = { _, _ -> error("No activation link") }, getConfig = { config }, saveConfig = { config = it },
            stage = { pending = it }, loadStage = { pending }, permissionsGranted = { true }, onActivated = {},
            resolve = { _, _, _ -> throw com.example.enrollment.ActivationRequiredException() },
            bootstrap = { _, _ -> throw java.io.IOException("Synthetic carrier proof unavailable") })
        manager.resolveDriverConfiguration("09350000004", driverName = "Unproved entered name")
        assertEquals("A failed proof must leave the existing identity, token and consent intact", previous, config)
        assertNull(pending)
        assertEquals(EnrollmentStatus.FAILED, manager.status.value)
    }

    @Test fun aTypedNameMustMatchTheCompleteAuthenticatedNameBeforeStaging() = runBlocking {
        for (expectedName in listOf("علی", "Different driver")) {
            var config = ForwardConfig()
            var pending: String? = null
            val previous = config
            val manager = EnrollmentManager(endpoint, "DRIVER", { "synthetic-device" },
                redeem = { _, _ -> error("No activation link") }, getConfig = { config }, saveConfig = { config = it },
                stage = { pending = it }, loadStage = { pending }, permissionsGranted = { true }, onActivated = {},
                resolve = { _, _, _ -> throw com.example.enrollment.ActivationRequiredException() },
                bootstrap = { _, _ -> EnrollmentCredentials(phone, endpoint, "a".repeat(64), "09120000002", "09350000003", "علی احمدی") })
            manager.resolveDriverConfiguration(phone, driverName = expectedName)
            assertEquals("A partial or different name must not install credentials", previous, config)
            assertNull("An unmatched grant must not become a reusable decoded stage", pending)
            assertEquals(EnrollmentStatus.FAILED, manager.status.value)
            assertFalse("An identity mismatch needs corrected user input", manager.failureRetryable)
        }
    }

    @Test fun commonPersianLetterAndWhitespaceFormsMatchTheAuthenticatedFullName() = runBlocking {
        var config = ForwardConfig()
        var pending: String? = null
        val manager = EnrollmentManager(endpoint, "DRIVER", { "synthetic-device" },
            redeem = { _, _ -> error("No activation link") }, getConfig = { config }, saveConfig = { config = it },
            stage = { pending = it }, loadStage = { pending }, permissionsGranted = { true }, onActivated = {},
            resolve = { _, _, _ -> throw com.example.enrollment.ActivationRequiredException() },
            bootstrap = { _, _ -> EnrollmentCredentials(phone, endpoint, "a".repeat(64), "09120000002", "09350000003", "علی کاظمی") })
        manager.resolveDriverConfiguration(phone, driverName = "  علي\u00a0  كاظمي  ")
        assertEquals(EnrollmentStatus.ACTIVE, manager.status.value)
        assertEquals("علی کاظمی", config.driverFullName)
    }

    @Test fun aRetainedDecodedGrantCannotBypassTheUsersExpectedName() = runBlocking {
        var config = ForwardConfig()
        val previous = config
        var pending: String? = EnrollmentCredentials(phone, endpoint, "a".repeat(64), "09120000002", "09350000003", "Registered driver").document()
        val manager = EnrollmentManager(endpoint, "DRIVER", { "synthetic-device" },
            redeem = { _, _ -> error("No activation link") }, getConfig = { config }, saveConfig = { config = it },
            stage = { pending = it }, loadStage = { pending }, permissionsGranted = { true }, onActivated = {},
            resolve = { _, _, token ->
                assertEquals("a".repeat(64), token)
                EnrollmentCredentials(phone, endpoint, token, "09120000002", "09350000003", "Registered driver")
            })
        manager.resolveDriverConfiguration(phone, driverName = "Different driver")
        assertEquals(previous, config)
        assertEquals(EnrollmentStatus.FAILED, manager.status.value)
    }
}
