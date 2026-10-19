package io.github.labushuya.emberlight.share.space.location

import io.github.labushuya.emberlight.share.space.DetectedFormat
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Ergebnis der Standort-Bereinigung einer Datei (ADR 0015). */
sealed interface LocationScrubResult {
    /**
     * Bereinigt oder frei von Standort. [changed] ist `true`, wenn Bytes überschrieben oder Anhänge abgeschnitten wurden.
     * [sha256] ist der Hash
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
 * Entfernt Standortdaten beim Upload (ADR 0015). Die Bereinigung überschreibt Bytes, ohne Längen innerhalb einer
 * Struktur zu ändern, und ist deterministisch: Gleiche Standortdaten werden durch gleiche Füllbytes ersetzt.
 *
 * Anhänge hinter dem logischen Dateiende (ADR 0015, Abschnitt „Anhänge nach dem Dateiende“) werden abgeschnitten. Die
 * Länge ändert sich dann, der Hash gilt für den Stand nach dem Kürzen.
 *
 * Ablauf (Plan/Apply, ohne Sicherungskopie):
 * 1. Plan: Der Walker läuft im Änderungsmodus gegen [PlanStore]. Die Bereiche und die Ziel-Länge liegen im Speicher,
 *    die Datei bleibt unverändert.
 * 2. Prüfung des Plans: Der Detektor läuft ohne Änderung über den geplanten Stand, also auch über die gekürzte Länge.
 * 3. Ein Byte-Scan über den geplanten Stand sucht nach ISO-6709-Koordinaten und GPS-Eigenschaften. Ist nichts
 *    geändert, läuft nur dieser Scan, und der beim Streamen berechnete Hash bleibt gültig. Ist etwas geändert, berechnet
 *    derselbe Durchlauf den Hash über die verarbeiteten Bytes.
 * 4. Apply: Erst wenn alles besteht, werden die Bereiche geschrieben und die Datei auf die Ziel-Länge gekürzt. Ein
 *    Fehler davor hinterlässt die Datei unverändert.
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

    /** Ergebnis von Plan und Prüfung, bevor etwas geschrieben wird. */
    private sealed interface Verdict {
        class Ready(
            val changed: Boolean,
            val sha256: String,
        ) : Verdict

        class Refused(
            val reason: NotRemovableReason,
        ) : Verdict
    }

    /**
     * Kern der Bereinigung auf einem beliebigen Speicher. Plan und Prüfung laufen im Fehlerfang: Dort gelten Fehler der
     * Struktur als nicht bereinigbar. Das Schreiben (Apply) läuft danach außerhalb davon, damit ein Fehler dort nie als
     * Strukturfehler mit halb geschriebener Datei endet.
     */
    internal fun scrubStore(
        base: ByteStore,
        format: DetectedFormat,
        streamedSha256: String,
    ): LocationScrubResult {
        val plan = PlanStore(base)
        val verdict =
            try {
                verify(plan, format, streamedSha256)
            } catch (refused: Unremovable) {
                Verdict.Refused(refused.reason)
            } catch (defect: RuntimeException) {
                // Fail closed: Unerwartete Fehler in einer defekten Struktur gelten als nicht bereinigbar.
                Verdict.Refused(NotRemovableReason.STRUCTURE)
            }
        return when (verdict) {
            is Verdict.Refused -> {
                LocationScrubResult.NotRemovable(verdict.reason)
            }

            is Verdict.Ready -> {
                if (verdict.changed) plan.applyTo(base)
                LocationScrubResult.Clean(changed = verdict.changed, sha256 = verdict.sha256)
            }
        }
    }

    /** Plan, Prüfdurchlauf und Byte-Scan über den geplanten Stand. Schreibt nichts. */
    private fun verify(
        plan: PlanStore,
        format: DetectedFormat,
        streamedSha256: String,
    ): Verdict {
        dispatch(ScrubPass(plan, mutate = true), format.mimeType)
        val check = ScrubPass(plan, mutate = false)
        dispatch(check, format.mimeType)
        if (check.findings.isNotEmpty()) return Verdict.Refused(NotRemovableReason.VERIFICATION_FAILED)
        if (!plan.hasChanges) {
            return if (LocationText.scanAll(plan, digest = null)) {
                Verdict.Refused(NotRemovableReason.VERIFICATION_FAILED)
            } else {
                Verdict.Ready(changed = false, sha256 = streamedSha256)
            }
        }
        val digest = MessageDigest.getInstance(SHA_256)
        return if (LocationText.scanAll(plan, digest)) {
            Verdict.Refused(NotRemovableReason.VERIFICATION_FAILED)
        } else {
            Verdict.Ready(changed = true, sha256 = digest.digest().toHex())
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
