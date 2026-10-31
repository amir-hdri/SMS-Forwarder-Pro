package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.SecureStorageManager
import com.example.crypto.AesEncryptionUtils
import org.junit.rules.ExternalResource
import java.util.UUID

/** Test-only provider substitution: Robolectric does not implement AndroidKeyStore.
 * Integration tests use synthetic credentials here; device tests use production encryption. */
class SecureStorageTestRule : ExternalResource() {
    private val singleton = SecureStorageManager::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private var previous: Any? = null
    private var previousSaltProvider: (() -> ByteArray)? = null
    private lateinit var context: Application
    private val name = "synthetic_encrypted_storage_" + UUID.randomUUID()

    override fun before() {
        context = ApplicationProvider.getApplicationContext()
        previous = singleton.get(null)
        previousSaltProvider = AesEncryptionUtils.saltProvider
        val store = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        singleton.set(null, SecureStorageManager(context) { store })
        AesEncryptionUtils.saltProvider = { SecureStorageManager.getInstance(context).getOrCreateSalt() }
    }

    override fun after() {
        singleton.set(null, previous)
        AesEncryptionUtils.saltProvider = previousSaltProvider
        context.deleteSharedPreferences(name)
    }
}
