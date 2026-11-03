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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ReleaseDownloadRecoveryTest {
    @Test fun aFailedDownloadCanRetryTheSameAuthenticatedReleaseWithoutANewPublication() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val bytes = "apk".toByteArray()
        val payload = JSONObject().put("audience", context.packageName).put("version", 70001)
            .put("issuedAt", System.currentTimeMillis() / 1000).put("versionCode", 999).put("versionName", "owned-fixture")
            .put("apkUrl", "https://fixture.example.test/app.apk").put("apkSizeBytes", bytes.size)
            .put("apkSha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        val segment = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().toByteArray())
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private); update(segment.toByteArray(Charsets.US_ASCII)); sign()
        }
        val document = segment + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
        var downloads = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val manifest = chain.request().url.encodedPath.endsWith("manifest")
            val code = if (manifest || ++downloads > 1) 200 else 503
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("owned")
                .body((if (manifest) document else "apk").toResponseBody("application/octet-stream".toMediaType())).build()
        }.build()
        val manager = AppUpdateManager(context, Base64.getEncoder().encodeToString(pair.public.encoded), httpClient = client)
        val first = manager.check("https://fixture.example.test/manifest") as UpdateCheck.Available
        assertTrue(manager.download(first.manifest) is StageResult.Failed)
        val resumed = manager.check("https://fixture.example.test/manifest")
        assertTrue("A seen manifest is still pending until its APK is installed", resumed is UpdateCheck.Available)
        assertTrue(manager.download((resumed as UpdateCheck.Available).manifest) is StageResult.Staged)
        assertEquals(2, downloads)
    }
}
