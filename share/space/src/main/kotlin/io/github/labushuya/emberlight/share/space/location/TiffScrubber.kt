package io.github.labushuya.emberlight.share.space.location

/**
 * Gemeinsamer TIFF/IFD-Walker für alle EXIF-Vorkommen (ADR 0015, Fundorte): JPEG-APP1, PNG `eXIf` und Raw-Profile,
 * WebP `EXIF`. Er prüft IFD0, das Exif-IFD (MakerNote), das GPS-IFD und das IFD1-Thumbnail (eigenes JPEG, rekursiv).
 *
 * Vorgaben: GPS-IFD mit Entry-Count 0, alle Eintragsbytes, Next-Zeiger und ausgelagerte Werte nullen. Den Zeiger
 * `GPSInfo` (0x8825) in IFD0 lassen. MakerNote (0x927C) vollständig mit 0x00 überschreiben.
 */
internal object TiffScrubber {
    /** Bytes je Wert nach TIFF-Typ, Index = Typ-Nummer. Typ 0 und Typen über 12 sind unbekannt. */
    private val TYPE_SIZE = intArrayOf(0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8)
    private const val TAG_THUMBNAIL_OFFSET = 0x0201
    private const val TAG_THUMBNAIL_LENGTH = 0x0202
    private const val TAG_EXIF_IFD = 0x8769
    private const val TAG_GPS_IFD = 0x8825
    private const val TAG_MAKER_NOTE = 0x927C
    private const val ENTRY_SIZE = 12L
    private val TIFF_LE = byteArrayOf(0x49, 0x49, 0x2A, 0x00)
    private val TIFF_BE = byteArrayOf(0x4D, 0x4D, 0x00, 0x2A)

    /**
     * Ein IFD-Eintrag. [at] ist die absolute Position des Eintrags, [size] die Länge des Werts in Bytes oder -1 bei
     * unbekanntem Typ.
     */
    internal class Entry(
        val tag: Int,
        val type: Int,
        val count: Long,
        val at: Long,
        val size: Long,
    )

    /** Walker ab dem TIFF-Header bei [base], begrenzt durch [end]. */
    fun scrub(
        pass: ScrubPass,
        base: Long,
        end: Long,
        depth: Int,
    ) {
        if (depth > ScrubLimits.MAX_DEPTH) bail(NotRemovableReason.STRUCTURE)
        val big = byteOrder(pass, base, end)
        if (pass.u16(base + 2, end, big) != 42) bail(NotRemovableReason.STRUCTURE)
        val seen = HashSet<Long>()
        var ifdOffset = pass.u32(base + 4, end, big)
        while (ifdOffset != 0L) {
            if (!seen.add(ifdOffset)) bail(NotRemovableReason.STRUCTURE)
            val ifd = base + ifdOffset
            val entries = readIfd(pass, ifd, end, big)
            for (entry in entries) {
                when (entry.tag) {
                    TAG_EXIF_IFD -> walkExif(pass, base, base + scalar(pass, entry, end, big), end, big)
                    TAG_GPS_IFD -> clearGps(pass, base, base + scalar(pass, entry, end, big), end, big)
                }
            }
            thumbnail(pass, base, end, big, entries, depth)
            ifdOffset = pass.u32(ifd + 2 + entries.size * ENTRY_SIZE, end, big)
        }
    }

    /** Liest die Einträge eines IFD. Der Zähler und jeder Eintrag laufen über das Budget. */
    internal fun readIfd(
        pass: ScrubPass,
        ifd: Long,
        end: Long,
        big: Boolean,
    ): List<Entry> {
        val count = pass.u16(ifd, end, big).toLong()
        pass.bounds(ifd, 2 + count * ENTRY_SIZE + 4, end)
        val entries = ArrayList<Entry>(count.toInt())
        for (i in 0 until count) {
            pass.count("tiff_entries")
            val at = ifd + 2 + i * ENTRY_SIZE
            val tag = pass.u16(at, end, big)
            val type = pass.u16(at + 2, end, big)
            val valueCount = pass.u32(at + 4, end, big)
            val size = if (type in 1..12) valueCount * TYPE_SIZE[type] else -1L
            entries.add(Entry(tag, type, valueCount, at, size))
        }
        return entries
    }

    /** Position und Länge des Werts eines Eintrags. Werte bis 4 Bytes stehen im Eintrag, größere sind ausgelagert. */
    internal fun valueRange(
        pass: ScrubPass,
        base: Long,
        end: Long,
        big: Boolean,
        entry: Entry,
    ): Pair<Long, Long> {
        if (entry.size < 0) bail(NotRemovableReason.STRUCTURE)
        if (entry.size <= 4) return entry.at + 8 to entry.size
        val start = base + pass.u32(entry.at + 8, end, big)
        pass.bounds(start, entry.size, end)
        return start to entry.size
    }

    private fun byteOrder(
        pass: ScrubPass,
        base: Long,
        end: Long,
    ): Boolean =
        when (String(pass.bytes(base, 2, end), Charsets.ISO_8859_1)) {
            "MM" -> true
            "II" -> false
            else -> bail(NotRemovableReason.STRUCTURE)
        }

    /** `true`, wenn bei [at] ein TIFF-Header (`II*\0` oder `MM\0*`) steht. Ohne Strukturprüfung. */
    fun hasHeader(
        pass: ScrubPass,
        at: Long,
        end: Long,
    ): Boolean {
        if (end - at < 4) return false
        val head = pass.bytes(at, 4, end)
        return head.contentEquals(TIFF_LE) || head.contentEquals(TIFF_BE)
    }

    /** Ganzzahliger Wert eines Pointer- oder Längen-Eintrags (SHORT oder LONG, einzeln). */
    private fun scalar(
        pass: ScrubPass,
        entry: Entry,
        end: Long,
        big: Boolean,
    ): Long =
        when (entry.type) {
            3 -> pass.u16(entry.at + 8, end, big).toLong()
            4 -> pass.u32(entry.at + 8, end, big)
            else -> bail(NotRemovableReason.STRUCTURE)
        }

    private fun walkExif(
        pass: ScrubPass,
        base: Long,
        ifd: Long,
        end: Long,
        big: Boolean,
    ) {
        for (entry in readIfd(pass, ifd, end, big)) {
            if (entry.tag == TAG_MAKER_NOTE) {
                val (start, length) = valueRange(pass, base, end, big, entry)
                pass.blank(start, length, 0x00, "maker_note")
            }
        }
    }

    /** Leert das GPS-IFD: Zähler, Einträge, ausgelagerte Werte und Next-Zeiger. Der Zeiger in IFD0 bleibt. */
    private fun clearGps(
        pass: ScrubPass,
        base: Long,
        ifd: Long,
        end: Long,
        big: Boolean,
    ) {
        val entries = readIfd(pass, ifd, end, big)
        for (entry in entries) {
            // Unbekannter Typ (Größe -1) oder ausgelagerter Wert: valueRange wirft bei unbekanntem Typ.
            if (entry.size !in 0..4) {
                val (start, length) = valueRange(pass, base, end, big, entry)
                pass.blank(start, length, 0x00, "gps_value")
            }
        }
        pass.blank(ifd, 2, 0x00, "gps_ifd")
        pass.blank(ifd + 2, entries.size * ENTRY_SIZE + 4, 0x00, "gps_ifd")
    }

    /** Eingebettetes Thumbnail-JPEG (IFD1 oder ein weiteres IFD der Kette). Es wird als eigenes JPEG geprüft. */
    private fun thumbnail(
        pass: ScrubPass,
        base: Long,
        end: Long,
        big: Boolean,
        entries: List<Entry>,
        depth: Int,
    ) {
        val offset = entries.firstOrNull { it.tag == TAG_THUMBNAIL_OFFSET } ?: return
        val length = entries.firstOrNull { it.tag == TAG_THUMBNAIL_LENGTH } ?: return
        val start = base + scalar(pass, offset, end, big)
        val size = scalar(pass, length, end, big)
        if (size == 0L) return
        pass.bounds(start, size, end)
        JpegScrubber.scrubEmbedded(pass, start, start + size, depth + 1)
    }
}
