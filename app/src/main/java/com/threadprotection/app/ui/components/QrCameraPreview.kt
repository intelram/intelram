package com.threadprotection.app.ui.components

import android.util.Log
import android.view.MotionEvent
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import android.util.Size
import java.util.concurrent.Executors

/**
 * Live CameraX preview + on-device ML Kit QR decoding.
 *
 * Decoding happens entirely on-device; only a decoded *web address* is ever sent out, and only to
 * the reputation sources the user configured in Settings — see QrContentClassifier, which is what
 * decides whether anything leaves the phone at all.
 *
 * ## Why this was slow, and what changed
 *
 * The previous version passed `ContextCompat.getMainExecutor(ctx)` to `setAnalyzer`, so every
 * camera frame was converted and run through the detector **on the main thread**. That is the root
 * cause of the scanner feeling sluggish: barcode detection competed with Compose recomposition and
 * touch handling for the UI thread, so the preview stuttered *and* the effective frame rate
 * reaching the detector collapsed — fewer frames analysed per second means fewer chances to catch
 * a marginal code. Analysis now runs on its own single-thread executor, leaving the UI thread free.
 *
 * The other three changes each target a specific failure mode reported as "doesn't scan":
 *  - **Resolution.** No resolution was requested, so CameraX picked its default (commonly 640×480).
 *    A dense (high-version) QR code, or any small or distant one, simply does not survive that
 *    few pixels per module. A 1280×720 target keeps enough detail to decode them.
 *  - **Distance.** `setZoomSuggestionOptions` lets ML Kit tell the app when everything in frame is
 *    too far to decode, and the app drives the real camera zoom to the suggested ratio. This is the
 *    supported fix for "QR code far from the camera" and needs no image processing of our own.
 *  - **Blur.** Tap-to-focus triggers a real autofocus/metering cycle at the tapped point, which is
 *    the fastest route out of a soft frame on a close-up code.
 */
@Composable
fun QrCameraPreview(
    modifier: Modifier = Modifier,
    torchEnabled: Boolean = false,
    onDecoded: (String) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val onDecodedState = rememberUpdatedState(onDecoded)
    val torchState = rememberUpdatedState(torchEnabled)

    // Held across recompositions so the camera isn't rebuilt when an unrelated bit of state moves.
    val cameraControl = remember { arrayOfNulls<CameraControl>(1) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                // Returns codes ML Kit can see but not yet decode, which is what makes the
                // zoom suggestion below fire for a code that is present but too small.
                .enableAllPotentialBarcodes()
                .setZoomSuggestionOptions(
                    ZoomSuggestionOptions.Builder { ratio ->
                        val control = cameraControl[0] ?: return@Builder false
                        runCatching { control.setZoomRatio(ratio) }
                            .onFailure { Log.w(TAG, "zoom suggestion $ratio rejected by camera", it) }
                            .isSuccess
                    }.setMaxSupportedZoomRatio(MAX_AUTO_ZOOM).build(),
                )
                .build(),
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            scanner.close()
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            // Guards against re-reporting the same code dozens of times a second while it stays in
            // frame, without blocking a deliberate re-scan of the same code later.
            var lastDecoded: String? = null
            var lastDecodedAt = 0L

            cameraProviderFuture.addListener({
                val cameraProvider = runCatching { cameraProviderFuture.get() }.getOrElse { e ->
                    Log.e(TAG, "Camera provider unavailable", e)
                    return@addListener
                }
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }

                val resolutionSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(ANALYSIS_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    )
                    .build()

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(resolutionSelector)
                    .build()

                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    val mediaImage = imageProxy.image
                    if (mediaImage == null) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                    scanner.process(image)
                        .addOnSuccessListener { barcodes ->
                            // Several codes can share a frame (a poster of them, a screen). Take the
                            // largest decoded one: it is the one the user is most likely aiming at,
                            // and it is the most reliably decoded. Undecoded entries are present
                            // too (enableAllPotentialBarcodes) and are skipped here — their job is
                            // to drive the zoom suggestion, not to produce a result.
                            val value = barcodes
                                .filter { !it.rawValue.isNullOrBlank() }
                                .maxByOrNull { b -> b.boundingBox?.let { it.width() * it.height() } ?: 0 }
                                ?.rawValue
                            val now = System.currentTimeMillis()
                            if (value != null && (value != lastDecoded || now - lastDecodedAt > RESCAN_COOLDOWN_MS)) {
                                lastDecoded = value
                                lastDecodedAt = now
                                ContextCompat.getMainExecutor(ctx).execute { onDecodedState.value(value) }
                            }
                        }
                        .addOnFailureListener { e -> Log.w(TAG, "Barcode decode failed", e) }
                        .addOnCompleteListener { imageProxy.close() }
                }

                runCatching {
                    cameraProvider.unbindAll()
                    val camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                    cameraControl[0] = camera.cameraControl
                    if (camera.cameraInfo.hasFlashUnit()) {
                        camera.cameraControl.enableTorch(torchState.value)
                    }

                    // Tap to focus. A soft frame on a close-up code is the most common cause of a
                    // code that "won't scan", and a metering cycle at the tapped point clears it
                    // far faster than waiting for continuous autofocus to settle on its own.
                    previewView.setOnTouchListener { view, event ->
                        if (event.action != MotionEvent.ACTION_UP) return@setOnTouchListener true
                        val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                        runCatching {
                            camera.cameraControl.startFocusAndMetering(
                                FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                                    .setAutoCancelDuration(AUTOFOCUS_HOLD_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                                    .build(),
                            )
                        }.onFailure { Log.w(TAG, "focus/metering failed", it) }
                        view.performClick()
                        true
                    }
                }.onFailure { e -> Log.e(TAG, "Failed to bind camera", e) }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
        update = {
            // Torch is driven here rather than in factory{} so toggling it doesn't rebuild the
            // camera — rebinding drops frames and visibly stalls the preview.
            cameraControl[0]?.let { control ->
                runCatching { control.enableTorch(torchState.value) }
                    .onFailure { Log.w(TAG, "torch toggle failed (no flash unit?)", it) }
            }
        },
    )
}

private const val TAG = "QrCameraPreview"

/**
 * 1280×720 for analysis. The default (often 640×480) leaves too few pixels per module to decode a
 * dense or distant code; going much higher costs conversion time per frame for little gain.
 */
private val ANALYSIS_SIZE = Size(1280, 720)

/** Cap on the zoom ML Kit may ask for — beyond this the image is upscaled mush, not detail. */
private const val MAX_AUTO_ZOOM = 4f

/** How long the same code is suppressed before it may be reported again. */
private const val RESCAN_COOLDOWN_MS = 3_000L

private const val AUTOFOCUS_HOLD_SECONDS = 4L
