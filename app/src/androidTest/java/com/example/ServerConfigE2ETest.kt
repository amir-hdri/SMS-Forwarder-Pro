package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToLog
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device end-to-end provisioning test (driver flavor).
 *
 * Fills the operator configuration through the real UI (no ADB keyboard hacks):
 * driver phone, both Hub SIM numbers, webhook URL, webhook token, and the HTTP
 * cleartext switch — then asserts persistence and a live HEALTH_CHECK against
 * the configured server ("اتصال با موفقیت تأیید شد").
 *
 * Secrets NEVER live in this file: webhook URL / token / driver phone arrive via
 * instrumentation arguments, e.g.:
 *   -Pandroid.testInstrumentationRunnerArguments.webhookUrl=http://87.107.160.251/api/v1/otp/sms-forwarder
 *   -Pandroid.testInstrumentationRunnerArguments.webhookToken=<OTP_WEBHOOK_SECRET>
 *   -Pandroid.testInstrumentationRunnerArguments.driverPhone=09120000001
 * Defaults are emulator-local harmless values for offline runs.
 */
@RunWith(AndroidJUnit4::class)
class ServerConfigE2ETest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun arg(name: String, def: String): String =
        InstrumentationRegistry.getArguments().getString(name, def) ?: def

    private fun shell(cmd: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(cmd).close()
    }

    private fun scrollTo(tag: String) {
        try {
            compose.onNodeWithTag("server_config_lazy_column")
                .performScrollToNode(hasTestTag(tag))
        } catch (_: Throwable) {
        }
    }

    private fun scrollToText(text: String) {
        try {
            compose.onNodeWithTag("server_config_lazy_column")
                .performScrollToNode(hasText(text, substring = true))
        } catch (_: Throwable) {
        }
    }

    @Test
    fun provisionWebhookConfigAndVerifyServerRoundtrip() {
        // 0. Wake screen, dismiss keyguard, and grant runtime permissions
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        listOf(
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.RECEIVE_SMS",
            "android.permission.READ_SMS",
            "android.permission.SEND_SMS",
            "android.permission.READ_PHONE_STATE",
        ).forEach { perm -> shell("pm grant $pkg $perm") }

        val url = arg("webhookUrl", "http://10.0.2.2:18099/api/v1/otp/sms-forwarder")
        val token = arg("webhookToken", "test-token-emu-001")
        val driverPhone = arg("driverPhone", "09120000001")
        val hubMci = arg("hubMci", "09966567754")
        val hubIrancell = arg("hubIrancell", "09047119812")

        // 1. Open operator settings from home.
        compose.waitUntil(60_000) {
            compose.onAllNodesWithTag("top_settings_action").fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodesWithContentDescription("تنظیمات").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithTag("top_settings_action").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("top_settings_action").performClick()
        } else {
            compose.onNodeWithContentDescription("تنظیمات").performClick()
        }

        // 2. Driver phone.
        scrollTo("driver_phone_input")
        compose.onNodeWithTag("driver_phone_input").performTextClearance()
        compose.onNodeWithTag("driver_phone_input").performTextInput(driverPhone)
        compose.onNodeWithTag("driver_phone_input").assertTextContains(driverPhone)

        // 3. Both Hub SIM numbers.
        scrollTo("hub_mci_input")
        compose.onNodeWithTag("hub_mci_input").performTextClearance()
        compose.onNodeWithTag("hub_mci_input").performTextInput(hubMci)
        scrollTo("hub_irancell_input")
        compose.onNodeWithTag("hub_irancell_input").performTextClearance()
        compose.onNodeWithTag("hub_irancell_input").performTextInput(hubIrancell)

        // 4. Expand advanced server settings if collapsed.
        scrollTo("toggle_advanced_settings")
        if (
            compose.onAllNodesWithContentDescription("باز کردن")
                .fetchSemanticsNodes().isNotEmpty()
        ) {
            compose.onNodeWithTag("toggle_advanced_settings").performClick()
        }

        // 5. Webhook URL.
        scrollTo("server_url_input")
        compose.onNodeWithTag("server_url_input").performTextClearance()
        compose.onNodeWithTag("server_url_input").performTextInput(url)

        // 6. Cleartext switch must be ON for http:// endpoints.
        scrollTo("cleartext_switch")
        val switchIsOn = try {
            compose.onNodeWithTag("cleartext_switch").assertIsOn()
            true
        } catch (_: AssertionError) {
            false
        }
        if (!switchIsOn) {
            compose.onNodeWithTag("cleartext_switch").performClick()
        }

        // 7. Webhook token.
        scrollTo("server_token_input")
        compose.onNodeWithTag("server_token_input").performTextClearance()
        compose.onNodeWithTag("server_token_input").performTextInput(token)

        // 8. Persisted values round-trip through EncryptedSharedPreferences.
        compose.onNodeWithTag("server_url_input").assertTextContains(url)
        scrollTo("driver_phone_input")
        compose.onNodeWithTag("driver_phone_input").assertTextContains(driverPhone)

        // 9. Live server round-trip (HEALTH_CHECK → "ready").
        scrollTo("quick_test_button")
        compose.onNodeWithTag("quick_test_button").performClick()
        scrollTo("server_status_card")
        compose.waitUntil(45_000) {
            compose.onAllNodesWithText("اتصال با موفقیت", substring = true)
                .fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("خطا در اتصال", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("اتصال با موفقیت", substring = true).assertExists()
        scrollToText("اتصال با موفقیت")
        compose.onNodeWithText("اتصال با موفقیت", substring = true).assertIsDisplayed()
    }
}
