package io.github.labushuya.emberlight.feature.gallery.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.labushuya.emberlight.core.media.MediaLibraryState
import io.github.labushuya.emberlight.core.media.MediaRepository
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

/** Eingaben einer Rasteransicht. Alles, was den Zustand bestimmt, außer dem Geltungsbereich. */
internal class GridInputs(
    val library: MediaLibraryState,
    val askedBefore: Boolean,
    val showRationale: Boolean,
    val zoomLevel: Int,
)

/**
 * Gemeinsame Logik der Rasteransichten: Galerie und Album (ADR 0009). Die Basisklasse hält den Index, die Berechtigung
 * und die Zoomstufe, bildet sie über [mediaGridState] auf den Zustand des Rasters ab und gruppiert auf
 * [defaultDispatcher]. Die Zoomstufe liegt im gemeinsamen [ZoomLevelStore], daher wirkt ein Wechsel in einer Ansicht
 * auch in der anderen.
 *
 * Eine Unterklasse legt den Geltungsbereich fest und formt aus [GridInputs] und dem Rasterzustand ihren eigenen Zustand
 * über [viewState]. Der Flow stoppt, wenn niemand mehr zuschaut.
 */
internal abstract class MediaGridViewModel(
    private val repository: MediaRepository,
    private val permissionPrompts: PermissionPromptStore,
    private val zoomLevels: ZoomLevelStore,
    private val defaultDispatcher: CoroutineDispatcher,
    private val clock: Clock,
    val zone: ZoneId,
    private val scope: MediaScope,
) : ViewModel() {
    private val rationaleShown = MutableStateFlow(false)
    private val grouper = TimelineGroupingCache()
    private val scopedItems = ScopedItems(scope)

    /** Beim ersten Vordergrund hat der Index gerade erst begonnen zu laden, dann ist kein Neulesen nötig. */
    private var resumedBefore = false

    private val inputs: Flow<GridInputs> =
        combine(
            repository.library,
            permissionPrompts.askedBefore,
            rationaleShown,
            zoomLevels.level,
        ) { library, asked, rationale, level ->
            GridInputs(library = library, askedBefore = asked, showRationale = rationale, zoomLevel = level)
        }

    /**
     * Zustand einer Ansicht aus den Eingaben und dem Rasterzustand. Die Abbildung läuft auf dem Default-Dispatcher.
     * [initial] gilt, bis die erste Abbildung vorliegt.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    protected fun <T> viewState(
        initial: T,
        map: (GridInputs, MediaGridState) -> T,
    ): StateFlow<T> =
        inputs
            .mapLatest { input ->
                withContext(defaultDispatcher) { map(input, gridStateOf(input)) }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), initial)

    /**
     * Neue Zoomstufe, etwa durch Pinch oder Bedienungshilfe. Sie gilt für alle Ansichten und wird gespeichert. Ungültige
     * Werte werden begrenzt.
     */
    fun onZoomLevelChanged(level: Int) {
        viewModelScope.launch { zoomLevels.setLevel(ZoomLevels.clamp(level)) }
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

    private fun gridStateOf(input: GridInputs): MediaGridState =
        mediaGridState(
            library = input.library,
            scope = scope,
            askedBefore = input.askedBefore,
            showRationale = input.showRationale,
            zoomLevel = input.zoomLevel,
            today = LocalDate.now(clock.withZone(zone)),
            zone = zone,
            grouper = grouper,
            scopedItems = scopedItems,
        )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
