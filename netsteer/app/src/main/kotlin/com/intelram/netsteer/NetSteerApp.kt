package com.intelram.netsteer

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.util.Log
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Process-wide singletons shared by the UI and the VPN service. */
class NetSteerApp : Application() {
    lateinit var monitor: NetworkMonitor
        private set
    val prober = Prober()

    /**
     * Keep each website on one network for 10 minutes. Fixes sites that tie a
     * login session to one IP address, at the cost of less combining.
     */
    @Volatile var compatibilityMode: Boolean = false
        set(value) {
            field = value
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_COMPAT, value).apply()
        }

    override fun onCreate() {
        super.onCreate()
        compatibilityMode = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_COMPAT, false)
        val cm = getSystemService(ConnectivityManager::class.java)!!
        monitor = NetworkMonitor(cm).also { it.start(hotStandby = false) }
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            // Debug builds: log every change in what the app sees, for the emulator test and bug reports.
            MainScope().launch {
                monitor.networks
                    .map { nets ->
                        nets.values.map { it.toSnapshot() }.sortedBy { it.transport }.joinToString("; ") {
                            "${it.transport} validated=${it.validated} captive=${it.captivePortal} blocked=${it.blocked}"
                        }
                    }
                    .distinctUntilChanged()
                    .collect { Log.i("NetSteer", "net: $it") }
            }
        }
    }

    private companion object {
        const val PREFS = "netsteer"
        const val KEY_COMPAT = "compat_mode"
    }
}
