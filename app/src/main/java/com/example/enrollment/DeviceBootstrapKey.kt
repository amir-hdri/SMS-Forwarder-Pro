package com.example.enrollment

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/** Production keys are generated and used in AndroidKeyStore; private key bytes never leave it. */
class DeviceBootstrapKey internal constructor(private val keyPair: () -> KeyPair) {
    constructor() : this(::androidKeyPair)

    fun publicKey(): String = Base64.encodeToString(keyPair().public.encoded, Base64.NO_WRAP)

    fun signature(nonce: String, deviceId: String): String = Base64.encodeToString(
        Signature.getInstance("SHA256withRSA").apply { initSign(keyPair().private); update(binding(nonce, deviceId)) }.sign(),
        Base64.NO_WRAP)

    fun decrypt(grant: JSONObject, nonce: String, deviceId: String): String {
        require(grant.getString("protocol") == PROTOCOL && grant.getString("status") == "granted")
        require(grant.getString("nonce") == nonce && grant.getString("device_id") == deviceId)
        fun bytes(name: String): ByteArray {
            val encoded = grant.getString(name)
            require(encoded.length <= 12_000)
            return Base64.decode(encoded, Base64.NO_WRAP)
        }
        val wrapped = bytes("wrapped_key").also { require(it.size == 256) }
        val aes = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").apply {
            init(Cipher.DECRYPT_MODE, keyPair().private,
                OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT))
        }.doFinal(wrapped).also { require(it.size == 32) }
        val iv = bytes("iv").also { require(it.size == 12) }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, iv))
            updateAAD(binding(nonce, deviceId))
        }.doFinal(bytes("ciphertext")).toString(Charsets.UTF_8)
    }

    companion object {
        const val PROTOCOL = "barpro-bootstrap-v1"
        private const val ALIAS = "barpro-driver-bootstrap-rsa-v1"
        fun binding(nonce: String, deviceId: String): ByteArray = "$PROTOCOL\n$nonce\n$deviceId".toByteArray(Charsets.UTF_8)

        @Synchronized private fun androidKeyPair(): KeyPair {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (store.containsAlias(ALIAS)) return KeyPair(store.getCertificate(ALIAS).publicKey,
                store.getKey(ALIAS, null) as java.security.PrivateKey)
            return KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP).build())
            }.generateKeyPair()
        }
    }
}
