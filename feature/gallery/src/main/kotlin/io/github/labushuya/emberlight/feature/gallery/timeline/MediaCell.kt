package io.github.labushuya.emberlight.feature.gallery.timeline

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.github.labushuya.emberlight.core.media.model.MediaItem
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.core.media.thumbnail.MediaThumbnail
import io.github.labushuya.emberlight.feature.gallery.R

/**
 * Eine quadratische Zelle des Rasters. Das Vorschaubild füllt die Zelle mit Zuschnitt. Videos zeigen unten ein
 * Abspiel-Symbol und die Dauer über einem dezenten Verlauf.
 *
 * [label] ist die vollständige Beschreibung für Screenreader. Die Zelle fasst Bild und Dauer zu einem Eintrag zusammen.
 */
@Composable
internal fun MediaCell(
    item: MediaItem,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalPlatformContext.current
    // Ohne feste Größe misst Coil die Zelle selbst. Die Anfrage ist pro Eintrag gleich, daher einmal gebaut.
    val request =
        remember(context, item.uri, item.type) {
            ImageRequest
                .Builder(context)
                .data(MediaThumbnail(uri = item.uri, type = item.type))
                .crossfade(false)
                .build()
        }
    val placeholderColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val placeholder = remember(placeholderColor) { ColorPainter(placeholderColor) }

    Box(
        modifier =
            modifier
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.extraSmall)
                .clickable(onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = label },
    ) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            placeholder = placeholder,
            error = placeholder,
            modifier = Modifier.fillMaxSize(),
        )
        if (item.type == MediaType.VIDEO) {
            VideoBadge(durationText = formatDuration(item.durationMs ?: 0L))
        }
    }
}

@Composable
private fun VideoBadge(
    durationText: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, SCRIM))),
        )
        Row(
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_play_arrow),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = durationText,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

private val SCRIM = Color(0xB3000000)
