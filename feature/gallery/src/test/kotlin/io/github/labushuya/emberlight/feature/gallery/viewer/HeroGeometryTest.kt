package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

/** Geometrie der Hero-Animation: Fit, Zuschnitt, Interpolation, Zieh-Transformation und Ersatz ohne Kachel. */
class HeroGeometryTest {
    private val screen = Rect(0f, 0f, 400f, 800f)

    @Test
    fun fitIsCenteredAndKeepsTheAspectWhenWidthLimits() {
        // Querformat-Bild (2:1) in einem Hochformat-Bereich: die Breite begrenzt, Höhe 200.
        val fit = fitRect(screen, aspect = 2f)

        assertRect(Rect(0f, 300f, 400f, 500f), fit)
    }

    @Test
    fun fitIsCenteredAndKeepsTheAspectWhenHeightLimits() {
        // Schmales Hochformat (1:4) im Bereich 400 x 800: die Höhe begrenzt, also 200 x 800.
        val fit = fitRect(screen, aspect = 0.25f)

        assertRect(Rect(100f, 0f, 300f, 800f), fit)
    }

    @Test
    fun fitWithoutAspectFillsTheBounds() {
        assertEquals(screen, fitRect(screen, aspect = 0f))
        assertEquals(screen, fitRect(screen, aspect = Float.NaN))
    }

    @Test
    fun cropOfAWideImageIsCenteredHorizontally() {
        // 400 x 200 auf Quadrat: die Mitte 200 x 200 bleibt.
        assertRect(Rect(100f, 0f, 300f, 200f), cropSourceRect(400, 200, targetAspect = 1f))
    }

    @Test
    fun cropOfATallImageIsCenteredVertically() {
        assertRect(Rect(0f, 100f, 200f, 300f), cropSourceRect(200, 400, targetAspect = 1f))
    }

    @Test
    fun cropOfAnImageWithTheSameAspectIsTheWholeImage() {
        assertRect(Rect(0f, 0f, 300f, 300f), cropSourceRect(300, 300, targetAspect = 1f))
    }

    @Test
    fun heroStartsAtTheTileWithTheCropAndEndsAtTheFitWithTheWholeImage() {
        val tile = Rect(0f, 100f, 100f, 200f)
        val fit = Rect(0f, 300f, 400f, 500f)
        val crop = Rect(100f, 0f, 300f, 200f)
        val full = Rect(0f, 0f, 400f, 200f)

        val start = heroFrame(0f, tile, fit, crop, full)
        val end = heroFrame(1f, tile, fit, crop, full)

        assertEquals(tile, start.dst)
        assertEquals(crop, start.src)
        assertEquals(fit, end.dst)
        assertEquals(full, end.src)
    }

    @Test
    fun heroInterpolatesLinearlyInTheMiddle() {
        val tile = Rect(0f, 0f, 100f, 100f)
        val fit = Rect(0f, 0f, 200f, 200f)
        val crop = Rect(0f, 0f, 100f, 100f)
        val full = Rect(0f, 0f, 200f, 200f)

        val middle = heroFrame(0.5f, tile, fit, crop, full)

        assertRect(Rect(0f, 0f, 150f, 150f), middle.dst)
        assertRect(Rect(0f, 0f, 150f, 150f), middle.src)
    }

    @Test
    fun heroProgressIsClampedToTheRange() {
        val tile = Rect(0f, 0f, 10f, 10f)
        val fit = Rect(0f, 0f, 20f, 20f)
        val crop = Rect(0f, 0f, 10f, 10f)
        val full = Rect(0f, 0f, 20f, 20f)

        assertEquals(tile, heroFrame(-1f, tile, fit, crop, full).dst)
        assertEquals(fit, heroFrame(2f, tile, fit, crop, full).dst)
    }

    @Test
    fun transformWithoutGestureChangesNothing() {
        val rect = Rect(10f, 20f, 110f, 120f)

        assertEquals(rect, transformRect(rect, pivot = Offset(200f, 400f), scale = 1f, dy = 0f))
    }

    @Test
    fun transformShrinksAroundThePivotAndMovesDown() {
        val rect = Rect(0f, 0f, 100f, 100f)

        // Mittelpunkt (50, 50) um (50, 50) skaliert bleibt; Verschiebung um 30 nach unten.
        val moved = transformRect(rect, pivot = Offset(50f, 50f), scale = 0.5f, dy = 30f)

        assertRect(Rect(25f, 55f, 75f, 105f), moved)
    }

    @Test
    fun fallbackTileIsCenteredAndHalfTheFit() {
        val fit = Rect(0f, 300f, 400f, 500f)

        assertRect(Rect(100f, 350f, 300f, 450f), fallbackTile(fit))
    }

    @Test
    fun lerpRectMovesEveryEdge() {
        val mid = lerpRect(Rect(0f, 0f, 10f, 10f), Rect(10f, 10f, 30f, 30f), 0.5f)

        assertRect(Rect(5f, 5f, 20f, 20f), mid)
    }

    private fun assertRect(
        expected: Rect,
        actual: Rect,
    ) {
        assertEquals(expected.left, actual.left, TOLERANCE)
        assertEquals(expected.top, actual.top, TOLERANCE)
        assertEquals(expected.right, actual.right, TOLERANCE)
        assertEquals(expected.bottom, actual.bottom, TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 0.01f
    }
}
