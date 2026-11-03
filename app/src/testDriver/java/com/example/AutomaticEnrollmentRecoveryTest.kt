package com.example

import android.Manifest
import android.app.Application
import android.telephony.SmsManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.SecureStorageManager
import com.example.data.local.AppDatabase
import com.example.data.model.ForwardConfig
import com.example.data.repository.SmsForwardRepository
import com.example.enrollment.DriverActivationRuntime
import com.example.enrollment.EnrollmentCredentials
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSubscriptionManager

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AutomaticEnrollmentRecoveryTest {
    @get:Rule val secureStorage = SecureStorageTestRule()

    @Test fun retainedGrantCannotReenableAnAlreadyEnrolledExplicitlyStoppedDriver() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS)
        ShadowSubscriptionManager.setDefaultSmsSubscriptionId(17)
        val repositoryField = SmsForwardRepository::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
        val runtimeField = DriverActivationRuntime::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previousRepository = repositoryField.get(null); val previousRuntime = runtimeField.get(null)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repository = SmsForwardRepository(db, appContext = context)
        val disabled = ForwardConfig(driverPhone = "09120000001", setupCompleted = true,
            endpointUrl = "https://87.107.160.251/api/v1/otp/sms-forwarder/09120000001",
            authHeaderValue = "a".repeat(64), forwarderSecret = "a".repeat(64),
            isMasterEnabled = false, userConsentGiven = false)
        val modem = context.getSystemService(SmsManager::class.java).createForSubscriptionId(17)
        shadowOf(modem).clearLastSentTextMessageParams()
        try {
            repository.saveConfig(disabled)
            repositoryField.set(null, repository); runtimeField.set(null, null)
            SecureStorageManager.getInstance(context).savePendingEnrollment(EnrollmentCredentials(disabled.driverPhone,
                disabled.endpointUrl, "a".repeat(64), "09120000002", "09350000003").document())
            DriverActivationRuntime.get(context).activateAutomatically()
            val restored = repository.getConfig()
            assertFalse("A retained stage must not undo explicit forwarding stop", restored.isMasterEnabled)
            assertFalse(restored.userConsentGiven)
            assertEquals(disabled.endpointUrl, restored.endpointUrl)
            assertEquals(disabled.authHeaderValue, restored.authHeaderValue)
            assertNull("Recovery must not send liveness for an explicitly stopped driver", shadowOf(modem).lastSentTextMessageParams)
        } finally {
            repositoryField.set(null, previousRepository); runtimeField.set(null, previousRuntime); db.close()
        }
    }
}
