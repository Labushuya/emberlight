package io.github.labushuya.emberlight.share.space.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Die Plattform-Umsetzung der Vorschauen unter Robolectric (ADR 0019): Größe, kein EXIF im Ergebnis, Ausrichtung, und
 * `failed` ohne Dekodierversuch, wo die Plattform fehlt. Die Videoframes brauchen ein Gerät, siehe Testkatalog.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidPreviewRendererTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val renderer = AndroidPreviewRenderer()

    @Test
    fun aLargeJpegBecomesAJpegWithTheLongEdgeAt320() {
        val source = jpegFile("gross.jpg", width = 1200, height = 600)
        val target = File(folder.root, "vorschau.jpg")

        val ok = renderer.renderImage(source, "image/jpeg", PreviewRules.PREVIEW_LONG_EDGE, target)

        assertTrue(ok)
        assertEquals(320 to 160, sizeOf(target))
    }

    @Test
    fun theResultCarriesNoExifFromTheSource() {
        val source = jpegFile("mit-exif.jpg", width = 640, height = 480, make = "Testkamera")
        val target = File(folder.root, "ohne-exif.jpg")

        assertTrue(renderer.renderImage(source, "image/jpeg", PreviewRules.PREVIEW_LONG_EDGE, target))

        val exif = ExifInterface(target)
        assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
        // Ohne Ausrichtungsdaten meldet ExifInterface "0" (undefiniert), nie eine echte Drehung.
        assertTrue(exif.getAttribute(ExifInterface.TAG_ORIENTATION).let { it == null || it == "0" })
    }

    @Test
    fun anExifRotationOfNinetyDegreesTurnsTheImageToPortrait() {
        val source = jpegFile("quer-gedreht.jpg", width = 200, height = 100, orientation = ExifInterface.ORIENTATION_ROTATE_90)
        val target = File(folder.root, "hochkant.jpg")

        assertTrue(renderer.renderImage(source, "image/jpeg", PreviewRules.PREVIEW_LONG_EDGE, target))

        assertEquals(100 to 200, sizeOf(target))
    }

    @Test
    fun aSmallImageIsNotEnlarged() {
        val source = jpegFile("klein.jpg", width = 120, height = 80)
        val target = File(folder.root, "klein-vorschau.jpg")

        assertTrue(renderer.renderImage(source, "image/jpeg", PreviewRules.PREVIEW_LONG_EDGE, target))

        assertEquals(120 to 80, sizeOf(target))
    }

    @Test
    fun aFileThatIsNoImageFailsWithoutWritingAResult() {
        val source = File(folder.root, "kaputt.jpg").apply { writeBytes("keine Bilddaten".toByteArray()) }
        val target = File(folder.root, "kaputt-vorschau.jpg")

        assertFalse(renderer.renderImage(source, "image/jpeg", PreviewRules.PREVIEW_LONG_EDGE, target))
        assertFalse(target.exists())
    }

    @Test
    @Config(sdk = [26])
    fun heicWithoutThePlatformDecoderBelowApi28FailsWithoutTryingToDecode() {
        val source = File(folder.root, "bild.heic").apply { writeBytes(ByteArray(16)) }
        val target = File(folder.root, "heic-vorschau.jpg")

        assertFalse(renderer.renderImage(source, "image/heic", PreviewRules.VIEW_LONG_EDGE, target))
        assertFalse(target.exists())
    }

    @Test
    @Config(sdk = [30])
    fun avifWithoutThePlatformDecoderBelowApi31FailsWithoutTryingToDecode() {
        val source = File(folder.root, "bild.avif").apply { writeBytes(ByteArray(16)) }
        val target = File(folder.root, "avif-vorschau.jpg")

        assertFalse(renderer.renderImage(source, "image/avif", PreviewRules.PREVIEW_LONG_EDGE, target))
    }

    @Test
    fun aFileThatIsNoVideoGivesNoFrameAndNoCodec() {
        val source = File(folder.root, "kein.mp4").apply { writeBytes("kein Video".toByteArray()) }
        val target = File(folder.root, "frame.jpg")

        assertFalse(renderer.renderVideoFrame(source, PreviewRules.PREVIEW_LONG_EDGE, target))
        assertNull(renderer.videoCodec(source))
    }

    private fun jpegFile(
        name: String,
        width: Int,
        height: Int,
        make: String? = null,
        orientation: Int? = null,
    ): File {
        val file = File(folder.root, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        if (make != null || orientation != null) {
            val exif = ExifInterface(file)
            make?.let { exif.setAttribute(ExifInterface.TAG_MAKE, it) }
            orientation?.let { exif.setAttribute(ExifInterface.TAG_ORIENTATION, it.toString()) }
            exif.saveAttributes()
        }
        return file
    }

    private fun sizeOf(file: File): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return bounds.outWidth to bounds.outHeight
    }
}
