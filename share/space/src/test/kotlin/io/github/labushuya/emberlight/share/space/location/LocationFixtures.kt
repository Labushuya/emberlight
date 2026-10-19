package io.github.labushuya.emberlight.share.space.location

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Synthetische Dateien mit Standort an allen Fundorten aus ADR 0015. Alles entsteht im Testcode, keine Fremd- oder
 * Nutzerdateien. [Synth] trägt zusätzlich die Zonen, in denen Standort oder Herstellerdaten liegen, damit der Test
 * prüfen kann, dass nur dort Bytes geändert werden.
 */
internal object LocationFixtures {
    /** Bytes und Zonen (Bereiche, relativ zum Anfang des Synthesestücks). */
    class Synth(
        val bytes: ByteArray,
        val zones: List<IntRange>,
    )

    private const val LOCATION_TEXT = "+48.8566+002.3522+035.000/"
    val XMP_UUID =
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

    /** XMP mit Standort als Element und als Attribut, Ortsnamen und einem Feld, das erhalten bleiben muss. */
    val XMP_WITH_LOCATION: String =
        """<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about="" xmlns:exif="http://ns.adobe.com/exif/1.0/" exif:GPSLongitude="13,24.2E"
 xmlns:photoshop="http://ns.adobe.com/photoshop/1.0/" xmlns:Iptc4xmpExt="http://iptc.org/std/Iptc4xmpExt/2008-02-29/"
 xmlns:dc="http://purl.org/dc/elements/1.1/">
<exif:GPSLatitude>48,51.3N</exif:GPSLatitude>
<photoshop:City>Berlinstadt</photoshop:City>
<photoshop:Country>Deutschland</photoshop:Country>
<dc:title>Urlaubsfoto</dc:title>
<Iptc4xmpExt:LocationCreated><rdf:Bag><rdf:li>Am Platz</rdf:li></rdf:Bag></Iptc4xmpExt:LocationCreated>
</rdf:Description>
</rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""

    // ---------- Grundbausteine ----------

    fun ascii(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

    fun le16(value: Int): ByteArray = byteArrayOf(value.toByte(), (value shr 8).toByte())

    fun le32(value: Long): ByteArray =
        byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    fun be16(value: Int): ByteArray = byteArrayOf((value shr 8).toByte(), value.toByte())

    fun be32(value: Long): ByteArray =
        byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    // ---------- EXIF/TIFF (Little Endian) ----------

    private fun entry(
        tag: Int,
        type: Int,
        count: Long,
        value: ByteArray,
    ): ByteArray {
        require(value.size == 4)
        return le16(tag) + le16(type) + le32(count) + value
    }

    /**
     * TIFF mit IFD0 (Exif-IFD, GPS-IFD, optional IFD1 mit eingebettetem Thumbnail). Layout fest, Offsets rechnen
     * sich aus
     * den Größen. Zonen: MakerNote, GPS-IFD und GPS-Werte.
     */
    fun exifTiff(thumbnail: ByteArray? = null): Synth {
        val ifd0 = 8L
        val exifIfd = ifd0 + 30
        val makerData = exifIfd + 18
        val gpsIfd = makerData + MAKER_NOTE.size
        val latData = gpsIfd + 66
        val lonData = latData + 24
        val ifd1 = lonData + 24
        val thumbData = ifd1 + 30
        val ifd0Bytes =
            le16(2) +
                entry(0x8769, 4, 1, le32(exifIfd)) +
                entry(0x8825, 4, 1, le32(gpsIfd)) +
                le32(if (thumbnail != null) ifd1 else 0L)
        val exifBytes = le16(1) + entry(0x927C, 7, MAKER_NOTE.size.toLong(), le32(makerData)) + le32(0)
        val gpsBytes =
            le16(5) +
                entry(0x0000, 1, 4, byteArrayOf(2, 3, 0, 0)) +
                entry(0x0001, 2, 2, byteArrayOf('N'.code.toByte(), 0, 0, 0)) +
                entry(0x0002, 5, 3, le32(latData)) +
                entry(0x0003, 2, 2, byteArrayOf('E'.code.toByte(), 0, 0, 0)) +
                entry(0x0004, 5, 3, le32(lonData)) +
                le32(0)
        val latBytes = rational(48, 1) + rational(51, 1) + rational(1867, 100)
        val lonBytes = rational(13, 1) + rational(24, 1) + rational(1220, 100)
        val ifd1Bytes: ByteArray
        val thumbBytes: ByteArray
        if (thumbnail != null) {
            ifd1Bytes =
                le16(2) +
                entry(0x0201, 4, 1, le32(thumbData)) +
                entry(0x0202, 4, 1, le32(thumbnail.size.toLong())) +
                le32(0)
            thumbBytes = thumbnail
        } else {
            ifd1Bytes = ByteArray(0)
            thumbBytes = ByteArray(0)
        }
        val header = ascii("II") + le16(42) + le32(ifd0)
        val bytes =
            concat(header, ifd0Bytes, exifBytes, MAKER_NOTE, gpsBytes, latBytes, lonBytes, ifd1Bytes, thumbBytes)
        val zones =
            listOf(
                makerData.toInt() until (makerData + MAKER_NOTE.size).toInt(),
                gpsIfd.toInt() until lonData.toInt() + 24,
            )
        return Synth(bytes, zones)
    }

    private val MAKER_NOTE = ascii("HERSTELLER-DATEN1")

    private fun rational(
        numerator: Long,
        denominator: Long,
    ): ByteArray = le32(numerator) + le32(denominator)

    // ---------- JPEG ----------

    fun segment(
        marker: Int,
        payload: ByteArray,
    ): ByteArray = byteArrayOf(0xFF.toByte(), marker.toByte()) + be16(payload.size + 2) + payload

    fun app1Exif(tiff: ByteArray): ByteArray = segment(0xE1, ascii("Exif\u0000\u0000") + tiff)

    fun app1Xmp(xmp: String): ByteArray = segment(0xE1, ascii("http://ns.adobe.com/xap/1.0/\u0000") + ascii(xmp))

    fun app1ExtendedXmp(xmp: String): ByteArray =
        segment(0xE1, ascii("http://ns.adobe.com/xmp/extension/\u0000") + ascii(xmp))

    /** IIM-Datensatz: Marker 0x1C, Record, Dataset, Länge, Wert. */
    fun iim(
        record: Int,
        dataset: Int,
        value: String,
    ): ByteArray {
        val data = ascii(value)
        return byteArrayOf(0x1C, record.toByte(), dataset.toByte()) + be16(data.size) + data
    }

    /** Photoshop-Ressource 8BIM mit leerem Namen (Feld auf 2 Bytes gepolstert) und Nutzdaten. */
    fun irb(
        id: Int,
        data: ByteArray,
    ): ByteArray {
        val padding = if (data.size % 2 == 1) byteArrayOf(0) else ByteArray(0)
        return ascii("8BIM") + be16(id) + byteArrayOf(0, 0) + be32(data.size.toLong()) + data + padding
    }

    fun app13Iptc(vararg datasets: ByteArray): ByteArray =
        segment(0xED, ascii("Photoshop 3.0\u0000") + irb(0x0404, concat(*datasets)))

    /**
     * MPF-Index in APP2 (Big Endian) mit einem Zusatzbild. Der Index-Eintrag des Zusatzbilds trägt die Größe und den
     * Offset relativ zum TIFF-Anfang des MPF-Headers. Die Feldgrößen sind fest, deshalb ändert ein anderer Offset
     * nichts an der Segmentlänge.
     */
    fun app2Mpf(
        primarySize: Long,
        secondarySize: Long,
        secondaryOffsetFromMpf: Long,
    ): ByteArray {
        val ifd = 8L
        val indexAt = ifd + 2 + 3 * 12L + 4
        val header = ascii("MM") + be16(42) + be32(ifd)
        val ifdBytes =
            be16(3) +
                be16(0xB000) + be16(7) + be32(4) + ascii("0100") +
                be16(0xB001) + be16(4) + be32(1) + be32(2) +
                be16(0xB002) + be16(7) + be32(32) + be32(indexAt) +
                be32(0)
        val primaryEntry = be32(0x20000000) + be32(primarySize) + be32(0) + ByteArray(4)
        val secondaryEntry = be32(0x00000000) + be32(secondarySize) + be32(secondaryOffsetFromMpf) + ByteArray(4)
        return segment(0xE2, ascii("MPF\u0000") + header + ifdBytes + primaryEntry + secondaryEntry)
    }

    /** Ein Motion-Photo-Anhang: ftyp, moov mit `©xyz`, mdat. */
    fun motionMp4(): ByteArray =
        box("ftyp", ascii("isom") + ByteArray(4)) +
            box("moov", box("udta", copyrightXyz(LOCATION_TEXT))) +
            box("mdat", ByteArray(32))

    fun jpeg(vararg segments: ByteArray): ByteArray =
        concat(byteArrayOf(0xFF.toByte(), 0xD8.toByte()), *segments, byteArrayOf(0xFF.toByte(), 0xD9.toByte()))

    // ---------- PNG ----------

    fun pngChunk(
        type: String,
        data: ByteArray,
    ): ByteArray {
        val typeBytes = ascii(type)
        val crc =
            CRC32()
                .apply {
                    update(typeBytes)
                    update(data)
                }.value
        return be32(data.size.toLong()) + typeBytes + data + be32(crc)
    }

    fun png(vararg chunks: ByteArray): ByteArray {
        val ihdr = be32(1) + be32(1) + byteArrayOf(8, 2, 0, 0, 0)
        return concat(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            pngChunk("IHDR", ihdr),
            *chunks,
            pngChunk("IEND", ByteArray(0)),
        )
    }

    /** tEXt mit „Raw profile type exif“ wie ImageMagick: Zeilenumbruch, Profil, Länge, Hex-Text. */
    fun rawProfileText(tiff: ByteArray): ByteArray {
        val hex = tiff.joinToString("") { "%02x".format(it.toInt() and 0xFF) }.chunked(72).joinToString("\n")
        val body = "\nexif\n" + "%8d".format(tiff.size) + "\n" + hex + "\n"
        return ascii("Raw profile type exif") + byteArrayOf(0) + ascii(body)
    }

    fun itxtXmp(xmp: String): ByteArray = ascii("XML:com.adobe.xmp") + byteArrayOf(0, 0, 0, 0, 0) + ascii(xmp)

    fun ztxtDeflated(
        keyword: String,
        text: String,
    ): ByteArray {
        val deflater = Deflater()
        deflater.setInput(ascii(text))
        deflater.finish()
        val buffer = ByteArray(4096)
        val size = deflater.deflate(buffer)
        deflater.end()
        return ascii(keyword) + byteArrayOf(0, 0) + buffer.copyOf(size)
    }

    // ---------- WebP ----------

    private fun riff(
        fourcc: String,
        data: ByteArray,
    ): ByteArray {
        val padding = if (data.size % 2 == 1) byteArrayOf(0) else ByteArray(0)
        return ascii(fourcc) + le32(data.size.toLong()) + data + padding
    }

    fun webp(vararg chunks: ByteArray): ByteArray {
        val body = ascii("WEBP") + concat(*chunks)
        return ascii("RIFF") + le32(body.size.toLong()) + body
    }

    fun webpChunk(
        fourcc: String,
        data: ByteArray,
    ): ByteArray = riff(fourcc, data)

    // ---------- GIF ----------

    /** GIF mit einer XMP-Application-Extension, Magic-Trailer und Trailer 0x3B. */
    fun gifWithXmp(xmp: String): ByteArray {
        val payload = ascii(xmp) + ByteArray(256) { (255 - it).toByte() }
        val blocks = ByteArrayOutputStream()
        var index = 0
        while (index < payload.size) {
            val length = minOf(255, payload.size - index)
            blocks.write(length)
            blocks.write(payload, index, length)
            index += length
        }
        blocks.write(0)
        val lsd = le16(1) + le16(1) + byteArrayOf(0, 0, 0)
        return ascii("GIF89a") + lsd +
            byteArrayOf(0x21, 0xFF.toByte(), 11) + ascii("XMP DataXMP") + blocks.toByteArray() +
            byteArrayOf(0x3B)
    }

    // ---------- ISO-BMFF ----------

    fun box(
        type: String,
        payload: ByteArray = ByteArray(0),
    ): ByteArray = be32(8L + payload.size) + ascii(type) + payload

    /** FullBox-Kopf: Version und Flags als vier Nullbytes. */
    private val FULL_HEADER = ByteArray(4)

    private fun copyrightXyz(text: String): ByteArray = box("©xyz", be16(ascii(text).size) + be16(0x15C7) + ascii(text))

    private fun hdlr(handler: String): ByteArray =
        box("hdlr", FULL_HEADER + ByteArray(4) + ascii(handler) + ByteArray(13))

    private fun keys(vararg names: String): ByteArray {
        val entries = names.map { name -> be32(8L + name.length) + ascii("mdta") + ascii(name) }
        return box("keys", FULL_HEADER + be32(names.size.toLong()) + concat(*entries.toTypedArray()))
    }

    /** Item mit dem 1-basierten Key-Index als Typ und einem `data`-Kind mit [value]. */
    private fun ilstItem(
        index: Long,
        value: String,
    ): ByteArray {
        val data = box("data", be32(1) + be32(0) + ascii(value))
        return box(String(be32(index), Charsets.ISO_8859_1), data)
    }

    /** `meta` im QuickTime-Stil (ohne FullBox-Kopf) mit Keys und Items für den Apple-Standort. */
    fun appleLocationMeta(): ByteArray =
        box(
            "meta",
            hdlr("mdta") +
                keys("com.apple.quicktime.location.ISO6709", "com.apple.quicktime.make") +
                box("ilst", ilstItem(1, LOCATION_TEXT) + ilstItem(2, "Apple")),
        )

    /** `meta` mit einem einzigen Key [keyName] und dem Standort-Wert [value] (andere Schreiber nutzen andere Namen). */
    fun metaWithLocationKey(
        keyName: String,
        value: String,
    ): ByteArray = box("meta", hdlr("mdta") + keys(keyName) + box("ilst", ilstItem(1, value)))

    /** `loci` (3GPP): Name, Koordinaten und Notiz, alles ungleich null. */
    fun loci(): ByteArray {
        val coordinates = be32(0x000C_8000) + be32(0x0010_0000) + be32(0x0000_0A00)
        val body =
            FULL_HEADER + ascii("de") + ascii("Berlin") + byteArrayOf(0) + byteArrayOf(1) + coordinates +
                ascii("Mond") + byteArrayOf(0) + ascii("Notiz") + byteArrayOf(0)
        return box("loci", body)
    }

    /** MP4 mit Standort an allen Stellen aus ADR 0015 außer Metadaten-Tracks und `free`. */
    fun mp4WithLocation(): ByteArray {
        val udta = box("udta", copyrightXyz(LOCATION_TEXT) + loci())
        val moov =
            box(
                "moov",
                udta + appleLocationMeta() + trakWithHandler("vide", ByteArray(0)) +
                    box("udta", copyrightXyz("Trak-Ort")),
            )
        val xmp = box("uuid", XMP_UUID + ascii(XMP_WITH_LOCATION))
        return box("ftyp", ascii("isom") + ByteArray(4)) + moov + xmp + box("mdat", ByteArray(64))
    }

    /** Track mit Handler und einem Sample-Eintrag. */
    fun trakWithHandler(
        handler: String,
        entry: ByteArray,
    ): ByteArray {
        val stsd = box("stsd", FULL_HEADER + be32(1) + entry)
        val stbl = box("stbl", stsd)
        val minf = box("minf", stbl)
        return box("trak", box("mdia", hdlr(handler) + minf))
    }

    /** Metadaten-Track mit `gpmd`-Eintrag. */
    fun gpmdTrack(): ByteArray = trakWithHandler("meta", box("gpmd", ByteArray(16)))

    /** `mebx` mit einem Key, der `location` enthält. */
    fun mebxTrack(keyName: String): ByteArray {
        val keyEntry = box("keys", FULL_HEADER + be32(1) + be32(8L + keyName.length) + ascii("mdta") + ascii(keyName))
        return trakWithHandler("meta", box("mebx", ByteArray(8) + keyEntry))
    }

    /** Top-Level `free` mit einer `gps `-Box in der Nutzlast (wie bei einer Pittasoft-Datei). */
    fun freeWithGps(): ByteArray = box("free", box("gps ", ascii(LOCATION_TEXT)))

    fun uuidXmp(xmp: String): ByteArray = box("uuid", XMP_UUID + ascii(xmp))

    /**
     * Ein JPEG mit allen Fundorten: EXIF mit GPS, MakerNote und Thumbnail (das selbst GPS trägt), XMP mit Element und
     * Attribut, IPTC-Ortsfelder und ein MPF-Zusatzbild mit GPS. Dahinter hängt ein Motion-Photo-MP4 mit Standort.
     * Der MPF-Offset wird in zwei Durchläufen berechnet, weil das Segment feste Größe hat.
     */
    fun jpegWithAllLocations(): ByteArray {
        val thumbnail = jpeg(app1Exif(exifTiff().bytes))
        val tiff = exifTiff(thumbnail).bytes
        val secondary = jpeg(app1Exif(exifTiff().bytes))

        fun primary(
            offset: Long,
            primarySize: Long,
        ): ByteArray =
            jpeg(
                app1Exif(tiff),
                app1Xmp(XMP_WITH_LOCATION),
                app13Iptc(iim(2, 90, "Berlinstadt"), iim(2, 101, "Deutschland"), iim(2, 25, "Schlagwort")),
                app2Mpf(primarySize, secondary.size.toLong(), offset),
            )
        val draft = primary(0, 0)
        val mpfStart = indexOf(draft, ascii("MPF\u0000")) + 4
        val secondaryStart = draft.size.toLong()
        val firstPass = primary(secondaryStart - mpfStart, draft.size.toLong())
        check(firstPass.size == draft.size) { "MPF-Segment hat feste Größe, die Länge darf nicht schwanken." }
        return firstPass + secondary + motionMp4()
    }

    private fun indexOf(
        haystack: ByteArray,
        needle: ByteArray,
    ): Int {
        for (start in 0..haystack.size - needle.size) {
            if (needle.indices.all { haystack[start + it] == needle[it] }) return start
        }
        error("Muster nicht gefunden.")
    }

    /** Hilfe: Die Bytes der Datei, die sich zwischen [before] und [after] unterscheiden, als Indexmenge. */
    fun changedIndices(
        before: ByteArray,
        after: ByteArray,
    ): List<Int> {
        check(before.size == after.size) { "Länge geändert: ${before.size} zu ${after.size}" }
        return before.indices.filter { before[it] != after[it] }
    }

    /** Prüft, dass [text] in den Bytes vorkommt (ISO-8859-1). */
    fun contains(
        bytes: ByteArray,
        text: String,
    ): Boolean = String(bytes, Charsets.ISO_8859_1).contains(text)
}
