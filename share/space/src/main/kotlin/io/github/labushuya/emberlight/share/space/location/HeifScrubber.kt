package io.github.labushuya.emberlight.share.space.location

/**
 * HEIC, HEIF und AVIF (ADR 0015, Fundorte). Item-Walker über `meta` (FullBox mit `hdlr` `pict`):
 * - `iinf` mit `infe` (Versionen 2 und 3) ordnet jedem Item seinen Typ zu: `Exif`, oder `mime` mit
 *   `application/rdf+xml` (XMP).
 * - `iloc` (Versionen 0 bis 2) liefert die Extents. Konstruktionsmethode 0 = Datei-Offset, 1 = `idat`. Methode 2 ist
 *   bei Exif- und XMP-Items `unsupported`.
 * - Mehrere Extents bilden ein logisches Item ([ExtentStore]). Exif-Items laufen durch den TIFF-Walker, XMP-Items
 *   durch den XMP-Scrubber. Alle Items dieser Typen werden erfasst, auch Thumbnails und Hilfsbilder.
 * - Items ohne `infe`, doppelte Einträge, mehrfache `iinf`/`iloc`/`idat`/`hdlr` und unbekannte Versionen gelten als
 *   nicht bereinigbar (fail closed).
 *
 * `moov` auf Top-Level (HEIF-Sequenzen, Bursts) wird wie bei MP4 geprüft. Mediendaten (`mdat`) werden nie gelesen.
 */
internal object HeifScrubber {
    private const val EXIF_TYPE = "Exif"
    private const val MIME_TYPE = "mime"
    private const val XMP_CONTENT_TYPE = "application/rdf+xml"
    private const val METHOD_FILE = 0
    private const val METHOD_IDAT = 1
    private const val METHOD_ITEM = 2
    private const val HANDLER_PICTURE = "pict"
    private val EXIF_ID = ascii("Exif\u0000\u0000")
    private val SIZES = setOf(0, 4, 8)

    private class ItemInfo(
        val type: String,
        val contentType: String?,
    )

    private class Extent(
        val offset: Long,
        val length: Long,
    )

    private class Location(
        val itemId: Long,
        val method: Int,
        val baseOffset: Long,
        val extents: List<Extent>,
    )

    /** Ganze Datei ab Offset 0. */
    fun scrubFile(pass: ScrubPass) {
        val end = BmffScrubber.logicalEnd(pass, 0, pass.store.length)
        var p = 0L
        while (p < end) {
            pass.count("heif_boxes")
            val box = BmffScrubber.box(pass, p, end, top = true)
            when (box.type) {
                "ftyp", "mdat", "wide" -> Unit
                "meta" -> meta(pass, box, depth = 1)
                "moov" -> BmffScrubber.moov(pass, box)
                "free", "skip" -> BmffScrubber.freeBox(pass, box)
                "uuid" -> BmffScrubber.uuid(pass, box)
                else -> BmffScrubber.generic(pass, box)
            }
            p = box.end
        }
        pass.truncate(end, CUT_FINDING)
    }

    /** `meta` als FullBox: Version 0, Flags, dann die Kinder. Items werden erst nach allen Kindern verarbeitet. */
    private fun meta(
        pass: ScrubPass,
        box: BmffScrubber.Box,
        depth: Int,
    ) {
        if (depth > ScrubLimits.MAX_DEPTH) bail(NotRemovableReason.STRUCTURE)
        if (pass.u8(box.payload, box.end) != 0) bail(NotRemovableReason.UNSUPPORTED)
        val children = ArrayList<BmffScrubber.Box>()
        var p = box.payload + 4
        while (p < box.end) {
            pass.count("meta_boxes")
            val child = BmffScrubber.box(pass, p, box.end, top = false)
            children.add(child)
            p = child.end
        }
        val handler = single(children, "hdlr") ?: bail(NotRemovableReason.STRUCTURE)
        if (handlerType(pass, handler) != HANDLER_PICTURE) bail(NotRemovableReason.UNSUPPORTED)
        val infos = single(children, "iinf")?.let { itemInfos(pass, it) } ?: emptyMap()
        val idat = single(children, "idat")
        val locations = single(children, "iloc")?.let { itemLocations(pass, it) } ?: emptyList()
        for (child in children) {
            when (child.type) {
                "hdlr", "iinf", "iloc", "idat" -> Unit
                "uuid" -> BmffScrubber.uuid(pass, child)
                else -> BmffScrubber.generic(pass, child)
            }
        }
        for (location in locations) {
            val info = infos[location.itemId] ?: bail(NotRemovableReason.STRUCTURE)
            val isExif = info.type == EXIF_TYPE
            val isXmp = info.type == MIME_TYPE && info.contentType == XMP_CONTENT_TYPE
            if (!isExif && !isXmp) continue
            if (location.method == METHOD_ITEM) bail(NotRemovableReason.UNSUPPORTED)
            val item = pass.view(itemStore(pass, location, idat))
            if (isExif) {
                exif(item, depth)
            } else {
                XmpScrubber.scrubRegion(item, 0, item.store.length)
            }
        }
    }

    /** Das einzige Kind mit [type]. Mehrere gleichartige Kinder sind mehrdeutig und gelten als Strukturfehler. */
    private fun single(
        children: List<BmffScrubber.Box>,
        type: String,
    ): BmffScrubber.Box? {
        val matches = children.filter { it.type == type }
        if (matches.size > 1) bail(NotRemovableReason.STRUCTURE)
        return matches.firstOrNull()
    }

    /** Handler-Typ einer `hdlr`-FullBox: Version und Flags, Pre-defined, dann der Typ. */
    private fun handlerType(
        pass: ScrubPass,
        box: BmffScrubber.Box,
    ): String = pass.bytes(box.payload + 8, 4, box.end).toString(Charsets.ISO_8859_1)

    /**
     * Exif-Item (ADR 0015, digiKam `dmetadata_libheif.cpp`): Die ersten vier Bytes sind der BE-Offset des TIFF-Headers,
     * gezählt ab dem Ende dieser vier Bytes. Steht dort ein `Exif\0\0`-Präfix, beginnt das TIFF erst danach.
     */
    private fun exif(
        pass: ScrubPass,
        depth: Int,
    ) {
        val end = pass.store.length
        if (end < 4) bail(NotRemovableReason.STRUCTURE)
        var base = 4 + pass.u32Be(0, end)
        if (base > end) bail(NotRemovableReason.STRUCTURE)
        if (pass.startsWith(base, end, EXIF_ID)) base += EXIF_ID.size
        TiffScrubber.scrub(pass, base, end, depth + 1)
    }

    /** `iinf`: Zähler (16 Bit bei Version 0, sonst 32 Bit), dann die `infe`-Kinder. */
    private fun itemInfos(
        pass: ScrubPass,
        box: BmffScrubber.Box,
    ): Map<Long, ItemInfo> {
        val version = pass.u8(box.payload, box.end)
        if (version > 1) bail(NotRemovableReason.UNSUPPORTED)
        val count: Long
        var p = box.payload + 4
        if (version == 0) {
            count = pass.u16(p, box.end, big = true).toLong()
            p += 2
        } else {
            count = pass.u32Be(p, box.end)
            p += 4
        }
        val infos = HashMap<Long, ItemInfo>()
        for (index in 0 until count) {
            pass.count("iinf_entries")
            val entry = BmffScrubber.box(pass, p, box.end, top = false)
            if (entry.type != "infe") bail(NotRemovableReason.STRUCTURE)
            val (id, info) = itemInfo(pass, entry)
            if (infos.put(id, info) != null) bail(NotRemovableReason.STRUCTURE)
            p = entry.end
        }
        return infos
    }

    /**
     * `infe` (FullBox): Version 2 mit 16-Bit-ID, Version 3 mit 32-Bit-ID. Dann Schutz-Index, Typ (4 Zeichen), Name
     * (NUL-terminiert) und bei Typ `mime` der Content-Type (NUL-terminiert). Versionen 0 und 1 kennen keinen Typ und
     * sind nicht unterstützt.
     */
    private fun itemInfo(
        pass: ScrubPass,
        box: BmffScrubber.Box,
    ): Pair<Long, ItemInfo> {
        val version = pass.u8(box.payload, box.end)
        val id: Long
        val typeAt: Long
        when (version) {
            2 -> {
                id = pass.u16(box.payload + 4, box.end, big = true).toLong()
                typeAt = box.payload + 8
            }

            3 -> {
                id = pass.u32Be(box.payload + 4, box.end)
                typeAt = box.payload + 10
            }

            else -> {
                bail(NotRemovableReason.UNSUPPORTED)
            }
        }
        val type = pass.bytes(typeAt, 4, box.end).toString(Charsets.ISO_8859_1)
        val nameEnd = nulIndex(pass, typeAt + 4, box.end)
        val contentType =
            if (type == MIME_TYPE) {
                val contentStart = nameEnd + 1
                val contentEnd = nulIndex(pass, contentStart, box.end)
                pass.bytes(contentStart, contentEnd - contentStart, box.end).toString(Charsets.ISO_8859_1)
            } else {
                null
            }
        return id to ItemInfo(type, contentType)
    }

    /** Position des nächsten NUL-Bytes ab [from], innerhalb von [end]. Fehlt es, gilt die Struktur als defekt. */
    private fun nulIndex(
        pass: ScrubPass,
        from: Long,
        end: Long,
    ): Long {
        var p = from
        while (pass.u8(p, end) != 0) p++
        return p
    }

    /**
     * `iloc`: Versionen 0 bis 2. Größen je 0, 4 oder 8 Bytes. Die Länge (`length_size`) muss angegeben sein, sonst
     * gilt der Eintrag als nicht unterstützt. `data_reference_index` ungleich 0 (fremde Datei) ist nicht unterstützt.
     */
    private fun itemLocations(
        pass: ScrubPass,
        box: BmffScrubber.Box,
    ): List<Location> {
        val version = pass.u8(box.payload, box.end)
        if (version > 2) bail(NotRemovableReason.UNSUPPORTED)
        val end = box.end
        var p = box.payload + 4
        val sizes = pass.u8(p, end)
        p += 1
        val offsetSize = sizes shr 4
        val lengthSize = sizes and 0x0F
        val baseSizes = pass.u8(p, end)
        p += 1
        val baseOffsetSize = baseSizes shr 4
        val indexSize = if (version > 0) baseSizes and 0x0F else 0
        if (offsetSize !in SIZES || lengthSize !in SIZES || baseOffsetSize !in SIZES || indexSize !in SIZES) {
            bail(NotRemovableReason.STRUCTURE)
        }
        val count: Long
        if (version < 2) {
            count = pass.u16(p, end, big = true).toLong()
            p += 2
        } else {
            count = pass.u32Be(p, end)
            p += 4
        }
        val locations = ArrayList<Location>()
        for (index in 0 until count) {
            pass.count("iloc_items")
            val itemId: Long
            if (version < 2) {
                itemId = pass.u16(p, end, big = true).toLong()
                p += 2
            } else {
                itemId = pass.u32Be(p, end)
                p += 4
            }
            var method = METHOD_FILE
            if (version >= 1) {
                method = pass.u16(p, end, big = true) and 0x000F
                p += 2
            }
            if (pass.u16(p, end, big = true) != 0) bail(NotRemovableReason.UNSUPPORTED)
            p += 2
            val baseOffset = readSized(pass, p, baseOffsetSize, end)
            p += baseOffsetSize
            val extentCount = pass.u16(p, end, big = true)
            p += 2
            val extents = ArrayList<Extent>(extentCount)
            for (extentIndex in 0 until extentCount) {
                pass.count("iloc_extents")
                // Der Extent-Index ist nur für die Konstruktionsmethoden 1 und 2 belegt. Er wird hier übersprungen.
                if (version >= 1 && indexSize > 0) p += indexSize
                val offset = readSized(pass, p, offsetSize, end)
                p += offsetSize
                if (lengthSize == 0) bail(NotRemovableReason.UNSUPPORTED)
                val length = readSized(pass, p, lengthSize, end)
                p += lengthSize
                extents.add(Extent(offset, length))
            }
            locations.add(Location(itemId, method, baseOffset, extents))
        }
        return locations
    }

    /** Ganzzahl mit [size] Bytes (0, 4 oder 8). Werte ab 2^63 sind nicht darstellbar und gelten als Strukturfehler. */
    private fun readSized(
        pass: ScrubPass,
        at: Long,
        size: Int,
        end: Long,
    ): Long =
        when (size) {
            0 -> {
                0L
            }

            4 -> {
                pass.u32Be(at, end)
            }

            8 -> {
                val high = pass.u32Be(at, end)
                val low = pass.u32Be(at + 4, end)
                if (high > 0x7FFF_FFFFL) bail(NotRemovableReason.STRUCTURE)
                (high shl 32) or low
            }

            else -> {
                bail(NotRemovableReason.STRUCTURE)
            }
        }

    /**
     * Logische Sicht auf ein Item mit mehreren Extents. Die physische Lage liegt in [segments] in logischer Reihenfolge.
     * Lesen und Schreiben laufen über die physischen Bereiche, die Länge bleibt gleich.
     */
    private fun itemStore(
        pass: ScrubPass,
        location: Location,
        idat: BmffScrubber.Box?,
    ): ExtentStore {
        val segments = ArrayList<ExtentStore.Segment>(location.extents.size)
        var total = 0L
        for (extent in location.extents) {
            val start =
                when (location.method) {
                    METHOD_FILE -> {
                        val at = Math.addExact(location.baseOffset, extent.offset)
                        pass.bounds(at, extent.length, pass.store.length)
                        at
                    }

                    METHOD_IDAT -> {
                        val box = idat ?: bail(NotRemovableReason.STRUCTURE)
                        val at = Math.addExact(box.payload, Math.addExact(location.baseOffset, extent.offset))
                        pass.bounds(at, extent.length, box.end)
                        at
                    }

                    else -> {
                        bail(NotRemovableReason.UNSUPPORTED)
                    }
                }
            total += extent.length
            if (total > ScrubLimits.MAX_READ) bail(NotRemovableReason.STRUCTURE)
            segments.add(ExtentStore.Segment(start, extent.length))
        }
        return ExtentStore(pass.store, segments)
    }
}

/**
 * Zusammengesetzte Sicht auf die Extents eines Items. Die Länge ist die Summe der Segmente. Lesen und Schreiben an
 * logischen Positionen werden auf die physischen Bereiche des [base]-Speichers abgebildet.
 */
internal class ExtentStore(
    private val base: ByteStore,
    private val segments: List<Segment>,
) : ByteStore {
    /** Ein physischer Bereich: Start in [base], Länge. */
    internal class Segment(
        val start: Long,
        val length: Long,
    )

    override val length: Long = segments.sumOf { it.length }

    override fun read(
        offset: Long,
        count: Int,
    ): ByteArray {
        val out = ByteArray(count)
        forEachPart(offset, count) { physical, index, size ->
            base.read(physical, size).copyInto(out, destinationOffset = index)
        }
        return out
    }

    override fun write(
        offset: Long,
        bytes: ByteArray,
    ) {
        forEachPart(offset, bytes.size) { physical, index, size ->
            base.write(physical, bytes.copyOfRange(index, index + size))
        }
    }

    /** Zerlegt den logischen Bereich [offset, offset + count) in physische Teile und ruft [action] für jeden auf. */
    private inline fun forEachPart(
        offset: Long,
        count: Int,
        action: (physical: Long, index: Int, size: Int) -> Unit,
    ) {
        var segmentStart = 0L
        var done = 0
        for (segment in segments) {
            if (done == count) break
            val segmentEnd = segmentStart + segment.length
            val position = offset + done
            if (position < segmentEnd) {
                val inside = position - segmentStart
                val size = minOf(segment.length - inside, (count - done).toLong()).toInt()
                action(segment.start + inside, done, size)
                done += size
            }
            segmentStart = segmentEnd
        }
        check(done == count) { "Logischer Bereich liegt außerhalb des Items." }
    }
}
