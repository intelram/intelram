package com.intelram.shield.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.intelram.shield.qr.LinkInspectionResult
import com.intelram.shield.qr.LinkInspector
import com.intelram.shield.ui.components.LinkResultCard
import com.intelram.shield.ui.components.PrimaryButton
import com.intelram.shield.ui.theme.Bg
import com.intelram.shield.ui.theme.Green
import com.intelram.shield.ui.theme.InkFaint
import com.intelram.shield.ui.theme.InkSoft
import com.intelram.shield.ui.theme.Surface

private val CHECKLIST = listOf(
    "Sender address" to "Check the full email address, not just the display name — " +
        "\"Bank Support\" can hide almost any address behind it.",
    "Urgency and threats" to "\"Act now or your account is suspended\" is a pressure tactic " +
        "designed to stop you from thinking it through.",
    "Generic greeting" to "\"Dear Customer\" instead of your name is common in mass phishing sends, " +
        "though not proof by itself.",
    "Links before you click" to "On a phone, press and hold a link to preview where it actually " +
        "goes — or paste it below to have it checked for you.",
    "Unexpected attachments" to "Don't open .exe, .zip, .apk, or macro-enabled Office files you " +
        "weren't expecting, even from someone you know — their account may be compromised.",
    "Requests for credentials or payment" to "Real banks, and Google, never ask you to \"confirm\" " +
        "your password or send payment by email.",
    "Too good to be true" to "Unexpected prizes, refunds, or job offers are one of the most common " +
        "phishing lures.",
)

private sealed interface LinkCheckState {
    data object Idle : LinkCheckState
    data class Checking(val payload: String) : LinkCheckState
    data class Done(val result: LinkInspectionResult) : LinkCheckState
}

@Composable
fun EmailLinkCheckScreen() {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<LinkCheckState>(LinkCheckState.Idle) }

    LaunchedEffect(state) {
        val checking = state as? LinkCheckState.Checking ?: return@LaunchedEffect
        val result = LinkInspector(context).inspect(checking.payload)
        state = LinkCheckState.Done(result)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Bg),
        contentPadding = PaddingValues(20.dp, 48.dp, 20.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("Email & Link Check", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Spot a phishing email yourself, or paste a suspicious link from one below to " +
                    "run it through the same real checks as the QR scanner.",
                style = MaterialTheme.typography.bodySmall,
                color = InkFaint,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Surface),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        "Check a link from an email",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Paste a link, e.g. https://…") },
                        singleLine = true,
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
                    when (state) {
                        is LinkCheckState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                            Text(
                                "  Checking this link…",
                                style = MaterialTheme.typography.bodySmall,
                                color = InkSoft,
                            )
                        }
                        else -> PrimaryButton(
                            text = "Check This Link",
                            onClick = { if (input.isNotBlank()) state = LinkCheckState.Checking(input.trim()) },
                        )
                    }
                }
            }
        }

        (state as? LinkCheckState.Done)?.let { done ->
            item {
                LinkResultCard(done.result) {
                    state = LinkCheckState.Idle
                    input = ""
                }
            }
        }

        item {
            Text(
                "What to look for yourself",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        items(CHECKLIST) { (title, body) -> ChecklistCard(title, body) }

        item {
            Text(
                "This checklist is educational — the app doesn't connect to your Gmail or any " +
                    "inbox and can't scan email content directly. The link checker above uses the " +
                    "same real redirect, certificate, Safe Browsing, and URLhaus checks as the QR scanner.",
                style = MaterialTheme.typography.bodySmall,
                color = InkFaint,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ChecklistCard(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface)) {
        Row(Modifier.padding(16.dp)) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Green)
            Column(Modifier.padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodySmall, color = InkSoft, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}
