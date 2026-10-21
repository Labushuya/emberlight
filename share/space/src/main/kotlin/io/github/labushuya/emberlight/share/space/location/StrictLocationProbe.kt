package io.github.labushuya.emberlight.share.space.location

/**
 * Strenge Gegenprobe (ADR 0015, Nachkontrolle). Bewusst unabhängig von den Walkern: Sie sucht im Rohstrom nach jedem
 * TIFF-Header (`II*\0` bzw. `MM\0*`), also auch in eingebetteten Strukturen wie APP10, Thumbnails oder IRB-Ressourcen.
 * Zu jedem gültigen IFD0 mit GPS-Zeiger wird das GPS-IFD gelesen. Es zählt jedes Byte ungleich null darin als Befund.
 * Dazu Textmuster: GPS-Eigenschaften mit Ziffern in ihrem Wert und ISO-6709-Koordinaten (XMP, Container).
 *
 * Die TIFF-Suche gilt nur für Formate, in denen ein TIFF am Stück im Strom liegt (JPEG, PNG, WebP, GIF). In HEIF liegt
 * ein Exif-Item oft in verstreuten Extents; dort würde ein Rohparsen falsch lesen. Dort gilt nur die Textsuche.
 *
 * Fail closed bei Befunden: Ein gültiger GPS-Zeiger, dessen GPS-IFD nicht lesbar ist, ist ein Befund. Ein Kandidat für
 * einen TIFF-Header, der sich nicht als TIFF lesen lässt (etwa zufällige Bytes in komprimierten Bilddaten), ist keiner.
 * Liefert nur Befundnamen, nie Werte. Der Strom wird in einem Durchlauf gelesen.
 */
internal object StrictLocationProbe {
    private const val WINDOW = 64 * 1024

    /** Überlappung der Fenster. Größer als jedes Textmuster, damit ein Muster an der Grenze nicht verloren geht. */
    private const val OVERLAP = 512
    private const val MAX_IFD_ENTRIES = 1024
    private const val TAG_GPS_POINTER = 0x8825
    private const val ENTRY_SIZE = 12L

    private val ISO_6709 = Regex("[+-]\\d{1,3}\\.\\d+[+-]\\d{1,3}\\.\\d+")
    private val GPS_VALUE =
        Regex("GPS(?:Latitude|Longitude|Altitude|Timestamp|DateStamp)\\s*(?:=\\s*[\"']|>)[^<\"']{0,80}\\d")

    /**
     * Befunde der Gegenprobe über den ganzen Strom. Leer heißt: kein Standort gefunden. [scanTiff] schaltet die
     * TIFF-Suche ab (Formate mit verstreuten Extents, siehe Kopf der Datei).
     */
    fun findings(
        store: ByteStore,
        scanTiff: Boolean,
    ): Set<String> {
        val found = sortedSetOf<String>()
        val length = store.length
        var pos = 0L
        while (pos < length) {
            val count = minOf(WINDOW.toLong(), length - pos).toInt()
            val bytes = store.read(pos, count)
            val last = pos + count >= length
            if (scanTiff) {
                // Dieses Fenster besitzt die Header-Positionen bis zur Überlappung, das nächste Fenster den Rest.
                val headerEnd = if (last) count - 3 else count - OVERLAP
                for (i in 0 until headerEnd) {
                    if (isTiffHeader(bytes, i)) tiffFinding(store, pos + i)?.let { found += it }
                }
            }
            val text = String(bytes, Charsets.ISO_8859_1)
            if (ISO_6709.containsMatchIn(text) || GPS_VALUE.containsMatchIn(text)) found += "location_text"
            if (last) break
            pos += count - OVERLAP
        }
        return found
    }

    /** Ein TIFF-Header bei [at]. Liest IFD0 und das GPS-IFD. Liefert den Befundnamen oder `null`. */
    private fun tiffFinding(
        store: ByteStore,
        at: Long,
    ): String? {
        val big = (store.read(at, 1)[0] == 'M'.code.toByte())
        val tiff = Tiff(store, big)
        val ifd0 = at + (tiff.u32(at + 4) ?: return null)
        val entries = tiff.u16(ifd0) ?: return null
        if (entries > MAX_IFD_ENTRIES) return null
        var gpsOffset: Long? = null
        for (index in 0 until entries) {
            val entry = ifd0 + 2 + index * ENTRY_SIZE
            if (tiff.u16(entry) != TAG_GPS_POINTER) continue
            gpsOffset = if (tiff.u16(entry + 2) == 3) tiff.u16(entry + 8)?.toLong() else tiff.u32(entry + 8)
            break
        }
        val gps = gpsOffset ?: return null
        val gpsIfd = at + gps
        val gpsEntries = tiff.u16(gpsIfd) ?: return "gps_ifd_unreadable"
        if (gpsEntries > MAX_IFD_ENTRIES) return "gps_ifd_unreadable"
        // Zähler, Einträge und Next-Zeiger: Jedes Byte ungleich null zählt als Standort-Rest.
        val region = tiff.bytes(gpsIfd, (2 + gpsEntries * ENTRY_SIZE + 4).toInt()) ?: return "gps_ifd_unreadable"
        return if (region.any { it != 0.toByte() }) "gps_ifd_nonzero" else null
    }

    private fun isTiffHeader(
        b: ByteArray,
        i: Int,
    ): Boolean =
        (b[i] == 0x49.toByte() && b[i + 1] == 0x49.toByte() && b[i + 2] == 0x2A.toByte() && b[i + 3] == 0.toByte()) ||
            (b[i] == 0x4D.toByte() && b[i + 1] == 0x4D.toByte() && b[i + 2] == 0.toByte() && b[i + 3] == 0x2A.toByte())

    /** Lesen aus dem Strom mit Grenzprüfung. Außerhalb liefert alles `null`, nie eine Ausnahme. */
    private class Tiff(
        private val store: ByteStore,
        private val big: Boolean,
    ) {
        fun bytes(
            offset: Long,
            count: Int,
        ): ByteArray? {
            if (offset < 0 || count < 0 || offset + count > store.length) return null
            return store.read(offset, count)
        }

        fun u16(offset: Long): Int? {
            val b = bytes(offset, 2) ?: return null
            val hi = b[if (big) 0 else 1].toInt() and 0xFF
            val lo = b[if (big) 1 else 0].toInt() and 0xFF
            return (hi shl 8) or lo
        }

        fun u32(offset: Long): Long? {
            val b = bytes(offset, 4) ?: return null
            var value = 0L
            for (i in 0 until 4) {
                val index = if (big) i else 3 - i
                value = (value shl 8) or (b[index].toLong() and 0xFF)
            }
            return value
        }
    }
}
