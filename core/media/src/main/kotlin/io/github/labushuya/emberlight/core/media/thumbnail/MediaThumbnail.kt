package io.github.labushuya.emberlight.core.media.thumbnail

import androidx.compose.runtime.Immutable
import io.github.labushuya.emberlight.core.media.model.MediaType

/**
 * Anfrage für ein Vorschaubild eines MediaStore-Eintrags. Coil lädt damit die verkleinerte Darstellung, nicht das
 * Original. Der Vollbild-Viewer verwendet dieses Modell nicht, er lädt das Original über [uri].
 *
 * [uri] ist die Content-URI als String (siehe [io.github.labushuya.emberlight.core.media.model.MediaItem]).
 */
@Immutable
data class MediaThumbnail(
    val uri: String,
    val type: MediaType,
)
