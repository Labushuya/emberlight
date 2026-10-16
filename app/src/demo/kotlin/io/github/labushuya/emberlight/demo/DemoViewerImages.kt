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
 * Speicher und wird einmal je Seite gezeichnet. Solange es fehlt, bleibt die Seite leer; die Vorschau trägt bis zum Öffnen.
 */
internal val DemoViewerImageSource: ViewerImageSource = { item, _, _ ->
    remember(item.id) { DemoZoomableImageSource(item) }
}

private class DemoZoomableImageSource(
    private val item: ViewerItem,
) : ZoomableImageSource {
    @Composable
    override fun resolve(canvasSize: Flow<Size>): ZoomableImageSource.ResolveResult {
        val bitmap: ImageBitmap? by
            produceState<ImageBitmap?>(initialValue = null, key1 = item.id) {
                value =
                    withContext(Dispatchers.Default) {
                        renderDemoBitmap(
                            item.id,
                            item.timestamp,
                            item.width,
                            item.height,
                            FULL_LONG_EDGE,
                        ).asImageBitmap()
                    }
            }
        return ZoomableImageSource.ResolveResult(
            delegate = bitmap?.let { ZoomableImageSource.PainterDelegate(BitmapPainter(it)) },
        )
    }
}
