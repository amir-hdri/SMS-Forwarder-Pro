package com.example

import android.app.Application
import android.util.Base64
import com.example.enrollment.DeviceBootstrapKey
import com.example.enrollment.HubInstallContract
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyFactory
import java.security.KeyPair
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/** Python production crypto generated this fixture with synthetic test-only capability, token and RSA key. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HubPythonInteropTest {
    @Test fun productionAndroidCryptoDecryptsAndSignsThePythonDeviceScopedHubFixture() {
        val fixture = JSONObject(javaClass.getResourceAsStream("/hub-server-grant-v1.json")!!.bufferedReader().use { it.readText() })
        val factory = KeyFactory.getInstance("RSA")
        val privateDer = fixture.getString("private_key").lineSequence().filterNot { it.startsWith("-----") }.joinToString("")
        val public = factory.generatePublic(X509EncodedKeySpec(Base64.decode(fixture.getString("public_key"), Base64.NO_WRAP)))
        val private = factory.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privateDer, Base64.NO_WRAP)))
        val key = DeviceBootstrapKey { KeyPair(public, private) }
        val id = fixture.getString("device_id")
        val nonce = fixture.getString("nonce")
        assertEquals(id, key.hubDeviceId())
        val decrypted = key.decrypt(fixture.getJSONObject("grant"), nonce, id)
        val expected = fixture.getJSONObject("credentials")
        val root = expected.getString("endpoint_url").toHttpUrl().newBuilder().encodedPath("/").build()
        val credentials = HubInstallContract.parseCredentials(decrypted, root, id)
        assertEquals(expected.getString("webhook_token"), credentials.token)
        assertEquals(expected.getString("hub_phone_mci"), credentials.hubMci)
        assertEquals(expected.getString("hub_phone_irancell"), credentials.hubIrancell)
        assertTrue(Signature.getInstance("SHA256withRSA").run {
            initVerify(public); update("barpro-bootstrap-v2\n$nonce\n$id".toByteArray())
            verify(Base64.decode(key.signature(nonce, id, DeviceBootstrapKey.AUTOMATIC_PROTOCOL), Base64.NO_WRAP))
        })
    }
}
