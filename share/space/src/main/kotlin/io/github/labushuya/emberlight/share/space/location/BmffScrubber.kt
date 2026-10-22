package io.github.labushuya.emberlight.share.space.location

import io.github.labushuya.emberlight.share.space.ByteReader
import io.github.labushuya.emberlight.share.space.ContentInspector

/**
 * ISO-BMFF-Scrubber für MP4, MOV und 3GP (ADR 0015, Fundorte, Nachtrag 3.6d). Box-Walker mit 64-bit-Größen, `size 0` bis
 * zum Ende, Grenzen und Schachtelungstiefe.
 *
 * - `udta` (auch in `trak`): `©xyz` (Text ab Offset 4) und `loci` (3GPP) werden geleert und in `free` umbenannt. Alle
 *   anderen Kinder (außer `meta`, `udta`, `uuid`) sind unbekannt und werden immer neutralisiert ([neutralize]). `meta` mit `keys` und
 *   `ilst`: Items der Keys `com.apple.quicktime.location.*` und `©xyz` werden in der `data`-Nutzlast überschrieben.
 *   `keys` und Indizes bleiben unverändert. Andere Kinder von `meta` werden ebenfalls neutralisiert (außer [META_KEEP]).
 * - Spuren (`trak`): Die Samples aller Spuren, deren Handler nicht in [MEDIA_HANDLERS] liegt, werden immer geleert
 *   (`location_track`). Dazu gehören `gpmd`, `camm`, `djmd`, `meta`, Text- und Untertitelspuren und unbekannte Handler.
 *   Die Samples kommen aus `stsz`, `stsc` und `stco`/`co64`. Sind die Tabellen nicht lesbar, gilt `metadata_track`
 *   (Ablehnung). Gibt es Fragmente (`moof`), gilt `metadata_track` für jede Datenspur, weil ihre Samples in `mdat`
 *   bei `trun` liegen und hier nicht gelesen werden.
 * - `mebx`-Sample-Einträge: Der Schlüsselname `location` wird in den Key-Namen überschrieben (gleiche Länge).
 * - XMP-`uuid`: XMP-Scrubber. Andere `uuid` unter `udta` oder `meta` sind unbekannt und werden neutralisiert.
 * - `free`/`skip` mit Standort: `free_box_location`.
 * - Unbekannte Boxen mit binärem Standort (TIFF mit GPS-IFD) werden geleert (`location_box_binary`). Ist die Box zu groß
 *   für den Speicher, gilt `location_not_removable`.
 * - Alles, was nicht eingeordnet werden kann und Standort-Text enthält: `unsupported`.
 *
 * Mediendaten (`mdat`) werden nie gelesen, außer den Samples von Datenspuren oben. Ein Anhang hinter der letzten gültigen
 * Top-Level-Box wird abgeschnitten (ADR 0015, Anhänge). Unbekannte Top-Level-Boxen und unbekannte Kinder von `moov`
 * (außerhalb von `udta`, `meta`) werden nur bei einem Befund geleert (siehe Restrisiken in ADR 0015).
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
    private const val LOCATION_KEY_WORD = "location"
    private val LOCATION_KEY_MASK = ascii("xxxxxxxx")

    /**
     * Handler, deren Samples Bild-, Video- oder Audiodaten sind. Sie werden nie geleert (ADR 0015, Nachtrag 3.6d). `pict`
     * (Bildsequenz) und `auxv` (Hilfsvideo, etwa Alpha) gehören dazu, damit Bilddaten keinen Fehlalarm auslösen.
     */
    private val MEDIA_HANDLERS = setOf("vide", "soun", "pict", "auxv")

    /**
     * Unterkinder von `meta`, die nicht neutralisiert werden. `hdlr`, `keys`, `ilst` und `dinf` sind die Struktur, mit der
     * Metadaten gelesen werden (ISO 14496-12 bzw. QuickTime). `free`/`skip` werden von Lesern ignoriert und nur geprüft.
     */
    private val META_KEEP = setOf("hdlr", "keys", "ilst", "dinf", "free", "skip", "uuid")
    private const val TRACK_FINDING = "location_track"
    private const val BOX_FINDING = "location_box_binary"
    private const val NEUTRAL_FINDING = "metadata_box"
    private const val KEY_FINDING = "location_key"

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
     * Sammelt Angaben je `trak`: Handler-Typ, Sample-Einträge und die Sample-Tabellen (`stsz`, `stsc`, `stco`/`co64`) als
     * Boxen. Gelesen werden die Tabellen erst, wenn die Spur geprüft wird ([sampleSpans]).
     */
    private class Track {
        var handler: String? = null
        val entries = ArrayList<SampleEntry>()
        val tables = ArrayList<Box>()
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
        val fragmented = hasMoof(pass, start, end)
        var p = start
        while (p < end) {
            pass.count("bmff_boxes")
            val box = box(pass, p, end, top = true)
            when (box.type) {
                "ftyp", "mdat", "wide" -> Unit
                "moov" -> children(pass, box.payload, box.end, 1, null, fragmented)
                "free", "skip" -> freeBox(pass, box)
                "uuid" -> uuid(pass, box)
                "meta" -> bail(NotRemovableReason.UNSUPPORTED)
                else -> generic(pass, box)
            }
            p = box.end
        }
    }

    /** Gibt es eine Top-Level-Box `moof` (fragmentiertes MP4) zwischen [start] und [end]? */
    internal fun hasMoof(
        pass: ScrubPass,
        start: Long,
        end: Long,
    ): Boolean {
        var p = start
        while (p < end) {
            pass.count("bmff_scan")
            val box = box(pass, p, end, top = true)
            if (box.type == "moof") return true
            p = box.end
        }
        return false
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

    /**
     * `moov` auf Top-Level (auch in HEIF-Sequenzen und Bursts): Kinder wie bei MP4. [fragmented] gibt an, ob ein `moof`
     * in der Datei liegt (siehe [hasMoof]).
     */
    internal fun moov(
        pass: ScrubPass,
        box: Box,
        fragmented: Boolean,
    ) {
        children(pass, box.payload, box.end, 1, null, fragmented)
    }

    private fun children(
        pass: ScrubPass,
        start: Long,
        end: Long,
        depth: Int,
        track: Track?,
        fragmented: Boolean,
    ) {
        if (depth > ScrubLimits.MAX_DEPTH) bail(NotRemovableReason.STRUCTURE)
        var p = start
        while (p < end) {
            pass.count("bmff_boxes")
            val box = box(pass, p, end, top = false)
            when (box.type) {
                "moov" -> {
                    children(pass, box.payload, box.end, depth + 1, null, fragmented)
                }

                "trak" -> {
                    val fresh = Track()
                    children(pass, box.payload, box.end, depth + 1, fresh, fragmented)
                    verifyTrack(pass, fresh, fragmented)
                }

                "mdia", "minf", "stbl" -> {
                    children(pass, box.payload, box.end, depth + 1, track, fragmented)
                }

                "hdlr" -> {
                    if (track != null) track.handler = handlerType(pass, box) else generic(pass, box)
                }

                "stsd" -> {
                    if (track != null) sampleEntries(pass, box, track) else generic(pass, box)
                }

                "stsz", "stco", "co64", "stsc" -> {
                    if (track != null) track.tables.add(box) else generic(pass, box)
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

    /** Eine Sample-Tabelle des Typs [type]. Doppelte sind mehrdeutig und gelten als Strukturfehler. */
    private fun tableBox(
        track: Track,
        type: String,
    ): Box? {
        val matches = track.tables.filter { it.type == type }
        if (matches.size > 1) bail(NotRemovableReason.STRUCTURE)
        return matches.firstOrNull()
    }

    /** `stsz`: Version und Flags, Standardgröße (0 = je Sample), Anzahl, dann die Größen je Sample. */
    private fun sampleSizes(
        pass: ScrubPass,
        box: Box,
    ): LongArray {
        val fixed = pass.u32Be(box.payload + 4, box.end)
        val count = pass.u32Be(box.payload + 8, box.end)
        if (count > ScrubLimits.MAX_TRACK_SAMPLES) bail(NotRemovableReason.STRUCTURE)
        return LongArray(count.toInt()) { index ->
            if (fixed != 0L) fixed else pass.u32Be(box.payload + 12 + 4L * index, box.end)
        }
    }

    /** `stco` (32 Bit) oder `co64` (64 Bit): Anzahl der Chunks, dann ihre absoluten Dateioffsets. */
    private fun chunkOffsets(
        pass: ScrubPass,
        box: Box,
    ): LongArray {
        val count = pass.u32Be(box.payload + 4, box.end)
        if (count > ScrubLimits.MAX_TRACK_SAMPLES) bail(NotRemovableReason.STRUCTURE)
        val wide = box.type == "co64"
        return LongArray(count.toInt()) { index ->
            if (!wide) {
                pass.u32Be(box.payload + 8 + 4L * index, box.end)
            } else {
                val at = box.payload + 8 + 8L * index
                val high = pass.u32Be(at, box.end)
                if (high > 0x7FFF_FFFFL) bail(NotRemovableReason.STRUCTURE)
                (high shl 32) or pass.u32Be(at + 4, box.end)
            }
        }
    }

    /** `stsc`: Anzahl, dann je Eintrag der erste Chunk und die Samples je Chunk (Paare aus zwei Zahlen). */
    private fun sampleToChunk(
        pass: ScrubPass,
        box: Box,
    ): List<LongArray> {
        val count = pass.u32Be(box.payload + 4, box.end)
        if (count > ScrubLimits.MAX_TRACK_SAMPLES) bail(NotRemovableReason.STRUCTURE)
        return List(count.toInt()) { index ->
            val at = box.payload + 8 + 12L * index
            longArrayOf(pass.u32Be(at, box.end), pass.u32Be(at + 4, box.end))
        }
    }

    /**
     * Byte-Bereiche aller Samples eines Tracks aus `stsz`, `stsc` und `stco`/`co64`. Die Tabellen werden erst hier gelesen,
     * damit Bild- und Videotracks mit vielen Samples nicht betroffen sind. Fehlt eine Tabelle, liefert es `null`. Passen
     * die Tabellen nicht zusammen, ist die Struktur fehlerhaft.
     */
    private fun sampleSpans(
        pass: ScrubPass,
        track: Track,
    ): List<Span>? {
        val stco = tableBox(track, "stco")
        val co64 = tableBox(track, "co64")
        if (stco != null && co64 != null) bail(NotRemovableReason.STRUCTURE)
        val chunkBox = stco ?: co64 ?: return null
        val sizeBox = tableBox(track, "stsz") ?: return null
        val mapBox = tableBox(track, "stsc") ?: return null
        val chunks = chunkOffsets(pass, chunkBox)
        val sizes = sampleSizes(pass, sizeBox)
        // Eine Spur ohne Samples hat nichts zu leeren. Ihre Tabellen dürfen leer sein (etwa ein leerer Text-Track).
        if (sizes.isEmpty()) return emptyList()
        val map = sampleToChunk(pass, mapBox)
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
     * Prüft eine Spur (fail closed). Mediaspuren (Handler in [MEDIA_HANDLERS]) bleiben unberührt. Alle anderen Spuren
     * werden geleert, ohne Befund und ohne Rücksicht auf den Inhalt: Ihre Samples werden nicht zur Wiedergabe gebraucht.
     * Ohne lesbare Sample-Tabellen oder bei Fragmenten wird der Upload abgelehnt (`metadata_track`).
     */
    private fun verifyTrack(
        pass: ScrubPass,
        track: Track,
        fragmented: Boolean,
    ) {
        val handler = track.handler
        if (handler != null && handler in MEDIA_HANDLERS) return
        if (fragmented) bail(NotRemovableReason.METADATA_TRACK)
        val spans = sampleSpans(pass, track) ?: bail(NotRemovableReason.METADATA_TRACK)
        blankSpans(pass, spans)
        for (entry in track.entries) {
            if (entry.type == "mebx") neutralizeKeyNames(pass, entry)
        }
    }

    /**
     * Leert die Samples einer Spur. Aufeinanderfolgende Samples liegen am Stück in der Datei und werden zu einem Bereich
     * zusammengefasst. Die Bereiche gehören nur zu dieser Spur (Spuren überlappen sich nicht mit ihren eigenen Bytes).
     */
    private fun blankSpans(
        pass: ScrubPass,
        spans: List<Span>,
    ) {
        var index = 0
        while (index < spans.size) {
            val start = spans[index].start
            var end = start + spans[index].size
            var next = index + 1
            while (next < spans.size && spans[next].start == end) {
                end += spans[next].size
                next++
            }
            pass.blank(start, end - start, 0, TRACK_FINDING)
            index = next
        }
    }

    /**
     * Sample-Eintrag `mebx`: Der Schlüsselname `location` wird in den Key-Namen durch gleich lange Zeichen ersetzt.
     * Die Key-Struktur bleibt, damit die Sample-Beschreibung gültig bleibt.
     */
    private fun neutralizeKeyNames(
        pass: ScrubPass,
        entry: SampleEntry,
    ) {
        val size = entry.end - entry.start
        val text = pass.bytes(entry.start, size, entry.end).toString(Charsets.ISO_8859_1).lowercase()
        var at = text.indexOf(LOCATION_KEY_WORD)
        while (at >= 0) {
            pass.overwrite(entry.start + at, LOCATION_KEY_MASK, KEY_FINDING)
            at = text.indexOf(LOCATION_KEY_WORD, at + LOCATION_KEY_WORD.length)
        }
    }

    /** `udta` mit `©xyz`, `loci`, `meta`, XMP-`uuid`, und unbekannten Kindern, die neutralisiert werden. */
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
                    embeddedUuid(pass, item)
                }

                "free", "skip" -> {
                    generic(pass, item)
                }

                else -> {
                    neutralize(pass, item)
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

    /**
     * Unbekannte Metadaten-Box ohne Befund (ADR 0015, Nachtrag 3.6d): Der Typ wird in `free` geändert, die Nutzlast mit
     * Nullen geleert. Die Länge bleibt, Leser überspringen `free`.
     */
    private fun neutralize(
        pass: ScrubPass,
        box: Box,
    ) {
        pass.overwrite(box.start + 4, FREE_TYPE, NEUTRAL_FINDING)
        pass.blank(box.payload, box.end - box.payload, 0, NEUTRAL_FINDING)
    }

    /** `uuid` unter `udta` oder `meta`: XMP wird gescrubbt, jede andere `uuid` ist unbekannt und wird neutralisiert. */
    private fun embeddedUuid(
        pass: ScrubPass,
        box: Box,
    ) {
        if (isXmp(pass, box)) XmpScrubber.scrubRegion(pass, box.payload + 16, box.end) else neutralize(pass, box)
    }

    private fun isXmp(
        pass: ScrubPass,
        box: Box,
    ): Boolean = box.end - box.payload >= 16 && pass.bytes(box.payload, 16, box.end).contentEquals(XMP_UUID)

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
     * unterschieden: `keys` liefert die Namen, `ilst` die Items. Kinder außerhalb von [META_KEEP] werden neutralisiert.
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
                "uuid" -> embeddedUuid(pass, child)
                in META_KEEP -> generic(pass, child)
                else -> neutralize(pass, child)
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
            if (name.contains(LOCATION_KEY_WORD, ignoreCase = true)) locationIndexes.add(index + 1L)
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
        if (isXmp(pass, box)) {
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
