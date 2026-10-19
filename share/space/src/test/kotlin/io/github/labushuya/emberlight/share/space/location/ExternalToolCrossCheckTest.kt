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
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Gegenprobe mit ExifTool und ffmpeg (ADR 0015, Tests). Unabhängige Werkzeuge prüfen den Standort vorher und nachher.
 *
 * - (a) ffmpeg erzeugt ein MP4 mit `-metadata location=…` und dem Apple-Key `com.apple.quicktime.location.ISO6709`.
 * - (b) ExifTool bestätigt, dass die Eingabe tatsächlich Standort enthält. Ein Leerlauf-Test fällt hier auf.
 * - (c) Der Scrubber bereinigt die Datei und die synthetischen Bilder.
 * - (d) ExifTool findet danach keinen Standort-Wert mehr.
 *
 * Lokal fehlen die Werkzeuge meist, dann wird der Test übersprungen. Mit `CI=true` (von GitHub Actions gesetzt) schlägt
 * er bei fehlenden Werkzeugen fehl. Der Runner installiert beide per apt (siehe `.github/workflows/ci.yml`).
 */
class ExternalToolCrossCheckTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun exiftoolAndFfmpegSeeTheLocationBeforeAndNothingAfterScrubbing() {
        if (!toolsAvailable()) {
            if (System.getenv("CI") == "true") fail("ExifTool oder ffmpeg fehlt im CI-Lauf (Gegenprobe ist Pflicht).")
            Assume.assumeTrue("ExifTool und ffmpeg fehlen lokal, Gegenprobe übersprungen.", false)
            return
        }

        // (a) und (b): ffmpeg schreibt Standort, ExifTool bestätigt ihn vorher.
        val video = folder.newFile("ffmpeg-input.mp4")
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
            video.absolutePath,
        )
        val before = run("exiftool", "-a", "-G1", "-gps:all", "-location:all", "-xmp:all", video.absolutePath)
        assertTrue("Kein Standort in der Eingabe, der Test wäre Leerlauf:\n$before", before.contains("48.8566"))

        // (c) und (d): bereinigen, danach findet ExifTool keinen Standort mehr.
        val cleanedVideo = folder.newFile("ffmpeg-cleaned.mp4")
        cleanedVideo.writeBytes(video.readBytes())
        assertEquals(
            LocationScrubResult.Clean(changed = true),
            LocationScrubber.scrub(cleanedVideo, DetectedFormat("video/mp4", MediaKind.VIDEO, boxed = true)),
        )
        assertNoLocationTag(
            run("exiftool", "-a", "-G1", "-gps:all", "-location:all", "-xmp:all", cleanedVideo.absolutePath),
        )

        // Synthetische Bilder mit allen Fundorten: dieselbe Kette.
        for (case in imageCases()) {
            val input = folder.newFile("${case.first}-input")
            input.writeBytes(case.second)
            val beforeImage = run("exiftool", "-a", "-G1", "-gps:all", "-xmp:all", input.absolutePath)
            assertTrue(
                "${case.first}: ExifTool sieht keinen GPS-Standort:\n$beforeImage",
                beforeImage.lines().any(::isLocationLineWithValue),
            )

            val cleaned = folder.newFile("${case.first}-cleaned")
            cleaned.writeBytes(case.second)
            val mime = case.third
            val result = LocationScrubber.scrub(cleaned, DetectedFormat(mime, MediaKind.IMAGE, boxed = false))
            assertTrue("${case.first}: $result", result is LocationScrubResult.Clean)
            assertEquals("${case.first}: Länge", case.second.size.toLong(), cleaned.length())
            assertNoLocationTag(run("exiftool", "-a", "-G1", "-gps:all", "-xmp:all", cleaned.absolutePath))
        }
    }

    /** Fälle: Name, Bytes, MIME-Typ. */
    private fun imageCases(): List<Triple<String, ByteArray, String>> =
        listOf(
            Triple("jpeg", LocationFixtures.jpegWithAllLocations(), "image/jpeg"),
            Triple(
                "png",
                LocationFixtures.png(
                    LocationFixtures.pngChunk("eXIf", LocationFixtures.exifTiff().bytes),
                    LocationFixtures.pngChunk("iTXt", LocationFixtures.itxtXmp(LocationFixtures.XMP_WITH_LOCATION)),
                ),
                "image/png",
            ),
            Triple(
                "webp",
                LocationFixtures.webp(
                    LocationFixtures.webpChunk("EXIF", LocationFixtures.exifTiff().bytes),
                    LocationFixtures.webpChunk("XMP ", LocationFixtures.ascii(LocationFixtures.XMP_WITH_LOCATION)),
                ),
                "image/webp",
            ),
        )

    private fun toolsAvailable(): Boolean = probe("exiftool", "-ver") && probe("ffmpeg", "-version")

    private fun probe(vararg command: String): Boolean =
        try {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            process.inputStream.readBytes()
            process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0
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

    /** Eine Zeile mit Standort-Tag und Wert (Ziffern hinter dem Doppelpunkt). Namen ohne Wert sind unkritisch. */
    private fun isLocationLineWithValue(line: String): Boolean =
        GPS_TAG.containsMatchIn(line) && line.substringAfter(':', "").any(Char::isDigit)

    private fun assertNoLocationTag(output: String) {
        val leaks = output.lines().filter { it.contains("48.8566") || isLocationLineWithValue(it) }
        assertTrue(
            "ExifTool findet nach der Bereinigung noch einen Standort:\n${leaks.joinToString("\n")}",
            leaks.isEmpty(),
        )
    }

    private companion object {
        val GPS_TAG = Regex("(?i)gps|location|city|country")
    }
}
