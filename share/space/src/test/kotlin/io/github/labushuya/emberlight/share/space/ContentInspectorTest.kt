package io.github.labushuya.emberlight.share.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min

class ContentInspectorTest {
    private fun identify(bytes: ByteArray): DetectedFormat? =
        ContentInspector.identify(bytes.copyOf(min(bytes.size, ContentInspector.HEAD_BYTES)))

    /** Erkennung plus Strukturprüfung, wie der Upload sie ausführt. */
    private fun accepted(bytes: ByteArray): Boolean {
        val format = identify(bytes) ?: return false
        return ContentInspector.hasValidStructure(format, bytes.size.toLong()) { offset, length ->
            bytes.copyOfRange(offset.toInt(), min(bytes.size, offset.toInt() + length))
        }
    }

    @Test
    fun recognizesStillImageFormats() {
        assertEquals("image/jpeg", identify(MediaFixtures.jpeg())?.mimeType)
        assertEquals("image/png", identify(MediaFixtures.png())?.mimeType)
        assertEquals("image/gif", identify(MediaFixtures.gif())?.mimeType)
        assertEquals("image/webp", identify(MediaFixtures.webp())?.mimeType)
        assertEquals(MediaKind.IMAGE, identify(MediaFixtures.jpeg())?.kind)
    }

    @Test
    fun recognizesHeicAndAvifByBrand() {
        assertEquals("image/heic", identify(MediaFixtures.heic())?.mimeType)
        assertEquals("image/avif", identify(MediaFixtures.avif())?.mimeType)
        assertEquals("image/heif", identify(MediaFixtures.ftyp("mif1"))?.mimeType)
        assertEquals("image/heic", identify(MediaFixtures.ftyp("heix"))?.mimeType)
        assertTrue(accepted(MediaFixtures.heic()))
        assertTrue(accepted(MediaFixtures.avif()))
    }

    @Test
    fun recognizesMp4ThreeGpAndQuickTimeByBrand() {
        assertEquals("video/mp4", identify(MediaFixtures.mp4())?.mimeType)
        assertEquals("video/3gpp", identify(MediaFixtures.threeGp())?.mimeType)
        assertEquals("video/quicktime", identify(MediaFixtures.quickTime())?.mimeType)
        assertEquals(MediaKind.VIDEO, identify(MediaFixtures.mp4())?.kind)
        assertTrue(accepted(MediaFixtures.mp4()))
        assertTrue(accepted(MediaFixtures.threeGp()))
        assertTrue(accepted(MediaFixtures.quickTime()))
    }

    @Test
    fun recognizesQuickTimeWithoutFtypByFirstBox() {
        for (type in listOf("moov", "mdat", "wide")) {
            val bytes = MediaFixtures.box(type, ByteArray(16)) + MediaFixtures.box("mdat", ByteArray(16))
            assertEquals("Erste Box $type", "video/quicktime", identify(bytes)?.mimeType)
            assertTrue("Box-Kette $type", accepted(bytes))
        }
    }

    @Test
    fun rejectsSequencesFreeAndUnknownBrands() {
        assertNull(identify(MediaFixtures.ftyp("msf1")))
        assertNull(identify(MediaFixtures.ftyp("hevc")))
        assertNull(identify(MediaFixtures.ftyp("hevx")))
        assertNull(identify(MediaFixtures.ftyp("avis")))
        assertNull(identify(MediaFixtures.box("free", ByteArray(16))))
        assertNull(identify(MediaFixtures.ascii("<html><body>nicht ein Bild</body></html>")))
        assertNull(identify(ByteArray(0)))
    }

    @Test
    fun rejectsBrokenSignatures() {
        // Ein halber JPEG-Kopf ist kein JPEG. Ein RIFF-Container ohne WEBP ist kein WebP.
        assertNull(identify(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
        assertNull(identify(MediaFixtures.ascii("RIFF") + ByteArray(12)))
    }

    @Test
    fun polyglotPngWithAppendedHtmlStaysPng() {
        val bytes = MediaFixtures.png(MediaFixtures.ascii("<script>alert(1)</script>"))

        assertEquals("image/png", identify(bytes)?.mimeType)
        assertTrue(accepted(bytes))
    }

    @Test
    fun plainImagesSkipStructureCheck() {
        assertTrue(accepted(MediaFixtures.jpeg(size = 1024)))
    }

    @Test
    fun validBoxChainIsAccepted() {
        assertTrue(accepted(MediaFixtures.mp4()))
    }

    @Test
    fun boxWithSizeZeroAsLastBoxIsAccepted() {
        val bytes = MediaFixtures.ftyp("isom") + MediaFixtures.rawBox(0, "mdat", ByteArray(32))

        assertTrue(accepted(bytes))
    }

    @Test
    fun boxWithLargeSizeIsAccepted() {
        val payload = ByteArray(16)
        val large =
            MediaFixtures.u32(1) + MediaFixtures.ascii("mdat") +
                ByteArray(8) { i -> if (i == 7) (16 + payload.size).toByte() else 0 } + payload
        val bytes = MediaFixtures.ftyp("isom") + large

        assertTrue(accepted(bytes))
    }

    @Test
    fun boxLargerThanFileIsRejected() {
        val bytes = MediaFixtures.ftyp("isom") + MediaFixtures.rawBox(1000, "mdat", ByteArray(16))

        assertFalse(accepted(bytes))
    }

    @Test
    fun boxSizeBelowHeaderIsRejected() {
        val bytes = MediaFixtures.ftyp("isom") + MediaFixtures.rawBox(4, "mdat", ByteArray(16))

        assertFalse(accepted(bytes))
    }

    @Test
    fun trailingPartialHeaderIsRejected() {
        val bytes = MediaFixtures.mp4() + ByteArray(3)

        assertFalse(accepted(bytes))
    }

    @Test
    fun largeSizeBelowHeaderLengthIsRejected() {
        val bytes = MediaFixtures.ftyp("isom") + MediaFixtures.u32(1) + MediaFixtures.ascii("mdat") + ByteArray(8)

        assertFalse(accepted(bytes))
    }

    @Test
    fun formatWithoutValidBoxChainFailsRecognitionAndStructure() {
        val bytes = MediaFixtures.ftyp("isom") + MediaFixtures.rawBox(999_999, "mdat")
        val format = identify(bytes)

        assertNotNull(format)
        assertFalse(accepted(bytes))
    }
}
