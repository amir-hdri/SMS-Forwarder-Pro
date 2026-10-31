package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.SecureStorageManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SecureStoragePolicyTest {
    private val singleton = SecureStorageManager::class.java.getDeclaredField("instance").apply { isAccessible = true }
    @Before fun isolate() { singleton.set(null, null) }
    @After fun release() { singleton.set(null, null) }

    // Robolectric deliberately has no AndroidKeyStore provider. Production must reject this
    // environment, rather than use it as a reason to create plaintext credential files.
    @Test fun unavailableKeystoreFailsClosedForEverySecretOperation() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val storage = SecureStorageManager.getInstance(context)
        val actions: List<Pair<String, () -> Any?>> = listOf(
            "outbox key" to { storage.getOrCreateOutboxKey() },
            "webhook read" to { storage.getForwarderSecret() },
            "webhook write" to { storage.saveForwarderSecret("synthetic-webhook-secret") },
            "encryption key read" to { storage.getSecretEncryptionKey() },
            "encryption key write" to { storage.saveSecretEncryptionKey("synthetic-encryption-key") },
            "PBKDF2 salt" to { storage.getOrCreateSalt() },
            "enrollment write" to { storage.savePendingEnrollment("synthetic-enrollment") }
        )
        val accepted = actions.filter { (_, action) -> runCatching(action).isSuccess }.map { it.first }
        assertTrue("Unavailable Keystore must reject secret operations, accepted: $accepted", accepted.isEmpty())
        assertFalse("A failure must never create a plaintext fallback store",
            File(context.dataDir, "shared_prefs/barpro_secure_storage_prefs_fallback.xml").exists())
    }

    @Test fun legacyPlaintextSecretsMigrateBeforeRemovalAndKeepTheOutboxKey() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val legacy = context.getSharedPreferences("barpro_secure_storage_prefs_fallback", Context.MODE_PRIVATE)
        check(legacy.edit().putString("secure_forwarder_secret", "synthetic-legacy-secret")
            .putString("outbox_key_v2", "synthetic-existing-outbox-key").commit())
        assertTrue("The legacy fixture must actually exist on disk",
            File(context.dataDir, "shared_prefs/barpro_secure_storage_prefs_fallback.xml").exists())
        val destination = context.getSharedPreferences("synthetic_secure_migration", Context.MODE_PRIVATE)
        val storage = SecureStorageManager(context) { destination }
        assertEquals("synthetic-legacy-secret", storage.getForwarderSecret())
        assertEquals("synthetic-existing-outbox-key", storage.getOrCreateOutboxKey())
        assertFalse(File(context.dataDir, "shared_prefs/barpro_secure_storage_prefs_fallback.xml").exists())
    }

    @Test fun encryptedCredentialsWinOverAStalePlaintextFallback() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val legacy = context.getSharedPreferences("barpro_secure_storage_prefs_fallback", Context.MODE_PRIVATE)
        check(legacy.edit().putString("secure_forwarder_secret", "synthetic-stale-secret").commit())
        val destination = context.getSharedPreferences("synthetic_secure_migration", Context.MODE_PRIVATE)
        check(destination.edit().putString("secure_forwarder_secret", "synthetic-current-secret").commit())
        val storage = SecureStorageManager(context) { destination }
        assertEquals("synthetic-current-secret", storage.getForwarderSecret())
        assertFalse("Successful encrypted migration must remove the obsolete plaintext file",
            File(context.dataDir, "shared_prefs/barpro_secure_storage_prefs_fallback.xml").exists())
    }

    @Test fun encryptionInitializationFailurePreservesExistingSecretsWithoutAddingNewOnes() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val legacy = context.getSharedPreferences("barpro_secure_storage_prefs_fallback", Context.MODE_PRIVATE)
        check(legacy.edit().putString("secure_forwarder_secret", "synthetic-legacy-secret").commit())
        val storage = SecureStorageManager(context) { throw java.security.KeyStoreException("synthetic-unavailable-provider") }
        assertTrue(runCatching { storage.saveSecretEncryptionKey("synthetic-new-key") }.isFailure)
        assertEquals("synthetic-legacy-secret", legacy.getString("secure_forwarder_secret", null))
        assertFalse(legacy.contains("secure_secret_encryption_key"))
    }
}
