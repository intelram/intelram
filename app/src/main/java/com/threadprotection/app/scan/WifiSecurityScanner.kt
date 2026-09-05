package com.threadprotection.app.scan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.threadprotection.app.data.Category
import com.threadprotection.app.data.Finding
import com.threadprotection.app.data.Remedy
import com.threadprotection.app.ui.theme.Severity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import kotlin.random.Random

/**
 * Assesses the network the phone is actually on right now — the one part of the threat surface the
 * scan had no view of at all: the previous Wi-Fi code (`WifiInfo.kt`) only read the network's *name*
 * for display and never judged it.
 *
 * Everything here is a real property of the live connection, read from Android's own
 * `WifiManager`/`ConnectivityManager`, or a live DNS probe made at scan time. Where Android refuses
 * to tell an ordinary app something, that is reported as unknown rather than guessed — see
 * [Encryption.UNKNOWN] and [NetworkSecurity.dnsHijack]'s null case.
 */
object WifiSecurityScanner {

    private const val TAG = "WifiSecurity"

    /** What actually protects the traffic on this network. */
    enum class Encryption {
        /** No encryption at all — anyone in range can read the traffic off the air. */
        OPEN,

        /** Enhanced Open (OWE): encrypted but unauthenticated, so still no protection from a fake AP. */
        OWE,

        /** Broken since 2001; recoverable in minutes. */
        WEP,

        /** Original WPA/TKIP — deprecated and attackable. */
        WPA,
        WPA2,
        WPA3,

        /** 802.1X / EAP — corporate networks, individually authenticated. */
        ENTERPRISE,

        /** Android wouldn't say (needs location services on, or an OS version that exposes it). */
        UNKNOWN,
    }

    data class NetworkSecurity(
        val onWifi: Boolean,
        val ssid: String?,
        val encryption: Encryption,
        /** Sitting behind a hotel/airport sign-in page. */
        val captivePortal: Boolean,
        /** Android verified a real internet path on this network. */
        val validated: Boolean,
        val vpnActive: Boolean,
        /** Encrypted DNS hostname when DNS-over-TLS is on, or null. */
        val privateDns: String?,
        val dnsServers: List<String>,
        /** True when the network answered a guaranteed-nonexistent name; null when untestable. */
        val dnsHijack: Boolean?,
    )

    suspend fun scan(context: Context): NetworkSecurity = withContext(Dispatchers.IO) {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val caps = network?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }
        val link = network?.let { runCatching { cm.getLinkProperties(it) }.getOrNull() }

        val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val ssid = (WifiInfo.current(context) as? WifiStatus.Connected)?.ssid

        NetworkSecurity(
            onWifi = onWifi,
            ssid = ssid,
            encryption = if (onWifi) currentEncryption(context) else Encryption.UNKNOWN,
            captivePortal = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            vpnActive = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
            privateDns = privateDnsOf(link),
            dnsServers = link?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty(),
            dnsHijack = probeDnsHijack(),
        )
    }

    private fun privateDnsOf(link: LinkProperties?): String? {
        if (link == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return runCatching {
            when {
                link.privateDnsServerName != null -> link.privateDnsServerName
                link.isPrivateDnsActive -> "on (automatic)"
                else -> null
            }
        }.getOrNull()
    }

    /**
     * Reads the live network's cipher. Android 12 exposes this directly; older versions only expose
     * it via the scan-results table, which additionally requires location services to be switched on
     * — hence [Encryption.UNKNOWN] rather than an assumption when it isn't.
     */
    @Suppress("DEPRECATION")
    private fun currentEncryption(context: Context): Encryption {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return Encryption.UNKNOWN
        val info = runCatching { wifi.connectionInfo }.getOrNull() ?: return Encryption.UNKNOWN

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { info.currentSecurityType }.getOrNull()?.let { type ->
                securityTypeToEncryption(type)?.let { return it }
            }
        }

        // Pre-Android-12 fallback: find this BSSID in the scan table and read its capability string.
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return Encryption.UNKNOWN
        val bssid = info.bssid
        val ssid = info.ssid?.removeSurrounding("\"")
        val results = runCatching { wifi.scanResults }.getOrNull().orEmpty()
        val match = results.firstOrNull { it.BSSID == bssid } ?: results.firstOrNull { it.SSID == ssid }
        return match?.capabilities?.let { encryptionFromCapabilities(it) } ?: Encryption.UNKNOWN
    }

    /** Maps `android.net.wifi.WifiInfo.SECURITY_TYPE_*` without referencing constants that don't
     *  exist on older compile targets. */
    internal fun securityTypeToEncryption(type: Int): Encryption? = when (type) {
        0 -> Encryption.OPEN // SECURITY_TYPE_OPEN
        1 -> Encryption.WEP // SECURITY_TYPE_WEP
        2 -> Encryption.WPA2 // SECURITY_TYPE_PSK — WPA/WPA2 personal
        3 -> Encryption.ENTERPRISE // SECURITY_TYPE_EAP
        4 -> Encryption.WPA3 // SECURITY_TYPE_SAE
        5, 9, 10, 11 -> Encryption.ENTERPRISE // EAP suite-B / WPA3-enterprise variants
        6 -> Encryption.OWE // SECURITY_TYPE_OWE
        else -> null
    }

    /**
     * Parses the capability string Android reports for a scanned access point, e.g.
     * `[WPA2-PSK-CCMP][ESS]`. Kept pure and internal so the mapping is unit-tested rather than
     * only exercised on a phone that happens to be near the right kind of network.
     */
    internal fun encryptionFromCapabilities(capabilities: String): Encryption {
        val c = capabilities.uppercase()
        return when {
            c.contains("SAE") || c.contains("WPA3") -> Encryption.WPA3
            c.contains("EAP") -> Encryption.ENTERPRISE
            c.contains("OWE") -> Encryption.OWE
            c.contains("WPA2") || c.contains("RSN") -> Encryption.WPA2
            c.contains("WPA") -> Encryption.WPA
            c.contains("WEP") -> Encryption.WEP
            // No cipher token at all — just [ESS]/[IBSS]-style flags — means an open network.
            c.contains("ESS") || c.isBlank() -> Encryption.OPEN
            else -> Encryption.UNKNOWN
        }
    }

    /**
     * Asks the network to resolve a name that cannot exist. `.invalid` is reserved by RFC 6761
     * precisely so that it never resolves, so any address in the answer means this network is
     * rewriting DNS failures — the mechanism behind captive-portal ad injection and search
     * hijacking, and the same position an attacker needs to redirect a real domain.
     *
     * Chosen over comparing a real domain's answer against a trusted resolver, which would report a
     * false alarm every time a CDN legitimately answered differently for a different client.
     */
    private suspend fun probeDnsHijack(): Boolean? = withTimeoutOrNull(4_000) {
        val nonce = (1..12).map { "abcdefghijklmnopqrstuvwxyz0123456789".random(Random) }.joinToString("")
        runCatching {
            val answers = InetAddress.getAllByName("$nonce.invalid")
            answers.isNotEmpty()
        }.getOrElse {
            // The expected, healthy outcome: the name genuinely doesn't resolve.
            Log.d(TAG, "DNS hijack probe: name correctly did not resolve")
            false
        }
    }

    /**
     * Turns the assessment into the same [Finding] shape every other check produces, so these
     * surface through the existing results/threat-detail screens with no screen of their own.
     */
    fun findings(security: NetworkSecurity): List<Finding> {
        if (!security.onWifi) return emptyList()
        val findings = mutableListOf<Finding>()
        val where = security.ssid?.let { "\"$it\"" } ?: "this network"

        when (security.encryption) {
            Encryption.OPEN, Encryption.OWE -> findings += Finding(
                id = "wifi-open",
                name = "Wi-Fi network $where has no password protection",
                type = "Network · Encryption",
                cat = Category.NETWORK,
                sev = Severity.HIGH,
                risk = 78,
                desc = if (security.encryption == Encryption.OWE) {
                    "This network uses Enhanced Open, which encrypts traffic but never proves the network is who it claims to be — anyone can stand up an identical one."
                } else {
                    "This network is unencrypted. Everything sent over it that isn't itself encrypted can be read by anyone within radio range, and a fake copy of the network is trivial to run."
                },
                advice = "Avoid signing in to anything, banking or shopping while on it. Use mobile data or a VPN for anything private.",
                fix = "Review Wi-Fi settings",
                pros = listOf("Removes the easiest way for someone nearby to read your traffic"),
                cons = listOf("You may lose the free connection this network provides"),
                source = "WifiManager (on-device)",
                remedy = Remedy.None,
            )
            Encryption.WEP -> findings += Finding(
                id = "wifi-wep",
                name = "Wi-Fi network $where uses WEP, which is broken",
                type = "Network · Encryption",
                cat = Category.NETWORK,
                sev = Severity.CRITICAL,
                risk = 88,
                desc = "WEP has been considered broken since 2001 — its key can be recovered from ordinary captured traffic in minutes. It offers no meaningful protection.",
                advice = "Change the router to WPA2 or WPA3 if it's yours; otherwise treat this network as fully public.",
                fix = "Review Wi-Fi settings",
                pros = listOf("WPA2/WPA3 makes captured traffic genuinely unreadable"),
                cons = listOf("Very old devices that only speak WEP would need replacing"),
                source = "WifiManager (on-device)",
                remedy = Remedy.None,
            )
            Encryption.WPA -> findings += Finding(
                id = "wifi-wpa1",
                name = "Wi-Fi network $where uses the original WPA",
                type = "Network · Encryption",
                cat = Category.NETWORK,
                sev = Severity.MEDIUM,
                risk = 52,
                desc = "The first version of WPA (TKIP) is deprecated and has known practical weaknesses. It is much weaker than the WPA2 every current router supports.",
                advice = "If this is your own network, switch it to WPA2 or WPA3 in the router settings.",
                fix = "Review Wi-Fi settings",
                pros = listOf("WPA2/WPA3 closes the known weaknesses in TKIP"),
                cons = listOf("Devices from before roughly 2006 may not reconnect"),
                source = "WifiManager (on-device)",
                remedy = Remedy.None,
            )
            Encryption.WPA2, Encryption.WPA3, Encryption.ENTERPRISE, Encryption.UNKNOWN -> Unit
        }

        if (security.dnsHijack == true) {
            findings += Finding(
                id = "wifi-dns-hijack",
                name = "This network is tampering with DNS",
                type = "Network · DNS",
                cat = Category.NETWORK,
                sev = Severity.HIGH,
                risk = 80,
                desc = "Asked to look up an address that cannot exist, this network returned one anyway. That means it rewrites DNS answers — the same position needed to quietly send a real address somewhere else.",
                advice = "Don't sign in to anything on this network. Turning on Private DNS (Settings → Network → Private DNS) stops the network seeing or changing your lookups.",
                fix = "Review network settings",
                pros = listOf("Private DNS makes lookups unreadable and unmodifiable by the network"),
                cons = listOf("A few captive-portal networks need Private DNS off to show their sign-in page"),
                source = "Live DNS probe (RFC 6761 .invalid)",
                remedy = Remedy.None,
            )
        }

        if (security.captivePortal) {
            findings += Finding(
                id = "wifi-captive",
                name = "Sign-in required — this network intercepts traffic",
                type = "Network · Captive portal",
                cat = Category.NETWORK,
                sev = Severity.LOW,
                risk = 30,
                desc = "This network is holding traffic behind a sign-in page. That's normal for hotels, airports and cafés, but it does mean the network is inspecting and redirecting connections.",
                advice = "Only enter the details the venue actually asked for, and never reuse a password on a portal page.",
                fix = "Review network settings",
                pros = listOf("Knowing traffic is being intercepted is the point of checking"),
                cons = listOf("Nothing — this is informational"),
                source = "ConnectivityManager (on-device)",
                remedy = Remedy.None,
            )
        }

        return findings
    }

    /** One-line summary for the scan progress line — no finding, just what was seen. */
    fun summaryLabel(security: NetworkSecurity): String = when {
        !security.onWifi -> "Not on Wi-Fi — mobile data or no connection"
        else -> buildList {
            add(
                when (security.encryption) {
                    Encryption.OPEN -> "Open network"
                    Encryption.OWE -> "Enhanced Open"
                    Encryption.WEP -> "WEP (broken)"
                    Encryption.WPA -> "WPA (deprecated)"
                    Encryption.WPA2 -> "WPA2"
                    Encryption.WPA3 -> "WPA3"
                    Encryption.ENTERPRISE -> "Enterprise (802.1X)"
                    Encryption.UNKNOWN -> "Encryption not readable"
                },
            )
            if (security.vpnActive) add("VPN active")
            if (security.privateDns != null) add("Private DNS on")
            if (security.dnsHijack == true) add("DNS tampering")
        }.joinToString(" · ")
    }
}
