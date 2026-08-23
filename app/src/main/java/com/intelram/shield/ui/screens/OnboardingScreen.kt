package com.intelram.shield.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.intelram.shield.ui.components.PrimaryButton
import com.intelram.shield.ui.theme.Bg
import com.intelram.shield.ui.theme.Green
import com.intelram.shield.ui.theme.GreenDark
import com.intelram.shield.ui.theme.GreenSoft
import com.intelram.shield.ui.theme.InkSoft

private data class Bullet(val icon: androidx.compose.ui.graphics.vector.ImageVector, val title: String, val subtitle: String)

@Composable
fun OnboardingScreen(onGetStarted: () -> Unit, onSignIn: () -> Unit) {
    val bullets = listOf(
        Bullet(Icons.Filled.CheckCircle, "Written in plain English", "No technical jargon — ever"),
        Bullet(Icons.Filled.Shield, "Protects in real time", "Not just when you remember to check"),
        Bullet(Icons.Filled.Lock, "Your data stays yours", "Every scan runs on your device"),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(horizontal = 28.dp)
            .padding(top = 56.dp, bottom = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Green),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Shield, contentDescription = null, tint = Color.White)
            }
            Text(
                "  Threat Protection",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(GreenSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Shield,
                    contentDescription = null,
                    tint = GreenDark,
                    modifier = Modifier.size(64.dp),
                )
            }
            Spacer(Modifier.height(28.dp))
            Text(
                "Your whole digital life, protected.",
                style = MaterialTheme.typography.headlineLarge,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "We check your apps, Wi-Fi, links, files and accounts for danger — and explain " +
                    "everything in plain English. No jargon, ever.",
                style = MaterialTheme.typography.bodyLarge,
                color = InkSoft,
            )
            Spacer(Modifier.height(14.dp))
            bullets.forEach { bullet ->
                Row(
                    modifier = Modifier.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(GreenSoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(bullet.icon, contentDescription = null, tint = GreenDark, modifier = Modifier.size(20.dp))
                    }
                    Column(modifier = Modifier.padding(start = 14.dp)) {
                        Text(bullet.title, style = MaterialTheme.typography.titleMedium)
                        Text(bullet.subtitle, style = MaterialTheme.typography.bodySmall, color = InkSoft)
                    }
                }
            }
        }

        PrimaryButton(text = "Get Started", onClick = onGetStarted)
        Spacer(Modifier.height(16.dp))
        Text(
            "Already protected? Sign in",
            style = MaterialTheme.typography.bodyMedium,
            color = GreenDark,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSignIn)
                .padding(vertical = 4.dp),
        )
    }
}
