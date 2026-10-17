package io.github.labushuya.emberlight.feature.gallery.timeline

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.core.media.thumbnail.MediaThumbnail

/**
 * Vorschaubild eines Eintrags, zugeschnitten auf die Fläche von [modifier]. Zellen des Rasters und Albumkarten nutzen
 * denselben Weg über Coil und [MediaThumbnail], damit beide dieselben Bilder und denselben Platzhalter zeigen.
 */
@Composable
internal fun MediaThumbnailImage(
    uri: String,
    type: MediaType,
    modifier: Modifier = Modifier,
) {
    val context = LocalPlatformContext.current
    // Ohne feste Größe misst Coil die Fläche selbst. Die Anfrage ist pro Eintrag gleich, daher einmal gebaut.
    val request =
        remember(context, uri, type) {
            val thumbnail = MediaThumbnail(uri = uri, type = type)
            ImageRequest
                .Builder(context)
                .data(thumbnail)
                // Fester Schlüssel: Der Vollbild-Viewer findet dieses Vorschaubild im Speicher-Cache wieder (ADR 0010).
                .memoryCacheKey(thumbnail.memoryCacheKey)
                .crossfade(false)
                .build()
        }
    val placeholderColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val placeholder = remember(placeholderColor) { ColorPainter(placeholderColor) }

    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        placeholder = placeholder,
        error = placeholder,
        modifier = modifier,
    )
}
