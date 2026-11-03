package com.example

import android.app.Application
import com.example.data.model.ForwardConfig
import com.example.enrollment.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AutomaticEnrollmentManagerTest {
    @Test fun missingPhoneActivatesWithOnlyTheServerVerifiedCarrierIdentity() = runBlocking {
        var config = ForwardConfig()
        var pending: String? = null
        var bootstrapCalls = 0
        val manager = EnrollmentManager(config.endpointUrl, "DRIVER", { "synthetic-device" },
            redeem = { _, _ -> error("No links") }, getConfig = { config }, saveConfig = { config = it },
            stage = { pending = it }, loadStage = { pending }, permissionsGranted = { true }, onActivated = {},
            resolve = { _, _ -> error("No anonymous credential rediscovery") }, bootstrap = { phone, _ ->
                assertEquals("", phone); bootstrapCalls++
                EnrollmentCredentials("09120000001", "https://87.107.160.251/api/v1/otp/sms-forwarder/09120000001",
                    "a".repeat(64), "09120000002", "09350000003")
            })
        manager.resolveDriverConfiguration("")
        assertEquals(manager.errorMessage.value, EnrollmentStatus.ACTIVE, manager.status.value)
        assertEquals(1, bootstrapCalls)
        assertEquals("09120000001", config.driverPhone)
        assertTrue(config.isMasterEnabled)
        assertNull(pending)
    }
}
