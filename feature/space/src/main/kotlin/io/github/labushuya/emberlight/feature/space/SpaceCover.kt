package io.github.labushuya.emberlight.feature.space

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.labushuya.emberlight.core.design.theme.SpaceAccents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Kante der Vorschau in Pixeln. Größer dekodiert wird nicht, Titelbilder können bis 50 MiB groß sein. */
private const val PREVIEW_MAX_PIXELS = 512

/**
 * Titelbild eines Space, oder die Akzentfarbe, wenn keins da ist. Das Bild wird verkleinert dekodiert. Die Beschreibung
 * kommt von außen, weil sie den Namen des Space enthält.
 */
@Composable
internal fun CoverImage(
    file: File?,
    colorIndex: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(CORNER_RADIUS),
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, file) {
        value = file?.let { withContext(Dispatchers.IO) { decodePreview(it) } }
    }
    val accent = SpaceAccents.colorAt(colorIndex)
    Box(
        modifier =
            modifier
                .clip(shape)
                .background(accent)
                .then(
                    if (contentDescription !=
                        null
                    ) {
                        Modifier.semantics { this.contentDescription = contentDescription }
                    } else {
                        Modifier
                    },
                ),
    ) {
        bitmap?.let { image ->
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Dekodiert [file] mit einer Kante von höchstens [PREVIEW_MAX_PIXELS] Pixeln. Unlesbare Dateien ergeben `null`. */
internal fun decodePreview(file: File): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= PREVIEW_MAX_PIXELS &&
        bounds.outHeight / (sample * 2) >= PREVIEW_MAX_PIXELS
    ) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
}

internal val CORNER_RADIUS: Dp = 12.dp
