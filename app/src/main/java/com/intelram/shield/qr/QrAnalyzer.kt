package com.intelram.shield.qr

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** Decodes QR codes from CameraX frames entirely on-device via ZXing — no network call. */
class QrAnalyzer(private val onDecoded: (Result) -> Unit) : ImageAnalysis.Analyzer {

    private val reader = QRCodeReader()

    override fun analyze(imageProxy: ImageProxy) {
        try {
            val plane = imageProxy.planes[0]
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)

            val source = PlanarYUVLuminanceSource(
                data,
                imageProxy.width,
                imageProxy.height,
                0,
                0,
                imageProxy.width,
                imageProxy.height,
                false,
            )
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = reader.decode(bitmap)
            onDecoded(result)
        } catch (e: NotFoundException) {
            // No QR code in this frame — expected on most frames while scanning.
        } catch (e: Exception) {
            // Decoding hiccup on this frame; the next frame will retry.
        } finally {
            reader.reset()
            imageProxy.close()
        }
    }
}
