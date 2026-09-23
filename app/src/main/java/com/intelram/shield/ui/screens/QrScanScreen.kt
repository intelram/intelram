package com.intelram.shield.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.intelram.shield.qr.LinkInspectionResult
import com.intelram.shield.qr.LinkInspector
import com.intelram.shield.qr.QrAnalyzer
import com.intelram.shield.ui.components.LinkResultCard
import com.intelram.shield.ui.components.PrimaryButton
import com.intelram.shield.ui.theme.Bg
import com.intelram.shield.ui.theme.GreenDark
import com.intelram.shield.ui.theme.InkFaint

private sealed interface ScanState {
    data object Scanning : ScanState
    data class Checking(val payload: String) : ScanState
    data class Done(val result: LinkInspectionResult) : ScanState
}

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

    var state by remember { mutableStateOf<ScanState>(ScanState.Scanning) }

    LaunchedEffect(state) {
        val checking = state as? ScanState.Checking ?: return@LaunchedEffect
        val result = LinkInspector(context).inspect(checking.payload)
        state = ScanState.Done(result)
    }

    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.padding(24.dp, 48.dp, 24.dp, 8.dp)) {
            Text("QR Code Scanner", style = MaterialTheme.typography.headlineSmall)
            Text(
                "We check the link's redirects, certificate, and threat-intel feeds before you visit it",
                style = MaterialTheme.typography.bodySmall,
                color = InkFaint,
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (val s = state) {
                is ScanState.Done -> LinkResultCard(s.result) { state = ScanState.Scanning }
                is ScanState.Checking -> CheckingCard()
                ScanState.Scanning -> if (!hasPermission) {
                    PermissionPrompt { permissionLauncher.launch(Manifest.permission.CAMERA) }
                } else {
                    CameraPreview(onDecoded = { payload -> state = ScanState.Checking(payload) })
                }
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
        Spacer(Modifier.height(16.dp))
        PrimaryButton(text = "Allow Camera", onClick = onRequest)
    }
}

@Composable
private fun CheckingCard() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
        CircularProgressIndicator(color = GreenDark)
        Spacer(Modifier.height(16.dp))
        Text("Checking this link…", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Following redirects and checking the destination — nothing is opened or downloaded",
            style = MaterialTheme.typography.bodySmall,
            color = InkFaint,
            textAlign = TextAlign.Center,
        )
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
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

