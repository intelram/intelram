package com.intelram.netsteer

import android.net.ConnectivityManager
import android.net.Network

/**
 * Applies a network choice to THIS APP'S PROCESS ONLY.
 *
 * bindProcessToNetwork() redirects sockets and DNS created by this process
 * after the call. It cannot affect other apps, and already-open connections
 * stay on whichever network they were created on, so callers must reconnect
 * after a switch.
 */
class NetworkSelector(private val cm: ConnectivityManager) {
    @Volatile var boundId: Long? = null
        private set

    /** Pin the process to [network]; null returns to following the system default. */
    fun bind(network: Network?): Boolean {
        val ok = cm.bindProcessToNetwork(network)
        if (ok) boundId = network?.networkHandle
        return ok
    }

    /**
     * Tell Android a network looks broken (or fine) from our point of view.
     * This nudges the OS to re-run its own validation; it does not force a switch.
     */
    fun report(network: Network, hasConnectivity: Boolean) =
        cm.reportNetworkConnectivity(network, hasConnectivity)
}
