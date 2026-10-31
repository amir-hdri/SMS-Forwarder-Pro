package com.example

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.AesEncryptionUtils
import com.example.crypto.EncryptedSmsPayload
import com.example.crypto.SecureStorageManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.management.ManagementFactory
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SecureSaltConcurrencyTest {
    // Without serialization, warm-up retains an earlier missing-salt read while
    // the first OTP commits and encrypts with another salt. Warm-up then replaces it.
    @Test fun firstOtpCiphertextRemainsDecryptableAfterConcurrentSaltWarmup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val preferencesName = "synthetic_concurrent_salt_" + UUID.randomUUID()
        val persisted = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        val firstMissingRead = CountDownLatch(1)
        val releaseWarmupRead = CountDownLatch(1)
        val secondMissingRead = CountDownLatch(1)
        val encryptionStarted = CountDownLatch(1)
        val encryptionFinished = CountDownLatch(1)
        val saltReads = AtomicInteger()
        val failure = AtomicReference<Throwable>()
        val encrypted = AtomicReference<EncryptedSmsPayload>()
        val previousProvider = AesEncryptionUtils.saltProvider
        val preferences = object : SharedPreferences by persisted {
            override fun getString(key: String?, defValue: String?): String? {
                val snapshot = persisted.getString(key, defValue)
                if (key == "secure_pbkdf2_salt" && snapshot == null) {
                    when (saltReads.incrementAndGet()) {
                        1 -> {
                            firstMissingRead.countDown()
                            check(releaseWarmupRead.await(10, TimeUnit.SECONDS)) { "Warm-up read was not released" }
                        }
                        2 -> secondMissingRead.countDown()
                    }
                }
                return snapshot
            }
        }
        val storage = SecureStorageManager(context) { preferences }
        val key = storage.getOrCreateOutboxKey()
        val message = "synthetic first OTP 54321"
        AesEncryptionUtils.saltProvider = { storage.getOrCreateSalt() }
        val warmup = Thread({
            try { storage.getOrCreateSalt() } catch (error: Throwable) { failure.compareAndSet(null, error) }
        }, "salt-warmup")
        val firstOtp = Thread({
            encryptionStarted.countDown()
            try { encrypted.set(AesEncryptionUtils.encrypt(message, key)) }
            catch (error: Throwable) { failure.compareAndSet(null, error) }
            finally { encryptionFinished.countDown() }
        }, "first-otp-encryption")
        try {
            warmup.start()
            assertTrue("Warm-up must read the real missing salt before the OTP starts", firstMissingRead.await(4, TimeUnit.SECONDS))
            firstOtp.start()
            assertTrue(encryptionStarted.await(4, TimeUnit.SECONDS))
            val threads = ManagementFactory.getThreadMXBean()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            // Release only when the competing call either reads the missing salt
            // or is actually blocked by the warm-up's storage monitor. This avoids
            // a scheduling delay deciding whether the regression was exercised.
            while (secondMissingRead.count > 0) {
                val info = threads.getThreadInfo(firstOtp.id)
                if (info?.threadState == Thread.State.BLOCKED && info.lockOwnerId == warmup.id &&
                    info.lockInfo?.identityHashCode == System.identityHashCode(storage)) break
                assertTrue("OTP must reach the controlled read or wait for warm-up serialization", System.nanoTime() < deadline)
                secondMissingRead.await(10, TimeUnit.MILLISECONDS)
            }
            if (secondMissingRead.count == 0L) {
                assertTrue("The racing OTP must retain its real ciphertext before warm-up commits", encryptionFinished.await(5, TimeUnit.SECONDS))
            }
            releaseWarmupRead.countDown()
            warmup.join(5_000)
            firstOtp.join(5_000)
            assertFalse(warmup.isAlive)
            assertFalse(firstOtp.isAlive)
            assertNull("Both real operations must complete: ${failure.get()}", failure.get())
            val payload = encrypted.get()
            assertNotNull(payload)
            val reopened = SecureStorageManager(context) { persisted }
            AesEncryptionUtils.saltProvider = { reopened.getOrCreateSalt() }
            val decrypted = runCatching { AesEncryptionUtils.decrypt(payload.iv, payload.ciphertext, key) }
            assertEquals("Retained OTP ciphertext must decrypt with the final persisted salt; ${decrypted.exceptionOrNull()}",
                message, decrypted.getOrNull())
        } finally {
            releaseWarmupRead.countDown()
            warmup.join(5_000)
            firstOtp.join(5_000)
            AesEncryptionUtils.saltProvider = previousProvider
            context.deleteSharedPreferences(preferencesName)
        }
    }
}
