package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.crypto.SecureStorageManager
import com.example.data.local.AppDatabase
import com.example.data.model.ForwardConfig
import com.example.data.repository.SmsForwardRepository
import com.example.enrollment.DeviceBootstrapKey
import com.example.update.AppUpdateManager
import com.example.update.UpdateStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit controlled fixture; never supplies a real carrier proof or Hub install capability. */
@RunWith(AndroidJUnit4::class)
class NativeUpgradePolicyTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val marker get() = context.getSharedPreferences("barpro_native_upgrade_qa", 0)
    private fun fixtureToken() = if (BuildConfig.APP_ROLE == "HUB")
        "HUB1_" + "a".repeat(16) + "_" + "b".repeat(64) else "a".repeat(64)

    @Test fun stageExplicitSyntheticUpgradeFixture() = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("syntheticUpgradeFixture") == "true")
        assertEquals(7, AppUpdateManager(context, "").installedVersionCode())
        val key = DeviceBootstrapKey()
        SmsForwardRepository.getInstance(context).saveConfig(ForwardConfig(
            driverId = "adb-upgrade-fixture", driverFullName = "راننده آزمایشی ارتقا",
            driverPhone = "09120000001", setupCompleted = true,
            endpointUrl = "https://87.107.160.251/api/v1/otp/sms-forwarder" +
                if (BuildConfig.APP_ROLE == "DRIVER") "/09120000001" else "",
            authHeaderValue = fixtureToken(), forwarderSecret = fixtureToken(),
            deviceIdentifier = if (BuildConfig.APP_ROLE == "HUB") key.hubDeviceId() else "synthetic-device",
            isMasterEnabled = false, userConsentGiven = false, autoUpdateEnabled = false,
            configManifestUrl = "", updateManifestUrl = ""))
        val store = UpdateStore.getInstance(context)
        store.recordConfigRequirement(8, 7, 100)
        store.recordReleaseRequirement(8, 7, 100)
        assertTrue(store.blockedByMandatoryUpdate)
        check(marker.edit().putString("fixture", "controlled-adb-upgrade-v1")
            .putString("public_key", key.publicKey()).commit())
    }

    @Test fun manualReplacementPreservesPrivateIdentityAndExplicitStop() = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("syntheticUpgradeFixture") == "true")
        assertEquals("controlled-adb-upgrade-v1", marker.getString("fixture", null))
        assertEquals(8, AppUpdateManager(context, "").installedVersionCode())
        assertFalse("Application startup must reconcile the actual manually installed APK",
            UpdateStore.getInstance(context).blockedByMandatoryUpdate)
        val config = SmsForwardRepository.getInstance(context).getConfig()
        assertEquals("adb-upgrade-fixture", config.driverId)
        assertEquals("09120000001", config.driverPhone)
        assertTrue(config.setupCompleted)
        assertFalse(config.isMasterEnabled)
        assertFalse(config.userConsentGiven)
        assertFalse(config.autoUpdateEnabled)
        assertEquals(fixtureToken(), SecureStorageManager.getInstance(context).getForwarderSecret())
        assertEquals(marker.getString("public_key", null), DeviceBootstrapKey().publicKey())
        val raw = AppDatabase.getDatabase(context).forwardConfigDao().getConfig()!!
        assertEquals("", raw.authHeaderValue)
        assertEquals("", raw.forwarderSecret)
    }
}
