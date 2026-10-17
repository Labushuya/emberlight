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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.labushuya.emberlight.core.media.model.MediaItem
import io.github.labushuya.emberlight.core.media.model.MediaType
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
    // Die Kachel meldet ihre Grenzen für die Hero-Animation des Overlays (ADR 0010). Ohne Raster ist die Tabelle wegwerfbar.
    val cells = LocalCellBounds.current
    DisposableEffect(item.id) { onDispose { cells.remove(item.id) } }
    // Test-Seam (siehe GridProbe): ohne Probe keine Effekte.
    GridProbeHolder.current?.let { probe ->
        SideEffect { probe.cellComposed(item.uri) }
        DisposableEffect(item.uri) {
            probe.cellEntered(item.uri)
            onDispose { probe.cellLeft(item.uri) }
        }
    }
    Box(
        modifier =
            modifier
                .aspectRatio(1f)
                .onGloballyPositioned { coords -> cells.put(item.id, unclippedRootRect(coords)) }
                .clip(MaterialTheme.shapes.extraSmall)
                .clickable(onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = label },
    ) {
        MediaThumbnailImage(uri = item.uri, type = item.type, modifier = Modifier.fillMaxSize())
        if (item.type == MediaType.VIDEO) {
            VideoBadge(durationText = formatDuration(item.durationMs ?: 0L))
        }
    }
}

/**
 * Ganze Kachel in Root-Koordinaten, ohne Abschnitt durch Kopfzeile oder Raster. `boundsInRoot` liefert dagegen nur den
 * sichtbaren Teil einer teilweise abgeschnittenen Kachel (ADR 0010, Feinschliff). Die Hero-Animation braucht das ganze Rechteck.
 */
internal fun unclippedRootRect(coords: LayoutCoordinates): Rect =
    Rect(coords.positionInRoot(), Size(coords.size.width.toFloat(), coords.size.height.toFloat()))

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

internal val SCRIM = Color(0xB3000000)
