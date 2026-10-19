package io.github.labushuya.emberlight.share.space

import kotlinx.coroutines.CancellationException

/**
 * Synthetische Testdateien. Jede ist eine minimale Byte-Folge mit den Signaturen aus ADR 0013 und sonst leer. Es gibt
 * keine Fremddateien: Herkunft ist dieser Code, Lizenz also unproblematisch.
 */
internal object MediaFixtures {
    private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun jpeg(size: Int = 64): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(size - 3)

    fun png(extra: ByteArray = ByteArray(0)): ByteArray = pngSignature + ByteArray(24) + extra

    fun gif(): ByteArray = ascii("GIF89a") + ByteArray(26)

    fun webp(): ByteArray = ascii("RIFF") + u32(12) + ascii("WEBP") + ByteArray(12)

    /** Ein Box-Kopf mit normaler 32-bit-Größe, gefolgt von [payload]. */
    fun box(
        type: String,
        payload: ByteArray = ByteArray(0),
    ): ByteArray = u32(8L + payload.size) + ascii(type) + payload

    /** Ein `ftyp`-Box mit Hauptmarke [brand]. */
    fun ftyp(brand: String): ByteArray = box("ftyp", ascii(brand) + ByteArray(4))

    fun heic(): ByteArray = ftyp("heic") + box("mdat", ByteArray(16))

    fun avif(): ByteArray = ftyp("avif") + box("mdat", ByteArray(16))

    fun mp4(): ByteArray = ftyp("isom") + box("moov", ByteArray(16)) + box("mdat", ByteArray(16))

    fun threeGp(): ByteArray = ftyp("3gp5") + box("mdat", ByteArray(16))

    fun quickTime(): ByteArray = ftyp("qt  ") + box("moov", ByteArray(16)) + box("mdat", ByteArray(16))

    /** QuickTime ohne `ftyp`: die erste Box ist `moov`, `mdat` oder `wide`. */
    fun quickTimeWithoutFtyp(): ByteArray = box("moov", ByteArray(16)) + box("mdat", ByteArray(16))

    /** Eine Box mit beliebiger, auch falscher, Größenangabe. Der Typ steht immer an Offset 4. */
    fun rawBox(
        size32: Long,
        type: String,
        payload: ByteArray = ByteArray(0),
    ): ByteArray = u32(size32) + ascii(type) + payload

    fun ascii(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

    fun u32(value: Long): ByteArray =
        byteArrayOf(
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte(),
        )
}

/** Quelle, die einen Körper in Stücken liefert. Ein Stück ist höchstens [chunk] Bytes lang. */
internal class BytesSource(
    private val data: ByteArray,
    private val chunk: Int = 4096,
) : ChunkSource {
    private var position = 0

    override suspend fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (position >= data.size) return -1
        val count = minOf(length, chunk, data.size - position)
        data.copyInto(buffer, offset, position, position + count)
        position += count
        return count
    }
}

/** Quelle, die nach [failAfter] Bytes abbricht, wie eine unterbrochene Verbindung. */
internal class BrokenSource(
    private val inner: ChunkSource,
    private val failAfter: Int,
) : ChunkSource {
    private var delivered = 0

    override suspend fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (delivered >= failAfter) throw CancellationException("Verbindung abgebrochen (Test).")
        val count = inner.read(buffer, offset, minOf(length, failAfter - delivered))
        if (count > 0) delivered += count
        return count
    }
}
