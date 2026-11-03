package com.example

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.model.ForwardConfig
import com.example.data.repository.SmsForwardRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ManagedDefaultsTest {
    @get:Rule val secureStorage = SecureStorageTestRule()

    @Test fun missingPublicChannelsAreSeededWithoutReplacingExistingPrivateIdentity() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repository = SmsForwardRepository(db, appContext = context)
            val previous = ForwardConfig(endpointUrl = "https://fleet.example.test/api/v1/otp/sms-forwarder/09120000001",
                driverPhone = "09120000001", authHeaderValue = "a".repeat(64), forwarderSecret = "a".repeat(64),
                isMasterEnabled = false, userConsentGiven = false, setupCompleted = true,
                fallbackServerPhoneNumber = "09120000002", hubIrancellPhoneNumber = "09350000003")
            repository.saveConfig(previous)
            val restored = repository.getConfig()
            val role = if (BuildConfig.APP_ROLE == "DRIVER") "driver" else "hub"
            assertEquals("https://github.com/amir-hdri/SMS-Forwarder-Pro/releases/latest/download/barpro-$role.config.signed", restored.configManifestUrl)
            assertEquals("https://github.com/amir-hdri/SMS-Forwarder-Pro/releases/latest/download/barpro-$role.update.signed", restored.updateManifestUrl)
            assertEquals(previous, restored.copy(configManifestUrl = "", updateManifestUrl = ""))
        } finally { db.close() }
    }

    @Test fun publicDefaultsCannotInventAFreshHubCredentialOrConsent() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val fresh = SmsForwardRepository(db, appContext = context).getConfig()
            assertEquals("", fresh.authHeaderValue)
            assertEquals("", fresh.forwarderSecret)
            assertFalse(fresh.isMasterEnabled)
            assertFalse(fresh.userConsentGiven)
        } finally { db.close() }
    }
}
