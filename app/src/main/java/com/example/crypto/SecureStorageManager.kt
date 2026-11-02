package com.example.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.SecureRandom

/**
 * Keystore-backed credential storage. Failure to initialize encryption rejects every operation;
 * production never substitutes plaintext preferences. The factory is an injectable storage
 * boundary so non-Android runtimes can exercise consumers without an AndroidKeyStore provider.
 */
class SecureStorageManager internal constructor(
    context: Context,
    encryptedPreferencesFactory: (Context) -> SharedPreferences = ::createEncryptedPreferences
) {
    private val prefs: SharedPreferences by lazy {
        try {
            encryptedPreferencesFactory(context).also { migrateLegacyFallback(context, it) }
        } catch (error: Exception) {
            throw IllegalStateException("Secure credential storage unavailable", error)
        }
    }

    /** Enrollment never falls back to plaintext secret storage if Keystore is unavailable. */
    fun savePendingEnrollment(document: String?) {
        val storage = prefs
        check(storage.edit().putString("pending_driver_enrollment_v1", document).commit())
    }

    fun getPendingEnrollment(): String? {
        val storage = prefs
        return storage.getString("pending_driver_enrollment_v1", null)
    }

    fun savePendingBootstrap(document: String?) {
        check(prefs.edit().putString("pending_driver_bootstrap_v1", document).commit())
    }

    fun savePendingHubInstall(document: String?) {
        check(prefs.edit().putString("pending_hub_install_v1", document).commit())
    }

    fun getCompletedHubInstallHash(): String? = prefs.getString("completed_hub_install_hash", null)

    fun saveCompletedHubInstallHash(hash: String) {
        require(hash.matches(Regex("[0-9a-f]{64}")))
        check(prefs.edit().putString("completed_hub_install_hash", hash).commit())
    }

    fun getPendingHubInstall(): String? = prefs.getString("pending_hub_install_v1", null)

    fun getPendingBootstrap(): String? = prefs.getString("pending_driver_bootstrap_v1", null)

    fun saveBootstrapSmsAttempt(document: String?) {
        check(prefs.edit().putString("bootstrap_sms_attempt_v1", document).commit())
    }

    fun getBootstrapSmsAttempt(): String? = prefs.getString("bootstrap_sms_attempt_v1", null)

    @Synchronized
    fun getOrCreateOutboxKey(): String {
        val existing = prefs.getString("outbox_key_v2", null)
        if (existing != null) return existing
        val key = AesEncryptionUtils.generateSecureKey(48)
        check(prefs.edit().putString("outbox_key_v2", key).commit()) { "Cannot persist outbox key" }
        return key
    }

    fun getForwarderSecret(): String {
        return prefs.getString(KEY_FORWARDER_SECRET, "") ?: ""
    }

    fun saveForwarderSecret(secret: String) {
        check(prefs.edit().putString(KEY_FORWARDER_SECRET, secret).commit()) { "Cannot persist webhook secret" }
    }

    fun getSecretEncryptionKey(): String {
        return prefs.getString(KEY_SECRET_ENCRYPTION_KEY, "") ?: ""
    }

    fun saveSecretEncryptionKey(key: String) {
        check(prefs.edit().putString(KEY_SECRET_ENCRYPTION_KEY, key).commit()) { "Cannot persist encryption key" }
    }

    /**
     * Gets or generates a cryptographically secure 128-bit random salt for PBKDF2 key derivation.
     * The salt is persisted in EncryptedSharedPreferences so the same passphrase deterministically
     * derives the same AES key on this device across restarts.
     */
    @Synchronized
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
        check(prefs.edit().putString(KEY_PBKDF2_SALT, encoded).commit()) { "Cannot persist encryption salt" }
        return newSalt
    }

    fun clearAllSecrets() {
        check(prefs.edit().clear().commit()) { "Cannot clear credential storage" }
    }

    companion object {
        private const val PREFS_NAME = "barpro_secure_storage_prefs"
        private const val KEY_FORWARDER_SECRET = "secure_forwarder_secret"
        private const val KEY_SECRET_ENCRYPTION_KEY = "secure_secret_encryption_key"
        private const val KEY_PBKDF2_SALT = "secure_pbkdf2_salt"

        private fun migrateLegacyFallback(context: Context, encrypted: SharedPreferences) {
            val legacyName = PREFS_NAME + "_fallback"
            if (!File(context.dataDir, "shared_prefs/$legacyName.xml").exists()) return
            val legacy = context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
            val editor = encrypted.edit()
            for (key in listOf(KEY_FORWARDER_SECRET, KEY_SECRET_ENCRYPTION_KEY, KEY_PBKDF2_SALT,
                "outbox_key_v2", "pending_driver_enrollment_v1")) {
                // A populated encrypted store remains authoritative; never rotate its outbox key.
                if (!encrypted.contains(key)) legacy.getString(key, null)?.let { editor.putString(key, it) }
            }
            check(editor.commit()) { "Cannot migrate legacy credential storage" }
            check(context.deleteSharedPreferences(legacyName)) { "Cannot remove legacy credential storage" }
        }

        private fun createEncryptedPreferences(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(context, PREFS_NAME, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        }

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
