package io.github.labushuya.emberlight.feature.gallery.viewer

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import coil3.BitmapImage
import coil3.compose.asPainter
import coil3.imageLoader
import coil3.memory.MemoryCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import me.saket.telephoto.subsamplingimage.ImageBitmapOptions
import me.saket.telephoto.subsamplingimage.SubSamplingImageSource
import me.saket.telephoto.zoomable.ZoomableImageSource

/**
 * Bildquelle für das Original über eine Content-URI (ADR 0010).
 *
 * Telephoto liest große Bilder kachelweise direkt vom ContentResolver. Der Weg über Coil würde das Original in den
 * Disk-Cache schreiben, das verbietet ADR 0008. [onFailed] meldet, wenn die Datei fehlt oder kein Bild ist, dann bleibt
 * es bei der Fehlermeldung der Seite.
 *
 * Bis das Original bereit ist, zeigt die Quelle das Vorschaubild aus dem Speicher-Cache als Platzhalter (und als
 * Vorschau für die Kacheln), damit nichts aufblitzt. Das hochauflösende Bild (Subsampling) bleibt bis zum Ende des
 * Übergangs aus ([settled] ist `false`), damit der Übergang nicht von Kacheln unterbrochen wird.
 */
internal class ContentUriImageSource(
    private val uri: String,
    private val previewKey: String?,
    private val screen: ImageBitmap?,
    private val onFailed: () -> Unit,
    private val settled: State<Boolean>,
) : ZoomableImageSource {
    @Composable
    override fun resolve(canvasSize: Flow<Size>): ZoomableImageSource.ResolveResult {
        val context = LocalContext.current
        val preview = remember(previewKey, context) { previewOf(context, previewKey) }
        // Die Vorschau in Bildschirmgröße ist der Platzhalter, sobald sie da ist. Sonst bleibt das Raster-Vorschaubild.
        val placeholder = screen?.let { BitmapPainter(it) } ?: preview?.painter
        val source by produceState<SubSamplingImageSource?>(initialValue = null, key1 = uri) {
            val readable = withContext(Dispatchers.IO) { isDecodableImage(context, uri) }
            val created =
                if (readable) {
                    SubSamplingImageSource.contentUriOrNull(uri.toUri(), screen ?: preview?.bitmap)
                } else {
                    null
                }
            if (created == null) onFailed() else value = created
        }
        val ready = settled.value
        val current = source
        return ZoomableImageSource.ResolveResult(
            delegate =
                if (current != null && ready) {
                    ZoomableImageSource.SubSamplingDelegate(
                        source = current,
                        imageOptions = ImageBitmapOptions.Default,
                    )
                } else {
                    null
                },
            placeholder = placeholder,
        )
    }

    private class Preview(
        val painter: Painter,
        val bitmap: ImageBitmap?,
    )

    private companion object {
        /** Vorschaubild aus dem Speicher-Cache, falls das Raster es geladen hat. */
        fun previewOf(
            context: Context,
            key: String?,
        ): Preview? {
            if (key == null) return null
            val image =
                context.imageLoader.memoryCache
                    ?.get(MemoryCache.Key(key))
                    ?.image ?: return null
            return Preview(
                painter = image.asPainter(context),
                bitmap = (image as? BitmapImage)?.bitmap?.asImageBitmap(),
            )
        }

        /**
         * Prüft, ob die URI ein erkennbares Bild liefert. Liest nur den Kopf der Datei: Maße und MIME-Typ entstehen beim
         * Erkennen des Kopfes. Fehlende Datei, entzogene Berechtigung, fremde URIs und Dateien, die kein Bild sind, zählen
         * alle als nicht lesbar.
         */
        fun isDecodableImage(
            context: Context,
            uri: String,
        ): Boolean =
            runCatching {
                context.contentResolver.openInputStream(uri.toUri())?.use { stream ->
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(stream, null, options)
                    options.outWidth > 0 && options.outHeight > 0 && options.outMimeType != null
                } ?: false
            }.getOrDefault(false)
    }
}
