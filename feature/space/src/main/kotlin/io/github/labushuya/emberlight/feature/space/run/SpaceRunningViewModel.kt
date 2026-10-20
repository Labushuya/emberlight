package io.github.labushuya.emberlight.feature.space.run

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.labushuya.emberlight.share.server.RunState
import io.github.labushuya.emberlight.share.server.SpaceServerService
import io.github.labushuya.emberlight.share.space.MediaRow
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.management.SpaceRecord
import io.github.labushuya.emberlight.share.space.management.SpaceRepository
import io.github.labushuya.emberlight.share.space.run.PauseEvent
import io.github.labushuya.emberlight.share.space.run.RunClock
import io.github.labushuya.emberlight.share.space.run.SpaceRunRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Zustand des laufenden Space. [nowMillis] ist die Wand-Uhr für „noch X“, sie tickt regelmäßig. */
data class SpaceRunningUiState(
    val record: SpaceRecord? = null,
    val loaded: Boolean = false,
    val usedBytes: Long = 0L,
    val media: List<MediaRow> = emptyList(),
    val runState: RunState = RunState.Idle,
    /** Die neueste Pause, die der Gastgeber noch nicht gesehen hat. */
    val pause: PauseEvent? = null,
    val nowMillis: Long = 0L,
) {
    /** Speicher in Prozent des Limits, am Ende bei 100. Ohne Limit `0`. */
    val usedPercent: Int
        get() {
            val limit = record?.settings?.storageLimitBytes ?: return 0
            if (limit <= 0L) return 0
            return ((usedBytes * PERCENT) / limit).coerceIn(0L, PERCENT).toInt()
        }

    /** Ab 80 % erscheint die Warnung (KONZEPT, Speicher). */
    val storageWarning: Boolean get() = usedPercent >= WARNING_PERCENT

    private companion object {
        const val PERCENT = 100L
        const val WARNING_PERCENT = 80
    }
}

/**
 * Gastgeber-Ansicht des laufenden Space (Mockup Abschnitt 4, Abschnitt 5 Pausiert). Live aktualisiert: Speicher, Medien,
 * Pausen und der Betriebszustand. Das Ende geht über [end], die Oberfläche bestätigt vorher.
 */
@HiltViewModel
class SpaceRunningViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val spaces: SpaceRepository,
        private val runs: SpaceRunRepository,
        private val controller: SpaceRunController,
        private val clock: RunClock,
    ) : ViewModel() {
        private val spaceId: SpaceId? = savedStateHandle.get<String>(ARG_ID)?.let { SpaceId.parse(it) }

        val state: StateFlow<SpaceRunningUiState> =
            if (spaceId == null) {
                MutableStateFlow(SpaceRunningUiState(loaded = true)).asStateFlow()
            } else {
                combine(
                    combine(spaces.observe(spaceId), runs.observeUsedBytes(spaceId)) { record, used -> record to used },
                    combine(runs.observeMedia(spaceId, MEDIA_LIMIT), runs.observePauses(spaceId)) { media, pauses ->
                        media to pauses
                    },
                    SpaceServerService.runState,
                    ticker(),
                ) { (record, used), (media, pauses), runState, now ->
                    SpaceRunningUiState(
                        record = record,
                        loaded = true,
                        usedBytes = used,
                        media = media,
                        runState = runState,
                        pause = pauses.firstOrNull { !it.acknowledged },
                        nowMillis = now,
                    )
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SpaceRunningUiState())
            }

        /** Beendet den Space. Die Oberfläche hat die Bestätigung eingeholt. */
        fun end() {
            controller.end()
        }

        /** Bestätigt den Wechsel in ein neues WLAN. */
        fun confirmNetwork() {
            controller.confirmNetwork()
        }

        /** Der Hinweis zur Pause ist gesehen. */
        fun acknowledgePauses() {
            val id = spaceId ?: return
            viewModelScope.launch { runs.acknowledgePauses(id) }
        }

        /** Wand-Uhr in festen Abständen. Die Anzeige rechnet „noch X“ daraus. */
        private fun ticker(): Flow<Long> =
            flow {
                while (true) {
                    emit(clock.wallMillis())
                    delay(TICK_MILLIS)
                }
            }

        private companion object {
            const val ARG_ID = "id"
            const val MEDIA_LIMIT = 50
            const val TICK_MILLIS = 15_000L
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
