package com.example

import android.Manifest
import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.ForwardConfig
import com.example.ui.screens.ManagedDriverScreen
import com.example.ui.viewmodel.DriverActivationUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AutomaticDriverScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun permissionApprovedFreshDriverOffersPhoneInputAndAutoBootstrapFallback() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS,
            Manifest.permission.POST_NOTIFICATIONS)
        val claims = mutableListOf<String>()
        compose.setContent {
            MaterialTheme { ManagedDriverScreen(ForwardConfig(), {}, {}, {}, activationState = DriverActivationUiState(),
                onActivateDriver = { claims.add(it) }, onResetActivation = {}) }
        }
        compose.waitForIdle()
        compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).assertCountEquals(2)
        compose.onNodeWithTag("driver_name_input").assertExists()
        compose.onNodeWithTag("driver_phone_input").assertExists()
        compose.onNodeWithTag("btn_auto_activate").assertExists()
        compose.onNodeWithTag("btn_fallback_auto_activate").assertExists()
        assertTrue("Fresh driver screen does not trigger unprompted background activation", claims.isEmpty())

        // Clicking fallback auto-activation triggers bootstrap with empty phone
        compose.onNodeWithTag("btn_fallback_auto_activate").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("Fallback button triggers auto-activation with empty phone", listOf(""), claims)
    }

    @Test fun typingDriverPhoneNumberEnablesManualResolution() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS,
            Manifest.permission.POST_NOTIFICATIONS)
        val claims = mutableListOf<String>()
        compose.setContent {
            MaterialTheme { ManagedDriverScreen(ForwardConfig(), {}, {}, {}, activationState = DriverActivationUiState(),
                onActivateDriver = { claims.add(it) }, onResetActivation = {}) }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("driver_phone_input").performScrollTo().performTextInput("09382173202")
        compose.waitForIdle()
        compose.onNodeWithTag("driver_phone_input").assertTextContains("09382173202")

        compose.onNodeWithTag("btn_auto_activate").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals("Entering driver phone triggers manual resolution with phone number", listOf("09382173202"), claims)
    }

    @Test fun fallbackAutoActivationIsAvailableWhenPhoneNumberIsEntered() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS,
            Manifest.permission.POST_NOTIFICATIONS)
        val claims = mutableListOf<String>()
        compose.setContent {
            MaterialTheme { ManagedDriverScreen(ForwardConfig(), {}, {}, {}, activationState = DriverActivationUiState(),
                onActivateDriver = { claims.add(it) }, onResetActivation = {}) }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("driver_phone_input").performScrollTo().performTextInput("09382173202")
        compose.waitForIdle()

        compose.onNodeWithTag("btn_fallback_auto_activate").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals("Fallback button triggers auto-activation with empty phone", listOf(""), claims)
    }

    @Test fun enteringDriverNameAndPhoneTriggersResolutionWithName() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS,
            Manifest.permission.POST_NOTIFICATIONS)
        val claims = mutableListOf<Pair<String, String>>()
        compose.setContent {
            MaterialTheme { ManagedDriverScreen(ForwardConfig(), {}, {}, {}, activationState = DriverActivationUiState(),
                onActivateDriverWithName = { phone, name -> claims.add(phone to name) }, onResetActivation = {}) }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("driver_name_input").performScrollTo().performTextInput("محمد رضایی")
        compose.waitForIdle()
        compose.onNodeWithTag("driver_name_input").assertTextContains("محمد رضایی")

        compose.onNodeWithTag("driver_phone_input").performScrollTo().performTextInput("09121112233")
        compose.waitForIdle()
        compose.onNodeWithTag("driver_phone_input").assertTextContains("09121112233")

        compose.onNodeWithTag("btn_auto_activate").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(listOf("09121112233" to "محمد رضایی"), claims)
    }
}
