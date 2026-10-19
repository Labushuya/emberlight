package io.github.labushuya.emberlight.share.space.location

import java.util.zip.CRC32

/**
 * PNG-Scrubber (ADR 0015, Fundorte, W3C PNG-3). Chunk-Walker. Gelesen werden nur die Metadaten-Chunks, nie IDAT.
 *
 * - `eXIf`: TIFF-Walker.
 * - `tEXt` oder unkomprimiertes `iTXt` mit `Raw profile type exif`: Hex-Payload dekodieren, TIFF-Walker auf den
 *   Bytes, geänderte Bytes als `0` in den Hex-Text zurückschreiben. Die Länge bleibt gleich.
 * - `iTXt` `XML:com.adobe.xmp` unkomprimiert: XMP-Scrubber.
 * - `zTXt` und komprimiertes `iTXt`: mit Größenlimit dekomprimieren und prüfen. Standort darin ist `compressed_text`.
 *
 * Nach jeder Änderung eines Chunks wird seine CRC über Typ und Daten neu berechnet.
 */
internal object PngScrubber {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val EXIF_PREFIX = ascii("Exif\u0000\u0000")
    private const val RAW_PROFILE_EXIF = "Raw profile type exif"
    private const val XMP_KEYWORD = "XML:com.adobe.xmp"
    private const val SCAN_CHUNK = 64 * 1024

    /** Ein dekodiertes Raw-Profile. [digitAt] sind die Positionen der Hex-Ziffern im Text, zwei je Byte. */
    private class RawProfile(
        val bytes: ByteArray,
        val digitAt: IntArray,
    )

    fun scrubFile(pass: ScrubPass) {
        val size = pass.store.length
        if (!pass.bytes(0, 8, size).contentEquals(SIGNATURE)) bail(NotRemovableReason.STRUCTURE)
        var p = 8L
        while (true) {
            pass.count("png_chunks")
            val length = pass.u32Be(p, size)
            if (length > Int.MAX_VALUE) bail(NotRemovableReason.STRUCTURE)
            val type = pass.bytes(p + 4, 4, size).toString(Charsets.ISO_8859_1)
            val dataStart = p + 8
            val dataEnd = dataStart + length
            val chunkEnd = dataEnd + 4
            pass.bounds(dataStart, length + 4, size)
            val writesBefore = pass.writes
            if (type == "IEND") {
                trailing(pass, chunkEnd, size)
                return
            }
            chunk(pass, type, dataStart, dataEnd)
            if (pass.mutate && pass.writes != writesBefore) writeCrc(pass, p + 4, dataEnd)
            p = chunkEnd
        }
    }

    private fun chunk(
        pass: ScrubPass,
        type: String,
        dataStart: Long,
        dataEnd: Long,
    ) {
        when (type) {
            "eXIf" -> walkPayload(pass, dataStart, dataEnd, 0)
            "tEXt" -> text(pass, dataStart, dataEnd)
            "zTXt" -> compressedText(pass, dataStart, dataEnd)
            "iTXt" -> internationalText(pass, dataStart, dataEnd)
        }
    }

    /** Text-Chunk `tEXt`: Schlüsselwort, NUL, Text. */
    private fun text(
        pass: ScrubPass,
        dataStart: Long,
        dataEnd: Long,
    ) {
        val data = pass.bytes(dataStart, dataEnd - dataStart, dataEnd)
        val nul = data.nulFrom(0)
        if (nul < 0) bail(NotRemovableReason.STRUCTURE)
        if (String(data, 0, nul, Charsets.ISO_8859_1) == RAW_PROFILE_EXIF) {
            rawProfile(pass, dataStart + nul + 1, dataEnd, 0)
        }
    }

    /** Komprimierter Text `zTXt`: Schlüsselwort, NUL, Methode (0), komprimierter Text. */
    private fun compressedText(
        pass: ScrubPass,
        dataStart: Long,
        dataEnd: Long,
    ) {
        val data = pass.bytes(dataStart, dataEnd - dataStart, dataEnd)
        val nul = data.nulFrom(0)
        if (nul < 0 || nul + 1 >= data.size || data[nul + 1].toInt() != 0) bail(NotRemovableReason.STRUCTURE)
        val keyword = String(data, 0, nul, Charsets.ISO_8859_1)
        checkCompressed(keyword, inflateLimited(data.copyOfRange(nul + 2, data.size)))
    }

    /**
     * Text-Chunk `iTXt`: Schlüsselwort, NUL, Kompressionsflag, Methode, Sprache, NUL, übersetzter Schlüssel, NUL,
     * Text.
     */
    private fun internationalText(
        pass: ScrubPass,
        dataStart: Long,
        dataEnd: Long,
    ) {
        val data = pass.bytes(dataStart, dataEnd - dataStart, dataEnd)
        val keywordEnd = data.nulFrom(0)
        if (keywordEnd < 0 || keywordEnd + 2 >= data.size) bail(NotRemovableReason.STRUCTURE)
        val keyword = String(data, 0, keywordEnd, Charsets.ISO_8859_1)
        val compressed = data[keywordEnd + 1].toInt() != 0
        val languageEnd = data.nulFrom(keywordEnd + 3)
        if (languageEnd < 0) bail(NotRemovableReason.STRUCTURE)
        val translatedEnd = data.nulFrom(languageEnd + 1)
        if (translatedEnd < 0) bail(NotRemovableReason.STRUCTURE)
        val textStart = dataStart + translatedEnd + 1
        if (compressed) {
            if (data[keywordEnd + 2].toInt() != 0) bail(NotRemovableReason.STRUCTURE)
            checkCompressed(keyword, inflateLimited(data.copyOfRange(translatedEnd + 1, data.size)))
            return
        }
        when (keyword) {
            XMP_KEYWORD -> XmpScrubber.scrubRegion(pass, textStart, dataEnd)
            RAW_PROFILE_EXIF -> rawProfile(pass, textStart, dataEnd, 0)
        }
    }

    /**
     * Prüft einen dekomprimierten Text, ohne ihn zu ändern. Ein Standortbefund heißt `compressed_text`, da er nicht
     * sicher entfernt werden kann.
     */
    private fun checkCompressed(
        keyword: String,
        text: ByteArray,
    ) {
        val asText = String(text, Charsets.ISO_8859_1)
        if (LocationText.containsIn(asText)) bail(NotRemovableReason.COMPRESSED_TEXT)
        when (keyword) {
            XMP_KEYWORD -> {
                if (XmpScrubber.locationSpans(text).isNotEmpty()) bail(NotRemovableReason.COMPRESSED_TEXT)
            }

            RAW_PROFILE_EXIF -> {
                val inner = ScrubPass(MemoryStore(decodeProfile(asText).bytes), mutate = false)
                walkPayload(inner, 0, text.size.toLong(), 0)
                if (inner.findings.isNotEmpty()) bail(NotRemovableReason.COMPRESSED_TEXT)
            }
        }
    }

    /** Raw-Profile im Text einer Datei. Die Bytes werden im Speicher geändert und dann als Hex-Ziffern `0` geschrieben. */
    private fun rawProfile(
        pass: ScrubPass,
        start: Long,
        end: Long,
        depth: Int,
    ) {
        val text = String(pass.bytes(start, end - start, end), Charsets.ISO_8859_1)
        val profile = decodeProfile(text)
        // Kopie: Der Vergleich nach dem Walker braucht die Originalbytes.
        val memory = MemoryStore(profile.bytes.copyOf())
        val inner = ScrubPass(memory, pass.mutate)
        walkPayload(inner, 0, profile.bytes.size.toLong(), depth)
        pass.findings.addAll(inner.findings)
        if (!pass.mutate) return
        val after = memory.bytes()
        for (index in profile.bytes.indices) {
            if (after[index] == profile.bytes[index]) continue
            pass.blank(start + profile.digitAt[2 * index], 1, '0'.code.toByte(), "raw_profile")
            pass.blank(start + profile.digitAt[2 * index + 1], 1, '0'.code.toByte(), "raw_profile")
        }
    }

    /**
     * Format: Zeilenumbruch, Profilname, Zeilenumbruch, Länge in Dezimal, Zeilenumbruch, Hex-Ziffern (Leerraum erlaubt).
     * Die Anzahl der Hex-Ziffern muss genau das Doppelte der Länge sein.
     */
    private fun decodeProfile(text: String): RawProfile {
        if (!text.startsWith("\n")) bail(NotRemovableReason.STRUCTURE)
        val nameEnd = text.indexOf('\n', 1)
        if (nameEnd < 0) bail(NotRemovableReason.STRUCTURE)
        val lengthEnd = text.indexOf('\n', nameEnd + 1)
        if (lengthEnd < 0) bail(NotRemovableReason.STRUCTURE)
        val declared = text.substring(nameEnd + 1, lengthEnd).trim().toIntOrNull() ?: bail(NotRemovableReason.STRUCTURE)
        if (declared <= 0 || declared > ScrubLimits.MAX_TEXT) bail(NotRemovableReason.STRUCTURE)
        val digits = ArrayList<Int>(declared * 2)
        for (index in lengthEnd + 1 until text.length) {
            val char = text[index]
            if (char.isWhitespace()) continue
            if (Character.digit(char, 16) < 0) bail(NotRemovableReason.STRUCTURE)
            digits.add(index)
        }
        if (digits.size != declared * 2) bail(NotRemovableReason.STRUCTURE)
        val bytes =
            ByteArray(declared) { k ->
                val high = Character.digit(text[digits[2 * k]], 16)
                val low = Character.digit(text[digits[2 * k + 1]], 16)
                ((high shl 4) or low).toByte()
            }
        return RawProfile(bytes, digits.toIntArray())
    }

    /** Position des nächsten NUL-Bytes ab [from], oder -1. */
    private fun ByteArray.nulFrom(from: Int): Int {
        for (index in from until size) if (this[index].toInt() == 0) return index
        return -1
    }

    /** TIFF-Nutzlast: optional mit `Exif\0\0`-Präfix. */
    private fun walkPayload(
        pass: ScrubPass,
        start: Long,
        end: Long,
        depth: Int,
    ) {
        val base = if (pass.startsWith(start, end, EXIF_PREFIX)) start + EXIF_PREFIX.size else start
        TiffScrubber.scrub(pass, base, end, depth + 1)
    }

    /** Nach dem IEND darf nur Leere folgen. Alles andere ist ein Anhängsel, das nicht sicher bewertet ist. */
    private fun trailing(
        pass: ScrubPass,
        from: Long,
        size: Long,
    ) {
        if (!JpegScrubber.allZero(pass, from, size)) bail(NotRemovableReason.TRAILING_DATA)
    }

    /** Berechnet die CRC über Typ und Daten neu und schreibt sie hinter die Daten. Nur im Änderungsmodus. */
    private fun writeCrc(
        pass: ScrubPass,
        typeStart: Long,
        dataEnd: Long,
    ) {
        val crc = CRC32()
        var pos = typeStart
        while (pos < dataEnd) {
            val count = minOf(SCAN_CHUNK.toLong(), dataEnd - pos)
            crc.update(pass.bytes(pos, count, dataEnd))
            pos += count
        }
        val value = crc.value
        val bytes =
            byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())
        pass.store.write(dataEnd, bytes)
    }
}
