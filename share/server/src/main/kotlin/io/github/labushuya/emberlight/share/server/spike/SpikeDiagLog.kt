package io.github.labushuya.emberlight.share.server.spike

import java.io.File
import java.io.IOException

/**
 * Selbstprotokoll des Spike-Dienstes (ADR 0012, nur Branch). Schreibt Zeilen in eine Datei mit begrenzter Größe.
 *
 * Jede Zeile trägt drei Uhren: Wanduhr (`wall`), `elapsedRealtime` (läuft im Tiefschlaf weiter) und `uptimeMillis`
 * (läuft im Tiefschlaf nicht weiter). Lücken nur in `up` deuten auf CPU-Schlaf hin, Lücken in beiden auf ein
 * pausiertes Programm.
 *
 * Die Klasse enthält keine Android-Aufrufe außer dem Dateisystem, damit Formatierung und Kürzung testbar bleiben.
 */
internal class SpikeDiagLog(
    private val file: File,
    private val maxBytes: Long = MAX_BYTES,
) {
    /** Hängt eine Zeile an. Fehler beim Schreiben werden verschluckt, damit das Protokoll nie den Dienst stoppt. */
    @Synchronized
    fun append(line: String) {
        try {
            file.parentFile?.mkdirs()
            val entry = line + "\n"
            if (file.isFile && file.length() + entry.toByteArray(Charsets.UTF_8).size > maxBytes) {
                trim()
            }
            file.appendText(entry, Charsets.UTF_8)
        } catch (_: IOException) {
            // Kein Abbruch wegen Protokoll.
        }
    }

    /** Behält die jüngeren Zeilen, bis etwa die Hälfte der Größe erreicht ist. */
    private fun trim() {
        val kept = keepNewest(file.readLines(Charsets.UTF_8), maxBytes / 2)
        file.writeText(kept.joinToString(separator = "") { it + "\n" }, Charsets.UTF_8)
    }

    companion object {
        const val FILE_NAME = "diag.log"
        const val MAX_BYTES: Long = 2L * 1024 * 1024

        /**
         * Formatiert eine Zeile mit den drei Uhren und dem Ereignistext. Zeilenumbrüche im Text werden entschärft, damit
         * jede Ereignisart genau eine Zeile bleibt.
         */
        fun formatLine(
            wallMs: Long,
            elapsedMs: Long,
            uptimeMs: Long,
            event: String,
        ): String {
            val flat = event.replace('\r', ' ').replace('\n', ' ')
            return "wall=$wallMs elapsed=$elapsedMs up=$uptimeMs $flat"
        }

        /**
         * Liefert die jüngsten Zeilen, deren Summe (inklusive Zeilenumbruch) höchstens [maxBytes] ergibt. Die Reihenfolge
         * bleibt erhalten. Eine einzelne Zeile, die allein zu groß wäre, fällt weg.
         */
        fun keepNewest(
            lines: List<String>,
            maxBytes: Long,
        ): List<String> {
            var total = 0L
            var start = lines.size
            for (index in lines.indices.reversed()) {
                val size = lines[index].toByteArray(Charsets.UTF_8).size + 1L
                if (total + size > maxBytes) break
                total += size
                start = index
            }
            return lines.subList(start, lines.size)
        }
    }
}
