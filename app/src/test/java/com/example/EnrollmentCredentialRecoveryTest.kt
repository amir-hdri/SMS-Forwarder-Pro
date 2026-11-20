package com.example

import android.app.Application
import com.example.data.model.ForwardConfig
import com.example.enrollment.ActivationRequiredException
import com.example.enrollment.EnrollmentCredentials
import com.example.enrollment.EnrollmentManager
import com.example.enrollment.EnrollmentStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Server revocation must be observed before a retained local credential becomes current authority. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class EnrollmentCredentialRecoveryTest {
    private val phone = "09120000001"
    private val other = "09350000004"
    private fun credential(number: String = phone, token: String = "a", name: String = "Registered driver") =
        EnrollmentCredentials(number, "https://87.107.160.251/api/v1/otp/sms-forwarder/$number",
            token.repeat(64), "09120000002", "09350000003", name)

    private class Profile(val initial: EnrollmentCredentials) {
        var config = initial.applyTo(ForwardConfig(), "synthetic-device")
        var pending: String? = null
    }

    private fun manager(profile: Profile,
        resolve: suspend (String, String, String) -> EnrollmentCredentials,
        bootstrap: suspend (String, String) -> EnrollmentCredentials = { _, _ -> error("Carrier proof was not authorized") }
    ) = EnrollmentManager(credential().endpointUrl, "DRIVER", { "synthetic-device" },
        redeem = { _, _ -> error("No activation link") }, getConfig = { profile.config },
        saveConfig = { profile.config = it }, stage = { profile.pending = it }, loadStage = { profile.pending },
        permissionsGranted = { true }, onActivated = {}, resolve = resolve, bootstrap = bootstrap)

    @Test fun aBlankRetainedActivationAuthenticatesItsOwnPhoneBeforeActive() = runBlocking {
        val profile = Profile(credential())
        var calls = 0
        val owner = manager(profile, resolve = { requested, _, token ->
            assertEquals(phone, requested)
            assertEquals(profile.config.forwarderSecret, token)
            calls++
            credential()
        })
        owner.resolveDriverConfiguration("")
        assertEquals("A local enrolled flag cannot skip server authentication", 1, calls)
        assertEquals(EnrollmentStatus.ACTIVE, owner.status.value)
        assertEquals(profile.initial.webhookToken, profile.config.forwarderSecret)
    }

    @Test fun revokedBlankCredentialUsesOwnedCarrierProofWithoutErasingTheProfile() = runBlocking {
        val profile = Profile(credential())
        val previous = profile.config
        var proofCalls = 0
        val owner = manager(profile, resolve = { requested, _, token ->
            assertEquals(phone, requested)
            assertEquals("a".repeat(64), token)
            throw ActivationRequiredException()
        }, bootstrap = { requested, _ ->
            assertEquals(phone, requested)
            assertEquals(previous, profile.config)
            proofCalls++
            credential(token = "c")
        })
        owner.resolveDriverConfiguration("")
        assertEquals(1, proofCalls)
        assertEquals("c".repeat(64), profile.config.forwarderSecret)
        assertEquals(EnrollmentStatus.ACTIVE, owner.status.value)
    }

    @Test fun aRetainedStageIsAuthenticatedBeforeResumeAppliesIt() = runBlocking {
        val profile = Profile(credential())
        profile.pending = credential(token = "b").document()
        var calls = 0
        val owner = manager(profile, resolve = { requested, _, token ->
            assertEquals(phone, requested)
            assertEquals("b".repeat(64), token)
            calls++
            credential(token = "b")
        })
        owner.resume()
        assertEquals("Resume cannot trust a decoded credential without server validation", 1, calls)
        assertEquals("b".repeat(64), profile.config.forwarderSecret)
        assertNull(profile.pending)
    }

    @Test fun revokedOtherPhoneStageCannotBorrowTheCurrentProfilesAuthority() = runBlocking {
        val profile = Profile(credential())
        val previous = profile.config
        profile.pending = credential(other, "b").document()
        var calls = 0
        val owner = manager(profile, resolve = { requested, _, token ->
            assertEquals(other, requested)
            assertEquals("b".repeat(64), token)
            assertNotEquals(profile.config.forwarderSecret, token)
            calls++
            throw ActivationRequiredException()
        }, bootstrap = { requested, _ ->
            assertEquals(other, requested)
            assertEquals(previous, profile.config)
            assertNull("A rejected stage cannot remain reusable authority", profile.pending)
            credential(other, "c")
        })
        owner.resume()
        assertEquals(1, calls)
        assertEquals(other, profile.config.driverPhone)
        assertEquals("c".repeat(64), profile.config.forwarderSecret)
    }

    @Test fun validationTransportFailureRetainsTheProfileAndPendingCredential() = runBlocking {
        val profile = Profile(credential())
        val previous = profile.config
        val stage = credential(token = "b").document()
        profile.pending = stage
        val owner = manager(profile, resolve = { _, _, _ -> throw java.io.IOException("Synthetic validation outage") })
        owner.resume()
        assertEquals(previous, profile.config)
        assertEquals(stage, profile.pending)
        assertEquals(EnrollmentStatus.FAILED, owner.status.value)
    }

    @Test fun approvedNameIsMatchedAgainstTheCurrentAuthenticatedResponse() = runBlocking {
        val profile = Profile(credential())
        profile.pending = credential(token = "b", name = "Old approved name").document()
        val owner = manager(profile, resolve = { _, _, _ -> credential(token = "c", name = "Current approved name") })
        owner.resolveDriverConfiguration(phone, driverName = "Current approved name")
        assertEquals(EnrollmentStatus.ACTIVE, owner.status.value)
        assertEquals("Current approved name", profile.config.driverFullName)
        assertEquals("c".repeat(64), profile.config.forwarderSecret)
    }

    @Test fun stopDuringCredentialValidationRejectsItsLateResponse() = runBlocking {
        withTimeout(3000) {
            val profile = Profile(credential())
            val previous = profile.config
            val reached = CompletableDeferred<Unit>()
            val response = CompletableDeferred<EnrollmentCredentials>()
            var proofCalls = 0
            val owner = manager(profile, resolve = { _, _, _ ->
                reached.complete(Unit)
                withContext(NonCancellable) { response.await() }
            }, bootstrap = { _, _ -> proofCalls++; credential(token = "c") })
            val activation = launch(start = CoroutineStart.UNDISPATCHED) { owner.resolveDriverConfiguration("") }
            reached.await()
            owner.cancelActiveOperation()
            response.complete(credential(token = "b"))
            activation.join()
            assertEquals(previous, profile.config)
            assertNull(profile.pending)
            assertEquals(0, proofCalls)
            assertEquals(EnrollmentStatus.IDLE, owner.status.value)
        }
    }
}
