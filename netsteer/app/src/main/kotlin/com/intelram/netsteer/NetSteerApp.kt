package com.intelram.netsteer

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager

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
    }

    private companion object {
        const val PREFS = "netsteer"
        const val KEY_COMPAT = "compat_mode"
    }
}
