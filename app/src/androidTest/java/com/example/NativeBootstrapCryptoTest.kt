package com.example

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.enrollment.DeviceBootstrapKey
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class NativeBootstrapCryptoTest {
    @Test fun actualAndroidKeystoreDecryptsBoundGrantAndRejectsTampering() {
        val key = DeviceBootstrapKey()
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(
            X509EncodedKeySpec(Base64.decode(key.publicKey(), Base64.NO_WRAP)))
        val nonce = "synthetic-native-nonce"
        val device = key.hubDeviceId()
        val binding = DeviceBootstrapKey.binding(nonce, device, DeviceBootstrapKey.AUTOMATIC_PROTOCOL)
        val signature = Base64.decode(key.signature(nonce, device, DeviceBootstrapKey.AUTOMATIC_PROTOCOL), Base64.NO_WRAP)
        assertTrue(Signature.getInstance("SHA256withRSA").run {
            initVerify(publicKey); update(binding); verify(signature)
        })
        val random = SecureRandom()
        val aes = ByteArray(32).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val wrapped = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").run {
            init(Cipher.ENCRYPT_MODE, publicKey,
                OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT))
            doFinal(aes)
        }
        val expected = "{\"protocol\":\"synthetic-native-fixture\"}"
        val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, iv))
            updateAAD(binding); doFinal(expected.toByteArray())
        }
        val grant = JSONObject().put("protocol", DeviceBootstrapKey.AUTOMATIC_PROTOCOL)
            .put("status", "granted").put("nonce", nonce).put("device_id", device)
            .put("wrapped_key", Base64.encodeToString(wrapped, Base64.NO_WRAP))
            .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        assertEquals(expected, key.decrypt(grant, nonce, device))
        assertTrue(runCatching { key.decrypt(grant, nonce + "other", device) }.isFailure)
        assertTrue(runCatching { key.decrypt(grant, nonce, device + "other") }.isFailure)
        ciphertext[0] = (ciphertext[0].toInt() xor 1).toByte()
        grant.put("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        assertTrue(runCatching { key.decrypt(grant, nonce, device) }.isFailure)
    }
}
