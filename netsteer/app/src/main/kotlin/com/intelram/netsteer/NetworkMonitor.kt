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
 * Tracking is passive. Android normally tears mobile data down shortly after
 * Wi-Fi validates; [setMobileRequested] holds a requestNetwork(CELLULAR) to keep
 * it connected, which costs battery, so callers hold it only while needed.
 */
class NetworkMonitor(private val cm: ConnectivityManager) {
    private val _networks = MutableStateFlow<Map<Long, NetworkState>>(emptyMap())
    val networks: StateFlow<Map<Long, NetworkState>> = _networks.asStateFlow()

    private val _systemDefault = MutableStateFlow<Long?>(null)
    val systemDefault: StateFlow<Long?> = _systemDefault.asStateFlow()

    private val registered = mutableListOf<ConnectivityManager.NetworkCallback>()
    private var started = false
    private var mobileRequest: ConnectivityManager.NetworkCallback? = null

    /** Whether mobile data is currently being held up by us. */
    val mobileRequested: Boolean @Synchronized get() = mobileRequest != null

    @Synchronized
    fun start() {
        if (started) return
        started = true
        cm.registerNetworkCallback(internetRequest(NetworkCapabilities.TRANSPORT_WIFI), tracker().also(registered::add))
        cm.registerNetworkCallback(internetRequest(NetworkCapabilities.TRANSPORT_CELLULAR), tracker().also(registered::add))

        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { _systemDefault.value = network.networkHandle }
            override fun onLost(network: Network) {
                _systemDefault.update { if (it == network.networkHandle) null else it }
            }
        }.also(registered::add))
    }

    /** Keep mobile data connected (true) or let Android manage it (false). Idempotent. */
    @Synchronized
    fun setMobileRequested(on: Boolean) {
        if (on && mobileRequest == null) {
            val cb = object : ConnectivityManager.NetworkCallback() {} // state arrives via the tracker
            runCatching { cm.requestNetwork(internetRequest(NetworkCapabilities.TRANSPORT_CELLULAR), cb) }
                .onSuccess { mobileRequest = cb }
        } else if (!on) {
            mobileRequest?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            mobileRequest = null
        }
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
