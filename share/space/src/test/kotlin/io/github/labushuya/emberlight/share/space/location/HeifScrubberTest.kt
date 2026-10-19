package io.github.labushuya.emberlight.share.space.location

import com.drew.imaging.ImageMetadataReader
import com.drew.lang.GeoLocation
import com.drew.metadata.exif.GpsDirectory
import io.github.labushuya.emberlight.share.space.DetectedFormat
import io.github.labushuya.emberlight.share.space.MediaKind
import io.github.labushuya.emberlight.share.space.location.HeifFixtures.IlocEntry
import io.github.labushuya.emberlight.share.space.location.HeifFixtures.Item
import io.github.labushuya.emberlight.share.space.location.HeifFixtures.METHOD_IDAT
import io.github.labushuya.emberlight.share.space.location.HeifFixtures.METHOD_ITEM
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.XMP_WITH_LOCATION
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.ascii
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.be16
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.box
import io.github.labushuya.emberlight.share.space.location.LocationFixtures.exifTiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.security.MessageDigest

/**
 * HEIC, HEIF und AVIF (ADR 0015, Item-Walker). Synthetische Dateien mit Exif- und XMP-Items, Konstruktionsmethoden 0
 * und 1, mehreren Extents, mehreren Exif-Items und gezielt kaputten `iloc`-Werten (fail closed).
 */
@RunWith(RobolectricTestRunner::class)
class HeifScrubberTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun scrub(
        bytes: ByteArray,
        mime: String,
    ): Pair<LocationScrubResult, ByteArray> {
        val file = folder.newFile()
        file.writeBytes(bytes)
        val format = DetectedFormat(mime, MediaKind.IMAGE, boxed = true)
        val result = LocationScrubber.scrub(file, format, sha256Hex(bytes))
        return result to file.readBytes()
    }

    /** Erwartet eine geänderte, bereinigte Datei gleicher Länge mit passendem Hash. Liefert die Bytes danach. */
    private fun assertCleanAndChanged(
        original: ByteArray,
        mime: String,
    ): ByteArray {
        val (result, out) = scrub(original, mime)
        assertTrue("Erwartet Clean mit Änderung, war $result", result is LocationScrubResult.Clean && result.changed)
        assertEquals("Länge darf sich nicht ändern", original.size, out.size)
        assertEquals("Hash über die verarbeiteten Bytes", sha256Hex(out), (result as LocationScrubResult.Clean).sha256)
        assertNoLocationText(out)
        return out
    }

    private fun assertNotRemovable(
        original: ByteArray,
        reason: NotRemovableReason,
    ) {
        val (result, out) = scrub(original, "image/heic")
        assertEquals(LocationScrubResult.NotRemovable(reason), result)
        assertTrue("Nicht bereinigbar: Datei bleibt bit-identisch", original.contentEquals(out))
    }

    private fun assertNoLocationText(bytes: ByteArray) {
        val text = String(bytes, Charsets.ISO_8859_1)
        assertFalse("Standort-Text im Ergebnis", LocationText.containsIn(text))
        assertFalse("Ortsname im Ergebnis", text.contains("Berlinstadt"))
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Exif-Item mit GPS, MakerNote und optional einem Thumbnail-JPEG, das selbst Standort trägt. */
    private fun exif(
        thumbnail: ByteArray? = null,
        offset: Long = 6,
        prefix: Boolean = true,
    ): ByteArray = HeifFixtures.exifItem(exifTiff(thumbnail = thumbnail).bytes, offset, prefix)

    private val locatedThumbnail: ByteArray
        get() = LocationFixtures.jpeg(LocationFixtures.app1Exif(exifTiff().bytes))

    /** Geolokalisierungen aller GPS-Verzeichnisse, unabhängig vom eigenen Code. ExifInterface liest diesen HEIF-Aufbau nicht. */
    private fun geoLocations(bytes: ByteArray): List<GeoLocation?> =
        ImageMetadataReader
            .readMetadata(ByteArrayInputStream(bytes))
            .getDirectoriesOfType(GpsDirectory::class.java)
            .map { it.geoLocation }

    @Test
    fun exifItemWithGpsAndThumbnailIsCleanedAndMetadataExtractorSeesNoLocation() {
        val original = HeifFixtures.standard("heic", listOf(Item(1, "Exif", exif(thumbnail = locatedThumbnail))))
        assertTrue(
            "Fixture: metadata-extractor liest den Standort vor der Bereinigung",
            geoLocations(original).any {
                it !=
                    null
            },
        )

        val out = assertCleanAndChanged(original, "image/heic")

        assertTrue(
            "metadata-extractor meldet nach der Bereinigung keinen Standort",
            geoLocations(out).all { it == null },
        )
        assertFalse("MakerNote ist genullt", LocationFixtures.contains(out, "HERSTELLER-DATEN1"))
    }

    @Test
    fun exifItemInIdatConstructionIsCleaned() {
        val original =
            HeifFixtures.standard(
                "heic",
                listOf(Item(1, "Exif", exif(), method = METHOD_IDAT)),
                ilocVersion = 1,
            )

        val out = assertCleanAndChanged(original, "image/heic")

        assertFalse("MakerNote ist genullt", LocationFixtures.contains(out, "HERSTELLER-DATEN1"))
    }

    @Test
    fun multipleExtentsInReverseOrderAreMappedAndGapsStayUntouched() {
        val original =
            HeifFixtures.standard(
                "heic",
                listOf(Item(1, "Exif", exif(), extents = 3, reversed = true)),
                ilocVersion = 0,
            )

        val out = assertCleanAndChanged(original, "image/heic")

        assertFalse("MakerNote über mehrere Extents ist genullt", LocationFixtures.contains(out, "HERSTELLER-DATEN1"))
        assertTrue("Lücken zwischen den Extents bleiben unverändert", LocationFixtures.contains(out, "~~GAP~~"))
    }

    @Test
    fun everyExifItemIsCleanedIncludingAuxiliaryOnes() {
        val original =
            HeifFixtures.standard(
                "heic",
                listOf(
                    Item(1, "Exif", exif(thumbnail = locatedThumbnail)),
                    Item(2, "Exif", exif()),
                ),
            )

        val out = assertCleanAndChanged(original, "image/heic")

        assertFalse("beide Exif-Items sind genullt", LocationFixtures.contains(out, "HERSTELLER-DATEN1"))
    }

    /** Anhängsel nach dem EOI eines Thumbnails ist nicht bereinigbar, sondern `trailing_data` (nicht ungeprüft). */
    @Test
    fun thumbnailWithTrailingDataIsNotRemovable() {
        val original =
            HeifFixtures.standard(
                "heic",
                listOf(Item(1, "Exif", exif(thumbnail = locatedThumbnail + ascii("anhang")))),
            )

        assertNotRemovable(original, NotRemovableReason.TRAILING_DATA)
    }

    /** Offset 0 mit `Exif\0\0`-Präfix dahinter: Der Walker springt über das Präfix (robuster Fall). */
    @Test
    fun exifItemWithZeroOffsetAndPrefixBehindItIsCleaned() {
        val original =
            HeifFixtures.standard(
                "heic",
                listOf(Item(1, "Exif", exif(offset = 0, prefix = true))),
                ilocVersion = 0,
            )

        val out = assertCleanAndChanged(original, "image/heic")

        assertFalse(LocationFixtures.contains(out, "HERSTELLER-DATEN1"))
    }

    @Test
    fun exifItemWithPrefixAfterOffsetAndIdWidthVersionTwoIsCleaned() {
        val original =
            HeifFixtures.standard(
                "heic",
                listOf(Item(1, "Exif", HeifFixtures.exifItem(exifTiff().bytes, prefix = true))),
                ilocVersion = 2,
            )

        val out = assertCleanAndChanged(original, "image/heic")

        assertFalse(LocationFixtures.contains(out, "HERSTELLER-DATEN1"))
    }

    @Test
    fun xmpItemIsBlankedWithSpaces() {
        val original =
            HeifFixtures.standard(
                "heic",
                listOf(Item(2, "mime", ascii(XMP_WITH_LOCATION), contentType = "application/rdf+xml")),
            )

        val out = assertCleanAndChanged(original, "image/heic")

        assertFalse(LocationFixtures.contains(out, "48,51.3N"))
    }

    @Test
    fun avifWithExifAndXmpItemsIsCleaned() {
        val original =
            HeifFixtures.standard(
                "avif",
                listOf(
                    Item(1, "Exif", exif()),
                    Item(2, "mime", ascii(XMP_WITH_LOCATION), contentType = "application/rdf+xml"),
                ),
                ilocVersion = 1,
            )

        val (result, out) = scrub(original, "image/avif")

        assertTrue("AVIF: $result", result is LocationScrubResult.Clean && result.changed)
        assertEquals(original.size, out.size)
        assertNoLocationText(out)
        assertFalse(LocationFixtures.contains(out, "HERSTELLER-DATEN1"))
    }

    @Test
    fun sequenceMoovWithLocationIsCleanedLikeMp4() {
        val location = be16(ascii("+48.8566+002.3522/").size) + be16(0x15C7) + ascii("+48.8566+002.3522/")
        val moov = box("moov", box("udta", box("©xyz", location)))
        val original =
            HeifFixtures.standard("heic", listOf(Item(1, "Exif", exif()))) + moov

        val out = assertCleanAndChanged(original, "image/heic")

        assertFalse(LocationFixtures.contains(out, "©xyz"))
        assertTrue(LocationFixtures.contains(out, "free"))
    }

    @Test
    fun constructionMethodTwoForExifIsUnsupported() {
        val original = HeifFixtures.standard("heic", listOf(Item(1, "Exif", exif(), method = METHOD_ITEM)))

        assertNotRemovable(original, NotRemovableReason.UNSUPPORTED)
    }

    @Test
    fun itemWithoutInfeEntryIsStructuralRefusal() {
        val original = HeifFixtures.standard("heic", listOf(Item(1, "Exif", exif(), listed = false)))

        assertNotRemovable(original, NotRemovableReason.STRUCTURE)
    }

    @Test
    fun secondIlocBoxIsRefusedAsAmbiguous() {
        val original =
            HeifFixtures.standard(
                "heic",
                listOf(Item(1, "Exif", exif())),
                extraMetaChildren = listOf(HeifFixtures.iloc(0, emptyList())),
            )

        assertNotRemovable(original, NotRemovableReason.STRUCTURE)
    }

    @Test
    fun ilocOffsetSizeOtherThanZeroFourEightIsStructure() {
        val iloc = HeifFixtures.fullBox("iloc", 0, byteArrayOf(0x34, 0x00) + be16(0))
        val original = rawWithIloc(iloc)

        assertNotRemovable(original, NotRemovableReason.STRUCTURE)
    }

    @Test
    fun extentBeyondTheFileIsStructure() {
        val iloc = HeifFixtures.iloc(0, listOf(IlocEntry(1, 0, listOf(1_000_000L to 10L))))
        val original = rawWithIloc(iloc)

        assertNotRemovable(original, NotRemovableReason.STRUCTURE)
    }

    @Test
    fun idatConstructionWithoutIdatBoxIsStructure() {
        val iloc = HeifFixtures.iloc(1, listOf(IlocEntry(1, METHOD_IDAT, listOf(0L to 8L))))
        val original = rawWithIloc(iloc)

        assertNotRemovable(original, NotRemovableReason.STRUCTURE)
    }

    @Test
    fun exifOffsetBeyondItemIsStructure() {
        val original = HeifFixtures.standard("heic", listOf(Item(1, "Exif", be32(1000) + exifTiff().bytes)))

        assertNotRemovable(original, NotRemovableReason.STRUCTURE)
    }

    @Test
    fun externalDataReferenceIsUnsupported() {
        val iloc = HeifFixtures.iloc(0, listOf(IlocEntry(1, 0, listOf(0L to 4L), dataReference = 1)))
        val original = rawWithIloc(iloc)

        assertNotRemovable(original, NotRemovableReason.UNSUPPORTED)
    }

    @Test
    fun metaWithoutPictureHandlerIsUnsupported() {
        val original =
            HeifFixtures.raw(
                "heic",
                listOf(HeifFixtures.fullBox("hdlr", 0, be32(0) + ascii("vide") + ByteArray(13))),
                ByteArray(16),
            )

        assertNotRemovable(original, NotRemovableReason.UNSUPPORTED)
    }

    @Test
    fun infeVersionOneWithoutItemTypeIsUnsupported() {
        val infe = HeifFixtures.fullBox("infe", 1, be16(1) + be16(0) + byteArrayOf(0))
        val original =
            HeifFixtures.raw(
                "heic",
                listOf(HeifFixtures.hdlr(), HeifFixtures.iinf(infe)),
                ByteArray(16),
            )

        assertNotRemovable(original, NotRemovableReason.UNSUPPORTED)
    }

    @Test
    fun unknownIlocVersionIsUnsupported() {
        val iloc = HeifFixtures.fullBox("iloc", 3, byteArrayOf(0x44, 0x00) + be16(0))
        val original = rawWithIloc(iloc)

        assertNotRemovable(original, NotRemovableReason.UNSUPPORTED)
    }

    /** Datei mit Exif-Item in Konstruktionsmethode 0 und einem selbst gebauten `iloc`. Die Länge des `iloc` ist fest. */
    private fun rawWithIloc(iloc: ByteArray): ByteArray =
        HeifFixtures.raw(
            "heic",
            listOf(
                HeifFixtures.hdlr(),
                HeifFixtures.iinf(HeifFixtures.infe(1, "Exif")),
                iloc,
            ),
            ByteArray(16),
        )

    private fun be32(value: Long): ByteArray = LocationFixtures.be32(value)
}
