package io.github.labushuya.emberlight.feature.space.setup

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.labushuya.emberlight.feature.space.run.SpaceStarter
import io.github.labushuya.emberlight.feature.space.run.StartOutcome
import io.github.labushuya.emberlight.share.space.SpaceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Zustand der Einrichtung. [outcome] ist ein Fehler des letzten Startversuchs, bis die Oberfläche ihn zeigt. */
data class SpaceSetupUiState(
    val items: List<SetupItem> = emptyList(),
    val gate: StartGate = StartGate(blocked = true, recommendedOpen = emptyList()),
    val starting: Boolean = false,
    val outcome: StartOutcome? = null,
)

/**
 * Einrichtung eines Space (Mockup Abschnitt 3). Die Punkte werden bei jedem Zurückkehren neu geprüft ([refresh]). Der Start
 * prüft die Vorbedingungen des Betriebs, die Einrichtung hat die Oberfläche vorher geprüft.
 */
@HiltViewModel
class SpaceSetupViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val starter: SpaceStarter,
        /** Speicher der Einrichtung. Die Oberfläche baut daraus die Umgebung für [refresh]. */
        val store: SetupStore,
    ) : ViewModel() {
        /** Der Entwurf, zu dem die Einrichtung gehört. Ungültige Kennungen gelten als nicht gesetzt. */
        val spaceId: SpaceId? = savedStateHandle.get<String>(ARG_ID)?.let { SpaceId.parse(it) }

        /** `true`, wenn die Einrichtung vor dem Start steht und der Start-Knopf erscheint. */
        val startMode: Boolean = savedStateHandle.get<Boolean>(ARG_START) ?: false

        private val mutableState = MutableStateFlow(SpaceSetupUiState())
        val state: StateFlow<SpaceSetupUiState> = mutableState.asStateFlow()

        /** Prüft alle Punkte neu. Die Oberfläche ruft das bei jedem Zurückkehren in den Bildschirm auf. */
        fun refresh(env: SetupEnvironment) {
            val items = SetupChecks.items(env)
            mutableState.update { it.copy(items = items, gate = SetupChecks.gate(items)) }
        }

        /** Speichert die Selbstbestätigung der Hersteller-Freigabe. Danach ist [refresh] nötig. */
        fun confirmOem(confirmed: Boolean) {
            store.setOemConfirmed(confirmed)
        }

        /** Merkt, dass eine Berechtigung angefragt wurde. Ohne das ließe sich „dauerhaft abgelehnt“ nicht erkennen. */
        fun markAsked(permission: String) {
            store.markAsked(permission)
        }

        /** Startet den Space. Bei Erfolg ruft [onStarted] auf, bei Fehlern bleibt der Grund im Zustand. */
        fun start(onStarted: () -> Unit) {
            val id = spaceId ?: return
            if (mutableState.value.starting) return
            viewModelScope.launch {
                mutableState.update { it.copy(starting = true, outcome = null) }
                val outcome = starter.start(id)
                mutableState.update { it.copy(starting = false, outcome = outcome.takeUnless { it is StartOutcome.Started }) }
                if (outcome is StartOutcome.Started) onStarted()
            }
        }

        /** Die Oberfläche hat den Fehler gezeigt. */
        fun dismissOutcome() {
            mutableState.update { it.copy(outcome = null) }
        }

        private companion object {
            const val ARG_ID = "id"
            const val ARG_START = "startMode"
        }
    }
