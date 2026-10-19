package io.github.labushuya.emberlight.share.space.location

import io.github.labushuya.emberlight.share.space.location.LocationFixtures.ascii
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.be16
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.be32
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.box
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.concat
import java.io.ByteArrayOutputStream

/**
 * Synthetische HEIF- und AVIF-Dateien (ADR 0015, Tests). Alles entsteht im Testcode. Die Items folgen dem ISO/IEC
 * 23008-12-Aufbau: `meta` als FullBox mit `hdlr` `pict`, `iinf` mit `infe`, `iloc` (Versionen 0 bis 2), `idat`,
 * `mdat`.
 *
 * Die Extents eines Items liegen physisch in umgekehrter Reihenfolge und mit Lücken dazwischen, damit der Test die
 * logisch-physische Abbildung prüft. Die Lücken enthalten ein Muster, das kein Standort ist.
 */
internal object HeifFixtures {
    const val METHOD_FILE = 0
    const val METHOD_IDAT = 1
    const val METHOD_ITEM = 2

    private val GAP = ascii("~~GAP~~")

    /**
     * Ein Item für [standard]. [data] ist die logische Nutzlast, [extents] die Anzahl der Extents, [reversed] legt
     * sie physisch rückwärts ab.
     */
    class Item(
        val id: Int,
        val type: String,
        val data: ByteArray,
        val contentType: String? = null,
        val method: Int = METHOD_FILE,
        val extents: Int = 1,
        val reversed: Boolean = false,
        val listed: Boolean = true,
    )

    /** Ein Eintrag der `iloc`-Box: Extents als Paare aus Offset und Länge in der Konstruktionsmethode. */
    class IlocEntry(
        val id: Long,
        val method: Int,
        val extents: List<Pair<Long, Long>>,
        val dataReference: Int = 0,
    )

    /**
     * Exif-Item. Die ersten vier Bytes sind der BE-Offset des TIFF-Headers ab ihrem Ende. Der Standard ist 6 mit
     * `Exif\0\0`-Präfix, wie es übliche HEIC-Dateien tragen. Mit [offset] 0 und Präfix liegt das TIFF nach dem Präfix
     * (robuster Fall des Walkers). Ohne Präfix steht das TIFF direkt hinter dem Offset.
     */
    fun exifItem(
        tiff: ByteArray,
        offset: Long = 6,
        prefix: Boolean = true,
    ): ByteArray = be32(offset) + (if (prefix) ascii("Exif\u0000\u0000") else ByteArray(0)) + tiff

    /** Vollständige Datei mit Items. Der Offset der Datei-Items wird in zwei Durchläufen festgelegt (feste Feldgrößen). */
    fun standard(
        brand: String,
        items: List<Item>,
        ilocVersion: Int = 1,
        extraMetaChildren: List<ByteArray> = emptyList(),
    ): ByteArray {
        val ftyp = ftyp(brand)
        val first = layout(items, ilocVersion, mdatStart = 0L, extraMetaChildren = extraMetaChildren)
        val mdatStart = ftyp.size + first.meta.size + 8L
        val second = layout(items, ilocVersion, mdatStart, extraMetaChildren)
        return ftyp + second.meta + box("mdat", second.mdat)
    }

    /** Datei aus fertigen Teilen, für gezielt kaputte Strukturen. */
    fun raw(
        brand: String,
        metaChildren: List<ByteArray>,
        mdat: ByteArray,
    ): ByteArray = ftyp(brand) + fullBox("meta", 0, concat(*metaChildren.toTypedArray())) + box("mdat", mdat)

    fun ftyp(brand: String): ByteArray = box("ftyp", ascii(brand) + be32(0) + ascii(brand) + ascii("mif1"))

    fun fullBox(
        type: String,
        version: Int,
        payload: ByteArray,
    ): ByteArray = box(type, byteArrayOf(version.toByte(), 0, 0, 0) + payload)

    fun hdlr(handler: String = "pict"): ByteArray =
        fullBox("hdlr", 0, be32(0) + ascii(handler) + ByteArray(12) + byteArrayOf(0))

    /** `infe` Version 2 mit 16-Bit-ID. Der Name ist leer, der Content-Type folgt nur bei `mime`. */
    fun infe(
        id: Int,
        type: String,
        contentType: String? = null,
    ): ByteArray {
        val mime = if (contentType != null) ascii(contentType) + byteArrayOf(0) else ByteArray(0)
        return fullBox("infe", 2, be16(id) + be16(0) + ascii(type) + byteArrayOf(0) + mime)
    }

    /** `infe` Version 3 mit 32-Bit-ID. */
    fun infeV3(
        id: Long,
        type: String,
        contentType: String? = null,
    ): ByteArray {
        val mime = if (contentType != null) ascii(contentType) + byteArrayOf(0) else ByteArray(0)
        return fullBox("infe", 3, be32(id) + be16(0) + ascii(type) + byteArrayOf(0) + mime)
    }

    fun iinf(vararg entries: ByteArray): ByteArray = fullBox("iinf", 0, be16(entries.size) + concat(*entries))

    /** `iloc` mit Offset- und Längen-Feldern zu je 4 Bytes und ohne Basis-Offset (ADR 0015, Tests). */
    fun iloc(
        version: Int,
        entries: List<IlocEntry>,
    ): ByteArray {
        val header = byteArrayOf(0x44, 0x00)
        val count = if (version < 2) be16(entries.size) else be32(entries.size.toLong())
        val body =
            entries.map { entry ->
                val id = if (version < 2) be16(entry.id.toInt()) else be32(entry.id)
                val method = if (version >= 1) be16(entry.method) else ByteArray(0)
                val extents = entry.extents.map { (offset, length) -> be32(offset) + be32(length) }
                id + method + be16(entry.dataReference) + be16(entry.extents.size) + concat(*extents.toTypedArray())
            }
        return fullBox("iloc", version, header + count + concat(*body.toTypedArray()))
    }

    private class Layout(
        val meta: ByteArray,
        val mdat: ByteArray,
    )

    private fun layout(
        items: List<Item>,
        ilocVersion: Int,
        mdatStart: Long,
        extraMetaChildren: List<ByteArray>,
    ): Layout {
        val mdat = ByteArrayOutputStream()
        val idat = ByteArrayOutputStream()
        val entries = ArrayList<IlocEntry>()
        for (item in items) {
            val parts = split(item.data, item.extents)
            val target = if (item.method == METHOD_IDAT) idat else mdat
            val order = if (item.reversed) parts.indices.reversed() else parts.indices.toList()
            val positions = LongArray(parts.size)
            for (index in order) {
                positions[index] = if (item.method == METHOD_IDAT) idat.size().toLong() else mdatStart + mdat.size()
                target.write(parts[index])
                if (parts.size > 1) target.write(GAP)
            }
            entries.add(
                IlocEntry(
                    id = item.id.toLong(),
                    method = item.method,
                    extents = parts.indices.map { positions[it] to parts[it].size.toLong() },
                ),
            )
        }
        val children = ArrayList<ByteArray>()
        children.add(hdlr())
        children.add(fullBox("pitm", 0, be16(1)))
        children.add(iinf(*items.filter { it.listed }.map { infe(it.id, it.type, it.contentType) }.toTypedArray()))
        children.add(iloc(ilocVersion, entries))
        if (idat.size() > 0) children.add(box("idat", idat.toByteArray()))
        children.addAll(extraMetaChildren)
        return Layout(fullBox("meta", 0, concat(*children.toTypedArray())), mdat.toByteArray())
    }

    /** Teilt [data] in [count] annähernd gleich große Stücke. Die Summe bleibt [data]. */
    private fun split(
        data: ByteArray,
        count: Int,
    ): List<ByteArray> {
        if (count <= 1) return listOf(data)
        val size = (data.size + count - 1) / count
        return data.toList().chunked(size).map { it.toByteArray() }
    }
}
