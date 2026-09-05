package com.example

import android.app.Application
import com.example.data.local.AppDatabase
import com.example.data.repository.SmsForwardRepository

class SmsForwarderApp : Application() {

    lateinit var repository: SmsForwardRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = SmsForwardRepository.getInstance(this)
        com.example.service.ServerHealthNotifier.ensureChannel(this)
    }
}
