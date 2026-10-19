package io.github.labushuya.emberlight.feature.space

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Synthetische Bilddateien für die Titelbild-Tests der Oberfläche. Jede ist eine minimale Byte-Folge mit der richtigen
 * Signatur. Die ausführlichen Fälle der Bereinigung stehen in `share:space`. Hier geht es nur um Ablauf und Meldungen.
 */
internal object CoverFixtures {
    /** Minimales JPEG: SOI, ein COM-Segment, EOI. Genau [size] Bytes (mindestens 8). */
    fun jpeg(size: Int = 64): ByteArray {
        require(size >= 8) { "JPEG-Fixture braucht mindestens 8 Bytes." }
        val comLength = size - 6
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xFE.toByte()) +
            byteArrayOf((comLength shr 8).toByte(), comLength.toByte()) +
            ByteArray(size - 8) +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }

    /** Video (MP4): Ein Bild ist es nicht, also wird es als Titelbild abgewiesen. */
    fun mp4(): ByteArray =
        box("ftyp", ascii("isom") + ByteArray(4)) + box("moov", box("free", ByteArray(8))) + box("mdat", ByteArray(16))

    /**
     * PNG, dessen Text mit einem Ort komprimiert im zTXt-Chunk liegt. Das lässt sich nicht sicher ändern, also ist der
     * Standort nicht bereinigbar.
     */
    fun pngWithCompressedLocation(): ByteArray {
        val ihdr = be32(1) + be32(1) + byteArrayOf(8, 2, 0, 0, 0)
        val text = "Comment".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 0) + deflate("Ort +48.8566+002.3522/")
        return PNG_SIGNATURE + chunk("IHDR", ihdr) + chunk("zTXt", text) + chunk("IEND", ByteArray(0))
    }

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun box(
        type: String,
        payload: ByteArray,
    ): ByteArray = be32((8 + payload.size).toLong()) + ascii(type) + payload

    private fun chunk(
        type: String,
        data: ByteArray,
    ): ByteArray {
        val typeBytes = ascii(type)
        val crc = CRC32().apply { update(typeBytes + data) }.value
        return be32(data.size.toLong()) + typeBytes + data + be32(crc)
    }

    private fun deflate(text: String): ByteArray {
        val deflater = Deflater()
        deflater.setInput(text.toByteArray(Charsets.ISO_8859_1))
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(256)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            out.write(buffer, 0, count)
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

    private fun be32(value: Long): ByteArray =
        byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())
}
