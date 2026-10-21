package io.github.labushuya.emberlight.feature.space

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.labushuya.emberlight.feature.space.retention.RetentionReminders
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.management.SpaceRecord
import io.github.labushuya.emberlight.share.space.management.SpaceRepository
import io.github.labushuya.emberlight.share.space.retention.PurgeResult
import io.github.labushuya.emberlight.share.space.retention.RetentionChange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** Zustand des Detail: der gewählte Space, sobald geladen. [record] ist `null`, wenn es ihn nicht (mehr) gibt. */
data class SpaceDetailUiState(
    val selected: SpaceId?,
    val loading: Boolean,
    val record: SpaceRecord?,
) {
    companion object {
        val NOTHING_SELECTED = SpaceDetailUiState(selected = null, loading = false, record = null)
    }
}

/**
 * Detail eines Space. Die Auswahl kommt aus der Route oder, auf großen Displays, aus der Liste daneben ([select]).
 * Löschen entfernt Einträge und Speicher (Paket 3.2). Für beendete Spaces gilt die Aufbewahrung (Paket 3.6b): Frist
 * ändern und „Jetzt löschen“ nach Bestätigung.
 */
@HiltViewModel
class SpaceDetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: SpaceRepository,
        private val retention: RetentionReminders,
    ) : ViewModel() {
        private val selected = MutableStateFlow(savedStateHandle.get<String>(ARG_ID)?.let { SpaceId.parse(it) })

        /** Die Bestätigung „Jetzt löschen“ öffnet sich gleich, wenn die Route es verlangt (Benachrichtigung). */
        val confirmPurgeRequested: Boolean = savedStateHandle.get<Boolean>(ARG_CONFIRM_PURGE) ?: false

        /** Ob Erinnerungen erlaubt sind. Ohne Erlaubnis zeigt das Detail einen Hinweis. */
        val notificationsAllowed: Boolean = retention.notificationsAllowed()

        private val purgeFailed = MutableStateFlow(false)

        /** Die Löschung ist gescheitert. Sie wird beim nächsten Start fortgesetzt. */
        val failure: StateFlow<Boolean> = purgeFailed

        @OptIn(ExperimentalCoroutinesApi::class)
        val state: StateFlow<SpaceDetailUiState> =
            selected
                .flatMapLatest { id ->
                    if (id == null) {
                        flowOf(SpaceDetailUiState.NOTHING_SELECTED)
                    } else {
                        repository.observe(id).map { record ->
                            SpaceDetailUiState(id, loading = false, record = record)
                        }
                    }
                }.stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    SpaceDetailUiState(selected = selected.value, loading = selected.value != null, record = null),
                )

        /** Wählt einen Space. `null` hebt die Auswahl auf. Ungültige Kennungen gelten als keine Auswahl. */
        fun select(id: String?) {
            selected.value = id?.let { SpaceId.parse(it) }
        }

        /** Titelbild für die Anzeige, oder `null`. */
        fun coverFile(record: SpaceRecord?): File? = record?.coverSha256?.let { repository.coverFile(record.id, it) }

        /** Löscht den gewählten Space samt Speicher und ruft danach [onDone] auf. */
        fun delete(onDone: () -> Unit) {
            val id = selected.value ?: return
            viewModelScope.launch {
                repository.delete(id)
                selected.value = null
                onDone()
            }
        }

        /** Setzt die Frist des gewählten beendeten Space in Tagen (0 für „sofort“). Liefert das Ergebnis an [onResult]. */
        fun changeRetention(
            days: Int,
            onResult: (RetentionChange) -> Unit = {},
        ) {
            val id = selected.value ?: return
            viewModelScope.launch {
                onResult(retention.changeRetention(id, days, System.currentTimeMillis()))
            }
        }

        /** Löscht den gewählten beendeten Space nach der Bestätigung. Danach [onDone]. Bei Fehler bleibt die Anzeige. */
        fun purge(onDone: () -> Unit) {
            val id = selected.value ?: return
            viewModelScope.launch {
                try {
                    when (retention.purge(id)) {
                        PurgeResult.Purged -> {
                            purgeFailed.value = false
                            selected.value = null
                            onDone()
                        }

                        PurgeResult.NotEnded -> {
                            purgeFailed.value = true
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failed: Exception) {
                    // Jeder andere Fehler lässt den Space stehen. Der Start setzt die Löschung fort.
                    purgeFailed.value = true
                }
            }
        }

        private companion object {
            const val ARG_ID = "id"
            const val ARG_CONFIRM_PURGE = "confirmPurge"
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
