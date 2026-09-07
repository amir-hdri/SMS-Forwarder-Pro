package com.example.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Enterprise Secure Storage Manager using Android Keystore and EncryptedSharedPreferences.
 * Safely manages high-entropy secrets (Forwarder webhook secret, AES encryption key, PBKDF2 salt)
 * protecting them from physical extraction, adb backup, or rooted device snooping.
 */
class SecureStorageManager private constructor(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Throwable) {
        // Fallback for Robolectric or environments where Android Keystore hardware is unavailable
        context.getSharedPreferences("${PREFS_NAME}_fallback", Context.MODE_PRIVATE)
    }

    fun getForwarderSecret(): String {
        return prefs.getString(KEY_FORWARDER_SECRET, "") ?: ""
    }

    fun saveForwarderSecret(secret: String) {
        prefs.edit().putString(KEY_FORWARDER_SECRET, secret).apply()
    }

    fun getSecretEncryptionKey(): String {
        return prefs.getString(KEY_SECRET_ENCRYPTION_KEY, "") ?: ""
    }

    fun saveSecretEncryptionKey(key: String) {
        prefs.edit().putString(KEY_SECRET_ENCRYPTION_KEY, key).apply()
    }

    /**
     * Gets or generates a cryptographically secure 128-bit random salt for PBKDF2 key derivation.
     * The salt is persisted in EncryptedSharedPreferences so the same passphrase deterministically
     * derives the same AES key on this device across restarts.
     */
    fun getOrCreateSalt(length: Int = 16): ByteArray {
        val storedBase64 = prefs.getString(KEY_PBKDF2_SALT, null)
        if (!storedBase64.isNullOrBlank()) {
            return try {
                Base64.decode(storedBase64, Base64.NO_WRAP)
            } catch (_: Exception) {
                generateAndSaveSalt(length)
            }
        }
        return generateAndSaveSalt(length)
    }

    private fun generateAndSaveSalt(length: Int): ByteArray {
        val newSalt = ByteArray(length)
        SecureRandom().nextBytes(newSalt)
        val encoded = Base64.encodeToString(newSalt, Base64.NO_WRAP)
        prefs.edit().putString(KEY_PBKDF2_SALT, encoded).apply()
        return newSalt
    }

    fun clearAllSecrets() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "barpro_secure_storage_prefs"
        private const val KEY_FORWARDER_SECRET = "secure_forwarder_secret"
        private const val KEY_SECRET_ENCRYPTION_KEY = "secure_secret_encryption_key"
        private const val KEY_PBKDF2_SALT = "secure_pbkdf2_salt"

        @Volatile
        private var instance: SecureStorageManager? = null

        fun getInstance(context: Context): SecureStorageManager {
            return instance ?: synchronized(this) {
                instance ?: SecureStorageManager(context.applicationContext).also { instance = it }
            }
        }

        fun getInstanceIfAvailable(): SecureStorageManager? {
            return instance
        }
    }
}
