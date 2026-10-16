package io.github.labushuya.emberlight.feature.gallery.viewer

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.imageLoader
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.github.labushuya.emberlight.core.design.motion.sharedMediaBounds
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.core.media.thumbnail.MediaThumbnail

/**
 * Vorschaubild einer Seite als gemeinsames Element des Übergangs (ADR 0010).
 *
 * Das Bild liegt im Fit-Rechteck der Seite, also an der Stelle, an der das Vollbild es zeigt. Der Zuschnitt ist für Kachel und
 * Rechteck gleich (Crop), deshalb gibt es weder einen Sprung noch eine Verzerrung. Ist das Seitenverhältnis unbekannt, zeigt
 * die Seite das Bild ohne gemeinsames Element.
 *
 * [onError] meldet, wenn das Bild nicht lädt.
 */
@Composable
internal fun ViewerPreview(
    item: ViewerItem,
    onError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalPlatformContext.current
    val aspect = remember(item.id, item.previewKey, item.width, item.height) { previewAspect(context, item) }
    val request =
        remember(context, item.uri, item.type, item.previewKey) {
            ImageRequest
                .Builder(context)
                .data(MediaThumbnail(uri = item.uri, type = item.thumbnailType()))
                .memoryCacheKey(item.previewKey)
                .crossfade(false)
                .build()
        }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val fit = aspect?.let { fittedSize(maxWidth.value, maxHeight.value, it) }
        if (fit == null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onError = { _ -> onError() },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .size(fit.first.dp, fit.second.dp)
                        .sharedMediaBounds(key = viewerSharedKey(item.id)),
            ) {
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onError = { _ -> onError() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * Größtes Rechteck mit dem Seitenverhältnis [aspect] (Breite durch Höhe), das in [maxWidth] mal [maxHeight] passt. Das ist
 * dieselbe Fläche, die Telephoto für ein Bild mit `Fit` nutzt. `null`, wenn eine Angabe fehlt oder ungültig ist.
 */
internal fun fittedSize(
    maxWidth: Float,
    maxHeight: Float,
    aspect: Float,
): Pair<Float, Float>? {
    if (maxWidth <= 0f || maxHeight <= 0f || aspect <= 0f || aspect.isNaN() || aspect.isInfinite()) return null
    val widthAtFullHeight = maxHeight * aspect
    return if (widthAtFullHeight <= maxWidth) {
        widthAtFullHeight to maxHeight
    } else {
        maxWidth to maxWidth / aspect
    }
}

/**
 * Seitenverhältnis des Bildes. Vorrang hat das Vorschaubild im Speicher-Cache: Es ist nach der Drehung aus EXIF geladen. Die
 * Angaben des MediaStore sind nur der Ausweg, weil sie bei gedrehten Fotos vertauscht sein können.
 */
private fun previewAspect(
    context: Context,
    item: ViewerItem,
): Float? {
    val cached =
        item.previewKey?.let { key ->
            context.imageLoader.memoryCache
                ?.get(MemoryCache.Key(key))
                ?.image
        }
    if (cached != null && cached.width > 0 && cached.height > 0) {
        return cached.width.toFloat() / cached.height.toFloat()
    }
    val width = item.width
    val height = item.height
    return if (width != null && height != null && width > 0 && height > 0) {
        width.toFloat() / height.toFloat()
    } else {
        null
    }
}

private fun ViewerItem.thumbnailType(): MediaType =
    if (type ==
        ViewerMediaType.VIDEO
    ) {
        MediaType.VIDEO
    } else {
        MediaType.IMAGE
    }
