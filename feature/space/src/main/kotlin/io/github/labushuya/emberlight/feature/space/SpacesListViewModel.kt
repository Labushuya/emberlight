package io.github.labushuya.emberlight.feature.space

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.labushuya.emberlight.feature.space.retention.RetentionReminders
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.management.SpaceRecord
import io.github.labushuya.emberlight.share.space.management.SpaceRepository
import io.github.labushuya.emberlight.share.space.retention.RemoveResult
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Liste aller Spaces, neueste zuerst. Der Zugriff auf die Liste prüft zuerst, ob Aufbewahrungen abgelaufen sind (Paket
 * 3.6c, ADR 0021). Sie werden dann gelöscht, bevor die Liste erscheint.
 */
@HiltViewModel
class SpacesListViewModel
    @Inject
    constructor(
        private val repository: SpaceRepository,
        private val retention: RetentionReminders,
    ) : ViewModel() {
        val spaces: StateFlow<List<SpaceRecord>> =
            repository
                .observeAll()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

        init {
            viewModelScope.launch { retention.checkNow(System.currentTimeMillis()) }
        }

        /** Titelbild eines Space für die Anzeige, oder `null`. Die Datei liegt im Space-Speicher. */
        fun coverFile(record: SpaceRecord): File? = record.coverSha256?.let { repository.coverFile(record.id, it) }

        /** Entfernt einen gelöschten Space aus der Liste, samt Titelbild. Liefert, ob er entfernt wurde. */
        suspend fun removeFromList(id: SpaceId): Boolean = retention.removeFromList(id) == RemoveResult.Removed

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
