package com.threadprotection.app

import android.app.Application
import com.threadprotection.app.data.SettingsRepository

class ThreadProtectionApp : Application() {
    val settingsRepository by lazy { SettingsRepository(this) }
}
