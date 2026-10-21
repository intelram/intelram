package com.intelram.netsteer

import android.app.Application
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Process-wide singletons shared by the UI and the VPN service. */
class NetSteerApp : Application() {
    lateinit var monitor: NetworkMonitor
        private set
    lateinit var settings: Settings
        private set
    lateinit var filters: FilterManager
        private set
    lateinit var links: LinkRegistry
        private set
    lateinit var usage: UsageStore
        private set
    val prober = Prober()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val debuggable: Boolean get() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        filters = FilterManager(this, settings)
        val cm = getSystemService(ConnectivityManager::class.java)!!
        monitor = NetworkMonitor(cm).also { it.start() }
        links = LinkRegistry(this, monitor)
        usage = UsageStore(this)

        scope.launch(Dispatchers.IO) { filters.load() }
        scope.launch(Dispatchers.IO) {
            // Enabling/disabling a list changes what is compiled.
            settings.values.map { it.listAdGuard to it.listStevenBlack }.distinctUntilChanged().drop(1).collect {
                filters.load()
            }
        }

        if (debuggable) {
            // Debug builds: log every change in what the app sees, for the emulator test and bug reports.
            scope.launch {
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
}
