package io.github.labushuya.emberlight.share.space.location

/**
 * ISO-BMFF-Scrubber für MP4, MOV und 3GP (ADR 0015, Fundorte). Box-Walker mit 64-bit-Größen, `size 0` bis zum Ende,
 * Grenzen und Schachtelungstiefe.
 *
 * - `udta` (auch in `trak`): `©xyz` (Text ab Offset 4), `loci` (3GPP). `meta` mit `keys` und `ilst`: Items der Keys
 *   `com.apple.quicktime.location.*` und `©xyz` werden in der `data`-Nutzlast überschrieben. `keys` und Indizes
 *   bleiben unverändert.
 * - XMP-`uuid`: XMP-Scrubber. `free`/`skip` mit Standort: `free_box_location`.
 * - Metadaten-Tracks (`gpmd`, `camm`, `mebx` mit `location`, Handler `meta`): `metadata_track`.
 * - Alles, was nicht eingeordnet werden kann und Standort-Text enthält: `unsupported`.
 *
 * Mediendaten (`mdat`) werden nie gelesen.
 */
internal object BmffScrubber {
    private val XMP_UUID =
        byteArrayOf(
            0xBE.toByte(),
            0x7A,
            0xCF.toByte(),
            0xCB.toByte(),
            0x97.toByte(),
            0xA9.toByte(),
            0x42,
            0xE8.toByte(),
            0x9C.toByte(),
            0x71,
            0x99.toByte(),
            0x94.toByte(),
            0x91.toByte(),
            0xE3.toByte(),
            0xAF.toByte(),
            0xAC.toByte(),
        )
    private const val COPYRIGHT_XYZ = "©xyz"
    private const val LOCATION_KEY_PREFIX = "com.apple.quicktime.location."
    private val METADATA_SAMPLE_TYPES = setOf("gpmd", "camm")

    private class Box(
        val type: String,
        val start: Long,
        val payload: Long,
        val end: Long,
    )

    private class SampleEntry(
        val type: String,
        val start: Long,
        val end: Long,
    )

    /** Sammelt Angaben je `trak`: Handler-Typ und Sample-Einträge. */
    private class Track {
        var handler: String? = null
        val entries = ArrayList<SampleEntry>()
    }

    /** Ganze Datei ab Offset 0. */
    fun scrubFile(pass: ScrubPass) {
        scrubTop(pass, 0, pass.store.length)
    }

    /** Top-Level-Boxen zwischen [start] und [end]. Wird auch für ein MP4 nach einem JPEG-EOI verwendet. */
    fun scrubTop(
        pass: ScrubPass,
        start: Long,
        end: Long,
    ) {
        var p = start
        while (p < end) {
            pass.count("bmff_boxes")
            val box = box(pass, p, end, top = true)
            when (box.type) {
                "ftyp", "mdat", "wide" -> Unit
                "moov" -> children(pass, box.payload, box.end, 1, null)
                "free", "skip" -> freeBox(pass, box)
                "uuid" -> uuid(pass, box)
                "meta" -> bail(NotRemovableReason.UNSUPPORTED)
                else -> generic(pass, box)
            }
            p = box.end
        }
    }

    private fun box(
        pass: ScrubPass,
        start: Long,
        parentEnd: Long,
        top: Boolean,
    ): Box {
        val size32 = pass.u32Be(start, parentEnd)
        val type = pass.bytes(start + 4, 4, parentEnd).toString(Charsets.ISO_8859_1)
        var payload = start + 8
        val end =
            when (size32) {
                1L -> {
                    val large = (pass.u32Be(start + 8, parentEnd) shl 32) or pass.u32Be(start + 12, parentEnd)
                    if (large < 16) bail(NotRemovableReason.STRUCTURE)
                    payload = start + 16
                    start + large
                }

                0L -> {
                    if (!top) bail(NotRemovableReason.STRUCTURE)
                    parentEnd
                }

                else -> {
                    if (size32 < 8) bail(NotRemovableReason.STRUCTURE)
                    start + size32
                }
            }
        if (end > parentEnd || end < start) bail(NotRemovableReason.STRUCTURE)
        return Box(type, start, payload, end)
    }

    private fun children(
        pass: ScrubPass,
        start: Long,
        end: Long,
        depth: Int,
        track: Track?,
    ) {
        if (depth > ScrubLimits.MAX_DEPTH) bail(NotRemovableReason.STRUCTURE)
        var p = start
        while (p < end) {
            pass.count("bmff_boxes")
            val box = box(pass, p, end, top = false)
            when (box.type) {
                "moov" -> {
                    children(pass, box.payload, box.end, depth + 1, null)
                }

                "trak" -> {
                    val fresh = Track()
                    children(pass, box.payload, box.end, depth + 1, fresh)
                    verifyTrack(pass, fresh)
                }

                "mdia", "minf", "stbl" -> {
                    children(pass, box.payload, box.end, depth + 1, track)
                }

                "hdlr" -> {
                    if (track != null) track.handler = handlerType(pass, box) else generic(pass, box)
                }

                "stsd" -> {
                    if (track != null) sampleEntries(pass, box, track) else generic(pass, box)
                }

                "udta" -> {
                    userData(pass, box, depth + 1)
                }

                "meta" -> {
                    meta(pass, box, depth + 1)
                }

                "uuid" -> {
                    uuid(pass, box)
                }

                else -> {
                    generic(pass, box)
                }
            }
            p = box.end
        }
    }

    /** Handler-Typ einer `hdlr`-FullBox: Version und Flags, Pre-defined, dann der Typ. */
    private fun handlerType(
        pass: ScrubPass,
        box: Box,
    ): String = pass.bytes(box.payload + 8, 4, box.end).toString(Charsets.ISO_8859_1)

    /** `stsd`: Version und Flags, Anzahl, dann die Sample-Einträge als Boxen. */
    private fun sampleEntries(
        pass: ScrubPass,
        box: Box,
        track: Track,
    ) {
        var p = box.payload + 8
        while (p < box.end) {
            pass.count("sample_entries")
            val entry = box(pass, p, box.end, top = false)
            track.entries.add(SampleEntry(entry.type, entry.start, entry.end))
            p = entry.end
        }
    }

    /** Metadaten-Tracks: `gpmd` und `camm` immer, `mebx` nur mit Standort-Key, Handler `meta` immer. */
    private fun verifyTrack(
        pass: ScrubPass,
        track: Track,
    ) {
        if (track.handler == "meta" && track.entries.isEmpty()) bail(NotRemovableReason.METADATA_TRACK)
        for (entry in track.entries) {
            when {
                entry.type in METADATA_SAMPLE_TYPES -> {
                    bail(NotRemovableReason.METADATA_TRACK)
                }

                entry.type == "mebx" -> {
                    val text = pass.bytes(entry.start, entry.end - entry.start, entry.end).toString(Charsets.ISO_8859_1)
                    if (text.contains("location", ignoreCase = true)) bail(NotRemovableReason.METADATA_TRACK)
                }

                track.handler == "meta" -> {
                    bail(NotRemovableReason.METADATA_TRACK)
                }
            }
        }
    }

    /** `udta` mit `©xyz`, `loci`, `meta`, XMP-`uuid` und übrigen Boxen. */
    private fun userData(
        pass: ScrubPass,
        box: Box,
        depth: Int,
    ) {
        if (depth > ScrubLimits.MAX_DEPTH) bail(NotRemovableReason.STRUCTURE)
        var p = box.payload
        while (p < box.end) {
            pass.count("udta_boxes")
            val item = box(pass, p, box.end, top = false)
            when (item.type) {
                COPYRIGHT_XYZ -> xyzText(pass, item)
                "loci" -> loci(pass, item)
                "meta" -> meta(pass, item, depth + 1)
                "udta" -> userData(pass, item, depth + 1)
                "uuid" -> uuid(pass, item)
                else -> generic(pass, item)
            }
            p = item.end
        }
    }

    /** `©xyz`: 2 Byte Länge, 2 Byte Sprache, dann der Text. Länge und Sprache bleiben. */
    private fun xyzText(
        pass: ScrubPass,
        item: Box,
    ) {
        val length = pass.u16(item.payload, item.end, big = true).toLong()
        val textStart = item.payload + 4
        pass.bounds(textStart, length, item.end)
        pass.blank(textStart, length, 0x20, "location_text")
    }

    /**
     * `loci` (3GPP, FullBox): Sprache, Name (NUL-terminiert), Rolle (1 Byte), Länge, Breite, Höhe als Festkomma (je
     * 4 Bytes), Himmelskörper und Notizen (je NUL-terminiert). Koordinaten werden mit 0x00, Texte mit 0x20 geleert.
     */
    private fun loci(
        pass: ScrubPass,
        item: Box,
    ) {
        var p = item.payload + 4 + 2
        val nameEnd = nulIndex(pass, p, item.end)
        pass.blank(p, nameEnd - p, 0x20, "location_text")
        p = nameEnd + 2
        pass.blank(p, 12, 0x00, "location_coordinates")
        p += 12
        val bodyEnd = nulIndex(pass, p, item.end)
        pass.blank(p, bodyEnd - p, 0x20, "location_text")
        p = bodyEnd + 1
        val notesEnd = nulIndex(pass, p, item.end)
        pass.blank(p, notesEnd - p, 0x20, "location_text")
    }

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
     * `meta`. QuickTime kennt keine FullBox-Kopfzeile, ISO schon (vier Bytes Version und Flags). Die Kinder werden
     * unterschieden: `keys` liefert die Namen, `ilst` die Items.
     */
    private fun meta(
        pass: ScrubPass,
        box: Box,
        depth: Int,
    ) {
        if (depth > ScrubLimits.MAX_DEPTH) bail(NotRemovableReason.STRUCTURE)
        val quickTime =
            pass.bytes(box.payload + 4, 4, box.end).toString(Charsets.ISO_8859_1) in setOf("hdlr", "keys", "ilst")
        var p = if (quickTime) box.payload else box.payload + 4
        val items = ArrayList<Box>()
        while (p < box.end) {
            pass.count("meta_boxes")
            val child = box(pass, p, box.end, top = false)
            items.add(child)
            p = child.end
        }
        val keyNames = items.firstOrNull { it.type == "keys" }?.let { keyNames(pass, it) } ?: emptyList()
        for (child in items) {
            when (child.type) {
                "keys" -> Unit
                "ilst" -> ilst(pass, child, keyNames)
                "uuid" -> uuid(pass, child)
                else -> generic(pass, child)
            }
        }
    }

    /** `keys`: Version und Flags, Anzahl, dann je Eintrag Größe, Namensraum und Name. Der Index zählt ab 1. */
    private fun keyNames(
        pass: ScrubPass,
        keys: Box,
    ): List<String> {
        val count = pass.u32Be(keys.payload + 4, keys.end)
        var p = keys.payload + 8
        val names = ArrayList<String>()
        for (index in 0 until count) {
            pass.count("keys_entries")
            val size = pass.u32Be(p, keys.end)
            if (size < 8) bail(NotRemovableReason.STRUCTURE)
            names.add(pass.bytes(p + 8, size - 8, keys.end).toString(Charsets.ISO_8859_1))
            p += size
        }
        return names
    }

    /**
     * `ilst`: Items. Das Item-Feld ist beim `mdta`-Schema der 1-basierte Key-Index. Items mit Standort-Keys und `©xyz`
     * werden in der `data`-Nutzlast mit 0x20 überschrieben. Der übrige Inhalt wird nur geprüft.
     */
    private fun ilst(
        pass: ScrubPass,
        list: Box,
        keyNames: List<String>,
    ) {
        val locationIndexes = HashSet<Long>()
        keyNames.forEachIndexed { index, name ->
            if (name.startsWith(LOCATION_KEY_PREFIX)) locationIndexes.add(index + 1L)
        }
        var p = list.payload
        while (p < list.end) {
            pass.count("ilst_items")
            val item = box(pass, p, list.end, top = false)
            val isLocation = item.type == COPYRIGHT_XYZ || pass.u32Be(item.start + 4, list.end) in locationIndexes
            if (isLocation) dataValues(pass, item) else generic(pass, item)
            p = item.end
        }
    }

    private fun dataValues(
        pass: ScrubPass,
        item: Box,
    ) {
        var q = item.payload
        while (q < item.end) {
            pass.count("ilst_data")
            val data = box(pass, q, item.end, top = false)
            if (data.type == "data") {
                // Nutzlast nach Typ-Angabe (4 Bytes) und Locale (4 Bytes).
                val valueStart = data.payload + 8
                if (valueStart > data.end) bail(NotRemovableReason.STRUCTURE)
                pass.blank(valueStart, data.end - valueStart, 0x20, "location_key")
            }
            q = data.end
        }
    }

    /** XMP-`uuid` (Adobe): der XMP-Scrubber läuft über den Rest. Andere `uuid` werden nur geprüft. */
    private fun uuid(
        pass: ScrubPass,
        box: Box,
    ) {
        if (box.end - box.payload >= 16 && pass.bytes(box.payload, 16, box.end).contentEquals(XMP_UUID)) {
            XmpScrubber.scrubRegion(pass, box.payload + 16, box.end)
        } else {
            generic(pass, box)
        }
    }

    /** Top-Level `free` oder `skip`: Standort-Muster oder ein `gps `-Box-Typ ergeben `free_box_location`. */
    private fun freeBox(
        pass: ScrubPass,
        box: Box,
    ) {
        val length = box.end - box.payload
        if (length > ScrubLimits.MAX_TEXT) bail(NotRemovableReason.STRUCTURE)
        val text = pass.bytes(box.payload, length, box.end).toString(Charsets.ISO_8859_1)
        if (text.contains("gps ") || LocationText.containsIn(text)) bail(NotRemovableReason.FREE_BOX_LOCATION)
    }

    /** Alles, was der Walker nicht einordnet: Standort-Text darin führt zu `unsupported`, sonst bleibt es. */
    private fun generic(
        pass: ScrubPass,
        box: Box,
    ) {
        if (LocationText.containsIn(pass.store, box.payload, box.end)) bail(NotRemovableReason.UNSUPPORTED)
    }
}
