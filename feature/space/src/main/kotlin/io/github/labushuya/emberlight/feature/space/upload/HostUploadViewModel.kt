package io.github.labushuya.emberlight.feature.space.upload

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.labushuya.emberlight.share.space.IngestResult
import io.github.labushuya.emberlight.share.space.RejectReason
import io.github.labushuya.emberlight.share.space.SpaceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Zustand einer Datei in der Reihe. */
sealed interface UploadStatus {
    /** Wartet auf die Reihe. */
    data object Waiting : UploadStatus

    /** Wird gerade hochgeladen. Der Fortschritt steht am Eintrag. */
    data object Uploading : UploadStatus

    /** Gespeichert. [warning] ist gesetzt, wenn der Space die Warnschwelle erreicht hat. */
    data class Stored(
        val warning: Boolean,
    ) : UploadStatus

    /** Gleicher Inhalt liegt schon im Space. Die Datei wird nicht noch einmal gespeichert. */
    data object Duplicate : UploadStatus

    /** Abgelehnt, mit dem Grund aus der Vault (Format, Größe, Speicher, Standort). */
    data class Rejected(
        val reason: RejectReason,
    ) : UploadStatus

    /** Das System gibt die Datei nicht heraus. */
    data object Unreadable : UploadStatus

    /** Der Standort ließ sich nicht entfernen, und der Gastgeber hat nicht hochgeladen. */
    data object Skipped : UploadStatus

    /** Die Reihe ist vor dieser Datei beendet worden. */
    data class Stopped(
        val reason: StopReason,
    ) : UploadStatus
}

/** Warum eine Reihe vor einer Datei endet. */
enum class StopReason {
    /** Der Gastgeber hat abgebrochen. */
    CANCELLED,

    /** Speicher voll oder Gerät knapp. */
    STORAGE_FULL,

    /** Der Space läuft nicht mehr (Ende, Pause, Ablauf). */
    NOT_RUNNING,
}

/** Eintrag der Reihe: Name (sobald gelesen), Zustand und Fortschritt. */
data class UploadItem(
    val uri: String,
    val name: String,
    val status: UploadStatus,
    val progress: UploadProgress? = null,
)

/** Zustand des Gastgeber-Uploads für die Anzeige. [locationQuestion] ist der Name der Datei, zu der die Rückfrage offen ist. */
data class HostUploadUiState(
    val items: List<UploadItem> = emptyList(),
    val running: Boolean = false,
    val locationQuestion: String? = null,
) {
    /** Mindestens eine Datei hat die Warnschwelle des Speichers ausgelöst. */
    val storageWarning: Boolean get() = items.any { it.status == UploadStatus.Stored(warning = true) }
}

/**
 * Gastgeber-Upload der ausgewählten Dateien (Paket 3.5c). Die Dateien laufen nacheinander, damit Speicher und Sperre nicht
 * um Bytes wetteifern. Ist der Standort einer Datei nicht entfernbar, fragt die Oberfläche pro Datei nach. Bei Ja lädt die
 * Datei mit Einwilligung hoch, bei Nein nicht. Abbrechen stoppt die Reihe und die laufende Datei.
 */
@HiltViewModel
class HostUploadViewModel
    @Inject
    constructor(
        private val port: HostUploadPort,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(HostUploadUiState())

        /** Zustand der Reihe. */
        val state: StateFlow<HostUploadUiState> = mutableState.asStateFlow()

        private var job: Job? = null
        private var answer: CompletableDeferred<Boolean>? = null

        /** Reiht Dateien ein. Läuft schon eine Reihe, hängen sie sich an. Sonst beginnt eine neue Anzeige. */
        fun enqueue(
            spaceId: SpaceId,
            uris: List<String>,
        ) {
            if (uris.isEmpty()) return
            val added = uris.map { UploadItem(uri = it, name = "", status = UploadStatus.Waiting) }
            if (job?.isActive == true) {
                mutableState.update { it.copy(items = it.items + added) }
                return
            }
            mutableState.value = HostUploadUiState(items = added, running = true)
            job = viewModelScope.launch { runBatch(spaceId) }
        }

        /** Bricht die Reihe ab. Die laufende Datei wird verworfen. Ihre Teildatei räumt die Vault. */
        fun cancel() {
            job?.cancel()
        }

        /** Antwort auf die Standort-Rückfrage: [keep] `true` heißt „Mit Standort hochladen“. */
        fun answerLocation(keep: Boolean) {
            answer?.complete(keep)
        }

        private suspend fun runBatch(spaceId: SpaceId) {
            var stop = StopReason.CANCELLED
            try {
                while (true) {
                    val index = mutableState.value.items.indexOfFirst { it.status == UploadStatus.Waiting }
                    if (index < 0) break
                    if (!port.canUpload(spaceId)) {
                        stop = StopReason.NOT_RUNNING
                        break
                    }
                    setName(index, port.describe(mutableState.value.items[index].uri).name)
                    setStatus(index, UploadStatus.Uploading)
                    val status = uploadOne(spaceId, index)
                    setStatus(index, status)
                    if (status is UploadStatus.Rejected && HostUploadRules.stopsTheBatch(status.reason)) {
                        stop = StopReason.STORAGE_FULL
                        break
                    }
                }
            } finally {
                // Ende, Stopp oder Abbruch: Was noch wartet oder läuft, bekommt den Grund.
                finish(stop)
            }
        }

        /** Lädt eine Datei. Verlangt der Standort eine Einwilligung, fragt die Oberfläche vorher pro Datei. */
        private suspend fun uploadOne(
            spaceId: SpaceId,
            index: Int,
        ): UploadStatus {
            val uri = mutableState.value.items[index].uri
            val first = port.upload(spaceId, uri, keepLocation = false) { progress(index, it) }
            if (first.needsLocationConsent()) {
                if (!askLocation(mutableState.value.items[index].name)) return UploadStatus.Skipped
                // Mit Einwilligung kann der Standort nicht mehr abgelehnt werden. Das Ergebnis ist endgültig.
                val second = port.upload(spaceId, uri, keepLocation = true) { progress(index, it) }
                return second.toStatus()
            }
            return first.toStatus()
        }

        /** Wartet auf die Antwort der Oberfläche. Ein Abbruch der Reihe beendet das Warten. */
        private suspend fun askLocation(name: String): Boolean {
            val deferred = CompletableDeferred<Boolean>()
            answer = deferred
            mutableState.update { it.copy(locationQuestion = name) }
            try {
                return deferred.await()
            } finally {
                answer = null
                mutableState.update { it.copy(locationQuestion = null) }
            }
        }

        private fun progress(
            index: Int,
            progress: UploadProgress,
        ) {
            mutableState.update { state ->
                state.copy(
                    items =
                        state.items.mapIndexed { i, item ->
                            if (i ==
                                index
                            ) {
                                item.copy(progress = progress)
                            } else {
                                item
                            }
                        },
                )
            }
        }

        private fun setName(
            index: Int,
            name: String,
        ) {
            mutableState.update { state ->
                state.copy(
                    items =
                        state.items.mapIndexed { i, item ->
                            if (i ==
                                index
                            ) {
                                item.copy(name = name)
                            } else {
                                item
                            }
                        },
                )
            }
        }

        private fun setStatus(
            index: Int,
            status: UploadStatus,
        ) {
            mutableState.update { state ->
                state.copy(
                    items =
                        state.items.mapIndexed { i, item ->
                            if (i == index) item.copy(status = status, progress = null) else item
                        },
                )
            }
        }

        /** Schließt die Reihe: offene Dateien bekommen [reason], die Anzeige läuft nicht mehr. */
        private fun finish(reason: StopReason) {
            mutableState.update { state ->
                state.copy(
                    running = false,
                    locationQuestion = null,
                    items =
                        state.items.map { item ->
                            if (item.status == UploadStatus.Waiting || item.status == UploadStatus.Uploading) {
                                item.copy(status = UploadStatus.Stopped(reason), progress = null)
                            } else {
                                item
                            }
                        },
                )
            }
        }

        private fun PickedUploadOutcome.needsLocationConsent(): Boolean {
            val rejected = (this as? PickedUploadOutcome.Ingested)?.result as? IngestResult.Rejected ?: return false
            return rejected.reason is RejectReason.LocationNotRemovable
        }

        private fun PickedUploadOutcome.toStatus(): UploadStatus =
            when (this) {
                PickedUploadOutcome.Unreadable -> {
                    UploadStatus.Unreadable
                }

                is PickedUploadOutcome.Ingested -> {
                    when (val result = result) {
                        is IngestResult.Created -> UploadStatus.Stored(warning = result.warning != null)
                        is IngestResult.Duplicate -> UploadStatus.Duplicate
                        is IngestResult.Rejected -> UploadStatus.Rejected(result.reason)
                    }
                }
            }
    }
