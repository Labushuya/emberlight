package io.github.labushuya.emberlight.core.media.model

import androidx.compose.runtime.Immutable
import io.github.labushuya.emberlight.core.media.permission.MediaAccess

/**
 * Vollständiger Index: alle Einträge, absteigend nach effektivem Zeitstempel, und die daraus gebildeten Alben.
 * [mediaAccess] sagt, welcher Zugriff gilt. Bei [MediaAccess.NONE] ist die Liste leer.
 * [missingTypes] nennt Medientypen, die trotz Vollzugriff fehlen, weil der Nutzer nur einen Typ gewährt hat (ADR 0007).
 * Ihre Einträge fehlen dann in [items] und [albums].
 */
@Immutable
data class MediaLibrary(
    val items: List<MediaItem>,
    val albums: List<Album>,
    val mediaAccess: MediaAccess,
    val missingTypes: Set<MediaType> = emptySet(),
)
