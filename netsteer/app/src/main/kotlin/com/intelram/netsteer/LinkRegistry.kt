package com.intelram.netsteer

import android.content.Context
import android.net.Network
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import com.intelram.netsteer.core.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

enum class LinkState { ACTIVE, READY, CONNECTING, NO_INTERNET, BLOCKED, OFF }

/** One link as shown to the user. */
data class LinkStatus(
    val id: String,
    val label: String,
    val state: LinkState,
    /** Why it is in this state, in plain words. */
    val detail: String,
    val rxBps: Double = 0.0,
    val txBps: Double = 0.0,
    val connections: Int = 0,
    val usedTodayBytes: Long = 0,
) {
    val up: Boolean get() = state == LinkState.ACTIVE || state == LinkState.READY
}

/**
 * All internet links the phone has (Wi-Fi, a second Wi-Fi on dual-Wi-Fi phones,
 * mobile, USB Ethernet, Bluetooth tethering), with stable ids so stats and the
 * scheduler keep their history when a network reconnects.
 *
 * A network is usable when Android validated it, or when our own reachability
 * probe succeeded recently (some carriers/OEMs never mark a background mobile
 * network as validated even though it works).
 */
class LinkRegistry(ctx: Context, private val monitor: NetworkMonitor) {
    private val telephony = ctx.getSystemService(TelephonyManager::class.java)
    private val ids = ConcurrentHashMap<Long, String>() // network handle -> id
    private val probedOkAt = ConcurrentHashMap<Long, Long>()
    private val cache = ConcurrentHashMap<String, AndroidUplink>()

    data class Link(val id: String, val label: String, val transport: Transport, val state: NetworkState, val usable: Boolean)

    /** Every known network, usable or not. */
    fun links(): List<Link> {
        val now = System.currentTimeMillis()
        val nets = monitor.networks.value
        ids.keys.retainAll(nets.keys)
        probedOkAt.keys.retainAll(nets.keys)
        return nets.values.mapNotNull { s ->
            val kind = kindOf(s) ?: return@mapNotNull null
            val id = idFor(s.id, kind)
            val snap = s.toSnapshot()
            val partial = s.caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_PARTIAL_CONNECTIVITY) == true
            val probed = (probedOkAt[s.id] ?: 0) > now - PROBE_VALID_MS
            val usable = s.caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true && !snap.captivePortal && !snap.blocked && !partial && (snap.validated || probed)
            Link(id, labelFor(id), snap.transport, s, usable)
        }.sortedWith(compareBy({ ORDER.indexOf(it.id.takeWhile(Char::isLetter)) }, { it.id }))
    }

    /** Usable links as proxy uplinks (sockets bound to each network). */
    fun uplinks(): List<AndroidUplink> = links().filter { it.usable }.map { l ->
        cache[l.id]?.takeIf { it.network == l.state.network } ?: AndroidUplink(l.state.network, l.id).also { cache[l.id] = it }
    }

    /** Probe networks Android hasn't validated, so working-but-unvalidated links can still be used. */
    suspend fun probeUnvalidated() = withContext(Dispatchers.IO) {
        val targets = monitor.networks.value.values.filter {
            kindOf(it) != null && !it.toSnapshot().validated && !it.toSnapshot().captivePortal && !it.toSnapshot().blocked
        }
        coroutineScope {
            targets.map { s ->
                async {
                    if (reachable(s.network)) probedOkAt[s.id] = System.currentTimeMillis() else probedOkAt.remove(s.id)
                }
            }.awaitAll()
        }
    }

    private fun reachable(n: Network): Boolean = PROBE_TARGETS.any { (host, port) ->
        try {
            n.socketFactory.createSocket().use { it.connect(InetSocketAddress(host, port), 3_000) }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** User-facing status for every link, including expected-but-missing mobile data. */
    fun describe(active: Set<String>, mobileHeld: Boolean, mobileWithheld: Boolean): List<LinkStatus> {
        val out = ArrayList<LinkStatus>()
        val ls = links()
        for (l in ls) {
            val snap = l.state.toSnapshot()
            val (state, detail) = when {
                snap.blocked -> LinkState.BLOCKED to "Blocked for NetSteer by Data Saver or background-data restriction"
                snap.captivePortal -> LinkState.NO_INTERNET to "Needs sign-in (captive portal)"
                !l.usable && !snap.validated -> LinkState.NO_INTERNET to "Connected, but no internet"
                !l.usable -> LinkState.NO_INTERNET to "Limited connectivity"
                l.transport == Transport.CELLULAR && mobileWithheld -> LinkState.READY to "Ready · joins for big downloads (battery saver)"
                l.id in active -> LinkState.ACTIVE to (if (snap.validated) "In use" else "In use (verified by NetSteer)")
                else -> LinkState.READY to "Ready"
            }
            out += LinkStatus(l.id, l.label, state, detail)
        }
        if (ls.none { it.transport == Transport.CELLULAR }) out += mobileMissing(mobileHeld)
        if (ls.none { it.transport == Transport.WIFI }) out.add(0, LinkStatus("wifi", "Wi-Fi", LinkState.OFF, "Not connected"))
        return out
    }

    private fun mobileMissing(held: Boolean): LinkStatus {
        val sim = runCatching { telephony?.simState }.getOrNull()
        val dataOn = runCatching { telephony?.isDataEnabled }.getOrNull()
        val (state, detail) = when {
            telephony == null -> LinkState.OFF to "This device has no mobile data"
            sim != null && sim != TelephonyManager.SIM_STATE_READY -> LinkState.OFF to "No SIM card ready"
            dataOn == false -> LinkState.OFF to "Mobile data is turned off in Android settings"
            held -> LinkState.CONNECTING to "Connecting… (if this stays, your carrier or phone may not allow mobile data while on Wi-Fi)"
            else -> LinkState.READY to "Standby · joins for big downloads (battery saver)"
        }
        return LinkStatus("cell", "Mobile", state, detail)
    }

    private fun kindOf(s: NetworkState): String? {
        val c = s.caps ?: return null
        return when {
            c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> null
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) || c.hasTransport(NetworkCapabilities.TRANSPORT_USB) -> "eth"
            c.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bt"
            else -> null
        }
    }

    /** First free id of this kind: wifi, wifi2, wifi3… Stable for the network's lifetime. */
    @Synchronized
    private fun idFor(handle: Long, kind: String): String {
        ids[handle]?.let { return it }
        val taken = ids.values.toSet()
        val id = generateSequence(1) { it + 1 }.map { if (it == 1) kind else "$kind$it" }.first { it !in taken }
        ids[handle] = id
        return id
    }

    companion object {
        private const val PROBE_VALID_MS = 60_000L
        private val ORDER = listOf("wifi", "cell", "eth", "bt")
        private val PROBE_TARGETS = listOf("1.1.1.1" to 443, "8.8.8.8" to 443, "9.9.9.9" to 443)

        fun labelFor(id: String): String {
            val n = id.dropWhile { it.isLetter() }
            val base = when (id.takeWhile { it.isLetter() }) {
                "wifi" -> "Wi-Fi"
                "cell" -> "Mobile"
                "eth" -> "Ethernet"
                "bt" -> "Bluetooth"
                else -> id
            }
            return if (n.isEmpty()) base else "$base $n"
        }

        fun isMobile(id: String) = id.startsWith("cell")
    }
}
