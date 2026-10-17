package io.github.labushuya.emberlight.feature.gallery.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** Formatierung der Werte im Infobereich, deutsch mit Dezimalkomma und englisch mit Punkt (ADR 0010). */
class InfoFormatsTest {
    @Test
    fun apertureUsesTheLocaleDecimalMark() {
        assertEquals("f/1,8", formatAperture(1.8, Locale.GERMANY))
        assertEquals("f/1.8", formatAperture(1.8, Locale.US))
        assertEquals("f/4.0", formatAperture(4.0, Locale.US))
    }

    @Test
    fun shortExposuresAreFractionsOfASecond() {
        assertEquals("1/250 s", formatExposure(1.0 / 250.0, Locale.GERMANY))
        assertEquals("1/250 s", formatExposure(1.0 / 250.0, Locale.US))
        assertEquals("1/2 s", formatExposure(0.5, Locale.US))
    }

    @Test
    fun longExposuresAreSecondsWithTheLocaleDecimalMark() {
        assertEquals("2,5 s", formatExposure(2.5, Locale.GERMANY))
        assertEquals("2.5 s", formatExposure(2.5, Locale.US))
        assertEquals("2 s", formatExposure(2.0, Locale.US))
        assertEquals("1 s", formatExposure(1.0, Locale.GERMANY))
    }

    @Test
    fun isoIsWrittenWithItsUnit() {
        assertEquals("ISO 200", formatIso(200))
    }

    @Test
    fun focalLengthIsWholeOrOneDecimal() {
        assertEquals("50 mm", formatFocalLength(50.0, Locale.US))
        assertEquals("4,5 mm", formatFocalLength(4.5, Locale.GERMANY))
        assertEquals("4.5 mm", formatFocalLength(4.5, Locale.US))
    }

    @Test
    fun megapixelsRoundToOneDecimal() {
        assertEquals("12,2 MP", formatMegapixels(4032, 3024, Locale.GERMANY))
        assertEquals("12.2 MP", formatMegapixels(4032, 3024, Locale.US))
    }

    @Test
    fun megapixelsAreMissingWithoutSize() {
        assertNull(formatMegapixels(null, 3024, Locale.US))
        assertNull(formatMegapixels(0, 3024, Locale.US))
    }

    @Test
    fun resolutionIsWidthTimesHeight() {
        assertEquals("4032 × 3024", formatResolution(4032, 3024))
        assertNull(formatResolution(4032, null))
        assertNull(formatResolution(-1, 3024))
    }

    @Test
    fun mimeTypesAreReadable() {
        assertEquals("JPEG", formatMimeType("image/jpeg"))
        assertEquals("JPEG", formatMimeType("image/jpg"))
        assertEquals("HEIC", formatMimeType("image/heic"))
        assertEquals("MP4", formatMimeType("video/mp4"))
        assertEquals("3GP", formatMimeType("video/3gpp"))
    }

    @Test
    fun aMissingMimeTypeIsNotShownAsText() {
        assertNull(formatMimeType(null))
        assertNull(formatMimeType(""))
        assertNull(formatMimeType("image/"))
    }
}
