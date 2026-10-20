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
import io.github.labushuya.emberlight.share.space.management.SpaceStatus
import io.github.labushuya.emberlight.share.space.run.PauseEvent
import io.github.labushuya.emberlight.share.space.run.RunClock
import io.github.labushuya.emberlight.share.space.run.SpaceRunRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
    /** Ergebnis des letzten Fortsetzen-Versuchs, wenn er scheiterte. `null` sonst. */
    val resumeFailure: StartOutcome? = null,
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

    /**
     * Aktiv, nicht abgelaufen, aber kein Server läuft: der Lauf ist unterbrochen (ADR 0017). Die Oberfläche bietet dann
     * „Fortsetzen“ an. Ein abgelaufener Space gilt nicht als unterbrochen, er endet.
     */
    val interrupted: Boolean
        get() {
            val current = record ?: return false
            if (current.status != SpaceStatus.ACTIVE || runState !is RunState.Idle) return false
            val expires = current.expiresAt ?: return true
            return nowMillis < expires
        }

    private companion object {
        const val PERCENT = 100L
        const val WARNING_PERCENT = 80
    }
}

/**
 * Gastgeber-Ansicht des laufenden Space (Mockup Abschnitt 4, Abschnitt 5 Pausiert). Live aktualisiert: Speicher, Medien,
 * Pausen und der Betriebszustand. Das Ende geht über [end], die Oberfläche bestätigt vorher. Eine Unterbrechung setzt
 * [resume] fort.
 */
@HiltViewModel
class SpaceRunningViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val spaces: SpaceRepository,
        private val runs: SpaceRunRepository,
        private val controller: SpaceRunController,
        private val starter: SpaceStarter,
        private val clock: RunClock,
    ) : ViewModel() {
        private val spaceId: SpaceId? = savedStateHandle.get<String>(ARG_ID)?.let { SpaceId.parse(it) }
        private val resumeFailure = MutableStateFlow<StartOutcome?>(null)

        /**
         * Ohne Kennung (Route aus der Benachrichtigung) folgt der Zustand dem aktiven Space. Gibt es keinen, ist die Anzeige
         * leer und sagt es.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        val state: StateFlow<SpaceRunningUiState> =
            (
                spaceId?.let { observeSpace(it) }
                    ?: runs.observeActive().flatMapLatest { active ->
                        active?.let { observeSpace(it.id) } ?: flowOf(SpaceRunningUiState(loaded = true))
                    }
            ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SpaceRunningUiState())

        init {
            viewModelScope.launch { closeIfExpired() }
        }

        private fun observeSpace(id: SpaceId): Flow<SpaceRunningUiState> =
            combine(
                combine(spaces.observe(id), runs.observeUsedBytes(id)) { record, used -> record to used },
                combine(runs.observeMedia(id, MEDIA_LIMIT), runs.observePauses(id)) { media, pauses ->
                    media to pauses
                },
                SpaceServerService.runState,
                ticker(),
                resumeFailure,
            ) { (record, used), (media, pauses), runState, now, failure ->
                SpaceRunningUiState(
                    record = record,
                    loaded = true,
                    usedBytes = used,
                    media = media,
                    runState = runState,
                    pause = pauses.firstOrNull { !it.acknowledged },
                    nowMillis = now,
                    resumeFailure = failure,
                )
            }

        /**
         * Ein Space, dessen Frist um ist, aber ohne Server, endet beim Öffnen der Ansicht. Der Dienst hält die Unterbrechung
         * als Pause fest (ADR 0017, Neustart). Läuft ein Server, entscheidet der Dienst selbst.
         */
        private suspend fun closeIfExpired() {
            val active = runs.findActive() ?: return
            val expires = active.expiresAt ?: return
            if (SpaceServerService.runState.value !is RunState.Idle) return
            if (clock.wallMillis() >= expires) controller.expire()
        }

        /** Beendet den Space. Die Oberfläche hat die Bestätigung eingeholt. */
        fun end() {
            controller.end()
        }

        /** Bestätigt den Wechsel in ein neues WLAN. */
        fun confirmNetwork() {
            controller.confirmNetwork()
        }

        /** Setzt einen unterbrochenen Space fort. Scheitert der Start, bleibt der Space aktiv und die Oberfläche sagt es. */
        fun resume() {
            val id = state.value.record?.id ?: spaceId ?: return
            viewModelScope.launch {
                resumeFailure.value = starter.resume(id).takeUnless { it == StartOutcome.Started }
            }
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
