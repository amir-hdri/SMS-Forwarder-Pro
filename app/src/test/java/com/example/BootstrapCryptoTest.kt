package com.example

import android.app.Application
import android.util.Base64
import com.example.enrollment.BootstrapSms
import com.example.enrollment.DeviceBootstrapKey
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BootstrapCryptoTest {
    private val nonce = "n".repeat(43)
    private val device = "device-synthetic"
    @Test fun distinctActivationMessageUsesCarrierOriginOnly() {
        assertEquals("09120000001" to nonce, BootstrapSms.parse("+989120000001", "BPA1#$nonce"))
        for (body in listOf("BPA1#$nonce#09120000001", "BP1#09120000001#123#45678#signature", "BPA1#12345"))
            assertNull(BootstrapSms.parse("09120000001", body))
        assertNull(BootstrapSms.parse("7777000982", "BPA1#$nonce"))
    }

    @Test fun realDeviceSignatureAndAuthenticatedGrantDecryptRejectTampering() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val key = DeviceBootstrapKey { pair }
        val binding = "barpro-bootstrap-v1\n$nonce\n$device".toByteArray()
        assertTrue(Signature.getInstance("SHA256withRSA").apply { initVerify(pair.public); update(binding) }
            .verify(Base64.decode(key.signature(nonce, device), Base64.NO_WRAP)))
        val aes = ByteArray(32) { (it + 1).toByte() }
        val iv = ByteArray(12) { (it + 5).toByte() }
        val encrypt = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, iv)); updateAAD(binding)
        }
        val wrapping = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").apply {
            init(Cipher.ENCRYPT_MODE, pair.public, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT))
        }
        fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val grant = JSONObject().put("protocol", "barpro-bootstrap-v1").put("nonce", nonce).put("device_id", device)
            .put("status", "granted").put("wrapped_key", b64(wrapping.doFinal(aes))).put("iv", b64(iv))
            .put("ciphertext", b64(encrypt.doFinal("synthetic-credential-document".toByteArray())))
        assertEquals("synthetic-credential-document", key.decrypt(grant, nonce, device))
        val bytes = Base64.decode(grant.getString("ciphertext"), Base64.NO_WRAP)
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        grant.put("ciphertext", b64(bytes))
        try { key.decrypt(grant, nonce, device); fail("GCM alteration must fail") } catch (_: Exception) { }
        try { key.decrypt(grant, nonce, "other-device"); fail("Binding substitution must fail") } catch (_: Exception) { }
    }
}
