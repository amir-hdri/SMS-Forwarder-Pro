package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.example.data.model.ForwardConfig
import com.example.network.ServerHealthState
import com.example.ui.screens.ServerConfigScreen
import com.example.ui.viewmodel.EncryptionSandboxState
import com.example.ui.viewmodel.EndpointTestState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [34], qualifiers = "w412dp-h915dp")
class ManagedDriverSettingsTest {
    @get:Rule val compose = createComposeRule()

    // This catches editable operator fields reachable from the actual Driver branch,
    // including fields a LazyColumn composes only after scrolling.
    @Test fun settingsExposeOnlyTheControlsPermittedForTheirInstalledRole() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.height(540.dp)) {
                    ServerConfigScreen(
                        config = ForwardConfig(driverPhone = "09120000001", setupCompleted = true),
                        serverHealthState = ServerHealthState(), testState = EndpointTestState(),
                        cryptoSandboxState = EncryptionSandboxState(), onSaveConfig = {},
                        onRunTest = { _, _, _, _, _, _, _ -> }, onCheckServerHealth = {},
                        onTestDisconnectNotification = {}, onTestCryptoSandbox = { _, _ -> },
                        onGenerateKey = { "synthetic" }, onOpenServerGuide = {}
                    )
                }
            }
        }
        if (BuildConfig.APP_ROLE == "DRIVER") {
            repeat(18) {
                compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).assertCountEquals(0)
                compose.onAllNodes(hasScrollAction()).onFirst().performTouchInput { swipeUp() }
            }
            compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).assertCountEquals(0)
            compose.onNodeWithText("09120000001").assertExists()
        } else {
            compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasSetTextAction())
            compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().assertExists()
        }
    }
}
