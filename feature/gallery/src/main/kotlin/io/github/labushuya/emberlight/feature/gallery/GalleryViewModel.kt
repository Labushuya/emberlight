package io.github.labushuya.emberlight.feature.gallery

import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.labushuya.emberlight.core.media.MediaRepository
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

/** Galerie mit allen Einträgen: Zustand des Fotos-Reiters. */
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
        val uiState: StateFlow<MediaGridState> = viewState(MediaGridState.Loading) { _, grid -> grid }
    }
