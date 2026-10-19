package io.github.labushuya.emberlight.share.space.location

/**
 * JPEG-Scrubber (ADR 0015, Fundorte). Segment-Walker über die Marker. Gelesen werden nur die Segmente mit
 * Metadaten. Entropie-kodierte Bilddaten werden bis zum nächsten Marker nur gestreamt durchlaufen, nie geladen.
 *
 * - APP1 `Exif`: TIFF-Walker. APP1 XMP: XMP-Scrubber. APP1 Extended XMP: nur prüfen, bei Standort nicht bereinigbar.
 * - APP13 Photoshop-IRB mit IPTC-IIM: Ortsfelder 2:90, 2:92, 2:95, 2:100, 2:101 mit 0x20 überschreiben.
 * - APP2 MPF: Zusatzbilder über die MPF-Offsets rekursiv als JPEG.
 * - Daten nach EOI: ein angehängtes MP4 (Motion Photo) über den MP4-Scrubber, bis zu seinem logischen Ende. Alles danach
 *   wird abgeschnitten (ADR 0015, Anhänge). Lücken zwischen zwei bekannten Teilen bleiben `trailing_data`.
 */
internal object JpegScrubber {
    private val EXIF_ID = ascii("Exif\u0000\u0000")
    private val XMP_ID = ascii("http://ns.adobe.com/xap/1.0/\u0000")
    private val XMP_EXTENSION_ID = ascii("http://ns.adobe.com/xmp/extension/\u0000")
    private val MPF_ID = ascii("MPF\u0000")
    private val PHOTOSHOP_ID = ascii("Photoshop 3.0\u0000")
    private val SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    private const val IRB_IPTC = 0x0404
    private const val IRB_XMP = 0x0424
    private const val MPF_INDEX_TAG = 0xB002
    private const val MP_ENTRY_SIZE = 16L
    private const val SCAN_CHUNK = 64 * 1024
    private val IPTC_LOCATION_DATASETS = setOf(90, 92, 95, 100, 101)

    /** Marker vor einem angehängten MP4 (Samsung-artig). Hinter dem Marker beginnt das MP4 (Offset 4 vor `ftyp`). */
    private const val MOTION_MARKER = "MotionPhoto_Data"
    private const val MOTION_HEADER_WINDOW = 64
    private const val MIN_MOTION_HEADER = 8L

    /**
     * Ganze Datei: Hauptbild, MPF-Zusatzbilder und alles, was nach dem EOI folgt. Hinter dem letzten bekannten Teil
     * wird abgeschnitten (ADR 0015, Anhänge).
     */
    fun scrubFile(pass: ScrubPass) {
        val size = pass.store.length
        val afterEoi = scrubImage(pass, 0, size, 0)
        pass.truncate(trailing(pass, afterEoi, size), CUT_FINDING)
    }

    /**
     * Verarbeitet ein JPEG ab [start] bis höchstens [limit] und liefert die Position nach dem EOI. Die Schachtelung
     * ist durch die Tiefe begrenzt (Thumbnail, MPF-Zusatzbild).
     */
    fun scrubImage(
        pass: ScrubPass,
        start: Long,
        limit: Long,
        depth: Int,
    ): Long {
        if (depth > ScrubLimits.MAX_DEPTH) bail(NotRemovableReason.STRUCTURE)
        if (!pass.bytes(start, 2, limit).contentEquals(SOI)) bail(NotRemovableReason.STRUCTURE)
        var pos = start + 2
        while (true) {
            pass.count("jpeg_segments")
            if (pass.u8(pos, limit) != 0xFF) bail(NotRemovableReason.STRUCTURE)
            var markerAt = pos + 1
            while (pass.u8(markerAt, limit) == 0xFF) markerAt++
            val marker = pass.u8(markerAt, limit)
            when {
                marker == 0xD9 -> {
                    return markerAt + 1
                }

                marker == 0x00 || marker == 0xFF -> {
                    bail(NotRemovableReason.STRUCTURE)
                }

                marker in 0xD0..0xD7 || marker == 0x01 -> {
                    pos = markerAt + 1
                }

                marker == 0xDA -> {
                    val headerEnd = segmentEnd(pass, markerAt, limit)
                    pos = entropyEnd(pass, headerEnd, limit)
                }

                else -> {
                    val end = segmentEnd(pass, markerAt, limit)
                    segment(pass, marker, markerAt + 3, end, depth)
                    pos = end
                }
            }
        }
    }

    /** Ende eines Segments mit Längenfeld bei [markerAt]. Die Länge zählt das Feld selbst mit, nicht den Marker. */
    private fun segmentEnd(
        pass: ScrubPass,
        markerAt: Long,
        limit: Long,
    ): Long {
        val length = pass.u16(markerAt + 1, limit, big = true)
        if (length < 2) bail(NotRemovableReason.STRUCTURE)
        val end = markerAt + 1 + length
        pass.bounds(markerAt + 1, length.toLong(), limit)
        return end
    }

    /**
     * Sucht nach den Bilddaten (SOS) den nächsten Marker. Stuffing (FF 00), RST-Marker und Fill-Bytes gehören zu den
     * Daten. Gestreamt in Blöcken, die Daten werden nicht gespeichert.
     */
    private fun entropyEnd(
        pass: ScrubPass,
        from: Long,
        limit: Long,
    ): Long {
        var pos = from
        var previousFf = false
        while (pos < limit) {
            val count = minOf(SCAN_CHUNK.toLong(), limit - pos).toInt()
            val chunk = pass.bytes(pos, count.toLong(), limit)
            for (i in 0 until count) {
                val value = chunk[i].toInt() and 0xFF
                if (previousFf && value != 0x00 && value !in 0xD0..0xD7 && value != 0xFF) {
                    return pos + i - 1
                }
                previousFf = value == 0xFF
            }
            pos += count
        }
        bail(NotRemovableReason.STRUCTURE)
    }

    private fun segment(
        pass: ScrubPass,
        marker: Int,
        payload: Long,
        end: Long,
        depth: Int,
    ) {
        when (marker) {
            0xE1 -> app1(pass, payload, end, depth)
            0xE2 -> app2Mpf(pass, payload, end, depth)
            0xED -> if (pass.startsWith(payload, end, PHOTOSHOP_ID)) irb(pass, payload + PHOTOSHOP_ID.size, end)
        }
    }

    private fun app1(
        pass: ScrubPass,
        payload: Long,
        end: Long,
        depth: Int,
    ) {
        when {
            pass.startsWith(payload, end, EXIF_ID) -> {
                TiffScrubber.scrub(pass, payload + EXIF_ID.size, end, depth + 1)
            }

            pass.startsWith(payload, end, XMP_ID) -> {
                XmpScrubber.scrubRegion(pass, payload + XMP_ID.size, end)
            }

            pass.startsWith(payload, end, XMP_EXTENSION_ID) -> {
                // Erweitertes XMP wird nur geprüft: Standort darin ist nicht sicher entfernbar.
                val check = ScrubPass(pass.store, mutate = false)
                XmpScrubber.scrubRegion(check, payload + XMP_EXTENSION_ID.size, end)
                if (check.findings.isNotEmpty()) bail(NotRemovableReason.EXTENDED_XMP)
            }
        }
    }

    /** Photoshop-Ressourcen (8BIM): IPTC-IIM (0x0404) und XMP (0x0424). */
    private fun irb(
        pass: ScrubPass,
        first: Long,
        end: Long,
    ) {
        var p = first
        while (p < end) {
            pass.count("irb_resources")
            if (pass.bytes(p, 4, end).toString(Charsets.ISO_8859_1) != "8BIM") bail(NotRemovableReason.STRUCTURE)
            val id = pass.u16(p + 4, end, big = true)
            val nameLength = pass.u8(p + 6, end)
            // Pascal-Name: Längenbyte plus Text, insgesamt auf gerade Länge gepolstert.
            val nameField = 1 + nameLength + ((1 + nameLength) % 2)
            val sizeAt = p + 6 + nameField
            val size = pass.u32Be(sizeAt, end)
            val dataStart = sizeAt + 4
            pass.bounds(dataStart, size, end)
            when (id) {
                IRB_IPTC -> iptc(pass, dataStart, dataStart + size)
                IRB_XMP -> XmpScrubber.scrubRegion(pass, dataStart, dataStart + size)
            }
            p = dataStart + size + (size % 2)
        }
    }

    /** IPTC-IIM-Datensätze. Ortsfelder des Records 2 werden mit 0x20 überschrieben, Längen bleiben gleich. */
    private fun iptc(
        pass: ScrubPass,
        first: Long,
        end: Long,
    ) {
        var p = first
        while (p < end) {
            pass.count("iim_datasets")
            if (pass.u8(p, end) != 0x1C) {
                // Auffüllung am Ende ist erlaubt, sonst ist die Struktur unklar.
                if (allZero(pass, p, end)) return
                bail(NotRemovableReason.STRUCTURE)
            }
            val record = pass.u8(p + 1, end)
            val dataset = pass.u8(p + 2, end)
            val length = pass.u16(p + 3, end, big = true)
            // Erweiterte Länge (Bit 15) kommt in Ressourcen nicht vor und wird nicht geraten.
            if ((length and 0x8000) != 0) bail(NotRemovableReason.STRUCTURE)
            val dataStart = p + 5
            pass.bounds(dataStart, length.toLong(), end)
            if (record == 2 && dataset in IPTC_LOCATION_DATASETS) {
                pass.blank(dataStart, length.toLong(), 0x20, "iptc_location")
            }
            p = dataStart + length
        }
    }

    /**
     * MPF (CIPA DC-007): Das Index-IFD steht in APP2. Die Zusatzbilder ab Index 1 sind eigene JPEGs. Ihre Offsets sind
     * relativ zum MPF-TIFF-Anfang. Jedes Zusatzbild wird rekursiv geprüft.
     */
    private fun app2Mpf(
        pass: ScrubPass,
        payload: Long,
        end: Long,
        depth: Int,
    ) {
        if (!pass.startsWith(payload, end, MPF_ID)) return
        val base = payload + MPF_ID.size
        val big = pass.bytes(base, 2, end).toString(Charsets.ISO_8859_1).let { it == "MM" }
        val ifd = base + pass.u32(base + 4, end, big)
        val entries = TiffScrubber.readIfd(pass, ifd, end, big)
        val index = entries.firstOrNull { it.tag == MPF_INDEX_TAG } ?: return
        val (table, tableSize) = TiffScrubber.valueRange(pass, base, end, big, index)
        val images = tableSize / MP_ENTRY_SIZE
        for (i in 1 until images) {
            pass.count("mpf_images")
            val entry = table + i * MP_ENTRY_SIZE
            val size = pass.u32(entry + 4, end, big)
            val offset = pass.u32(entry + 8, end, big)
            if (offset == 0L) bail(NotRemovableReason.STRUCTURE)
            val start = base + offset
            pass.bounds(start, size, pass.store.length)
            val end = scrubImage(pass, start, start + size, depth + 1)
            pass.claimed.add(start to end)
        }
    }

    /**
     * Eingebettetes JPEG (Thumbnail in TIFF): wie das Hauptbild, aber das Anhängsel nach dem EOI wird innerhalb der
     * eigenen Grenze geprüft. Nicht leere Bytes ergeben `trailing_data`, sie bleiben also nicht ungeprüft stehen.
     */
    fun scrubEmbedded(
        pass: ScrubPass,
        start: Long,
        limit: Long,
        depth: Int,
    ) {
        val afterEoi = scrubImage(pass, start, limit, depth)
        middleGap(pass, afterEoi, limit)
    }

    /**
     * Alles nach dem EOI, abzüglich der MPF-Zusatzbilder, die schon geprüft sind. Liefert das logische Ende: Bis dorthin
     * bleibt die Datei, der Rest wird abgeschnitten (ADR 0015, Anhänge). Lücken zwischen zwei bekannten Teilen lassen
     * sich nicht kürzen und folgen [middleGap].
     */
    private fun trailing(
        pass: ScrubPass,
        from: Long,
        size: Long,
    ): Long {
        var position = from
        for ((start, end) in pass.claimed.sortedBy { it.first }) {
            if (start > position) middleGap(pass, position, start)
            position = maxOf(position, end)
        }
        return tailEnd(pass, position, size)
    }

    /**
     * Lücke zwischen zwei bekannten Teilen. Sie liegt mitten in der Datei und kann nicht abgeschnitten werden. Zulässig
     * sind Nullbytes oder ein MP4 (`ftyp` an Offset 4). Anderes führt zu `trailing_data`.
     */
    private fun middleGap(
        pass: ScrubPass,
        from: Long,
        to: Long,
    ) {
        if (from >= to) return
        if (from + 8 <= to && pass.startsWith(from + 4, to, ascii("ftyp"))) {
            BmffScrubber.scrubTop(pass, from, to)
            return
        }
        if (!allZero(pass, from, to)) bail(NotRemovableReason.TRAILING_DATA)
    }

    /**
     * Ende des letzten bekannten Teils nach dem EOI. Ein angehängtes MP4 bleibt bis zu seinem logischen Ende, alles
     * danach wird abgeschnitten. Ohne MP4 endet das Logische bei [from]: Der ganze Rest wird abgeschnitten.
     */
    private fun tailEnd(
        pass: ScrubPass,
        from: Long,
        size: Long,
    ): Long {
        val video = motionVideoStart(pass, from, size) ?: return from
        return BmffScrubber.scrubRegion(pass, video, size)
    }

    /**
     * Start eines angehängten MP4. Google-Motion-Photo: `ftyp` direkt nach dem EOI (Offset 4). Samsung-artig: ein Kopf
     * mit dem Marker `MotionPhoto_Data`, danach das MP4 (`ftyp` hinter dem Marker, Offset 4 vor `ftyp`). Sonst `null`.
     */
    private fun motionVideoStart(
        pass: ScrubPass,
        from: Long,
        size: Long,
    ): Long? {
        if (from + 8 <= size && pass.startsWith(from + 4, size, ascii("ftyp"))) return from
        val window = minOf(MOTION_HEADER_WINDOW.toLong(), size - from)
        if (window < MIN_MOTION_HEADER) return null
        val head = pass.bytes(from, window, size).toString(Charsets.ISO_8859_1)
        val marker = head.indexOf(MOTION_MARKER)
        if (marker < 0) return null
        val afterMarker = marker + MOTION_MARKER.length
        val ftyp = head.indexOf("ftyp", startIndex = afterMarker + 4)
        if (ftyp < 0) return null
        return from + ftyp - 4
    }

    /** Prüft einen Bereich streamend auf Nullbytes. */
    internal fun allZero(
        pass: ScrubPass,
        from: Long,
        end: Long,
    ): Boolean {
        var pos = from
        while (pos < end) {
            val count = minOf(SCAN_CHUNK.toLong(), end - pos)
            if (pass.bytes(pos, count, end).any { it.toInt() != 0 }) return false
            pos += count
        }
        return true
    }
}
