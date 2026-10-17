package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.compose.runtime.Immutable
import io.github.labushuya.emberlight.core.media.model.MediaItem
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.core.media.model.TimestampSource
import io.github.labushuya.emberlight.core.media.thumbnail.MediaThumbnail

/** Art eines Elements im Vollbild. Eigene Enum, damit der Viewer nicht vom MediaStore-Modell abhängt (ADR 0010). */
enum class ViewerMediaType {
    IMAGE,
    VIDEO,
}

/**
 * Ein Element des Vollbild-Viewers. Quellenunabhängig: Die Galerie füllt es aus dem MediaStore-Index ([toViewerItem]), ein
 * späterer Space füllt es aus eigenen Daten. Die UI-Bausteine kennen nur dieses Modell.
 *
 * - [uri] ist die Adresse des Originals. Die Bildquelle muss sie verstehen (bei der Galerie eine Content-URI).
 * - [previewKey] ist der Schlüssel eines bereits geladenen Vorschaubilds im Speicher-Cache von Coil, `null` wenn keiner
 *   bekannt ist. Der Viewer zeigt das Vorschaubild als Platzhalter, bis das Original da ist.
 * - Optionale Werte sind `null`, wenn die Quelle sie nicht kennt.
 */
@Immutable
data class ViewerItem(
    val id: Long,
    val uri: String,
    val type: ViewerMediaType,
    val timestamp: Long,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val previewKey: String?,
    /** Angaben für den Infobereich. Ohne Angaben bleibt er bei Datum und Auflösung. */
    val details: MediaDetails = MediaDetails(),
)

/** Abbildung eines Galerie-Eintrags. Das Vorschaubild hat denselben Cache-Schlüssel wie in der Zelle des Rasters. */
internal fun MediaItem.toViewerItem(): ViewerItem =
    ViewerItem(
        id = id,
        uri = uri,
        type =
            when (type) {
                MediaType.IMAGE -> ViewerMediaType.IMAGE
                MediaType.VIDEO -> ViewerMediaType.VIDEO
            },
        timestamp = timestamp,
        width = width,
        height = height,
        durationMs = durationMs,
        details =
            MediaDetails(
                displayName = displayName,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                albumName = bucketName,
                folder = relativePath?.trimEnd('/')?.takeIf { it.isNotEmpty() },
                dateEstimated = timestampSource != TimestampSource.DATE_TAKEN,
            ),
        previewKey = MediaThumbnail(uri = uri, type = type).memoryCacheKey,
    )
