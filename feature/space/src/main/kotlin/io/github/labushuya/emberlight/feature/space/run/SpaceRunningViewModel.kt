package io.github.labushuya.emberlight.feature.space.run

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.labushuya.emberlight.share.server.RunState
import io.github.labushuya.emberlight.share.server.SpaceServerService
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.management.SpaceRecord
import io.github.labushuya.emberlight.share.space.management.SpaceRepository
import io.github.labushuya.emberlight.share.space.management.SpaceStatus
import io.github.labushuya.emberlight.share.space.participants.ParticipantInfo
import io.github.labushuya.emberlight.share.space.run.ExtendProblem
import io.github.labushuya.emberlight.share.space.run.PauseEvent
import io.github.labushuya.emberlight.share.space.run.RunClock
import io.github.labushuya.emberlight.share.space.run.SpaceRunPolicy
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
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Zustand des laufenden Space. [nowMillis] ist die Wand-Uhr für „noch X“, sie tickt regelmäßig. */
data class SpaceRunningUiState(
    val record: SpaceRecord? = null,
    val loaded: Boolean = false,
    val usedBytes: Long = 0L,
    val runState: RunState = RunState.Idle,
    /** Die neueste Pause, die der Gastgeber noch nicht gesehen hat. */
    val pause: PauseEvent? = null,
    val nowMillis: Long = 0L,
    /** Ergebnis des letzten Fortsetzen-Versuchs, wenn er scheiterte. `null` sonst. */
    val resumeFailure: StartOutcome? = null,
    /** Aktive Teilnehmer, Gastgeber zuerst (ADR 0018). */
    val participants: List<ParticipantInfo> = emptyList(),
    /** Beitrittsadresse mit Einladung, nur solange der Server läuft. */
    val invite: String? = null,
    /** Der Start dieses Space wartet auf den Nachlauf des vorigen (bis zu 60 s). Der Server läuft dann noch nicht. */
    val startQueued: Boolean = false,
) {
    /** Die Adresse hat sich geändert, weil der feste Port belegt war (Paket 3.4). */
    val addressChanged: Boolean get() = (runState as? RunState.Running)?.addressChanged == true

    /** Die Einladung entstand neu, weil das gespeicherte Geheimnis nicht lesbar war. Der QR-Code muss neu geteilt werden (ADR 0018). */
    val inviteRenewed: Boolean get() = (runState as? RunState.Running)?.inviteRenewed == true

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
            // Ein wartender Start ist kein unterbrochener Lauf, er startet von selbst.
            if (current.status != SpaceStatus.ACTIVE || runState !is RunState.Idle || startQueued) return false
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
        private val hostActions: SpaceHostActions,
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

        @OptIn(ExperimentalCoroutinesApi::class)
        private fun observeSpace(id: SpaceId): Flow<SpaceRunningUiState> {
            val server =
                combine(SpaceServerService.runState, SpaceServerService.startQueued) { run, queued ->
                    run to
                        queued
                }
            val base =
                combine(
                    combine(spaces.observe(id), runs.observeUsedBytes(id)) { record, used -> record to used },
                    runs.observePauses(id),
                    server,
                    ticker(),
                    resumeFailure,
                ) { (record, used), pauses, (runState, queued), now, failure ->
                    SpaceRunningUiState(
                        record = record,
                        loaded = true,
                        usedBytes = used,
                        runState = runState,
                        pause = pauses.firstOrNull { !it.acknowledged },
                        nowMillis = now,
                        resumeFailure = failure,
                        startQueued = queued,
                    )
                }
            // Teilnehmer laden neu bei jeder Änderung (ADR 0018). Die Einladung folgt dem Betriebszustand.
            val hostPart = hostActions.changes().mapLatest { hostActions.participants(id) }
            return combine(base, hostPart) { state, participants ->
                state.copy(participants = participants, invite = inviteFor(state.runState, id))
            }
        }

        private fun inviteFor(
            runState: RunState,
            id: SpaceId,
        ): String? {
            val running = runState as? RunState.Running ?: return null
            val secret = hostActions.currentInvite(id) ?: return null
            return InviteLink.of(running.address.ipv4, running.port, secret)
        }

        /** Entfernt einen Gast. Mit [deleteUploads] verschwinden auch seine Medien (ADR 0018). */
        fun removeGuest(
            participant: ParticipantInfo,
            deleteUploads: Boolean,
        ) {
            val id = state.value.record?.id ?: spaceId ?: return
            viewModelScope.launch { hostActions.removeGuest(id, participant.id, deleteUploads) }
        }

        /** Erneuert die Einladung. Alte QR-Codes sind danach ungültig, Beigetretene bleiben. */
        fun rotateInvite() {
            val id = state.value.record?.id ?: spaceId ?: return
            viewModelScope.launch {
                hostActions.rotateInvite(id)
                // Eine manuelle Erneuerung beantwortet den Hinweis zur automatischen Erneuerung gleich mit.
                if (state.value.inviteRenewed) controller.acknowledgeInvite()
            }
        }

        /** Der Gastgeber bestätigt den Hinweis zur automatisch erneuerten Einladung. */
        fun acknowledgeInvite() {
            controller.acknowledgeInvite()
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

        /**
         * Verlängert den laufenden Space (Paket 3.6a). Liefert den Grund, wenn die Regel nicht gilt (30 Tage ab Start, Ende in der
         * Zukunft, nach dem bisherigen Ende, aktiver Space). Sonst `null`: der Dienst speichert die neue Endzeit.
         */
        fun extend(choice: ExtendChoice): ExtendProblem? {
            val record = state.value.record ?: return ExtendProblem.NOT_ACTIVE
            val current = record.expiresAt ?: return ExtendProblem.NOT_ACTIVE
            val newEnd =
                when (choice) {
                    ExtendChoice.PlusHour -> current + HOUR_MILLIS
                    ExtendChoice.PlusDay -> current + DAY_MILLIS
                    is ExtendChoice.Until -> choice.endsAtMillis
                }
            val problem =
                SpaceRunPolicy.extendProblem(
                    status = record.status,
                    startedAtMillis = record.startedAt,
                    currentEndsAtMillis = current,
                    newEndsAtMillis = newEnd,
                    nowWallMillis = clock.wallMillis(),
                )
            if (problem == null) controller.extend(record.id, newEnd)
            return problem
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
            const val TICK_MILLIS = 15_000L
            const val HOUR_MILLIS = 3_600_000L
            const val DAY_MILLIS = 86_400_000L
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
