package io.github.labushuya.emberlight.feature.gallery.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Schätzung des sichtbaren Bereichs vor dem Layout und das Merken der Rückkehr-Meldung (ADR 0010). */
class GridScrollWindowTest {
    @Test
    fun aTargetInsideTheFirstRowsIsInView() {
        // Zwei Spalten, fünf Zeilen ab Position 0: die Positionen 0 bis 9.
        assertTrue(inVisibleWindow(firstVisible = 0, target = 9, columns = 2, rows = 5))
    }

    @Test
    fun aTargetJustBeyondTheWindowIsNotInView() {
        assertFalse(inVisibleWindow(firstVisible = 0, target = 10, columns = 2, rows = 5))
    }

    @Test
    fun aTargetBeforeTheFirstVisibleItemIsNotInView() {
        assertFalse(inVisibleWindow(firstVisible = 40, target = 39, columns = 3, rows = 4))
    }

    @Test
    fun aWindowWithoutRowsOrColumnsNeverContainsAnything() {
        assertFalse(inVisibleWindow(firstVisible = 0, target = 0, columns = 0, rows = 5))
        assertFalse(inVisibleWindow(firstVisible = 0, target = 0, columns = 2, rows = 0))
    }

    @Test
    fun aClaimIsGrantedOnceForEachElement() {
        val scroll = LastViewedScroll()

        assertTrue(scroll.claim(7L))
        assertFalse(scroll.claim(7L))
        assertTrue(scroll.claim(8L))
    }

    @Test
    fun resetAllowsTheSameElementAgainAfterANewVisit() {
        val scroll = LastViewedScroll()
        assertTrue(scroll.claim(7L))

        scroll.reset()

        assertEquals(true, scroll.claim(7L))
    }
}
