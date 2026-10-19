package io.github.labushuya.emberlight.share.space.location

/**
 * GIF-Scrubber (ADR 0015, Fundorte, Adobe XMP Part 3). Geprüft wird die Application Extension `XMP DataXMP`. Ihr
 * XMP-Text wird nur mit 0x20 überschrieben, nie mit 0x00. Der Magic-Trailer und die Blockstruktur bleiben
 * unverändert. Bilder und andere Erweiterungen werden übersprungen, nur die Blöcke werden gelesen.
 */
internal object GifScrubber {
    private val XMP_IDENTIFIER = ascii("XMP DataXMP")
    private val XPACKET_END = ascii("<?xpacket end")

    /** Ein Teil der logischen XMP-Daten, der in der Datei bei [fileStart] liegt. */
    private class Piece(
        val fileStart: Long,
        val length: Int,
    )

    fun scrubFile(pass: ScrubPass) {
        val size = pass.store.length
        val signature = pass.bytes(0, 6, size).toString(Charsets.ISO_8859_1)
        if (signature != "GIF87a" && signature != "GIF89a") bail(NotRemovableReason.STRUCTURE)
        var p = 13L
        val packed = pass.u8(10, size)
        if ((packed and 0x80) != 0) p += colorTableSize(packed)
        while (true) {
            pass.count("gif_blocks")
            when (pass.u8(p, size)) {
                0x3B -> {
                    if (!JpegScrubber.allZero(pass, p + 1, size)) bail(NotRemovableReason.TRAILING_DATA)
                    return
                }

                0x21 -> {
                    p = extension(pass, p, size)
                }

                0x2C -> {
                    val imagePacked = pass.u8(p + 9, size)
                    p += 10
                    if ((imagePacked and 0x80) != 0) p += colorTableSize(imagePacked)
                    // Mindestcode für LZW, danach die Datenblöcke.
                    p = skipSubBlocks(pass, p + 1, size)
                }

                else -> {
                    bail(NotRemovableReason.STRUCTURE)
                }
            }
        }
    }

    private fun colorTableSize(packed: Int): Long = 3L * (1L shl ((packed and 0x07) + 1))

    /** Erweiterung ab [at] (Introducer 0x21). Liefert die Position nach der Erweiterung. */
    private fun extension(
        pass: ScrubPass,
        at: Long,
        size: Long,
    ): Long {
        val label = pass.u8(at + 1, size)
        if (label != 0xFF) return skipSubBlocks(pass, at + 2, size)
        val blockSize = pass.u8(at + 2, size)
        if (blockSize != 11) return skipSubBlocks(pass, at + 3 + blockSize, size)
        val identifier = pass.bytes(at + 3, 11, size)
        if (!identifier.contentEquals(XMP_IDENTIFIER)) return skipSubBlocks(pass, at + 14, size)
        return xmp(pass, at + 14, size)
    }

    /** XMP-Daten aus den Datenblöcken ab [from] lesen, Standort darin überschreiben, Position nach dem Ende liefern. */
    private fun xmp(
        pass: ScrubPass,
        from: Long,
        size: Long,
    ): Long {
        val pieces = ArrayList<Piece>()
        val logical = java.io.ByteArrayOutputStream()
        var p = from
        while (true) {
            pass.count("gif_subblocks")
            val length = pass.u8(p, size)
            if (length == 0) break
            pass.bounds(p + 1, length.toLong(), size)
            pieces.add(Piece(p + 1, length))
            logical.write(pass.bytes(p + 1, length.toLong(), size))
            if (logical.size() > ScrubLimits.MAX_TEXT) bail(NotRemovableReason.STRUCTURE)
            p += 1 + length
        }
        val data = logical.toByteArray()
        val end = endOfPacket(data)
        if (end < 0) {
            // Ohne Paketende ist der Bereich nicht eindeutig abgrenzbar: Enthält er Standort, gilt er als unsicher.
            if (LocationText.containsIn(String(data, Charsets.ISO_8859_1))) bail(NotRemovableReason.STRUCTURE)
            return p + 1
        }
        for (span in XmpScrubber.locationSpans(data.copyOfRange(0, end))) {
            blankLogical(pass, pieces, span.start, span.end)
        }
        return p + 1
    }

    /** Position nach dem `?>` des Paketendes, oder -1. */
    private fun endOfPacket(data: ByteArray): Int {
        val text = String(data, Charsets.ISO_8859_1)
        val marker = text.indexOf(String(XPACKET_END, Charsets.ISO_8859_1))
        if (marker < 0) return -1
        val close = text.indexOf("?>", marker)
        return if (close < 0) -1 else close + 2
    }

    /** Überschreibt die logischen Bytes [start, end) über die Datenblöcke mit 0x20. */
    private fun blankLogical(
        pass: ScrubPass,
        pieces: List<Piece>,
        start: Int,
        end: Int,
    ) {
        var logicalStart = 0
        for (piece in pieces) {
            val pieceEnd = logicalStart + piece.length
            val from = maxOf(start, logicalStart)
            val to = minOf(end, pieceEnd)
            if (from < to) {
                pass.blank(piece.fileStart + (from - logicalStart), (to - from).toLong(), 0x20, "xmp_location")
            }
            logicalStart = pieceEnd
        }
    }

    /** Überspringt Datenblöcke ab [from] bis zum Terminator 0x00. Liefert die Position nach dem Terminator. */
    private fun skipSubBlocks(
        pass: ScrubPass,
        from: Long,
        size: Long,
    ): Long {
        var p = from
        while (true) {
            pass.count("gif_subblocks")
            val length = pass.u8(p, size)
            if (length == 0) return p + 1
            p += 1 + length
        }
    }
}
