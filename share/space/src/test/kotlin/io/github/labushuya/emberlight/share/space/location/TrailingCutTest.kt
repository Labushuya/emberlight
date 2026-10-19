package io.github.labushuya.emberlight.share.space.location

import io.github.labushuya.emberlight.share.space.ContentInspector
import io.github.labushuya.emberlight.share.space.DetectedFormat
import io.github.labushuya.emberlight.share.space.MediaKind
import io.github.labushuya.emberlight.share.space.location.HeifFixtures.Item
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.app1Exif
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.ascii
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.be32
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.box
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.exifTiff
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.jpeg
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.motionMp4
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.mp4WithLocation
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import kotlin.math.min

/**
 * Anhänge hinter dem logischen Dateiende (ADR 0015, Abschnitt „Anhänge nach dem Dateiende“). Unbekannte Anhänge werden
 * abgeschnitten. Bekannte eingebettete Teile (MPF-Zusatzbild, Motion-Photo-MP4) bleiben und werden bereinigt. Anhänge
 * innerhalb einer Struktur bleiben `trailing_data`, weil sie sich nicht abschneiden lassen.
 *
 * Alle Fälle sind synthetisch. Die Anhänge sind deterministische Zufallsbytes oder Teile aus dem Testcode.
 */
class TrailingCutTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun scrub(
        bytes: ByteArray,
        mime: String,
        kind: MediaKind = MediaKind.IMAGE,
    ): Pair<LocationScrubResult, ByteArray> {
        val file = folder.newFile()
        file.writeBytes(bytes)
        val format = DetectedFormat(mime, kind, boxed = mime.startsWith("video/") || mime.startsWith("image/hei"))
        val result = LocationScrubber.scrub(file, format, sha256Hex(bytes))
        return result to file.readBytes()
    }

    /** Erwartet: bereinigt, gekürzt auf [expectedLength], ohne Standort im Ergebnis. */
    private fun assertCutTo(
        original: ByteArray,
        mime: String,
        expectedLength: Int,
        kind: MediaKind = MediaKind.IMAGE,
    ): ByteArray {
        val (result, out) = scrub(original, mime, kind)
        assertTrue("Erwartet Clean, war $result", result is LocationScrubResult.Clean)
        assertTrue("changed", (result as LocationScrubResult.Clean).changed)
        assertEquals("Länge nach dem Abschneiden", expectedLength, out.size)
        assertEquals("Hash über die gekürzten Bytes", sha256Hex(out), result.sha256)
        assertNoLocationLeft(out)
        return out
    }

    private fun assertNoLocationLeft(bytes: ByteArray) {
        val text = String(bytes, Charsets.ISO_8859_1)
        assertFalse("Standort-Text im Ergebnis", LocationText.containsIn(text))
        assertFalse("Ortsname im Ergebnis", text.contains("Berlinstadt"))
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Deterministische Zufallsbytes, die keine Struktur bilden. */
    private fun noise(
        count: Int,
        seed: Long,
    ): ByteArray = ByteArray(count).also { java.util.Random(seed).nextBytes(it) }

    private fun cleanJpeg(): ByteArray = jpeg(app1Exif(exifTiff().bytes))

    // ---------- JPEG ----------

    @Test
    fun jpegWithRandomAttachmentIsCutToTheEndOfTheImage() {
        val clean = cleanJpeg()
        val original = clean + noise(506_193, seed = 1)

        assertCutTo(original, "image/jpeg", clean.size)
    }

    @Test
    fun jpegWithAttachedCompleteGpsJpegIsCut() {
        val clean = cleanJpeg()
        val original = clean + jpeg(app1Exif(exifTiff().bytes))

        assertCutTo(original, "image/jpeg", clean.size)
    }

    @Test
    fun jpegWithMpfSecondaryAndAttachmentKeepsTheSecondaryAndCutsTheRest() {
        val intact = LocationFixtures.jpegWithAllLocations()
        val withAttachment = intact + noise(4_096, seed = 2)

        val out = assertCutTo(withAttachment, "image/jpeg", intact.size)

        // Der MPF-Teil liegt vor dem Anhang und bleibt vollständig erhalten: Das Zusatzbild endet mit seinem EOI.
        assertEquals(intact.size, out.size)
        assertTrue(LocationFixtures.contains(out, "MPF\u0000"))
    }

    @Test
    fun googleMotionPhotoKeepsTheVideoAndCutsTheSefTrailer() {
        val clean = cleanJpeg()
        val video = motionMp4()
        val trailer = ascii("SEFH") + noise(96, seed = 3) + ascii("SEFT")
        val original = clean + video + trailer

        val out = assertCutTo(original, "image/jpeg", clean.size + video.size)

        assertEquals("ftyp", String(out, clean.size + 4, 4, Charsets.ISO_8859_1))
    }

    @Test
    fun samsungMotionHeaderKeepsTheVideoAndCutsTheTrailer() {
        val clean = cleanJpeg()
        val header = ByteArray(8) + ascii("MotionPhoto_Data")
        val video = motionMp4()
        val original = clean + header + video + ascii("SEFH") + noise(64, seed = 4) + ascii("SEFT")

        val out = assertCutTo(original, "image/jpeg", clean.size + header.size + video.size)

        assertEquals("MotionPhoto_Data", String(out, clean.size + 8, 16, Charsets.ISO_8859_1))
        assertEquals("ftyp", String(out, clean.size + header.size + 4, 4, Charsets.ISO_8859_1))
    }

    /**
     * Eine Lücke zwischen zwei bekannten Teilen (hier vor dem MPF-Zusatzbild) liegt mitten in der Datei. Sie lässt sich
     * nicht abschneiden, also bleibt `trailing_data`. Das Zusatzbild dahinter darf nicht verloren gehen.
     */
    @Test
    fun junkBetweenTheMainImageAndTheMpfSecondaryStaysTrailingData() {
        val thumbnail = jpeg(app1Exif(exifTiff().bytes))
        val tiff = exifTiff(thumbnail).bytes
        val secondary = jpeg(app1Exif(exifTiff().bytes))

        fun primary(
            offset: Long,
            primarySize: Long,
        ): ByteArray = jpeg(app1Exif(tiff), LocationFixtures.app2Mpf(primarySize, secondary.size.toLong(), offset))
        val draft = primary(0, 0)
        val mpfStart = draft.indexOfBytes(ascii("MPF\u0000")) + 4
        val junk = noise(16, seed = 5)
        val secondaryStart = draft.size + junk.size
        val first = primary(secondaryStart - mpfStart.toLong(), draft.size.toLong())
        val original = first + junk + secondary

        val (result, _) = scrub(original, "image/jpeg")

        assertEquals(LocationScrubResult.NotRemovable(NotRemovableReason.TRAILING_DATA), result)
    }

    private fun ByteArray.indexOfBytes(needle: ByteArray): Int {
        for (start in 0..size - needle.size) {
            if (needle.indices.all { this[start + it] == needle[it] }) return start
        }
        error("Muster nicht gefunden.")
    }

    // ---------- MP4 ----------

    @Test
    fun mp4WithAttachmentAfterTheLastBoxIsCutAndCleaned() {
        val clean = mp4WithLocation()
        val original = clean + noise(201, seed = 6)

        val out = assertCutTo(original, "video/mp4", clean.size, MediaKind.VIDEO)

        // Nach dem Abschneiden ist die Box-Kette lückenlos bis zum Dateiende.
        val chainEnd =
            ContentInspector.validBoxEnd(out.size.toLong()) { offset, length ->
                out.copyOfRange(offset.toInt(), min(out.size, offset.toInt() + length))
            }
        assertEquals(out.size.toLong(), chainEnd)
    }

    @Test
    fun mp4WithBrokenBoxInsideTheChainIsNotRemovableAndUnchanged() {
        val original =
            box("ftyp", ascii("isom") + ByteArray(4)) +
                box("moov", box("free", ByteArray(8))) +
                be32(1_000_000L) + ascii("mdat") + ByteArray(16) + ByteArray(64)

        val (result, out) = scrub(original, "video/mp4", MediaKind.VIDEO)

        assertEquals(LocationScrubResult.NotRemovable(NotRemovableReason.STRUCTURE), result)
        assertArrayEquals(original, out)
    }

    // ---------- Weitere Formate ----------

    @Test
    fun webpDataAfterTheRiffSizeIsCut() {
        val clean = LocationFixtures.webp(LocationFixtures.webpChunk("EXIF", exifTiff().bytes))
        val original = clean + noise(100, seed = 7)

        assertCutTo(original, "image/webp", clean.size)
    }

    @Test
    fun gifDataAfterTheTrailerIsCut() {
        val clean = LocationFixtures.gifWithXmp(LocationFixtures.XMP_WITH_LOCATION)
        val original = clean + noise(64, seed = 8)

        assertCutTo(original, "image/gif", clean.size)
    }

    @Test
    fun heifDataAfterTheLastBoxIsCut() {
        val clean = HeifFixtures.standard("heic", listOf(Item(1, "Exif", HeifFixtures.exifItem(exifTiff().bytes))))
        val original = clean + noise(64, seed = 9)

        assertCutTo(original, "image/heic", clean.size, MediaKind.IMAGE)
    }

    // ---------- Determinismus und Dedupe ----------

    /** Gleiche Datei mit verschiedenen Anhängen ergibt denselben Inhalt und damit denselben Hash (Dedupe). */
    @Test
    fun differentAttachmentsOfTheSameFileGiveTheSameBytesAndHash() {
        val clean = cleanJpeg()

        val (firstResult, firstOut) = scrub(clean + noise(300, seed = 10), "image/jpeg")
        val (secondResult, secondOut) = scrub(clean + noise(700, seed = 11), "image/jpeg")

        assertArrayEquals(firstOut, secondOut)
        assertEquals(
            (firstResult as LocationScrubResult.Clean).sha256,
            (secondResult as LocationScrubResult.Clean).sha256,
        )
    }
}
