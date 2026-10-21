package io.github.labushuya.emberlight.feature.space.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.labushuya.emberlight.feature.space.run.SpaceHostActions
import io.github.labushuya.emberlight.share.space.MediaId
import io.github.labushuya.emberlight.share.space.MediaRow
import io.github.labushuya.emberlight.share.space.Role
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.preview.PreviewRules
import io.github.labushuya.emberlight.share.space.preview.PreviewState
import io.github.labushuya.emberlight.share.space.run.SpaceRunRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** Filter der Medienansicht: alle Medien oder nur die eigenen (Paket 3.5c, mockup „Alle“ und „Meine“). */
enum class MediaFilter {
    ALL,
    MINE,
}

/** Eine Kachel der Medienansicht. [previewFile] ist `null`, solange die Vorschau nicht bereit ist. */
data class HostMediaItem(
    val id: String,
    val isVideo: Boolean,
    val isHeic: Boolean,
    val mine: Boolean,
    val uploaderName: String?,
    val uploaderRemoved: Boolean,
    val locationKept: Boolean,
    val previewState: String?,
    val previewFile: File?,
    val sizeBytes: Long,
    val createdAt: Long,
    /**
     * Original für den Rückfall (Hotfix 2): nur bei einem Bild mit fehlgeschlagener Vorschau. Die Kachel verkleinert es
     * beim Laden. Scheitert auch das, zeigt sie „Keine Vorschau“.
     */
    val originalFile: File? = null,
)

/** Zustand der Medienansicht. [loaded] ist `false`, bis die erste Antwort des Index da ist. */
data class HostMediaUiState(
    val loaded: Boolean = false,
    val filter: MediaFilter = MediaFilter.ALL,
    val items: List<HostMediaItem> = emptyList(),
) {
    /** Die Kacheln, die der Filter zeigt. */
    val visible: List<HostMediaItem>
        get() = if (filter == MediaFilter.MINE) items.filter { it.mine } else items
}

/** Datei für die Detailansicht. [isVideo] wählt den Player, sonst das Bild. */
data class DetailMedia(
    val file: File,
    val isVideo: Boolean,
)

/**
 * Medienansicht des laufenden Space (Paket 3.5c). Die Liste folgt dem Index: Jede Änderung an Medien oder Teilnehmern
 * erscheint sofort (Flow aus Room, ADR 0019). Der Gastgeber kann alles entfernen, Gäste nicht. Die Entscheidung, ob
 * Änderungen erlaubt sind (laufender Space), trifft die Oberfläche.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HostMediaViewModel
    @Inject
    constructor(
        private val runs: SpaceRunRepository,
        private val hostActions: SpaceHostActions,
        private val store: HostMediaStore,
    ) : ViewModel() {
        private val spaceId = MutableStateFlow<SpaceId?>(null)
        private val filter = MutableStateFlow(MediaFilter.ALL)

        /** Zustand der Ansicht. Ohne [show] ist er leer. */
        val state: StateFlow<HostMediaUiState> =
            combine(
                spaceId.flatMapLatest { id ->
                    id?.let { observe(it) } ?: flowOf(HostMediaUiState())
                },
                filter,
            ) { base, f ->
                base.copy(filter = f)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), HostMediaUiState())

        /** Zeigt die Medien des Space [id]. Ein erneuter Aufruf mit demselben Space ändert nichts. */
        fun show(id: SpaceId) {
            spaceId.value = id
        }

        fun setFilter(value: MediaFilter) {
            filter.value = value
        }

        /** Entfernt ein Medium. Der Gastgeber darf jedes (ADR 0014). Die Liste aktualisiert sich über den Index. */
        fun delete(mediaId: String) {
            val space = spaceId.value ?: return
            val media = MediaId.parse(mediaId) ?: return
            viewModelScope.launch { hostActions.deleteMedia(space, media) }
        }

        /** Datei für die Detailansicht, `null` wenn das Medium fehlt oder die Ansicht noch nicht bereit ist. */
        suspend fun detailOf(mediaId: String): DetailMedia? {
            val space = spaceId.value ?: return null
            val media = MediaId.parse(mediaId) ?: return null
            val item = state.value.items.firstOrNull { it.id == mediaId } ?: return null
            // Rückfall auf das Original, wenn die Vorschau fehlgeschlagen ist (Hotfix 2). Videos bleiben ohne Rückfall.
            val file =
                store.detailFile(space, media, heic = item.isHeic)
                    ?: item.originalFile?.takeIf { item.previewState == PreviewState.FAILED }
                    ?: return null
            return DetailMedia(file, isVideo = item.isVideo)
        }

        /**
         * Medien und Teilnehmer kommen beide live aus dem Index, derselben Datenbank wie der Server. Ein Beitritt, eine
         * Entfernung oder ein Upload erscheint daher ohne erneutes Öffnen. Der Gastgeber für „Meine“ ist der Teilnehmer
         * mit der Rolle Gastgeber.
         */
        private fun observe(id: SpaceId): Flow<HostMediaUiState> =
            combine(runs.observeMedia(id, MEDIA_LIMIT), runs.observeParticipants(id)) { rows, people ->
                rows to people.firstOrNull { it.role == Role.HOST }?.id
            }.mapLatest { (rows, hostId) ->
                HostMediaUiState(
                    loaded = true,
                    items =
                        rows.map { row ->
                            toItem(
                                id,
                                row,
                                mine = hostId != null && row.uploaderId == hostId.value,
                            )
                        },
                )
            }

        private suspend fun toItem(
            id: SpaceId,
            row: MediaRow,
            mine: Boolean,
        ): HostMediaItem {
            val ready = row.previewState == PreviewState.READY
            return HostMediaItem(
                id = row.id,
                isVideo = PreviewRules.isVideo(row.mime),
                isHeic = PreviewRules.hasHeicView(row.mime),
                mine = mine,
                uploaderName = row.uploaderName,
                uploaderRemoved = row.uploaderRevoked,
                locationKept = row.locationKept,
                previewState = row.previewState,
                previewFile = if (ready) MediaId.parse(row.id)?.let { store.previewFile(id, it) } else null,
                sizeBytes = row.sizeBytes,
                createdAt = row.createdAt,
                originalFile = originalFor(id, row),
            )
        }

        /** Original nur für ein Bild mit fehlgeschlagener Vorschau (Hotfix 2). */
        private suspend fun originalFor(
            id: SpaceId,
            row: MediaRow,
        ): File? {
            if (row.previewState != PreviewState.FAILED || PreviewRules.isVideo(row.mime)) return null
            return MediaId.parse(row.id)?.let { store.originalFile(id, it) }
        }

        private companion object {
            /** Erste Seite des Index. Mehr Medien braucht Nachladen, das ist offen (Testkatalog, Abschnitt 6). */
            const val MEDIA_LIMIT = 200
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
