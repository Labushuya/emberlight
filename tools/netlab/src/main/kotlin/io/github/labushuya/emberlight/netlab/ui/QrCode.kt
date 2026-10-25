package io.github.labushuya.emberlight.netlab.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Zeichnet einen QR-Code als Bild. Der Text ist die Bildbeschreibung für Screenreader. */
@Composable
fun QrCodeImage(
    content: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(content) { encodeQr(content, QR_SIZE_PX) }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = description,
        modifier = modifier.size(QR_SIZE_DP.dp),
    )
}

/** Kamera-Vorschau mit QR-Erkennung. Liefert den ersten gelesenen Text genau einmal an [onResult]. */
@Composable
fun QrScanner(
    onResult: (String) -> Unit,
    onUnavailable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val delivered = remember { AtomicBoolean(false) }
    val provider = remember { mutableListOf<ProcessCameraProvider>() }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val view = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener(
                {
                    try {
                        val cameraProvider = future.get()
                        provider += cameraProvider
                        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                        val analysis =
                            ImageAnalysis
                                .Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                                .also { useCase ->
                                    // Die Erkennung läuft auf einem Hintergrund-Thread. Das Ergebnis geht zurück auf den Hauptthread.
                                    val mainExecutor = ContextCompat.getMainExecutor(ctx)
                                    useCase.setAnalyzer(analysisExecutor) { image ->
                                        decodeOnce(image, delivered) { text -> mainExecutor.execute { onResult(text) } }
                                    }
                                }
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                    } catch (e: Exception) {
                        // Keine Kamera oder belegt. Die manuelle Eingabe bleibt verfügbar.
                        Log.w(LOG_TAG, "Kamera nicht verfügbar: ${e.javaClass.simpleName}")
                        onUnavailable()
                    }
                },
                ContextCompat.getMainExecutor(ctx),
            )
            view
        },
    )

    DisposableEffect(Unit) {
        onDispose {
            provider.forEach { it.unbindAll() }
            provider.clear()
            analysisExecutor.shutdownNow()
        }
    }
}

private fun decodeOnce(
    image: ImageProxy,
    delivered: AtomicBoolean,
    onResult: (String) -> Unit,
) {
    try {
        if (delivered.get()) return
        val plane = image.planes.firstOrNull() ?: return
        val width = image.width
        val height = image.height
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val luminance = ByteArray(width * height)
        val row = ByteArray(rowStride)
        for (y in 0 until height) {
            val offset = y * rowStride
            val available = minOf(rowStride, buffer.limit() - offset)
            if (available < width) break
            buffer.position(offset)
            buffer.get(row, 0, available)
            System.arraycopy(row, 0, luminance, y * width, width)
        }
        val source = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
        val binary = BinaryBitmap(HybridBinarizer(source))
        val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
        val text = MultiFormatReader().decode(binary, hints).text
        if (delivered.compareAndSet(false, true)) onResult(text)
    } catch (_: NotFoundException) {
        // Auf diesem Bild kein Code. Weiter mit dem nächsten Bild.
    } finally {
        image.close()
    }
}

/** QR-Code als Bitmap. Fehlerkorrektur M, quadratisch. */
internal fun encodeQr(
    content: String,
    size: Int,
): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val pixels =
        IntArray(size * size) { index ->
            if (matrix.get(index % size, index / size)) BLACK else WHITE
        }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

private const val QR_SIZE_PX = 640
private const val QR_SIZE_DP = 280
private const val BLACK = -0x1000000
private const val WHITE = -0x1
private const val LOG_TAG = "EmberlightNetlab"
