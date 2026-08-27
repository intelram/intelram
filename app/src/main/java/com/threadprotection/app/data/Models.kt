package com.threadprotection.app.data

import com.threadprotection.app.ui.theme.Severity

enum class Category { SOFTWARE, PORTS, SERVICES, LICENSING, ACTIVITY, HARDWARE, OS, EMAIL }

data class Breach(val site: String, val date: String, val data: String)

/**
 * What tapping "Fix" actually does. A 3rd-party app cannot revoke another app's permission,
 * close a listening port, or patch the OS itself — Android has no API for any of that — so the
 * only honest "fix" is opening the exact system screen where the user can do it themselves.
 * [None] means there's genuinely no settings screen for it (e.g. unplugging a USB device);
 * the finding's `advice` text is the whole remedy in that case.
 */
sealed interface Remedy {
    data class AppSettings(val packageName: String) : Remedy
    data object DeveloperOptions : Remedy
    data object SystemUpdate : Remedy
    data class PlayStore(val packageName: String) : Remedy
    data object None : Remedy
}

data class Finding(
    val id: String,
    val name: String,
    val type: String,
    val cat: Category,
    val sev: Severity,
    val risk: Int,
    val desc: String,
    val advice: String,
    val fix: String,
    val pros: List<String>,
    val cons: List<String>,
    val source: String,
    val breaches: List<Breach>? = null,
    val remedy: Remedy = Remedy.None,
)

data class HwDevice(val name: String, val detail: String, val ok: Boolean)

enum class HwVerdict { DANGER, WARN }

data class HwSim(
    val name: String,
    val kind: String,
    val verdict: HwVerdict,
    val risk: Int,
    val why: String,
    val advice: String,
)

data class Feed(val name: String, val kind: String, val items: String)

/** [state] is the live OS-reported grant state, re-read from PackageManager on every audit — see
 *  scan/PermissionState.kt. [permissionName] is the full `android.permission.*` string, kept so the
 *  UI can name exactly which switch the user needs to find in system Settings. */
data class AppPermission(
    val id: String,
    val permissionName: String,
    val name: String,
    val description: String,
    val why: String,
    val risk: Boolean,
    val state: com.threadprotection.app.scan.PermGrantState = com.threadprotection.app.scan.PermGrantState.UNKNOWN,
)

/** installerLabel is a human-readable source ("Google Play Store", "Sideloaded / unknown source",
 *  "Preinstalled (system)") derived from the real installer package PackageManager reports — see
 *  PermissionAudit.installerLabelOf. safetyScore is a real 0-100 heuristic computed from the
 *  actual permission/install-source signals for this one app (README's "safety rating"), the same
 *  kind of on-device scoring the dashboard's overall security score already uses — not a lookup
 *  against any external reputation database. */
data class PermApp(
    val app: String,
    val packageName: String,
    val kind: String,
    val installerLabel: String,
    val safetyScore: Int,
    val perms: List<AppPermission>,
)

data class BrainSource(val name: String, val detail: String, val metric: String)

data class LearnStep(val step: String, val body: String)

data class Assurance(val title: String, val body: String)

data class TrustPoint(val title: String, val body: String)

/** Seed URLs for the QR screen's "try a code" grid — each is run through the real, live URL checker. */
data class QrSample(val label: String, val url: String)

data class Stat(val value: String, val label: String)

data class Account(
    val name: String,
    val email: String,
    val initial: String,
    val picture: String? = null,
)
