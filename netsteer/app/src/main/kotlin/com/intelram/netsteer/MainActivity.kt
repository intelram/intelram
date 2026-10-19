package com.intelram.netsteer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import com.intelram.netsteer.core.DiagnosisClassifier
import com.intelram.netsteer.core.Preference
import com.intelram.netsteer.core.Transport
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File
import java.net.URL

/**
 * Minimal View-based UI (no AndroidX) so the whole app depends only on the
 * framework and kotlinx-coroutines.
 */
class MainActivity : Activity() {
    private val ui = MainScope()
    private val app get() = application as NetSteerApp

    private lateinit var networksView: TextView
    private lateinit var diagView: TextView
    private lateinit var logView: TextView
    private lateinit var serviceButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        root.addView(text("Failover & network management. This is NOT bandwidth bonding: " +
            "one network carries this app's traffic at a time. Other apps are unaffected.", bold = true))

        root.addView(text("Preferred network for this app's traffic"))
        val group = RadioGroup(this)
        Preference.entries.forEach { p ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId(); text = p.name; tag = p
                isChecked = p == app.controller.preference
            })
        }
        group.setOnCheckedChangeListener { g, id ->
            (g.findViewById<View>(id)?.tag as? Preference)?.let { app.controller.preference = it }
        }
        root.addView(group)

        root.addView(CheckBox(this).apply {
            text = "Hot standby: keep cellular up while on Wi-Fi (faster failover, more battery)"
            setOnCheckedChangeListener { _, on -> app.monitor.start(hotStandby = on) }
        })

        serviceButton = button("") { toggleService() }.also(root::addView)
        root.addView(button("Run diagnostics on all networks") { runDiagnostics() })

        val urlField = EditText(this).apply { hint = "https://… large file on a Range-capable server" }
        root.addView(urlField)
        root.addView(button("Aggregated download test (this app only)") { aggregatedDownload(urlField.text.toString()) })

        networksView = text("").also(root::addView)
        diagView = text("").also(root::addView)
        root.addView(text("Event log", bold = true))
        logView = text("").apply { typeface = Typeface.MONOSPACE }.also(root::addView)

        setContentView(ScrollView(this).apply { addView(root) })
        refreshServiceButton()

        ui.launch {
            combine(app.monitor.networks, app.monitor.systemDefault) { nets, def -> nets to def }
                .collect { (nets, def) -> networksView.text = describe(nets, def) }
        }
        ui.launch { app.controller.log.collect { logView.text = it.joinToString("\n") } }
    }

    override fun onDestroy() {
        ui.cancel()
        super.onDestroy()
    }

    private fun describe(nets: Map<Long, NetworkState>, def: Long?): String = buildString {
        appendLine("Networks (system default = ${nets[def]?.transport ?: "none"}, app bound to = ${nets[app.selector.boundId]?.transport ?: "system default"})")
        if (nets.isEmpty()) appendLine("  none")
        nets.values.forEach { s ->
            val n = s.toSnapshot()
            appendLine("• ${n.transport} validated=${n.validated} metered=${n.metered} portal=${n.captivePortal} blocked=${n.blocked}")
            appendLine("   est ↓${n.downKbps} kbps ↑${n.upKbps} kbps  dns=${s.link?.dnsServers?.joinToString { it.hostAddress ?: "?" }}")
            n.probe?.let { appendLine("   probe rtt=${it.rttMs ?: "-"} ms loss=${"%.0f".format(it.lossRatio * 100)}%") }
        }
    }

    private fun toggleService() {
        val intent = Intent(this, MonitorService::class.java)
        if (app.controller.isRunning) stopService(intent) else startForegroundService(intent)
        serviceButton.postDelayed({ refreshServiceButton() }, 300)
    }

    private fun refreshServiceButton() {
        serviceButton.text = if (app.controller.isRunning) "Stop failover service" else "Start failover service"
    }

    private fun runDiagnostics() = ui.launch {
        diagView.text = "Diagnosing…"
        val states = app.monitor.networks.value.values.toList()
        if (states.isEmpty()) { diagView.text = "No Wi-Fi or cellular network is up."; return@launch }
        val reports = states.map { s ->
            val r = app.prober.probe(s)
            val d = DiagnosisClassifier.classify(r)
            "[${r.transport}] ${d.verdict}\n${d.detail}\nFix: ${d.remedy}"
        }
        diagView.text = reports.joinToString("\n\n")
    }

    private fun aggregatedDownload(url: String) = ui.launch {
        val nets = app.monitor.networks.value.values
            .filter { it.transport == Transport.WIFI || it.transport == Transport.CELLULAR }
            .associate { it.transport.name to it.network }
        if (nets.size < 2) {
            diagView.text = "Need Wi-Fi and cellular both up (enable Hot standby). Found: ${nets.keys}"
            return@launch
        }
        diagView.text = "Downloading over ${nets.keys}…"
        val out = File(cacheDir, "aggregate.bin")
        diagView.text = runCatching { app.downloader.download(URL(url), out, nets) }.fold(
            onSuccess = { r ->
                val mbps = r.bytes * 8.0 / 1_000_000 / (r.millis / 1000.0).coerceAtLeast(0.001)
                "Done: ${r.bytes} bytes in ${r.millis} ms (${"%.1f".format(mbps)} Mbit/s)\nPer network: ${r.bytesByNetwork}"
            },
            onFailure = { "Aggregated download failed: ${it.message}" },
        )
        out.delete()
    }

    private fun text(s: String, bold: Boolean = false) = TextView(this).apply {
        text = s
        setPadding(0, 16, 0, 16)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }
}
