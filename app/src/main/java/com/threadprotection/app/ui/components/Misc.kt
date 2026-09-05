package com.threadprotection.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.threadprotection.app.network.TechnicalDetails
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.Severity
import com.threadprotection.app.ui.theme.TpType
import com.threadprotection.app.ui.theme.severityColor
import com.threadprotection.app.ui.theme.severityTint

/** The app's real launcher icon, read straight from PackageManager — not a placeholder glyph.
 *  Falls back to a generic icon if the app was uninstalled between the scan and this render. */
@Composable
fun AppIcon(packageName: String, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val palette = LocalTpPalette.current
    val bitmap = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName) }
            .getOrNull()
            ?.toBitmap(width = 128, height = 128)
            ?.asImageBitmap()
    }
    val shape = RoundedCornerShape(size / 4)
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = modifier.size(size).clip(shape))
    } else {
        Box(
            modifier = modifier.size(size).clip(shape).background(palette.card2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Apps, contentDescription = null, tint = palette.muted, modifier = Modifier.size(size / 2))
        }
    }
}

@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    val palette = LocalTpPalette.current
    Text(text = text.uppercase(), style = TpType.sectionHeading, color = palette.muted, modifier = modifier)
}

@Composable
fun SeverityBadge(label: String, color: Color, tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tint)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text = label, style = TpType.badge, color = color)
    }
}

@Composable
fun SeverityBadgeFor(sev: Severity, modifier: Modifier = Modifier) {
    val palette = LocalTpPalette.current
    val label = when (sev) {
        Severity.CRITICAL -> "CRITICAL"
        Severity.HIGH -> "HIGH"
        Severity.MEDIUM -> "MEDIUM"
        Severity.LOW -> "LOW"
        Severity.FIXED -> "FIXED"
    }
    SeverityBadge(label, palette.severityColor(sev), palette.severityTint(sev), modifier)
}

@Composable
fun BackCircleButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalTpPalette.current
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .border(BorderStroke(1.5.dp, palette.line4), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = palette.fg2)
    }
}

@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    val palette = LocalTpPalette.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(palette.card)
            .border(BorderStroke(1.dp, palette.line), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 13.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = TpType.cardTitleBold.copy(fontSize = 18.sp), color = palette.accent, textAlign = TextAlign.Center)
        Text(label, style = TpType.caption.copy(fontSize = 12.5.sp, lineHeight = 16.sp), color = palette.muted, textAlign = TextAlign.Center)
    }
}

enum class NavTab { HOME, QR, CHAT, BRAIN, SETTINGS }

@Composable
fun BottomNavBar(
    active: NavTab,
    onHome: () -> Unit,
    onQr: () -> Unit,
    onChat: () -> Unit,
    onBrain: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTpPalette.current
    Column(modifier = modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.line))
        Row(modifier = Modifier.fillMaxWidth().background(palette.bg2)) {
            NavTabItem("Home", active == NavTab.HOME, onHome, Modifier.weight(1f))
            NavTabItem("QR", active == NavTab.QR, onQr, Modifier.weight(1f))
            NavTabItem("Chat", active == NavTab.CHAT, onChat, Modifier.weight(1f))
            NavTabItem("AI brain", active == NavTab.BRAIN, onBrain, Modifier.weight(1f))
            NavTabItem("Settings", active == NavTab.SETTINGS, onSettings, Modifier.weight(1f))
        }
    }
}

@Composable
private fun NavTabItem(label: String, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalTpPalette.current
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(top = 15.dp, bottom = 17.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .width(20.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (active) palette.accent else Color.Transparent),
        )
        Text(
            text = label,
            style = TpType.caption.copy(fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium),
            color = if (active) palette.accent else palette.muted,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/**
 * Real, independently-verifiable facts about a site, gathered live at check time — the "proof"
 * behind a URL verdict. Shared by the Scan-a-website and QR-scanner result screens since both
 * run the same [com.threadprotection.app.network.ThreatIntelRepository.checkUrl] pipeline.
 */
@Composable
fun TechnicalDetailsCard(tech: TechnicalDetails, modifier: Modifier = Modifier) {
    val palette = LocalTpPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(palette.card2)
            .border(BorderStroke(1.dp, palette.line2), RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Technical details", style = TpType.cardTitle.copy(fontSize = 15.sp), color = palette.fg)

        DetailRow("Host", tech.host)
        if (tech.resolvedIps.isNotEmpty()) DetailRow("Resolved IP", tech.resolvedIps.joinToString())
        tech.ipIntel?.let { ip ->
            DetailRow("Hosted by", listOfNotNull(ip.org ?: ip.isp, ip.asn?.let { "AS$it" }).joinToString(" · ").ifBlank { "Unknown" })
            listOfNotNull(ip.city, ip.country).joinToString(", ").takeIf { it.isNotBlank() }?.let { DetailRow("Location", it) }
        }

        val domain = tech.domain
        when {
            domain?.ageDays != null -> {
                DetailRow("Domain age", "${domain.ageDays} day${if (domain.ageDays == 1L) "" else "s"} (registered ${domain.registeredOn?.take(10) ?: "?"})")
                domain.registrar?.let { DetailRow("Registrar", it) }
            }
            else -> DetailRow("Domain age", "Not available for this domain's registry")
        }

        val tls = tech.tls
        when {
            tls == null -> DetailRow("TLS certificate", "Could not connect on port 443")
            tls.error != null -> DetailRow("TLS certificate", tls.error)
            tls.trusted -> DetailRow(
                "TLS certificate",
                "Trusted · issued by ${tls.issuer?.substringBefore(',') ?: "unknown"} · expires in ${tls.daysUntilExpiry ?: "?"} days",
            )
        }

        tech.http?.let { http ->
            if (http.error != null) {
                DetailRow("Live request", "Could not connect: ${http.error}")
            } else {
                DetailRow("Live request", "HTTP ${http.statusCode ?: "?"}${http.serverHeader?.let { " · server: $it" }.orEmpty()}")
                if (http.redirectCount > 0) {
                    // The full hop-by-hop chain, not just a count — this is exactly what a shortener
                    // was hiding before this check followed it.
                    val landedOnHost = http.finalUrl?.let { url -> runCatching { java.net.URI(url).host }.getOrNull() }
                    DetailRow("Redirect chain", (http.redirectHosts + listOfNotNull(landedOnHost)).joinToString(" → "))
                }
            }
        }

        val dnssec = tech.dnssec
        DetailRow(
            "DNS security",
            when {
                dnssec == null -> "Could not check"
                dnssec.validated -> "DNSSEC validated — DNS answers are cryptographically signed"
                else -> "Not DNSSEC-signed (common — not itself a warning sign)"
            },
        )
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    val palette = LocalTpPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = TpType.caption.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
            color = palette.muted,
            modifier = Modifier.width(96.dp),
        )
        Text(value, style = TpType.caption.copy(fontSize = 12.5.sp, lineHeight = 18.sp), color = palette.fg2, modifier = Modifier.weight(1f))
    }
}
