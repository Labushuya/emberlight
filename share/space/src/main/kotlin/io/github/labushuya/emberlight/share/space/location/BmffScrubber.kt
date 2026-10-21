package io.github.labushuya.emberlight.share.space.location

import io.github.labushuya.emberlight.share.space.ByteReader
import io.github.labushuya.emberlight.share.space.ContentInspector

/**
 * ISO-BMFF-Scrubber für MP4, MOV und 3GP (ADR 0015, Fundorte). Box-Walker mit 64-bit-Größen, `size 0` bis zum Ende,
 * Grenzen und Schachtelungstiefe.
 *
 * - `udta` (auch in `trak`): `©xyz` (Text ab Offset 4), `loci` (3GPP). Beide werden geleert und danach in `free` umbenannt
 *   (längenerhaltend, ADR 0015). `meta` mit `keys` und `ilst`: Items der Keys
 *   `com.apple.quicktime.location.*` und `©xyz` werden in der `data`-Nutzlast überschrieben. `keys` und Indizes
 *   bleiben unverändert.
 * - XMP-`uuid`: XMP-Scrubber. `free`/`skip` mit Standort: `free_box_location`.
 * - Metadaten-Tracks mit binärem Standort (Nachtrag 3.1b-2): `gpmd` mit `GPS5`/`GPS9`, `camm` mit GPS-Records (Typ 5
 *   oder 6), `djmd` (DJI, immer). Die Samples dieser Tracks werden längenerhaltend mit 0x00 geleert. Die Sample-Lage
 *   kommt aus `stsz`, `stsc` und `stco`/`co64`. Ein `gpmd`/`camm`-Track ohne GPS-Befund bleibt unverändert.
 * - Metadaten-Tracks ohne lesbare Sample-Tabelle, `mebx` mit `location` und unbekannte Handler `meta`:
 *   `metadata_track` (fail closed, wie bisher).
 * - Unbekannte Boxen mit binärem Standort (TIFF mit GPS-IFD) werden geleert (`location_box_binary`). Ist die Box zu groß
 *   für den Speicher, gilt `location_not_removable`.
 * - Alles, was nicht eingeordnet werden kann und Standort-Text enthält: `unsupported`.
 *
 * Mediendaten (`mdat`) werden nie gelesen, außer den Samples von Metadaten-Tracks oben. Ein Anhang hinter der letzten
 * gültigen Top-Level-Box wird abgeschnitten (ADR 0015, Anhänge).
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
    private val FREE_TYPE = ascii("free")
    private const val LOCATION_KEY_PREFIX = "com.apple.quicktime.location."
    private val SAMPLE_TRACK_TYPES = setOf("gpmd", "camm", "djmd")
    private const val TRACK_FINDING = "location_track"
    private const val BOX_FINDING = "location_box_binary"

    internal class Box(
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

    /** Ein Byte-Bereich eines Samples: Start und Länge. */
    private class Span(
        val start: Long,
        val size: Long,
    )

    /**
     * Sammelt Angaben je `trak`: Handler-Typ, Sample-Einträge und die Sample-Tabellen (ISO 14496-12, §8.7).
     * [sampleSizes] aus `stsz`, [chunkOffsets] aus `stco`/`co64`, [sampleToChunk] aus `stsc` als Paare (erster Chunk,
     * Samples je Chunk).
     */
    private class Track {
        var handler: String? = null
        val entries = ArrayList<SampleEntry>()
        var sampleSizes: LongArray? = null
        var chunkOffsets: LongArray? = null
        var sampleToChunk: List<LongArray>? = null
    }

    /**
     * Ganze Datei ab Offset 0. Die Kette endet am logischen Ende (ADR 0015, Anhänge). Was danach folgt, wird abgeschnitten.
     */
    fun scrubFile(pass: ScrubPass) {
        val size = pass.store.length
        val end = logicalEnd(pass, 0, size)
        scrubTop(pass, 0, end)
        pass.truncate(end, CUT_FINDING)
    }

    /**
     * Bereinigt ein MP4, das bei [start] beginnt (etwa nach einem JPEG-EOI), und liefert sein logisches Ende. Alles
     * danach bis [limit] gehört nicht zur Kette und liegt im Aufrufer (abschneiden oder ablehnen).
     */
    fun scrubRegion(
        pass: ScrubPass,
        start: Long,
        limit: Long,
    ): Long {
        val end = logicalEnd(pass, start, limit)
        scrubTop(pass, start, end)
        return end
    }

    /**
     * Ende der gültigen Top-Level-Kette zwischen [start] und [limit] (absolut). Eine beschädigte Kette ist nicht
     * bereinigbar (STRUCTURE).
     */
    internal fun logicalEnd(
        pass: ScrubPass,
        start: Long,
        limit: Long,
    ): Long {
        val reader =
            ByteReader { offset, length ->
                val at = start + offset
                val count = minOf(length.toLong(), limit - at).toInt()
                if (count <= 0) ByteArray(0) else pass.bytes(at, count.toLong(), limit)
            }
        val end = ContentInspector.validBoxEnd(limit - start, reader) ?: bail(NotRemovableReason.STRUCTURE)
        return start + end
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

    internal fun box(
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

    /** `moov` auf Top-Level (auch in HEIF-Sequenzen und Bursts): Kinder wie bei MP4. */
    internal fun moov(
        pass: ScrubPass,
        box: Box,
    ) {
        children(pass, box.payload, box.end, 1, null)
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

                "stsz", "stco", "co64", "stsc" -> {
                    if (track != null) sampleTable(pass, box, track) else generic(pass, box)
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

    /**
     * `stsz` (Größen), `stco`/`co64` (Chunk-Offsets), `stsc` (Samples je Chunk). Nur die Felder der Tabellen, keine
     * Sample-Daten. Ein Eintrag über [ScrubLimits.MAX_ENTRIES] gilt als Strukturfehler.
     */
    private fun sampleTable(
        pass: ScrubPass,
        box: Box,
        track: Track,
    ) {
        val p = box.payload + 4
        when (box.type) {
            "stsz" -> {
                val fixed = pass.u32Be(p, box.end)
                val count = pass.u32Be(p + 4, box.end)
                if (count > ScrubLimits.MAX_ENTRIES) bail(NotRemovableReason.STRUCTURE)
                track.sampleSizes =
                    LongArray(count.toInt()) { index ->
                        if (fixed != 0L) fixed else pass.u32Be(p + 8 + 4L * index, box.end)
                    }
            }

            "stco" -> {
                val count = pass.u32Be(p, box.end)
                if (count > ScrubLimits.MAX_ENTRIES) bail(NotRemovableReason.STRUCTURE)
                track.chunkOffsets = LongArray(count.toInt()) { index -> pass.u32Be(p + 4 + 4L * index, box.end) }
            }

            "co64" -> {
                val count = pass.u32Be(p, box.end)
                if (count > ScrubLimits.MAX_ENTRIES) bail(NotRemovableReason.STRUCTURE)
                track.chunkOffsets =
                    LongArray(count.toInt()) { index ->
                        val at = p + 4 + 8L * index
                        val high = pass.u32Be(at, box.end)
                        if (high > 0x7FFF_FFFFL) bail(NotRemovableReason.STRUCTURE)
                        (high shl 32) or pass.u32Be(at + 4, box.end)
                    }
            }

            "stsc" -> {
                val count = pass.u32Be(p, box.end)
                if (count > ScrubLimits.MAX_ENTRIES) bail(NotRemovableReason.STRUCTURE)
                track.sampleToChunk =
                    List(count.toInt()) { index ->
                        val at = p + 4 + 12L * index
                        longArrayOf(pass.u32Be(at, box.end), pass.u32Be(at + 4, box.end))
                    }
            }
        }
    }

    /**
     * Byte-Bereiche aller Samples eines Tracks. Fehlt eine der drei Tabellen, liefert es `null`. Passen die Tabellen nicht
     * zusammen, ist die Struktur fehlerhaft.
     */
    private fun sampleSpans(track: Track): List<Span>? {
        val sizes = track.sampleSizes ?: return null
        val chunks = track.chunkOffsets ?: return null
        val map = track.sampleToChunk ?: return null
        if (map.isEmpty() || map[0][0] != 1L) bail(NotRemovableReason.STRUCTURE)
        val spans = ArrayList<Span>(sizes.size)
        var sample = 0
        var entry = 0
        for (chunk in chunks.indices) {
            val chunkNumber = chunk + 1L
            // Der letzte Eintrag mit erstem Chunk ≤ aktueller Chunk gilt (ISO 14496-12, stsc). Die Liste ist aufsteigend.
            while (entry + 1 < map.size && map[entry + 1][0] <= chunkNumber) entry++
            if (map[entry][0] > chunkNumber) bail(NotRemovableReason.STRUCTURE)
            val perChunk = map[entry][1]
            var offset = chunks[chunk]
            for (inChunk in 0L until perChunk) {
                if (sample >= sizes.size) bail(NotRemovableReason.STRUCTURE)
                val size = sizes[sample++]
                spans.add(Span(offset, size))
                offset += size
            }
        }
        if (sample != sizes.size) bail(NotRemovableReason.STRUCTURE)
        return spans
    }

    /**
     * Ob die Samples eines Metadaten-Tracks binären Standort tragen. `gpmd`: GPMF-Schlüssel `GPS5` oder `GPS9` (Schlüssel
     * sind vierstellige ASCII-Kennungen). `camm`: Record mit Typ 5 oder 6 (Kopf: zwei Nullbytes, dann der Typ als
     * Little-Endian-Zahl). `djmd` (DJI) gilt immer als Standortträger, weil seine Felder nicht sicher lesbar sind. Ein
     * Fehlalarm betrifft nur diesen Metadaten-Track, nie Bild- oder Videodaten.
     */
    private fun carriesGps(
        pass: ScrubPass,
        type: String,
        spans: List<Span>,
    ): Boolean {
        if (type == "djmd") return true
        return spans.any { span ->
            pass.count("track_samples")
            val bytes = pass.bytes(span.start, span.size, pass.store.length)
            when (type) {
                "gpmd" -> containsAscii(bytes, "GPS5") || containsAscii(bytes, "GPS9")
                "camm" -> cammGps(bytes)
                else -> false
            }
        }
    }

    private fun containsAscii(
        bytes: ByteArray,
        text: String,
    ): Boolean = String(bytes, Charsets.ISO_8859_1).contains(text)

    /** camm-Record mit Kopf `00 00`, dann Typ 5 oder 6 als Little-Endian-Zahl. An jeder Stelle gesucht. */
    private fun cammGps(bytes: ByteArray): Boolean {
        for (i in 0 until bytes.size - 3) {
            if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && bytes[i + 3] == 0.toByte()) {
                val type = bytes[i + 2].toInt()
                if (type == 5 || type == 6) return true
            }
        }
        return false
    }

    /**
     * Ein Metadaten-Track mit Sample-Daten: Ohne lesbare Tabellen gilt er als `metadata_track` (fail closed). Mit
     * binärem Standort werden alle Samples geleert. Ohne Befund bleibt er unverändert.
     */
    private fun sampleTrack(
        pass: ScrubPass,
        track: Track,
        type: String,
    ) {
        val spans = sampleSpans(track) ?: bail(NotRemovableReason.METADATA_TRACK)
        if (!carriesGps(pass, type, spans)) return
        for (span in spans) pass.blank(span.start, span.size, 0, TRACK_FINDING)
    }

    /** Metadaten-Tracks: Sample-Tracks mit GPS-Befund werden geleert, `mebx` nur mit Standort-Key, Handler `meta` immer. */
    private fun verifyTrack(
        pass: ScrubPass,
        track: Track,
    ) {
        if (track.handler == "meta" && track.entries.isEmpty()) bail(NotRemovableReason.METADATA_TRACK)
        for (entry in track.entries) {
            when {
                entry.type in SAMPLE_TRACK_TYPES -> {
                    sampleTrack(pass, track, entry.type)
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
                COPYRIGHT_XYZ -> {
                    xyzText(pass, item)
                    renameToFree(pass, item)
                }

                "loci" -> {
                    loci(pass, item)
                    renameToFree(pass, item)
                }

                "meta" -> {
                    meta(pass, item, depth + 1)
                }

                "udta" -> {
                    userData(pass, item, depth + 1)
                }

                "uuid" -> {
                    uuid(pass, item)
                }

                else -> {
                    generic(pass, item)
                }
            }
            p = item.end
        }
    }

    /**
     * Macht eine Standort-Box unter `udta` unsichtbar: Der Typ wird in `free` geändert (längenerhaltend). Leser
     * ignorieren `free` (ISO 14496-12, §8.1.2). So bleibt kein Standort-Typ mit leerem Wert zurück, den ein Leser als
     * Position (0/0) deuten könnte (ADR 0015, Abschnitt „Kein Null Island“).
     */
    private fun renameToFree(
        pass: ScrubPass,
        item: Box,
    ) {
        pass.overwrite(item.start + 4, FREE_TYPE, "location_box")
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
        // Jeder Key mit „location“ im Namen zählt, nicht nur das Apple-Präfix (andere Schreiber nutzen andere Namen).
        keyNames.forEachIndexed { index, name ->
            if (name.contains("location", ignoreCase = true)) locationIndexes.add(index + 1L)
        }
        var p = list.payload
        while (p < list.end) {
            pass.count("ilst_items")
            val item = box(pass, p, list.end, top = false)
            val isLocation = item.type == COPYRIGHT_XYZ || pass.u32Be(item.start + 4, list.end) in locationIndexes
            // Standort-Text im Wert, auch unter einem unbekannten Key, wird ebenfalls überschrieben.
            if (isLocation || LocationText.containsIn(pass.store, item.payload, item.end)) {
                dataValues(pass, item)
            } else {
                generic(pass, item)
            }
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
    internal fun uuid(
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
    internal fun freeBox(
        pass: ScrubPass,
        box: Box,
    ) {
        val length = box.end - box.payload
        if (length > ScrubLimits.MAX_TEXT) bail(NotRemovableReason.STRUCTURE)
        val text = pass.bytes(box.payload, length, box.end).toString(Charsets.ISO_8859_1)
        if (text.contains("gps ") || LocationText.containsIn(text)) bail(NotRemovableReason.FREE_BOX_LOCATION)
    }

    /**
     * Alles, was der Walker nicht einordnet. Enthält es ein eingebettetes TIFF mit GPS-Befund, wird die Nutzlast geleert
     * (`location_box_binary`, längenerhaltend). Standort-Text darin führt zu `unsupported`, sonst bleibt es.
     */
    internal fun generic(
        pass: ScrubPass,
        box: Box,
    ) {
        if (StrictLocationProbe.containsBinaryLocation(RangeStore(pass.store, box.payload, box.end))) {
            val count = box.end - box.payload
            if (count > ScrubLimits.MAX_READ) bail(NotRemovableReason.LOCATION_NOT_REMOVABLE)
            pass.blank(box.payload, count, 0, BOX_FINDING)
            return
        }
        if (LocationText.containsIn(pass.store, box.payload, box.end)) bail(NotRemovableReason.UNSUPPORTED)
    }
}
