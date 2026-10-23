package io.github.labushuya.emberlight.feature.space.export

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import androidx.work.WorkInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.labushuya.emberlight.feature.space.SpaceExportRoute
import io.github.labushuya.emberlight.feature.space.media.HostMediaStore
import io.github.labushuya.emberlight.share.export.MediaStoreExportSink
import io.github.labushuya.emberlight.share.space.MediaEntity
import io.github.labushuya.emberlight.share.space.MediaId
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpaceLimits
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.export.ExportCandidate
import io.github.labushuya.emberlight.share.space.export.ExportSelection
import io.github.labushuya.emberlight.share.space.export.ExportSpacePolicy
import io.github.labushuya.emberlight.share.space.management.SpaceStatus
import io.github.labushuya.emberlight.share.space.preview.PreviewRules
import io.github.labushuya.emberlight.share.space.preview.PreviewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Eine Kachel der Auswahl. [isVideo] wählt das Kennzeichen „Video“. [previewFile] ist die 320-px-Vorschau aus dem
 * Space-Speicher, [originalFile] der Rückfall bei fehlgeschlagener Vorschau (Bild). Beides `null` heißt: Platzhalter.
 */
data class ExportItemUi(
    val mediaId: String,
    val isVideo: Boolean,
    val sizeBytes: Long,
    val locationKept: Boolean,
    val exported: Boolean,
    val selected: Boolean,
    val previewState: String? = null,
    val previewFile: File? = null,
    val originalFile: File? = null,
)

/** Ergebnis einer Übernahme in Zahlen. */
data class ExportResultUi(
    val exported: Int,
    val skipped: Int,
    val failed: Int,
    val failedHash: Int,
    val failedWrite: Int = 0,
    val failedMissing: Int = 0,
    val failedUnknown: Int = 0,
    val stoppedByPurge: Boolean,
)

/** Phase der Übernahme. Nur eine ist gleichzeitig sichtbar. */
sealed interface ExportPhase {
    data object Idle : ExportPhase

    data class Running(
        val doneFiles: Int,
        val totalFiles: Int,
        val doneBytes: Long,
        val totalBytes: Long,
    ) : ExportPhase

    data class NoSpace(
        val requiredBytes: Long,
        val availableBytes: Long,
    ) : ExportPhase

    data class Finished(
        val result: ExportResultUi,
    ) : ExportPhase

    data object Cancelled : ExportPhase

    data object Failed : ExportPhase

    data object NotExportable : ExportPhase
}

data class SpaceExportUiState(
    val loading: Boolean = true,
    val spaceName: String = "",
    val items: List<ExportItemUi> = emptyList(),
    val selectedCount: Int = 0,
    val selectedBytes: Long = 0L,
    val withLocationCount: Int = 0,
    val phase: ExportPhase = ExportPhase.Idle,
) {
    /** Der Knopf „Übernehmen“ ist aktiv, wenn etwas gewählt ist und gerade nichts läuft. */
    val canStart: Boolean get() = selectedCount > 0 && phase !is ExportPhase.Running
}

/**
 * Auswahl der Medien zur Übernahme in die Galerie (Paket 3.7, ADR 0022). Vorauswahl ist nichts. „Alle“ und „Nichts“
 * ändern nur die Auswahl. Übernehmen prüft den Platz und reiht den Auftrag ein. Der Stand kommt aus WorkManager.
 */
@HiltViewModel
class SpaceExportViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val database: SpacesDatabase,
        @ApplicationContext private val context: Context,
        private val store: HostMediaStore,
    ) : ViewModel() {
        private val spaceId: String = savedStateHandle.toRoute<SpaceExportRoute>().id
        private val selectionState = MutableStateFlow(ExportSelection(emptyList()))
        private val _state = MutableStateFlow(SpaceExportUiState())
        val state: StateFlow<SpaceExportUiState> = _state.asStateFlow()

        private var exportable = true

        /** Vorschau-Dateien je Medium, beim Laden aus dem Space-Speicher gelesen (ADR 0022, Vorschaubilder). */
        private var previews: Map<String, PreviewFiles> = emptyMap()

        init {
            viewModelScope.launch { load() }
            SpaceExportQueue
                .observe(context, spaceId)
                .onEach { info -> onWork(info) }
                .launchIn(viewModelScope)
        }

        fun selectAll() = update { it.selectAll() }

        fun selectNone() = update { it.selectNone() }

        fun toggle(mediaId: String) = update { it.toggle(mediaId) }

        fun cancel() {
            SpaceExportQueue.cancel(context, spaceId)
        }

        /** Prüft den Platz und reiht die Übernahme ein. Ohne Auswahl oder während eines Laufs geschieht nichts. */
        fun start() {
            val selection = selectionState.value
            if (selection.count == 0 || _state.value.phase is ExportPhase.Running || !exportable) return
            viewModelScope.launch {
                val available = withContext(Dispatchers.IO) { MediaStoreExportSink(context).availableBytes() }
                val required =
                    ExportSpacePolicy.requiredBytes(
                        selection.totalBytes,
                        SpaceLimits().minDeviceReserveBytes,
                    )
                if (!ExportSpacePolicy.fits(available, selection.totalBytes, SpaceLimits().minDeviceReserveBytes)) {
                    _state.update { it.copy(phase = ExportPhase.NoSpace(required, available)) }
                    return@launch
                }
                val ids = selection.chosen.map { it.mediaId }
                withContext(Dispatchers.IO) { SpaceExportQueue.enqueue(context, spaceId, ids) }
                _state.update {
                    it.copy(
                        phase = ExportPhase.Running(0, ids.size, 0L, selection.totalBytes),
                    )
                }
            }
        }

        private fun update(change: (ExportSelection) -> ExportSelection) {
            selectionState.value = change(selectionState.value)
            publish()
        }

        private suspend fun load() {
            val row = database.spaces().find(spaceId)
            exportable = row != null && !row.purging &&
                (row.status == SpaceStatus.ACTIVE.name || row.status == SpaceStatus.ENDED.name)
            val exported = database.exports().exportedIds(spaceId).toSet()
            val media =
                database
                    .media()
                    .allOf(spaceId)
                    .filter { it.state == READY }
                    .sortedWith(compareByDescending<MediaEntity> { it.createdAt }.thenBy { it.id })
            previews = media.associate { it.id to previewFilesOf(it) }
            val candidates =
                media
                    .map { medium ->
                        ExportCandidate(
                            mediaId = medium.id,
                            sizeBytes = medium.sizeBytes,
                            locationKept = medium.locationKept,
                            isVideo = medium.mime.startsWith("video/"),
                            alreadyExported = medium.id in exported,
                        )
                    }
            selectionState.value = ExportSelection(candidates)
            _state.update {
                it.copy(
                    loading = false,
                    spaceName = row?.name.orEmpty(),
                    phase = if (exportable) it.phase else ExportPhase.NotExportable,
                )
            }
            publish()
        }

        /**
         * Vorschau und Rückfall für ein Medium aus dem Space-Speicher, wie in der Medienansicht (ADR 0019, Hotfix 2): Die
         * Vorschau nur, wenn sie bereit ist. Das Original nur für ein Bild mit fehlgeschlagener Vorschau.
         */
        private suspend fun previewFilesOf(medium: MediaEntity): PreviewFiles {
            val space = runCatching { SpaceId(spaceId) }.getOrNull()
            val id = MediaId.parse(medium.id)
            if (space == null || id == null) return PreviewFiles(medium.previewState, null, null)
            val isVideo = PreviewRules.isVideo(medium.mime)
            val preview = if (medium.previewState == PreviewState.READY) store.previewFile(space, id) else null
            val original =
                if (medium.previewState == PreviewState.FAILED && !isVideo) store.originalFile(space, id) else null
            return PreviewFiles(medium.previewState, preview, original)
        }

        private fun publish() {
            val selection = selectionState.value
            _state.update { current ->
                current.copy(
                    items =
                        selection.candidates.map { candidate ->
                            val files = previews[candidate.mediaId]
                            ExportItemUi(
                                mediaId = candidate.mediaId,
                                isVideo = candidate.isVideo,
                                sizeBytes = candidate.sizeBytes,
                                locationKept = candidate.locationKept,
                                exported = candidate.alreadyExported,
                                selected = candidate.mediaId in selection.selected,
                                previewState = files?.previewState,
                                previewFile = files?.preview,
                                originalFile = files?.original,
                            )
                        },
                    selectedCount = selection.count,
                    selectedBytes = selection.totalBytes,
                    withLocationCount = selection.withLocationCount,
                )
            }
        }

        private suspend fun onWork(info: WorkInfo?) {
            if (info == null) return
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {}

                WorkInfo.State.RUNNING -> {
                    val progress = info.progress
                    _state.update {
                        it.copy(
                            phase =
                                ExportPhase.Running(
                                    doneFiles = progress.getInt(SpaceExportQueue.PROGRESS_DONE_FILES, 0),
                                    totalFiles = progress.getInt(SpaceExportQueue.PROGRESS_TOTAL_FILES, 0),
                                    doneBytes = progress.getLong(SpaceExportQueue.PROGRESS_DONE_BYTES, 0L),
                                    totalBytes = progress.getLong(SpaceExportQueue.PROGRESS_TOTAL_BYTES, 0L),
                                ),
                        )
                    }
                }

                WorkInfo.State.SUCCEEDED -> {
                    val out = info.outputData
                    val phase =
                        when (out.getString(SpaceExportQueue.OUT_OUTCOME)) {
                            SpaceExportQueue.OUTCOME_FINISHED -> {
                                ExportPhase.Finished(
                                    ExportResultUi(
                                        exported = out.getInt(SpaceExportQueue.OUT_EXPORTED, 0),
                                        skipped = out.getInt(SpaceExportQueue.OUT_SKIPPED, 0),
                                        failed = out.getInt(SpaceExportQueue.OUT_FAILED, 0),
                                        failedHash = out.getInt(SpaceExportQueue.OUT_FAILED_HASH, 0),
                                        failedWrite = out.getInt(SpaceExportQueue.OUT_FAILED_WRITE, 0),
                                        failedMissing = out.getInt(SpaceExportQueue.OUT_FAILED_MISSING, 0),
                                        failedUnknown = out.getInt(SpaceExportQueue.OUT_FAILED_UNKNOWN, 0),
                                        stoppedByPurge = out.getBoolean(SpaceExportQueue.OUT_STOPPED_BY_PURGE, false),
                                    ),
                                )
                            }

                            SpaceExportQueue.OUTCOME_NO_SPACE -> {
                                ExportPhase.NoSpace(
                                    requiredBytes = out.getLong(SpaceExportQueue.OUT_REQUIRED, 0L),
                                    availableBytes = out.getLong(SpaceExportQueue.OUT_AVAILABLE, 0L),
                                )
                            }

                            else -> {
                                ExportPhase.NotExportable
                            }
                        }
                    _state.update { it.copy(phase = phase) }
                    reloadExported()
                }

                WorkInfo.State.FAILED -> {
                    _state.update { it.copy(phase = ExportPhase.Failed) }
                }

                WorkInfo.State.CANCELLED -> {
                    _state.update { it.copy(phase = ExportPhase.Cancelled) }
                }
            }
        }

        /** Nach einer Übernahme: die neu kopierten Medien sind „bereits übernommen“ und lassen sich nicht erneut wählen. */
        private suspend fun reloadExported() {
            val exported = database.exports().exportedIds(spaceId).toSet()
            selectionState.value =
                selectionState.value.refreshed(
                    selectionState.value.candidates.map { it.copy(alreadyExported = it.mediaId in exported) },
                )
            publish()
        }

        private companion object {
            const val READY = "READY"
        }
    }

/** Dateien für die Kachel eines Mediums. Nur im ViewModel, die Oberfläche bekommt sie über [ExportItemUi]. */
private data class PreviewFiles(
    val previewState: String?,
    val preview: File?,
    val original: File?,
)
