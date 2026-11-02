package com.example

import android.app.Application
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.SecureStorageManager
import com.example.data.local.AppDatabase
import com.example.data.model.ForwardConfig
import com.example.data.repository.SmsForwardRepository
import com.example.enrollment.*
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HubInstallActivationTest {
    @get:Rule val secureStorage = SecureStorageTestRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val key = DeviceBootstrapKey { pair }
    private val id = "hub_" + MessageDigest.getInstance("SHA-256").digest(pair.public.encoded)
        .joinToString("") { "%02x".format(it) }.take(32)
    private val cap = "A".repeat(43)

    @Test fun concurrentPermissionOnlyActivationInstallsOneKeyBoundGrantWithoutClaimingSmsHealth() = runBlocking {
        OwnedHubTlsServer().use { owned ->
            val requests = AtomicInteger()
            val reached = CountDownLatch(1)
            val release = CountDownLatch(1)
            owned.server.createContext("/api/v1/otp/hub-enroll") { exchange ->
                val request = JSONObject(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
                require(request.getString("capability") == cap && request.getString("device_id") == id)
                require(request.getString("public_key") == key.publicKey())
                require(Signature.getInstance("SHA256withRSA").run {
                    initVerify(pair.public); update("barpro-bootstrap-v2\n$cap\n$id".toByteArray())
                    verify(Base64.decode(request.getString("signature"), Base64.NO_WRAP))
                })
                requests.incrementAndGet(); reached.countDown(); check(release.await(5, TimeUnit.SECONDS))
                val body = grant(owned.root).toString().toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
            }
            val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            try {
                val repository = SmsForwardRepository(db, appContext = context)
                repository.saveConfig(ForwardConfig())
                val storage = SecureStorageManager.getInstance(context)
                val activated = AtomicInteger()
                val profile = HubInstallProfile(owned.root.toHttpUrl(), cap, 100000)
                val client = BootstrapClient(profile.server, owned.client)
                val manager = HubInstallManager("HUB", key, repository, { profile },
                    { p, dev, k -> client.enrollHub(p.capability, dev, k) },
                    storage::getPendingHubInstall, storage::savePendingHubInstall, { true }, { activated.incrementAndGet() })
                val first = async(Dispatchers.IO) { manager.activate() }
                assertTrue(reached.await(5, TimeUnit.SECONDS))
                val second = async(Dispatchers.IO) { manager.activate() }
                // A profile/stop committed after the network began must survive the atomic credential upgrade.
                repository.saveConfig(ForwardConfig(setupCompleted = true, isMasterEnabled = false,
                    userConsentGiven = false, authHeaderValue = "synthetic-old-authorized-profile", healthCheckIntervalMinutes = 19))
                release.countDown()
                withTimeout(5000) { awaitAll(first, second) }
                assertEquals(EnrollmentStatus.ACTIVE, manager.status.value)
                val saved = repository.getConfig()
                assertEquals(id, saved.deviceIdentifier)
                assertTrue(saved.authHeaderValue.matches(Regex("HUB1_[0-9a-f]{16}_[0-9a-f]{64}")))
                assertEquals(saved.authHeaderValue, saved.forwarderSecret)
                assertFalse(saved.isMasterEnabled)
                assertFalse(saved.userConsentGiven)
                assertEquals(19, saved.healthCheckIntervalMinutes)
                assertEquals(1, requests.get())
                assertEquals("A grant is not a sent/received SMS or health witness", 0, activated.get())
                assertEquals(0, db.forwardLogDao().getLatestLog()?.let { 1 } ?: 0)
                assertEquals("Private credentials stay outside Room", "", db.forwardConfigDao().getConfig()!!.authHeaderValue)
                assertNull(storage.getPendingHubInstall())
            } finally { release.countDown(); db.close() }
        }
    }

    @Test fun authenticatedGrantCanResumeAfterPermissionRevocationWithoutThePrivateAsset() = runBlocking {
        OwnedHubTlsServer().use { owned ->
            val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            try {
                val repository = SmsForwardRepository(db, appContext = context)
                repository.saveConfig(ForwardConfig())
                val storage = SecureStorageManager.getInstance(context)
                var permissions = true
                var profile: HubInstallProfile? = HubInstallProfile(owned.root.toHttpUrl(), cap, 100000)
                val activated = AtomicInteger()
                val manager = HubInstallManager("HUB", key, repository, { profile },
                    { _, _, _ -> permissions = false; grant(owned.root) },
                    storage::getPendingHubInstall, storage::savePendingHubInstall, { permissions }, { activated.incrementAndGet() })
                manager.activate()
                assertEquals(EnrollmentStatus.NEEDS_PERMISSIONS, manager.status.value)
                assertNotNull(storage.getPendingHubInstall())
                assertFalse(repository.getConfig().setupCompleted)
                profile = null; permissions = true
                manager.activate()
                assertEquals(EnrollmentStatus.ACTIVE, manager.status.value)
                assertTrue(repository.getConfig().isMasterEnabled)
                assertTrue(repository.getConfig().userConsentGiven)
                assertEquals(1, activated.get())
                assertNull(storage.getPendingHubInstall())
            } finally { db.close() }
        }
    }

    @Test fun aLostResponseAfterServerClaimRecoversTheSameCachedEncryptedGrantOverTls() = runBlocking {
        OwnedHubTlsServer().use { owned ->
            val requests = AtomicInteger()
            val cached = grant(owned.root).toString().toByteArray()
            owned.server.createContext("/api/v1/otp/hub-enroll") { exchange ->
                val request = JSONObject(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
                require(request.getString("device_id") == id && request.getString("public_key") == key.publicKey())
                val attempt = requests.incrementAndGet()
                val body = if (attempt == 1) "{}".toByteArray() else cached
                exchange.sendResponseHeaders(if (attempt == 1) 503 else 200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            try {
                val repository = SmsForwardRepository(db, appContext = context)
                val storage = SecureStorageManager.getInstance(context)
                val profile = HubInstallProfile(owned.root.toHttpUrl(), cap, 100000)
                val client = BootstrapClient(profile.server, owned.client)
                val manager = HubInstallManager("HUB", key, repository, { profile },
                    { p, dev, k -> client.enrollHub(p.capability, dev, k) },
                    storage::getPendingHubInstall, storage::savePendingHubInstall, { true }, {})
                manager.activate()
                assertEquals(EnrollmentStatus.FAILED, manager.status.value)
                assertTrue(manager.retryable)
                assertFalse(repository.getConfig().setupCompleted)
                manager.activate()
                assertEquals(EnrollmentStatus.ACTIVE, manager.status.value)
                assertEquals(id, repository.getConfig().deviceIdentifier)
                assertEquals(2, requests.get())
            } finally { db.close() }
        }
    }

    @Test fun aNewPrivateCapabilityRotatesAnExistingScopedProfileWithoutRestoringRevokedConsent() = runBlocking {
        OwnedHubTlsServer().use { owned ->
            val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            try {
                val repository = SmsForwardRepository(db, appContext = context)
                val storage = SecureStorageManager.getInstance(context)
                var profile = HubInstallProfile(owned.root.toHttpUrl(), cap, 100000)
                var redeems = 0
                val newerToken = "HUB1_" + "d".repeat(16) + "_" + "e".repeat(64)
                val manager = HubInstallManager("HUB", key, repository, { profile },
                    { p, _, _ -> redeems++; grant(owned.root, p.capability, if (redeems == 1) null else newerToken) },
                    storage::getPendingHubInstall, storage::savePendingHubInstall, { true }, {})
                manager.activate()
                repository.updateConfig { it.copy(isMasterEnabled = false, userConsentGiven = false) }
                profile = HubInstallProfile(owned.root.toHttpUrl(), "B".repeat(43), 100000)
                manager.activate()
                assertEquals("A newly packaged one-use permission must be redeemable for credential rotation", 2, redeems)
                val updated = repository.getConfig()
                assertEquals(newerToken, updated.authHeaderValue)
                assertFalse(updated.isMasterEnabled)
                assertFalse(updated.userConsentGiven)
            } finally { db.close() }
        }
    }

    @Test fun driverAndMissingPermissionsCannotReadOrRedeemPrivateHubCapability() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repository = SmsForwardRepository(db, appContext = context)
            for ((role, permissions) in listOf("DRIVER" to true, "HUB" to false)) {
                val manager = HubInstallManager(role, key, repository,
                    { error("Private capability must not be read") }, { _, _, _ -> error("Must not redeem") },
                    { null }, {}, { permissions }, { error("Must not activate") })
                manager.activate()
                assertFalse(repository.getConfig().isMasterEnabled)
                assertTrue(manager.status.value in setOf(EnrollmentStatus.IDLE, EnrollmentStatus.NEEDS_PERMISSIONS))
            }
        } finally { db.close() }
    }

    private fun grant(root: String, nonce: String = cap, token: String? = null): JSONObject {
        val document = JSONObject().put("protocol", "barpro-hub-v1").put("device_id", id)
            .put("endpoint_url", root + "/api/v1/otp/sms-forwarder")
            .put("webhook_token", token ?: ("HUB1_" + "b".repeat(16) + "_" + "c".repeat(64)))
            .put("hub_phone_mci", "09120000001").put("hub_phone_irancell", "09350000001").toString()
        val aes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val encrypted = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, iv))
            updateAAD("barpro-bootstrap-v2\n$nonce\n$id".toByteArray())
        }.doFinal(document.toByteArray())
        val wrapped = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").apply {
            init(Cipher.ENCRYPT_MODE, pair.public,
                OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT))
        }.doFinal(aes)
        return JSONObject().put("protocol", "barpro-bootstrap-v2").put("status", "granted")
            .put("nonce", nonce).put("device_id", id)
            .put("wrapped_key", Base64.encodeToString(wrapped, Base64.NO_WRAP))
            .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
    }
}
