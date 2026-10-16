package io.github.labushuya.emberlight.feature.gallery.album

import io.github.labushuya.emberlight.core.media.MediaLibraryState
import io.github.labushuya.emberlight.core.media.model.Album

/**
 * Reihenfolge der Alben für die Anzeige. Der Index sortiert neueste zuerst, das Sammelalbum „Sonstige“ (Einträge ohne
 * Ordner, `bucketId` ist `null`) aber nur bei Gleichstand ans Ende. Hier steht es immer zuletzt. Die übrige Reihenfolge
 * bleibt erhalten, weil `sortedBy` stabil ist.
 */
internal fun albumsForDisplay(albums: List<Album>): List<Album> = albums.sortedBy { it.bucketId == null }

/** Alben des Index in Anzeigereihenfolge. Leer, solange der Index lädt. */
internal fun albumsOf(library: MediaLibraryState): List<Album> =
    (library as? MediaLibraryState.Ready)?.let { albumsForDisplay(it.library.albums) }.orEmpty()
