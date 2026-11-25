package com.example.framescope

import android.app.Application
import com.example.framescope.data.RecordingsRepository
import com.example.framescope.data.SettingsRepository

class FrameScopeApplication : Application() {
    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var recordingsRepository: RecordingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(applicationContext)
        recordingsRepository = RecordingsRepository(applicationContext)
    }
}
