package io.github.labushuya.emberlight.feature.space.run

import android.graphics.Bitmap
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import io.github.labushuya.emberlight.share.space.participants.Secrets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * QR-Code und Beitrittsadresse (ADR 0018): Inhalt, Ruhezone, Lesbarkeit.
 *
 * Lesbarkeit prüft der Test am Bitmap, das die Oberfläche zeigt, mit `PURE_BARCODE`. Ohne diesen Hinweis sucht ZXing
 * Finder- und Ausrichtungsmuster selbst. Auf dem scharfen, synthetischen Bild scheiterte das bei rund einem von hundert
 * Zufallsgeheimnissen (`NotFoundException` oder `ChecksumException`), unabhängig von der Pixelgröße. Mit `PURE_BARCODE` liest
 * ZXing das Modulraster direkt. Die Aussage bleibt: Der Code enthält genau die Einladungs-URL.
 */
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
        assertEquals(link, decode(InviteQr.bitmap(link)))
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

    /** Dekodiert das Bitmap der Oberfläche. `PURE_BARCODE`: kein Suchen nach Mustern, nur das Modulraster. */
    private fun decode(bitmap: Bitmap): String {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val hints = mapOf<DecodeHintType, Any>(DecodeHintType.PURE_BARCODE to true)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    }
}
