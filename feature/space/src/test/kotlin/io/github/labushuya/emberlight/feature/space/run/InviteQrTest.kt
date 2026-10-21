package io.github.labushuya.emberlight.feature.space.run

import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import io.github.labushuya.emberlight.share.space.participants.Secrets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** QR-Code und Beitrittsadresse (ADR 0018): Inhalt, Ruhezone, Lesbarkeit. */
@RunWith(RobolectricTestRunner::class)
class InviteQrTest {
    private val secret = Secrets.generate()
    private val link = InviteLink.of("192.168.1.20", 8787, secret)

    @Test
    fun theLinkCarriesTheSecretOnlyInTheFragment() {
        assertEquals("http://192.168.1.20:8787/#i=$secret", link)
        // Der Teil vor dem Fragment, den der Browser an den Server schickt, enthält kein Geheimnis.
        assertFalse(link.substringBefore('#').contains(secret))
    }

    @Test
    fun theCodeDecodesBackToTheSameLink() {
        val matrix = InviteQr.matrix(link)

        assertEquals(link, decode(matrix))
    }

    @Test
    fun theQuietZoneIsFourModulesOnEveryEdge() {
        val matrix = InviteQr.matrix(link)
        val last = matrix.width - 1
        for (offset in 0 until InviteQr.QUIET_ZONE_MODULES) {
            for (i in 0..last) {
                assertFalse("Oben, Zeile $offset", matrix.get(i, offset))
                assertFalse("Unten, Zeile ${last - offset}", matrix.get(i, last - offset))
                assertFalse("Links, Spalte $offset", matrix.get(offset, i))
                assertFalse("Rechts, Spalte ${last - offset}", matrix.get(last - offset, i))
            }
        }
    }

    @Test
    fun theBitmapScalesEveryModuleByAWholeNumberOfPixels() {
        val matrix = InviteQr.matrix(link)
        val bitmap = InviteQr.bitmap(link)

        assertEquals(0, bitmap.width % matrix.width)
        assertEquals(bitmap.width, bitmap.height)
        assertTrue(bitmap.width >= matrix.width)
    }

    private fun decode(matrix: BitMatrix): String {
        val source = MatrixSource(matrix)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }

    /** Graustufen aus der Matrix, ohne Bild-Bibliothek. Schwarz ist ein Modul. */
    private class MatrixSource(
        private val matrix: BitMatrix,
        private val scale: Int = SCALE,
    ) : LuminanceSource(matrix.width * scale, matrix.height * scale) {
        override fun getRow(
            y: Int,
            row: ByteArray?,
        ): ByteArray {
            val out = if (row != null && row.size >= width) row else ByteArray(width)
            for (x in 0 until width) out[x] = luminance(x, y)
            return out
        }

        override fun getMatrix(): ByteArray =
            ByteArray(width * height) { index ->
                luminance(
                    index % width,
                    index / width,
                )
            }

        private fun luminance(
            x: Int,
            y: Int,
        ): Byte = if (matrix.get(x / scale, y / scale)) BLACK else WHITE

        private companion object {
            /** Pixel je Modul. Zu kleine Bilder mit einem Pixel je Modul lassen den Binarisierer scheitern. */
            const val SCALE = 8
            const val BLACK: Byte = 0
            val WHITE: Byte = (-1).toByte()
        }
    }
}
