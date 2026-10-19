package io.github.labushuya.emberlight.share.space.location

/**
 * WebP-Scrubber (ADR 0015, Fundorte, Google RIFF-Container). Alle Chunks `EXIF` (mit oder ohne `Exif\0\0`-Präfix) und
 * `XMP ` (mit Leerzeichen) werden verarbeitet, auch mehrfache. Die RIFF-Größe bleibt gleich, weil nur überschrieben
 * wird.
 */
internal object WebpScrubber {
    private val EXIF_PREFIX = ascii("Exif\u0000\u0000")

    fun scrubFile(pass: ScrubPass) {
        val size = pass.store.length
        if (pass.bytes(0, 4, size).toString(Charsets.ISO_8859_1) != "RIFF") bail(NotRemovableReason.STRUCTURE)
        if (pass.bytes(8, 4, size).toString(Charsets.ISO_8859_1) != "WEBP") bail(NotRemovableReason.STRUCTURE)
        val riffEnd = 8 + pass.u32(4, size, big = false)
        if (riffEnd < 12 || riffEnd > size) bail(NotRemovableReason.STRUCTURE)
        var p = 12L
        while (p < riffEnd) {
            pass.count("riff_chunks")
            val fourcc = pass.bytes(p, 4, riffEnd).toString(Charsets.ISO_8859_1)
            val length = pass.u32(p + 4, riffEnd, big = false)
            val dataStart = p + 8
            val dataEnd = dataStart + length
            pass.bounds(dataStart, length, riffEnd)
            when (fourcc) {
                "EXIF" -> {
                    val base =
                        if (pass.startsWith(
                                dataStart,
                                dataEnd,
                                EXIF_PREFIX,
                            )
                        ) {
                            dataStart + EXIF_PREFIX.size
                        } else {
                            dataStart
                        }
                    TiffScrubber.scrub(pass, base, dataEnd, 1)
                }

                "XMP " -> {
                    XmpScrubber.scrubRegion(pass, dataStart, dataEnd)
                }
            }
            // Chunks sind auf gerade Länge gepolstert.
            p = dataEnd + (length % 2)
        }
        // Alles hinter der RIFF-Größe ist ein Anhang und wird abgeschnitten (ADR 0015, Anhänge).
        pass.truncate(riffEnd, CUT_FINDING)
    }
}
