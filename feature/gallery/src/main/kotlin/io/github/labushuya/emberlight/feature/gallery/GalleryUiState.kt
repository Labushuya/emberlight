package io.github.labushuya.emberlight.feature.gallery

import androidx.compose.runtime.Immutable
import io.github.labushuya.emberlight.core.media.model.Album
import io.github.labushuya.emberlight.feature.gallery.timeline.MediaGridState

/**
 * Zustand der Galerie für beide Reiter (ADR 0009). [grid] steuert Laden, Berechtigung und Leerzustand gemeinsam. [albums]
 * speist die Alben-Übersicht in Anzeigereihenfolge.
 */
@Immutable
internal data class GalleryUiState(
    val grid: MediaGridState,
    val albums: List<Album>,
) {
    companion object {
        val Loading: GalleryUiState = GalleryUiState(grid = MediaGridState.Loading, albums = emptyList())
    }
}
