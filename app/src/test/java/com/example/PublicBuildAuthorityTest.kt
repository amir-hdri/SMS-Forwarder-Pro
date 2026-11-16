package com.example

import android.app.Application
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PublicBuildAuthorityTest {
    @Test fun distributableBuildHasNoSharedWebhookAuthority() {
        assertTrue("A public role APK must obtain its own scoped grant, not ship a shared authority",
            BuildConfig.DEFAULT_WEBHOOK_TOKEN.isEmpty())
    }
}
