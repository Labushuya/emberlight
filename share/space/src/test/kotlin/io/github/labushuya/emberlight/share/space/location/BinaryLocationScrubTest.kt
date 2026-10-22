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
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * Binäre Restrisiken aus ADR 0015 (Nachtrag 3.1b-2): MP4-Metadaten-Tracks, unbekannte HEIF-Items, PNG-Text mit
 * unbekanntem Schlüsselwort. Jeder Fall prüft: Befund vorher, Bereinigung nachher, Datei weiterhin gültig (Länge, CRC),
 * Wiederholung ohne Befund. Gegenproben ohne Standort dürfen nichts ändern.
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

    // ---------- Restrisiko 2: MP4-Metadaten-Tracks und unbekannte Boxen ----------

    @Test
    fun gpmdTrackWithGps5IsNulledInItsSamplesOnly() {
        val mp4 = BinaryLocationFixtures.mp4("meta", "gpmd", listOf(gpmfSample(true), gpmfSample(true)))
        assertTrue("Vorher: GPS5 im Sample", text(mp4.bytes).contains("GPS5"))

        val (result, out) = scrub(mp4.bytes, mp4Format)

        assertCleanChanged(result)
        assertChangesWithin(mp4.bytes, out, mp4.sampleRanges)
        assertFalse("Nachher: kein GPS5 mehr", text(out).contains("GPS5"))
        assertRepeatIsClean(out, mp4Format)
    }

    @Test
    fun gpmdTrackWithoutGpsStaysUnchanged() {
        val mp4 = BinaryLocationFixtures.mp4("meta", "gpmd", listOf(gpmfSample(false)))

        val (result, out) = scrub(mp4.bytes, mp4Format)

        assertUnchanged(result, mp4.bytes, out)
    }

    @Test
    fun cammTrackWithGpsRecordIsNulled() {
        val mp4 =
            BinaryLocationFixtures.mp4("meta", "camm", listOf(cammSample(0), cammSample(5)))
        val gpsRange = mp4.sampleRanges[1]
        assertEquals("Vorher: Typ 5 im Record", 5.toByte(), mp4.bytes[gpsRange.first + 2])

        val (result, out) = scrub(mp4.bytes, mp4Format)

        assertCleanChanged(result)
        assertChangesWithin(mp4.bytes, out, mp4.sampleRanges)
        assertTrue("Nachher: GPS-Sample geleert", gpsRange.all { out[it] == 0.toByte() })
        assertRepeatIsClean(out, mp4Format)
    }

    @Test
    fun cammTrackWithoutGpsRecordStaysUnchanged() {
        val mp4 = BinaryLocationFixtures.mp4("meta", "camm", listOf(cammSample(0), cammSample(1)))

        val (result, out) = scrub(mp4.bytes, mp4Format)

        assertUnchanged(result, mp4.bytes, out)
    }

    @Test
    fun djmdTrackIsNulledBecauseItsGpsFieldsAreNotReadable() {
        val mp4 = BinaryLocationFixtures.mp4("meta", "djmd", listOf(djmdSample(), djmdSample()))

        val (result, out) = scrub(mp4.bytes, mp4Format)

        assertCleanChanged(result)
        assertChangesWithin(mp4.bytes, out, mp4.sampleRanges)
        assertRepeatIsClean(out, mp4Format)
    }

    @Test
    fun gpmdTrackWithoutSampleTableStaysMetadataTrack() {
        val mp4 = BinaryLocationFixtures.mp4("meta", "gpmd", listOf(gpmfSample(true)), withTables = false)

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
    fun cammTrackWithManySamplesWithoutGpsStaysClean() {
        val samples = List(12_000) { cammSample(0) }
        val mp4 = BinaryLocationFixtures.mp4("meta", "camm", samples)

        val (result, out) = scrub(mp4.bytes, mp4Format)

        assertUnchanged(result, mp4.bytes, out)
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

    // ---------- Restrisiko 1: unbekannte HEIF-Items ----------

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
    fun imageItemsAndRandomBytesWithTiffHeaderGiveNoFalseAlarm() {
        // Gegenprobe: Bilddaten mit zufälligem TIFF-Kopf und unbrauchbarem IFD-Zeiger sind kein Standort.
        val original =
            HeifFixtures.standard(
                "heic",
                listOf(
                    HeifFixtures.Item(1, "hvc1", randomWithTiffHeader(3, 2048)),
                    HeifFixtures.Item(2, "uri ", randomWithTiffHeader(4, 512)),
                ),
            )

        val (result, out) = scrub(original, heicFormat)

        assertUnchanged(result, original, out)
    }

    // ---------- Restrisiko 3: PNG-Text mit unbekanntem Schlüsselwort ----------

    @Test
    fun unknownKeywordTextWithHexTiffIsNulledAndCrcStaysValid() {
        val original = png(textChunk("Comment", profileBody(gpsTiff())))
        assertTrue("Vorher: Hex-TIFF im Text (II*\\0 als 49492a00)", text(original).contains("49492a00"))

        val (result, out) = scrub(original, pngFormat)

        assertCleanChanged(result)
        assertEquals(original.size, out.size)
        assertFalse("Nachher: kein Hex-TIFF mehr", text(out).contains("49492a00"))
        assertTrue("Schlüsselwort bleibt", text(out).contains("Comment"))
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
    fun compressedUnknownKeywordWithHexTiffIsNotRemovable() {
        val compressed = ztxtDeflated("Comment", String(profileBody(gpsTiff()), Charsets.ISO_8859_1))
        val original = png(pngChunk("zTXt", compressed))

        val (result, _) = scrub(original, pngFormat)

        assertEquals(LocationScrubResult.NotRemovable(NotRemovableReason.COMPRESSED_TEXT), result)
    }

    @Test
    fun unknownKeywordTextWithoutLocationStaysUnchanged() {
        val original = png(textChunk("Comment", ascii("Urlaub 2026, Fotos vom Strand")))

        val (result, out) = scrub(original, pngFormat)

        assertUnchanged(result, original, out)
    }

    @Test
    fun idatWithTiffHeaderInImageDataGivesNoFalseAlarm() {
        // Gegenprobe: Komprimierte Bilddaten mit zufälligem TIFF-Kopf bleiben unangetastet.
        val original = png(idatWithTiffHeader(5))

        val (result, out) = scrub(original, pngFormat)

        assertUnchanged(result, original, out)
    }
}
