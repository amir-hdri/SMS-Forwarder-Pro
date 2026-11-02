package com.example

import android.Manifest
import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.ForwardConfig
import com.example.ui.screens.ManagedDriverScreen
import com.example.ui.viewmodel.DriverActivationUiState
import org.junit.Assert.assertEquals
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

    @Test fun permissionApprovedFreshDriverStartsWithoutTypedIdentityOrTechnicalFields() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS,
            Manifest.permission.POST_NOTIFICATIONS)
        val claims = mutableListOf<String>()
        compose.setContent {
            MaterialTheme { ManagedDriverScreen(ForwardConfig(), {}, {}, {}, activationState = DriverActivationUiState(),
                onActivateDriver = { claims.add(it) }, onResetActivation = {}) }
        }
        compose.waitForIdle()
        compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).assertCountEquals(0)
        assertEquals("Permission approval starts one anonymous activation without a phone entry", listOf(""), claims)
    }
}
