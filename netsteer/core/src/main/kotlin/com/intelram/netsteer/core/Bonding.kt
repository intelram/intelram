package com.intelram.netsteer.core

/** Wi-Fi security as advertised in a scan result's capabilities string (e.g. "[WPA2-PSK-CCMP][RSN-SAE-CCMP][ESS]"). */
enum class WifiSecurity(val label: String, val needsPassword: Boolean, val supported: Boolean) {
    OPEN("Open", false, true),
    OWE("Enhanced open", false, true),
    WPA2_PSK("WPA2", true, true),
    WPA3_SAE("WPA3", true, true),
    WPA2_WPA3("WPA2/WPA3", true, true),
    /** Deprecated and insecure; Android no longer accepts WEP from apps. */
    WEP("WEP (insecure)", true, false),
    /** Needs certificates/identity: not offered through this screen. */
    ENTERPRISE("Enterprise", true, false),
    UNKNOWN("Unknown", true, false);

    companion object {
        fun parse(capabilities: String?): WifiSecurity {
            val c = (capabilities ?: "").uppercase()
            return when {
                c.contains("EAP") -> ENTERPRISE
                c.contains("SAE") && c.contains("PSK") -> WPA2_WPA3
                c.contains("SAE") -> WPA3_SAE
                c.contains("PSK") -> WPA2_PSK
                c.contains("OWE") -> OWE
                c.contains("WEP") -> WEP
                c.contains("WPA") -> UNKNOWN
                else -> OPEN
            }
        }
    }
}

object WifiBands {
    fun bandOf(freqMHz: Int): String = when (freqMHz) {
        in 2400..2500 -> "2.4 GHz"
        in 4900..5900 -> "5 GHz"
        in 5925..7125 -> "6 GHz"
        else -> "${freqMHz} MHz"
    }

    /** 0..4 bars from RSSI in dBm. */
    fun bars(dBm: Int): Int = when {
        dBm >= -55 -> 4
        dBm >= -67 -> 3
        dBm >= -75 -> 2
        dBm >= -85 -> 1
        else -> 0
    }
}

/**
 * The user's chosen Wi-Fi sources (max [max]). Passwords are never stored here:
 * they go straight to Android's suggestion API, which keeps them.
 */
class WifiSelection(private val max: Int = MAX) {
    data class Entry(val ssid: String, val security: WifiSecurity)

    enum class AddResult { ADDED, DUPLICATE, FULL, UNSUPPORTED, INVALID_SSID }

    private val entries = ArrayList<Entry>()

    val list: List<Entry> get() = entries.toList()
    val size: Int get() = entries.size
    val isFull: Boolean get() = entries.size >= max

    @Synchronized
    fun add(ssid: String, security: WifiSecurity): AddResult {
        val s = ssid.trim()
        if (s.isEmpty() || s.toByteArray().size > 32) return AddResult.INVALID_SSID
        if (!security.supported) return AddResult.UNSUPPORTED
        if (entries.any { it.ssid == s && it.security == security }) return AddResult.DUPLICATE
        if (entries.size >= max) return AddResult.FULL
        entries += Entry(s, security)
        return AddResult.ADDED
    }

    @Synchronized
    fun remove(ssid: String, security: WifiSecurity): Boolean = entries.removeIf { it.ssid == ssid && it.security == security }

    fun contains(ssid: String): Boolean = entries.any { it.ssid == ssid }

    /** One "SECURITY\tSSID" per line; tabs/newlines can't appear in SSIDs we accept. */
    fun serialize(): String = entries.joinToString("\n") { "${it.security.name}\t${it.ssid.replace("\t", " ").replace("\n", " ")}" }

    companion object {
        const val MAX = 4

        fun deserialize(s: String?, max: Int = MAX): WifiSelection {
            val sel = WifiSelection(max)
            s?.lineSequence()?.forEach { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) {
                    val sec = runCatching { WifiSecurity.valueOf(line.substring(0, tab)) }.getOrNull() ?: return@forEach
                    sel.add(line.substring(tab + 1), sec)
                }
            }
            return sel
        }
    }
}

/**
 * What the user is actually getting. "Bonding" (one connection's packets over
 * several links) is not achievable without a remote endpoint, so it is never reported.
 */
enum class ConnectionMode(val label: String, val explanation: String) {
    OFF("Off", "NetSteer is not running."),
    NO_NETWORK("No network", "No working internet connection right now."),
    SINGLE("Single connection", "Only one network is usable, so all traffic uses it."),
    FAILOVER(
        "Failover",
        "One network carries traffic; the others are on standby and take over if it fails.",
    ),
    LOAD_BALANCING(
        "Load balancing",
        "New connections are spread across all active networks by measured speed and health. " +
            "Many parallel connections add up; a single download stream still uses one network " +
            "(packet-level bonding needs a remote bonding server).",
    );

    companion object {
        /**
         * @param active links currently taking new connections.
         * @param standby usable links held back (battery saver, mobile off for bonding, failing health)
         *        or added Wi-Fi networks Android can switch to.
         */
        fun classify(running: Boolean, active: Int, standby: Int): ConnectionMode = when {
            !running -> OFF
            active <= 0 -> NO_NETWORK
            active == 1 && standby > 0 -> FAILOVER
            active == 1 -> SINGLE
            else -> LOAD_BALANCING
        }
    }
}

/** Per-link latency/loss from periodic probes; links failing most probes are held out of new connections. */
class HealthBook(private val clock: () -> Long = System::currentTimeMillis, private val freshMs: Long = 120_000) {
    data class Health(val rttMs: Int?, val lossRatio: Double, val at: Long)

    private val map = HashMap<String, Health>()

    @Synchronized fun record(id: String, rttMs: Int?, lossRatio: Double) { map[id] = Health(rttMs, lossRatio, clock()) }

    @Synchronized fun get(id: String): Health? = map[id]?.takeIf { clock() - it.at <= freshMs }

    /** Most recent probes mostly failed (and the result is fresh). */
    @Synchronized fun unhealthy(id: String): Boolean = get(id)?.let { it.lossRatio >= 0.67 } ?: false

    @Synchronized fun retain(ids: Set<String>) { map.keys.retainAll(ids) }
}
