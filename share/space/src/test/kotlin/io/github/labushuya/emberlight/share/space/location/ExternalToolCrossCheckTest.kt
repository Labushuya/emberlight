package io.github.labushuya.emberlight.share.space.location

import io.github.labushuya.emberlight.share.space.DetectedFormat
import io.github.labushuya.emberlight.share.space.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Gegenprobe mit ExifTool, ffmpeg, heif-enc und avifenc (ADR 0015, Tests). Unabhängige Werkzeuge prüfen den Standort
 * vorher und nachher.
 *
 * - (a) Eingaben entstehen mit Werkzeugen: ffmpeg für MP4 und MOV (`-metadata location=…`, Apple-Key ISO6709),
 *   heif-enc und avifenc aus einem synthetischen PNG. ExifTool schreibt den Standort in HEIC (EXIF-GPS) und AVIF
 *   (XMP-GPS) hinein.
 * - (b) ExifTool bestätigt, dass die Eingabe tatsächlich Standort enthält. Ein Leerlauf-Test fällt hier auf.
 * - (c) Der Scrubber bereinigt die Datei.
 * - (d) ExifTool findet danach keinen Standort-Wert mehr. Auch keine Nullkoordinaten (0/0): Standort-Boxen werden
 *   umbenannt, Werte überschrieben (ADR 0015, „Kein Null Island“).
 *
 * Lokal fehlen die Werkzeuge meist, dann wird der Test übersprungen. Mit `CI=true` (von GitHub Actions gesetzt) schlägt
 * er bei fehlenden Werkzeugen fehl. Der Runner installiert alle per apt (siehe `.github/workflows/ci.yml`).
 */
class ExternalToolCrossCheckTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun exiftoolSeesTheLocationBeforeAndNothingAfterScrubbing() {
        val missing = TOOLS.filterNot(::startsTool)
        if (missing.isNotEmpty()) {
            if (System.getenv("CI") == "true") {
                fail("Werkzeuge fehlen im CI-Lauf (Gegenprobe ist Pflicht): ${missing.joinToString()}")
            }
            Assume.assumeTrue("Werkzeuge fehlen lokal (${missing.joinToString()}), Gegenprobe übersprungen.", false)
            return
        }

        for (case in cases()) {
            val input = File(folder.root, "${case.name}-input").apply { writeBytes(case.bytes) }
            val before = exiftool(input)
            assertTrue(
                "${case.name}: ExifTool sieht vor der Bereinigung keinen Standort (Leerlauf):\n$before",
                before.lines().any(::isLocationLineWithValue),
            )

            val cleaned = File(folder.root, "${case.name}-cleaned").apply { writeBytes(case.bytes) }
            val result = LocationScrubber.scrub(cleaned, case.format, sha256Hex(case.bytes))
            assertTrue("${case.name}: Bereinigung liefert $result, erwartet Clean", result is LocationScrubResult.Clean)
            assertEquals("${case.name}: Länge nach dem Abschneiden", case.keptLength.toLong(), cleaned.length())
            val after = exiftool(cleaned)
            // Ausgabe für die CI-Gegenprobe (ADR 0015): Der Schritt „Gegenprobe-Ausgabe“ zeigt diese Zeilen im Log.
            println(
                "GEGENPROBE ${case.name} vorher: ${before.lines().filter(
                    ::isLocationLineWithValue,
                ).joinToString(" | ")}",
            )
            println("GEGENPROBE ${case.name} nachher: ${after.lines().filter(::isLocationTagLine).joinToString(" | ")}")
            assertNoLocationTag(case.name, after)
        }
    }

    /**
     * Ein Prüffall: Name, Format für den Scrubber und die Bytes der Eingabe. [keptLength] ist die Länge nach dem Bereinigen.
     * Sie ist kleiner als die Eingabe, wenn ein Anhang abgeschnitten wird (ADR 0015, Anhänge).
     */
    private class Case(
        val name: String,
        val format: DetectedFormat,
        val bytes: ByteArray,
        val keptLength: Int = bytes.size,
    )

    private fun cases(): List<Case> {
        val mp4 = ffmpegVideo("mp4")
        // Anhang nach der letzten Box: Zufallsbytes ohne Box-Struktur (ADR 0015, Anhänge).
        val attachment = ByteArray(257) { (it * 37 + 11).toByte() }
        return listOf(
            Case("mp4", DetectedFormat("video/mp4", MediaKind.VIDEO, boxed = true), mp4),
            Case(
                "mp4-anhang",
                DetectedFormat("video/mp4", MediaKind.VIDEO, boxed = true),
                mp4 + attachment,
                keptLength = mp4.size,
            ),
            Case("mov", DetectedFormat("video/quicktime", MediaKind.VIDEO, boxed = true), ffmpegVideo("mov")),
            Case(
                "jpeg",
                DetectedFormat("image/jpeg", MediaKind.IMAGE, boxed = false),
                LocationFixtures.jpegWithAllLocations(),
            ),
            Case(
                "png",
                DetectedFormat("image/png", MediaKind.IMAGE, boxed = false),
                LocationFixtures.png(
                    LocationFixtures.pngChunk("eXIf", LocationFixtures.exifTiff().bytes),
                    LocationFixtures.pngChunk("iTXt", LocationFixtures.itxtXmp(LocationFixtures.XMP_WITH_LOCATION)),
                ),
            ),
            Case(
                "webp",
                DetectedFormat("image/webp", MediaKind.IMAGE, boxed = false),
                LocationFixtures.webp(
                    LocationFixtures.webpChunk("EXIF", LocationFixtures.exifTiff().bytes),
                    LocationFixtures.webpChunk("XMP ", LocationFixtures.ascii(LocationFixtures.XMP_WITH_LOCATION)),
                ),
            ),
            Case("heic", DetectedFormat("image/heic", MediaKind.IMAGE, boxed = true), heicWithLocation()),
            Case("avif", DetectedFormat("image/avif", MediaKind.IMAGE, boxed = true), avifWithLocation()),
        )
    }

    /** MP4 oder MOV mit dem Standort als `©xyz` und dem Apple-Key. */
    private fun ffmpegVideo(extension: String): ByteArray {
        val output = File(folder.root, "ffmpeg-input.$extension")
        run(
            "ffmpeg",
            "-v",
            "error",
            "-y",
            "-f",
            "lavfi",
            "-i",
            "color=c=black:s=64x64:d=0.2",
            "-c:v",
            "mpeg4",
            "-movflags",
            "use_metadata_tags",
            "-metadata",
            "location=+48.8566+002.3522/",
            "-metadata",
            "com.apple.quicktime.location.ISO6709=+48.8566+002.3522/",
            output.absolutePath,
        )
        return output.readBytes()
    }

    /** HEIC aus einem synthetischen PNG per heif-enc, danach EXIF-GPS per ExifTool. */
    private fun heicWithLocation(): ByteArray {
        val source = syntheticPng("heic-source")
        val output = File(folder.root, "heic-source.heic")
        run("heif-enc", "-q", "50", "-o", output.absolutePath, source.absolutePath)
        run(
            "exiftool",
            "-overwrite_original",
            "-GPSLatitude=48.8566",
            "-GPSLongitude=2.3522",
            "-GPSLatitudeRef=N",
            "-GPSLongitudeRef=E",
            output.absolutePath,
        )
        return output.readBytes()
    }

    /** AVIF aus einem synthetischen PNG per avifenc, danach XMP-GPS per ExifTool. */
    private fun avifWithLocation(): ByteArray {
        val source = syntheticPng("avif-source")
        val output = File(folder.root, "avif-source.avif")
        run("avifenc", source.absolutePath, output.absolutePath)
        run(
            "exiftool",
            "-overwrite_original",
            "-XMP:GPSLatitude=48.8566",
            "-XMP:GPSLongitude=2.3522",
            output.absolutePath,
        )
        return output.readBytes()
    }

    /** Ein kleines, gültiges PNG (Farbverlauf), das die Encoder brauchen. Entsteht im Test, keine Fremddatei. */
    private fun syntheticPng(name: String): File {
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 64) {
            for (y in 0 until 64) {
                image.setRGB(x, y, ((x * 4) shl 16) or ((y * 4) shl 8) or 0x80)
            }
        }
        val file = File(folder.root, "$name.png")
        check(ImageIO.write(image, "png", file)) { "PNG konnte nicht geschrieben werden." }
        return file
    }

    private fun exiftool(file: File): String =
        run("exiftool", "-a", "-G1", "-gps:all", "-location:all", "-xmp:all", file.absolutePath)

    /**
     * Eine Zeile mit Standort-Tag und einem Wert mit Ziffer. Nullkoordinaten zählen ebenso (0/0), denn die Bereinigung
     * darf keine Position mehr ableiten lassen. Namen ohne Wert sind unkritisch.
     */
    private fun isLocationLineWithValue(line: String): Boolean =
        isLocationTagLine(line) && line.substringAfter(':', "").any { it.isDigit() }

    /** Eine Zeile mit Standort-Tag, mit oder ohne Wert. */
    private fun isLocationTagLine(line: String): Boolean = GPS_TAG.containsMatchIn(line.substringBefore(':'))

    private fun assertNoLocationTag(
        name: String,
        output: String,
    ) {
        val leaks = output.lines().filter { it.contains("48.8566") || isLocationLineWithValue(it) }
        assertTrue(
            "$name: ExifTool findet nach der Bereinigung noch einen Standort:\n${leaks.joinToString("\n")}",
            leaks.isEmpty(),
        )
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Startet das Werkzeug. Gilt als vorhanden, wenn der Prozess startet und endet. Der Exit-Code zählt nicht, weil
     * nicht jedes Werkzeug `-ver` oder `--version` kennt.
     */
    private fun startsTool(tool: String): Boolean =
        try {
            val process = ProcessBuilder(tool, PROBE_ARG).redirectErrorStream(true).start()
            process.inputStream.readBytes()
            process.waitFor(30, TimeUnit.SECONDS)
        } catch (missing: IOException) {
            false
        }

    private fun run(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(120, TimeUnit.SECONDS)) { "Zeitüberschreitung: ${command.joinToString(" ")}" }
        assertEquals("Fehlgeschlagen: ${command.joinToString(" ")}\n$output", 0, process.exitValue())
        return output
    }

    private companion object {
        val GPS_TAG = Regex("(?i)gps|location|city|country")
        val TOOLS = listOf("exiftool", "ffmpeg", "heif-enc", "avifenc")
        const val PROBE_ARG = "-h"
    }
}
