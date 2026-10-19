package io.github.labushuya.emberlight.share.space

/** Art eines Mediums. Bestimmt die Größengrenze (ADR 0013, Dateigrenzen). */
enum class MediaKind {
    IMAGE,
    VIDEO,
}

/**
 * Ein am Inhalt erkanntes Format. [boxed] heißt: Die Datei ist eine Kette von Boxen (ISO-BMFF oder QuickTime). Ihre
 * Größenangaben werden vor der Annahme geprüft.
 */
data class DetectedFormat(
    val mimeType: String,
    val kind: MediaKind,
    val boxed: Boolean,
)

/** Lesezugriff auf die Datei für die Strukturprüfung. Liefert höchstens [length] Bytes ab [offset], leer am Ende. */
fun interface ByteReader {
    fun read(
        offset: Long,
        length: Int,
    ): ByteArray
}

/**
 * Inhaltsprüfung nach Signatur und Struktur, ohne Volldekodierung (ADR 0013, Inhaltsprüfung).
 *
 * Endung und `Content-Type` des Clients zählen nicht. Erkannt werden nur die Formate der Liste. Alles andere, auch
 * Sequenzen (HEIC, AVIF) und MP4-Varianten ohne bekannte Marke, liefert `null`.
 */
object ContentInspector {
    /** Bytes am Anfang, die für die Erkennung reichen. */
    const val HEAD_BYTES = 32

    private val jpegSignature = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val pngSignature =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** Hauptmarken für `ftyp` (Offset 8). Sequenzmarken fehlen absichtlich. */
    private val ftypBrands: Map<String, Pair<String, MediaKind>> =
        buildMap {
            put("heic", "image/heic" to MediaKind.IMAGE)
            put("heix", "image/heic" to MediaKind.IMAGE)
            put("mif1", "image/heif" to MediaKind.IMAGE)
            put("avif", "image/avif" to MediaKind.IMAGE)
            listOf("isom", "iso2", "mp41", "mp42", "avc1").forEach { put(it, "video/mp4" to MediaKind.VIDEO) }
            put("qt  ", "video/quicktime" to MediaKind.VIDEO)
            listOf("3gp4", "3gp5", "3gp6", "3gp7", "3ge6", "3ge7", "3gg6", "3gr6", "3gs6").forEach {
                put(it, "video/3gpp" to MediaKind.VIDEO)
            }
        }

    /** Erkennt das Format am Anfang der Datei. [head] sollte [HEAD_BYTES] Bytes lang sein. */
    fun identify(head: ByteArray): DetectedFormat? =
        when {
            head.startsWith(jpegSignature) -> {
                DetectedFormat("image/jpeg", MediaKind.IMAGE, boxed = false)
            }

            head.startsWith(pngSignature) -> {
                DetectedFormat("image/png", MediaKind.IMAGE, boxed = false)
            }

            head.matchesAscii(0, "GIF87a") || head.matchesAscii(0, "GIF89a") -> {
                DetectedFormat("image/gif", MediaKind.IMAGE, boxed = false)
            }

            head.matchesAscii(0, "RIFF") && head.matchesAscii(8, "WEBP") -> {
                DetectedFormat("image/webp", MediaKind.IMAGE, boxed = false)
            }

            head.matchesAscii(4, "ftyp") -> {
                identifyFtyp(head)
            }

            head.matchesAscii(4, "moov") || head.matchesAscii(4, "mdat") || head.matchesAscii(4, "wide") -> {
                DetectedFormat("video/quicktime", MediaKind.VIDEO, boxed = true)
            }

            else -> {
                null
            }
        }

    /**
     * Prüft die Box-Struktur. Bei Formaten ohne Boxen gilt die Prüfung als bestanden. Sonst muss die Kette der
     * Top-Level-Boxen lückenlos bis zum Dateiende reichen. Gelesen wird nur der Kopf jeder Box.
     */
    fun hasValidStructure(
        format: DetectedFormat,
        fileSize: Long,
        reader: ByteReader,
    ): Boolean = !format.boxed || BoxChain.isValid(fileSize, reader)

    private fun identifyFtyp(head: ByteArray): DetectedFormat? {
        val brand = head.asciiAt(8, 4) ?: return null
        val (mime, kind) = ftypBrands[brand] ?: return null
        return DetectedFormat(mime, kind, boxed = true)
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun ByteArray.matchesAscii(
        offset: Int,
        text: String,
    ): Boolean = asciiAt(offset, text.length) == text

    private fun ByteArray.asciiAt(
        offset: Int,
        length: Int,
    ): String? {
        if (offset + length > size) return null
        return String(this, offset, length, Charsets.ISO_8859_1)
    }
}

/** Kette der Top-Level-Boxen (ADR 0013, Strukturprüfung). Größen müssen plausibel sein und lückenlos enden. */
private object BoxChain {
    private const val MAX_BOXES = 100_000
    private const val HEADER_READ = 16
    private const val MIN_HEADER = 8

    fun isValid(
        fileSize: Long,
        reader: ByteReader,
    ): Boolean {
        var offset = 0L
        var boxes = 0
        while (offset < fileSize) {
            boxes++
            if (boxes > MAX_BOXES) return false
            val header = reader.read(offset, HEADER_READ)
            if (header.size < MIN_HEADER) return false
            val size32 = header.unsignedInt(0)
            val boxSize =
                when (size32) {
                    // Größe 1: 64-bit-Länge folgt. Die Länge muss den Kopf von 16 Bytes umfassen.
                    1L -> {
                        if (header.size < HEADER_READ) return false
                        val large = header.longAt(8)
                        if (large < HEADER_READ) return false
                        large
                    }

                    // Größe 0: die Box reicht bis zum Dateiende. Danach kommt keine weitere Box.
                    0L -> {
                        fileSize - offset
                    }

                    else -> {
                        if (size32 < MIN_HEADER) return false
                        size32
                    }
                }
            if (boxSize > fileSize - offset) return false
            offset += boxSize
        }
        return boxes > 0 && offset == fileSize
    }

    private fun ByteArray.unsignedInt(offset: Int): Long =
        ((this[offset].toLong() and 0xFF) shl 24) or
            ((this[offset + 1].toLong() and 0xFF) shl 16) or
            ((this[offset + 2].toLong() and 0xFF) shl 8) or
            (this[offset + 3].toLong() and 0xFF)

    private fun ByteArray.longAt(offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (this[offset + i].toLong() and 0xFF)
        return value
    }
}
