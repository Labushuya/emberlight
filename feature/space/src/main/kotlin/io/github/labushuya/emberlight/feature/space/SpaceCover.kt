package io.github.labushuya.emberlight.feature.space

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import io.github.labushuya.emberlight.core.design.theme.SpaceAccents
import java.io.File

/** Kante der Vorschau in Pixeln. Coil dekodiert nur so groß, Titelbilder können bis 50 MiB groß sein. */
internal const val PREVIEW_MAX_PIXELS = 512

/**
 * Titelbild eines Space, oder die Akzentfarbe, wenn keins da ist. Das Bild lädt Coil aus der Datei im Space-Speicher:
 * verkleinert dekodiert, mit Cache, abbrechbar, und mit der EXIF-Ausrichtung. Die Beschreibung kommt von außen, weil sie
 * den Namen des Space enthält. Fehlt die Datei oder ist sie unlesbar, bleibt die Akzentfarbe.
 */
@Composable
internal fun CoverImage(
    file: File?,
    colorIndex: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(CORNER_RADIUS),
) {
    val accent = SpaceAccents.colorAt(colorIndex)
    val context = LocalPlatformContext.current
    val request =
        remember(context, file) {
            file?.let {
                ImageRequest
                    .Builder(context)
                    .data(it)
                    .size(PREVIEW_MAX_PIXELS)
                    .build()
            }
        }
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
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

internal val CORNER_RADIUS: Dp = 12.dp
