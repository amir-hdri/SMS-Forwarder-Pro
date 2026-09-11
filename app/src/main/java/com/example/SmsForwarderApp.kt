package com.example

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import com.example.data.repository.SmsForwardRepository

class SmsForwarderApp : Application(), Configuration.Provider {

    lateinit var repository: SmsForwardRepository
        private set

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        repository = SmsForwardRepository.getInstance(this)
        try {
            com.example.service.ServerHealthNotifier.ensureChannel(this)
        } catch (e: Exception) {
            Log.w("SmsForwarderApp", "Failed to ensure notification channel: ${e.message}")
        }
        try {
            com.example.service.CleanupWorker.schedule(this)
        } catch (e: Exception) {
            Log.w("SmsForwarderApp", "Failed to schedule CleanupWorker: ${e.message}")
        }
    }
}

