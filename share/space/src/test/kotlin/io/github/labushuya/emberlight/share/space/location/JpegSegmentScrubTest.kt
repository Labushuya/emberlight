package io.github.labushuya.emberlight.share.space.location

import androidx.exifinterface.media.ExifInterface
import io.github.labushuya.emberlight.share.space.DetectedFormat
import io.github.labushuya.emberlight.share.space.MediaKind
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.ascii
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.concat
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.contains
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.decodableJpeg
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.exifTiff
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.honorStyleJpeg
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.irb
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.segment
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.withSegmentsAfterSoi
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import javax.imageio.ImageIO

/**
 * JPEG-Segmente außerhalb der bekannten Marker (ADR 0015, Nachtrag): Exif in beliebigem APPn (Honor: APP10),
 * herstellerspezifische APPn, COM und Photoshop-Ressourcen. Jeder Fall prüft: Länge gleich, Standort weg (strenge
 * Gegenprobe, ExifInterface, Textsuche), Bild weiterhin dekodierbar.
 */
@RunWith(RobolectricTestRunner::class)
class JpegSegmentScrubTest {
    private val locationTextForTests = "+48.8566+002.3522+035.000/"

    @get:Rule
    val folder = TemporaryFolder()

    private fun scrub(bytes: ByteArray): Pair<LocationScrubResult, ByteArray> {
        val file = folder.newFile()
        file.writeBytes(bytes)
        val result =
            LocationScrubber.scrub(
                file,
                DetectedFormat("image/jpeg", MediaKind.IMAGE, boxed = false),
                sha256Hex(bytes),
            )
        return result to file.readBytes()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun assertDecodable(bytes: ByteArray) {
        val image = ImageIO.read(ByteArrayInputStream(bytes))
        assertNotNull("ImageIO muss das bereinigte Bild dekodieren", image)
        assertEquals(16, image.width)
        assertEquals(16, image.height)
    }

    private fun assertNoLocationExternally(bytes: ByteArray) {
        assertNull("ExifInterface meldet einen Standort", ExifInterface(ByteArrayInputStream(bytes)).latLong)
        val strict = StrictLocationProbe.findings(MemoryStore(bytes), scanTiff = true)
        assertTrue("strenge Gegenprobe meldet: $strict", strict.isEmpty())
    }

    @Test
    fun honorLayoutLosesEveryLocationInEveryBlockAndStaysDecodable() {
        val original = honorStyleJpeg()
        // Vorher: die strenge Gegenprobe erkennt den GPS-Block in APP10 (und den Standorttext).
        val before = StrictLocationProbe.findings(MemoryStore(original), scanTiff = true)
        assertTrue("Vorher muss ein Standort sichtbar sein: $before", before.contains("gps_ifd_nonzero"))

        val (result, out) = scrub(original)

        assertTrue("Erwartet Clean, war $result", result is LocationScrubResult.Clean)
        assertTrue((result as LocationScrubResult.Clean).changed)
        assertEquals("Länge bleibt gleich", original.size, out.size)
        assertEquals(sha256Hex(out), result.sha256)
        assertNoLocationExternally(out)
        assertFalse("Herstellerblock ist genullt", contains(out, "VER"))
        assertFalse("Standorttext in COM und APP12 ist weg", contains(out, "+48.8566"))
        assertDecodable(out)
    }

    @Test
    fun unknownAppSegmentIsBlankedAndTheRestOfTheImageIsUnchanged() {
        val text = ascii("Ortsdaten $locationTextForTests")
        val original = withSegmentsAfterSoi(decodableJpeg(), segment(0xE5, text))

        val (result, out) = scrub(original)

        assertTrue("Erwartet Clean, war $result", result is LocationScrubResult.Clean)
        assertEquals(original.size, out.size)
        assertFalse(contains(out, "+48.8566"))
        // Marker und Länge des Segments bleiben, nur der Inhalt ist 0x00.
        val marker = byteArrayOf(0xFF.toByte(), 0xE5.toByte(), 0, (text.size + 2).toByte())
        assertTrue("Segmentkopf bleibt", contains(out, String(marker, Charsets.ISO_8859_1)))
        assertDecodable(out)
    }

    @Test
    fun iccProfileInApp2IsKeptAsItIs() {
        val icc = ascii("ICC_PROFILE\u0000") + ByteArray(100) { 1 }
        val original = withSegmentsAfterSoi(decodableJpeg(), segment(0xE2, icc))

        val (_, out) = scrub(original)

        assertArrayEquals(original, out)
    }

    @Test
    fun exifInApp10WithGpsIsWalkedAndGpsIsCleared() {
        val tiff = exifTiff().bytes
        val original = withSegmentsAfterSoi(decodableJpeg(), segment(0xEA, ascii("Exif\u0000\u0000") + tiff))
        assertTrue(StrictLocationProbe.findings(MemoryStore(original), scanTiff = true).contains("gps_ifd_nonzero"))

        val (result, out) = scrub(original)

        assertTrue("Erwartet Clean, war $result", result is LocationScrubResult.Clean)
        assertEquals(original.size, out.size)
        assertNoLocationExternally(out)
        assertFalse(contains(out, "HERSTELLER-DATEN1"))
        assertDecodable(out)
    }

    @Test
    fun exifInPhotoshopResourceWithGpsIsWalkedAndUnknownResourceIsBlanked() {
        val tiff = exifTiff().bytes
        val unknownResource = irb(0x0BB7, ascii("Standort $locationTextForTests"))
        val photoshop = ascii("Photoshop 3.0\u0000") + irb(0x0422, tiff) + unknownResource
        val original = withSegmentsAfterSoi(decodableJpeg(), segment(0xED, photoshop))

        val (result, out) = scrub(original)

        assertTrue("Erwartet Clean, war $result", result is LocationScrubResult.Clean)
        assertEquals(original.size, out.size)
        assertNoLocationExternally(out)
        assertFalse(contains(out, "Standort"))
        assertDecodable(out)
    }

    @Test
    fun comSegmentWithLocationTextIsBlanked() {
        val original = withSegmentsAfterSoi(decodableJpeg(), segment(0xFE, ascii("Aufnahme $locationTextForTests")))

        val (result, out) = scrub(original)

        assertTrue("Erwartet Clean, war $result", result is LocationScrubResult.Clean)
        assertEquals(original.size, out.size)
        assertFalse(contains(out, "+48.8566"))
        assertDecodable(out)
    }

    @Test
    fun strictProbeIgnoresTiffLikeBytesWithoutGpsPointer() {
        val noise = ascii("II") + byteArrayOf(42, 0, 8, 0, 0, 0, 0, 0, 0, 0) + ByteArray(40) { 0x7F }

        assertTrue(StrictLocationProbe.findings(MemoryStore(noise), scanTiff = true).isEmpty())
    }

    @Test
    fun strictProbeFlagsGpsPointerIntoNothingAsUnreadable() {
        // IFD0 mit einem GPS-Zeiger hinter dem Dateiende: fail closed.
        val tiff =
            ascii("II") + byteArrayOf(42, 0) + byteArrayOf(8, 0, 0, 0) +
                byteArrayOf(1, 0) + byteArrayOf(0x25, 0x88.toByte(), 4, 0, 1, 0, 0, 0) + byteArrayOf(0, 0x10, 0, 0)

        assertEquals(setOf("gps_ifd_unreadable"), StrictLocationProbe.findings(MemoryStore(tiff), scanTiff = true))
    }

    @Test
    fun strictProbeFindsEveryTiffHeaderInTheStream() {
        // Ein Exif-Block mit GPS, eingebettet hinter Füllbytes: die Gegenprobe muss ihn finden, egal wo er liegt.
        val tiff = exifTiff().bytes
        val bytes = concat(ByteArray(70_000) { 0x20 }, tiff)

        assertEquals(setOf("gps_ifd_nonzero"), StrictLocationProbe.findings(MemoryStore(bytes), scanTiff = true))
    }
}
