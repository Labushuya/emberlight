package io.github.labushuya.emberlight.feature.gallery

import io.github.labushuya.emberlight.core.media.MediaLibraryState
import io.github.labushuya.emberlight.core.media.MediaRepository
import io.github.labushuya.emberlight.feature.gallery.permission.PermissionPromptStore
import io.github.labushuya.emberlight.feature.gallery.timeline.ZoomLevelStore
import io.github.labushuya.emberlight.feature.gallery.timeline.ZoomLevels
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Index mit festem Zustand, den der Test ändern kann. Zählt die Aufrufe von [refresh]. */
internal class FakeLibraryRepository(
    initial: MediaLibraryState,
) : MediaRepository {
    private val state = MutableStateFlow(initial)

    var refreshCount = 0
        private set

    override val library: StateFlow<MediaLibraryState> = state

    override fun refresh() {
        refreshCount++
    }

    fun update(library: MediaLibraryState) {
        state.value = library
    }
}

/** Gespeicherte Zoomstufe im Speicher. Eine Stufe, die gesetzt wird, gilt sofort für alle Leser. */
internal class FakeZoomStore(
    saved: Int = ZoomLevels.DEFAULT_LEVEL,
) : ZoomLevelStore {
    private val flow = MutableStateFlow(saved)

    val saved: Int get() = flow.value

    override val level: Flow<Int> = flow

    override suspend fun setLevel(level: Int) {
        flow.value = ZoomLevels.clamp(level)
    }
}

/** Merkmal „schon gefragt“ im Speicher. */
internal class FakePromptStore(
    asked: Boolean,
) : PermissionPromptStore {
    val askedFlag = MutableStateFlow(asked)

    override val askedBefore: Flow<Boolean> = askedFlag

    override suspend fun markAsked() {
        askedFlag.value = true
    }
}
