package io.github.labushuya.emberlight.share.space.location

/**
 * WebP-Scrubber (ADR 0015, Fundorte, Google RIFF-Container). Alle Chunks `EXIF` (mit oder ohne `Exif\0\0`-Präfix) und
 * `XMP ` (mit Leerzeichen) werden verarbeitet, auch mehrfache. `ANMF` (Animationsbilder) wird rekursiv durchlaufen, denn
 * dort können eigene `EXIF`- und `XMP `-Chunks stehen. Unbekannte Chunks werden längenerhaltend mit 0x00 überschrieben
 * (ADR 0015, Nachtrag). Die RIFF-Größe bleibt gleich, weil nur überschrieben wird.
 */
internal object WebpScrubber {
    private val EXIF_PREFIX = ascii("Exif\u0000\u0000")

    /** Chunks mit Bild- oder Darstellungsdaten, die keine Standortquelle sind. Alles andere wird genullt. */
    private val KEPT = setOf("VP8 ", "VP8L", "VP8X", "ALPH", "ANIM", "ICCP")
    private const val ANMF_HEADER = 16L

    fun scrubFile(pass: ScrubPass) {
        val size = pass.store.length
        if (pass.bytes(0, 4, size).toString(Charsets.ISO_8859_1) != "RIFF") bail(NotRemovableReason.STRUCTURE)
        if (pass.bytes(8, 4, size).toString(Charsets.ISO_8859_1) != "WEBP") bail(NotRemovableReason.STRUCTURE)
        val riffEnd = 8 + pass.u32(4, size, big = false)
        if (riffEnd < 12 || riffEnd > size) bail(NotRemovableReason.STRUCTURE)
        walkChunks(pass, 12, riffEnd, 0)
        // Alles hinter der RIFF-Größe ist ein Anhang und wird abgeschnitten (ADR 0015, Anhänge).
        pass.truncate(riffEnd, CUT_FINDING)
    }

    private fun walkChunks(
        pass: ScrubPass,
        from: Long,
        end: Long,
        depth: Int,
    ) {
        if (depth > ScrubLimits.MAX_DEPTH) bail(NotRemovableReason.STRUCTURE)
        var p = from
        while (p < end) {
            pass.count("riff_chunks")
            val fourcc = pass.bytes(p, 4, end).toString(Charsets.ISO_8859_1)
            val length = pass.u32(p + 4, end, big = false)
            val dataStart = p + 8
            val dataEnd = dataStart + length
            pass.bounds(dataStart, length, end)
            when (fourcc) {
                "EXIF" -> {
                    val hasPrefix = pass.startsWith(dataStart, dataEnd, EXIF_PREFIX)
                    TiffScrubber.scrub(pass, if (hasPrefix) dataStart + EXIF_PREFIX.size else dataStart, dataEnd, 1)
                }

                "XMP " -> {
                    XmpScrubber.scrubRegion(pass, dataStart, dataEnd)
                }

                "ANMF" -> {
                    pass.bounds(dataStart, ANMF_HEADER, dataEnd)
                    walkChunks(pass, dataStart + ANMF_HEADER, dataEnd, depth + 1)
                }

                in KEPT -> {
                    // Bild- und Darstellungsdaten: keine Standortquelle, nichts zu bereinigen.
                }

                else -> {
                    pass.blank(dataStart, length, 0x00, "riff_unknown")
                }
            }
            // Chunks sind auf gerade Länge gepolstert.
            p = dataEnd + (length % 2)
        }
    }
}
