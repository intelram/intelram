package com.intelram.netsteer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.intelram.netsteer.core.DiagnosisClassifier
import com.intelram.netsteer.core.DomainFilter
import com.intelram.netsteer.core.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Framework-Views UI (no AndroidX). */
class MainActivity : Activity() {
    private val ui = MainScope()
    private val app get() = application as NetSteerApp

    private lateinit var stateView: TextView
    private lateinit var speedView: TextView
    private lateinit var linksView: TextView
    private lateinit var adsView: TextView
    private lateinit var hintView: TextView
    private lateinit var power: Button
    private lateinit var swCombine: Switch
    private lateinit var swSmart: Switch
    private lateinit var swCompat: Switch
    private lateinit var swAds: Switch
    private lateinit var swAdGuard: Switch
    private lateinit var swHosts: Switch
    private lateinit var filterView: TextView
    private lateinit var topView: TextView
    private lateinit var allowBox: LinearLayout
    private lateinit var diagView: TextView
    private var binding = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        root.addView(text("NetSteer", 28f, bold = true))
        root.addView(text("Faster internet by using Wi-Fi and mobile data together, and no ads.", 15f).apply {
            setPadding(0, dp(4), 0, dp(16))
        })

        // Status card
        val card = vbox(dp(16)).apply { background = outline() }
        stateView = text("Off", 16f, bold = true).also(card::addView)
        speedView = text("—", 40f, bold = true).also(card::addView)
        linksView = text("", 15f).apply { typeface = Typeface.MONOSPACE }.also(card::addView)
        adsView = text("", 15f).apply { setPadding(0, dp(8), 0, 0) }.also(card::addView)
        root.addView(card)
        hintView = text("", 14f).apply { setPadding(0, dp(8), 0, 0) }.also(root::addView)

        power = Button(this).apply {
            textSize = 18f
            minHeight = dp(56)
            setTextColor(Color.WHITE)
            setOnClickListener { if (CombineVpnService.status.value.running) stopVpn() else requestStart() }
        }
        root.addView(power, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })

        // Speed
        root.addView(section("Speed"))
        swCombine = switch("Use Wi-Fi and mobile data together") { v -> app.settings.update { it.copy(combine = v) } }.also(root::addView)
        swSmart = switch("Battery saver: add mobile data only during big downloads") { v -> app.settings.update { it.copy(smart = v) } }.also(root::addView)
        root.addView(note("Saves battery and mobile data. Turn off to always use both networks for maximum speed."))
        swCompat = switch("Compatibility mode: keep each website on one network") { v -> app.settings.update { it.copy(compat = v) } }.also(root::addView)
        root.addView(note("Use this if a website keeps logging you out."))

        // Ad blocking
        root.addView(section("Ad blocking"))
        swAds = switch("Block ads and trackers") { v -> app.settings.update { it.copy(adBlock = v) } }.also(root::addView)
        swAdGuard = switch("AdGuard DNS filter") { v -> app.settings.update { it.copy(listAdGuard = v) } }.also(root::addView)
        swHosts = switch("Steven Black unified hosts") { v -> app.settings.update { it.copy(listStevenBlack = v) } }.also(root::addView)
        filterView = text("", 14f).apply { setPadding(0, dp(4), 0, dp(4)) }.also(root::addView)
        root.addView(Button(this).apply {
            text = "Update filters now"
            setOnClickListener { updateFilters() }
        })
        root.addView(text("Most blocked", 15f, bold = true).apply { setPadding(0, dp(12), 0, dp(4)) })
        topView = text("", 14f).apply { typeface = Typeface.MONOSPACE }.also(root::addView)

        root.addView(text("Never block (allowlist)", 15f, bold = true).apply { setPadding(0, dp(12), 0, dp(4)) })
        root.addView(note("If an app or website breaks, add its domain here, e.g. example.com"))
        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val domainField = EditText(this).apply {
            hint = "example.com"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
        }
        addRow.addView(domainField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addRow.addView(Button(this).apply {
            text = "Allow"
            setOnClickListener {
                val d = DomainFilter.cleanDomain(domainField.text.toString().trim().removePrefix("https://").removePrefix("http://").substringBefore('/'))
                if (d == null) {
                    domainField.error = "Enter a domain like example.com"
                } else {
                    app.settings.update { it.copy(allowlist = it.allowlist + d) }
                    domainField.setText("")
                }
            }
        })
        root.addView(addRow)
        allowBox = vbox(0).also(root::addView)

        // Help
        root.addView(section("Good to know"))
        root.addView(note(
            "• Start automatically after a restart: Android Settings › Network › VPN › NetSteer › Always-on VPN.\n" +
                "• Faster with both networks: web pages, app stores, multi-thread download managers. A single " +
                "download stream uses one network.\n" +
                "• Ads inside some apps (e.g. YouTube video ads) are served from the same servers as the content " +
                "and can't be blocked without breaking the app.",
        ))
        root.addView(Button(this).apply {
            text = "Battery settings (if your phone stops NetSteer)"
            setOnClickListener {
                runCatching { startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
        })
        root.addView(Button(this).apply {
            text = "Diagnose networks"
            setOnClickListener { runDiagnostics() }
        })
        diagView = text("", 14f).apply { setPadding(0, dp(8), 0, 0) }.also(root::addView)

        setContentView(ScrollView(this).apply { addView(root) })

        ui.launch {
            combine(CombineVpnService.status, app.monitor.networks, app.settings.values, app.filters.state) { s, nets, set, fs ->
                Quad(s, nets, set, fs)
            }.collect { (s, nets, set, fs) -> render(s, nets, set, fs) }
        }
        ui.launch {
            // Blocked-domain list changes constantly; refresh it gently while visible.
            while (isActive) {
                renderTop()
                delay(3_000)
            }
        }
        handleAction(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAction(intent)
    }

    override fun onDestroy() {
        ui.cancel()
        super.onDestroy()
    }

    @Deprecated("Activity result API from the framework; AndroidX is not used")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) launchService() else hintView.text = "NetSteer needs the VPN permission to work."
        }
    }

    /**
     * Debug builds only: `am start -n com.intelram.netsteer/.MainActivity -a com.intelram.netsteer.START
     * --ez combine true --ez smart false --ez adblock true` drives the app for the emulator test.
     * Release builds ignore it so other apps cannot toggle the VPN.
     */
    private fun handleAction(intent: Intent?) {
        if (!app.debuggable || intent == null) return
        val ex = intent.extras
        if (ex != null) {
            app.settings.update { v ->
                v.copy(
                    combine = ex.getBoolean("combine", v.combine),
                    smart = ex.getBoolean("smart", v.smart),
                    adBlock = ex.getBoolean("adblock", v.adBlock),
                )
            }
        }
        when (intent.action) {
            CombineVpnService.ACTION_START -> requestStart()
            CombineVpnService.ACTION_STOP -> stopVpn()
            ACTION_UPDATE_FILTERS -> updateFilters()
        }
    }

    private fun requestStart() {
        val consent = VpnService.prepare(this)
        if (consent != null) startActivityForResult(consent, REQ_VPN) else launchService()
    }

    private fun launchService() {
        startForegroundService(Intent(this, CombineVpnService::class.java).setAction(CombineVpnService.ACTION_START))
    }

    private fun stopVpn() {
        startService(Intent(this, CombineVpnService::class.java).setAction(CombineVpnService.ACTION_STOP))
    }

    private fun updateFilters() {
        ui.launch {
            val nets = app.monitor.networks.value.values.filter { it.toSnapshot().validated }
            val net = (nets.firstOrNull { it.transport == Transport.WIFI } ?: nets.firstOrNull())?.network
            withContext(Dispatchers.IO) { app.filters.update(net, force = true) }
        }
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    private fun render(s: CombineStatus, nets: Map<Long, NetworkState>, set: Settings.Values, fs: FilterManager.State) {
        binding = true
        swCombine.isChecked = set.combine
        swSmart.isChecked = set.smart
        swSmart.isEnabled = set.combine
        swCompat.isChecked = set.compat
        swAds.isChecked = set.adBlock
        swAdGuard.isChecked = set.listAdGuard
        swHosts.isChecked = set.listStevenBlack
        swAdGuard.isEnabled = set.adBlock
        swHosts.isEnabled = set.adBlock
        binding = false

        power.text = if (s.running) "Turn off" else "Turn on"
        power.background = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setColor(if (s.running) Color.rgb(198, 40, 40) else Color.rgb(25, 118, 210))
        }

        val hints = mutableListOf<String>()
        if (!s.running) {
            stateView.text = "Off"
            speedView.text = "—"
            linksView.text = nets.values.joinToString("\n") { n ->
                "${label(n)}: ${if (n.toSnapshot().validated) "connected" else "no internet"}"
            }.ifEmpty { "No Wi-Fi or mobile network connected" }
            s.message?.let(hints::add)
        } else {
            val up = s.links.filter { it.up }
            stateView.text = when {
                up.isEmpty() -> "Waiting for a network…"
                s.mobileActive && up.size == 2 -> "Combining Wi-Fi + Mobile"
                set.combine && set.smart && up.any { it.label == "Wi-Fi" } -> "On · Wi-Fi (mobile joins for big downloads)"
                else -> "On · ${up.joinToString(" + ") { it.label }}"
            }
            speedView.text = "${StatusNotifier.mbps(s.totalBps)} Mb/s"
            linksView.text = s.links.joinToString("\n") { l ->
                if (!l.up) String.format(Locale.US, "%-7s standby", l.label)
                else String.format(Locale.US, "%-7s ↓%5s ↑%5s  %d conn", l.label, StatusNotifier.mbps(l.rxBps), StatusNotifier.mbps(l.txBps), l.connections)
            }
            if (set.combine && !set.smart && s.links.none { it.label == "Mobile" && it.up }) {
                hints += "Mobile data is off or has no signal. Turn on Mobile data to combine."
            }
        }
        val strictDns = nets.values.mapNotNull { it.link?.privateDnsServerName }.firstOrNull()
        if (set.adBlock && strictDns != null) {
            hints += "Private DNS is set to \"$strictDns\". Ads are still blocked for most connections, " +
                "but for best results set Private DNS to Automatic in Android Settings."
        }
        hintView.text = hints.joinToString("\n\n")

        adsView.text = if (!set.adBlock) "Ad blocking is off"
        else String.format(Locale.US, "%,d ads & trackers blocked today · %,d total", app.filters.blockedToday.get(), app.filters.blockedTotal.get())

        val lists = buildList {
            if (set.listAdGuard) add("AdGuard ${if (FilterManager.ADGUARD in fs.downloaded) "✓" else "(not downloaded yet)"}")
            if (set.listStevenBlack) add("Steven Black ${if (FilterManager.STEVEN_BLACK in fs.downloaded) "✓" else "(not downloaded yet)"}")
        }.joinToString(" · ").ifEmpty { "Built-in list only" }
        val updated = if (fs.updatedAt > 0) "updated ${DateUtils.getRelativeTimeSpanString(fs.updatedAt)}" else "built-in list"
        filterView.text = when {
            fs.updating -> "Downloading filter lists…"
            else -> String.format(Locale.US, "%s\n%,d rules · %s", lists, fs.rules, updated) + (fs.error?.let { "\n⚠ $it" } ?: "")
        }

        allowBox.removeAllViews()
        set.allowlist.sorted().forEach { d ->
            allowBox.addView(TextView(this).apply {
                text = "✕  $d"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(0, dp(6), 0, dp(6))
                contentDescription = "Remove $d from allowlist"
                setOnClickListener { app.settings.update { it.copy(allowlist = it.allowlist - d) } }
            })
        }
    }

    private fun renderTop() {
        val top = app.filters.topBlocked(5)
        topView.text = if (top.isEmpty()) "Nothing blocked yet in this session"
        else top.joinToString("\n") { (d, n) -> String.format(Locale.US, "%5d  %s", n, d) }
        if (::adsView.isInitialized && app.settings.current.adBlock) {
            adsView.text = String.format(Locale.US, "%,d ads & trackers blocked today · %,d total", app.filters.blockedToday.get(), app.filters.blockedTotal.get())
        }
    }

    private fun label(n: NetworkState) = when (n.transport) {
        Transport.WIFI -> "Wi-Fi"
        Transport.CELLULAR -> "Mobile"
        else -> n.transport.name
    }

    private fun runDiagnostics() = ui.launch {
        diagView.text = "Diagnosing…"
        val states = app.monitor.networks.value.values.toList()
        if (states.isEmpty()) {
            diagView.text = "No Wi-Fi or mobile network is connected."
            return@launch
        }
        val reports = states.map { s ->
            val d = DiagnosisClassifier.classify(app.prober.probe(s))
            "${label(s)}: ${d.verdict}\n${d.detail}\nFix: ${d.remedy}"
        }
        diagView.text = reports.joinToString("\n\n")
    }

    private fun switch(label: String, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(0, dp(10), 0, dp(10))
        setOnCheckedChangeListener { _, v -> if (!binding) onChange(v) }
    }

    private fun section(title: String) = text(title, 20f, bold = true).apply { setPadding(0, dp(28), 0, dp(4)) }
    private fun note(s: String) = text(s, 13f).apply { alpha = 0.75f; setPadding(0, 0, 0, dp(6)) }

    private fun vbox(pad: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad, pad, pad)
    }

    private fun outline() = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setStroke(dp(1), Color.argb(60, 128, 128, 128))
    }

    private fun text(s: String, sp: Float, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQ_VPN = 10
        const val REQ_NOTIF = 11
        const val ACTION_UPDATE_FILTERS = "com.intelram.netsteer.UPDATE_FILTERS"
    }
}
