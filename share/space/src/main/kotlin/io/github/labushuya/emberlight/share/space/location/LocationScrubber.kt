package io.github.labushuya.emberlight.share.space.location

import io.github.labushuya.emberlight.share.space.DetectedFormat
import java.io.File
import java.io.RandomAccessFile

/** Ergebnis der Standort-Bereinigung einer Datei (ADR 0015). */
sealed interface LocationScrubResult {
    /** Bereinigt oder frei von Standort. [changed] ist `true`, wenn Bytes überschrieben wurden. */
    data class Clean(
        val changed: Boolean,
    ) : LocationScrubResult

    /** Nicht sicher bereinigbar. Der Aufrufer lehnt ab oder speichert mit ausdrücklicher Einwilligung. */
    data class NotRemovable(
        val reason: NotRemovableReason,
    ) : LocationScrubResult
}

/**
 * Entfernt Standortdaten beim Upload (ADR 0015). Die Bereinigung überschreibt nur Bytes, ohne Längen zu ändern, und
 * ist deterministisch: Gleiche Standortdaten werden durch gleiche Füllbytes ersetzt.
 *
 * Ablauf: Erst der Walker im Änderungsmodus. Dann die Prüfung, die fail closed ist. Der Detektor läuft erneut ohne
 * Änderung, und ein Byte-Scan sucht im ganzen Datenstrom nach ISO-6709-Koordinaten und GPS-Eigenschaften. Findet die
 * Prüfung etwas, gilt die Datei als nicht bereinigbar.
 */
object LocationScrubber {
    /** Version der Verarbeitung. Wird mit jedem Medium gespeichert (ADR 0015). */
    const val PROCESSING_VERSION = 1

    /**
     * Bereinigt [file] in place. Blockierend, der Aufruf gehört auf einen IO-Dispatcher. Unerwartete Fehler im
     * Format gelten als `structure`, nicht als Absturz. I/O-Fehler laufen weiter, damit der Server sie meldet.
     */
    fun scrub(
        file: File,
        format: DetectedFormat,
    ): LocationScrubResult =
        RandomAccessFile(file, "rw").use { raf ->
            val store = FileStore(raf, raf.length())
            try {
                val pass = ScrubPass(store, mutate = true)
                dispatch(pass, format.mimeType)
                val check = ScrubPass(store, mutate = false)
                dispatch(check, format.mimeType)
                if (check.findings.isNotEmpty() || LocationText.containsIn(store, 0, store.length)) {
                    LocationScrubResult.NotRemovable(NotRemovableReason.VERIFICATION_FAILED)
                } else {
                    LocationScrubResult.Clean(changed = pass.writes > 0)
                }
            } catch (refused: Unremovable) {
                LocationScrubResult.NotRemovable(refused.reason)
            } catch (defect: RuntimeException) {
                // Fail closed: Jede unerwartete Lesefehler in einer defekten Struktur gelten als nicht bereinigbar.
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

            // HEIC, HEIF und AVIF: Der Item-Walker (Exif-Items, iloc) ist in diesem Paket nicht umgesetzt.
            else -> bail(NotRemovableReason.UNSUPPORTED)
        }
    }
}
