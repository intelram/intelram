package com.intelram.shield.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.intelram.shield.auth.AuthUiState
import com.intelram.shield.auth.AuthViewModel
import com.intelram.shield.ui.components.PrimaryButton
import com.intelram.shield.ui.theme.Bg
import com.intelram.shield.ui.theme.Border
import com.intelram.shield.ui.theme.Green
import com.intelram.shield.ui.theme.GreenDark
import com.intelram.shield.ui.theme.GreenSoft
import com.intelram.shield.ui.theme.InkFaint
import com.intelram.shield.ui.theme.InkSoft
import com.intelram.shield.ui.theme.Surface

@Composable
fun SignInScreen(authViewModel: AuthViewModel, onContinue: () -> Unit) {
    val state by authViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(horizontal = 28.dp)
            .padding(top = 52.dp, bottom = 28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(Green),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Shield, contentDescription = null, tint = Color.White)
            }
            Text("  Threat Protection", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
        }

        Spacer(Modifier.height(36.dp))

        when (state) {
            is AuthUiState.SignedIn -> {
                val signedIn = state as AuthUiState.SignedIn
                SignedInContent(email = signedIn.email, isDemo = signedIn.isDemo, onContinue = onContinue)
            }
            else -> {
                SignedOutContent(
                    isLoading = state is AuthUiState.SigningIn,
                    errorMessage = (state as? AuthUiState.Error)?.message,
                    onGoogleClick = { authViewModel.signIn(context) },
                    onGuestClick = onContinue,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.SignedOutContent(
    isLoading: Boolean,
    errorMessage: String?,
    onGoogleClick: () -> Unit,
    onGuestClick: () -> Unit,
) {
    Text("Sign in to protect your world", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(10.dp))
    Text(
        "One tap with Google. We'll check if your email has ever appeared in a known data breach.",
        style = MaterialTheme.typography.bodyLarge,
        color = InkSoft,
    )
    Spacer(Modifier.height(28.dp))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Surface)
            .then(Modifier.border(1.5.dp, Border, RoundedCornerShape(16.dp)))
            .clickable(enabled = !isLoading, onClick = onGoogleClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp)
        } else {
            GoogleLogo()
            Text("  Continue with Google", style = MaterialTheme.typography.titleMedium)
        }
    }

    errorMessage?.let {
        Spacer(Modifier.height(10.dp))
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }

    Spacer(Modifier.height(16.dp))
    Text("or", color = InkFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    Spacer(Modifier.height(16.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Bg)
            .clickable(onClick = onGuestClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text("Continue as Guest", style = MaterialTheme.typography.titleMedium, color = InkSoft)
    }

    Spacer(Modifier.weight(1f))

    TrustRow(Icons.Filled.Lock, "Google handles your password — we never see or store it.")
    TrustRow(Icons.Filled.Shield, "Your data is encrypted in transit and at rest.")
    TrustRow(Icons.Filled.CheckCircle, "Disconnect your account anytime from Settings.")
}

@Composable
private fun TrustRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(modifier = Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = GreenDark, modifier = Modifier.size(18.dp))
        Text("  $text", style = MaterialTheme.typography.bodySmall, color = InkSoft)
    }
}

@Composable
private fun SignedInContent(email: String, isDemo: Boolean, onContinue: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 40.dp)) {
        Box(
            modifier = Modifier.size(84.dp).clip(CircleShape).background(GreenSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = GreenDark, modifier = Modifier.size(42.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("Welcome back", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text("Signed in as $email", style = MaterialTheme.typography.bodyMedium, color = InkSoft)
        if (isDemo) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Demo mode — add a Google OAuth client ID to enable real sign-in",
                style = MaterialTheme.typography.bodySmall,
                color = InkFaint,
            )
        }
        Spacer(Modifier.height(28.dp))
        PrimaryButton(text = "Continue to Dashboard", onClick = onContinue)
    }
}

@Composable
private fun GoogleLogo() {
    Icon(
        painter = androidx.compose.ui.res.painterResource(com.intelram.shield.R.drawable.ic_google_logo),
        contentDescription = null,
        tint = Color.Unspecified,
        modifier = Modifier.size(20.dp),
    )
}
