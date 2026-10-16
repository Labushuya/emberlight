package io.github.labushuya.emberlight.feature.gallery.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.labushuya.emberlight.core.media.MediaLibraryState
import io.github.labushuya.emberlight.core.media.MediaRepository
import io.github.labushuya.emberlight.feature.gallery.di.DefaultDispatcher
import io.github.labushuya.emberlight.feature.gallery.permission.PermissionPromptStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * Bildet den Medienindex auf den [TimelineUiState] ab. Die Gruppierung läuft auf [DefaultDispatcher], das Ergebnis
 * ist unveränderlich. Der Flow stoppt, wenn niemand mehr zuschaut.
 */
@HiltViewModel
internal class TimelineViewModel
    @Inject
    constructor(
        private val repository: MediaRepository,
        private val permissionPrompts: PermissionPromptStore,
        @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
        private val clock: Clock,
        private val zone: ZoneId,
    ) : ViewModel() {
        private val columns = MutableStateFlow(DEFAULT_COLUMNS)
        private val rationaleShown = MutableStateFlow(false)

        /** Beim ersten Vordergrund hat der Index gerade erst begonnen zu laden, dann ist kein Neulesen nötig. */
        private var resumedBefore = false

        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState: StateFlow<TimelineUiState> =
            combine(
                repository.library,
                permissionPrompts.askedBefore,
                rationaleShown,
                columns,
            ) { library, asked, rationale, count ->
                Snapshot(library, asked, rationale, count)
            }.mapLatest { snapshot ->
                withContext(defaultDispatcher) {
                    timelineUiState(
                        library = snapshot.library,
                        askedBefore = snapshot.askedBefore,
                        showRationale = snapshot.showRationale,
                        columns = snapshot.columns,
                        today = LocalDate.now(clock.withZone(zone)),
                        zone = zone,
                    )
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TimelineUiState.Loading)

        /** Aktuelle Spaltenzahl. Nur die Standardstufe ist in 3a erreichbar. */
        fun onColumnsChanged(count: Int) {
            columns.value = count
        }

        /**
         * Rückkehr in den Vordergrund, etwa aus den Systemeinstellungen. Ändert sich dort die Berechtigung, greift sie
         * sofort, weil der Index neu liest.
         */
        fun onResumed(showRationale: Boolean) {
            rationaleShown.value = showRationale
            if (resumedBefore) repository.refresh()
            resumedBefore = true
        }

        /**
         * Der Systemdialog ist beantwortet. Die Anfrage gilt ab jetzt als gestellt, und der Index liest die Berechtigung neu.
         * [showRationale] ist der Stand nach der Antwort: `true` heißt, der Dialog darf erneut erscheinen.
         */
        fun onPermissionResult(showRationale: Boolean) {
            rationaleShown.value = showRationale
            repository.refresh()
            viewModelScope.launch { permissionPrompts.markAsked() }
        }

        private class Snapshot(
            val library: MediaLibraryState,
            val askedBefore: Boolean,
            val showRationale: Boolean,
            val columns: Int,
        )

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
