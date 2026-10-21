package io.github.labushuya.emberlight.share.space.location

import io.github.labushuya.emberlight.share.space.DetectedFormat
import io.github.labushuya.emberlight.share.space.MediaKind
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.ascii
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.contains
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.exifTiff
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.png
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.pngChunk
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.rawProfileText
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.webp
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.webpChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.MessageDigest

/**
 * Lücken der Container außerhalb des JPEG (ADR 0015, Nachtrag): PNG-Raw-Profile anderer Art (iptc, app1), WebP mit
 * unbekannten Chunks und EXIF/XMP in Animationsbildern (ANMF). Jeder Fall prüft: Länge gleich, Standort weg.
 */
@RunWith(RobolectricTestRunner::class)
class ContainerGapScrubTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun scrub(
        bytes: ByteArray,
        mime: String,
    ): Pair<LocationScrubResult, ByteArray> {
        val file = folder.newFile()
        file.writeBytes(bytes)
        val result =
            LocationScrubber.scrub(
                file,
                DetectedFormat(mime, MediaKind.IMAGE, boxed = false),
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
            )
        return result to file.readBytes()
    }

    /** Hex-Text ohne Leerraum, damit Zeilenumbrüche im Raw-Profile keine Treffer verbergen. */
    private fun hexText(bytes: ByteArray): String = String(bytes, Charsets.ISO_8859_1).filterNot { it.isWhitespace() }

    private fun hexOf(text: String): String =
        text.toByteArray(Charsets.ISO_8859_1).joinToString("") { "%02x".format(it) }

    @Test
    fun pngRawProfileOfOtherTypeHasItsHexDigitsBlankedAndKeepsLength() {
        val makerHex = hexOf("HERSTELLER-DATEN1")
        val original = png(pngChunk("tEXt", rawProfileText(exifTiff().bytes, type = "iptc")))
        assertTrue("Vorher muss das Muster im Hex-Text stehen", hexText(original).contains(makerHex))

        val (result, out) = scrub(original, "image/png")

        assertTrue("Erwartet Clean, war $result", result is LocationScrubResult.Clean)
        assertEquals(original.size, out.size)
        assertFalse("Hex-Text ist genullt", hexText(out).contains(makerHex))
        assertTrue("Anderer Raw-Profile-Typ bleibt als Chunk erhalten", contains(out, "Raw profile type iptc"))
    }

    @Test
    fun pngExifRawProfileStillGoesThroughTheTiffWalker() {
        val makerHex = hexOf("HERSTELLER-DATEN1")
        val original = png(pngChunk("tEXt", rawProfileText(exifTiff().bytes)))

        val (result, out) = scrub(original, "image/png")

        assertTrue("Erwartet Clean, war $result", result is LocationScrubResult.Clean)
        assertEquals(original.size, out.size)
        assertFalse(hexText(out).contains(makerHex))
    }

    @Test
    fun webpUnknownChunkAndExifInsideAnimationFrameAreCleaned() {
        val frame = webpChunk("EXIF", exifTiff().bytes)
        val anmf = webpChunk("ANMF", ByteArray(16) + frame)
        val unknown = webpChunk("XFOO", ascii("Ort +48.8566+002.3522+035.000/"))
        val original = webp(webpChunk("VP8 ", ByteArray(10)), unknown, anmf)
        assertTrue(StrictLocationProbe.findings(MemoryStore(original), scanTiff = true).isNotEmpty())

        val (result, out) = scrub(original, "image/webp")

        assertTrue("Erwartet Clean, war $result", result is LocationScrubResult.Clean)
        assertEquals(original.size, out.size)
        assertFalse(contains(out, "+48.8566"))
        assertTrue(StrictLocationProbe.findings(MemoryStore(out), scanTiff = true).isEmpty())
    }
}
