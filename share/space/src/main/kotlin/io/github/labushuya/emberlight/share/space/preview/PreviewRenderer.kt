package io.github.labushuya.emberlight.share.space.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Erzeugt die Vorschau-JPEGs und liest den Video-Codec (ADR 0019). Die Funktionen blockieren und laufen deshalb auf dem
 * Hintergrundfaden des Worker. Sie schreiben nur nach [target] und liefern bei jedem Fehler `false` bzw. `null`.
 */
interface PreviewRenderer {
    /** JPEG mit der langen Kante [longEdge] aus einem Bild. [mime] bestimmt, ob die Plattform dekodieren kann. */
    fun renderImage(
        source: File,
        mime: String,
        longEdge: Int,
        target: File,
    ): Boolean

    /** JPEG mit der langen Kante [longEdge] aus einem Frame des Videos. */
    fun renderVideoFrame(
        source: File,
        longEdge: Int,
        target: File,
    ): Boolean

    /** Video-Codec aus dem Kopf der Datei (`avc1`, `hvc1`, …), `null` wenn er nicht erkannt wird. */
    fun videoCodec(source: File): String?
}

/**
 * Umsetzung mit den Plattformdekodern. Ab API 28 [ImageDecoder] mit Zielgröße (dekodiert bereits subsampled und richtet
 * nach EXIF aus). Darunter [BitmapFactory] mit `inSampleSize` und Ausrichtung per [ExifInterface]. HEIC und HEIF brauchen
 * API 28, AVIF laut Plattform API 31. Darunter gibt es `failed` ohne Dekodierversuch.
 */
class AndroidPreviewRenderer : PreviewRenderer {
    override fun renderImage(
        source: File,
        mime: String,
        longEdge: Int,
        target: File,
    ): Boolean {
        if (!platformCanDecode(mime)) return false
        return guarded {
            val bitmap = decodeImage(source, longEdge) ?: return@guarded false
            writeJpeg(bitmap, longEdge, target)
        }
    }

    override fun renderVideoFrame(
        source: File,
        longEdge: Int,
        target: File,
    ): Boolean =
        guarded {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(source.path)
                val width = retriever.metadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val height = retriever.metadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                val rotation = retriever.metadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                if (width <= 0 || height <= 0) return@guarded false
                val quarterTurn = rotation == 90 || rotation == 270
                val orientedWidth = if (quarterTurn) height else width
                val orientedHeight = if (quarterTurn) width else height
                if (PreviewRules.exceedsPixelLimit(orientedWidth, orientedHeight)) return@guarded false
                val duration = retriever.metadataLong(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val (targetWidth, targetHeight) = PreviewRules.targetSize(orientedWidth, orientedHeight, longEdge)
                val frame =
                    frameAt(retriever, PreviewRules.seekMicros(duration), targetWidth, targetHeight)
                        ?: frameAt(retriever, 0L, targetWidth, targetHeight)
                        ?: return@guarded false
                val upright =
                    if (quarterTurn &&
                        frame.isLandscape() != (orientedWidth > orientedHeight)
                    ) {
                        rotate(frame, rotation)
                    } else {
                        frame
                    }
                writeJpeg(upright, longEdge, target)
            } finally {
                retriever.release()
            }
        }

    override fun videoCodec(source: File): String? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(source.path)
            (0 until extractor.trackCount)
                .firstNotNullOfOrNull { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.takeIf { PreviewRules.isVideo(it) }
                }?.let { PreviewRules.codecOf(it) }
        } catch (unreadable: IOException) {
            null
        } catch (unreadable: RuntimeException) {
            null
        } finally {
            extractor.release()
        }
    }

    private fun platformCanDecode(mime: String): Boolean =
        when (mime) {
            "image/heic", "image/heif" -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            "image/avif" -> Build.VERSION.SDK_INT >= AVIF_MIN_SDK
            else -> true
        }

    private fun decodeImage(
        source: File,
        longEdge: Int,
    ): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decodeWithImageDecoder(source, longEdge)
        } else {
            decodeWithBitmapFactory(source, longEdge)
        }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeWithImageDecoder(
        source: File,
        longEdge: Int,
    ): Bitmap? {
        val imageSource = ImageDecoder.createSource(source)
        return ImageDecoder.decodeBitmap(imageSource) { decoder, info, _ ->
            val size = info.size
            if (PreviewRules.exceedsPixelLimit(size.width, size.height)) throw PixelLimitExceededException()
            val (width, height) = PreviewRules.targetSize(size.width, size.height, longEdge)
            decoder.setTargetSize(width, height)
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
        }
    }

    private fun decodeWithBitmapFactory(
        source: File,
        longEdge: Int,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0 || PreviewRules.exceedsPixelLimit(width, height)) return null
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = PreviewRules.sampleSize(width, height, longEdge)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        val decoded = BitmapFactory.decodeFile(source.path, options) ?: return null
        return applyExifOrientation(source, decoded)
    }

    private fun applyExifOrientation(
        source: File,
        bitmap: Bitmap,
    ): Bitmap {
        val orientation =
            ExifInterface(source).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_180 -> {
                matrix.postRotate(180f)
            }

            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.postScale(1f, -1f)
            }

            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_90 -> {
                matrix.postRotate(90f)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_270 -> {
                matrix.postRotate(270f)
            }

            else -> {
                return bitmap
            }
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun frameAt(
        retriever: MediaMetadataRetriever,
        seekMicros: Long,
        width: Int,
        height: Int,
    ): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(seekMicros, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, height)
        } else {
            retriever.getFrameAtTime(seekMicros, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }

    /** Dreht um die Gradzahl, wenn der Frame noch im Rohformat vorliegt. */
    private fun rotate(
        frame: Bitmap,
        degrees: Int,
    ): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, matrix, true)
    }

    /**
     * Schreibt das Bild auf Weiß verflacht und in der Zielgröße als JPEG. Ohne Alpha, ohne EXIF, weil die Kodierung neu
     * entsteht. Das Ergebnis ist die Vorschau.
     */
    private fun writeJpeg(
        source: Bitmap,
        longEdge: Int,
        target: File,
    ): Boolean {
        val (width, height) = PreviewRules.targetSize(source.width, source.height, longEdge)
        val scaled =
            if (width == source.width && height == source.height) {
                source
            } else {
                Bitmap.createScaledBitmap(source, width, height, true)
            }
        val flat = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(flat).apply {
                drawColor(Color.WHITE)
                drawBitmap(scaled, 0f, 0f, null)
            }
            FileOutputStream(target).use { out ->
                return flat.compress(Bitmap.CompressFormat.JPEG, PreviewRules.JPEG_QUALITY, out)
            }
        } finally {
            if (scaled !== source) scaled.recycle()
            flat.recycle()
        }
    }

    private fun Bitmap.isLandscape(): Boolean = width > height

    private fun MediaMetadataRetriever.metadataInt(key: Int): Int = extractMetadata(key)?.toIntOrNull() ?: 0

    private fun MediaMetadataRetriever.metadataLong(key: Int): Long? = extractMetadata(key)?.toLongOrNull()

    /** Fängt jeden Fehler der Erzeugung. Ein Fehler heißt `failed`, kein Wiederholen (ADR 0019). */
    private inline fun guarded(block: () -> Boolean): Boolean =
        try {
            block()
        } catch (failure: Exception) {
            false
        } catch (memory: OutOfMemoryError) {
            false
        }

    private class PixelLimitExceededException : RuntimeException(null, null, false, false)

    private companion object {
        /** Erste Plattformversion mit AVIF-Dekodierung über [ImageDecoder]. */
        const val AVIF_MIN_SDK = 31
    }
}

/**
 * Hintergrundfaden des Worker: ein Faden mit niedriger Priorität, damit Vorschauen den Upload nicht bremsen (ADR 0019).
 * [close] beendet den Faden. Angebrochene Arbeit wird verworfen, der Index räumt sie beim nächsten Start auf.
 */
class PreviewWorkers : AutoCloseable {
    private val executor =
        Executors.newSingleThreadExecutor { task ->
            Thread(
                {
                    lowerPriority()
                    task.run()
                },
                THREAD_NAME,
            ).apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }

    /** Bereich für die Arbeit des Worker. Der Aufrufer startet den Worker damit. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())

    override fun close() {
        scope.cancel()
        executor.shutdown()
    }

    private fun lowerPriority() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        } catch (unsupported: RuntimeException) {
            // Ohne Wirkung auf die Erzeugung. Der Faden läuft mit der Priorität aus `priority`.
        }
    }

    private companion object {
        const val THREAD_NAME = "emberlight-previews"
    }
}
