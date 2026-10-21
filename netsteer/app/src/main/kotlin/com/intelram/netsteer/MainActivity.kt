package com.intelram.netsteer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
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
import android.util.Log
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
import java.security.SecureRandom
import java.util.Locale

/** Framework-Views UI (no AndroidX). */
class MainActivity : Activity() {
    private val ui = MainScope()
    private val app get() = application as NetSteerApp

    private lateinit var stateView: TextView
    private lateinit var speedView: TextView
    private lateinit var linksBox: LinearLayout
    private lateinit var adsView: TextView
    private lateinit var hintView: TextView
    private lateinit var power: Button
    private lateinit var swCombine: Switch
    private lateinit var swSmart: Switch
    private lateinit var swDual: Switch
    private lateinit var dualNote: TextView
    private lateinit var swCompat: Switch
    private lateinit var swAds: Switch
    private lateinit var swAdGuard: Switch
    private lateinit var swHosts: Switch
    private lateinit var filterView: TextView
    private lateinit var topView: TextView
    private lateinit var allowBox: LinearLayout
    private lateinit var excludedView: TextView
    private lateinit var speedResult: TextView
    private lateinit var speedButton: Button
    private lateinit var diagView: TextView
    private var binding = false
    private var lastLinksKey = ""

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
        root.addView(text("Faster internet by using all your networks together, and no ads.", 15f).apply {
            setPadding(0, dp(4), 0, dp(16))
        })

        // Status card
        val card = vbox(dp(16)).apply { background = outline() }
        stateView = text("Off", 16f, bold = true).also(card::addView)
        speedView = text("—", 40f, bold = true).also(card::addView)
        linksBox = vbox(0).also(card::addView)
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
        swCombine = switch("Use all networks together (Wi-Fi + mobile + others)") { v -> app.settings.update { it.copy(combine = v) } }.also(root::addView)
        swSmart = switch("Battery saver") { v -> app.settings.update { it.copy(smart = v) } }.also(root::addView)
        root.addView(note("Off (recommended for speed): mobile data is always used together with Wi-Fi. On: mobile joins only during big downloads, saving battery and data."))
        swDual = switch("Dual Wi-Fi: connect a second Wi-Fi network") { v -> app.settings.update { it.copy(dualWifi = v) } }.also(root::addView)
        dualNote = note("").also(root::addView)
        swCompat = switch("Compatibility mode: keep each website on one network") { v -> app.settings.update { it.copy(compat = v) } }.also(root::addView)
        root.addView(note("Use this if a website keeps logging you out."))
        speedButton = Button(this).apply {
            text = "Run speed test"
            setOnClickListener { runSpeedTest() }
        }
        root.addView(speedButton)
        speedResult = text("", 14f).apply { typeface = Typeface.MONOSPACE; setPadding(0, dp(4), 0, 0) }.also(root::addView)

        // Apps
        root.addView(section("Apps"))
        excludedView = note("").also(root::addView)
        root.addView(Button(this).apply {
            text = "Choose apps that bypass NetSteer"
            setOnClickListener { pickExcludedApps() }
        })

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
            "• Quick on/off: add the NetSteer tile to Quick Settings (pull down the status bar › edit).\n" +
                "• Start automatically after a restart: Android Settings › Network › VPN › NetSteer › Always-on VPN.\n" +
                "• More links: a USB Ethernet adapter or Bluetooth tethering from another phone is used automatically.\n" +
                "• Faster with several networks: web pages, app stores, multi-thread download managers, multi-connection " +
                "speed tests. A single download stream uses one network.\n" +
                "• Ads served from the same servers as the content (e.g. YouTube video ads) can't be blocked without breaking the app.",
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
            }.collect { (s, nets, set, fs) -> render(s, set, fs) }
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
     * The Quick Settings tile's "turn on" (authenticated by a per-install token), plus, in debug
     * builds only, START/STOP/UPDATE_FILTERS/SPEEDTEST and settings extras for the emulator test.
     */
    private fun handleAction(intent: Intent?) {
        if (intent == null) return
        if (intent.action == ACTION_TILE_START && intent.getStringExtra(EXTRA_TOKEN) == tileToken(this)) {
            requestStart()
            return
        }
        if (!app.debuggable) return
        val ex = intent.extras
        if (ex != null) {
            app.settings.update { v ->
                v.copy(
                    combine = ex.getBoolean("combine", v.combine),
                    smart = ex.getBoolean("smart", v.smart),
                    adBlock = ex.getBoolean("adblock", v.adBlock),
                    excludedApps = ex.getString("exclude")?.let { s -> s.split(',').filter { it.isNotBlank() }.toSet() } ?: v.excludedApps,
                )
            }
        }
        when (intent.action) {
            CombineVpnService.ACTION_START -> requestStart()
            CombineVpnService.ACTION_STOP -> stopVpn()
            ACTION_UPDATE_FILTERS -> updateFilters()
            ACTION_SPEEDTEST -> runSpeedTest()
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
            val ups = app.links.uplinks()
            val net = (ups.firstOrNull { !LinkRegistry.isMobile(it.id) } ?: ups.firstOrNull())?.network
            withContext(Dispatchers.IO) { app.filters.update(net, force = true) }
        }
    }

    private fun runSpeedTest() {
        val links = app.links.uplinks()
        if (links.isEmpty()) {
            speedResult.text = "No working network to test."
            return
        }
        speedButton.isEnabled = false
        ui.launch {
            try {
                val r = SpeedTest().run(links) { msg -> ui.launch { speedResult.text = msg } }
                val lines = r.perLink.map { (id, bps) ->
                    String.format(Locale.US, "%-11s %7s Mb/s", LinkRegistry.labelFor(id), StatusNotifier.mbps(bps))
                }.toMutableList()
                if (r.perLink.size > 1) {
                    val best = r.perLink.maxOf { it.second }
                    val gain = if (best > 0) (r.combinedBps / best - 1) * 100 else 0.0
                    lines += String.format(Locale.US, "%-11s %7s Mb/s  (%+.0f%% vs best single)", "Together", StatusNotifier.mbps(r.combinedBps), gain)
                }
                lines += "Data used by the test: ${UsageStore.human(r.bytesUsed)}"
                speedResult.text = lines.joinToString("\n")
                Log.i("NetSteer", "speedtest " + r.perLink.joinToString(" ") { "${it.first}=${it.second.toLong()}" } + " combined=${r.combinedBps.toLong()}")
            } catch (e: Exception) {
                speedResult.text = "Speed test failed: ${e.message}"
            } finally {
                speedButton.isEnabled = true
            }
        }
    }

    private fun pickExcludedApps() {
        ui.launch {
            val pm = packageManager
            val apps = withContext(Dispatchers.IO) {
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                    .filter { it.first != packageName }
                    .distinctBy { it.first }
                    .sortedBy { it.second.lowercase() }
            }
            val current = app.settings.current.excludedApps
            val checked = BooleanArray(apps.size) { apps[it].first in current }
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Apps that bypass NetSteer")
                .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
                .setPositiveButton("Save") { _, _ ->
                    val chosen = apps.filterIndexed { i, _ -> checked[i] }.map { it.first }.toSet()
                    app.settings.update { it.copy(excludedApps = chosen) } // the VPN reloads itself
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    private fun render(s: CombineStatus, set: Settings.Values, fs: FilterManager.State) {
        binding = true
        swCombine.isChecked = set.combine
        swSmart.isChecked = set.smart
        swSmart.isEnabled = set.combine
        swDual.isChecked = set.dualWifi
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

        val links = if (s.running) s.links else app.links.describe(app.links.uplinks().map { it.id }.toSet(), mobileHeld = false, mobileWithheld = false)
            .map { it.copy(state = if (it.state == LinkState.ACTIVE) LinkState.READY else it.state, usedTodayBytes = app.usage.today(it.id)) }
        val active = links.filter { it.state == LinkState.ACTIVE }
        if (!s.running) {
            stateView.text = "Off"
            speedView.text = "—"
        } else {
            stateView.text = when {
                active.isEmpty() -> "Waiting for a network…"
                active.size >= 2 -> "Combining ${active.size} networks"
                set.combine && set.smart -> "On · ${active[0].label} (others join for big downloads)"
                else -> "On · ${active[0].label}"
            }
            speedView.text = "${StatusNotifier.mbps(s.totalBps)} Mb/s"
        }
        renderLinks(links, s.running)

        dualNote.text = when (s.dualWifi) {
            DualWifi.UNSUPPORTED -> if (s.running) "This phone's Wi-Fi chip can only join one Wi-Fi network at a time (needs Android 13+ dual-Wi-Fi hardware)." else "Checked when NetSteer is on."
            DualWifi.OFF -> "Off."
            DualWifi.SEARCHING -> "Looking for a second Wi-Fi on the other band or another saved network…"
            DualWifi.CONNECTED -> "Second Wi-Fi connected."
        }
        swDual.isEnabled = !(s.running && s.dualWifi == DualWifi.UNSUPPORTED)

        val hints = mutableListOf<String>()
        if (!s.running) s.message?.let(hints::add)
        val strictDns = app.monitor.networks.value.values.mapNotNull { it.link?.privateDnsServerName }.firstOrNull()
        if (set.adBlock && strictDns != null) {
            hints += "Private DNS is set to \"$strictDns\". Ads are still blocked for most connections, " +
                "but for best results set Private DNS to Automatic in Android Settings."
        }
        hintView.text = hints.joinToString("\n\n")

        adsView.text = if (!set.adBlock) "Ad blocking is off"
        else String.format(Locale.US, "%,d ads & trackers blocked today · %,d total", app.filters.blockedToday.get(), app.filters.blockedTotal.get())

        excludedView.text = if (set.excludedApps.isEmpty()) "All apps use NetSteer."
        else "${set.excludedApps.size} app(s) bypass NetSteer (no combining, no ad blocking for them)."

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

    private fun renderLinks(links: List<LinkStatus>, running: Boolean) {
        val key = links.joinToString("|") { "${it.id}${it.state}${it.detail}${StatusNotifier.mbps(it.rxBps)}${StatusNotifier.mbps(it.txBps)}${it.connections}${it.usedTodayBytes shr 20}" }
        if (key == lastLinksKey) return
        lastLinksKey = key
        linksBox.removeAllViews()
        for (l in links) {
            val dot = when (l.state) {
                LinkState.ACTIVE -> "🟢"
                LinkState.READY -> "🔵"
                LinkState.CONNECTING -> "🟡"
                LinkState.NO_INTERNET, LinkState.BLOCKED -> "🟠"
                LinkState.OFF -> "⚪"
            }
            val head = if (running && l.state == LinkState.ACTIVE) {
                String.format(Locale.US, "%s %-10s ↓%5s ↑%5s  %d conn", dot, l.label, StatusNotifier.mbps(l.rxBps), StatusNotifier.mbps(l.txBps), l.connections)
            } else {
                "$dot ${l.label}"
            }
            linksBox.addView(text(head, 15f).apply { typeface = Typeface.MONOSPACE; setPadding(0, dp(6), 0, 0) })
            val sub = buildString {
                append(l.detail)
                if (l.usedTodayBytes > 0) append(" · ${UsageStore.human(l.usedTodayBytes)} today")
            }
            linksBox.addView(text("    $sub", 13f).apply { alpha = 0.75f })
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
        Transport.ETHERNET -> "Ethernet"
        else -> n.transport.name
    }

    private fun runDiagnostics() = ui.launch {
        diagView.text = "Diagnosing…"
        val states = app.monitor.networks.value.values.toList()
        if (states.isEmpty()) {
            diagView.text = "No network is connected."
            return@launch
        }
        val reports = states.map { s ->
            val d = DiagnosisClassifier.classify(app.prober.probe(s))
            "${label(s)}: ${d.verdict}\n${d.detail}\nFix: ${d.remedy}"
        }
        val linkReport = app.links.describe(app.links.uplinks().map { it.id }.toSet(), app.monitor.mobileRequested, false)
            .joinToString("\n") { "${it.label}: ${it.state} – ${it.detail}" }
        diagView.text = reports.joinToString("\n\n") + "\n\nLinks:\n" + linkReport
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

    companion object {
        private const val REQ_VPN = 10
        private const val REQ_NOTIF = 11
        private const val ACTION_UPDATE_FILTERS = "com.intelram.netsteer.UPDATE_FILTERS"
        private const val ACTION_SPEEDTEST = "com.intelram.netsteer.SPEEDTEST"
        const val ACTION_TILE_START = "com.intelram.netsteer.TILE_START"
        const val EXTRA_TOKEN = "token"

        /** Random per-install secret proving a TILE_START intent came from our own tile. */
        fun tileToken(ctx: Context): String {
            val p = ctx.getSharedPreferences("netsteer", Context.MODE_PRIVATE)
            p.getString("tile_token", null)?.let { return it }
            val t = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            p.edit().putString("tile_token", t).apply()
            return t
        }
    }
}
