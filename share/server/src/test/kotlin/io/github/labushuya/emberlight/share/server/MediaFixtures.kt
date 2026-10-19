package io.github.labushuya.emberlight.share.server

/**
 * Synthetische Testdateien für die Server-Tests. Minimale Byte-Folgen mit den Signaturen aus ADR 0013, sonst Nullen.
 * Herkunft: dieser Code. Keine Fremddateien. Die Fixtures aus `share:space` sind dort nicht sichtbar, deshalb die
 * kleine Kopie hier.
 */
internal object MediaFixtures {
    /** Minimales gültiges JPEG mit einem COM-Segment aus Nullen und EOI, genau [size] Bytes (mindestens 8). */
    fun jpeg(size: Int = 64): ByteArray {
        require(size >= 8) { "JPEG-Fixture braucht mindestens 8 Bytes." }
        val comLength = size - 6
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xFE.toByte()) +
            byteArrayOf((comLength shr 8).toByte(), comLength.toByte()) +
            ByteArray(size - 8) +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }

    fun box(
        type: String,
        payload: ByteArray = ByteArray(0),
    ): ByteArray = u32(8L + payload.size) + ascii(type) + payload

    fun ftyp(brand: String): ByteArray = box("ftyp", ascii(brand) + ByteArray(4))

    fun mp4(): ByteArray = ftyp("isom") + box("moov", box("free", ByteArray(8))) + box("mdat", ByteArray(16))

    fun ascii(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

    private fun u32(value: Long): ByteArray =
        byteArrayOf(
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte(),
        )
}
