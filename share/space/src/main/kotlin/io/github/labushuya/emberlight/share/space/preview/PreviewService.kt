package io.github.labushuya.emberlight.share.space.preview

import io.github.labushuya.emberlight.share.space.MediaDao
import io.github.labushuya.emberlight.share.space.MediaEntity
import io.github.labushuya.emberlight.share.space.MediaId
import io.github.labushuya.emberlight.share.space.SpaceLimits
import io.github.labushuya.emberlight.share.space.SpaceStorage
import io.github.labushuya.emberlight.share.space.StorageProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path

/** Ergebnis einer Anfrage nach einer Vorschau oder Ansicht (ADR 0019). */
sealed interface PreviewLookup {
    /** Das Medium fehlt oder ist nicht bereit. Die Route antwortet mit `not_found`. */
    data object NotFound : PreviewLookup

    /** Die Vorschau fehlt: `pending`, `failed` oder noch nicht eingereiht. Die Route antwortet mit `preview_unavailable`. */
    data object Unavailable : PreviewLookup

    /** Die Datei liegt bereit. */
    class Ready(
        val path: Path,
    ) : PreviewLookup
}

/**
 * Erzeugung der Vorschauen (Paket 3.5a-2, ADR 0019). Eine Warteschlange mit FIFO, ein Worker auf einem Faden mit niedriger
 * Priorität (siehe [PreviewWorkers]). Der Upload reiht nur ein, er wartet nicht. Schreibzugriffe auf den Index laufen unter
 * [lock], dem Commit-Lock des Vault. Das Rendern selbst läuft ohne Lock, damit Uploads nicht warten.
 *
 * Jeder Schritt endet mit `READY` oder `FAILED`. Es gibt keinen Wiederholungsversuch in der Schleife. Ein Neustart reiht
 * offene Medien erneut ein ([reconcile]).
 */
internal class PreviewService(
    private val media: MediaDao,
    private val storage: SpaceStorage,
    private val limits: SpaceLimits,
    private val probe: StorageProbe,
    private val lock: Mutex,
    private val renderer: PreviewRenderer,
) {
    private val queue = Channel<String>(Channel.UNLIMITED)
    private var worker: Job? = null

    /** Reiht ein Medium ein. Blockiert nie. */
    fun enqueue(id: MediaId) {
        queue.trySend(id.value)
    }

    /** Startet den Worker auf [scope]. Genau einmal pro Vault. */
    fun start(scope: CoroutineScope) {
        check(worker == null) { "Der Worker der Vorschauen läuft schon." }
        worker =
            scope.launch {
                for (id in queue) {
                    try {
                        process(id)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        // Der Index war nicht erreichbar. Das Medium bleibt offen und kommt beim nächsten Start wieder.
                    }
                }
            }
    }

    /** Beendet den Worker. Angefangene Arbeit wird verworfen, der nächste Start reiht sie wieder ein. */
    fun stop() {
        worker?.cancel()
        worker = null
    }

    /** Video-Codec aus dem Kopf der Datei. Leer, wenn er nicht erkannt wird (ADR 0019, Codec). */
    suspend fun codecOf(file: Path): String =
        withContext(Dispatchers.IO) { renderer.videoCodec(file.toFile()) }.orEmpty()

    /** Sucht die Datei einer Vorschau für die Auslieferung. */
    suspend fun lookup(
        id: MediaId,
        view: Boolean,
    ): PreviewLookup {
        val row = media.findById(id.value)?.takeIf { it.state == STATE_READY } ?: return PreviewLookup.NotFound
        val state = if (view) row.viewState else row.previewState
        if (state != PreviewState.READY) return PreviewLookup.Unavailable
        val edge = if (view) PreviewRules.VIEW_LONG_EDGE else PreviewRules.PREVIEW_LONG_EDGE
        val path = storage.existingPreview(row.sha256, edge) ?: return PreviewLookup.Unavailable
        return PreviewLookup.Ready(path)
    }

    /**
     * Erzeugt, was einem Medium fehlt: Video-Codec, Vorschau und bei HEIC/HEIF die Ansicht. Ein Fehler wird zu `failed`.
     * Der Aufruf hält den Lock nur für die Index-Zugriffe.
     */
    suspend fun process(id: String) {
        try {
            val row = lock.withLock { media.findById(id) }?.takeIf { it.state == STATE_READY } ?: return
            val source = storage.contentPath(row.sha256)
            if (PreviewRules.isVideo(row.mime) && row.videoCodec == null) {
                val codec = codecOf(source)
                lock.withLock { media.setVideoCodec(id, codec) }
            }
            if (needsPreview(row.previewState)) {
                render(id, row.sha256, row.mime, view = false)
            }
            if (PreviewRules.hasHeicView(row.mime) && needsPreview(row.viewState)) {
                render(id, row.sha256, row.mime, view = true)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            lock.withLock { markOpenAsFailed(id) }
        }
    }

    /**
     * Beim Start (ADR 0019, `recover`). Nur unter dem Commit-Lock des Vault aufrufen. Medien ohne Zustand bekommen
     * `PENDING` und werden eingereiht. `READY` ohne Datei wird `PENDING`. Vorschauen ohne Medium gehen. Die Namen der
     * Dateien, die zu bereiten Vorschauen gehören, bleiben stehen.
     */
    suspend fun reconcile(ready: List<MediaEntity>) {
        val keep = mutableSetOf<String>()
        ready.forEach { row ->
            val previewAfter =
                reconcileState(row.previewState, storage.existingPreview(row.sha256, PreviewRules.PREVIEW_LONG_EDGE)) {
                    media.setPreviewState(row.id, PreviewState.PENDING, 0L)
                }
            if (previewAfter ==
                PreviewState.READY
            ) {
                keep += SpaceStorage.previewFileName(row.sha256, PreviewRules.PREVIEW_LONG_EDGE)
            }
            val heic = PreviewRules.hasHeicView(row.mime)
            var viewAfter: String? = null
            if (heic) {
                viewAfter =
                    reconcileState(row.viewState, storage.existingPreview(row.sha256, PreviewRules.VIEW_LONG_EDGE)) {
                        media.setViewState(row.id, PreviewState.PENDING, 0L)
                    }
                if (viewAfter ==
                    PreviewState.READY
                ) {
                    keep += SpaceStorage.previewFileName(row.sha256, PreviewRules.VIEW_LONG_EDGE)
                }
            }
            val previewOpen = previewAfter == null || previewAfter == PreviewState.PENDING
            val viewOpen = heic && (viewAfter == null || viewAfter == PreviewState.PENDING)
            val codecOpen = PreviewRules.isVideo(row.mime) && row.videoCodec == null
            if (previewOpen || viewOpen || codecOpen) enqueue(MediaId(row.id))
        }
        storage.deletePreviewsExcept(keep)
    }

    /** Eine Vorschau erzeugen und das Ergebnis schreiben. Das Rendern läuft ohne Lock. */
    private suspend fun render(
        id: String,
        sha256: String,
        mime: String,
        view: Boolean,
    ) {
        val edge = if (view) PreviewRules.VIEW_LONG_EDGE else PreviewRules.PREVIEW_LONG_EDGE
        lock.withLock { write(id, view, PreviewState.PENDING, 0L, null) }
        val temp = storage.newTempPath()
        val source = storage.contentPath(sha256).toFile()
        val result: RenderResult =
            withTimeoutOrNull(PreviewRules.RENDER_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    runInterruptible {
                        if (PreviewRules.isVideo(mime)) {
                            renderer.renderVideoFrame(source, edge, temp.toFile())
                        } else {
                            renderer.renderImage(source, mime, edge, temp.toFile())
                        }
                    }
                }
            } ?: RenderResult.Failed(REASON_TIMEOUT)
        lock.withLock { commit(id, sha256, view, edge, temp, result) }
    }

    /** Übernimmt das Ergebnis. Fehlt das Medium inzwischen, wird die Teildatei verworfen. */
    private suspend fun commit(
        id: String,
        sha256: String,
        view: Boolean,
        edge: Int,
        temp: Path,
        result: RenderResult,
    ) {
        val row = media.findById(id)?.takeIf { it.state == STATE_READY }
        if (row == null) {
            withContext(Dispatchers.IO) { Files.deleteIfExists(temp) }
            return
        }
        if (result is RenderResult.Failed) {
            withContext(Dispatchers.IO) { Files.deleteIfExists(temp) }
            write(id, view, PreviewState.FAILED, 0L, result.reason)
            return
        }
        val size = withContext(Dispatchers.IO) { Files.size(temp) }
        if (!fitsBudget(size)) {
            withContext(Dispatchers.IO) { Files.deleteIfExists(temp) }
            write(id, view, PreviewState.FAILED, 0L, REASON_BUDGET)
            return
        }
        withContext(Dispatchers.IO) { storage.moveAtomically(temp, storage.previewPath(sha256, edge)) }
        write(id, view, PreviewState.READY, size, null)
    }

    /**
     * Die Vorschau zählt gegen das Speicherlimit und den Mindest-Restspeicher des Geräts (ADR 0019). Passt sie nicht, bleibt
     * sie `failed`. Der Index wird nicht überschritten.
     */
    private suspend fun fitsBudget(size: Long): Boolean {
        val used = media.usedBytes(storage.spaceId.value)
        val spaceLeft = limits.storageLimitBytes - used
        val deviceLeft = probe.usableBytes() - limits.minDeviceReserveBytes
        return size <= spaceLeft && size <= deviceLeft
    }

    /**
     * Schreibt Zustand, Größe und den Fehlergrund (hier als Code, Hotfix 2). Ein Grund ist nur bei `FAILED` gesetzt; bei
     * `READY` oder `PENDING` wird er gelöscht.
     */
    private suspend fun write(
        id: String,
        view: Boolean,
        state: String,
        bytes: Long,
        reason: String?,
    ) {
        if (view) {
            media.setViewState(id, state, bytes)
            media.setViewReason(id, reason)
        } else {
            media.setPreviewState(id, state, bytes)
            media.setPreviewReason(id, reason)
        }
    }

    /** Nach einem Fehler: offene Zustände werden `failed`. Bereite und schon fehlgeschlagene bleiben. */
    private suspend fun markOpenAsFailed(id: String) {
        val row = media.findById(id)?.takeIf { it.state == STATE_READY } ?: return
        if (needsPreview(row.previewState)) write(id, view = false, PreviewState.FAILED, 0L, REASON_SERVICE)
        if (PreviewRules.hasHeicView(row.mime) && needsPreview(row.viewState)) {
            write(id, view = true, PreviewState.FAILED, 0L, REASON_SERVICE)
        }
    }

    /** Ein Zustand braucht Arbeit, wenn er weder bereit noch endgültig fehlgeschlagen ist. */
    private fun needsPreview(state: String?): Boolean = state != PreviewState.READY && state != PreviewState.FAILED

    /**
     * Zustand nach dem Start. Bereit mit Datei bleibt bereit. Sonst ist der Zustand offen (`PENDING`), außer er ist schon
     * `FAILED`. [markPending] schreibt den offenen Zustand in den Index.
     */
    private suspend fun reconcileState(
        state: String?,
        file: Path?,
        markPending: suspend () -> Unit,
    ): String? =
        when {
            state == PreviewState.READY && file != null -> {
                PreviewState.READY
            }

            state == PreviewState.FAILED -> {
                PreviewState.FAILED
            }

            else -> {
                markPending()
                PreviewState.PENDING
            }
        }

    private companion object {
        const val STATE_READY = "READY"
        const val REASON_TIMEOUT = "timeout"
        const val REASON_BUDGET = "budget"
        const val REASON_SERVICE = "service"
    }
}
