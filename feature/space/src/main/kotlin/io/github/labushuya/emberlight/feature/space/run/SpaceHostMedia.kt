package io.github.labushuya.emberlight.feature.space.run

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import io.github.labushuya.emberlight.feature.space.R
import io.github.labushuya.emberlight.feature.space.formatDate
import io.github.labushuya.emberlight.feature.space.media.DetailMedia
import io.github.labushuya.emberlight.feature.space.media.HostMediaItem
import io.github.labushuya.emberlight.feature.space.media.HostMediaUiState
import io.github.labushuya.emberlight.feature.space.media.MediaFilter
import io.github.labushuya.emberlight.share.space.preview.PreviewState
import java.io.File

/**
 * Medienansicht des Gastgebers im laufenden Space (Paket 3.5c, Mockup „Medienraster“). Ersetzt die Textliste. Filter „Alle“
 * und „Meine“, Raster mit Vorschau, Uploader und „Standort enthalten“. Das Raster richtet sich nach der Breite: auf großen
 * Displays mehr Spalten. Änderungen am Index erscheinen ohne Zutun.
 */
@Composable
internal fun HostMediaSection(
    state: HostMediaUiState,
    onFilter: (MediaFilter) -> Unit,
    onOpen: (HostMediaItem) -> Unit,
    modifier: Modifier = Modifier,
    /** Ob eine Kachel zu öffnen ist. Eine nicht zu öffnende Kachel ist weder klickbar noch Schaltfläche (TalkBack). */
    isOpenable: (HostMediaItem) -> Boolean = { true },
) {
    // Vor der ersten Antwort des Index gibt es nichts zu filtern. Die Leiste erscheint erst danach.
    if (!state.loaded) return
    Column(modifier = modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.filter == MediaFilter.ALL,
                onClick = { onFilter(MediaFilter.ALL) },
                label = { Text(stringResource(R.string.space_media_filter_all)) },
            )
            FilterChip(
                selected = state.filter == MediaFilter.MINE,
                onClick = { onFilter(MediaFilter.MINE) },
                label = { Text(stringResource(R.string.space_media_filter_mine)) },
            )
        }
        Spacer(Modifier.height(8.dp))
        val visible = state.visible
        when {
            visible.isEmpty() -> {
                Text(
                    text =
                        stringResource(
                            if (state.filter ==
                                MediaFilter.MINE
                            ) {
                                R.string.space_media_empty_mine
                            } else {
                                R.string.space_running_media_empty
                            },
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                MediaGrid(items = visible, onOpen = onOpen, isOpenable = isOpenable)
            }
        }
    }
}

/** Raster ohne Lazy-Liste, weil es im scrollenden Bildschirm steht. Drei Spalten, auf breiten Displays fünf. */
@Composable
private fun MediaGrid(
    items: List<HostMediaItem>,
    onOpen: (HostMediaItem) -> Unit,
    isOpenable: (HostMediaItem) -> Boolean,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= WIDE_GRID_DP.dp) WIDE_COLUMNS else NARROW_COLUMNS
        Column(verticalArrangement = Arrangement.spacedBy(GAP_DP.dp)) {
            items.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { item ->
                        HostMediaTile(
                            item = item,
                            onClick = if (isOpenable(item)) ({ onOpen(item) }) else null,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Eine Kachel: Vorschau oder Platzhalter, Uploader, Typ-Kennzeichen Video und „Standort enthalten“. */
@Composable
private fun HostMediaTile(
    item: HostMediaItem,
    /** `null`, wenn die Kachel nichts öffnet. Dann ist sie weder klickbar noch eine Schaltfläche. */
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // Die Beschreibung ersetzt den Text der Kinder für TalkBack. Sie nennt daher auch Typ und Standort.
    val uploader = uploaderLabel(item)
    val video = stringResource(R.string.space_media_video)
    val location = stringResource(R.string.space_running_media_location)
    val description =
        listOfNotNull(uploader, video.takeIf { item.isVideo }, location.takeIf { item.locationKept }).joinToString(", ")
    val clickable = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Box(
        modifier =
            modifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(TILE_CORNER_DP.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(clickable)
                .semantics { contentDescription = description },
    ) {
        MediaPreview(
            source = item.previewFile ?: item.originalFile,
            previewState = item.previewState,
            modifier = Modifier.fillMaxSize(),
        )
        if (item.isVideo) {
            Tag(
                text = stringResource(R.string.space_media_video),
                color = MaterialTheme.colorScheme.secondaryContainer,
                textColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
            )
        }
        if (item.locationKept) {
            Tag(
                text = stringResource(R.string.space_running_media_location),
                color = MaterialTheme.colorScheme.errorContainer,
                textColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
            )
        }
        Text(
            text = uploader,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            // Ein langer Name endet mit „…“ statt abgeschnitten zu verschwinden (Hotfix 2, Golden „Christopher-Alexander“).
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = SCRIM_ALPHA))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * Vorschau einer Medienkachel, gemeinsam für den laufenden Space und die Übernahme-Auswahl (ADR 0022). Vorschau, sonst bei
 * fehlgeschlagener Vorschau das Original verkleinert (Hotfix 2). Scheitert auch das, bleibt der Platzhalter „Keine Vorschau“.
 * Der Coil-Plattencache ist aus (siehe [previewRequest]).
 */
@Composable
internal fun MediaPreview(
    source: File?,
    previewState: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalPlatformContext.current
    if (source != null) {
        val request = remember(context, source) { previewRequest(context, source, TILE_PIXELS) }
        SubcomposeAsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
            error = { PreviewPlaceholder(placeholderRes(PreviewState.FAILED)) },
        )
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            PreviewPlaceholder(placeholderRes(previewState))
        }
    }
}

@Composable
internal fun Tag(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    textColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(TAG_CORNER_DP.dp))
                .background(color)
                .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = textColor)
    }
}

/**
 * Name des Uploaders. Ein leerer Name gilt als unbekannt, sonst stünde in der Kachel nur „von “ (Fehlerbericht
 * v0.3.0-alpha.1).
 */
private fun uploaderNameOrNull(item: HostMediaItem): String? = item.uploaderName?.takeIf { it.isNotBlank() }

/** Name des Uploaders, mit „(entfernt)“, wenn er gegangen ist (ADR 0018). */
@Composable
private fun uploaderLabel(item: HostMediaItem): String {
    val name = uploaderNameOrNull(item) ?: return stringResource(R.string.space_running_media_unknown)
    return if (item.uploaderRemoved) {
        stringResource(R.string.space_running_media_uploader_removed, name)
    } else {
        stringResource(R.string.space_media_from, name)
    }
}

/** Name für die Detailzeile „Hochgeladen von …“. Entfernte Teilnehmer tragen den Vermerk im Namen (ADR 0018). */
@Composable
private fun detailUploader(item: HostMediaItem): String {
    val name = uploaderNameOrNull(item) ?: return stringResource(R.string.space_running_media_unknown)
    return if (item.uploaderRemoved) stringResource(R.string.space_running_media_uploader_removed, name) else name
}

private fun placeholderRes(state: String?): Int =
    when (state) {
        PreviewState.FAILED -> R.string.space_media_placeholder_failed
        else -> R.string.space_media_placeholder_pending
    }

/** Platzhalter in einer Kachel oder Ansicht: ein kurzer Text, mittig, ohne Bild. */
@Composable
private fun PreviewPlaceholder(textRes: Int) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(4.dp),
        )
    }
}

/** Anfrage für eine Vorschau aus dem Space-Speicher. Der Coil-Plattencache ist aus, die Datei liegt ohnehin dort. */
private fun previewRequest(
    context: coil3.PlatformContext,
    file: File,
    pixels: Int,
): ImageRequest =
    ImageRequest
        .Builder(context)
        .data(file)
        .size(pixels)
        .diskCachePolicy(CachePolicy.DISABLED)
        .build()

/**
 * Detailansicht eines Mediums (Paket 3.5c): eigene schlanke Ansicht, nicht der Galerie-Viewer (Trennung von Space und Galerie).
 * Bild mit Zoom, Video über Media3 mit einer Datei aus dem App-Speicher. Dazu Uploader, Zeit, Größe und der Standort-Vermerk.
 * „Entfernen“ nur in einem laufenden Space und nach Bestätigung.
 */
@Composable
internal fun HostMediaDetail(
    item: HostMediaItem,
    loadDetail: suspend () -> DetailMedia?,
    editable: Boolean,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var detail by remember(item.id) { mutableStateOf<DetailMedia?>(null) }
    var loaded by remember(item.id) { mutableStateOf(false) }
    var confirming by remember(item.id) { mutableStateOf(false) }
    val currentLoad by rememberUpdatedState(loadDetail)
    LaunchedEffect(item.id) {
        detail = currentLoad()
        loaded = true
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            HostMediaDetailBody(
                item = item,
                detail = detail,
                loaded = loaded,
                editable = editable,
                onRemoveRequest = { confirming = true },
                onClose = onDismiss,
            )
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.space_media_remove_title)) },
            text = { MediaRemoveText() },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onDelete()
                    },
                ) { Text(stringResource(R.string.space_media_remove_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.space_cancel)) }
            },
        )
    }
}

/** Inhalt der Detailansicht ohne Dialogfenster. Die Goldens zeigen ihn direkt. */
@Composable
internal fun HostMediaDetailBody(
    item: HostMediaItem,
    detail: DetailMedia?,
    loaded: Boolean,
    editable: Boolean,
    onRemoveRequest: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onClose) { Text(stringResource(R.string.space_media_close)) }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                detail != null && detail.isVideo -> {
                    HostVideoDetail(detail.file)
                }

                detail != null -> {
                    ZoomableImage(detail.file, description = detailUploader(item))
                }

                loaded -> {
                    Text(
                        text = stringResource(placeholderRes(item.previewState)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.space_media_detail_uploader, detailUploader(item)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.space_media_detail_time, formatDate(item.createdAt)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text =
                stringResource(
                    R.string.space_media_detail_size,
                    Formatter.formatShortFileSize(context, item.sizeBytes),
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (item.locationKept) {
            Text(
                text = stringResource(R.string.space_running_media_location),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (editable) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onRemoveRequest,
                modifier = Modifier.fillMaxWidth(),
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                Text(stringResource(R.string.space_media_remove))
            }
        }
    }
}

/** Text der Entfernen-Bestätigung. Eigene Funktion, damit die Goldens dieselben Zeilen zeigen wie die App. */
@Composable
internal fun MediaRemoveText() {
    Text(stringResource(R.string.space_media_remove_text))
}

/** Bild mit Zoom per Gesten. Das Bild wird auf die Breite der Ansicht verkleinert, nicht im Original dekodiert. */
@Composable
private fun ZoomableImage(
    file: File,
    description: String,
) {
    val context = LocalPlatformContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val request = remember(context, file) { previewRequest(context, file, DETAIL_PIXELS) }
    SubcomposeAsyncImage(
        model = request,
        contentDescription = stringResource(R.string.space_media_detail_image, description),
        contentScale = ContentScale.Fit,
        modifier =
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }.pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                        offset = if (scale > 1f) offset + pan else Offset.Zero
                    }
                },
        // Scheitert das Dekodieren (etwa HEIC auf einem älteren Gerät), zeigt die Ansicht „Keine Vorschau“ (Hotfix 2).
        error = { PreviewPlaceholder(placeholderRes(PreviewState.FAILED)) },
    )
}

private const val NARROW_COLUMNS = 3
private const val WIDE_COLUMNS = 5
private const val WIDE_GRID_DP = 600
private const val GAP_DP = 4
private const val TILE_CORNER_DP = 8
private const val TAG_CORNER_DP = 4
private const val TILE_PIXELS = 320
private const val DETAIL_PIXELS = 1600
private const val SCRIM_ALPHA = 0.8f
private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f
