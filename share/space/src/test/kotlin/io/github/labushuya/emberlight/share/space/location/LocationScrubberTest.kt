package io.github.labushuya.emberlight.share.space.location

import androidx.exifinterface.media.ExifInterface
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.GpsDirectory
import io.github.labushuya.emberlight.share.space.DetectedFormat
import io.github.labushuya.emberlight.share.space.MediaKind
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.XMP_WITH_LOCATION
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.app13Iptc
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.app1Exif
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.app1ExtendedXmp
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.app1Xmp
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.box
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.changedIndices
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.concat
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.exifTiff
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.freeWithGps
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.gifWithXmp
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.gpmdTrack
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.iim
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.itxtXmp
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.jpeg
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.jpegWithAllLocations
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.mebxTrack
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.motionMp4
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.mp4WithLocation
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.png
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.pngChunk
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.rawProfileText
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.webp
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.webpChunk
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.ztxtDeflated
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.Random

/**
 * Standortentfernung je Format (ADR 0015). Jeder Fall prüft: Länge gleich, Standort weg (eigener Detektor, Byte-Scan,
 * ExifInterface, metadata-extractor), Änderungen nur an erlaubten Stellen, deterministisch.
 */
@RunWith(RobolectricTestRunner::class)
class LocationScrubberTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun scrub(
        bytes: ByteArray,
        mime: String,
        kind: MediaKind = MediaKind.IMAGE,
        boxed: Boolean = mime.startsWith("video/"),
    ): Pair<LocationScrubResult, ByteArray> {
        val file = folder.newFile()
        file.writeBytes(bytes)
        // Der Hash des Stroms entspricht dem Upload: Er gilt, solange die Datei unverändert bleibt.
        val result = LocationScrubber.scrub(file, DetectedFormat(mime, kind, boxed), sha256Hex(bytes))
        return result to file.readBytes()
    }

    private fun assertClean(
        bytes: ByteArray,
        mime: String,
    ): ByteArray {
        val (result, out) = scrub(bytes, mime)
        assertCleanResult(result, changed = true)
        assertEquals("Länge darf sich nicht ändern", bytes.size, out.size)
        assertNoLocationLeft(out)
        assertEquals("Hash über die verarbeiteten Bytes", sha256Hex(out), (result as LocationScrubResult.Clean).sha256)
        return out
    }

    private fun assertCleanResult(
        result: LocationScrubResult,
        changed: Boolean,
    ) {
        assertTrue("Erwartet Clean, war $result", result is LocationScrubResult.Clean)
        assertEquals("changed", changed, (result as LocationScrubResult.Clean).changed)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun assertNoLocationLeft(bytes: ByteArray) {
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue("Standort-Text im Ergebnis", !LocationText.containsIn(text))
        assertTrue("Ortsname im Ergebnis", !text.contains("Berlinstadt"))
    }

    /** Gegenprobe mit ExifInterface und metadata-extractor, unabhängig vom eigenen Code (ADR 0015, Tests). */
    private fun assertNoGpsForExternalReaders(bytes: ByteArray) {
        assertNull("ExifInterface meldet einen Standort", ExifInterface(ByteArrayInputStream(bytes)).latLong)
        val metadata = ImageMetadataReader.readMetadata(ByteArrayInputStream(bytes))
        val gps = metadata.getDirectoriesOfType(GpsDirectory::class.java)
        assertTrue("metadata-extractor meldet einen Standort", gps.all { it.geoLocation == null })
    }

    @Test
    fun jpegWithAllLocationsIsCleanedAndKeepsItsLength() {
        val original = jpegWithAllLocations()

        val cleaned = assertClean(original, "image/jpeg")

        assertNoGpsForExternalReaders(cleaned)
        assertTrue(!String(cleaned, Charsets.ISO_8859_1).contains("HERSTELLER-DATEN1"))
        assertTrue(!String(cleaned, Charsets.ISO_8859_1).contains("Deutschland"))
        assertTrue(!String(cleaned, Charsets.ISO_8859_1).contains("+48.8566"))
        // Nicht-Standort-Felder bleiben erhalten.
        assertTrue(String(cleaned, Charsets.ISO_8859_1).contains("Urlaubsfoto"))
        assertTrue(String(cleaned, Charsets.ISO_8859_1).contains("Schlagwort"))
    }

    @Test
    fun scrubbingIsDeterministic() {
        val original = jpegWithAllLocations()

        val first = scrub(original, "image/jpeg").second
        val second = scrub(original, "image/jpeg").second

        assertArrayEquals(first, second)
    }

    @Test
    fun imagesThatDifferOnlyInTheLocationShareTheSameBytesAfterScrubbing() {
        val tiff = exifTiff().bytes
        val withNorth = jpeg(app1Exif(tiff), app1Xmp(XMP_WITH_LOCATION))
        val withSouth = jpeg(app1Exif(tiff), app1Xmp(XMP_WITH_LOCATION.replace("48,51.3N", "10,10.1S")))

        val a = assertClean(withNorth, "image/jpeg")
        val b = assertClean(withSouth, "image/jpeg")

        assertArrayEquals(a, b)
    }

    @Test
    fun exifChangesStayInsideTheGpsAndMakerNoteZones() {
        val synth = exifTiff()
        val original = jpeg(app1Exif(synth.bytes))
        val tiffStart = 2 + 4 + 6

        val cleaned = assertClean(original, "image/jpeg")

        val allowed =
            synth.zones
                .flatMap { it.toList() }
                .map { it + tiffStart }
                .toSet()
        val outside = changedIndices(original, cleaned).filterNot { it in allowed }
        assertTrue("Änderung außerhalb der Zonen: $outside", outside.isEmpty())
        assertNoGpsForExternalReaders(cleaned)
    }

    @Test
    fun extendedXmpWithLocationIsNotRemovable() {
        val original = jpeg(app1ExtendedXmp(XMP_WITH_LOCATION))

        val (result, out) = scrub(original, "image/jpeg")

        assertEquals(LocationScrubResult.NotRemovable(NotRemovableReason.EXTENDED_XMP), result)
        assertArrayEquals(original, out)
    }

    @Test
    fun extendedXmpWithoutLocationStaysClean() {
        val original = jpeg(app1ExtendedXmp("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>"))

        val (result, out) = scrub(original, "image/jpeg")

        assertCleanResult(result, changed = false)
        assertArrayEquals(original, out)
    }

    /** Anhängsel nach dem EOI wird abgeschnitten, nicht abgelehnt (ADR 0015, Anhänge). Der Standort ist weg. */
    @Test
    fun junkAfterTheJpegEndIsCutAndTheLocationIsGone() {
        val clean = jpeg(app1Exif(exifTiff().bytes))
        val original = clean + "<html>junk</html>".toByteArray()

        val (result, out) = scrub(original, "image/jpeg")

        assertCleanResult(result, changed = true)
        assertEquals("Länge = Hauptbild bis EOI", clean.size, out.size)
        assertNoLocationLeft(out)
    }

    @Test
    fun motionPhotoMp4AfterTheJpegIsCleaned() {
        val original = jpeg(app1Exif(exifTiff().bytes)) + motionMp4()

        assertClean(original, "image/jpeg")
    }

    @Test
    fun jpegWithoutEndMarkerIsStructuralRefusal() {
        val original = jpeg(app1Exif(exifTiff().bytes)).copyOf(40)

        val (result, _) = scrub(original, "image/jpeg")

        assertEquals(LocationScrubResult.NotRemovable(NotRemovableReason.STRUCTURE), result)
    }

    @Test
    fun brokenXmlWithLocationIsStructuralRefusal() {
        val broken = XMP_WITH_LOCATION.replace("</rdf:RDF>", "")
        val original = jpeg(app1Xmp(broken))

        val (result, _) = scrub(original, "image/jpeg")

        assertEquals(LocationScrubResult.NotRemovable(NotRemovableReason.STRUCTURE), result)
    }

    @Test
    fun pngWithExifRawProfileXmpAndCrcIsCleanedWithValidChunks() {
        val tiff = exifTiff().bytes
        val original =
            png(
                pngChunk("eXIf", tiff),
                pngChunk("tEXt", rawProfileText(tiff)),
                pngChunk("iTXt", itxtXmp(XMP_WITH_LOCATION)),
            )

        val cleaned = assertClean(original, "image/png")

        assertNoGpsForExternalReaders(cleaned)
        assertChunkCrcsValid(cleaned)
    }

    @Test
    fun compressedTextWithLocationIsNeutralized() {
        // Nachtrag 3.6d: Komprimierter Text wird immer neutralisiert, statt die Datei abzulehnen.
        val original = png(pngChunk("zTXt", ztxtDeflated("Comment", "Ort +48.8566+002.3522/")))

        val cleaned = assertClean(original, "image/png")

        assertEquals(original.size, cleaned.size)
        assertFalse("zTXt ist nicht mehr vorhanden", String(cleaned, Charsets.ISO_8859_1).contains("zTXt"))
        assertChunkCrcsValid(cleaned)
    }

    @Test
    fun compressedHarmlessTextIsAlsoNeutralized() {
        val original = png(pngChunk("zTXt", ztxtDeflated("Comment", "Ein Kommentar ohne Ort")))

        val cleaned = assertClean(original, "image/png")

        assertFalse(String(cleaned, Charsets.ISO_8859_1).contains("zTXt"))
        assertChunkCrcsValid(cleaned)
    }

    @Test
    fun webpWithPrefixedAndPlainExifAndXmpIsCleaned() {
        val tiff = exifTiff().bytes
        val original =
            webp(
                webpChunk("EXIF", ascii("Exif\u0000\u0000") + tiff),
                webpChunk("EXIF", tiff),
                webpChunk("XMP ", ascii(XMP_WITH_LOCATION)),
            )

        val cleaned = assertClean(original, "image/webp")

        assertNoGpsForExternalReaders(cleaned)
    }

    @Test
    fun gifXmpIsBlankedWithSpacesOnlyAndTrailerStaysUntouched() {
        val original = gifWithXmp(XMP_WITH_LOCATION)

        val cleaned = assertClean(original, "image/gif")

        val changed = changedIndices(original, cleaned)
        assertTrue("Es wurde nichts geändert", changed.isNotEmpty())
        assertTrue("Nur 0x20 darf entstehen", changed.all { cleaned[it] == 0x20.toByte() })
        assertEquals(0x3B.toByte(), cleaned.last())
    }

    @Test
    fun mp4WithAllLocationPlacesIsCleanedAndKeepsItsLength() {
        val original = mp4WithLocation()

        val cleaned = assertClean(original, "video/mp4")

        val text = String(cleaned, Charsets.ISO_8859_1)
        assertTrue(!text.contains("Mond"))
        assertTrue(!text.contains("Trak-Ort"))
        assertTrue(!text.contains("Am Platz"))
    }

    /**
     * Standort-Boxen unter `udta` (`©xyz`, `loci`) werden in `free` umbenannt (ADR 0015, „Kein Null Island“). So bleibt
     * kein Standort-Typ übrig, den ein Leser mit leerem Wert als Position deuten könnte.
     */
    @Test
    fun udtaLocationBoxesBecomeFreeBoxesOfTheSameLength() {
        val original = mp4WithLocation()
        assertTrue(LocationFixtures.contains(original, "©xyz"))
        assertTrue(LocationFixtures.contains(original, "loci"))

        val cleaned = assertClean(original, "video/mp4")

        assertFalse(LocationFixtures.contains(cleaned, "©xyz"))
        assertFalse(LocationFixtures.contains(cleaned, "loci"))
        assertTrue(LocationFixtures.contains(cleaned, "free"))
    }

    @Test
    fun appleLocationKeyTextIsRemovedWhileKeysAndIndexesStay() {
        val original = box("ftyp", ascii("isom") + ByteArray(4)) + box("moov", LocationFixtures.appleLocationMeta())

        val cleaned = assertClean(original, "video/quicktime")

        assertTrue(String(cleaned, Charsets.ISO_8859_1).contains("com.apple.quicktime.location.ISO6709"))
    }

    /** Ein Standort-Wert unter einem Key ohne Apple-Präfix (z. B. `location`) wird ebenfalls überschrieben. */
    @Test
    fun locationValueUnderAnotherKeyNameIsBlankedToo() {
        val original =
            box("ftyp", ascii("isom") + ByteArray(4)) +
                box("moov", LocationFixtures.metaWithLocationKey("location", "+48.8566+002.3522/"))

        assertClean(original, "video/quicktime")
    }

    @Test
    fun metadataTrackWithGpmdIsNotRemovable() {
        val original = box("ftyp", ascii("isom") + ByteArray(4)) + box("moov", gpmdTrack())

        val (result, _) = scrub(original, "video/mp4")

        assertEquals(LocationScrubResult.NotRemovable(NotRemovableReason.METADATA_TRACK), result)
    }

    @Test
    fun mebxTrackWithLocationKeyIsNotRemovable() {
        val original =
            box("ftyp", ascii("isom") + ByteArray(4)) + box("moov", mebxTrack("com.apple.quicktime.location.ISO6709"))

        val (result, _) = scrub(original, "video/mp4")

        assertEquals(LocationScrubResult.NotRemovable(NotRemovableReason.METADATA_TRACK), result)
    }

    @Test
    fun mebxTrackWithoutSampleTablesIsRejectedEvenWithoutLocationKey() {
        // Nachtrag 3.6d: Jede Datenspur wird geleert. Ohne lesbare Sample-Tabellen gilt sie als nicht bereinigbar.
        val original =
            box("ftyp", ascii("isom") + ByteArray(4)) + box("moov", mebxTrack("com.apple.quicktime.camera.exposure"))

        val (result, _) = scrub(original, "video/mp4")

        assertEquals(LocationScrubResult.NotRemovable(NotRemovableReason.METADATA_TRACK), result)
    }

    @Test
    fun freeBoxWithGpsIsNotRemovable() {
        val original =
            box("ftyp", ascii("isom") + ByteArray(4)) + box("moov", box("mvhd", ByteArray(8))) + freeWithGps()

        val (result, _) = scrub(original, "video/mp4")

        assertEquals(LocationScrubResult.NotRemovable(NotRemovableReason.FREE_BOX_LOCATION), result)
    }

    /** Robustheit: abgeschnittene und zufällig veränderte Eingaben dürfen nie Standort durchlassen oder abstürzen. */
    @Test
    fun truncatedAndMutatedInputsNeverLeakLocationAndNeverThrow() {
        val samples =
            listOf(
                "image/jpeg" to jpegWithAllLocations(),
                "image/png" to png(pngChunk("eXIf", exifTiff().bytes), pngChunk("iTXt", itxtXmp(XMP_WITH_LOCATION))),
                "image/webp" to webp(webpChunk("EXIF", exifTiff().bytes), webpChunk("XMP ", ascii(XMP_WITH_LOCATION))),
                "image/gif" to gifWithXmp(XMP_WITH_LOCATION),
                "video/mp4" to mp4WithLocation(),
            )
        val random = Random(20261019L)
        for ((mime, sample) in samples) {
            for (cut in 0 until sample.size step maxOf(1, sample.size / 40)) {
                assertNoLeak(mime, sample.copyOf(cut))
            }
            repeat(60) {
                val mutated = sample.copyOf()
                repeat(1 + random.nextInt(4)) {
                    mutated[random.nextInt(mutated.size)] = random.nextInt(256).toByte()
                }
                assertNoLeak(mime, mutated)
            }
        }
    }

    /** Invariante: Entweder abgelehnt, oder es bleibt kein Standort. Ausnahmen aus dem Walker sind Fehler. */
    private fun assertNoLeak(
        mime: String,
        bytes: ByteArray,
    ) {
        val (result, out) =
            try {
                scrub(bytes, mime)
            } catch (failure: Exception) {
                fail("Ausnahme aus dem Scrubber für $mime: $failure")
                return
            }
        if (result is LocationScrubResult.Clean) {
            assertTrue("Standort nach Bereinigung ($mime)", !LocationText.containsIn(String(out, Charsets.ISO_8859_1)))
        }
    }

    @Test
    fun selfReferencingIfdIsRefusedAsStructure() {
        val tiff = exifTiff().bytes.copyOf()
        // IFD0 zeigt auf sich selbst als nächsten IFD: 8 ist der Anfang von IFD0, nach 2 + 24 Bytes steht der Next-Zeiger.
        val nextPointer = 8 + 2 + 2 * 12
        tiff[nextPointer] = 8
        tiff[nextPointer + 1] = 0
        tiff[nextPointer + 2] = 0
        tiff[nextPointer + 3] = 0
        val original = jpeg(app1Exif(tiff))

        val (result, _) = scrub(original, "image/jpeg")

        assertEquals(LocationScrubResult.NotRemovable(NotRemovableReason.STRUCTURE), result)
    }

    @Test
    fun hugeIfdEntryCountIsRefusedWithoutAllocatingItsSize() {
        val tiff = exifTiff().bytes.copyOf()
        tiff[8] = 0xFF.toByte()
        tiff[9] = 0xFF.toByte()
        val original = jpeg(app1Exif(tiff))

        val (result, _) = scrub(original, "image/jpeg")

        assertEquals(LocationScrubResult.NotRemovable(NotRemovableReason.STRUCTURE), result)
    }

    @Test
    fun deeplyNestedBoxesAreRefusedAsStructure() {
        var inner: ByteArray = box("free", ByteArray(8))
        repeat(12) { inner = box("udta", inner) }
        val original = box("ftyp", ascii("isom") + ByteArray(4)) + box("moov", inner)

        val (result, _) = scrub(original, "video/mp4")

        assertEquals(LocationScrubResult.NotRemovable(NotRemovableReason.STRUCTURE), result)
    }

    /** Anhängsel nach IEND wird abgeschnitten, das PNG bleibt bis IEND unverändert (ADR 0015, Anhänge). */
    @Test
    fun pngWithAppendedDataAfterIendIsCut() {
        val clean = png(pngChunk("tEXt", ascii("Comment\u0000ok")))
        val original = clean + ascii("<script>")

        val (result, out) = scrub(original, "image/png")

        assertCleanResult(result, changed = true)
        assertArrayEquals(clean, out)
    }

    @Test
    fun iptcPaddingWithZerosIsAccepted() {
        val original = jpeg(app13Iptc(iim(2, 90, "Berlinstadt"), ByteArray(4)))

        assertClean(original, "image/jpeg")
    }

    /** Jeder Chunk prüft seine CRC nach dem Bereinigen neu (ADR 0015, PNG). */
    private fun assertChunkCrcsValid(bytes: ByteArray) {
        var position = 8
        while (position + 12 <= bytes.size) {
            val length =
                ((bytes[position].toInt() and 0xFF) shl 24) or ((bytes[position + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[position + 2].toInt() and 0xFF) shl 8) or (bytes[position + 3].toInt() and 0xFF)
            val typeAndData = bytes.copyOfRange(position + 4, position + 8 + length)
            val crc =
                java.util.zip
                    .CRC32()
                    .apply { update(typeAndData) }
                    .value
            val stored =
                ((bytes[position + 8 + length].toLong() and 0xFF) shl 24) or
                    ((bytes[position + 9 + length].toLong() and 0xFF) shl 16) or
                    ((bytes[position + 10 + length].toLong() and 0xFF) shl 8) or
                    (bytes[position + 11 + length].toLong() and 0xFF)
            assertEquals("CRC des Chunks bei $position", crc, stored)
            position += 12 + length
        }
    }

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)
}
