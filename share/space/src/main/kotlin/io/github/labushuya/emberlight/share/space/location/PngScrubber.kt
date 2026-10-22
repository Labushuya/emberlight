package io.github.labushuya.emberlight.share.space.location

import java.util.zip.CRC32

/**
 * PNG-Scrubber (ADR 0015, Fundorte, W3C PNG-3, Nachtrag 3.6d). Chunk-Walker. Gelesen werden nur die Metadaten-Chunks,
 * nie IDAT.
 *
 * - `eXIf`: TIFF-Walker (GPS wird geleert, der übrige Exif-Inhalt bleibt).
 * - `tEXt` oder unkomprimiertes `iTXt` mit `Raw profile type exif`: Hex-Payload dekodieren, TIFF-Walker auf den
 *   Bytes, geänderte Bytes als `0` in den Hex-Text zurückschreiben. Die Länge bleibt gleich.
 * - `iTXt` `XML:com.adobe.xmp` unkomprimiert: XMP-Scrubber.
 * - Andere `Raw profile type …` (etwa iptc, app1, xmp), unkomprimiert: Hex-Ziffern werden durch `0` ersetzt.
 * - Text mit einem bekannten Schlüsselwort ([KNOWN_TEXT_KEYWORDS], etwa `Title`, `Comment`, `Software`): bleibt. Ein
 *   binärer TIFF-Befund darin wird geleert, sonst läuft der Text durch die Textsuche der Nachkontrolle.
 * - Text mit unbekanntem Schlüsselwort und komprimierter Text (`zTXt`, komprimiertes `iTXt`) werden immer neutralisiert
 *   ([neutralize]): Der Typ wird in den privaten, unbekannten Ancillary-Typ [NEUTRAL_TYPE] geändert, der Inhalt mit
 *   Nullen geleert. Decoder überspringen solche Chunks sicher. Die Länge bleibt gleich.
 * - Unbekannte Ancillary-Chunks (nicht in [KNOWN_CHUNKS], etwa `caBX`) werden ebenso neutralisiert. Ein unbekannter
 *   kritischer Chunk (Großbuchstabe am Anfang) ist nicht lesbar und lehnt den Upload ab (`unsupported`).
 *
 * Nach jeder Änderung eines Chunks wird seine CRC über Typ und Daten neu berechnet.
 */
internal object PngScrubber {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val EXIF_PREFIX = ascii("Exif\u0000\u0000")
    private const val RAW_PROFILE_EXIF = "Raw profile type exif"
    private const val RAW_PROFILE_PREFIX = "Raw profile type "
    private const val XMP_KEYWORD = "XML:com.adobe.xmp"
    private const val SCAN_CHUNK = 64 * 1024
    private const val KNOWN_TEXT_FINDING = "text_location_binary"
    private const val NEUTRAL_FINDING = "text_neutralized"

    /**
     * Neutraler Typ: `e` (Ancillary), `m` (privat), `B` (Großbuchstabe an dritter Stelle, PNG 3 §5.4), `l` (sicher zu
     * kopieren). Decoder kennen ihn nicht und überspringen ihn.
     */
    private val NEUTRAL_TYPE = ascii("emBl")

    /** Schlüsselwörter der PNG-Spezifikation (§11.3.4.3) und die bekannten Schlüsselwörter von XMP. Sie bleiben. */
    private val KNOWN_TEXT_KEYWORDS =
        setOf(
            "Title",
            "Author",
            "Description",
            "Copyright",
            "Creation Time",
            "Software",
            "Disclaimer",
            "Warning",
            "Source",
            "Comment",
        )

    /** Standard-Chunk-Typen, die ein Decoder kennt. Text-Chunks werden in [chunk] gesondert behandelt. */
    private val KNOWN_CHUNKS =
        setOf(
            "IHDR",
            "PLTE",
            "IDAT",
            "IEND",
            "tRNS",
            "gAMA",
            "cHRM",
            "sRGB",
            "iCCP",
            "sBIT",
            "bKGD",
            "hIST",
            "pHYs",
            "sPLT",
            "tIME",
            "acTL",
            "fcTL",
            "fdAT",
            "cICP",
            "mDCv",
            "cLLI",
            "eXIf",
            "tEXt",
            "zTXt",
            "iTXt",
        )

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
            val typeAt = p + 4
            val type = pass.bytes(typeAt, 4, size).toString(Charsets.ISO_8859_1)
            if (!type.all { it in 'A'..'Z' || it in 'a'..'z' }) bail(NotRemovableReason.STRUCTURE)
            val dataStart = p + 8
            val dataEnd = dataStart + length
            val chunkEnd = dataEnd + 4
            pass.bounds(dataStart, length + 4, size)
            val writesBefore = pass.writes
            if (type == "IEND") {
                // Nach IEND folgt kein Bildteil mehr. Ein Anhang (etwa HTML) wird abgeschnitten (ADR 0015, Anhänge).
                pass.truncate(chunkEnd, CUT_FINDING)
                return
            }
            chunk(pass, type, typeAt, dataStart, dataEnd)
            if (pass.mutate && pass.writes != writesBefore) writeCrc(pass, typeAt, dataEnd)
            p = chunkEnd
        }
    }

    private fun chunk(
        pass: ScrubPass,
        type: String,
        typeAt: Long,
        dataStart: Long,
        dataEnd: Long,
    ) {
        when {
            type == "eXIf" -> walkPayload(pass, dataStart, dataEnd, 0)

            type == "tEXt" -> text(pass, typeAt, dataStart, dataEnd)

            type == "zTXt" -> neutralize(pass, typeAt, dataStart, dataEnd, NEUTRAL_FINDING)

            type == "iTXt" -> internationalText(pass, typeAt, dataStart, dataEnd)

            type in KNOWN_CHUNKS -> Unit

            // Unbekannter kritischer Chunk: Ein Decoder muss ihn verstehen, sonst ist das Bild nicht lesbar.
            type[0] in 'A'..'Z' -> bail(NotRemovableReason.UNSUPPORTED)

            else -> neutralize(pass, typeAt, dataStart, dataEnd, NEUTRAL_FINDING)
        }
    }

    /**
     * Macht einen Chunk unsichtbar: Typ in [NEUTRAL_TYPE], Daten mit Nullen. Die Länge bleibt, CRC wird danach neu
     * berechnet ([writeCrc]).
     */
    private fun neutralize(
        pass: ScrubPass,
        typeAt: Long,
        dataStart: Long,
        dataEnd: Long,
        finding: String,
    ) {
        pass.overwrite(typeAt, NEUTRAL_TYPE, finding)
        pass.blank(dataStart, dataEnd - dataStart, 0, finding)
    }

    /** Text-Chunk `tEXt`: Schlüsselwort, NUL, Text. */
    private fun text(
        pass: ScrubPass,
        typeAt: Long,
        dataStart: Long,
        dataEnd: Long,
    ) {
        val data = pass.bytes(dataStart, dataEnd - dataStart, dataEnd)
        val nul = data.nulFrom(0)
        if (nul < 0) bail(NotRemovableReason.STRUCTURE)
        val keyword = String(data, 0, nul, Charsets.ISO_8859_1)
        val valueStart = dataStart + nul + 1
        when {
            keyword == RAW_PROFILE_EXIF -> rawProfile(pass, valueStart, dataEnd, 0)
            keyword.startsWith(RAW_PROFILE_PREFIX) -> blankRawProfile(pass, valueStart, dataEnd)
            keyword in KNOWN_TEXT_KEYWORDS -> knownText(pass, valueStart, dataEnd)
            else -> neutralize(pass, typeAt, dataStart, dataEnd, NEUTRAL_FINDING)
        }
    }

    /**
     * Text mit bekanntem Schlüsselwort. Liegt darin ein TIFF mit GPS-Befund, roh oder als Hex-Profil, wird der ganze
     * Textwert mit Nullen geleert. Sonst bleibt er; ein Standort als Text fällt in der Nachkontrolle auf.
     */
    private fun knownText(
        pass: ScrubPass,
        start: Long,
        end: Long,
    ) {
        val raw = pass.bytes(start, end - start, end)
        val candidates = ArrayList<ByteArray>(2).apply { add(raw) }
        try {
            candidates.add(decodeProfile(String(raw, Charsets.ISO_8859_1)).bytes)
        } catch (notAProfile: Unremovable) {
            // Kein Hex-Profil: Nur der Rohtext zählt.
        }
        if (candidates.none { StrictLocationProbe.containsBinaryLocation(MemoryStore(it)) }) return
        pass.blank(start, end - start, 0, KNOWN_TEXT_FINDING)
    }

    /** Anderes Raw-Profile: Jede Hex-Ziffer wird durch `0` ersetzt. Leerraum bleibt, damit die Ziffernzahl stimmt. */
    private fun blankRawProfile(
        pass: ScrubPass,
        start: Long,
        end: Long,
    ) {
        val text = String(pass.bytes(start, end - start, end), Charsets.ISO_8859_1)
        val profile = decodeProfile(text)
        for (index in profile.digitAt) {
            pass.blank(start + index, 1, '0'.code.toByte(), "raw_profile_other")
        }
    }

    /**
     * Text-Chunk `iTXt`: Schlüsselwort, NUL, Kompressionsflag, Methode, Sprache, NUL, übersetzter Schlüssel, NUL,
     * Text. Komprimierter Text wird immer neutralisiert.
     */
    private fun internationalText(
        pass: ScrubPass,
        typeAt: Long,
        dataStart: Long,
        dataEnd: Long,
    ) {
        val data = pass.bytes(dataStart, dataEnd - dataStart, dataEnd)
        val keywordEnd = data.nulFrom(0)
        if (keywordEnd < 0 || keywordEnd + 2 >= data.size) bail(NotRemovableReason.STRUCTURE)
        val keyword = String(data, 0, keywordEnd, Charsets.ISO_8859_1)
        if (data[keywordEnd + 1].toInt() != 0) {
            neutralize(pass, typeAt, dataStart, dataEnd, NEUTRAL_FINDING)
            return
        }
        val languageEnd = data.nulFrom(keywordEnd + 3)
        if (languageEnd < 0) bail(NotRemovableReason.STRUCTURE)
        val translatedEnd = data.nulFrom(languageEnd + 1)
        if (translatedEnd < 0) bail(NotRemovableReason.STRUCTURE)
        val textStart = dataStart + translatedEnd + 1
        when {
            keyword == XMP_KEYWORD -> XmpScrubber.scrubRegion(pass, textStart, dataEnd)
            keyword == RAW_PROFILE_EXIF -> rawProfile(pass, textStart, dataEnd, 0)
            keyword.startsWith(RAW_PROFILE_PREFIX) -> blankRawProfile(pass, textStart, dataEnd)
            keyword in KNOWN_TEXT_KEYWORDS -> knownText(pass, textStart, dataEnd)
            else -> neutralize(pass, typeAt, dataStart, dataEnd, NEUTRAL_FINDING)
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

    /**
     * Berechnet die CRC über Typ und Daten neu und schreibt sie hinter die Daten. Nur im Änderungsmodus. [typeStart] ist
     * die Position des Typs im Chunk.
     */
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
