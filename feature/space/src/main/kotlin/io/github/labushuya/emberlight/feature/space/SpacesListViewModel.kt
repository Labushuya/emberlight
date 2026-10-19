package io.github.labushuya.emberlight.feature.space

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.labushuya.emberlight.share.space.management.SpaceRecord
import io.github.labushuya.emberlight.share.space.management.SpaceRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.io.File
import javax.inject.Inject

/** Liste aller Spaces, neueste zuerst. Die Liste hält keinen Zustand außer dem Stand des Speichers. */
@HiltViewModel
class SpacesListViewModel
    @Inject
    constructor(
        private val repository: SpaceRepository,
    ) : ViewModel() {
        val spaces: StateFlow<List<SpaceRecord>> =
            repository
                .observeAll()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

        /** Titelbild eines Space für die Anzeige, oder `null`. Die Datei liegt im Space-Speicher. */
        fun coverFile(record: SpaceRecord): File? = record.coverSha256?.let { repository.coverFile(record.id, it) }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
