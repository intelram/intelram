package com.intelram.shield.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.intelram.shield.qr.LinkSafety
import com.intelram.shield.qr.QrAnalyzer
import com.intelram.shield.qr.QrLinkHeuristic
import com.intelram.shield.ui.components.PrimaryButton
import com.intelram.shield.ui.theme.Bg
import com.intelram.shield.ui.theme.Green
import com.intelram.shield.ui.theme.GreenDark
import com.intelram.shield.ui.theme.GreenSoft
import com.intelram.shield.ui.theme.InkFaint
import com.intelram.shield.ui.theme.InkSoft
import com.intelram.shield.ui.theme.Red
import com.intelram.shield.ui.theme.RedSoft
import com.intelram.shield.ui.theme.Surface

@Composable
fun QrScanScreen() {
    val context = LocalContext.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    var result by remember { mutableStateOf<LinkSafety?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.padding(24.dp, 48.dp, 24.dp, 8.dp)) {
            Text("QR Code Scanner", style = MaterialTheme.typography.headlineSmall)
            Text("We check every code before you visit it", style = MaterialTheme.typography.bodySmall, color = InkFaint)
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                result != null -> QrResultCard(result!!) { result = null }
                !hasPermission -> PermissionPrompt { permissionLauncher.launch(Manifest.permission.CAMERA) }
                else -> CameraPreview(onDecoded = { payload -> result = QrLinkHeuristic.evaluate(payload) })
            }
        }
    }
}

@Composable
private fun PermissionPrompt(onRequest: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
        Text(
            "Camera access is needed to scan a code",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
        PrimaryButton(text = "Allow Camera", onClick = onRequest)
    }
}

@Composable
private fun CameraPreview(onDecoded: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var decodedOnce by remember { mutableStateOf(false) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(Unit) {
        onDispose { cameraProvider?.unbindAll() }
    }

    Box(contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier
                .size(280.dp)
                .clip(RoundedCornerShape(24.dp)),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val provider = cameraProviderFuture.get()
                    cameraProvider = provider
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(ContextCompat.getMainExecutor(ctx), QrAnalyzer { result ->
                                if (!decodedOnce) {
                                    decodedOnce = true
                                    provider.unbindAll()
                                    onDecoded(result.text)
                                }
                            })
                        }
                    try {
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                    } catch (e: Exception) {
                        // Camera unavailable on this device/emulator — preview stays blank.
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
        )
        Text(
            "Point your camera at a QR code",
            color = androidx.compose.ui.graphics.Color.White,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun QrResultCard(result: LinkSafety, onReset: () -> Unit) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
    ) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (result) {
                is LinkSafety.Suspicious -> {
                    ResultBadge(safe = false)
                    Text("This code isn't safe", style = MaterialTheme.typography.headlineSmall, color = Red)
                    androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                    result.reasons.forEach {
                        Text("• $it", style = MaterialTheme.typography.bodySmall, color = InkSoft, textAlign = TextAlign.Center)
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.height(14.dp))
                    Text(result.host, style = MaterialTheme.typography.bodySmall, color = InkFaint)
                    androidx.compose.foundation.layout.Spacer(Modifier.height(18.dp))
                    PrimaryButton(text = "Don't Open — Scan Again", onClick = onReset)
                    androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
                    Text(
                        "Open Anyway (not recommended)",
                        color = Red,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp).then(
                            Modifier.clickableOpen(context, result.url),
                        ),
                    )
                }
                is LinkSafety.Safe -> {
                    ResultBadge(safe = true)
                    Text("This code is safe", style = MaterialTheme.typography.headlineSmall, color = GreenDark)
                    androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                    Text(
                        "No red flags found — a heuristic check, not a live database.",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSoft,
                        textAlign = TextAlign.Center,
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
                    Text(result.host, style = MaterialTheme.typography.bodySmall, color = InkFaint)
                    androidx.compose.foundation.layout.Spacer(Modifier.height(18.dp))
                    PrimaryButton(text = "Open Link", onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.url)))
                    })
                    androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
                    Text(
                        "Scan another code",
                        color = InkFaint,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                LinkSafety.PlainText -> {
                    Text("Code scanned", style = MaterialTheme.typography.headlineSmall)
                    androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                    Text("This isn't a web link, so there's nothing to check for safety.", style = MaterialTheme.typography.bodySmall, color = InkSoft, textAlign = TextAlign.Center)
                    androidx.compose.foundation.layout.Spacer(Modifier.height(18.dp))
                    PrimaryButton(text = "Scan Again", onClick = onReset)
                }
            }
        }
    }
}

@Composable
private fun ResultBadge(safe: Boolean) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (safe) GreenSoft else RedSoft),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (safe) "✓" else "!", style = MaterialTheme.typography.headlineMedium, color = if (safe) GreenDark else Red)
    }
}

private fun Modifier.clickableOpen(context: android.content.Context, url: String): Modifier =
    this.clickable {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
