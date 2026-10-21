package io.github.labushuya.emberlight.share.space.location

import io.github.labushuya.emberlight.share.space.location.LocationFixtures.ascii
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.be32
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.box
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.concat
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.pngChunk
import java.util.Random

/**
 * Synthetische Dateien für die binären Restrisiken (ADR 0015, Nachtrag 3.1b-2). Alles wird im Testcode erzeugt, keine
 * Nutzerdateien. Die Bytes sind absichtlich klein und nur so aufgebaut, dass Walker und Gegenprobe sie lesen.
 */
internal object BinaryLocationFixtures {
    private val FULL_HEADER = ByteArray(4)

    /** Ergebnis eines MP4-Baus: Datei und die Bereiche der Sample-Daten in `mdat`. */
    class Mp4(
        val bytes: ByteArray,
        val sampleRanges: List<IntRange>,
    )

    /** TIFF mit GPS-IFD, dessen Einträge und Werte ungleich null sind. */
    fun gpsTiff(): ByteArray = LocationFixtures.exifTiff().bytes

    /** Ein GPMF-Sample mit Beschleunigung. [withGps] fügt den Standort-Schlüssel `GPS5` hinzu. */
    fun gpmfSample(withGps: Boolean): ByteArray {
        val accl = ascii("ACCL") + byteArrayOf(0x73, 6, 0, 1) + ByteArray(6) { 0x11 }
        val gps = if (withGps) ascii("GPS5") + byteArrayOf(0x6C, 20, 0, 1) + ByteArray(20) { 0x22 } else ByteArray(0)
        return ascii("DEVC") + FULL_HEADER + accl + gps
    }

    /** camm-Record: zwei Nullbytes, dann der Typ als Little-Endian-Zahl, dann Nutzdaten. Typ 5 und 6 sind GPS. */
    fun cammSample(type: Int): ByteArray = byteArrayOf(0, 0, type.toByte(), 0) + ByteArray(24) { 0x33 }

    /** Ein DJI-Sample (`djmd`). Die Felder sind nicht sicher lesbar, der Track gilt als Standortträger. */
    fun djmdSample(): ByteArray = ByteArray(32) { 0x44 }

    /**
     * MP4 mit einem Track. [handler] und [entry] (Sample-Eintrag) bestimmen die Art. Ohne [withTables] fehlen die
     * Sample-Tabellen. Alle Samples liegen in einem Chunk in `mdat`. Der Chunk-Offset wird in zwei Durchläufen festgelegt,
     * weil `stco` feste Größe hat. [topLevel] kommt zwischen `ftyp` und `moov`.
     */
    fun mp4(
        handler: String,
        entry: String,
        samples: List<ByteArray>,
        withTables: Boolean = true,
        topLevel: ByteArray = ByteArray(0),
    ): Mp4 {
        val ftyp = box("ftyp", ascii("isom") + FULL_HEADER)
        val payload = concat(*samples.toTypedArray())

        fun moov(offset: Long): ByteArray =
            box(
                "moov",
                track(
                    handler,
                    box(entry, ByteArray(16)),
                    if (withTables) tables(samples.map { it.size }, offset) else ByteArray(0),
                ),
            )
        val probe = moov(0L)
        val mdatStart = ftyp.size + topLevel.size + probe.size + 8L
        val real = moov(mdatStart)
        val bytes = ftyp + topLevel + real + box("mdat", payload)
        val ranges = ArrayList<IntRange>()
        var at = mdatStart
        for (sample in samples) {
            ranges.add(at.toInt() until (at + sample.size).toInt())
            at += sample.size
        }
        return Mp4(bytes, ranges)
    }

    private fun track(
        handler: String,
        entry: ByteArray,
        tables: ByteArray,
    ): ByteArray {
        val stsd = box("stsd", FULL_HEADER + be32(1) + entry)
        val stbl = box("stbl", stsd + tables)
        val hdlr = box("hdlr", FULL_HEADER + ByteArray(4) + ascii(handler) + ByteArray(13))
        return box("trak", box("mdia", hdlr + box("minf", stbl)))
    }

    /** `stsz`, `stsc` (ein Chunk mit allen Samples) und `stco` mit dem Offset des Chunks. */
    private fun tables(
        sizes: List<Int>,
        offset: Long,
    ): ByteArray {
        val stsz =
            box(
                "stsz",
                FULL_HEADER + be32(0) + be32(sizes.size.toLong()) +
                    concat(*sizes.map { be32(it.toLong()) }.toTypedArray()),
            )
        val stsc = box("stsc", FULL_HEADER + be32(1) + be32(1) + be32(sizes.size.toLong()) + be32(1))
        val stco = box("stco", FULL_HEADER + be32(1) + be32(offset))
        return stsz + stsc + stco
    }

    /** Zufallsbytes mit eingestreutem TIFF-Kopf ohne sinnvollen IFD-Zeiger, wie in komprimierten Bilddaten. */
    fun randomWithTiffHeader(
        seed: Long,
        size: Int,
    ): ByteArray {
        val bytes = ByteArray(size).also { Random(seed).nextBytes(it) }
        val header = ascii("II*\u0000") + byteArrayOf(0xF0.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F)
        header.copyInto(bytes, destinationOffset = size / 3)
        return bytes
    }

    /** Hex-Profil wie ImageMagick (Zeilenumbruch, Profilname, Länge, Hex-Ziffern) mit [tiff] als Nutzlast. */
    fun profileBody(tiff: ByteArray): ByteArray {
        val hex = tiff.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return ascii("\nexif\n" + "%8d".format(tiff.size) + "\n" + hex + "\n")
    }

    /** tEXt-Chunk mit beliebigem Schlüsselwort. */
    fun textChunk(
        keyword: String,
        value: ByteArray,
    ): ByteArray = pngChunk("tEXt", ascii(keyword) + byteArrayOf(0) + value)

    /** iTXt-Chunk, unkomprimiert, mit leerer Sprache und leerem übersetztem Schlüssel. */
    fun itxtChunk(
        keyword: String,
        value: ByteArray,
    ): ByteArray = pngChunk("iTXt", ascii(keyword) + byteArrayOf(0, 0, 0, 0, 0) + value)

    /** IDAT-Chunk mit Nutzdaten, die einen TIFF-Kopf mit unbrauchbarem IFD-Zeiger enthalten. */
    fun idatWithTiffHeader(seed: Long): ByteArray = pngChunk("IDAT", randomWithTiffHeader(seed, 4096))
}
