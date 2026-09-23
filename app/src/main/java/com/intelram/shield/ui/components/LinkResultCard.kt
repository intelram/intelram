package com.intelram.shield.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.intelram.shield.qr.LinkInspectionResult
import com.intelram.shield.qr.LinkVerdict
import com.intelram.shield.ui.theme.Amber
import com.intelram.shield.ui.theme.AmberSoft
import com.intelram.shield.ui.theme.GreenDark
import com.intelram.shield.ui.theme.GreenSoft
import com.intelram.shield.ui.theme.InkFaint
import com.intelram.shield.ui.theme.InkSoft
import com.intelram.shield.ui.theme.Red
import com.intelram.shield.ui.theme.RedSoft
import com.intelram.shield.ui.theme.Surface

/**
 * Renders a [LinkInspectionResult] verdict. Shared by the QR scanner and the
 * "check a link from an email" screen — both feed the exact same real,
 * network-metadata-only inspection through this one card.
 */
@Composable
fun LinkResultCard(result: LinkInspectionResult, onReset: () -> Unit) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
    ) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (result.verdict) {
                LinkVerdict.UNSAFE -> {
                    LinkResultBadge(color = Red, softColor = RedSoft, symbol = "!")
                    Text("This link isn't safe to open", style = MaterialTheme.typography.headlineSmall, color = Red)
                    LinkReasonsList(result.reasons)
                    LinkDestinationLine(result)
                    Spacer(Modifier.height(18.dp))
                    PrimaryButton(text = "Don't Open — Check Another", onClick = onReset)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Open Anyway (not recommended)",
                        color = Red,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp).clickable {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.finalUrl)))
                        },
                    )
                }
                LinkVerdict.CAUTION -> {
                    LinkResultBadge(color = Amber, softColor = AmberSoft, symbol = "?")
                    Text("Proceed with caution", style = MaterialTheme.typography.headlineSmall, color = Amber)
                    LinkReasonsList(result.reasons)
                    LinkDestinationLine(result)
                    Spacer(Modifier.height(18.dp))
                    PrimaryButton(
                        text = "Open Anyway",
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.finalUrl))) },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Don't open — check another",
                        color = InkFaint,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp).clickable(onClick = onReset),
                    )
                }
                LinkVerdict.SAFE -> {
                    LinkResultBadge(color = GreenDark, softColor = GreenSoft, symbol = "✓")
                    Text("This link looks safe", style = MaterialTheme.typography.headlineSmall, color = GreenDark)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        linkSafeSummary(result),
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSoft,
                        textAlign = TextAlign.Center,
                    )
                    LinkDestinationLine(result)
                    Spacer(Modifier.height(18.dp))
                    PrimaryButton(
                        text = "Open Link",
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.finalUrl))) },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Check another",
                        color = InkFaint,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp).clickable(onClick = onReset),
                    )
                }
                LinkVerdict.NOT_A_LINK -> {
                    Text("Nothing to check", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This isn't a web link, so there's nothing to check for safety.",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSoft,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(18.dp))
                    PrimaryButton(text = "Check Another", onClick = onReset)
                }
            }
        }
    }
}

@Composable
private fun LinkReasonsList(reasons: List<String>) {
    Spacer(Modifier.height(8.dp))
    reasons.forEach {
        Text("• $it", style = MaterialTheme.typography.bodySmall, color = InkSoft, textAlign = TextAlign.Center)
    }
}

@Composable
private fun LinkDestinationLine(result: LinkInspectionResult) {
    Spacer(Modifier.height(12.dp))
    Text(result.finalUrl, style = MaterialTheme.typography.bodySmall, color = InkFaint, textAlign = TextAlign.Center)
    if (result.redirectHops.size > 1) {
        Text(
            "via ${result.redirectHops.size} redirect(s) from the original link",
            style = MaterialTheme.typography.bodySmall,
            color = InkFaint,
        )
    }
}

private fun linkSafeSummary(result: LinkInspectionResult): String = buildString {
    append("No red flags found in its redirects or certificate")
    val checkedFeeds = listOfNotNull(
        "Google's threat list".takeIf { result.safeBrowsingChecked },
        "URLhaus's malware-link feed".takeIf { result.urlhausChecked },
    )
    if (checkedFeeds.isNotEmpty()) append(", and it's not on ${checkedFeeds.joinToString(" or ")}")
    append(".")
}

@Composable
private fun LinkResultBadge(color: Color, softColor: Color, symbol: String) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(softColor),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, style = MaterialTheme.typography.headlineMedium, color = color)
    }
}
