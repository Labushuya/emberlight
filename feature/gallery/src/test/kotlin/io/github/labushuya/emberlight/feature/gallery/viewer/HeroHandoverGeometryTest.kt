package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Geometrie der Übergabe und des Rückwegs aus dem Zoom (ADR 0010, Feinschliff): gleiche Fit-Berechnung für Hero und Seite,
 * Pixel-Rundung, Startrechteck aus dem Zoom bei 1×, 2×, 4× und am Bildrand.
 */
class HeroHandoverGeometryTest {
    private val screen = Rect(0f, 0f, 400f, 800f)
    private val image = Rect(0f, 0f, 1000f, 1500f)

    /** Quadratische Kachel unten im Bild, ihr Zuschnitt ist die Mitte des Bildes. */
    private val tile = Rect(100f, 500f, 200f, 600f)
    private val crop = cropSourceRect(1000, 1500, targetAspect = 1f)

    @Test
    fun heroFrameEqualsTheReversedHandoverFrame() {
        val fit = fitRect(screen, aspect = 1f)
        for (t in listOf(0f, 0.25f, 0.5f, 1f)) {
            val expected = heroFrame(t, tile, fit, crop, image)
            val actual = lerpFrame(1f - t, HeroFrame(dst = fit, src = image), HeroFrame(dst = tile, src = crop))
            assertRect(expected.dst, actual.dst)
            assertRect(expected.src, actual.src)
        }
    }

    @Test
    fun snapRoundsEveryEdgeToTheNearestPixel() {
        assertRect(Rect(0f, 1f, 99f, 101f), snapRect(Rect(0.4f, 0.6f, 99.4f, 100.6f)))
    }

    @Test
    fun snappedFitStaysWithinHalfAPixelOfTheExactFit() {
        val exact = fitRect(Rect(0f, 0f, 1080f, 2400f), aspect = 0.75f)
        val snapped = snapRect(exact)

        assertTrue(abs(snapped.left - exact.left) <= 0.5f)
        assertTrue(abs(snapped.top - exact.top) <= 0.5f)
        assertTrue(abs(snapped.right - exact.right) <= 0.5f)
        assertTrue(abs(snapped.bottom - exact.bottom) <= 0.5f)
    }

    @Test
    fun aRectIsUsableOnlyWhenFiniteAndNotEmpty() {
        assertTrue(Rect(0f, 0f, 1f, 1f).isUsable())
        assertFalse(Rect.Zero.isUsable())
        assertFalse(Rect(0f, 0f, 0f, 5f).isUsable())
        assertFalse(Rect(0f, 0f, Float.NaN, 5f).isUsable())
        assertFalse(Rect(0f, 0f, Float.POSITIVE_INFINITY, 5f).isUsable())
    }

    @Test
    fun atZoomOneTheHandoverStartsAtTheFitRectangle() {
        val fit = fitRect(screen, aspect = 1f)
        val start = zoomStart(fit, zoom = 1f, pan = Offset.Zero)

        assertRect(fit, start.dst)
        assertRect(image, start.src)
    }

    @Test
    fun atZoomTwoTheStartIsLargerThanTheScreenAndNotClamped() {
        val fit = fitRect(screen, aspect = 1f)
        val start = zoomStart(fit, zoom = 2f, pan = Offset.Zero)

        // 2x um die Mitte: 800 Pixel breit, also über beide Bildschirmränder hinaus.
        assertRect(Rect(-200f, 0f, 600f, 800f), start.dst)
    }

    @Test
    fun atZoomFourPannedToTheEdgeTheStartKeepsTheEdge() {
        val fit = fitRect(screen, aspect = 1f)
        // Der linke Rand des Inhalts liegt genau am linken Bildschirmrand: die Verschiebung 600 gleicht 4x aus.
        val start = zoomStart(fit, zoom = 4f, pan = Offset(600f, 0f))

        assertEquals(0f, start.dst.left, TOLERANCE)
        assertEquals(1600f, start.dst.width, TOLERANCE)
    }

    @Test
    fun theHandoverShrinksMonotonicallyFromTheZoomedRectangleToTheTile() {
        val fit = fitRect(screen, aspect = 1f)
        val start = zoomStart(fit, zoom = 4f, pan = Offset(600f, 0f))
        val widths =
            listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map { u ->
                lerpFrame(u, start, HeroFrame(dst = tile, src = crop)).dst.width
            }

        assertEquals(1600f, widths.first(), TOLERANCE)
        assertEquals(100f, widths.last(), TOLERANCE)
        assertTrue(widths.zipWithNext().all { (a, b) -> b < a })
    }

    @Test
    fun theSourceMovesFromTheWholeImageToTheTileCrop() {
        val fit = fitRect(screen, aspect = 1f)
        val start = zoomStart(fit, zoom = 2f, pan = Offset.Zero)

        assertRect(image, lerpFrame(0f, start, HeroFrame(dst = tile, src = crop)).src)
        assertRect(crop, lerpFrame(1f, start, HeroFrame(dst = tile, src = crop)).src)
    }

    /** Startrahmen aus dem Zoom: das Inhalts-Rechteck (Fit, skaliert um die Mitte und verschoben) und das ganze Bild. */
    private fun zoomStart(
        fit: Rect,
        zoom: Float,
        pan: Offset,
    ): HeroFrame {
        val c = fit.center + pan
        val content =
            Rect(
                c.x - fit.width * zoom / 2f,
                c.y - fit.height * zoom / 2f,
                c.x + fit.width * zoom / 2f,
                c.y + fit.height * zoom / 2f,
            )
        return HeroFrame(dst = content, src = image)
    }

    private fun assertRect(
        expected: Rect,
        actual: Rect,
    ) {
        assertEquals("left", expected.left, actual.left, TOLERANCE)
        assertEquals("top", expected.top, actual.top, TOLERANCE)
        assertEquals("right", expected.right, actual.right, TOLERANCE)
        assertEquals("bottom", expected.bottom, actual.bottom, TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 0.01f
    }
}
