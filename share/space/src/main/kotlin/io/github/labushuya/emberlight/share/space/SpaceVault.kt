package io.github.labushuya.emberlight.share.space

import android.content.Context
import io.github.labushuya.emberlight.share.space.location.LocationScrubResult
import io.github.labushuya.emberlight.share.space.location.LocationScrubber
import io.github.labushuya.emberlight.share.space.location.NotRemovableReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.min

/** Medium mit allen Angaben, die für Download und Löschen nötig sind. */
data class MediaRecord(
    val id: MediaId,
    val mime: String,
    val sizeBytes: Long,
    val sha256: String,
    val originalName: String,
    val uploaderId: ParticipantId,
    val createdAt: Long,
    val locationKept: Boolean = false,
)

/** Eintrag der Medienliste. Ohne Pfad, ohne Hash. [locationKept] markiert „Standort enthalten“ (ADR 0015). */
data class MediaListEntry(
    val id: MediaId,
    val mime: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val uploaderName: String?,
    val locationKept: Boolean = false,
)

data class MediaPage(
    val items: List<MediaListEntry>,
    val nextCursor: MediaCursor?,
)

/** Undurchsichtiger Cursor: Sortierschlüssel (Zeit, ID). Absteigende Reihenfolge (ADR 0014, Liste). */
data class MediaCursor(
    val createdAt: Long,
    val id: MediaId,
) {
    fun encode(): String = ENCODER.encodeToString("$createdAt:${id.value}".toByteArray(Charsets.UTF_8))

    companion object {
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()

        /** Liefert `null` für jeden Cursor, der nicht aus [encode] stammt. */
        fun decode(value: String): MediaCursor? {
            val text =
                try {
                    String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
                } catch (invalid: IllegalArgumentException) {
                    return null
                }
            val separator = text.indexOf(':')
            if (separator <= 0) return null
            val createdAt = text.substring(0, separator).toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val id = MediaId.parse(text.substring(separator + 1)) ?: return null
            return MediaCursor(createdAt, id)
        }
    }
}

/** Speicherstand des Space. [warning] ist gesetzt ab der Warnschwelle. */
data class StorageUsage(
    val usedBytes: Long,
    val limitBytes: Long,
    val warning: Boolean,
)

enum class StorageWarning { STORAGE_80 }

/** Ablehnungsgründe eines Uploads. Die Ktor-Schicht bildet sie auf Statuscodes ab. */
sealed interface RejectReason {
    /** Inhalt ist kein erlaubtes Format oder die Struktur ist ungültig (415). */
    data object UnsupportedType : RejectReason

    /**
     * Standort nicht sicher entfernbar und ohne Einwilligung (422, `location_not_removable`). Die Datei wird nicht
     * gespeichert. [reason] nennt den Grund (ADR 0015, Reason-Codes).
     */
    data class LocationNotRemovable(
        val reason: NotRemovableReason,
    ) : RejectReason

    /** Größe über dem Limit der Medienart (413). */
    data class TooLarge(
        val limitBytes: Long,
    ) : RejectReason

    /** Speicherlimit des Space erreicht (507, `storage_full`). */
    data object StorageFull : RejectReason

    /** Mindest-Restspeicher des Geräts würde unterschritten (507, `device_storage_low`). */
    data object DeviceStorageLow : RejectReason
}

sealed interface IngestResult {
    data class Created(
        val record: MediaRecord,
        val warning: StorageWarning?,
    ) : IngestResult

    /** Gleicher Inhalt liegt schon im Space. Der Uploader ist der erste (ADR 0014, Dedupe). */
    data class Duplicate(
        val record: MediaRecord,
        val warning: StorageWarning?,
    ) : IngestResult

    data class Rejected(
        val reason: RejectReason,
    ) : IngestResult
}

sealed interface DeleteOutcome {
    data object Deleted : DeleteOutcome

    data object NotFound : DeleteOutcome

    /** Medium existiert, aber der Teilnehmer darf es nicht löschen. */
    data object Forbidden : DeleteOutcome
}

/** Datei eines Medium für die Auslieferung. Der Pfad bleibt im Speicher des Space. */
class ContentFile(
    val record: MediaRecord,
    val path: Path,
)

/**
 * Zentrale Anwendungslogik des Space: Upload mit Grenzen und Inhaltsprüfung, Dedupe, Liste, Download, Löschen und
 * Bereinigung (ADR 0013, ADR 0014). Ohne HTTP. Die Ktor-Schicht übersetzt Ergebnisse und Ausnahmen.
 *
 * Schreibvorgänge und der Dedupe-Vergleich laufen unter [commitLock], damit zwei gleiche Uploads nicht beide neu
 * gespeichert werden.
 */
class SpaceVault internal constructor(
    private val database: SpacesDatabase,
    private val storage: SpaceStorage,
    private val limits: SpaceLimits,
    private val probe: StorageProbe,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val media = database.media()
    private val participants = database.participants()
    private val commitLock = Mutex()

    /**
     * Nimmt einen Upload entgegen. Die Reihenfolge entspricht ADR 0013 und ADR 0015: Kopf lesen und erkennen, Größe und
     * Budget prüfen, streamen und zählen (dabei entsteht der SHA-256 der Bytes), Struktur prüfen, Standort entfernen (oder mit Einwilligung unverändert
     * behalten; Plan/Apply, ohne Sicherungskopie, ADR 0015), Dedupe und atomar übernehmen. Bei jeder Ablehnung und jedem
     * Abbruch bleibt keine Teildatei zurück. Ein Abbruch durch [ChunkSource] wird weitergereicht.
     *
     * [keepLocation] ist die Einwilligung. Sie wirkt nur, wenn der Standort nicht sicher entfernt werden kann.
     */
    suspend fun ingest(
        uploader: Principal,
        rawFileName: String,
        declaredLength: Long?,
        source: ChunkSource,
        keepLocation: Boolean = false,
    ): IngestResult {
        val buffer = ByteArray(BUFFER_BYTES)
        val head = fillHead(source, buffer)
        val format =
            ContentInspector.identify(buffer.copyOf(head))
                ?: return IngestResult.Rejected(RejectReason.UnsupportedType)
        val maxBytes = limits.maxBytesFor(format.kind)
        if (declaredLength != null && declaredLength > maxBytes) {
            return IngestResult.Rejected(RejectReason.TooLarge(maxBytes))
        }
        val budget = currentBudget()
        if (budget.bytes <= 0) return IngestResult.Rejected(budget.reason)

        val temp = storage.newTempPath()
        try {
            // Der Hash des Stroms entsteht beim Schreiben. Bleibt die Datei unverändert, gilt er weiter (ADR 0015).
            val (size, streamedSha256) = writeBody(temp, source, buffer, head, Limit(maxBytes, budget))
            if (!structureIsValid(format, temp, size)) throw Rejection(RejectReason.UnsupportedType)
            val outcome = removeLocationOrKeep(temp, format, keepLocation, streamedSha256)
            // Nach dem Abschneiden von Anhängen zählt die Länge auf dem Speicher, nicht die des Stroms (ADR 0015).
            val storedSize = withContext(Dispatchers.IO) { Files.size(temp) }
            return commit(uploader, rawFileName, format, outcome.sha256, storedSize, temp, outcome.locationKept)
        } catch (rejection: Rejection) {
            Files.deleteIfExists(temp)
            return IngestResult.Rejected(rejection.reason)
        } catch (failure: Throwable) {
            // Abbruch durch Timeout, Verbindungsende oder Ausnahme: die Teildatei verschwindet in jedem Fall.
            Files.deleteIfExists(temp)
            throw failure
        }
    }

    /** Ergebnis der Standortprüfung: Hash der verarbeiteten Bytes und ob der Standort mit Einwilligung blieb. */
    private class Scrubbed(
        val sha256: String,
        val locationKept: Boolean,
    )

    /**
     * Bereinigt den Standort in [temp] nach Plan/Apply (ADR 0015): Die Datei wird nur geschrieben, wenn alles
     * bereinigbar ist. Ohne Einwilligung lehnt es bei nicht bereinigbarer Datei mit [Rejection] ab. Mit Einwilligung
     * bleibt die Datei unverändert und ungeschrieben, der Standort gilt als behalten.
     */
    private suspend fun removeLocationOrKeep(
        temp: Path,
        format: DetectedFormat,
        keepLocation: Boolean,
        streamedSha256: String,
    ): Scrubbed {
        val outcome = withContext(Dispatchers.IO) { LocationScrubber.scrub(temp.toFile(), format, streamedSha256) }
        return when (outcome) {
            is LocationScrubResult.Clean -> {
                Scrubbed(outcome.sha256, locationKept = false)
            }

            is LocationScrubResult.NotRemovable -> {
                if (!keepLocation) throw Rejection(RejectReason.LocationNotRemovable(outcome.reason))
                Scrubbed(streamedSha256, locationKept = true)
            }
        }
    }

    /** Seite der Medienliste, neueste zuerst. [limit] liegt zwischen 1 und [MAX_PAGE] (ADR 0014). */
    suspend fun list(
        cursor: MediaCursor?,
        limit: Int,
    ): MediaPage {
        require(limit in 1..MAX_PAGE) { "Seitengröße außerhalb 1 bis $MAX_PAGE." }
        val spaceId = storage.spaceId.value
        val rows =
            if (cursor == null) {
                media.firstPage(spaceId, limit + 1)
            } else {
                media.pageAfter(spaceId, cursor.createdAt, cursor.id.value, limit + 1)
            }
        val page =
            rows.take(limit).map {
                MediaListEntry(MediaId(it.id), it.mime, it.sizeBytes, it.createdAt, it.uploaderName, it.locationKept)
            }
        val next =
            if (rows.size > limit && page.isNotEmpty()) {
                page.last().let { MediaCursor(it.createdAt, it.id) }
            } else {
                null
            }
        return MediaPage(page, next)
    }

    /** Medium mit dem Datensatz, sofern es bereit ist. */
    suspend fun find(id: MediaId): MediaRecord? = readyRecord(id)

    /** Datei für die Auslieferung. `null`, wenn das Medium fehlt oder die Datei nicht mehr da ist. */
    suspend fun contentOf(id: MediaId): ContentFile? {
        val record = readyRecord(id) ?: return null
        val path = storage.contentPath(record.sha256)
        return if (Files.isRegularFile(path)) ContentFile(record, path) else null
    }

    /**
     * Löscht ein Medium. Der Gastgeber darf alles, andere Teilnehmer nur eigene Medien (ADR 0014, Löschen). Die
     * Reihenfolge ist: markieren, Datei löschen, Eintrag entfernen. Ein Absturz dazwischen räumt [recover] auf.
     */
    suspend fun delete(
        principal: Principal,
        id: MediaId,
    ): DeleteOutcome =
        commitLock.withLock {
            val row = readyRow(id)
            when {
                row == null -> {
                    DeleteOutcome.NotFound
                }

                !mayDelete(principal, row) -> {
                    DeleteOutcome.Forbidden
                }

                else -> {
                    media.markDeleting(id.value)
                    withContext(Dispatchers.IO) { Files.deleteIfExists(storage.contentPath(row.sha256)) }
                    media.deleteById(id.value)
                    DeleteOutcome.Deleted
                }
            }
        }

    /** Speicherstand des Space für die Anzeige und für die Warnung. */
    suspend fun usage(): StorageUsage {
        val used = media.usedBytes(storage.spaceId.value)
        return StorageUsage(used, limits.storageLimitBytes, limits.isWarning(used))
    }

    /**
     * Bereinigung beim Start, bevor der Server Anfragen annimmt (ADR 0013, Löschen). Entfernt Medien im Zustand
     * `DELETING` samt Datei und alle Dateien ohne Index-Eintrag, etwa Temp-Reste.
     */
    suspend fun recover() {
        commitLock.withLock {
            val spaceId = storage.spaceId.value
            val rows = media.allOf(spaceId)
            rows.filter { it.state == STATE_DELETING }.forEach { row ->
                Files.deleteIfExists(storage.contentPath(row.sha256))
                media.deleteById(row.id)
            }
            val keep = rows.filter { it.state == STATE_READY }.map { it.sha256 }.toSet()
            storage.deleteUnlisted(keep)
        }
    }

    /** Registriert einen Teilnehmer im Index, zum Beispiel beim Einrichten des Space. Pro ID ein Datensatz. */
    suspend fun registerParticipant(
        id: ParticipantId,
        displayName: String,
        role: Role,
        tokenSha256Hex: String,
    ) {
        participants.upsert(ParticipantEntity(id.value, displayName, role.name, tokenSha256Hex))
    }

    private suspend fun writeBody(
        target: Path,
        source: ChunkSource,
        buffer: ByteArray,
        head: Int,
        limit: Limit,
    ): Pair<Long, String> =
        withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance(SHA_256)
            Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
                var written = 0L
                var length = head
                while (true) {
                    if (length > 0) {
                        written += length
                        limit.check(written)
                        digest.update(buffer, 0, length)
                        out.write(buffer, 0, length)
                    }
                    length = source.read(buffer, 0, buffer.size)
                    if (length < 0) break
                }
                written to digest.digest().toHex()
            }
        }

    private suspend fun fillHead(
        source: ChunkSource,
        buffer: ByteArray,
    ): Int {
        var filled = 0
        while (filled < ContentInspector.HEAD_BYTES) {
            // Genau bis zur Kopflänge lesen: Ein unbekanntes Format wird nach diesen Bytes abgelehnt, ohne mehr zu lesen.
            val read = source.read(buffer, filled, ContentInspector.HEAD_BYTES - filled)
            if (read < 0) break
            filled += read
        }
        return filled
    }

    private suspend fun structureIsValid(
        format: DetectedFormat,
        path: Path,
        size: Long,
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (!format.boxed) {
                true
            } else {
                RandomAccessFile(path.toFile(), "r").use { file ->
                    ContentInspector.hasValidStructure(format, size) { offset, length ->
                        val count = min(length.toLong(), size - offset).toInt()
                        ByteArray(count).also {
                            file.seek(offset)
                            file.readFully(it)
                        }
                    }
                }
            }
        }

    private suspend fun commit(
        uploader: Principal,
        rawFileName: String,
        format: DetectedFormat,
        sha256: String,
        size: Long,
        temp: Path,
        locationKept: Boolean,
    ): IngestResult =
        commitLock.withLock {
            val spaceId = storage.spaceId.value
            val existing = media.findReadyBySha256(spaceId, sha256)
            if (existing != null) {
                Files.deleteIfExists(temp)
                IngestResult.Duplicate(existing.toRecord(), warningAfterUpload())
            } else {
                val id = freshMediaId()
                val target = storage.contentPath(sha256)
                withContext(Dispatchers.IO) { storage.moveAtomically(temp, target) }
                val entity =
                    MediaEntity(
                        id = id.value,
                        spaceId = spaceId,
                        sha256 = sha256,
                        mime = format.mimeType,
                        sizeBytes = size,
                        originalName = UploadFileName.sanitize(rawFileName),
                        uploaderId = uploader.participantId.value,
                        createdAt = nowMillis(),
                        state = STATE_READY,
                        locationKept = locationKept,
                        processingVersion = LocationScrubber.PROCESSING_VERSION,
                    )
                try {
                    media.insert(entity)
                } catch (failure: Throwable) {
                    Files.deleteIfExists(target)
                    throw failure
                }
                IngestResult.Created(entity.toRecord(), warningAfterUpload())
            }
        }

    /** Zufällige Medien-ID ohne Kollision. Eine Kollision wird verworfen und neu gewürfelt (ADR 0013, IDs). */
    private suspend fun freshMediaId(): MediaId {
        repeat(ID_ATTEMPTS) {
            val candidate = MediaId.generate()
            if (media.findById(candidate.value) == null) return candidate
        }
        error("Keine freie Medien-ID gefunden.")
    }

    private suspend fun currentBudget(): Budget {
        val used = media.usedBytes(storage.spaceId.value)
        val spaceLeft = limits.storageLimitBytes - used
        val deviceLeft = probe.usableBytes() - limits.minDeviceReserveBytes
        return if (spaceLeft <= deviceLeft) {
            Budget(spaceLeft, RejectReason.StorageFull)
        } else {
            Budget(deviceLeft, RejectReason.DeviceStorageLow)
        }
    }

    private suspend fun warningAfterUpload(): StorageWarning? =
        if (limits.isWarning(media.usedBytes(storage.spaceId.value))) StorageWarning.STORAGE_80 else null

    private suspend fun readyRow(id: MediaId): MediaEntity? =
        media.findById(id.value)?.takeIf { it.state == STATE_READY }

    private suspend fun readyRecord(id: MediaId): MediaRecord? = readyRow(id)?.toRecord()

    private fun mayDelete(
        principal: Principal,
        row: MediaEntity,
    ): Boolean = principal.role == Role.HOST || row.uploaderId == principal.participantId.value

    private fun MediaEntity.toRecord(): MediaRecord =
        MediaRecord(
            id = MediaId(id),
            mime = mime,
            sizeBytes = sizeBytes,
            sha256 = sha256,
            originalName = originalName,
            uploaderId = ParticipantId(uploaderId),
            createdAt = createdAt,
            locationKept = locationKept,
        )

    /** Obergrenze für Bytes in dieser Anfrage: die Medienart-Grenze und das Budget aus Speicher und Gerät. */
    private inner class Limit(
        private val maxBytes: Long,
        private val budget: Budget,
    ) {
        fun check(written: Long) {
            if (written > maxBytes) throw Rejection(RejectReason.TooLarge(maxBytes))
            if (written > budget.bytes) throw Rejection(budget.reason)
        }
    }

    private class Budget(
        val bytes: Long,
        val reason: RejectReason,
    )

    /** Interne Ablehnung während des Schreibens. Die Anfrage bekommt den Grund zurück, kein Fehler nach außen. */
    private class Rejection(
        val reason: RejectReason,
    ) : RuntimeException(null, null, false, false)

    companion object {
        /**
         * Öffnet den Speicher des Space unter [spacesDir]: `<spacesDir>/<spaceId>/`. Den Index [database] gibt der Aufrufer
         * vor. Er ist prozessweit geteilt (siehe [SpacesDatabase.shared]) und gehört dem Aufrufer, die Vault schließt
         * ihn nicht (Paket 3.3).
         */
        fun open(
            database: SpacesDatabase,
            spacesDir: File,
            spaceId: SpaceId,
            limits: SpaceLimits,
            probe: StorageProbe,
        ): SpaceVault = SpaceVault(database, SpaceStorage(spacesDir, spaceId), limits, probe)

        const val MAX_PAGE = 100
        const val DEFAULT_PAGE = 50
        const val STATE_READY = "READY"
        const val STATE_DELETING = "DELETING"
        private const val BUFFER_BYTES = 64 * 1024
        private const val ID_ATTEMPTS = 3
        private const val SHA_256 = "SHA-256"
    }
}

private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
