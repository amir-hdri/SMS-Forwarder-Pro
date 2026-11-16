package com.example

import android.app.Application
import com.example.data.model.ForwardConfig
import com.example.enrollment.EnrollmentCredentials
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class EnrollmentIdentityTest {
    private fun grant(phone: String, name: String = "") = EnrollmentCredentials(phone,
        "https://fleet.example.test/api/v1/otp/sms-forwarder/$phone", "a".repeat(64), "09120000002", "09350000003", name)

    @Test fun aDifferentProvedPhoneCannotInheritAnotherDriversName() {
        val previous = ForwardConfig(driverPhone = "09120000001", driverFullName = "Previous driver")
        val updated = grant("09350000002").applyTo(previous, "owned-device")
        assertEquals("09350000002", updated.driverPhone)
        assertEquals("", updated.driverFullName)
    }

    @Test fun samePhoneRediscoveryRetainsTheEstablishedNameWithoutANewName() {
        val previous = ForwardConfig(driverPhone = "+989120000001", driverFullName = "Established driver")
        assertEquals("Established driver", grant("09120000001").applyTo(previous, "owned-device").driverFullName)
    }

    @Test fun aFreshNameBelongsToTheNewProvedIdentity() {
        val previous = ForwardConfig(driverPhone = "09120000001", driverFullName = "Previous driver")
        assertEquals("New driver", grant("09350000002", "New driver").applyTo(previous, "owned-device").driverFullName)
    }
}
