package io.github.labushuya.emberlight.feature.gallery.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regeln der vertikalen Wischgesten: Achse, Richtung und das Einrasten des Infobereichs (ADR 0010). */
class InfoSheetRulesTest {
    @Test
    fun aMovementUpwardsIsTheInfoSheetNotADismiss() {
        assertEquals(DragIntent.INFO, dragIntent(totalX = 0f, totalY = -60f, slopPx = 8f))
        assertEquals(DragIntent.INFO, dragIntent(totalX = 10f, totalY = -60f, slopPx = 8f))
    }

    @Test
    fun aMovementDownwardsStaysTheDismiss() {
        assertEquals(DragIntent.DISMISS, dragIntent(totalX = 0f, totalY = 60f, slopPx = 8f))
    }

    @Test
    fun aHorizontalOrDiagonalMovementGoesToThePager() {
        assertEquals(DragIntent.PASS, dragIntent(totalX = 80f, totalY = -20f, slopPx = 8f))
        assertEquals(DragIntent.PASS, dragIntent(totalX = 41f, totalY = -60f, slopPx = 8f))
    }

    @Test
    fun aSmallMovementIsStillPending() {
        assertEquals(DragIntent.PENDING, dragIntent(totalX = 1f, totalY = -4f, slopPx = 8f))
    }

    @Test
    fun aSlowRestsOpenFromAboveHalfAndClosedBelow() {
        assertTrue(infoSheetOpens(fraction = 0.5f, velocityYPerSecond = 0f, flingPerSecond = FLING))
        assertFalse(infoSheetOpens(fraction = 0.49f, velocityYPerSecond = 0f, flingPerSecond = FLING))
    }

    @Test
    fun aFastUpwardFlingOpensEvenWhenBarelyMoved() {
        assertTrue(infoSheetOpens(fraction = 0.05f, velocityYPerSecond = -FLING, flingPerSecond = FLING))
    }

    @Test
    fun aFastDownwardFlingClosesEvenWhenOpenedFar() {
        assertFalse(infoSheetOpens(fraction = 0.9f, velocityYPerSecond = FLING, flingPerSecond = FLING))
    }

    private companion object {
        const val FLING = 600f
    }
}
