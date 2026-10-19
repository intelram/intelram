package com.intelram.netsteer

import android.app.Application
import android.net.ConnectivityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-wide singletons shared by the UI and the foreground service. */
class NetSteerApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var monitor: NetworkMonitor
        private set
    lateinit var selector: NetworkSelector
        private set
    lateinit var controller: FailoverController
        private set
    val prober = Prober()
    val downloader = MultiNetworkDownloader()

    override fun onCreate() {
        super.onCreate()
        val cm = getSystemService(ConnectivityManager::class.java)!!
        monitor = NetworkMonitor(cm).also { it.start(hotStandby = false) }
        selector = NetworkSelector(cm)
        controller = FailoverController(monitor, selector, prober, scope)
    }
}
