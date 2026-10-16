package io.github.labushuya.emberlight.feature.gallery.album

import androidx.compose.runtime.Immutable
import io.github.labushuya.emberlight.core.media.MediaLibraryState
import io.github.labushuya.emberlight.core.media.model.Album

/**
 * Alben in Anzeigereihenfolge. Die Hülle macht die Liste für Compose stabil, sodass die Alben-Übersicht überspringbar bleibt.
 */
@Immutable
internal data class AlbumList(
    val items: List<Album>,
)

/**
 * Reihenfolge der Alben für die Anzeige. Der Index sortiert neueste zuerst, das Sammelalbum „Sonstige“ (Einträge ohne
 * Ordner, `bucketId` ist `null`) aber nur bei Gleichstand ans Ende. Hier steht es immer zuletzt. Die übrige Reihenfolge
 * bleibt erhalten, weil `sortedBy` stabil ist.
 */
internal fun albumsForDisplay(albums: List<Album>): List<Album> = albums.sortedBy { it.bucketId == null }

/** Alben des Index in Anzeigereihenfolge. Leer, solange der Index lädt. */
internal fun albumsOf(library: MediaLibraryState): AlbumList =
    AlbumList(
        (library as? MediaLibraryState.Ready)?.let { albumsForDisplay(it.library.albums) }.orEmpty(),
    )
