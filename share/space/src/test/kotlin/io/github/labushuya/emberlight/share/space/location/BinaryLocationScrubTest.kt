package io.github.labushuya.emberlight.share.space.location

import io.github.labushuya.emberlight.share.space.DetectedFormat
import io.github.labushuya.emberlight.share.space.MediaKind
import io.github.labushuya.emberlight.share.space.location.BinaryLocationFixtures.cammSample
import io.github.labushuya.emberlight.share.space.location.BinaryLocationFixtures.djmdSample
import io.github.labushuya.emberlight.share.space.location.BinaryLocationFixtures.gpmfSample
import io.github.labushuya.emberlight.share.space.location.BinaryLocationFixtures.gpsTiff
import io.github.labushuya.emberlight.share.space.location.BinaryLocationFixtures.idatWithTiffHeader
import io.github.labushuya.emberlight.share.space.location.BinaryLocationFixtures.itxtChunk
import io.github.labushuya.emberlight.share.space.location.BinaryLocationFixtures.profileBody
import io.github.labushuya.emberlight.share.space.location.BinaryLocationFixtures.randomWithTiffHeader
import io.github.labushuya.emberlight.share.space.location.BinaryLocationFixtures.textChunk
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.ascii
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.box
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.concat
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.png
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.pngChunk
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.ztxtDeflated
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import javax.imageio.ImageIO

/**
 * Restrisiken aus ADR 0015 (Nachtrag 3.1b-2, Nachtrag 3.6d): MP4-Datenspuren, unbekannte Boxen und Herstellerdaten,
 * unbekannte HEIF-Items, PNG-Text und Chunks. Seit Nachtrag 3.6d gilt: Ohne Einwilligung werden unbekannte und nicht
 * benötigte Metadaten immer neutralisiert, auch ohne Standortbefund. Jeder Fall prüft: Ergebnis, Länge, Gültigkeit
 * (CRC bzw. Struktur), Wiederholung ohne Änderung. Bild-, Video- und Audio-Daten bleiben unverändert.
 */
class BinaryLocationScrubTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val mp4Format = DetectedFormat("video/mp4", MediaKind.VIDEO, boxed = true)
    private val heicFormat = DetectedFormat("image/heic", MediaKind.IMAGE, boxed = true)
    private val pngFormat = DetectedFormat("image/png", MediaKind.IMAGE, boxed = false)

    private fun scrub(
        bytes: ByteArray,
        format: DetectedFormat,
    ): Pair<LocationScrubResult, ByteArray> {
        val file = folder.newFile()
        file.writeBytes(bytes)
        // Der Hash des Stroms entspricht dem Upload, wie beim Empfang.
        val result = LocationScrubber.scrub(file, format, sha256Hex(bytes))
        return result to file.readBytes()
    }

    private fun assertCleanChanged(result: LocationScrubResult) {
        assertTrue("Erwartet Clean, war $result", result is LocationScrubResult.Clean)
        assertTrue("Erwartet changed=true", (result as LocationScrubResult.Clean).changed)
    }

    private fun assertUnchanged(
        result: LocationScrubResult,
        original: ByteArray,
        out: ByteArray,
    ) {
        assertTrue("Erwartet Clean ohne Änderung, war $result", result is LocationScrubResult.Clean)
        assertFalse("Erwartet changed=false", (result as LocationScrubResult.Clean).changed)
        assertArrayEquals(original, out)
    }

    /** Die bereinigte Datei hat keinen Befund mehr und bleibt bei einem zweiten Lauf unverändert. */
    private fun assertRepeatIsClean(
        out: ByteArray,
        format: DetectedFormat,
    ) {
        val (result, again) = scrub(out, format)
        assertUnchanged(result, out, again)
    }

    /** Die Bytes unterscheiden sich von [original] nur innerhalb von [allowed], und die Länge bleibt gleich. */
    private fun assertChangesWithin(
        original: ByteArray,
        out: ByteArray,
        allowed: List<IntRange>,
    ) {
        assertEquals("Länge darf sich nicht ändern", original.size, out.size)
        for (index in original.indices) {
            if (original[index] != out[index]) {
                assertTrue("Änderung außerhalb der Sample-Bereiche bei $index", allowed.any { index in it })
            }
        }
    }

    /** Alle Samples in [ranges] sind auf null geleert. */
    private fun assertZeroed(
        out: ByteArray,
        ranges: List<IntRange>,
    ) {
        for (range in ranges) assertTrue("Sample $range ist nicht geleert", range.all { out[it] == 0.toByte() })
    }

    private fun binaryLocation(bytes: ByteArray): Boolean =
        StrictLocationProbe.containsBinaryLocation(MemoryStore(bytes))

    /** Jede Chunk-CRC in einer PNG-Datei muss zu Typ und Daten passen. */
    private fun assertPngCrcsValid(bytes: ByteArray) {
        var at = 8
        while (at + 12 <= bytes.size) {
            val length = u32(bytes, at).toInt()
            val type = bytes.copyOfRange(at + 4, at + 8)
            val data = bytes.copyOfRange(at + 8, at + 8 + length)
            val stored = u32(bytes, at + 8 + length)
            val crc = CRC32().apply { update(type) }.apply { update(data) }.value
            assertEquals("CRC von ${String(type, Charsets.ISO_8859_1)}", crc, stored)
            at += 12 + length
            if (String(type, Charsets.ISO_8859_1) == "IEND") break
        }
    }

    private fun u32(
        bytes: ByteArray,
        at: Int,
    ): Long = (0 until 4).fold(0L) { acc, i -> (acc shl 8) or (bytes[at + i].toLong() and 0xFF) }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun text(bytes: ByteArray): String = String(bytes, Charsets.ISO_8859_1)

    /** Position von [needle] in [haystack], oder -1. */
    private fun indexOf(
        haystack: ByteArray,
        needle: ByteArray,
    ): Int {
        for (start in 0..haystack.size - needle.size) {
            if ((needle.indices).all { haystack[start + it] == needle[it] }) return start
        }
        return -1
    }

    // ---------- MP4: Datenspuren werden immer geleert (Nachtrag 3.6d) ----------

    @Test
    fun gpmdTrackWithGpsIsNulledInItsSamplesOnly() {
        val mp4 = BinaryLocationFixtures.mp4("meta", "gpmd", listOf(gpmfSample(true), gpmfSample(true)))
        assertTrue("Vorher: GPS5 im Sample", text(mp4.bytes).contains("GPS5"))

        val (result, out) = scrub(mp4.bytes, mp4Format)

        assertCleanChanged(result)
        assertChangesWithin(mp4.bytes, out, mp4.sampleRanges)
        assertFalse("Nachher: kein GPS5 mehr", text(out).contains("GPS5"))
        assertRepeatIsClean(out, mp4Format)
    }

    @Test
    fun gpmdTrackWithoutGpsIsNulledToo() {
        // Nachtrag 3.6d: Die Herstellerdaten einer Datenspur gehen auch ohne GPS-Befund.
        val mp4 = BinaryLocationFixtures.mp4("meta", "gpmd", listOf(gpmfSample(false)))

        val (result, out) = scrub(mp4.bytes, mp4Format)

        assertCleanChanged(result)
        assertChangesWithin(mp4.bytes, out, mp4.sampleRanges)
        assertZeroed(out, mp4.sampleRanges)
        assertRepeatIsClean(out, mp4Format)
    }

    @Test
    fun cammTrackWithGpsRecordIsNulled() {
        val mp4 = BinaryLocationFixtures.mp4("meta", "camm", listOf(cammSample(0), cammSample(5)))
        val gpsRange = mp4.sampleRanges[1]
        assertEquals("Vorher: Typ 5 im Record", 5.toByte(), mp4.bytes[gpsRange.first + 2])

        val (result, out) = scrub(mp4.bytes, mp4Format)

        assertCleanChanged(result)
        assertChangesWithin(mp4.bytes, out, mp4.sampleRanges)
        assertTrue("Nachher: GPS-Sample geleert", gpsRange.all { out[it] == 0.toByte() })
        assertRepeatIsClean(out, mp4Format)
    }

    @Test
    fun cammTrackWithoutGpsRecordIsNulledToo() {
        val mp4 = BinaryLocationFixtures.mp4("meta", "camm", listOf(cammSample(0), cammSample(1)))

        val (result, out) = scrub(mp4.bytes, mp4Format)

        assertCleanChanged(result)
        assertZeroed(out, mp4.sampleRanges)
        assertRepeatIsClean(out, mp4Format)
    }

    @Test
    fun djmdTrackIsNulled() {
        val mp4 = BinaryLocationFixtures.mp4("meta", "djmd", listOf(djmdSample(), djmdSample()))

        val (result, out) = scrub(mp4.bytes, mp4Format)

        assertCleanChanged(result)
        assertChangesWithin(mp4.bytes, out, mp4.sampleRanges)
        assertRepeatIsClean(out, mp4Format)
    }

    @Test
    fun textTrackWithLocationTextIsNulled() {
        // Dashcams schreiben GPS auch in Text- und Untertitelspuren.
        val mp4 = BinaryLocationFixtures.mp4("text", "tx3g", listOf(ascii("+48.8566+002.3522/")))
        assertTrue("Vorher: Standort als Text im Sample", text(mp4.bytes).contains("+48.8566+002.3522"))

        val (result, out) = scrub(mp4.bytes, mp4Format)

        assertCleanChanged(result)
        assertChangesWithin(mp4.bytes, out, mp4.sampleRanges)
        assertFalse(text(out).contains("+48.8566+002.3522"))
        assertRepeatIsClean(out, mp4Format)
    }

    @Test
    fun gpmdTrackWithoutSampleTableStaysMetadataTrack() {
        val mp4 = BinaryLocationFixtures.mp4("meta", "gpmd", listOf(gpmfSample(true)), withTables = false)

        val (result, _) = scrub(mp4.bytes, mp4Format)

        assertEquals(LocationScrubResult.NotRemovable(NotRemovableReason.METADATA_TRACK), result)
    }

    @Test
    fun emptyDataTrackWithoutSamplesIsAccepted() {
        // Eine Datenspur ohne Samples hat nichts zu leeren und darf den Upload nicht ablehnen.
        val mp4 = BinaryLocationFixtures.mp4("text", "tx3g", emptyList())

        val (result, out) = scrub(mp4.bytes, mp4Format)

        assertUnchanged(result, mp4.bytes, out)
    }

    @Test
    fun fragmentedFileWithDataTrackIsNotRemovable() {
        // Samples in Fragmenten (moof) werden nicht gelesen. Eine Datenspur darin lehnt den Upload ab.
        val mp4 =
            BinaryLocationFixtures.mp4(
                "meta",
                "gpmd",
                listOf(gpmfSample(true)),
                topLevel = box("moof", ByteArray(8)),
            )

        val (result, _) = scrub(mp4.bytes, mp4Format)

        assertEquals(LocationScrubResult.NotRemovable(NotRemovableReason.METADATA_TRACK), result)
    }

    @Test
    fun videoSampleWithGpsKeyTextIsNotTouched() {
        // Gegenprobe gegen Fehlalarme: Ein Videostream mit zufällig gleichem Text darf nicht verändert werden.
        val mp4 = BinaryLocationFixtures.mp4("vide", "avc1", listOf(gpmfSample(true)))

        val (result, out) = scrub(mp4.bytes, mp4Format)

        assertUnchanged(result, mp4.bytes, out)
    }

    @Test
    fun longVideoWithManySamplesStaysClean() {
        // Regression: Ein Video mit mehr als 10 000 Samples darf nicht als Strukturfehler abgelehnt werden.
        val samples = List(12_000) { index -> ByteArray(4) { (index and 0x7F).toByte() } }
        val mp4 = BinaryLocationFixtures.mp4("vide", "avc1", samples)

        val (result, out) = scrub(mp4.bytes, mp4Format)

        assertUnchanged(result, mp4.bytes, out)
    }

    @Test
    fun dataTrackWithManySamplesInOneChunkIsNulled() {
        val samples = List(12_000) { cammSample(0) }
        val mp4 = BinaryLocationFixtures.mp4("meta", "camm", samples)

        val (result, out) = scrub(mp4.bytes, mp4Format)

        assertCleanChanged(result)
        assertZeroed(out, mp4.sampleRanges)
        assertRepeatIsClean(out, mp4Format)
    }

    @Test
    fun dataTrackWithManyChunksIsNulledWithoutQuadraticCost() {
        // Regression: Jedes Sample in einem eigenen Chunk ergibt viele getrennte Bereiche. Die Bereinigung bleibt
        // innerhalb der Grenzen und läuft ohne Aussetzer durch.
        val samples = List(12_000) { cammSample(0) }
        val mp4 = BinaryLocationFixtures.mp4("meta", "camm", samples, chunkPerSample = true)

        val (result, out) = scrub(mp4.bytes, mp4Format)

        assertCleanChanged(result)
        assertZeroed(out, mp4.sampleRanges)
        assertRepeatIsClean(out, mp4Format)
    }

    @Test
    fun udtaChildrenWithoutLocationAreNeutralizedAndKeepLength() {
        // Nachtrag 3.6d: Unbekannte Kinder von udta werden geleert, ohne Befund. Die Box wird zu `free`.
        val udta = box("udta", box("xyzw", ByteArray(24) { 7 }) + box("titl", ascii("Urlaub 2026")))
        val original = box("ftyp", ascii("isom") + ByteArray(4)) + box("moov", udta) + box("mdat", ByteArray(16))

        val (result, out) = scrub(original, mp4Format)

        assertCleanChanged(result)
        assertEquals(original.size, out.size)
        assertFalse(text(out).contains("xyzw"))
        assertFalse(text(out).contains("Urlaub"))
        assertRepeatIsClean(out, mp4Format)
    }

    @Test
    fun unknownTopLevelBoxWithBinaryGpsIsNulledAndKeepsLength() {
        val mp4 =
            BinaryLocationFixtures.mp4(
                "vide",
                "avc1",
                listOf(ByteArray(64) { 7 }),
                topLevel = box("xyzw", gpsTiff()),
            )
        assertTrue("Vorher: binärer Standort in der Box", binaryLocation(mp4.bytes))

        val (result, out) = scrub(mp4.bytes, mp4Format)

        assertCleanChanged(result)
        assertEquals(mp4.bytes.size, out.size)
        assertFalse("Nachher: kein binärer Standort", binaryLocation(out))
        assertRepeatIsClean(out, mp4Format)
    }

    @Test
    fun unknownBoxTooLargeForMemoryIsNotRemovable() {
        val huge = box("xyzw", gpsTiff() + ByteArray(ScrubLimits.MAX_READ + 1))
        val mp4 = BinaryLocationFixtures.mp4("vide", "avc1", listOf(ByteArray(64) { 7 }), topLevel = huge)

        val (result, _) = scrub(mp4.bytes, mp4Format)

        assertEquals(LocationScrubResult.NotRemovable(NotRemovableReason.LOCATION_NOT_REMOVABLE), result)
    }

    // ---------- HEIF: unbekannte Items werden immer geleert (Nachtrag 3.6d) ----------

    @Test
    fun unknownItemWithBinaryGpsIsNulledAndKeepsLength() {
        val original =
            HeifFixtures.standard(
                "heic",
                listOf(
                    HeifFixtures.Item(1, "hvc1", ByteArray(256) { (it * 7).toByte() }),
                    HeifFixtures.Item(2, "uri ", gpsTiff()),
                ),
            )
        assertTrue("Vorher: binärer Standort im Item", binaryLocation(original))

        val (result, out) = scrub(original, heicFormat)

        assertCleanChanged(result)
        assertEquals(original.size, out.size)
        assertFalse("Nachher: kein binärer Standort", binaryLocation(out))
        assertRepeatIsClean(out, heicFormat)
    }

    @Test
    fun unknownItemWithTextLocationIsNulled() {
        val original =
            HeifFixtures.standard(
                "heic",
                listOf(
                    HeifFixtures.Item(1, "hvc1", ByteArray(64) { 9 }),
                    HeifFixtures.Item(2, "mime", ascii("+48.8566+002.3522/"), contentType = "text/plain"),
                ),
            )
        assertTrue("Vorher: Standort als Text", text(original).contains("+48.8566+002.3522"))

        val (result, out) = scrub(original, heicFormat)

        assertCleanChanged(result)
        assertEquals(original.size, out.size)
        assertFalse(text(out).contains("+48.8566+002.3522"))
    }

    @Test
    fun unknownItemWithoutLocationIsNulledAndImageItemIsKept() {
        // Nachtrag 3.6d: Ein unbekanntes Item wird auch ohne Befund geleert. Das Bild-Item bleibt unverändert.
        val image = ByteArray(128) { (it * 13).toByte() }
        val unknown = ByteArray(64) { 5 }
        val original =
            HeifFixtures.standard(
                "heic",
                listOf(
                    HeifFixtures.Item(1, "hvc1", image),
                    HeifFixtures.Item(2, "uri ", unknown),
                ),
            )
        assertTrue("Vorher: Item vorhanden", indexOf(original, unknown) >= 0)

        val (result, out) = scrub(original, heicFormat)

        assertCleanChanged(result)
        assertEquals(original.size, out.size)
        assertEquals("Nachher: unbekanntes Item geleert", -1, indexOf(out, unknown))
        assertTrue("Bild-Item unverändert", indexOf(out, image) >= 0)
        assertRepeatIsClean(out, heicFormat)
    }

    @Test
    fun imageItemsWithRandomBytesAndTiffHeaderAreNotTouched() {
        // Gegenprobe: Bilddaten mit zufälligem TIFF-Kopf und unbrauchbarem IFD-Zeiger sind kein Standort und bleiben.
        val image = randomWithTiffHeader(3, 2048)
        val original = HeifFixtures.standard("heic", listOf(HeifFixtures.Item(1, "hvc1", image)))

        val (result, out) = scrub(original, heicFormat)

        assertUnchanged(result, original, out)
    }

    // ---------- PNG: Text und Chunks mit unbekanntem Inhalt werden neutralisiert (Nachtrag 3.6d) ----------

    @Test
    fun unknownKeywordTextWithHexTiffIsNeutralizedAndCrcStaysValid() {
        val original = png(textChunk("Ort", profileBody(gpsTiff())))
        assertTrue("Vorher: Hex-TIFF im Text (II*\\0 als 49492a00)", text(original).contains("49492a00"))

        val (result, out) = scrub(original, pngFormat)

        assertCleanChanged(result)
        assertEquals(original.size, out.size)
        assertFalse("Nachher: kein Hex-TIFF mehr", text(out).contains("49492a00"))
        assertFalse("Nachher: Schlüsselwort ist neutralisiert", text(out).contains("Ort"))
        assertTrue("Neutraler Typ", text(out).contains("emBl"))
        assertPngCrcsValid(out)
        assertRepeatIsClean(out, pngFormat)
    }

    @Test
    fun unknownKeywordItxtWithHexTiffIsNulled() {
        val original = png(itxtChunk("Notiz", profileBody(gpsTiff())))
        assertTrue("Vorher: Hex-TIFF im iTXt", text(original).contains("49492a00"))

        val (result, out) = scrub(original, pngFormat)

        assertCleanChanged(result)
        assertEquals(original.size, out.size)
        assertFalse(text(out).contains("49492a00"))
        assertPngCrcsValid(out)
        assertRepeatIsClean(out, pngFormat)
    }

    @Test
    fun compressedUnknownKeywordIsNeutralizedAndCrcStaysValid() {
        val compressed = ztxtDeflated("Ort", String(profileBody(gpsTiff()), Charsets.ISO_8859_1))
        val original = png(pngChunk("zTXt", compressed))

        val (result, out) = scrub(original, pngFormat)

        assertCleanChanged(result)
        assertEquals(original.size, out.size)
        assertFalse("zTXt ist neutralisiert", text(out).contains("zTXt"))
        assertPngCrcsValid(out)
        assertRepeatIsClean(out, pngFormat)
    }

    @Test
    fun unknownKeywordTextWithoutLocationIsNeutralized() {
        val original = png(textChunk("Ort", ascii("Urlaub 2026, Fotos vom Strand")))

        val (result, out) = scrub(original, pngFormat)

        assertCleanChanged(result)
        assertEquals(original.size, out.size)
        assertFalse(text(out).contains("Urlaub"))
        assertPngCrcsValid(out)
        assertRepeatIsClean(out, pngFormat)
    }

    @Test
    fun knownKeywordTextWithoutLocationStaysUnchanged() {
        val original = png(textChunk("Comment", ascii("Urlaub 2026, Fotos vom Strand")))

        val (result, out) = scrub(original, pngFormat)

        assertUnchanged(result, original, out)
    }

    @Test
    fun knownKeywordTextWithLocationTextIsRefusedByVerification() {
        // Bekannte Schlüsselwörter bleiben, laufen aber durch die Textsuche der Nachkontrolle.
        val original = png(textChunk("Comment", ascii("Ort +48.8566+002.3522/")))

        val (result, _) = scrub(original, pngFormat)

        assertEquals(LocationScrubResult.NotRemovable(NotRemovableReason.VERIFICATION_FAILED), result)
    }

    @Test
    fun unknownAncillaryChunkIsNeutralizedAndCrcStaysValid() {
        val original = png(pngChunk("caBX", randomWithTiffHeader(9, 256)))

        val (result, out) = scrub(original, pngFormat)

        assertCleanChanged(result)
        assertEquals(original.size, out.size)
        assertTrue("Neutraler Typ statt caBX", text(out).contains("emBl") && !text(out).contains("caBX"))
        assertPngCrcsValid(out)
        assertRepeatIsClean(out, pngFormat)
    }

    @Test
    fun unknownCriticalChunkIsNotRemovable() {
        val original = png(pngChunk("ABCD", ByteArray(4)))

        val (result, _) = scrub(original, pngFormat)

        assertEquals(LocationScrubResult.NotRemovable(NotRemovableReason.UNSUPPORTED), result)
    }

    @Test
    fun pngStaysDecodableAfterNeutralizingTextAndChunks() {
        // Lesbarkeit: Ein echtes PNG bleibt nach dem Neutralisieren dekodierbar, mit denselben Pixeln.
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 16) for (y in 0 until 16) image.setRGB(x, y, (x * 16) shl 16 or (y * 16))
        val encoded = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        // Nach IHDR (Signatur 8 Bytes, IHDR 25 Bytes) einfügen: Text mit Befund, zTXt, unbekannter Ancillary-Chunk.
        val ihdrEnd = 8 + 25
        val extra =
            concat(
                textChunk("Ort", profileBody(gpsTiff())),
                pngChunk("zTXt", ztxtDeflated("Ort", "+48.8566+002.3522/")),
                pngChunk("caBX", randomWithTiffHeader(4, 128)),
            )
        val original = encoded.copyOfRange(0, ihdrEnd) + extra + encoded.copyOfRange(ihdrEnd, encoded.size)

        val (result, out) = scrub(original, pngFormat)

        assertCleanChanged(result)
        assertEquals(original.size, out.size)
        assertPngCrcsValid(out)
        val before = ImageIO.read(ByteArrayInputStream(encoded))
        val after =
            checkNotNull(ImageIO.read(ByteArrayInputStream(out))) { "PNG ist nach der Bereinigung nicht dekodierbar" }
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        for (x in 0 until 16) for (y in 0 until 16) assertEquals(before.getRGB(x, y), after.getRGB(x, y))
    }

    @Test
    fun idatWithTiffHeaderInImageDataGivesNoFalseAlarm() {
        // Gegenprobe: Komprimierte Bilddaten mit zufälligem TIFF-Kopf bleiben unangetastet.
        val original = png(idatWithTiffHeader(5))

        val (result, out) = scrub(original, pngFormat)

        assertUnchanged(result, original, out)
    }
}
