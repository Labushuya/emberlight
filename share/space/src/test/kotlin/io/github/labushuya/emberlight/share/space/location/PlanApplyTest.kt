package io.github.labushuya.emberlight.share.space.location

import io.github.labushuya.emberlight.share.space.DetectedFormat
import io.github.labushuya.emberlight.share.space.MediaKind
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.ascii
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.box
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.exifTiff
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Plan statt Sicherungskopie (ADR 0015): Geschrieben wird nur nach bestandener Prüfung. Fehler werden nicht verschluckt,
 * außer die Struktur selbst ist defekt (`structure`).
 */
class PlanApplyTest {
    /** Speicher, der Schreibvorgänge zählt und auf Wunsch beim Lesen einen Fehler wirft. */
    private class RecordingStore(
        bytes: ByteArray,
        private val failure: Throwable? = null,
    ) : ByteStore {
        private val data = bytes.copyOf()
        var writes = 0
            private set
        override val length: Long = data.size.toLong()

        override fun read(
            offset: Long,
            count: Int,
        ): ByteArray {
            failure?.let { throw it }
            return data.copyOfRange(offset.toInt(), offset.toInt() + count)
        }

        override fun write(
            offset: Long,
            bytes: ByteArray,
        ) {
            writes++
            bytes.copyInto(data, offset.toInt())
        }

        fun bytes(): ByteArray = data.copyOf()
    }

    private val png = DetectedFormat("image/png", MediaKind.IMAGE, boxed = false)
    private val jpeg = DetectedFormat("image/jpeg", MediaKind.IMAGE, boxed = false)

    @Test
    fun planChangesAreNotWrittenWhenALaterChunkRefusesTheFile() {
        // Der Standort im eXIf-Chunk wäre bereinigbar, aber ein komprimierter Text mit Standort nicht: keine Datei-Änderung.
        val original =
            LocationFixtures.png(
                LocationFixtures.pngChunk("eXIf", exifTiff().bytes),
                LocationFixtures.pngChunk("zTXt", LocationFixtures.ztxtDeflated("Comment", "+48.8566+002.3522/")),
            )
        val store = RecordingStore(original)

        val result = LocationScrubber.scrubStore(store, png, sha256Hex(original))

        assertEquals(LocationScrubResult.NotRemovable(NotRemovableReason.COMPRESSED_TEXT), result)
        assertEquals("Kein einziger Schreibvorgang bei nicht bereinigbarer Datei", 0, store.writes)
        assertArrayEquals(original, store.bytes())
    }

    @Test
    fun cleanFileIsWrittenOnceAndTheHashMatchesTheWrittenBytes() {
        val original = LocationFixtures.jpegWithAllLocations()
        val store = RecordingStore(original)

        val result = LocationScrubber.scrubStore(store, jpeg, sha256Hex(original))

        assertTrue("Erwartet Clean mit Änderung, war $result", result is LocationScrubResult.Clean && result.changed)
        assertTrue("Geschrieben wurde", store.writes > 0)
        assertEquals(sha256Hex(store.bytes()), (result as LocationScrubResult.Clean).sha256)
        assertEquals(original.size, store.bytes().size)
    }

    @Test
    fun unchangedFileOnlyScansAndKeepsTheStreamedHash() {
        val original = LocationFixtures.png(LocationFixtures.pngChunk("tEXt", ascii("Comment\u0000Hallo")))
        val store = RecordingStore(original)
        val streamed = "0123456789abcdef"

        val result = LocationScrubber.scrubStore(store, png, streamed)

        assertEquals(LocationScrubResult.Clean(changed = false, sha256 = streamed), result)
        assertEquals("Ohne Änderung kein Schreibvorgang", 0, store.writes)
    }

    @Test
    fun outOfMemoryErrorIsNeverSwallowedAsStructure() {
        val original = LocationFixtures.jpegWithAllLocations()
        val store = RecordingStore(original, failure = OutOfMemoryError("Test"))

        assertThrows(OutOfMemoryError::class.java) {
            LocationScrubber.scrubStore(store, jpeg, sha256Hex(original))
        }
    }

    @Test
    fun stackOverflowErrorIsNeverSwallowedAsStructure() {
        val original = LocationFixtures.jpegWithAllLocations()
        val store = RecordingStore(original, failure = StackOverflowError())

        assertThrows(StackOverflowError::class.java) {
            LocationScrubber.scrubStore(store, jpeg, sha256Hex(original))
        }
    }

    @Test
    fun ioErrorPropagatesSoTheServerReportsIt() {
        val original = LocationFixtures.jpegWithAllLocations()
        val store = RecordingStore(original, failure = IOException("Datenträger"))

        assertThrows(IOException::class.java) {
            LocationScrubber.scrubStore(store, jpeg, sha256Hex(original))
        }
    }

    @Test
    fun unexpectedRuntimeFailureInTheFormatIsStructure() {
        val original = LocationFixtures.jpegWithAllLocations()
        val store = RecordingStore(original, failure = IllegalStateException("Format"))

        val result = LocationScrubber.scrubStore(store, jpeg, sha256Hex(original))

        assertEquals(LocationScrubResult.NotRemovable(NotRemovableReason.STRUCTURE), result)
        assertEquals(0, store.writes)
    }

    @Test
    fun planStoreShowsPlannedBytesWhileTheBaseStaysUnchanged() {
        val base = RecordingStore(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9))
        val plan = PlanStore(base)

        plan.write(2, byteArrayOf(9, 9))
        plan.write(8, byteArrayOf(7))

        assertArrayEquals(byteArrayOf(0, 1, 9, 9, 4, 5, 6, 7, 7, 9), plan.read(0, 10))
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), base.bytes())
        assertEquals(0, base.writes)

        plan.applyTo(base)

        assertArrayEquals(byteArrayOf(0, 1, 9, 9, 4, 5, 6, 7, 7, 9), base.bytes())
        assertEquals(2, base.writes)
    }

    @Test
    fun planStoreRefusesMoreThanTheReadLimit() {
        val plan = PlanStore(RecordingStore(ByteArray(8)))

        assertThrows(Unremovable::class.java) {
            plan.write(0, ByteArray(ScrubLimits.MAX_READ + 1))
        }
    }

    @Test
    fun extentStoreMapsLogicalRangesOntoPhysicalSegments() {
        val base = RecordingStore(ByteArray(20) { it.toByte() })
        // Logisch: Bytes 10..11, dann 2..4 (rückwärts liegende Extents).
        val logical =
            ExtentStore(
                base,
                listOf(ExtentStore.Segment(10, 2), ExtentStore.Segment(2, 3)),
            )

        assertEquals(5L, logical.length)
        assertArrayEquals(byteArrayOf(10, 11, 2, 3, 4), logical.read(0, 5))
        assertArrayEquals(byteArrayOf(11, 2), logical.read(1, 2))

        logical.write(1, byteArrayOf(90, 91, 92))

        // Logische Position 1 liegt physisch bei 11, die Positionen 2 und 3 bei 2 und 3.
        assertArrayEquals(
            byteArrayOf(0, 1, 91, 92, 4, 5, 6, 7, 8, 9, 10, 90, 12, 13, 14, 15, 16, 17, 18, 19),
            base.bytes(),
        )
    }

    /** Zählt die gelesenen Bytes. Ein Lesedurchgang entspricht der Dateigröße (ADR 0015, Lesedurchgänge). */
    private class CountingStore(
        private val base: ByteStore,
    ) : ByteStore {
        var bytesRead = 0L
            private set
        override val length: Long get() = base.length

        override fun read(
            offset: Long,
            count: Int,
        ): ByteArray {
            bytesRead += count
            return base.read(offset, count)
        }

        override fun write(
            offset: Long,
            bytes: ByteArray,
        ) {
            base.write(offset, bytes)
        }
    }

    /**
     * Lesedurchgänge je Upload (ADR 0015). Ein Durchgang ist der Scan mit Hash. JPEG liest die Bilddaten zusätzlich im
     * Plan und in der Prüfung, weil die Suche nach dem nächsten Marker alle Entropiedaten streamt.
     */
    @Test
    fun readPassesPerUploadMatchTheDocumentedCount() {
        val jpegWithEntropy =
            LocationFixtures.jpeg(LocationFixtures.app1Exif(exifTiff().bytes)).let { head ->
                head.copyOf(head.size - 2) +
                    LocationFixtures.segment(0xDA, ByteArray(10)) +
                    ByteArray(MEGABYTE) { 0x11 } +
                    byteArrayOf(0xFF.toByte(), 0xD9.toByte())
            }
        val mp4 = box("ftyp", ascii("isom") + ByteArray(4)) + box("mdat", ByteArray(MEGABYTE))
        val pngWithImageData = LocationFixtures.png(LocationFixtures.pngChunk("IDAT", ByteArray(MEGABYTE)))

        assertPasses(jpegWithEntropy, jpeg, expected = 4.0)
        assertPasses(mp4, DetectedFormat("video/mp4", MediaKind.VIDEO, boxed = true), expected = 2.0)
        assertPasses(pngWithImageData, png, expected = 2.0)
    }

    private fun assertPasses(
        bytes: ByteArray,
        format: DetectedFormat,
        expected: Double,
    ) {
        val counting = CountingStore(RecordingStore(bytes))
        val result = LocationScrubber.scrubStore(counting, format, sha256Hex(bytes))
        assertTrue("Erwartet Clean, war $result", result is LocationScrubResult.Clean)
        val passes = counting.bytesRead.toDouble() / bytes.size
        assertTrue("Lesedurchgänge $passes, erwartet $expected (${format.mimeType})", abs(passes - expected) < 0.05)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MEGABYTE = 1024 * 1024
    }
}
