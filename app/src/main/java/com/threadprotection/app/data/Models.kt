package com.threadprotection.app.data

import com.threadprotection.app.ui.theme.Severity

enum class Category { SOFTWARE, PORTS, SERVICES, LICENSING, ACTIVITY, HARDWARE, OS, EMAIL }

data class Breach(val site: String, val date: String, val data: String)

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

data class AppPermission(val id: String, val name: String, val why: String, val risk: Boolean)

data class PermApp(val app: String, val packageName: String, val kind: String, val perms: List<AppPermission>)

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
