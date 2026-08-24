package com.threadprotection.app.scan

import android.os.Build
import com.threadprotection.app.data.Category
import com.threadprotection.app.data.Finding
import com.threadprotection.app.data.Remedy
import com.threadprotection.app.ui.theme.Severity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Real `Build.VERSION.SECURITY_PATCH` freshness check — no network needed. */
object OsPatchChecker {

    private val FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    data class PatchInfo(val patchDate: Date?, val ageDays: Int?, val raw: String)

    fun current(): PatchInfo {
        val raw = Build.VERSION.SECURITY_PATCH ?: ""
        val date = runCatching { FORMAT.parse(raw) }.getOrNull()
        val ageDays = date?.let { TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - it.time).toInt() }
        return PatchInfo(date, ageDays, raw)
    }

    fun finding(info: PatchInfo): Finding? {
        val ageDays = info.ageDays ?: return null
        if (ageDays < 120) return null
        val months = ageDays / 30
        val risk = (30 + months * 5).coerceAtMost(70)
        return Finding(
            id = "os-patch",
            name = "Security patch $months month${if (months == 1) "" else "s"} old",
            type = "Operating system · Patch level ${info.raw}",
            cat = Category.OS,
            sev = if (risk >= 60) Severity.HIGH else if (risk >= 45) Severity.MEDIUM else Severity.LOW,
            risk = risk,
            desc = "This phone's Android security patch is dated ${info.raw} — about $months months old. Unpatched devices are prime targets for known, already-public exploits.",
            advice = "Install the latest system update from Settings → System → System update. If this device no longer receives updates, real-time protection matters even more.",
            fix = "Open system update",
            pros = listOf("Closes publicly known kernel and framework flaws", "Usually improves battery and stability too"),
            cons = listOf("Requires a restart and some time", "Very old devices may no longer receive an update at all"),
            source = "Build.VERSION.SECURITY_PATCH (on-device)",
            remedy = Remedy.SystemUpdate,
        )
    }
}
