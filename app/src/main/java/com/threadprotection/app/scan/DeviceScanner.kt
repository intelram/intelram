package com.threadprotection.app.scan

import android.content.Context
import android.util.Log
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

data class ScanPhaseUpdate(val index: Int, val total: Int, val label: String, val meta: String, val liveItem: String? = null)

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
    /**
     * Which categories this scan could actually examine.
     *
     * Needed to tell "this problem is gone" from "this scan couldn't look". Only a category listed
     * here may be used to conclude that a previously resolved finding has genuinely been cleared —
     * see FindingIdentity.clearedRecords. A scan that couldn't read /proc/net must not be allowed
     * to decide that an open port has closed.
     */
    val coveredCategories: Set<Category>,
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

    /**
     * @param paced Whether to hold each phase on screen with the small artificial delays below —
     * on for the manual "Scan Now" flow, where lingering a moment on each phase (see the calls in
     * [ScanningScreen]) reads as legible progress rather than a flash. User-requested off for the
     * on-open flow (`AppViewModel.runAutoScanOnSplash`): "very time taking… I don't need it" — the
     * work itself is unchanged (still every real check below), only the roughly 3 seconds of pure
     * waiting these delays add on top of it disappears. Never skip a real check to make this faster,
     * only the cosmetic pacing.
     */
    suspend fun scan(apiKeys: ApiKeys, paced: Boolean = true, onPhase: suspend (ScanPhaseUpdate) -> Unit): ScanResult = withContext(Dispatchers.IO) {
        val total = 8
        val findings = mutableListOf<Finding>()
        suspend fun pace(ms: Long) { if (paced) delay(ms) }

        onPhase(ScanPhaseUpdate(0, total, "Building software inventory…", "Reading installed packages"))
        val audit = permissionAudit.audit { label, packageName ->
            onPhase(ScanPhaseUpdate(0, total, "Building software inventory…", "Checking $label", liveItem = "$label  ·  $packageName"))
        }
        onPhase(ScanPhaseUpdate(0, total, "Building software inventory…", "${audit.totalInstalledCount} apps installed"))
        pace(350)

        onPhase(ScanPhaseUpdate(1, total, "Scanning apps & sideloaded APKs…", "${audit.sideloadedApps.size} installed outside an app store"))
        findings += audit.findings
        pace(350)

        val riskyPermCount = audit.apps.sumOf { app -> app.perms.count { it.risk } }
        onPhase(ScanPhaseUpdate(2, total, "Auditing app permissions…", "$riskyPermCount risky permissions found across ${audit.apps.size} apps"))
        pace(350)

        onPhase(ScanPhaseUpdate(3, total, "Checking connected hardware…", "USB, Bluetooth, SIM, power"))
        val hwDevices = hardwareWatcher.scan()
        for (dev in hwDevices) {
            onPhase(ScanPhaseUpdate(3, total, "Checking connected hardware…", dev.detail, liveItem = "${dev.name}  ·  ${dev.detail}"))
            pace(90)
        }
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
        pace(350)

        onPhase(ScanPhaseUpdate(4, total, "Probing open ports & listeners…", "Checking local socket table"))
        val rawPorts = PortScanner.listeningPorts()
        val ports = rawPorts.map { PortFinding(it.port, ownerLabelForUid(it.uid)) }
        for (p in ports) {
            onPhase(ScanPhaseUpdate(4, total, "Probing open ports & listeners…", "Port ${p.port} open", liveItem = "Port ${p.port}  ·  ${p.ownerLabel}"))
            pace(80)
        }
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
        pace(350)

        onPhase(ScanPhaseUpdate(5, total, "Verifying OS build & patch level…", "Reading security patch date"))
        val patchInfo = OsPatchChecker.current()
        OsPatchChecker.finding(patchInfo)?.let { findings += it }
        pace(350)

        onPhase(ScanPhaseUpdate(6, total, "Checking Wi-Fi network security…", "Reading the live connection"))
        val wifiSecurity = WifiSecurityScanner.scan(context)
        wifiSecurity.ssid?.let {
            onPhase(ScanPhaseUpdate(6, total, "Checking Wi-Fi network security…", "Connected to $it", liveItem = "Wi-Fi  ·  $it"))
        }
        findings += WifiSecurityScanner.findings(wifiSecurity)
        onPhase(ScanPhaseUpdate(6, total, "Checking Wi-Fi network security…", WifiSecurityScanner.summaryLabel(wifiSecurity)))
        pace(350)

        val configuredFeeds = ApiKeyId.entries.count { apiKeys.has(it) }
        onPhase(ScanPhaseUpdate(7, total, "Checking live threat-intelligence feeds…", "Querying NVD for browser/WebView CVEs"))
        // Distinguish "the lookup ran and found nothing" from "the lookup couldn't run" — only the
        // former is evidence that a previously reported CVE finding is genuinely gone.
        var cveLookupSucceeded = false
        runCatching { webViewCveFinding(apiKeys) }
            .onSuccess { cve ->
                cveLookupSucceeded = true
                cve?.let { findings += it }
            }
            .onFailure { Log.w(TAG, "scan: WebView CVE lookup failed — not treating its absence as 'cleared'", it) }
        onPhase(ScanPhaseUpdate(7, total, "Checking live threat-intelligence feeds…", "$configuredFeeds/${ApiKeyId.entries.size} sources configured"))
        pace(350)

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
            coveredCategories = buildSet {
                // Package inspection always works, so a sideloaded-app finding that has gone really
                // has gone. The permission and hardware passes ride on the same enumeration.
                add(Category.SOFTWARE)
                add(Category.ACTIVITY)
                add(Category.HARDWARE)
                // Build.VERSION.SECURITY_PATCH is always readable.
                add(Category.OS)
                // Only claim port coverage when /proc/net was actually readable — on newer Android
                // it isn't, and an unreadable table is not evidence that nothing is listening.
                if (PortScanner.readable()) add(Category.PORTS)
                // A CVE lookup that failed (offline, rate-limited) is not evidence of no CVE.
                if (cveLookupSucceeded) add(Category.EMAIL)
                // Only claim network coverage when actually on Wi-Fi — off Wi-Fi, the absence of a
                // Wi-Fi finding is not evidence the previous one was resolved.
                if (wifiSecurity.onWifi) add(Category.NETWORK)
            },
        )
    }

    /** Resolves a listening socket's Linux UID to the real app that owns it, not just a bare number. */
    private fun ownerLabelForUid(uid: Int): String {
        WELL_KNOWN_UIDS[uid]?.let { return it }
        val pm = context.packageManager
        val packages = runCatching { pm.getPackagesForUid(uid) }.getOrNull()
        val label = packages?.firstNotNullOfOrNull { pkg ->
            runCatching { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }.getOrNull()
        }
        return label ?: "uid $uid"
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

    companion object {
        private const val TAG = "TPScan"

        /** Low system UIDs are shared kernel/framework identities, not tied to any installed package. */
        private val WELL_KNOWN_UIDS = mapOf(
            0 to "root (kernel)",
            1000 to "Android system",
            1001 to "Radio / telephony",
            1002 to "Bluetooth stack",
            1013 to "Media server",
            1021 to "GPS / location",
            1051 to "Network stack",
            9999 to "Sandboxed app (nobody)",
        )
    }
}
