package io.github.labushuya.emberlight.share.space.location

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.GpsDirectory
import com.drew.metadata.mov.metadata.QuickTimeMetadataDirectory
import com.drew.metadata.mp4.Mp4Directory
import com.drew.metadata.xmp.XmpDirectory
import io.github.labushuya.emberlight.share.space.ContentInspector
import io.github.labushuya.emberlight.share.space.DetectedFormat
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.math.min

/**
 * Hilfstest für die Gerätetests des Pakets 3.1b (ADR 0015). Opt-in: Ohne Umgebungsvariable wird der Test übersprungen,
 * auch in der CI.
 *
 * - `EMBERLIGHT_SAMPLES_DIR`: Originaldateien. Je Datei: Standort vorher (metadata-extractor, ja/nein, Directory- und
 *   Tag-Namen), Strukturprüfung und Ergebnis des Scrubbers im Plan-Modus. Die Datei wird nur gelesen, nie geschrieben.
 * - `EMBERLIGHT_COMPARE_DIR`: Arbeitskopien, zum Beispiel vom Gerät. Je Datei: Standort nachher, Strukturprüfung und
 *   Dekodierbarkeit.
 * - `EMBERLIGHT_SCRUB_OUT_DIR`: Ausgabeordner. Mit `EMBERLIGHT_SAMPLES_DIR` wird jede Probe in eine nummerierte Kopie
 *   kopiert und dort per Apply bereinigt. Die Originale werden nur gelesen. Danach prüft der Bericht die Kopien wie
 *   Arbeitskopien (Standort nachher, Struktur, Dekodierbarkeit).
 * - `EMBERLIGHT_REPORT_FILE`: Pfad der Berichtsdatei (Pflicht, wenn einer der beiden Ordner gesetzt ist).
 *
 * Der Bericht enthält nur Ja/Nein, Namen von Directories und Tags, Reason-Codes, Größen und Hashes. Keine Werte von
 * Metadaten, keine Dateinamen.
 */
class SampleLocationReportTest {
    @Test
    fun reportsLocationAndPlanForSamplesAndCopies() {
        val samplesDir = directoryFromEnv(ENV_SAMPLES)
        val compareDir = directoryFromEnv(ENV_COMPARE)
        Assume.assumeTrue(
            "Opt-in: $ENV_SAMPLES oder $ENV_COMPARE nicht gesetzt.",
            samplesDir != null || compareDir != null,
        )
        val reportFile = File(requireNotNull(System.getenv(ENV_REPORT)) { "$ENV_REPORT fehlt." })

        val report = mutableListOf<String>()
        var failures = 0
        if (samplesDir != null) {
            report += "== Originale (Plan-Modus, nur Lesen)"
            samplesDir.listSamples().forEachIndexed { index, file ->
                try {
                    report += reportSample(index + 1, file)
                } catch (failure: Exception) {
                    failures++
                    report += "#${index + 1} Fehler: ${failure.javaClass.simpleName}"
                }
            }
        }
        val scrubOut = System.getenv(ENV_SCRUB_OUT)?.takeIf { it.isNotBlank() }?.let { File(it) }
        if (samplesDir != null && scrubOut != null) {
            // Originale nur lesen und kopieren. Bereinigt wird ausschließlich die Kopie im Ausgabeordner.
            scrubOut.mkdirs()
            report += "== Bereinigung (Apply nur auf Kopien im Ausgabeordner)"
            samplesDir.listSamples().forEachIndexed { index, source ->
                try {
                    report += scrubCopy(index + 1, source, scrubOut)
                } catch (failure: Exception) {
                    failures++
                    report += "#${index + 1} Fehler: ${failure.javaClass.simpleName}"
                }
            }
            report += "== Ausgaben der Bereinigung (Standort nachher)"
            scrubOut.listSamples().forEachIndexed { index, file ->
                try {
                    report += reportCopy(index + 1, file)
                } catch (failure: Exception) {
                    failures++
                    report += "#${index + 1} Fehler: ${failure.javaClass.simpleName}"
                }
            }
        }
        if (compareDir != null) {
            report += "== Arbeitskopien (Standort nachher)"
            compareDir.listSamples().forEachIndexed { index, file ->
                try {
                    report += reportCopy(index + 1, file)
                } catch (failure: Exception) {
                    failures++
                    report += "#${index + 1} Fehler: ${failure.javaClass.simpleName}"
                }
            }
        }
        reportFile.absoluteFile.parentFile?.mkdirs()
        reportFile.writeText(report.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        assertTrue("Lesefehler bei $failures Datei(en), siehe Bericht.", failures == 0)
    }

    private fun reportSample(
        number: Int,
        file: File,
    ): List<String> {
        val label = labelOf(file.name)
        val size = file.length()
        val sha = sha256Hex(file)
        val location = locationReport(file)
        val head = headOf(file)
        val format = ContentInspector.identify(head)
        val lines = mutableListOf("#$number [$label] Größe=$size B SHA-256=${sha.take(16)}")
        lines += "  Standort vorher: ${location.summary()}"
        lines += "  Gegenprobe ISO-6709-Muster im Bytestrom: ${iso6709Count(file)}"
        lines += "  Gegenprobe streng (Rohstrom): ${strictReport(file, format?.mimeType)}"
        if (format == null) {
            lines += "  Format: nicht erkannt (Upload 415)"
            return lines
        }
        lines += "  Format: ${format.mimeType}"
        if (!structureValid(file, format)) {
            lines += "  Struktur: ungültig (Upload 415)"
            return lines
        }
        lines += "  Struktur: ok"
        lines += "  Scrubber Plan: ${planScrub(file, format, sha)}"
        return lines
    }

    /**
     * Bereinigt eine Kopie der Probe im Ausgabeordner per Apply. Die Probe selbst wird nur gelesen und kopiert. Der
     * Name der Kopie ist laufend nummeriert und enthält keinen Originalnamen.
     */
    private fun scrubCopy(
        number: Int,
        source: File,
        outDir: File,
    ): List<String> {
        val format =
            ContentInspector.identify(headOf(source))
                ?: return listOf("#$number Bereinigung übersprungen: Format nicht erkannt")
        val target = File(outDir, "sample-%02d.%s".format(number, extensionFor(format.mimeType)))
        source.copyTo(target, overwrite = true)
        val before = source.length()
        // Hash der empfangenen Bytes, wie beim Upload. Er gilt, solange die Kopie unverändert bleibt.
        val sha = sha256Hex(source)
        val outcome = LocationScrubber.scrub(target, format, sha)
        val result =
            when (outcome) {
                is LocationScrubResult.Clean -> {
                    if (outcome.changed) "bereinigt (überschrieben und/oder gekürzt)" else "ohne Standort, unverändert"
                }

                is LocationScrubResult.NotRemovable -> {
                    "nicht bereinigbar, reason=${outcome.reason.code} (Kopie unverändert)"
                }
            }
        return listOf(
            "#$number Bereinigung ${format.mimeType}: Länge vorher=${before / 1024} KB, nachher=${target.length() / 1024} KB",
            "  Ergebnis: $result",
        )
    }

    private fun extensionFor(mimeType: String): String =
        when (mimeType) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "video/mp4", "video/quicktime", "video/3gpp" -> "mp4"
            "image/heic", "image/heif" -> "heic"
            "image/avif" -> "avif"
            else -> "bin"
        }

    private fun reportCopy(
        number: Int,
        file: File,
    ): List<String> {
        val size = file.length()
        val sha = sha256Hex(file)
        val location = locationReport(file)
        val format = ContentInspector.identify(headOf(file))
        val lines = mutableListOf("#$number Kopie Größe=$size B SHA-256=${sha.take(16)}")
        lines += "  Standort nachher: ${location.summary()}"
        lines += "  Gegenprobe ISO-6709-Muster im Bytestrom: ${iso6709Count(file)}"
        lines += "  Gegenprobe streng (Rohstrom): ${strictReport(file, format?.mimeType)}"
        if (format == null) {
            lines += "  Format: nicht erkannt"
            lines += "  Dekodierbar: nein"
            return lines
        }
        lines += "  Format: ${format.mimeType}"
        val structure = structureValid(file, format)
        lines += "  Struktur: ${if (structure) "ok" else "ungültig"}"
        lines += "  Dekodierbar: ${decodable(file, format, structure, location.readable)}"
        return lines
    }

    /** Standort aus metadata-extractor: nur Ja/Nein sowie Namen von Directories und Tags, keine Werte. */
    private class LocationReport(
        val readable: Boolean,
        val present: Boolean,
        val details: List<String>,
    ) {
        fun summary(): String =
            when {
                !readable -> "Lesefehler (metadata-extractor)"
                present -> "ja (${details.joinToString("; ")})"
                details.isEmpty() -> "nein"
                else -> "nein (${details.joinToString("; ")})"
            }
    }

    private fun locationReport(file: File): LocationReport {
        val metadata =
            try {
                ImageMetadataReader.readMetadata(file)
            } catch (unreadable: Exception) {
                return LocationReport(readable = false, present = false, details = emptyList())
            }
        var present = false
        val details = mutableListOf<String>()
        for (directory in metadata.directories) {
            val tagNames = directory.tags.map { it.tagName }
            val locationTagNames = tagNames.filter { LOCATION_TAG_NAME.containsMatchIn(it) }.distinct()
            val coordinates =
                when (directory) {
                    is GpsDirectory -> {
                        directory.geoLocation != null
                    }

                    is XmpDirectory -> {
                        directory.xmpProperties.keys.any { GPS_PROPERTY.containsMatchIn(it) }
                    }

                    is QuickTimeMetadataDirectory -> {
                        directory.containsTag(QuickTimeMetadataDirectory.TAG_LOCATION_ISO6709)
                    }

                    is Mp4Directory -> {
                        directory.containsTag(Mp4Directory.TAG_LATITUDE) ||
                            directory.containsTag(Mp4Directory.TAG_LONGITUDE)
                    }

                    else -> {
                        false
                    }
                }
            if (coordinates) present = true
            if (coordinates || locationTagNames.isNotEmpty()) {
                val coordinatesText = if (coordinates) "Koordinaten vorhanden" else "keine Koordinaten"
                val tagsText = if (locationTagNames.isEmpty()) "" else ", Tags: ${locationTagNames.joinToString(", ")}"
                details += "${directory.name}-Directory, ${tagNames.size} Tags, $coordinatesText$tagsText"
            }
        }
        return LocationReport(readable = true, present = present, details = details)
    }

    /** Strenge Gegenprobe über den Rohstrom: nur Befundnamen, keine Werte. */
    private fun strictReport(
        file: File,
        mimeType: String?,
    ): String =
        RandomAccessFile(file, "r").use { raf ->
            val scanTiff = mimeType != null && mimeType in LocationScrubber.TIFF_CONTIGUOUS_MIME_TYPES
            val findings = StrictLocationProbe.findings(FileStore(raf, raf.length()), scanTiff = scanTiff)
            if (findings.isEmpty()) "kein Befund" else "Befunde: ${findings.joinToString(", ")}"
        }

    /** Gegenprobe über Bytes, nur Anzahl. Ohne Werte, nur für Dateien bis 64 MiB. */
    private fun iso6709Count(file: File): String {
        if (file.length() > MAX_PATTERN_BYTES) return "übersprungen (zu groß)"
        val text = file.readBytes().toString(Charsets.ISO_8859_1)
        return ISO_6709.findAll(text).count().toString()
    }

    private fun planScrub(
        file: File,
        format: DetectedFormat,
        sha: String,
    ): String =
        RandomAccessFile(file, "r").use { raf ->
            // Lesender Speicher: Schreibvorgänge werden nur gezählt, nie ausgeführt. Die Datei ist ohnehin read-only offen.
            val store = PlanOnlyStore(raf)
            when (val outcome = LocationScrubber.scrubStore(store, format, sha)) {
                is LocationScrubResult.Clean -> {
                    if (outcome.changed) {
                        "bereinigbar, Bereiche=${store.writes}, geänderte Bytes=${store.changedBytes}, " +
                            "Kürzen=${store.truncations}, Hash nach Plan=${outcome.sha256.take(16)}"
                    } else {
                        "ohne Standort"
                    }
                }

                is LocationScrubResult.NotRemovable -> {
                    "nicht bereinigbar, reason=${outcome.reason.code}"
                }
            }
        }

    private fun decodable(
        file: File,
        format: DetectedFormat,
        structure: Boolean,
        readable: Boolean,
    ): String {
        if (!structure) return "nein (Struktur)"
        return when (format.mimeType) {
            "image/jpeg", "image/png" -> {
                val image = runCatching { ImageIO.read(file) }.getOrNull()
                if (image != null) "ja (ImageIO)" else "nein (ImageIO)"
            }

            else -> {
                if (readable) "ja (metadata-extractor, Box-Kette)" else "nein (metadata-extractor)"
            }
        }
    }

    private fun structureValid(
        file: File,
        format: DetectedFormat,
    ): Boolean =
        RandomAccessFile(file, "r").use { raf ->
            ContentInspector.hasValidStructure(format, raf.length()) { offset, length ->
                val count = min(length.toLong(), raf.length() - offset).toInt()
                ByteArray(count).also {
                    raf.seek(offset)
                    raf.readFully(it)
                }
            }
        }

    private fun headOf(file: File): ByteArray =
        RandomAccessFile(file, "r").use { raf ->
            ByteArray(min(ContentInspector.HEAD_BYTES.toLong(), raf.length()).toInt()).also { raf.readFully(it) }
        }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(BLOCK_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    /** Kategorie nach bekanntem Namensmuster. Der Dateiname selbst erscheint nicht im Bericht. */
    private fun labelOf(name: String): String =
        when {
            name.startsWith("IMG_") -> "Kamera-Foto"
            name.startsWith("VID_") -> "Kamera-Video"
            name.startsWith("motion_photo") -> "Motion-Photo"
            else -> "Sonstige"
        }

    private fun directoryFromEnv(name: String): File? =
        System.getenv(name)?.takeIf { it.isNotBlank() }?.let { File(it) }?.also {
            require(it.isDirectory) { "$name ist kein Ordner." }
        }

    private fun File.listSamples(): List<File> = listFiles { file -> file.isFile }.orEmpty().sortedBy { it.name }

    private class PlanOnlyStore(
        private val file: RandomAccessFile,
    ) : ByteStore {
        override val length: Long = file.length()
        var writes = 0
            private set
        var changedBytes = 0L
            private set

        override fun read(
            offset: Long,
            count: Int,
        ): ByteArray {
            val out = ByteArray(count)
            file.seek(offset)
            file.readFully(out)
            return out
        }

        override fun write(
            offset: Long,
            bytes: ByteArray,
        ) {
            writes++
            changedBytes += bytes.size
        }

        override fun truncate(newLength: Long) {
            truncations++
        }

        var truncations = 0
            private set
    }

    private companion object {
        const val ENV_SAMPLES = "EMBERLIGHT_SAMPLES_DIR"
        const val ENV_COMPARE = "EMBERLIGHT_COMPARE_DIR"
        const val ENV_REPORT = "EMBERLIGHT_REPORT_FILE"
        const val ENV_SCRUB_OUT = "EMBERLIGHT_SCRUB_OUT_DIR"
        const val BLOCK_BYTES = 64 * 1024
        const val MAX_PATTERN_BYTES = 64L * 1024 * 1024
        val ISO_6709 = Regex("[+-]\\d{1,3}\\.\\d+[+-]\\d{1,3}\\.\\d+")
        val GPS_PROPERTY = Regex("GPS", RegexOption.IGNORE_CASE)
        val LOCATION_TAG_NAME = Regex("GPS|Latitude|Longitude|Location|ISO 6709", RegexOption.IGNORE_CASE)
    }
}
