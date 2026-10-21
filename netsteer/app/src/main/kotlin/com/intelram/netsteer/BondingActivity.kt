package com.intelram.netsteer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.intelram.netsteer.core.ConnectionMode
import com.intelram.netsteer.core.WifiBands
import com.intelram.netsteer.core.WifiSecurity
import com.intelram.netsteer.core.WifiSelection
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Internet Bonding: nearby Wi-Fi, up to four added Wi-Fi sources, mobile data, and what
 * NetSteer is actually doing with them (load balancing / failover / single connection).
 */
class BondingActivity : Activity() {
    private val ui = MainScope()
    private val app get() = application as NetSteerApp
    private lateinit var scanner: WifiScanner

    private lateinit var modeView: TextView
    private lateinit var modeNote: TextView
    private lateinit var speedView: TextView
    private lateinit var capabilityView: TextView
    private lateinit var power: Button
    private lateinit var linksBox: LinearLayout
    private lateinit var mobileStatus: TextView
    private lateinit var swMobile: Switch
    private lateinit var addedTitle: TextView
    private lateinit var addedBox: LinearLayout
    private lateinit var scanButton: Button
    private lateinit var scanNote: TextView
    private lateinit var nearbyBox: LinearLayout
    private var nearby: List<WifiScanner.Nearby> = emptyList()
    private var scanned = false
    private var binding = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanner = WifiScanner(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        root.addView(text("Internet Bonding", 26f, bold = true))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setStroke(dp(1), Color.argb(60, 128, 128, 128)) }
        }
        modeView = text("", 18f, bold = true).also(card::addView)
        modeNote = text("", 13f).apply { alpha = 0.8f }.also(card::addView)
        speedView = text("", 15f).apply { typeface = Typeface.MONOSPACE; setPadding(0, dp(8), 0, 0) }.also(card::addView)
        root.addView(card)

        power = Button(this).apply {
            textSize = 17f
            minHeight = dp(52)
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (CombineVpnService.status.value.running) {
                    startService(Intent(this@BondingActivity, CombineVpnService::class.java).setAction(CombineVpnService.ACTION_STOP))
                } else {
                    startBonding()
                }
            }
        }
        root.addView(power, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        capabilityView = note("").apply { setPadding(0, dp(10), 0, 0) }.also(root::addView)

        root.addView(section("Connections in use"))
        linksBox = vbox().also(root::addView)

        root.addView(section("Mobile data"))
        mobileStatus = text("", 14f).also(root::addView)
        swMobile = Switch(this).apply {
            text = "Use mobile data together with Wi-Fi"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, on -> if (!binding) setMobile(on) }
        }
        root.addView(swMobile)
        root.addView(note("Mobile data counts toward your plan and may cost money (especially when roaming). When this is off, mobile data is only used if no Wi-Fi works."))

        addedTitle = section("Added Wi-Fi connections").also(root::addView)
        root.addView(note("Choose up to ${WifiSelection.MAX} Wi-Fi networks. You enter each password and approve it; NetSteer never stores passwords."))
        addedBox = vbox().also(root::addView)

        root.addView(section("Nearby Wi-Fi"))
        scanButton = Button(this).apply {
            text = "Scan Wi-Fi"
            setOnClickListener { scanWifi() }
        }
        root.addView(scanButton)
        scanNote = note("").also(root::addView)
        nearbyBox = vbox().also(root::addView)

        root.addView(section("About bonding"))
        root.addView(note(
            "• Load balancing: each new connection goes to the network best able to carry it, so many parallel " +
                "connections (web pages, app stores, multi-thread downloads) use several networks at once.\n" +
                "• Failover: if a network fails, new connections move to the others automatically.\n" +
                "• True bonding (one download split across networks) needs a remote bonding server to reassemble the " +
                "traffic. NetSteer works entirely on your phone, so it doesn't do this.\n" +
                "• Android connects a phone to one internet Wi-Fi at a time (two on some Android 13+ phones). Extra " +
                "added networks are standby sources Android switches to.",
        ))

        setContentView(ScrollView(this).apply { addView(root) })

        app.wifiConnections.listenForFailures()
        ui.launch {
            combine(CombineVpnService.status, app.settings.values, app.wifiConnections.changes, app.monitor.networks) { s, set, _, _ -> s to set }
                .collect { (s, set) -> render(s, set) }
        }
    }

    override fun onDestroy() {
        ui.cancel()
        super.onDestroy()
    }

    @Deprecated("Framework activity result API; AndroidX is not used")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN && resultCode == RESULT_OK) {
            startForegroundService(Intent(this, CombineVpnService::class.java).setAction(CombineVpnService.ACTION_START))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_SCAN) {
            if (scanner.hasPermissions()) {
                app.wifiConnections.listenForFailures()
                scanWifi()
            } else {
                scanNote.text = "Wi-Fi scanning needs location and nearby-device access. You can still add networks by name."
                renderNearbyManual()
            }
        }
    }

    private fun startBonding() {
        val s = app.settings.current
        if (s.useMobile && !s.mobileConsent) {
            confirmMobile { startBonding() }
            return
        }
        val consent = VpnService.prepare(this)
        if (consent != null) startActivityForResult(consent, REQ_VPN)
        else startForegroundService(Intent(this, CombineVpnService::class.java).setAction(CombineVpnService.ACTION_START))
    }

    private fun setMobile(on: Boolean) {
        if (on && !app.settings.current.mobileConsent) {
            confirmMobile { app.settings.update { it.copy(useMobile = true) } }
            return
        }
        app.settings.update { it.copy(useMobile = on) }
    }

    private fun confirmMobile(then: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Use mobile data together with Wi-Fi?")
            .setMessage("Part of your traffic will go over mobile data even while Wi-Fi is connected. This counts toward your mobile plan and may cause carrier charges, especially when roaming.")
            .setPositiveButton("Use mobile data") { _, _ ->
                app.settings.update { it.copy(useMobile = true, mobileConsent = true) }
                then()
            }
            .setNegativeButton("Wi-Fi only") { _, _ ->
                app.settings.update { it.copy(useMobile = false, mobileConsent = true) }
                if (CombineVpnService.status.value.running.not()) startBonding()
            }
            .setOnCancelListener { render(CombineVpnService.status.value, app.settings.current) }
            .show()
    }

    private fun scanWifi() {
        if (!scanner.hasPermissions()) {
            requestPermissions(scanner.requiredPermissions(), REQ_SCAN)
            return
        }
        scanButton.isEnabled = false
        scanNote.text = "Scanning…"
        ui.launch {
            val r = scanner.scan()
            nearby = r.networks
            scanned = true
            scanNote.text = r.problem ?: if (r.networks.isEmpty()) "No Wi-Fi networks found nearby." else "${r.networks.size} networks found."
            scanButton.isEnabled = true
            render(CombineVpnService.status.value, app.settings.current)
        }
    }

    private fun render(s: CombineStatus, set: Settings.Values) {
        binding = true
        swMobile.isChecked = set.useMobile
        binding = false

        val mode = if (s.running) s.mode else ConnectionMode.OFF
        modeView.text = "Mode: ${mode.label}"
        modeNote.text = mode.explanation
        val active = s.links.filter { it.state == LinkState.ACTIVE }
        speedView.text = if (s.running) {
            val rx = active.sumOf { it.rxBps }
            val tx = active.sumOf { it.txBps }
            // Real bytes moved through NetSteer right now, not a sum of link capacities.
            String.format(Locale.US, "Measured now  ↓ %s  ↑ %s Mb/s\nover %d network(s) · %d connections", StatusNotifier.mbps(rx), StatusNotifier.mbps(tx), active.size, s.connections)
        } else {
            "Not running"
        }
        power.text = if (s.running) "Stop Bonding" else "Start Bonding"
        power.background = GradientDrawable().apply {
            cornerRadius = dp(26).toFloat()
            setColor(if (s.running) Color.rgb(198, 40, 40) else Color.rgb(25, 118, 210))
        }

        val wifiMgr = getSystemService(WifiManager::class.java)
        val multi = Build.VERSION.SDK_INT >= 33 && runCatching { wifiMgr?.isStaConcurrencyForMultiInternetSupported }.getOrNull() == true
        capabilityView.text = if (multi) {
            "This phone can use 2 Wi-Fi networks for internet at the same time (Android multi-internet). " +
                "Other added networks are standby sources."
        } else {
            "⚠ This phone can use only 1 Wi-Fi network for internet at a time (no dual-Wi-Fi hardware support). " +
                "Added networks are standby/failover sources, combined with mobile data where available."
        }

        renderLinks(s)
        val cell = (if (s.running) s.links else app.links.describe(emptySet(), false)).firstOrNull { LinkRegistry.isMobile(it.id) }
        mobileStatus.text = buildString {
            append(cell?.let { "${it.label}: ${it.detail}" } ?: "Mobile: not available")
            val used = app.usage.today("cell")
            if (used > 0) append("\nUsed through NetSteer today: ${UsageStore.human(used)}")
        }
        renderAdded()
        if (scanned) renderNearby() else if (nearbyBox.childCount == 0) renderNearbyManual()
    }

    private fun renderLinks(s: CombineStatus) {
        linksBox.removeAllViews()
        val list = if (s.running) s.links else emptyList()
        if (list.isEmpty()) {
            linksBox.addView(note("Start Bonding to see live speed and health per network."))
            return
        }
        for (l in list) {
            val health = listOfNotNull(l.rttMs?.let { "latency $it ms" }, l.lossPct?.let { "loss $it%" }).joinToString(", ")
            val head = if (l.state == LinkState.ACTIVE) {
                String.format(Locale.US, "● %-10s ↓%5s ↑%5s Mb/s", l.label, StatusNotifier.mbps(l.rxBps), StatusNotifier.mbps(l.txBps))
            } else {
                "○ ${l.label}"
            }
            linksBox.addView(text(head, 15f).apply { typeface = Typeface.MONOSPACE; setPadding(0, dp(6), 0, 0) })
            val sub = listOf(l.detail, health, if (l.usedTodayBytes > 0) "${UsageStore.human(l.usedTodayBytes)} today" else "")
                .filter { it.isNotEmpty() }.joinToString(" · ")
            linksBox.addView(note("   $sub"))
        }
    }

    private fun currentSsid(): String? = runCatching {
        @Suppress("DEPRECATION")
        getSystemService(WifiManager::class.java)?.connectionInfo?.ssid?.removeSurrounding("\"")?.takeIf { it != "<unknown ssid>" }
    }.getOrNull()

    private fun renderAdded() {
        val mgr = app.wifiConnections
        addedTitle.text = "Added Wi-Fi connections (${mgr.count}/${WifiSelection.MAX})"
        addedBox.removeAllViews()
        val wifiUsable = app.links.links().any { it.id == "wifi" && it.usable }
        val entries = mgr.statuses(currentSsid(), wifiUsable, nearby.map { it.ssid }.toSet(), rangeKnown = scanned)
        if (entries.isEmpty()) {
            addedBox.addView(note("None yet. Scan below and tap Add."))
            return
        }
        for (e in entries) {
            addedBox.addView(text("${e.ssid}  (${e.security.label})", 15f, bold = true).apply { setPadding(0, dp(8), 0, 0) })
            addedBox.addView(note(e.status.label))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            when (e.status) {
                WifiConnectionManager.Status.DISCONNECTED -> row.addView(small("Connect") { connect(e) })
                WifiConnectionManager.Status.WRONG_PASSWORD, WifiConnectionManager.Status.FAILED,
                WifiConnectionManager.Status.NEEDS_PASSWORD -> row.addView(small("Retry") { connect(e, askPassword = e.security.needsPassword) })
                WifiConnectionManager.Status.STANDBY, WifiConnectionManager.Status.NOT_IN_RANGE -> {
                    row.addView(small("Switch now") { openWifiPanel() })
                    row.addView(small("Disconnect") { app.wifiConnections.disconnect(e.ssid, e.security) })
                }
                else -> row.addView(small("Disconnect") { app.wifiConnections.disconnect(e.ssid, e.security) })
            }
            row.addView(small("Remove") { app.wifiConnections.remove(e.ssid, e.security) })
            addedBox.addView(row)
        }
    }

    private fun renderNearby() {
        nearbyBox.removeAllViews()
        val current = currentSsid()
        for (n in nearby) {
            val added = app.wifiConnections.entries().any { it.ssid == n.ssid && it.security == n.security }
            val state = when {
                n.ssid == current -> "Connected"
                added -> "Added"
                !n.security.supported -> "Not supported (${n.security.label})"
                app.wifiConnections.count >= WifiSelection.MAX -> "Limit of ${WifiSelection.MAX} reached"
                else -> "Available"
            }
            val bars = "▂▄▆█".take(WifiBands.bars(n.dBm)).padEnd(4, '·')
            nearbyBox.addView(text(n.ssid, 15f, bold = true).apply { setPadding(0, dp(8), 0, 0) })
            nearbyBox.addView(note("$bars ${n.dBm} dBm · ${WifiBands.bandOf(n.frequency)} · ${n.security.label} · $state"))
            if (state == "Available") nearbyBox.addView(small("Add") { addNetwork(n.ssid, n.security) })
        }
        renderNearbyManual(clear = false)
    }

    /** Hidden networks, or scanning not allowed: add by name. */
    private fun renderNearbyManual(clear: Boolean = true) {
        if (clear) nearbyBox.removeAllViews()
        nearbyBox.addView(small("Add a network by name…") { addByName() })
    }

    private fun addByName() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val ssid = EditText(this).apply { hint = "Network name (SSID)"; isSingleLine = true }
        val pass = EditText(this).apply { hint = "Password (leave empty if open)"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        box.addView(ssid); box.addView(pass)
        AlertDialog.Builder(this).setTitle("Add Wi-Fi network").setView(box)
            .setPositiveButton("Add") { _, _ ->
                val p = pass.text.toString()
                report(app.wifiConnections.add(ssid.text.toString(), if (p.isEmpty()) WifiSecurity.OPEN else WifiSecurity.WPA2_PSK, p.ifEmpty { null }))
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun addNetwork(ssid: String, security: WifiSecurity) {
        if (!security.needsPassword) {
            report(app.wifiConnections.add(ssid, security, null))
            return
        }
        askPassword(ssid) { p -> report(app.wifiConnections.add(ssid, security, p)) }
    }

    private fun connect(e: WifiConnectionManager.Entry, askPassword: Boolean = false) {
        if (askPassword) askPassword(e.ssid) { p -> report(app.wifiConnections.connect(e.ssid, e.security, p)) }
        else report(app.wifiConnections.connect(e.ssid, e.security))
    }

    private fun askPassword(ssid: String, then: (String) -> Unit) {
        val f = EditText(this).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(f, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }
        AlertDialog.Builder(this).setTitle("Password for $ssid").setView(box)
            .setPositiveButton("Connect") { _, _ -> then(f.text.toString()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun report(r: WifiConnectionManager.Result) {
        val msg = when (r) {
            WifiConnectionManager.Result.OK -> "Added. Android may ask you to allow NetSteer's suggested networks: tap Allow in its notification."
            WifiConnectionManager.Result.FULL -> "You can add at most ${WifiSelection.MAX} Wi-Fi networks. Remove one first."
            WifiConnectionManager.Result.DUPLICATE -> "That network is already added."
            WifiConnectionManager.Result.UNSUPPORTED -> "This network's security type (e.g. Enterprise or WEP) can't be added here."
            WifiConnectionManager.Result.INVALID -> "Enter a valid network name."
            WifiConnectionManager.Result.BAD_PASSWORD -> "Enter the network's password (WPA2: 8–63 characters, WPA3: 8–128)."
            WifiConnectionManager.Result.NOT_ALLOWED -> "Android didn't allow NetSteer to suggest networks. Check Settings › Apps › NetSteer › Wi-Fi control."
            WifiConnectionManager.Result.ERROR -> "Android refused the network. Try again."
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun openWifiPanel() {
        // Apps can't switch the main Wi-Fi themselves on Android 10+; let the user pick it.
        runCatching { startActivity(Intent(AndroidSettings.Panel.ACTION_WIFI)) }
    }

    private fun section(t: String) = text(t, 19f, bold = true).apply { setPadding(0, dp(24), 0, dp(4)) }
    private fun note(t: String) = text(t, 13f).apply { alpha = 0.75f }
    private fun vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun small(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun text(s: String, sp: Float, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQ_VPN = 20
        const val REQ_SCAN = 21
    }
}
