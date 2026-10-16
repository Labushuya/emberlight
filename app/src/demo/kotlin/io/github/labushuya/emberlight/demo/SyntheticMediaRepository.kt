package io.github.labushuya.emberlight.demo

import io.github.labushuya.emberlight.core.media.MediaLibraryState
import io.github.labushuya.emberlight.core.media.MediaRepository
import io.github.labushuya.emberlight.core.media.model.MediaLibrary
import io.github.labushuya.emberlight.core.media.permission.MediaAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/**
 * Index aus der synthetischen Bibliothek. Die Liste entsteht erst, wenn jemand den Zustand beobachtet, und nur auf dem
 * Default-Dispatcher. Der Vollzugriff gilt immer, eine Berechtigung ist im Demo-Modus nicht nötig.
 */
internal class SyntheticMediaRepository(
    scope: CoroutineScope,
) : MediaRepository {
    override val library: StateFlow<MediaLibraryState> =
        flow {
            emit(MediaLibraryState.Loading)
            emit(MediaLibraryState.Ready(build()))
        }.flowOn(Dispatchers.Default)
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MediaLibraryState.Loading)

    /** Nichts zu aktualisieren: die synthetische Bibliothek ändert sich nicht. */
    override fun refresh() = Unit

    private fun build(): MediaLibrary {
        val items = SyntheticLibrary.items()
        return MediaLibrary(
            items = items,
            albums = SyntheticLibrary.albums(items),
            mediaAccess = MediaAccess.FULL,
            missingTypes = emptySet(),
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
