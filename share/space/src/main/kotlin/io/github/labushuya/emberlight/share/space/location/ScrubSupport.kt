package io.github.labushuya.emberlight.share.space.location

import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.SortedSet

/**
 * Grund, warum eine Datei nicht sicher bereinigt werden kann (ADR 0015, Reason-Codes). Der Code erscheint als
 * `reason` in der 422-Antwort.
 */
enum class NotRemovableReason(
    val code: String,
) {
    METADATA_TRACK("metadata_track"),

    /** Anhang, der innerhalb einer Struktur liegt und nicht abgeschnitten werden kann (ADR 0015, Anhänge). */
    TRAILING_DATA("trailing_data"),
    EXTENDED_XMP("extended_xmp"),
    FREE_BOX_LOCATION("free_box_location"),
    STRUCTURE("structure"),
    UNSUPPORTED("unsupported"),
    VERIFICATION_FAILED("verification_failed"),

    /**
     * Binärer Standort (GPS-IFD, GPMF, camm, djmd) ist gefunden, lässt sich aber nicht längenerhaltend entfernen (ADR
     * 0015, Nachtrag 3.1b-2). Fail closed: Ohne Einwilligung wird der Upload abgelehnt.
     */
    LOCATION_NOT_REMOVABLE("location_not_removable"),
}

/** Abbruch der Bereinigung. Fail closed: jede unklare Struktur führt hierhin, nie zu einer Teilbereinigung. */
internal class Unremovable(
    val reason: NotRemovableReason,
) : RuntimeException(reason.code, null, false, false)

internal fun bail(reason: NotRemovableReason): Nothing = throw Unremovable(reason)

/** Grenzen aus ADR 0015: Schachtelungstiefe, Zähler und maximale Lesegröße je Struktur. */
internal object ScrubLimits {
    const val MAX_DEPTH = 8
    const val MAX_ENTRIES = 10_000

    /**
     * Samples eines Metadaten-Tracks und Einträge der Sample-Tabellen (`stsz`, `stsc`, `stco`). Großzügiger als [MAX_ENTRIES],
     * weil ein langes Video viele Samples hat. Auch Videotracks dürfen so viele haben, sie werden aber nicht gelesen.
     */
    const val MAX_TRACK_SAMPLES = 1_000_000

    /** Zahl der geplanten Schreibbereiche. Darüber gilt die Struktur als zu aufwendig (fail closed). */
    const val MAX_PLANNED = 100_000
    const val MAX_READ = 16 * 1024 * 1024
    const val MAX_TEXT = 8 * 1024 * 1024
}

/**
 * Finding-Name für Anhänge hinter dem logischen Dateiende (ADR 0015). Ein Walker kürzt dann die Datei; im Prüfdurchlauf
 * ist derselbe Fund ein Fehler, weil danach nichts mehr abzuschneiden ist.
 */
internal const val CUT_FINDING = "trailing_bytes"

/**
 * Zugriff auf die Bytes einer Datei oder eines Speicherpuffers. Überschreiben ändert keine Länge. Kürzen ist nur für
 * den Anhang hinter dem logischen Dateiende vorgesehen (ADR 0015) und nur auf der Datei oder im Plan möglich.
 */
internal interface ByteStore {
    val length: Long

    fun read(
        offset: Long,
        count: Int,
    ): ByteArray

    fun write(
        offset: Long,
        bytes: ByteArray,
    )

    /** Kürzt auf [newLength] Bytes. Speicherpuffer unterstützen das nicht. */
    fun truncate(newLength: Long): Unit =
        throw UnsupportedOperationException("Kürzen ist für diesen Speicher nicht vorgesehen.")
}

/** Die Temp-Datei, per wahlfreiem Zugriff. Gelesen werden nur die Stellen, die ein Walker anfordert. */
internal class FileStore(
    private val file: RandomAccessFile,
    initialLength: Long,
) : ByteStore {
    private var currentLength = initialLength

    override val length: Long get() = currentLength

    override fun read(
        offset: Long,
        count: Int,
    ): ByteArray {
        val out = ByteArray(count)
        file.seek(offset)
        file.readFully(out)
        return out
    }

    override fun write(
        offset: Long,
        bytes: ByteArray,
    ) {
        file.seek(offset)
        file.write(bytes)
    }

    override fun truncate(newLength: Long) {
        file.setLength(newLength)
        currentLength = newLength
    }
}

/** Sicht auf den Bereich [start, end) eines anderen Speichers. Die Länge ist die des Bereichs. */
internal class RangeStore(
    private val base: ByteStore,
    private val start: Long,
    end: Long,
) : ByteStore {
    override val length: Long = end - start

    override fun read(
        offset: Long,
        count: Int,
    ): ByteArray = base.read(start + offset, count)

    override fun write(
        offset: Long,
        bytes: ByteArray,
    ) {
        base.write(start + offset, bytes)
    }
}

/** Speicherpuffer für eingebettete Daten (TIFF in PNG-Text, GIF-XMP). */
internal class MemoryStore(
    private val data: ByteArray,
) : ByteStore {
    override val length: Long = data.size.toLong()

    override fun read(
        offset: Long,
        count: Int,
    ): ByteArray = data.copyOfRange(offset.toInt(), offset.toInt() + count)

    override fun write(
        offset: Long,
        bytes: ByteArray,
    ) {
        bytes.copyInto(data, offset.toInt())
    }

    fun bytes(): ByteArray = data
}

/**
 * Plan statt Sicherungskopie (ADR 0015, Plan/Apply). Die Überschreib-Bereiche liegen nur im Speicher, die Datei bleibt
 * bis [applyTo] unverändert. Lesen liefert den geplanten Stand, damit Walker und Prüfung dieselben Bytes sehen wie
 * nach dem Schreiben. Die Summe der geplanten Bytes ist durch [ScrubLimits.MAX_READ] begrenzt, die Zahl der Bereiche
 * durch [ScrubLimits.MAX_PLANNED]. Das Lesen sucht über einen Index nach Anfangsoffset und nicht über alle Bereiche.
 */
internal class PlanStore(
    private val base: ByteStore,
) : ByteStore {
    private class Planned(
        val seq: Int,
        val offset: Long,
        val bytes: ByteArray,
    )

    /** Alle Bereiche in Planungsreihenfolge (für [applyTo]). */
    private val planned = ArrayList<Planned>()

    /** Bereiche nach Anfangsoffset. Für das Lesen genügt ein Teilbereich des Index. */
    private val byStart = java.util.TreeMap<Long, MutableList<Planned>>()
    private var maxPlannedLength = 0L
    private var plannedBytes = 0L
    private var truncatedTo: Long? = null

    /** `true`, wenn etwas zu schreiben oder zu kürzen ist. Ohne Änderung bleibt der Hash des Streams gültig. */
    val hasChanges: Boolean get() = planned.isNotEmpty() || truncatedTo != null

    /** Länge des geplanten Stands: nach einem Kürzen die neue Länge, sonst die der Datei. */
    override val length: Long get() = truncatedTo ?: base.length

    override fun truncate(newLength: Long) {
        if (newLength < 0 || newLength > length) bail(NotRemovableReason.STRUCTURE)
        truncatedTo = newLength
    }

    override fun read(
        offset: Long,
        count: Int,
    ): ByteArray {
        val out = base.read(offset, count)
        if (planned.isEmpty()) return out
        val end = offset + count
        // Ein Bereich überlappt, wenn er vor [end] beginnt und nach [offset] endet. Er beginnt dann höchstens so weit
        // vor [offset], wie der längste geplante Bereich ist.
        val hits = ArrayList<Planned>()
        for (list in byStart.subMap(offset - maxPlannedLength, true, end, false).values) {
            for (entry in list) if (entry.offset + entry.bytes.size > offset) hits.add(entry)
        }
        hits.sortBy { it.seq }
        for (entry in hits) {
            val from = maxOf(offset, entry.offset)
            val to = minOf(end, entry.offset + entry.bytes.size)
            if (from < to) {
                entry.bytes.copyInto(
                    out,
                    destinationOffset = (from - offset).toInt(),
                    startIndex = (from - entry.offset).toInt(),
                    endIndex = (to - entry.offset).toInt(),
                )
            }
        }
        return out
    }

    override fun write(
        offset: Long,
        bytes: ByteArray,
    ) {
        plannedBytes += bytes.size
        if (plannedBytes > ScrubLimits.MAX_READ || planned.size >= ScrubLimits.MAX_PLANNED) {
            bail(NotRemovableReason.STRUCTURE)
        }
        val entry = Planned(planned.size, offset, bytes.copyOf())
        planned.add(entry)
        byStart.getOrPut(offset) { ArrayList(1) }.add(entry)
        maxPlannedLength = maxOf(maxPlannedLength, bytes.size.toLong())
    }

    /**
     * Schreibt alle geplanten Bereiche in der Reihenfolge der Planung, danach kürzt es. Spätere Bereiche gewinnen. Der
     * Aufruf erfolgt nur nach bestandener Prüfung (ADR 0015, Plan/Apply).
     */
    fun applyTo(target: ByteStore) {
        for (entry in planned) target.write(entry.offset, entry.bytes)
        truncatedTo?.let { target.truncate(it) }
    }
}

/**
 * Ein Durchlauf der Walker. Mit [mutate] = `true` werden Standortbytes überschrieben, sonst nur gezählt (Detektor
 * der Prüfung nach dem Bereinigen, ADR 0015, Prüfung). Jeder Fund steht in [findings]. [writes] zählt die
 * tatsächlichen Schreibvorgänge, damit der Aufrufer geänderte Chunks erkennt.
 *
 * [view] liefert eine Sicht auf einen anderen Speicher (zum Beispiel die logischen Bytes eines HEIF-Items), die
 * Zähler, Funde und Schreibvorgänge mit dem Ursprungsdurchlauf teilt.
 */
internal class ScrubPass private constructor(
    val store: ByteStore,
    val mutate: Boolean,
    private val state: State,
) {
    constructor(store: ByteStore, mutate: Boolean) : this(store, mutate, State())

    private class State {
        val findings = sortedSetOf<String>()
        var writes = 0
        val counts = HashMap<String, Int>()
        val claimed = ArrayList<Pair<Long, Long>>()
    }

    val findings: SortedSet<String> get() = state.findings
    val writes: Int get() = state.writes

    /** Bereiche, die ein Walker als eigene Bilder oder Container bereits verarbeitet hat (MPF-Zusatzbilder). */
    val claimed: MutableList<Pair<Long, Long>> get() = state.claimed

    /** Sicht auf einen anderen Speicher mit gemeinsamen Zählern, Funden und Schreibvorgängen. */
    fun view(other: ByteStore): ScrubPass = ScrubPass(other, mutate, state)

    /** Zählt Einträge einer Art. Über [ScrubLimits.MAX_ENTRIES] gilt die Struktur als unsicher. */
    fun count(kind: String) {
        val next = (state.counts[kind] ?: 0) + 1
        if (next > ScrubLimits.MAX_ENTRIES) bail(NotRemovableReason.STRUCTURE)
        state.counts[kind] = next
    }

    fun bounds(
        offset: Long,
        count: Long,
        end: Long,
    ) {
        if (offset < 0 || count < 0 || end > store.length || offset > end || count > end - offset) {
            bail(NotRemovableReason.STRUCTURE)
        }
    }

    fun bytes(
        offset: Long,
        count: Long,
        end: Long,
    ): ByteArray {
        bounds(offset, count, end)
        if (count > ScrubLimits.MAX_READ) bail(NotRemovableReason.STRUCTURE)
        return store.read(offset, count.toInt())
    }

    fun u8(
        offset: Long,
        end: Long,
    ): Int = bytes(offset, 1, end)[0].toInt() and 0xFF

    fun u16(
        offset: Long,
        end: Long,
        big: Boolean,
    ): Int {
        val b = bytes(offset, 2, end)
        val hi = b[if (big) 0 else 1].toInt() and 0xFF
        val lo = b[if (big) 1 else 0].toInt() and 0xFF
        return (hi shl 8) or lo
    }

    fun u32(
        offset: Long,
        end: Long,
        big: Boolean,
    ): Long {
        val b = bytes(offset, 4, end)
        var value = 0L
        for (i in 0 until 4) {
            val index = if (big) i else 3 - i
            value = (value shl 8) or (b[index].toLong() and 0xFF)
        }
        return value
    }

    fun u32Be(
        offset: Long,
        end: Long,
    ): Long = u32(offset, end, big = true)

    /**
     * Überschreibt [count] Bytes ab [offset] mit [fill]. Ein Fund entsteht, wenn ein Byte davon abweicht. Die
     * Länge bleibt gleich, deshalb bleiben alle Strukturverweise gültig.
     */
    fun blank(
        offset: Long,
        count: Long,
        fill: Byte,
        finding: String,
    ) {
        if (count == 0L) return
        bounds(offset, count, store.length)
        if (count > ScrubLimits.MAX_READ) bail(NotRemovableReason.STRUCTURE)
        overwrite(offset, ByteArray(count.toInt()) { fill }, finding)
    }

    /**
     * Kürzt die Datei auf [to] Bytes. Alles dahinter ist ein Anhang nach dem logischen Dateiende (ADR 0015) und wird
     * entfernt. Ein Fund entsteht, wenn es etwas abzuschneiden gibt. Gekürzt wird nur im Änderungsmodus, die Prüfung
     * zählt nur.
     */
    fun truncate(
        to: Long,
        finding: String,
    ) {
        if (to < 0 || to > store.length) bail(NotRemovableReason.STRUCTURE)
        if (to == store.length) return
        state.findings.add(finding)
        if (mutate) store.truncate(to)
    }

    /**
     * Ersetzt die Bytes ab [offset] durch [bytes] gleicher Länge (etwa einen Box-Typ durch `free`). Ein Fund entsteht,
     * wenn sich etwas ändert.
     */
    fun overwrite(
        offset: Long,
        bytes: ByteArray,
        finding: String,
    ) {
        if (bytes.isEmpty()) return
        bounds(offset, bytes.size.toLong(), store.length)
        val current = store.read(offset, bytes.size)
        if (current.contentEquals(bytes)) return
        state.findings.add(finding)
        if (mutate) {
            store.write(offset, bytes)
            state.writes++
        }
    }
}

/**
 * Textsuche nach Standort-Resten im Byte-Strom. Erkennt ISO-6709-Koordinaten und nicht geleerte GPS-Eigenschaften
 * (ADR 0015, Prüfung danach). Streamend, ohne den Bereich komplett zu laden.
 */
internal object LocationText {
    private const val WINDOW = 64 * 1024
    private const val OVERLAP = 256
    private val iso6709 = Regex("[+-]\\d{1,3}\\.\\d+[+-]\\d{1,3}\\.\\d+")
    private val gpsProperty =
        Regex("(?:GPSLatitude|GPSLongitude|exif:GPS[A-Za-z]+)[\"']?\\s*(?:=\\s*[\"']|>)([^\"'<]{0,80})")

    /** Prüft einen Text. Ein GPS-Eigenschaft zählt nur, wenn ihr Wert eine Ziffer enthält. */
    fun containsIn(text: String): Boolean =
        iso6709.containsMatchIn(text) ||
            gpsProperty.findAll(text).any { match -> match.groupValues[1].any { it.isDigit() } }

    /** Prüft den Bereich [start, end) des Speichers in Fenstern. */
    fun containsIn(
        store: ByteStore,
        start: Long,
        end: Long,
    ): Boolean {
        var pos = start
        while (true) {
            val count = minOf(WINDOW.toLong(), end - pos).toInt()
            if (count <= 0) return false
            val window = String(store.read(pos, count), Charsets.ISO_8859_1)
            if (containsIn(window)) return true
            if (pos + count >= end) return false
            pos += WINDOW - OVERLAP
        }
    }

    /**
     * Prüft den ganzen Strom in Fenstern von 64 KiB und füttert [digest] (falls angegeben) mit denselben Bytes. Ein
     * Durchlauf liefert Scan und Hash zugleich (ADR 0015, ein Lesedurchgang). Liefert `true` bei einem Fund.
     */
    fun scanAll(
        store: ByteStore,
        digest: MessageDigest?,
    ): Boolean {
        var pos = 0L
        var carry = ""
        while (pos < store.length) {
            val count = minOf(WINDOW.toLong(), store.length - pos).toInt()
            val bytes = store.read(pos, count)
            digest?.update(bytes)
            val window = carry + String(bytes, Charsets.ISO_8859_1)
            if (containsIn(window)) return true
            carry = window.takeLast(OVERLAP)
            pos += count
        }
        return false
    }
}

/** ASCII-Vergleich eines Bereichs mit einer festen Byte-Folge. */
internal fun ScrubPass.startsWith(
    offset: Long,
    end: Long,
    prefix: ByteArray,
): Boolean = end - offset >= prefix.size && bytes(offset, prefix.size.toLong(), end).contentEquals(prefix)

internal fun ascii(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)
