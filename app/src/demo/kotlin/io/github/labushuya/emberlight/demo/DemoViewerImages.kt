package io.github.labushuya.emberlight.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import io.github.labushuya.emberlight.feature.gallery.viewer.ViewerImageSource
import io.github.labushuya.emberlight.feature.gallery.viewer.ViewerImageSourceContribution
import io.github.labushuya.emberlight.feature.gallery.viewer.ViewerItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.ZoomableImageSource
import javax.inject.Inject

/**
 * Liefert für die synthetischen Einträge das Vollbild (ADR 0011). Andere Einträge bleiben bei der Standardquelle. Im Release-Build
 * gibt es diesen Beitrag nicht.
 */
internal class DemoViewerImageContribution
    @Inject
    constructor() : ViewerImageSourceContribution {
        override fun sourceFor(item: ViewerItem): ViewerImageSource? =
            if (SyntheticLibrary.indexOf(item.uri) != null) DemoViewerImageSource else null
    }

/**
 * Vollbild eines synthetischen Eintrags: dasselbe Motiv wie das Vorschaubild, in [FULL_LONG_EDGE] Pixel. Das Bild entsteht im
 * Speicher und wird einmal je Seite gezeichnet. Solange es fehlt, trägt der Platzhalter: die Vorschau in Bildschirmgröße, sonst
 * das Vorschaubild.
 */
internal val DemoViewerImageSource: ViewerImageSource = { item, _, screen, _ ->
    remember(item.id, screen) { DemoZoomableImageSource(item, screen) }
}

/**
 * Die Seite zeigt zuerst die Vorschau in Bildschirmgröße (wie das System für echte Medien), sonst das Vorschaubild in
 * Thumbnail-Größe. Erst wenn das Vollbild gerendert ist, ersetzt es den Platzhalter. So bleibt nie eine leere Seite stehen
 * (ADR 0010, Feinschliff). Die Vorschau in Bildschirmgröße ist ein Platzhalter, keine Quelle: sie wird nie abgebildet.
 */
private class DemoZoomableImageSource(
    private val item: ViewerItem,
    private val screen: ImageBitmap?,
) : ZoomableImageSource {
    @Composable
    override fun resolve(canvasSize: Flow<Size>): ZoomableImageSource.ResolveResult {
        val thumbnail: ImageBitmap? by
            produceState<ImageBitmap?>(initialValue = null, key1 = item.id) {
                value = withContext(Dispatchers.Default) { render(THUMBNAIL_LONG_EDGE) }
            }
        val bitmap: ImageBitmap? by
            produceState<ImageBitmap?>(initialValue = null, key1 = item.id) {
                value = withContext(Dispatchers.Default) { render(FULL_LONG_EDGE) }
            }
        val placeholder = screen ?: thumbnail
        return ZoomableImageSource.ResolveResult(
            delegate = bitmap?.let { ZoomableImageSource.PainterDelegate(BitmapPainter(it)) },
            placeholder = placeholder?.let { BitmapPainter(it) },
        )
    }

    private fun render(longEdge: Int): ImageBitmap =
        renderDemoBitmap(item.id, item.timestamp, item.width, item.height, longEdge).asImageBitmap()
}
