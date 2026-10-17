package io.github.labushuya.emberlight.feature.gallery.timeline

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kachel-Grenzen für die Hero-Animation: keine veralteten Einträge, Zählerstand je Schreiben, Sichtbarkeit unter Kopfzeile und
 * über der Leiste (ADR 0010, Feinschliff).
 */
class CellBoundsTest {
    private val viewport = Rect(0f, 56f, 400f, 900f)

    @Test
    fun aRemovedCellHasNoBoundsAnymore() {
        val cells = CellBounds()
        cells.put(1L, Rect(0f, 100f, 100f, 200f))

        cells.remove(1L)

        assertNull(cells.rectOf(1L))
        assertEquals(0L, cells.stampOf(1L))
    }

    @Test
    fun everyWriteRaisesTheStampOfThatCell() {
        val cells = CellBounds()
        cells.put(1L, Rect(0f, 100f, 100f, 200f))
        val first = cells.stampOf(1L)

        cells.put(2L, Rect(100f, 100f, 200f, 200f))
        cells.put(1L, Rect(0f, 300f, 100f, 400f))

        assertTrue(cells.stampOf(1L) > first)
        assertEquals(Rect(0f, 300f, 100f, 400f), cells.rectOf(1L))
        assertEquals(cells.stamp, cells.stampOf(1L))
    }

    @Test
    fun aWriteToAnotherCellDoesNotLookLikeAFreshWriteToThisOne() {
        val cells = CellBounds()
        cells.put(1L, Rect(0f, 100f, 100f, 200f))
        val before = cells.stamp

        cells.put(2L, Rect(100f, 100f, 200f, 200f))

        assertTrue(cells.stampOf(1L) <= before)
        assertTrue(cells.stampOf(2L) > before)
    }

    @Test
    fun theViewportIsForgottenWhenTheGridLeavesComposition() {
        val cells = CellBounds()
        cells.setViewport(viewport)
        assertEquals(viewport, cells.viewport)

        cells.setViewport(null)

        assertNull(cells.viewport)
    }

    @Test
    fun aTileBelowTheHeaderAndAboveTheBarIsFullyVisible() {
        assertTrue(isTileFullyVisible(Rect(0f, 100f, 100f, 200f), viewport, bottomLimit = 800f))
    }

    @Test
    fun aTileExactlyOnTheEdgesIsStillVisible() {
        assertTrue(isTileFullyVisible(Rect(0f, 56f, 400f, 900f), viewport, bottomLimit = null))
    }

    @Test
    fun aTileUnderTheHeaderIsNotVisible() {
        assertFalse(isTileFullyVisible(Rect(0f, 20f, 100f, 120f), viewport, bottomLimit = null))
    }

    @Test
    fun aTileAboveTheBarIsNotVisibleEvenInsideTheGrid() {
        // Die Leiste beginnt bei 800, die Kachel reicht bis 850: sie liegt halb unter der Leiste.
        assertFalse(isTileFullyVisible(Rect(0f, 750f, 100f, 850f), viewport, bottomLimit = 800f))
    }

    @Test
    fun withoutABarTheGridBottomIsTheLimit() {
        assertTrue(isTileFullyVisible(Rect(0f, 800f, 100f, 900f), viewport, bottomLimit = null))
        assertFalse(isTileFullyVisible(Rect(0f, 800f, 100f, 950f), viewport, bottomLimit = null))
    }

    @Test
    fun aBarBelowTheGridDoesNotExtendTheGrid() {
        // Eine Leiste unterhalb des Rasters darf das Sichtfenster nicht vergrößern.
        assertFalse(isTileFullyVisible(Rect(0f, 800f, 100f, 950f), viewport, bottomLimit = 1000f))
    }
}
