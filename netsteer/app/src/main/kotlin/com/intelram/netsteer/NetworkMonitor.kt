package com.intelram.netsteer

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.intelram.netsteer.core.NetworkSnapshot
import com.intelram.netsteer.core.ProbeStats
import com.intelram.netsteer.core.Transport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Everything the OS tells us about one network. */
data class NetworkState(
    val network: Network,
    val caps: NetworkCapabilities?,
    val link: LinkProperties?,
    val blocked: Boolean = false,
    val probe: ProbeStats? = null,
) {
    val id: Long get() = network.networkHandle

    val transport: Transport
        get() = when {
            caps == null -> Transport.OTHER
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> Transport.VPN
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
            else -> Transport.OTHER
        }

    fun toSnapshot() = NetworkSnapshot(
        id = id,
        transport = transport,
        validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        captivePortal = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
        metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true,
        blocked = blocked,
        downKbps = caps?.linkDownstreamBandwidthKbps ?: 0,
        upKbps = caps?.linkUpstreamBandwidthKbps ?: 0,
        probe = probe,
    )
}

/**
 * Tracks every Wi-Fi and cellular network with internet capability, plus the
 * system default network.
 *
 * Android normally tears cellular down shortly after Wi-Fi validates. With
 * [hotStandby] the monitor *requests* cellular (requestNetwork), which keeps the
 * radio attached so failover is instant. This costs battery and may incur
 * carrier keep-alive traffic; it is off by default.
 */
class NetworkMonitor(private val cm: ConnectivityManager) {
    private val _networks = MutableStateFlow<Map<Long, NetworkState>>(emptyMap())
    val networks: StateFlow<Map<Long, NetworkState>> = _networks.asStateFlow()

    private val _systemDefault = MutableStateFlow<Long?>(null)
    val systemDefault: StateFlow<Long?> = _systemDefault.asStateFlow()

    private val registered = mutableListOf<ConnectivityManager.NetworkCallback>()
    private var started = false

    @Synchronized
    fun start(hotStandby: Boolean) {
        if (started) stop()
        started = true

        val wifi = internetRequest(NetworkCapabilities.TRANSPORT_WIFI)
        val cell = internetRequest(NetworkCapabilities.TRANSPORT_CELLULAR)

        cm.registerNetworkCallback(wifi, tracker().also(registered::add))
        val cellCb = tracker().also(registered::add)
        if (hotStandby) cm.requestNetwork(cell, cellCb) else cm.registerNetworkCallback(cell, cellCb)

        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { _systemDefault.value = network.networkHandle }
            override fun onLost(network: Network) {
                _systemDefault.update { if (it == network.networkHandle) null else it }
            }
        }.also(registered::add))
    }

    @Synchronized
    fun stop() {
        registered.forEach { runCatching { cm.unregisterNetworkCallback(it) } }
        registered.clear()
        _networks.value = emptyMap()
        started = false
    }

    fun recordProbe(id: Long, stats: ProbeStats) =
        _networks.update { m -> m[id]?.let { m + (id to it.copy(probe = stats)) } ?: m }

    fun find(id: Long): Network? = _networks.value[id]?.network

    private fun internetRequest(transport: Int) = NetworkRequest.Builder()
        .addTransportType(transport)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()

    private fun tracker() = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = upsert(network) { it }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            upsert(network) { it.copy(caps = caps) }

        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) =
            upsert(network) { it.copy(link = lp) }

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) =
            upsert(network) { it.copy(blocked = blocked) }

        override fun onLost(network: Network) =
            _networks.update { it - network.networkHandle }
    }

    private inline fun upsert(network: Network, crossinline f: (NetworkState) -> NetworkState) =
        _networks.update { m ->
            val cur = m[network.networkHandle] ?: NetworkState(
                network,
                cm.getNetworkCapabilities(network),
                cm.getLinkProperties(network),
            )
            m + (network.networkHandle to f(cur))
        }
}
