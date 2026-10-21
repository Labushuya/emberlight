package io.github.labushuya.emberlight.share.space.management

import io.github.labushuya.emberlight.share.space.ContentInspector
import io.github.labushuya.emberlight.share.space.DetectedFormat
import io.github.labushuya.emberlight.share.space.MediaKind
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpaceLimits
import io.github.labushuya.emberlight.share.space.SpaceStorage
import io.github.labushuya.emberlight.share.space.location.LocationScrubResult
import io.github.labushuya.emberlight.share.space.location.LocationScrubber
import io.github.labushuya.emberlight.share.space.location.NotRemovableReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.math.min

/** Ergebnis der Übernahme eines Titelbilds in den Space-Speicher (Paket 3.2). */
sealed interface CoverResult {
    /** Gespeichert unter dem Hash der bereinigten Bytes. Der Verweis steht im Space. */
    data class Stored(
        val sha256: String,
    ) : CoverResult

    /** Kein Bild (Video, unbekanntes Format, beschädigte Struktur). */
    data object UnsupportedType : CoverResult

    /** Größer als das Limit für Bilder (50 MiB, wie beim Upload). */
    data class TooLarge(
        val limitBytes: Long,
    ) : CoverResult

    /**
     * Der Standort lässt sich nicht sicher entfernen. Für Titelbilder gibt es keine Einwilligung (Paket 3.2): Ein anderes
     * Bild muss gewählt werden.
     */
    data class LocationNotRemovable(
        val reason: NotRemovableReason,
    ) : CoverResult
}

/**
 * Titelbild-Pipeline des Space (Paket 3.2). Das Bild kommt als Stream, nie als URI: `share:space` kennt keine
 * Medienquelle. Ablauf: Kopf prüfen (nur Bild), begrenzt kopieren und dabei hashen, Struktur prüfen, Standort
 * entfernen (Plan/Apply, ADR 0015), dann unter `cover-<hash>` übernehmen. Eine Kopie des Originals bleibt nicht zurück.
 */
class SpaceCoverStore(
    private val spacesDir: File,
    private val maxBytes: Long = SpaceLimits().imageMaxBytes,
) {
    /** Übernimmt das Bild aus [input] in den Speicher von [spaceId]. Der Stream wird geschlossen. */
    suspend fun store(
        spaceId: SpaceId,
        input: InputStream,
    ): CoverResult =
        withContext(Dispatchers.IO) {
            input.use { stream -> storeBlocking(spaceId, stream) }
        }

    /** Titelbild ist im Speicher vorhanden. */
    fun exists(
        spaceId: SpaceId,
        sha256: String,
    ): Boolean = Files.isRegularFile(storage(spaceId).coverPath(sha256))

    /** Pfad des Titelbilds für die Anzeige in der App. Die Datei liegt im Space-Speicher, nie in der Galerie. */
    fun file(
        spaceId: SpaceId,
        sha256: String,
    ): File = storage(spaceId).coverPath(sha256).toFile()

    /** Entfernt alle Titelbilder von [spaceId] außer den in [keep] genannten. Medien bleiben unberührt. */
    suspend fun retainOnly(
        spaceId: SpaceId,
        keep: Set<String>,
    ): Int =
        withContext(Dispatchers.IO) {
            storage(spaceId).deleteCoversExcept(keep)
        }

    /**
     * Entfernt Medien und Vorschauen des Space, das Titelbild bleibt (Paket 3.6c, ADR 0021). Der Eintrag „Gelöscht“ zeigt es.
     */
    suspend fun deleteMediaOf(spaceId: SpaceId) {
        withContext(Dispatchers.IO) { storage(spaceId).deleteAllButCovers() }
    }

    /** Entfernt den gesamten Speicher des Space: Titelbilder, Medien und Verzeichnis. */
    suspend fun deleteSpace(spaceId: SpaceId) {
        withContext(Dispatchers.IO) { storage(spaceId).deleteEverything() }
    }

    /**
     * Räumt einen gelöschten Space auf: Außer den Titelbildern bleibt nichts im Ordner. So verschwinden Teildateien eines
     * Uploads, den ein Absturz nach der Löschung unterbrochen hat (ADR 0021, Nachtrag 3.6d-2). Fehlt der Ordner, geschieht
     * nichts, auch kein Anlegen.
     */
    suspend fun clearLeftovers(spaceId: SpaceId) {
        withContext(Dispatchers.IO) {
            if (Files.isDirectory(spacesDir.toPath().resolve(spaceId.value), LinkOption.NOFOLLOW_LINKS)) {
                storage(spaceId).deleteAllButCovers()
            }
        }
    }

    private fun storage(spaceId: SpaceId): SpaceStorage = SpaceStorage(spacesDir, spaceId)

    private fun storeBlocking(
        spaceId: SpaceId,
        input: InputStream,
    ): CoverResult {
        val head = readHead(input)
        val format = ContentInspector.identify(head) ?: return CoverResult.UnsupportedType
        if (format.kind != MediaKind.IMAGE) return CoverResult.UnsupportedType

        val storage = storage(spaceId)
        val temp = storage.newTempPath()
        try {
            val digest = MessageDigest.getInstance(SHA_256)
            val size = copyBounded(input, temp, head, digest) ?: return CoverResult.TooLarge(maxBytes)
            if (!structureIsValid(format, temp, size)) return CoverResult.UnsupportedType
            val streamedSha256 = digest.digest().toHex()
            return when (val outcome = LocationScrubber.scrub(temp.toFile(), format, streamedSha256)) {
                is LocationScrubResult.NotRemovable -> {
                    CoverResult.LocationNotRemovable(outcome.reason)
                }

                is LocationScrubResult.Clean -> {
                    storage.moveAtomically(temp, storage.coverPath(outcome.sha256))
                    CoverResult.Stored(outcome.sha256)
                }
            }
        } finally {
            // Nach einer erfolgreichen Übernahme gibt es die Temp-Datei nicht mehr. Bei jedem anderen Ausgang verschwindet sie.
            Files.deleteIfExists(temp)
        }
    }

    /** Liest bis zu [ContentInspector.HEAD_BYTES] Bytes für die Erkennung. Kürzer ist nur bei kurzen Dateien. */
    private fun readHead(input: InputStream): ByteArray {
        val buffer = ByteArray(ContentInspector.HEAD_BYTES)
        var filled = 0
        while (filled < buffer.size) {
            val read = input.read(buffer, filled, buffer.size - filled)
            if (read < 0) break
            filled += read
        }
        return buffer.copyOf(filled)
    }

    /**
     * Schreibt Kopf und Rest in [target], höchstens [maxBytes]. Liefert die Länge oder `null` bei Überschreitung. Der Hash
     * entsteht im selben Durchlauf.
     */
    private fun copyBounded(
        input: InputStream,
        target: Path,
        head: ByteArray,
        digest: MessageDigest,
    ): Long? {
        var total = 0L
        Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
            out.write(head)
            digest.update(head)
            total += head.size
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) return null
                out.write(buffer, 0, read)
                digest.update(buffer, 0, read)
            }
        }
        return total
    }

    private fun structureIsValid(
        format: DetectedFormat,
        path: Path,
        size: Long,
    ): Boolean {
        if (!format.boxed) return true
        return RandomAccessFile(path.toFile(), "r").use { file ->
            ContentInspector.hasValidStructure(format, size) { offset, length ->
                val count = min(length.toLong(), size - offset).toInt()
                ByteArray(count).also {
                    file.seek(offset)
                    file.readFully(it)
                }
            }
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private companion object {
        const val SHA_256 = "SHA-256"
        const val BUFFER_BYTES = 64 * 1024
    }
}
