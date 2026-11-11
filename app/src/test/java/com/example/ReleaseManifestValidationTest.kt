package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.update.*
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ReleaseManifestValidationTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val key = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private var response = ""
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body(response.toResponseBody("text/plain".toMediaType())).build()
    }.build()
    private val manager get() = AppUpdateManager(context, Base64.getEncoder().encodeToString(key.public.encoded),
        httpClient = client)

    @Before fun isolatePolicyState() {
        context.getSharedPreferences("barpro_update_state", 0).edit().clear().commit()
        UpdateStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }

    private fun payload(version: Long = 1) = JSONObject()
        .put("audience", context.packageName).put("version", version)
        .put("issuedAt", System.currentTimeMillis() / 1000).put("versionCode", 999)
        .put("versionName", "synthetic").put("apkUrl", "https://fixture.example.test/app.apk")
        .put("apkSha256", "a".repeat(64)).put("apkSizeBytes", 100)

    private fun serve(value: JSONObject) {
        val segment = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toString().toByteArray())
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(key.private); update(segment.toByteArray(Charsets.US_ASCII)); sign()
        }
        response = segment + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
    }

    @Test fun anAuthenticReleaseForAnotherAppIsARejectionRatherThanUpToDate() = runBlocking {
        serve(payload().put("audience", "another.app"))
        assertTrue(manager.check("https://fixture.example.test/manifest") is UpdateCheck.Failed)
        assertNull(UpdateStore.getInstance(context).lastVerifiedManifestDocument)
    }

    @Test fun anAuthenticMalformedReleaseDoesNotReportTheInstalledAppAsUpToDate() = runBlocking {
        serve(payload().put("apkSha256", "invalid"))
        assertTrue(manager.check("https://fixture.example.test/manifest") is UpdateCheck.Failed)
        assertNull(UpdateStore.getInstance(context).lastVerifiedManifestDocument)
    }

    @Test fun onlyAValidOlderReleaseIsTheNormalNoUpdateState() = runBlocking {
        serve(payload(2))
        assertTrue(manager.check("https://fixture.example.test/manifest") is UpdateCheck.Available)
        val saved = UpdateStore.getInstance(context).lastVerifiedManifestDocument
        serve(payload(1))
        assertTrue(manager.check("https://fixture.example.test/manifest") is UpdateCheck.UpToDate)
        serve(payload(1).put("apkSha256", "invalid"))
        assertTrue(manager.check("https://fixture.example.test/manifest") is UpdateCheck.Failed)
        assertEquals(saved, UpdateStore.getInstance(context).lastVerifiedManifestDocument)
    }
}
