package io.github.labushuya.emberlight.core.media.exif

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream

/** Abbildung der EXIF-Attribute auf [PhotoExif] an einer selbst erzeugten Test-JPEG (ohne GPS). */
@RunWith(RobolectricTestRunner::class)
class PhotoExifMappingTest {
    @Test
    fun mapsCameraFieldsFromTestJpeg() {
        val exif = readSample()

        assertEquals("Emberlight", exif.make)
        assertEquals("TestCam 1", exif.model)
        assertEquals("Test 50mm f/1.8", exif.lensModel)
        assertEquals(1.8, exif.fNumber!!, 0.001)
        assertEquals(1.0 / 250.0, exif.exposureSeconds!!, 0.0001)
        assertEquals(200, exif.iso)
        assertEquals(50.0, exif.focalLengthMm!!, 0.001)
        assertEquals(true, exif.flashFired)
        assertFalse(exif.isEmpty)
    }

    @Test
    fun testJpegHasNoGpsFieldsAtAll() {
        // Die Datei enthält keinen GPS-Block. Das Mapping liest ohnehin keine GPS-Felder.
        val attributes = ExifInterface(ByteArrayInputStream(sampleBytes()))
        assertNull(attributes.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(attributes.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
    }

    @Test
    fun jpegWithoutExifIsEmpty() {
        val bare = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val exif = ExifInterface(ByteArrayInputStream(bare)).toPhotoExif()
        assertTrue(exif.isEmpty)
    }

    @Test
    fun flashBitDecidesWhetherItFired() {
        assertEquals(false, flashFiredOrNull(0))
        assertEquals(true, flashFiredOrNull(1))
        assertEquals(true, flashFiredOrNull(9))
        assertEquals(false, flashFiredOrNull(16))
        assertNull(flashFiredOrNull(-1))
    }

    private fun readSample(): PhotoExif = ExifInterface(ByteArrayInputStream(sampleBytes())).toPhotoExif()

    private fun sampleBytes(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/exif/sample-exif.jpg")) { "Test-JPEG fehlt" }.use { it.readBytes() }
}
