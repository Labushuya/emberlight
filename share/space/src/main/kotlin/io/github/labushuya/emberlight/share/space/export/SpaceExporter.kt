package io.github.labushuya.emberlight.share.space.export

import io.github.labushuya.emberlight.share.space.MediaEntity
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpaceLimits
import io.github.labushuya.emberlight.share.space.SpaceStorage
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.management.SpaceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest

/** Ziel einer Kopie, wie der Sink sie anlegt. Keine Android-Typen in der Domäne. */
data class ExportFileMeta(
    /** Ordnername unter `Pictures/Emberlight/`. */
    val folderName: String,
    /** Dateiname, schon eindeutig im Ordner. */
    val fileName: String,
    val mime: String,
    /** Aufnahmezeit in Millisekunden, falls bekannt. Sonst `null`, dann setzt das System das Datum des Hinzufügens. */
    val captureTimeMillis: Long?,
    val sizeBytes: Long,
)

/** Kennung einer angelegten, noch nicht sichtbaren Kopie. [uri] ist die Adresse, die der Sink vergeben hat. */
data class ExportHandle(
    val uri: String,
    val fileName: String,
)

/**
 * Schreibziel der Übernahme (Paket 3.7, ADR 0022). Die Umsetzung für den Medienspeicher der Galerie liegt im Modul `share:export`. Die
 * Domäne kennt nur diese Schnittstelle. So bleibt `share:space` frei von Galerie-Zugriffen (`checkSpaceSources`).
 *
 * Ablauf je Datei: [create] legt eine ausstehende Kopie an, [openOutput] schreibt, [openInput] liest sie zurück zur
 * Prüfung, [publish] macht sie sichtbar. Bei einem Fehler oder einer Abweichung verwirft [discard] die Kopie
 * vollständig (bei Android: `IS_PENDING` bleibt gesetzt, der Eintrag wird gelöscht).
 */
interface ExportSink {
    /** Freier Speicher auf dem Ziel in Bytes. */
    fun availableBytes(): Long

    /** Gibt es den Ordner unter `Pictures/Emberlight/` schon, auch von einem gelöschten Space? */
    suspend fun folderExists(folderName: String): Boolean

    /** Gibt es die Datei im Ordner schon? Der Vergleich ist ohne Groß- und Kleinschreibung. */
    suspend fun fileExists(
        folderName: String,
        fileName: String,
    ): Boolean

    suspend fun create(meta: ExportFileMeta): ExportHandle

    suspend fun openOutput(handle: ExportHandle): OutputStream

    suspend fun openInput(handle: ExportHandle): InputStream

    suspend fun publish(handle: ExportHandle)

    /** Verwirft die Kopie. Mehrfacher Aufruf ist erlaubt, eine fehlende Kopie ist kein Fehler. */
    suspend fun discard(handle: ExportHandle)
}

/** Gründe, warum eine einzelne Datei nicht übernommen wurde. Die Oberfläche übersetzt sie. */
enum class ExportFailureReason {
    /** Die gespeicherte Space-Datei fehlt. */
    MISSING_SOURCE,

    /** Das Schreiben oder Lesen der Kopie ist fehlgeschlagen. */
    WRITE_FAILED,

    /** Die Kopie hat nach dem Zurücklesen nicht den Hash der Space-Datei. Sie wurde verworfen. */
    HASH_MISMATCH,

    /** Anderer Fehler, z. B. im Vermerk. Die Kopie wurde verworfen. */
    UNKNOWN,
}

data class ExportFailure(
    val mediaId: String,
    val reason: ExportFailureReason,
)

data class ExportProgress(
    val doneFiles: Int,
    val totalFiles: Int,
    val doneBytes: Long,
    val totalBytes: Long,
)

data class ExportSummary(
    /** Übernommen und geprüft. */
    val exported: Int,
    /** Übersprungen, weil schon übernommen. Keine Kopie entsteht. */
    val skippedAlreadyExported: Int,
    val failed: List<ExportFailure>,
    val bytesCopied: Long,
    /** Der Space wird gelöscht. Die Übernahme hat nach der laufenden Datei aufgehört. */
    val stoppedByPurge: Boolean,
)

sealed interface ExportOutcome {
    data class Finished(
        val summary: ExportSummary,
    ) : ExportOutcome

    /** Der Platz reicht nicht: Summe der Dateien plus 2 GiB Reserve. Es wurde nichts geschrieben. */
    data class NotEnoughSpace(
        val requiredBytes: Long,
        val availableBytes: Long,
    ) : ExportOutcome

    /** Der Space ist nicht übernehmbar: fehlt, wird gelöscht oder ist gelöscht. */
    data object NotExportable : ExportOutcome
}

/**
 * Übernahme ausgewählter Medien in die Galerie (Paket 3.7, ADR 0022). Kopiert die gespeicherte Space-Datei, also die
 * bereinigte Fassung, und prüft jede Kopie per SHA-256 gegen den Hash der Space-Datei.
 *
 * Ein Lauf hält die Sperre [gate] für den Space. Die Löschung wartet darauf und setzt vorher `purging`. Dieser Lauf prüft
 * `purging` vor jeder Datei und bricht dann sauber ab. Abbruch der Coroutine: Die gerade angelegte Kopie wird verworfen,
 * bereits fertige bleiben (mit Vermerk).
 */
class SpaceExporter(
    private val database: SpacesDatabase,
    private val spacesDir: File,
    private val sink: ExportSink,
    private val gate: SpaceExportGate,
    private val reserveBytes: Long = SpaceLimits().minDeviceReserveBytes,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val captureTimeOf: (File, String) -> Long? = CaptureTime::ofFile,
) {
    private val spaces = database.spaces()
    private val media = database.media()
    private val exports = database.exports()

    suspend fun export(
        id: SpaceId,
        mediaIds: Set<String>,
        onProgress: (ExportProgress) -> Unit = {},
    ): ExportOutcome = gate.exclusive(id) { run(id, mediaIds, onProgress) }

    private suspend fun run(
        id: SpaceId,
        mediaIds: Set<String>,
        onProgress: (ExportProgress) -> Unit,
    ): ExportOutcome {
        val row = spaces.find(id.value) ?: return ExportOutcome.NotExportable
        if (row.purging || row.status == SpaceStatus.DELETED.name || row.status == SpaceStatus.DRAFT.name) {
            return ExportOutcome.NotExportable
        }
        val exported = exports.exportedIds(id.value).toSet()
        val rows = media.allOf(id.value).filter { it.state == READY && it.id in mediaIds }
        val fresh = rows.filter { it.id !in exported }
        val skipped = rows.size - fresh.size
        if (fresh.isEmpty()) {
            return ExportOutcome.Finished(ExportSummary(0, skipped, emptyList(), 0L, stoppedByPurge = false))
        }

        val totalBytes = fresh.sumOf { it.sizeBytes }
        val required = ExportSpacePolicy.requiredBytes(totalBytes, reserveBytes)
        val available = sink.availableBytes()
        if (available < required) return ExportOutcome.NotEnoughSpace(required, available)

        val folder = folderFor(id, row.name)
        val storage = SpaceStorage(spacesDir, id)
        val failed = mutableListOf<ExportFailure>()
        var doneFiles = 0
        var doneBytes = 0L
        var exportedCount = 0
        var stoppedByPurge = false
        for (medium in fresh) {
            if (spaces.find(id.value)?.purging != false) {
                stoppedByPurge = true
                break
            }
            val outcome =
                copyOne(id, storage, folder, medium) { bytes ->
                    onProgress(ExportProgress(doneFiles, fresh.size, doneBytes + bytes, totalBytes))
                }
            doneFiles++
            when (outcome) {
                is CopyOutcome.Copied -> {
                    exportedCount++
                    doneBytes += medium.sizeBytes
                }

                is CopyOutcome.Failed -> {
                    failed += ExportFailure(medium.id, outcome.reason)
                }
            }
            onProgress(ExportProgress(doneFiles, fresh.size, doneBytes, totalBytes))
        }
        return ExportOutcome.Finished(
            ExportSummary(
                exported = exportedCount,
                skippedAlreadyExported = skipped,
                failed = failed,
                bytesCopied = doneBytes,
                stoppedByPurge = stoppedByPurge,
            ),
        )
    }

    private sealed interface CopyOutcome {
        data object Copied : CopyOutcome

        data class Failed(
            val reason: ExportFailureReason,
        ) : CopyOutcome
    }

    private suspend fun copyOne(
        id: SpaceId,
        storage: SpaceStorage,
        folder: String,
        medium: MediaEntity,
        onBytes: (Long) -> Unit,
    ): CopyOutcome {
        val source: File =
            try {
                storage.contentPath(medium.sha256).toFile()
            } catch (invalid: IllegalArgumentException) {
                return CopyOutcome.Failed(ExportFailureReason.MISSING_SOURCE)
            }
        if (!source.isFile) return CopyOutcome.Failed(ExportFailureReason.MISSING_SOURCE)

        val fileName = freeFileName(folder, ExportNames.fileName(medium.originalName, extensionOf(medium.mime)))
        val meta =
            ExportFileMeta(
                folderName = folder,
                fileName = fileName,
                mime = medium.mime,
                captureTimeMillis = captureTimeOf(source, medium.mime),
                sizeBytes = medium.sizeBytes,
            )
        var handle: ExportHandle? = null
        return try {
            val created = sink.create(meta)
            handle = created
            sink.openOutput(created).use { out -> copyCounting(source, out, onBytes) }
            val actual = sha256Hex(sink.openInput(created))
            if (actual != medium.sha256) {
                sink.discard(created)
                return CopyOutcome.Failed(ExportFailureReason.HASH_MISMATCH)
            }
            sink.publish(created)
            exports.insertExported(
                ExportedMediaEntity(
                    mediaId = medium.id,
                    spaceId = id.value,
                    targetName = created.fileName,
                    targetUri = created.uri,
                    exportedAt = nowMillis(),
                ),
            )
            CopyOutcome.Copied
        } catch (cancelled: CancellationException) {
            handle?.let { withContext(NonCancellable) { sink.discard(it) } }
            throw cancelled
        } catch (io: IOException) {
            handle?.let { discardQuietly(it) }
            CopyOutcome.Failed(ExportFailureReason.WRITE_FAILED)
        } catch (failed: Exception) {
            handle?.let { discardQuietly(it) }
            CopyOutcome.Failed(ExportFailureReason.UNKNOWN)
        }
    }

    private suspend fun discardQuietly(handle: ExportHandle) {
        try {
            withContext(NonCancellable) { sink.discard(handle) }
        } catch (ignored: Exception) {
            // Die Kopie bleibt ausstehend. Sie ist nicht sichtbar (IS_PENDING), und der Fehler ist schon gemeldet.
        }
    }

    /** Ordner des Space. Beim ersten Übernehmen wird ein eindeutiger Name gewählt und gespeichert. */
    private suspend fun folderFor(
        id: SpaceId,
        spaceName: String,
    ): String {
        exports.folderOf(id.value)?.let { return it.folderName }
        val base = ExportNames.folderName(spaceName)
        var taken = exports.allFolderNames().toSet()
        var candidate = ExportNames.unique(base, taken)
        while (sink.folderExists(candidate)) {
            taken = taken + candidate
            candidate = ExportNames.unique(base, taken)
        }
        exports.insertFolder(
            SpaceExportEntity(
                spaceId = id.value,
                folderName = candidate,
                createdAt = nowMillis(),
            ),
        )
        return candidate
    }

    private suspend fun freeFileName(
        folder: String,
        base: String,
    ): String {
        var taken = emptySet<String>()
        var candidate = base
        while (sink.fileExists(folder, candidate)) {
            taken = taken + candidate
            candidate = ExportNames.unique(base, taken)
        }
        return candidate
    }

    private companion object {
        const val READY = "READY"
        const val BUFFER_BYTES = 64 * 1024

        fun extensionOf(mime: String): String? =
            when (mime) {
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                "image/heic" -> "heic"
                "image/heif" -> "heif"
                "video/mp4" -> "mp4"
                "video/webm" -> "webm"
                "video/3gpp" -> "3gp"
                else -> null
            }

        fun copyCounting(
            source: File,
            out: OutputStream,
            onBytes: (Long) -> Unit,
        ) {
            Files.newInputStream(source.toPath()).use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    total += read
                    onBytes(total)
                }
            }
        }

        /** SHA-256 in Kleinbuchstaben, wie [MediaEntity.sha256] gespeichert. Der Strom wird geschlossen. */
        fun sha256Hex(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            input.use { stream ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
