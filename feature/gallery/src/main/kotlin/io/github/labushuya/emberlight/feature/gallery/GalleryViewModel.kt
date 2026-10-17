package io.github.labushuya.emberlight.feature.gallery

import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.labushuya.emberlight.core.media.MediaRepository
import io.github.labushuya.emberlight.feature.gallery.album.albumsOf
import io.github.labushuya.emberlight.feature.gallery.di.DefaultDispatcher
import io.github.labushuya.emberlight.feature.gallery.permission.PermissionPromptStore
import io.github.labushuya.emberlight.feature.gallery.timeline.MediaGridViewModel
import io.github.labushuya.emberlight.feature.gallery.timeline.MediaScope
import io.github.labushuya.emberlight.feature.gallery.timeline.ZoomLevelStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import java.time.Clock
import java.time.ZoneId
import javax.inject.Inject

/** Zustand der Galerie: alle Einträge für den Fotos-Reiter, die Alben für den Alben-Reiter. */
@HiltViewModel
internal class GalleryViewModel
    @Inject
    constructor(
        repository: MediaRepository,
        permissionPrompts: PermissionPromptStore,
        zoomLevels: ZoomLevelStore,
        @DefaultDispatcher defaultDispatcher: CoroutineDispatcher,
        clock: Clock,
        zone: ZoneId,
    ) : MediaGridViewModel(
            repository = repository,
            permissionPrompts = permissionPrompts,
            zoomLevels = zoomLevels,
            defaultDispatcher = defaultDispatcher,
            clock = clock,
            zone = zone,
            scope = MediaScope.All,
        ) {
        val uiState: StateFlow<GalleryUiState> =
            viewState(GalleryUiState.Loading) { input, grid ->
                GalleryUiState(grid = grid, albums = albumsOf(input.library))
            }
    }
