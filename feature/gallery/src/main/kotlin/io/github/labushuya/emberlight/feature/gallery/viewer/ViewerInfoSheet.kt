package io.github.labushuya.emberlight.feature.gallery.viewer

import android.text.format.Formatter
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.labushuya.emberlight.core.media.exif.PhotoExif
import io.github.labushuya.emberlight.feature.gallery.R
import io.github.labushuya.emberlight.feature.gallery.timeline.formatDuration
import java.time.ZoneId
import java.util.Locale

/** Testmarke des Infobereichs, damit Tests ihn finden. */
internal const val VIEWER_INFO_TAG = "viewer-info"

/** Obere Ecken des Infobereichs (Material 3, große Form). */
private val SHEET_CORNER = 28.dp

/**
 * Der Infobereich unten im Viewer (ADR 0010, Infobereich). Zeigt die Angaben des Elements: Datum, Dateiname, Album, Ordner,
 * Auflösung, Größe, Dateityp und bei Fotos die Aufnahmedaten. Fehlende Werte fehlen in der Anzeige, nie „null“.
 *
 * [exif] wird lazy geladen (siehe [rememberPhotoExif]). Solange es fehlt, zeigt der Bereich nur die Basisdaten. Die Überschrift
 * ist die Region des Bereichs, damit Bedienungshilfen ihn benennen.
 */
@Composable
internal fun ViewerInfoSheet(
    item: ViewerItem,
    exif: PhotoExif?,
    modifier: Modifier = Modifier,
    scroll: ScrollState = rememberScrollState(),
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val titles = remember(locale, zone) { ViewerFormats(locale, zone) }
    val title = stringResource(R.string.feature_gallery_info_title)
    val details = item.details
    Surface(
        modifier = modifier.fillMaxWidth().testTag(VIEWER_INFO_TAG).semantics { paneTitle = title },
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER),
    ) {
        Column(
            modifier =
                Modifier
                    .verticalScroll(scroll)
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(12.dp))

            InfoRow(
                label = stringResource(R.string.feature_gallery_info_date),
                value = titles.withWeekday(item.timestamp),
            )
            if (details.dateEstimated) {
                Text(
                    text = stringResource(R.string.feature_gallery_info_date_estimated),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )
            }
            details.displayName?.let { name ->
                InfoRow(stringResource(R.string.feature_gallery_info_name), name)
            }
            details.albumName?.let { album ->
                InfoRow(stringResource(R.string.feature_gallery_info_album), album)
            }
            details.folder?.let { folder ->
                InfoRow(stringResource(R.string.feature_gallery_info_folder), folder)
            }
            resolutionText(item, locale)?.let { resolution ->
                InfoRow(stringResource(R.string.feature_gallery_info_resolution), resolution)
            }
            details.sizeBytes?.takeIf { it > 0 }?.let { bytes ->
                InfoRow(
                    stringResource(R.string.feature_gallery_info_size),
                    Formatter.formatShortFileSize(context, bytes),
                )
            }
            formatMimeType(details.mimeType)?.let { type ->
                InfoRow(stringResource(R.string.feature_gallery_info_type), type)
            }
            if (item.type == ViewerMediaType.VIDEO) {
                item.durationMs?.takeIf { it > 0 }?.let { ms ->
                    InfoRow(stringResource(R.string.feature_gallery_info_duration), formatDuration(ms))
                }
            }

            if (item.type == ViewerMediaType.IMAGE && exif != null && !exif.isEmpty) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.feature_gallery_info_section_camera),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(4.dp))
                CameraRows(exif, locale)
            }
        }
    }
}

/** Die Kamera-Zeilen. Jede Angabe nur, wenn sie vorhanden ist. */
@Composable
private fun CameraRows(
    exif: PhotoExif,
    locale: Locale,
) {
    val camera = listOfNotNull(exif.make, exif.model).joinToString(" ").takeIf { it.isNotBlank() }
    camera?.let { InfoRow(stringResource(R.string.feature_gallery_info_camera), it) }
    exif.lensModel?.let { InfoRow(stringResource(R.string.feature_gallery_info_lens), it) }
    exif.fNumber?.let { InfoRow(stringResource(R.string.feature_gallery_info_aperture), formatAperture(it, locale)) }
    exif.exposureSeconds?.let {
        InfoRow(stringResource(R.string.feature_gallery_info_exposure), formatExposure(it, locale))
    }
    exif.iso?.let { InfoRow(stringResource(R.string.feature_gallery_info_iso), formatIso(it)) }
    exif.focalLengthMm?.let {
        InfoRow(stringResource(R.string.feature_gallery_info_focal_length), formatFocalLength(it, locale))
    }
    exif.flashFired?.let { fired ->
        InfoRow(
            stringResource(R.string.feature_gallery_info_flash),
            stringResource(
                if (fired) R.string.feature_gallery_info_flash_fired else R.string.feature_gallery_info_flash_not_fired,
            ),
        )
    }
}

/** Auflösung als „B × H“, bei Fotos mit Megapixeln in Klammern. `null`, wenn die Maße fehlen. */
@Composable
private fun resolutionText(
    item: ViewerItem,
    locale: Locale,
): String? {
    val resolution = formatResolution(item.width, item.height) ?: return null
    if (item.type != ViewerMediaType.IMAGE) return resolution
    val megapixels = formatMegapixels(item.width, item.height, locale) ?: return resolution
    return stringResource(R.string.feature_gallery_info_resolution_megapixels, resolution, megapixels)
}

/** Eine Zeile „Bezeichnung: Wert“. Für Bedienungshilfen ein Eintrag mit beiden Teilen. */
@Composable
private fun InfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * EXIF-Angaben des Elements, lazy: Die Abfrage startet, wenn der Infobereich offen ist (ADR 0010). Der Wert bleibt auch beim
 * Schließen erhalten, damit der Bereich beim Einfahren nicht leer wird. Er gehört immer zu [item]: Ein Wert für ein anderes
 * Element wird nie geliefert. Fehler liefern `null` und werden ruhig ignoriert.
 */
@Composable
internal fun rememberPhotoExif(
    item: ViewerItem,
    open: Boolean,
    lookup: ExifLookup,
): PhotoExif? {
    val loaded by produceState<LoadedExif?>(initialValue = null, item.id, open) {
        if (open && value?.id != item.id) value = LoadedExif(item.id, lookup.exifOf(item))
    }
    return loaded?.takeIf { it.id == item.id }?.exif
}

/** Ergebnis einer EXIF-Abfrage mit der Element-ID, zu der es gehört. */
private class LoadedExif(
    val id: Long,
    val exif: PhotoExif?,
)

/**
 * Infobereich einer Seite (ADR 0010, Infobereich). Liegt als Kind der Seite unten, damit horizontales Wischen über dem Bereich
 * den Pager erreicht und vertikales Wischen die Seite. Gezeichnet nur, solange der Bereich offen ist oder einfährt.
 */
@Composable
internal fun BoxScope.ViewerInfoLayer(
    item: ViewerItem,
    overlay: ViewerOverlayState,
    exif: ExifLookup,
    zone: ZoneId,
) {
    // Der Scroll-Zustand gehört dem Aufrufer, damit das Ziehen am Anschlag weiß, ob der Inhalt sich bewegen kann.
    val scroll = rememberScrollState()
    val sheetShown by remember(overlay) { derivedStateOf { overlay.infoOpen || overlay.infoFraction > 0f } }
    if (!sheetShown) return
    val photoExif = rememberPhotoExif(item = item, open = overlay.infoOpen, lookup = exif)
    // Ohne Überrollen: Der Inhalt verbraucht sonst das Ziehen nach unten am Anschlag, und der Bereich ließe sich nicht schließen
    // (ADR 0010, Infobereich). Das Ziehen am Anschlag übernimmt der Bereich selbst (siehe sheetDragToClose).
    CompositionLocalProvider(LocalOverscrollFactory provides null) {
        ViewerInfoSheet(
            item = item,
            exif = photoExif,
            scroll = scroll,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxHeight(INFO_PANEL_FRACTION)
                    .graphicsLayer {
                        // Der Bereich folgt dem Anteil: unten zu, am Boden offen. Er wird nicht mit dem Bild verschoben.
                        translationY = (1f - overlay.infoFraction) * overlay.panelHeightPx()
                    }.sheetDragToClose(overlay, scroll),
            zone = zone,
        )
    }
}
