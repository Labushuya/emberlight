package io.github.labushuya.emberlight.core.media.source

import io.github.labushuya.emberlight.core.media.MediaLibraryState
import io.github.labushuya.emberlight.core.media.MediaRepository
import io.github.labushuya.emberlight.core.media.di.AlternativeMediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * Wählt zur Laufzeit zwischen dem MediaStore und einer alternativen Quelle. Solange die Alternative aktiv ist, folgt der
 * Index ihr, sonst dem MediaStore. Die Berechtigung bleibt dabei unberührt: Die Alternative meldet ihren eigenen Zugriff.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SwitchingMediaRepository(
    private val store: MediaRepository,
    private val alternative: AlternativeMediaSource,
    scope: CoroutineScope,
) : MediaRepository {
    override val library: StateFlow<MediaLibraryState> =
        alternative.active
            .flatMapLatest { useAlternative -> if (useAlternative) alternative.repository.library else store.library }
            .stateIn(scope, SharingStarted.Eagerly, MediaLibraryState.Loading)

    override fun refresh() {
        store.refresh()
        alternative.repository.refresh()
    }
}

/** Wählt die Quelle: ohne Alternative der MediaStore allein, sonst die Umschaltung. */
internal fun selectMediaRepository(
    store: MediaRepository,
    alternatives: Set<AlternativeMediaSource>,
    scope: CoroutineScope,
): MediaRepository {
    require(alternatives.size <= 1) { "Höchstens eine alternative Medienquelle ist vorgesehen." }
    val alternative = alternatives.firstOrNull() ?: return store
    return SwitchingMediaRepository(store, alternative, scope)
}
