package com.example

import com.example.update.RemoteConfig
import com.example.update.SignedPayload
import com.example.update.UpdateManifest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class SignedPayloadTest {

    companion object {
        private lateinit var keyPair: KeyPair
        private lateinit var anotherKeyPair: KeyPair
        private lateinit var publicKeyBase64: String
        private lateinit var anotherPublicKeyBase64: String

        @BeforeClass
        @JvmStatic
        fun setupKeys() {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1")) // prime256v1
            keyPair = kpg.generateKeyPair()
            anotherKeyPair = kpg.generateKeyPair()

            publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
            anotherPublicKeyBase64 = Base64.getEncoder().encodeToString(anotherKeyPair.public.encoded)
        }

        private fun signDocument(json: String, kp: KeyPair = keyPair): String {
            val payloadSegment = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))
            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(kp.private)
            signer.update(payloadSegment.toByteArray(Charsets.US_ASCII))
            val sigBytes = signer.sign()
            val sigSegment = Base64.getUrlEncoder().withoutPadding().encodeToString(sigBytes)
            return "$payloadSegment.$sigSegment"
        }
    }

    @Test
    fun testValidSignatureVerifiesSuccessfully() {
        val json = """{"audience":"test.app","version":1,"issuedAt":1000}"""
        val doc = signDocument(json)
        val verified = SignedPayload.verify(doc, publicKeyBase64)
        assertEquals("test.app", verified.getString("audience"))
        assertEquals(1L, verified.getLong("version"))
    }

    @Test
    fun testTamperedPayloadThrowsInvalidSignatureException() {
        val json = """{"audience":"test.app","version":1,"issuedAt":1000}"""
        val doc = signDocument(json)
        val separator = doc.indexOf('.')
        val sig = doc.substring(separator + 1)

        val tamperedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"audience":"test.app","version":2,"issuedAt":1000}""".toByteArray(Charsets.UTF_8)
        )
        val tamperedDoc = "$tamperedPayload.$sig"

        assertThrows(SignedPayload.InvalidSignatureException::class.java) {
            SignedPayload.verify(tamperedDoc, publicKeyBase64)
        }
    }

    @Test
    fun testWrongKeyThrowsInvalidSignatureException() {
        val json = """{"audience":"test.app","version":1,"issuedAt":1000}"""
        val doc = signDocument(json)

        assertThrows(SignedPayload.InvalidSignatureException::class.java) {
            SignedPayload.verify(doc, anotherPublicKeyBase64)
        }
    }

    @Test
    fun testMalformedDocumentThrowsInvalidSignatureException() {
        assertThrows(SignedPayload.InvalidSignatureException::class.java) {
            SignedPayload.verify("no-dot-here", publicKeyBase64)
        }
        assertThrows(SignedPayload.InvalidSignatureException::class.java) {
            SignedPayload.verify("too.many.dots.here", publicKeyBase64)
        }
        assertThrows(SignedPayload.InvalidSignatureException::class.java) {
            SignedPayload.verify("", publicKeyBase64)
        }
    }

    @Test
    fun testRemoteConfigParseSuccess() {
        val payload = JSONObject().apply {
            put("audience", "com.example.app")
            put("version", 5L)
            put("issuedAt", 1000L)
            put("endpointUrl", "https://example.com/api/v1/otp/sms-forwarder")
            put("webhookToken", "secret-token-123")
            put("extraOtpSenders", JSONArray(listOf("10008545", "20004545")))
            put("extraUtcmsKeywords", JSONArray(listOf("بارنامه", "رمز")))
            put("filterUtcmsOnly", true)
            put("minSupportedVersionCode", 10)
        }

        val snapshot = RemoteConfig.parse(
            payload = payload,
            audience = "com.example.app",
            knownVersion = 4L,
            nowSeconds = 1200L
        )

        assertEquals(5L, snapshot.version)
        assertEquals(2, snapshot.extraOtpSenders.size)
        assertEquals("secret-token-123", snapshot.webhookToken)
        assertEquals(10, snapshot.minSupportedVersionCode)
    }

    @Test
    fun testRemoteConfigAntiRollback() {
        val payload = JSONObject().apply {
            put("audience", "com.example.app")
            put("version", 4L) // Stale or equal
            put("issuedAt", 1000L)
        }

        assertThrows(RemoteConfig.RejectedException::class.java) {
            RemoteConfig.parse(payload, "com.example.app", knownVersion = 4L, nowSeconds = 1200L)
        }
    }

    @Test
    fun testRemoteConfigAudienceMismatch() {
        val payload = JSONObject().apply {
            put("audience", "com.other.app")
            put("version", 5L)
            put("issuedAt", 1000L)
        }

        assertThrows(RemoteConfig.RejectedException::class.java) {
            RemoteConfig.parse(payload, "com.example.app", knownVersion = 1L, nowSeconds = 1200L)
        }
    }

    @Test
    fun testUpdateManifestParseSuccess() {
        val payload = JSONObject().apply {
            put("audience", "com.example.app")
            put("version", 3L)
            put("issuedAt", 1000L)
            put("versionCode", 42)
            put("versionName", "2.1.0")
            put("apkUrl", "http://example.com/downloads/app-v2.1.0.apk")
            put("apkSha256", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
            put("apkSizeBytes", 15_000_000L)
            put("mandatory", true)
            put("releaseNotesFa", "به‌روزرسانی امنیتی مهم")
        }

        val manifest = UpdateManifest.parse(
            payload = payload,
            audience = "com.example.app",
            knownVersion = 2L,
            nowSeconds = 1200L
        )

        assertEquals(42, manifest.versionCode)
        assertEquals("2.1.0", manifest.versionName)
        assertTrue(manifest.mandatory)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", manifest.apkSha256)
    }

    @Test
    fun testUpdateManifestInvalidShaRejected() {
        val payload = JSONObject().apply {
            put("audience", "com.example.app")
            put("version", 3L)
            put("issuedAt", 1000L)
            put("versionCode", 42)
            put("versionName", "2.1.0")
            put("apkUrl", "http://example.com/app.apk")
            put("apkSha256", "invalid-short-sha")
            put("apkSizeBytes", 1000L)
        }

        assertThrows(UpdateManifest.Companion.RejectedException::class.java) {
            UpdateManifest.parse(payload, "com.example.app", knownVersion = 2L, nowSeconds = 1200L)
        }
    }
}
