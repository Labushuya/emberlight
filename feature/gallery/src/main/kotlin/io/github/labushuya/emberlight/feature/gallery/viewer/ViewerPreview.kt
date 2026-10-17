package io.github.labushuya.emberlight.feature.gallery.viewer

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import coil3.BitmapImage
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.imageLoader
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.core.media.thumbnail.MediaThumbnail

/**
 * Vorschaubild einer Seite, solange das Original nicht bereit ist. Es liegt im Fit-Rechteck der Seite, dort also, wo das
 * Vollbild es zeigt. [onError] meldet, wenn das Bild nicht lädt. [onReady] meldet, wenn das Bild geladen und damit gezeichnet
 * werden kann (Bereitschaft für die Hero-Animation, ADR 0010).
 */
@Composable
internal fun ViewerPreview(
    item: ViewerItem,
    onError: () -> Unit,
    modifier: Modifier = Modifier,
    onReady: () -> Unit = {},
) {
    val context = LocalPlatformContext.current
    val request =
        remember(context, item.uri, item.type, item.previewKey) {
            ImageRequest
                .Builder(context)
                .data(MediaThumbnail(uri = item.uri, type = item.thumbnailType()))
                .memoryCacheKey(item.previewKey)
                .crossfade(false)
                .build()
        }
    Box(modifier = modifier.fillMaxSize()) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            onError = { _ -> onError() },
            onSuccess = { _ -> onReady() },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Das Vorschaubild aus dem Speicher-Cache als Bitmap für die Hero-Animation. `null`, wenn es dort nicht liegt. Dann blendet
 * der Übergang nur ein (ADR 0010). Die Bitmap ist dieselbe, die die Kachel zeigt, daher gibt es keinen Wechsel beim Übergang.
 */
internal fun cachedPreviewBitmap(
    context: Context,
    key: String?,
): ImageBitmap? {
    if (key == null) return null
    val image =
        context.imageLoader.memoryCache
            ?.get(MemoryCache.Key(key))
            ?.image ?: return null
    return (image as? BitmapImage)?.bitmap?.asImageBitmap()
}

/**
 * Seitenverhältnis des Bildes. Vorrang hat das Vorschaubild im Speicher-Cache: Es ist nach der Drehung aus EXIF geladen. Die
 * Angaben des MediaStore sind nur der Ausweg, weil sie bei gedrehten Fotos vertauscht sein können.
 */
internal fun previewAspect(
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
