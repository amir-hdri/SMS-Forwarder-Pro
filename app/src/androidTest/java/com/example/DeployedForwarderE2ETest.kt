package com.example

import android.Manifest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.example.crypto.SecureStorageManager
import com.example.data.local.AppDatabase
import com.example.data.repository.SmsForwardRepository
import kotlinx.coroutines.runBlocking
import java.net.URL
import javax.net.ssl.HttpsURLConnection

@RunWith(AndroidJUnit4::class)
class DeployedForwarderE2ETest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun shell(value: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(value)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    @Test fun driverHomeAndSettingsDoNotOfferOperatorInputs() {
        assumeTrue(BuildConfig.APP_ROLE == "DRIVER")
        val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        for (permission in listOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS,
            Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_PHONE_STATE)) shell("pm grant $pkg $permission")
        shell("cmd deviceidle whitelist +$pkg")
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitForIdle()
                compose.onNodeWithTag("managed_driver_screen").assertIsDisplayed()
                compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
                compose.onNodeWithTag("top_settings_action").performClick()
                compose.waitForIdle()
                compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
                for (tag in listOf("advanced_settings_card", "endpoint_url_field", "auth_header_value_field"))
                    compose.onNodeWithTag(tag).assertDoesNotExist()
            }
        } finally { shell("cmd deviceidle whitelist -$pkg") }
    }

    @Test fun installedAppTrustsTheEmbeddedCaForTheConfiguredServerTlsConnection() {
        val connection = URL("https://87.107.160.251/healthz").openConnection() as HttpsURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        try {
            assertEquals(200, connection.responseCode)
            assertTrue(connection.cipherSuite.startsWith("TLS_"))
            assertTrue(connection.serverCertificates.isNotEmpty())
        } finally { connection.disconnect() }
    }

    @Test fun version2UpgradeRetainsEnrollmentAndItsEncryptedCredential() = runBlocking {
        assumeTrue(BuildConfig.APP_ROLE == "DRIVER")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("v2-data-preservation-fixture", context.getSharedPreferences("barpro_upgrade_verification", 0)
            .getString("marker", null))
        val config = SmsForwardRepository.getInstance(context).getConfig()
        assertEquals("upgrade-fixture-driver", config.driverId)
        assertEquals("راننده آزمایشی ارتقا", config.driverFullName)
        assertEquals("09120000001", config.driverPhone)
        assertTrue(config.setupCompleted)
        assertFalse(config.isMasterEnabled)
        assertFalse(config.userConsentGiven)
        assertEquals("a".repeat(64), SecureStorageManager.getInstance(context).getForwarderSecret())
        val raw = AppDatabase.getDatabase(context).forwardConfigDao().getConfig()!!
        assertTrue(raw.authHeaderValue.isEmpty())
        assertTrue(raw.forwarderSecret.isEmpty())
        assertEquals("https://87.107.160.251/api/v1/otp/sms-forwarder/09120000001", config.endpointUrl)
    }
}
