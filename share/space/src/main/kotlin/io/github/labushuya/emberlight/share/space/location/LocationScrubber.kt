package io.github.labushuya.emberlight.share.space.location

import io.github.labushuya.emberlight.share.space.DetectedFormat
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Ergebnis der Standort-Bereinigung einer Datei (ADR 0015). */
sealed interface LocationScrubResult {
    /**
     * Bereinigt oder frei von Standort. [changed] ist `true`, wenn Bytes überschrieben wurden. [sha256] ist der Hash
     * der verarbeiteten Bytes: Bei unverändert ist es der beim Streamen berechnete Hash (kein zweiter Lesedurchgang).
     */
    data class Clean(
        val changed: Boolean,
        val sha256: String,
    ) : LocationScrubResult

    /**
     * Nicht sicher bereinigbar. Die Datei ist in diesem Fall unverändert (Plan/Apply schreibt nur bei Erfolg). Der
     * Aufrufer lehnt ab oder speichert mit ausdrücklicher Einwilligung.
     */
    data class NotRemovable(
        val reason: NotRemovableReason,
    ) : LocationScrubResult
}

/**
 * Entfernt Standortdaten beim Upload (ADR 0015). Die Bereinigung überschreibt nur Bytes, ohne Längen zu ändern, und
 * ist deterministisch: Gleiche Standortdaten werden durch gleiche Füllbytes ersetzt.
 *
 * Ablauf (Plan/Apply, ohne Sicherungskopie):
 * 1. Plan: Der Walker läuft im Änderungsmodus gegen [PlanStore]. Die Bereiche liegen im Speicher, die Datei bleibt
 *    unverändert.
 * 2. Prüfung des Plans: Der Detektor läuft ohne Änderung über den geplanten Stand.
 * 3. Ein Byte-Scan über den geplanten Stand sucht nach ISO-6709-Koordinaten und GPS-Eigenschaften. Ist nichts
 *    geändert, läuft nur dieser Scan, und der beim Streamen berechnete Hash bleibt gültig. Ist etwas geändert, berechnet
 *    derselbe Durchlauf den Hash über die verarbeiteten Bytes.
 * 4. Apply: Erst wenn alles besteht, werden die Bereiche in die Datei geschrieben. Ein Fehler davor hinterlässt
 *    die Datei unverändert.
 */
object LocationScrubber {
    /** Version der Verarbeitung. Wird mit jedem Medium gespeichert (ADR 0015). */
    const val PROCESSING_VERSION = 1

    /**
     * Bereinigt [file] in place. [streamedSha256] ist der Hash der Bytes, wie sie beim Empfang geschrieben wurden.
     * Blockierend, der Aufruf gehört auf einen IO-Dispatcher. Unerwartete Laufzeitfehler im Format gelten als
     * `structure`. Fehler des Dateizugriffs (I/O) und Errors wie `OutOfMemoryError` laufen weiter.
     */
    fun scrub(
        file: File,
        format: DetectedFormat,
        streamedSha256: String,
    ): LocationScrubResult =
        RandomAccessFile(file, "rw").use { raf ->
            scrubStore(FileStore(raf, raf.length()), format, streamedSha256)
        }

    /** Kern der Bereinigung auf einem beliebigen Speicher. Schreibt nur im Apply-Schritt. */
    internal fun scrubStore(
        base: ByteStore,
        format: DetectedFormat,
        streamedSha256: String,
    ): LocationScrubResult {
        val plan = PlanStore(base)
        return try {
            dispatch(ScrubPass(plan, mutate = true), format.mimeType)
            val check = ScrubPass(plan, mutate = false)
            dispatch(check, format.mimeType)
            if (check.findings.isNotEmpty()) {
                return LocationScrubResult.NotRemovable(
                    NotRemovableReason.VERIFICATION_FAILED,
                )
            }
            if (plan.pendingWrites == 0) {
                if (LocationText.scanAll(plan, digest = null)) {
                    LocationScrubResult.NotRemovable(NotRemovableReason.VERIFICATION_FAILED)
                } else {
                    LocationScrubResult.Clean(changed = false, sha256 = streamedSha256)
                }
            } else {
                val digest = MessageDigest.getInstance(SHA_256)
                if (LocationText.scanAll(plan, digest)) {
                    LocationScrubResult.NotRemovable(NotRemovableReason.VERIFICATION_FAILED)
                } else {
                    plan.applyTo(base)
                    LocationScrubResult.Clean(changed = true, sha256 = digest.digest().toHex())
                }
            }
        } catch (refused: Unremovable) {
            LocationScrubResult.NotRemovable(refused.reason)
        } catch (defect: RuntimeException) {
            // Fail closed: Unerwartete Fehler in einer defekten Struktur gelten als nicht bereinigbar.
            LocationScrubResult.NotRemovable(NotRemovableReason.STRUCTURE)
        }
    }

    private fun dispatch(
        pass: ScrubPass,
        mimeType: String,
    ) {
        when (mimeType) {
            "image/jpeg" -> JpegScrubber.scrubFile(pass)
            "image/png" -> PngScrubber.scrubFile(pass)
            "image/webp" -> WebpScrubber.scrubFile(pass)
            "image/gif" -> GifScrubber.scrubFile(pass)
            "video/mp4", "video/quicktime", "video/3gpp" -> BmffScrubber.scrubFile(pass)
            "image/heic", "image/heif", "image/avif" -> HeifScrubber.scrubFile(pass)
            else -> bail(NotRemovableReason.UNSUPPORTED)
        }
    }

    private const val SHA_256 = "SHA-256"

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
