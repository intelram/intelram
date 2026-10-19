package com.intelram.netsteer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.intelram.netsteer.core.DiagnosisClassifier
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Framework-Views UI (no AndroidX): one big Start/Stop button and live per-link speeds. */
class MainActivity : Activity() {
    private val ui = MainScope()
    private val app get() = application as NetSteerApp

    private lateinit var stateView: TextView
    private lateinit var speedView: TextView
    private lateinit var linksView: TextView
    private lateinit var hintView: TextView
    private lateinit var toggle: Button
    private lateinit var diagView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        root.addView(text("NetSteer", 28f, bold = true))
        root.addView(text("Uses Wi-Fi and mobile data at the same time by spreading your connections across both.", 15f).apply {
            setPadding(0, dp(4), 0, dp(16))
        })

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.argb(60, 128, 128, 128))
            }
        }
        stateView = text("Off", 16f, bold = true).also(card::addView)
        speedView = text("—", 40f, bold = true).also(card::addView)
        linksView = text("", 15f).apply { typeface = Typeface.MONOSPACE }.also(card::addView)
        root.addView(card)

        hintView = text("", 14f).apply { setPadding(0, dp(8), 0, 0) }.also(root::addView)

        toggle = Button(this).apply {
            textSize = 18f
            minHeight = dp(56)
            setTextColor(Color.WHITE)
            setOnClickListener { if (CombineVpnService.status.value.running) stopCombining() else requestStart() }
        }
        root.addView(toggle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })

        root.addView(CheckBox(this).apply {
            text = "Compatibility mode: keep each website on one network (use if a site logs you out)"
            isChecked = app.compatibilityMode
            setOnCheckedChangeListener { _, on -> app.compatibilityMode = on }
            setPadding(dp(4), dp(16), 0, dp(8))
        })

        root.addView(text(
            "What gets faster: web pages, app stores, multi-thread download managers and multi-connection speed tests. " +
                "A single download that uses one connection gets the speed of one network. " +
                "Uses your mobile data; the key icon in the status bar means it is active.",
            13f,
        ).apply { alpha = 0.75f; setPadding(0, dp(8), 0, dp(16)) })

        root.addView(Button(this).apply {
            text = "Diagnose networks"
            setOnClickListener { runDiagnostics() }
        })
        diagView = text("", 14f).apply { setPadding(0, dp(8), 0, 0) }.also(root::addView)

        setContentView(ScrollView(this).apply { addView(root) })

        ui.launch {
            combine(CombineVpnService.status, app.monitor.networks) { s, nets -> s to nets }.collect { (s, nets) ->
                render(s, nets)
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
            if (resultCode == RESULT_OK) launchService() else hintView.text = "NetSteer needs the VPN permission to combine networks."
        }
    }

    /**
     * Debug builds only: lets `am start -n com.intelram.netsteer/.MainActivity -a com.intelram.netsteer.START`
     * drive the app for the emulator test. Release builds ignore it so other apps cannot toggle the VPN.
     */
    private fun handleAction(intent: Intent?) {
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        when (intent?.action) {
            CombineVpnService.ACTION_START -> requestStart()
            CombineVpnService.ACTION_STOP -> stopCombining()
        }
    }

    private fun requestStart() {
        val consent = VpnService.prepare(this)
        if (consent != null) startActivityForResult(consent, REQ_VPN) else launchService()
    }

    private fun launchService() {
        startForegroundService(Intent(this, CombineVpnService::class.java).setAction(CombineVpnService.ACTION_START))
    }

    private fun stopCombining() {
        startService(Intent(this, CombineVpnService::class.java).setAction(CombineVpnService.ACTION_STOP))
    }

    private fun render(s: CombineStatus, nets: Map<Long, NetworkState>) {
        toggle.text = if (s.running) "Stop" else "Start combining"
        toggle.background = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setColor(if (s.running) Color.rgb(198, 40, 40) else Color.rgb(25, 118, 210))
        }
        if (!s.running) {
            stateView.text = "Off"
            speedView.text = "—"
            linksView.text = nets.values.joinToString("\n") { n ->
                val snap = n.toSnapshot()
                "${label(n)}: ${if (snap.validated) "connected" else "no internet"}"
            }.ifEmpty { "No Wi-Fi or mobile network connected" }
            hintView.text = s.message ?: ""
            return
        }
        val up = s.links.filter { it.up }
        stateView.text = when (up.size) {
            0 -> "Waiting for a network…"
            1 -> "Using ${up[0].label} only"
            else -> "Combining ${up.joinToString(" + ") { it.label }}"
        }
        speedView.text = "${StatusNotifier.mbps(s.totalBps)} Mb/s"
        linksView.text = s.links.joinToString("\n") { l ->
            if (!l.up) "%-7s off".format(l.label)
            else "%-7s ↓%5s ↑%5s  %d conn".format(l.label, StatusNotifier.mbps(l.rxBps), StatusNotifier.mbps(l.txBps), l.connections)
        }
        hintView.text = when {
            up.size == 2 -> ""
            s.links.none { it.label == "Mobile" && it.up } -> "Mobile data is off or has no internet. Turn on Mobile data to combine."
            else -> "Wi-Fi is off or has no internet. Connect to Wi-Fi to combine."
        }
    }

    private fun label(n: NetworkState) = when (n.transport) {
        com.intelram.netsteer.core.Transport.WIFI -> "Wi-Fi"
        com.intelram.netsteer.core.Transport.CELLULAR -> "Mobile"
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

    private fun text(s: String, sp: Float, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        gravity = Gravity.START
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQ_VPN = 10
        const val REQ_NOTIF = 11
    }
}
