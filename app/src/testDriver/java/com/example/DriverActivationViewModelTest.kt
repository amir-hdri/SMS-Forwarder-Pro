package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.example.enrollment.DriverBootstrapWorker
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DriverActivationViewModelTest {
    @get:Rule val secureStorage = SecureStorageTestRule()

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var app: Application
    private lateinit var viewModel: MainViewModel

    @Before fun setUp() {
        Dispatchers.setMain(testDispatcher)
        app = ApplicationProvider.getApplicationContext()
        viewModel = MainViewModel(app)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun invalidPhoneNumberRejectsImmediatelyWithHelpfulError() {
        viewModel.resolveAndActivateDriver("0912")
        val state = viewModel.driverActivationState.value
        assertFalse("State must not be loading for invalid phone", state.isLoading)
        assertEquals("شماره موبایل وارد شده نامعتبر است (فرمت صحیح: 09xxxxxxxxx)", state.errorMessage)
    }

    @Test fun nonIranianPhoneNumberRejectsImmediately() {
        viewModel.resolveAndActivateDriver("01234567890")
        val state = viewModel.driverActivationState.value
        assertFalse("State must not be loading for non-Iranian phone", state.isLoading)
        assertEquals("شماره موبایل وارد شده نامعتبر است (فرمت صحیح: 09xxxxxxxxx)", state.errorMessage)
    }

    @Test fun blankPhoneTriggersImmediateAutoActivationState() {
        viewModel.resolveAndActivateDriver("")
        val state = viewModel.driverActivationState.value
        // Immediate visual feedback triggered
        assertTrue("Blank phone triggers immediate loading state", state.isLoading)
        assertEquals("در حال شناسایی و تأیید خودکار سیم‌کارت…", state.progressMessage)
    }

    @Test fun validPhoneTriggersImmediateLoadingState() {
        viewModel.resolveAndActivateDriver("09382173202")
        val state = viewModel.driverActivationState.value
        // Immediate visual feedback triggered
        assertTrue("Valid phone triggers immediate loading state", state.isLoading)
        assertEquals("در حال استعلام و فعال‌سازی شماره راننده…", state.progressMessage)
    }

    @Test fun persianDigitsAreNormalizedAndTriggerImmediateLoadingState() {
        viewModel.resolveAndActivateDriver("۰۹۳۸۲۱۷۳۲۰۲")
        val state = viewModel.driverActivationState.value
        assertTrue("Persian digits trigger immediate loading state", state.isLoading)
        assertEquals("در حال استعلام و فعال‌سازی شماره راننده…", state.progressMessage)
    }

    @Test fun internationalFormatIsNormalizedAndTriggerImmediateLoadingState() {
        viewModel.resolveAndActivateDriver("+989382173202")
        val state = viewModel.driverActivationState.value
        assertTrue("International format triggers immediate loading state", state.isLoading)
        assertEquals("در حال استعلام و فعال‌سازی شماره راننده…", state.progressMessage)
    }

    @Test fun resetDriverActivationClearsUiState() {
        viewModel.resolveAndActivateDriver("09382173202")
        viewModel.resetDriverActivation()
        val state = viewModel.driverActivationState.value
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertNull(state.successMessage)
    }
}
