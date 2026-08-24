package com.threadprotection.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.threadprotection.app.data.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Restarts the protection service after a reboot, if the user had real-time protection on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val key = booleanPreferencesKey("tp_realtime")
                val enabled = appContext.dataStore.data.first()[key] ?: true
                if (enabled) ProtectionForegroundService.start(appContext)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
