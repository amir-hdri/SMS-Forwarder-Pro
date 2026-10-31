package com.example

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.SecureStorageManager
import com.example.data.local.AppDatabase
import com.example.data.model.ForwardConfig
import com.example.data.model.ForwardLog
import com.example.data.repository.SmsForwardRepository
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.reflect.Proxy
import java.security.KeyStoreException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34], qualifiers = "w412dp-h915dp")
class MainViewModelStorageFailureTest {
    @get:Rule val compose = createComposeRule()
    private val storageSingleton = SecureStorageManager::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private val repositorySingleton = SmsForwardRepository::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
    private var previousStorage: Any? = null
    private var previousRepository: Any? = null
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private lateinit var context: Application
    private lateinit var db: AppDatabase
    private lateinit var viewModel: MainViewModel
    private val models = ViewModelStore()
    private val storeName = "synthetic_ui_storage_" + UUID.randomUUID()
    private val storageAvailable = AtomicBoolean(false)
    private val writesAvailable = AtomicBoolean(true)
    private val storageAttempted = CountDownLatch(1)
    private val writeAttempted = CountDownLatch(1)
    private val uncaught = CountDownLatch(1)
    private val failures = CopyOnWriteArrayList<Throwable>()
    private val saved = ForwardConfig(isMasterEnabled = true, userConsentGiven = true, setupCompleted = true,
        driverPhone = "09120000001", endpointUrl = "https://87.107.160.251/api/v1/otp/sms-forwarder/09120000001")
    private val ciphertext = "v2:synthetic-existing-iv:synthetic-existing-ciphertext"

    @Before fun prepare() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        previousStorage = storageSingleton.get(null)
        previousRepository = repositorySingleton.get(null)
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> failures.add(error); uncaught.countDown() }
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val persisted = context.getSharedPreferences(storeName, Context.MODE_PRIVATE)
        check(persisted.edit().putString("secure_forwarder_secret", "a".repeat(64)).commit())
        val preferences = object : SharedPreferences by persisted {
            override fun edit(): SharedPreferences.Editor {
                val editor = persisted.edit()
                return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                    arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                    if (method.name == "commit" && !writesAvailable.get()) {
                        writeAttempted.countDown()
                        false
                    } else {
                        val result = method.invoke(editor, *(args ?: emptyArray()))
                        if (result is SharedPreferences.Editor) proxy else result
                    }
                } as SharedPreferences.Editor
            }
        }
        storageSingleton.set(null, SecureStorageManager(context) {
            val available = storageAvailable.get()
            storageAttempted.countDown()
            if (!available) throw KeyStoreException("synthetic temporarily unavailable Keystore")
            preferences
        })
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        db.forwardConfigDao().insertOrUpdate(saved)
        db.forwardLogDao().insertLog(ForwardLog(sender = "20007777", messageBody = "***",
            recipientPhone = saved.driverPhone, encryptedBody = ciphertext))
        repositorySingleton.set(null, SmsForwardRepository(db, appContext = context))
        viewModel = MainViewModel(context)
        models.put("storage-failure", viewModel)
    }

    @After fun release() {
        models.clear()
        if (::db.isInitialized) db.close()
        Dispatchers.resetMain()
        storageSingleton.set(null, previousStorage)
        repositorySingleton.set(null, previousRepository)
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        if (::context.isInitialized) context.deleteSharedPreferences(storeName)
    }

    private suspend fun assertPersistentIdentityPreserved() {
        val paused = withTimeout(4_000) {
            db.forwardConfigDao().getConfigFlow().first { it != null && !it.isMasterEnabled && !it.userConsentGiven }
        }
        assertEquals("Only the forwarding flags may change after a secure storage failure",
            saved.copy(isMasterEnabled = false, userConsentGiven = false), paused)
        assertEquals(ciphertext, db.forwardLogDao().getLatestLog()!!.encryptedBody)
        assertEquals("a".repeat(64), context.getSharedPreferences(storeName, Context.MODE_PRIVATE)
            .getString("secure_forwarder_secret", null))
    }

    @Test fun collectingConfigHandlesKeystoreFailureWithoutActivatingDefaults() = runBlocking {
        val observer = launch(Dispatchers.Default) { viewModel.config.collect() }
        try {
            assertTrue(storageAttempted.await(4, TimeUnit.SECONDS))
            assertFalse("Keystore failure must be handled instead of escaping the sharing coroutine: $failures",
                uncaught.await(500, TimeUnit.MILLISECONDS))
            viewModel.checkServerHealthNow()
            assertFalse("Health checking while secure configuration is stopped must not crash the UI",
                uncaught.await(500, TimeUnit.MILLISECONDS))
            assertFalse(viewModel.config.value.isMasterEnabled)
            assertTrue(viewModel.config.value.authHeaderValue.isEmpty())
            assertPersistentIdentityPreserved()
        } finally { observer.cancelAndJoin() }
    }

    @Test fun resumedUiRecoversStoredConfigurationAfterKeystoreReturns() = runBlocking {
        val observer = launch(Dispatchers.Default) { viewModel.config.collect() }
        try {
            assertTrue(storageAttempted.await(4, TimeUnit.SECONDS))
            assertFalse("Initial Keystore failure must remain a handled UI state: $failures",
                uncaught.await(500, TimeUnit.MILLISECONDS))
            storageAvailable.set(true)
            viewModel.refreshBackgroundRecovery()
            val restored = withTimeout(4_000) { viewModel.config.first { it.driverPhone == saved.driverPhone && it.authHeaderValue.isNotEmpty() } }
            assertFalse("Storage recovery requires a separate explicit forwarding opt-in", restored.isMasterEnabled)
            assertFalse(restored.userConsentGiven)
            assertEquals("a".repeat(64), restored.authHeaderValue)
            assertPersistentIdentityPreserved()
            assertTrue("Storage recovery must not leave an uncaught flow failure: $failures", failures.isEmpty())
        } finally { observer.cancelAndJoin() }
    }

    @Test fun failedConfigWriteStopsUiWithoutCrashingOrReplacingStoredIdentity() = runBlocking {
        storageAvailable.set(true)
        val observer = launch(Dispatchers.Default) { viewModel.config.collect() }
        try {
            withTimeout(4_000) { viewModel.config.first { it.driverPhone == saved.driverPhone && it.isMasterEnabled } }
            writesAvailable.set(false)
            viewModel.toggleMasterSwitch(false)
            assertTrue(writeAttempted.await(4, TimeUnit.SECONDS))
            assertFalse("Config write failure must not escape the user-action coroutine: $failures",
                uncaught.await(500, TimeUnit.MILLISECONDS))
            assertFalse("Failed secure configuration must stop the UI controls", viewModel.config.value.isMasterEnabled)
            assertPersistentIdentityPreserved()
        } finally { observer.cancelAndJoin() }
    }

    @Test fun mainScreenShowsTheStorageFailureAndRecoveryAction() {
        compose.setContent { MaterialTheme { MainAppScreen(viewModel) } }
        assertTrue(storageAttempted.await(4, TimeUnit.SECONDS))
        compose.onNodeWithText("ذخیره‌سازی امن در دسترس نیست؛ ارسال پیامک متوقف است.").assertExists()
        compose.onNodeWithText("تلاش دوباره برای ذخیره‌سازی امن").assertExists()
    }
}
