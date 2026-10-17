package io.github.labushuya.emberlight.feature.gallery.album

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.labushuya.emberlight.core.media.MediaLibraryState
import io.github.labushuya.emberlight.core.media.MediaRepository
import io.github.labushuya.emberlight.core.media.model.Album
import io.github.labushuya.emberlight.feature.gallery.di.DefaultDispatcher
import io.github.labushuya.emberlight.feature.gallery.permission.PermissionPromptStore
import io.github.labushuya.emberlight.feature.gallery.timeline.MediaGridState
import io.github.labushuya.emberlight.feature.gallery.timeline.MediaGridViewModel
import io.github.labushuya.emberlight.feature.gallery.timeline.MediaScope
import io.github.labushuya.emberlight.feature.gallery.timeline.ZoomLevelStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import java.time.Clock
import java.time.ZoneId
import javax.inject.Inject

/**
 * Zustand der Album-Ansicht. [album] ist das Album aus dem Index, `null` solange es nicht (mehr) vorhanden ist. [grid]
 * steuert Raster, Laden, Berechtigung und den Zustand „Album fehlt“.
 */
@Immutable
internal data class AlbumDetailUiState(
    val album: Album?,
    val grid: MediaGridState,
) {
    companion object {
        val Loading: AlbumDetailUiState = AlbumDetailUiState(album = null, grid = MediaGridState.Loading)
    }
}

/**
 * Album-Ansicht: die Einträge eines Albums im gemeinsamen Raster. Die Ordner-ID kommt aus der Route über
 * [SavedStateHandle]. Die Zoomstufe ist dieselbe wie in der Galerie.
 */
@HiltViewModel
internal class AlbumDetailViewModel
    @Inject
    constructor(
        repository: MediaRepository,
        permissionPrompts: PermissionPromptStore,
        zoomLevels: ZoomLevelStore,
        @DefaultDispatcher defaultDispatcher: CoroutineDispatcher,
        clock: Clock,
        zone: ZoneId,
        savedStateHandle: SavedStateHandle,
    ) : MediaGridViewModel(
            repository = repository,
            permissionPrompts = permissionPrompts,
            zoomLevels = zoomLevels,
            defaultDispatcher = defaultDispatcher,
            clock = clock,
            zone = zone,
            scope = MediaScope.Bucket(savedStateHandle.toRoute<AlbumRoute>().bucketId),
        ) {
        private val bucketId: Long? = savedStateHandle.toRoute<AlbumRoute>().bucketId

        val uiState: StateFlow<AlbumDetailUiState> =
            viewState(AlbumDetailUiState.Loading) { input, grid ->
                AlbumDetailUiState(album = albumWithBucket(input.library, bucketId), grid = grid)
            }
    }

/** Das Album mit dieser Ordner-ID aus dem Index, `null` wenn der Index lädt oder das Album fehlt. */
internal fun albumWithBucket(
    library: MediaLibraryState,
    bucketId: Long?,
): Album? = (library as? MediaLibraryState.Ready)?.library?.albums?.firstOrNull { it.bucketId == bucketId }
