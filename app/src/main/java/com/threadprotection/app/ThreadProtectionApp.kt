package com.threadprotection.app

import android.app.Application
import com.threadprotection.app.data.SettingsRepository
import dagger.hilt.android.HiltAndroidApp

/**
 * `@HiltAndroidApp` generates the app-wide Hilt component but changes nothing about how the
 * existing app is wired: [settingsRepository] and every other manual `by lazy`/`getInstance()`
 * dependency elsewhere in the app (BluetoothChatManager, MeshRelayManager, ExternalDeviceMonitor)
 * keeps working exactly as before. Hilt is used only for the new Analyst Mode feature — see
 * analyst/di/AnalystModule.kt.
 */
@HiltAndroidApp
class ThreadProtectionApp : Application() {
    val settingsRepository by lazy { SettingsRepository(this) }
}
