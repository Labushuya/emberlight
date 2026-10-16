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
import kotlinx.coroutines.flow.Flow
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
 * Bildet den Medienindex und die Zoomstufe auf den [TimelineUiState] ab. Die Gruppierung läuft auf [DefaultDispatcher]
 * und wird je Art einmal berechnet ([TimelineGroupingCache]), damit das Umschalten der Stufe flott bleibt. Der Flow stoppt,
 * wenn niemand mehr zuschaut.
 */
@HiltViewModel
internal class TimelineViewModel
    @Inject
    constructor(
        private val repository: MediaRepository,
        private val permissionPrompts: PermissionPromptStore,
        private val zoomLevels: ZoomLevelStore,
        @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
        private val clock: Clock,
        private val zone: ZoneId,
    ) : ViewModel() {
        /** Stufe, die der Nutzer gewählt hat. `null`, solange nichts gewählt wurde: dann gilt die gespeicherte. */
        private val userLevel = MutableStateFlow<Int?>(null)

        /**
         * Gewählte oder gespeicherte Stufe, begrenzt. Eine späte Antwort des Speichers überschreibt keine Nutzerwahl. Der
         * Speicher wird nur gelesen, nicht über eine eigene Coroutine: so startet die Zeitleiste ohne Zusatzarbeit.
         */
        private val zoomLevel: Flow<Int> =
            combine(userLevel, zoomLevels.level) { chosen, saved -> ZoomLevels.clamp(chosen ?: saved) }

        private val rationaleShown = MutableStateFlow(false)
        private val grouper = TimelineGroupingCache()

        /** Beim ersten Vordergrund hat der Index gerade erst begonnen zu laden, dann ist kein Neulesen nötig. */
        private var resumedBefore = false

        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState: StateFlow<TimelineUiState> =
            combine(
                repository.library,
                permissionPrompts.askedBefore,
                rationaleShown,
                zoomLevel,
            ) { library, asked, rationale, level ->
                Snapshot(library, asked, rationale, level)
            }.mapLatest { snapshot ->
                withContext(defaultDispatcher) {
                    timelineUiState(
                        library = snapshot.library,
                        askedBefore = snapshot.askedBefore,
                        showRationale = snapshot.showRationale,
                        zoomLevel = snapshot.zoomLevel,
                        today = LocalDate.now(clock.withZone(zone)),
                        zone = zone,
                        grouper = grouper,
                    )
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TimelineUiState.Loading)

        /**
         * Neue Zoomstufe, etwa durch Pinch oder Bedienungshilfe. Wird sofort gespeichert. Ungültige Werte werden begrenzt.
         */
        fun onZoomLevelChanged(level: Int) {
            val clamped = ZoomLevels.clamp(level)
            if (userLevel.value == clamped) return
            userLevel.value = clamped
            viewModelScope.launch { zoomLevels.setLevel(clamped) }
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
            val zoomLevel: Int,
        )

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
