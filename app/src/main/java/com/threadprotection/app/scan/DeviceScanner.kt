package com.threadprotection.app.scan

import android.content.Context
import com.threadprotection.app.data.ApiKeyId
import com.threadprotection.app.data.ApiKeys
import com.threadprotection.app.data.Category
import com.threadprotection.app.data.Finding
import com.threadprotection.app.data.HwDevice
import com.threadprotection.app.data.PermApp
import com.threadprotection.app.data.Remedy
import com.threadprotection.app.network.ThreatIntelRepository
import com.threadprotection.app.ui.theme.Severity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class ScanPhaseUpdate(val index: Int, val total: Int, val label: String, val meta: String)

data class PortFinding(val port: Int, val ownerLabel: String)

data class ScanResult(
    val findings: List<Finding>,
    val permApps: List<PermApp>,
    val hwDevices: List<HwDevice>,
    val appsScanned: Int,
    val ports: List<PortFinding>,
    val portsProbed: Boolean,
    val osPatchLabel: String,
    val feedsConfigured: Int,
    val feedsTotal: Int,
)

/**
 * Orchestrates every real on-device check described in the prototype's scan phases (README
 * §Scanning), reporting progress as it goes. This is the actual "Scan now" pipeline — everything
 * it finds comes from `PackageManager`, `/proc/net/tcp`, `Build.VERSION`, and Android's hardware
 * managers, not demo data.
 */
class DeviceScanner(
    private val context: Context,
    private val threatIntel: ThreatIntelRepository,
) {
    private val permissionAudit = PermissionAudit(context)
    private val hardwareWatcher = HardwareWatcher(context)

    suspend fun scan(apiKeys: ApiKeys, onPhase: suspend (ScanPhaseUpdate) -> Unit): ScanResult = withContext(Dispatchers.IO) {
        val total = 7
        val findings = mutableListOf<Finding>()

        onPhase(ScanPhaseUpdate(0, total, "Building software inventory…", "Reading installed packages"))
        val audit = permissionAudit.audit()
        onPhase(ScanPhaseUpdate(0, total, "Building software inventory…", "${audit.totalInstalledCount} apps installed"))
        delay(350)

        onPhase(ScanPhaseUpdate(1, total, "Scanning apps & sideloaded APKs…", "${audit.sideloadedApps.size} installed outside an app store"))
        findings += audit.findings
        delay(350)

        val riskyPermCount = audit.apps.sumOf { app -> app.perms.count { it.risk } }
        onPhase(ScanPhaseUpdate(2, total, "Auditing app permissions…", "$riskyPermCount risky permissions found across ${audit.apps.size} apps"))
        delay(350)

        onPhase(ScanPhaseUpdate(3, total, "Checking connected hardware…", "USB, Bluetooth, SIM, power"))
        val hwDevices = hardwareWatcher.scan()
        hardwareWatcher.suspiciousHidAlert()?.let {
            findings += Finding(
                id = "hw-hid",
                name = "Unrecognised keyboard-class USB device",
                type = "Hardware · ${it.kind}",
                cat = Category.HARDWARE,
                sev = Severity.HIGH,
                risk = it.risk,
                desc = it.why,
                advice = it.advice,
                fix = "Review connected hardware",
                pros = listOf("Stops an unrecognised input device from typing on your behalf"),
                cons = listOf("A genuine keyboard or dock would need to be reconnected"),
                source = "UsbManager (on-device)",
            )
        }
        delay(350)

        onPhase(ScanPhaseUpdate(4, total, "Probing open ports & listeners…", "Checking local socket table"))
        val rawPorts = PortScanner.listeningPorts()
        val ports = rawPorts.map { PortFinding(it.port, "uid ${it.uid}") }
        if (rawPorts.any { it.port == 5555 }) {
            findings += Finding(
                id = "port-5555",
                name = "Port 5555 open — ADB over Wi‑Fi",
                type = "Open port · Listening",
                cat = Category.PORTS,
                sev = Severity.CRITICAL,
                risk = 92,
                desc = "Wireless debugging is listening on port 5555. On the same network, this allows installing apps and reading storage without unlocking the phone.",
                advice = "Turn off wireless debugging in Developer options unless you're actively using it on a trusted network.",
                fix = "Close port 5555",
                pros = listOf("Removes remote shell access to this device", "No functional loss for everyday use"),
                cons = listOf("Wireless ADB debugging stops working until re-enabled"),
                source = "/proc/net/tcp (on-device)",
                remedy = Remedy.DeveloperOptions,
            )
        }
        onPhase(ScanPhaseUpdate(4, total, "Probing open ports & listeners…", if (PortScanner.readable()) "${ports.size} listening sockets found" else "Restricted on this Android version"))
        delay(350)

        onPhase(ScanPhaseUpdate(5, total, "Verifying OS build & patch level…", "Reading security patch date"))
        val patchInfo = OsPatchChecker.current()
        OsPatchChecker.finding(patchInfo)?.let { findings += it }
        delay(350)

        val configuredFeeds = ApiKeyId.entries.count { apiKeys.has(it) }
        onPhase(ScanPhaseUpdate(6, total, "Checking live threat-intelligence feeds…", "Querying NVD for browser/WebView CVEs"))
        webViewCveFinding(apiKeys)?.let { findings += it }
        onPhase(ScanPhaseUpdate(6, total, "Checking live threat-intelligence feeds…", "$configuredFeeds/${ApiKeyId.entries.size} sources configured"))
        delay(350)

        ScanResult(
            findings = findings.sortedByDescending { it.risk },
            permApps = audit.apps,
            hwDevices = hwDevices,
            appsScanned = audit.totalInstalledCount,
            ports = ports,
            portsProbed = PortScanner.readable(),
            osPatchLabel = patchInfo.raw.ifBlank { "Unknown" },
            feedsConfigured = configuredFeeds,
            feedsTotal = ApiKeyId.entries.size,
        )
    }

    /**
     * The one CVE cross-reference precise enough to state honestly without a device: every
     * Android phone runs a real, named, versioned WebView provider, and NVD has real CVE
     * records for it. This reports what NVD actually lists — it does not claim this exact
     * device is exploitable, since that needs proper version-range matching NVD's keyword
     * search doesn't give us.
     */
    private suspend fun webViewCveFinding(apiKeys: ApiKeys): Finding? {
        val webView = runCatching { android.webkit.WebView.getCurrentWebViewPackage() }.getOrNull() ?: return null
        val cves = threatIntel.searchCves("Android WebView", apiKeys[ApiKeyId.NVD])
        if (cves.isEmpty()) return null
        val top = cves.maxByOrNull { it.metrics?.bestScore ?: 0.0 } ?: cves.first()
        val score = top.metrics?.bestScore
        return Finding(
            id = "nvd-webview",
            name = "${cves.size} recent CVEs listed for Android WebView",
            type = "Software · ${webView.packageName} ${webView.versionName ?: ""}".trim(),
            cat = Category.SOFTWARE,
            sev = if ((score ?: 0.0) >= 7.0) Severity.MEDIUM else Severity.LOW,
            risk = ((score ?: 5.0) * 10).toInt().coerceIn(20, 65),
            desc = "NVD lists ${top.id}${score?.let { " (CVSS $it)" }.orEmpty()} for Android's WebView component, which every app that shows web content relies on: ${top.englishDescription?.take(220) ?: "see NVD for details"}",
            advice = "Keep \"Android System WebView\" and Chrome updated from the Play Store — these CVEs are patched automatically by routine updates, not by anything manual.",
            fix = "Open Play Store updates",
            pros = listOf("Closes publicly documented WebView flaws", "No manual configuration needed — just keep auto-update on"),
            cons = listOf("Requires a normal app update, a few seconds on Wi‑Fi"),
            source = "NVD CVE database (live)",
            remedy = Remedy.PlayStore(webView.packageName),
        )
    }
}
