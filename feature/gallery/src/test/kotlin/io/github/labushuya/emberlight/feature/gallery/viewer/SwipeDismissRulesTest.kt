package io.github.labushuya.emberlight.feature.gallery.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regeln des Wegwischens nach unten als reine Funktionen (ADR 0010): Achse, Schwelle, Geschwindigkeit, Zoom. */
class SwipeDismissRulesTest {
    private val height = 1_000f
    private val fling = 600f

    @Test
    fun aSmallMovementDoesNotDecideYet() {
        assertEquals(DragIntent.PENDING, dragIntent(totalX = 1f, totalY = 4f, slopPx = 8f))
    }

    @Test
    fun aPredominantlyDownwardMovementStartsTheDismiss() {
        assertEquals(DragIntent.DISMISS, dragIntent(totalX = 5f, totalY = 60f, slopPx = 8f))
    }

    @Test
    fun aHorizontalMovementGoesToThePager() {
        assertEquals(DragIntent.PASS, dragIntent(totalX = 60f, totalY = 20f, slopPx = 8f))
    }

    @Test
    fun anUpwardMovementIsNotADismiss() {
        assertEquals(DragIntent.PASS, dragIntent(totalX = 0f, totalY = -60f, slopPx = 8f))
    }

    @Test
    fun theAxisRatioDecidesADiagonalMovement() {
        // Knapp über dem Verhältnis von 1,5: vertikal. Knapp darunter: diagonal, also beim Pager.
        assertEquals(DragIntent.DISMISS, dragIntent(totalX = 39f, totalY = 60f, slopPx = 8f))
        assertEquals(DragIntent.PASS, dragIntent(totalX = 41f, totalY = 60f, slopPx = 8f))
    }

    @Test
    fun releasingBelowTheThresholdSpringsBack() {
        assertFalse(shouldDismiss(offsetPx = 149f, velocityYPerSecond = 0f, heightPx = height, flingPerSecond = fling))
    }

    @Test
    fun releasingAtTheThresholdCloses() {
        assertTrue(shouldDismiss(offsetPx = 150f, velocityYPerSecond = 0f, heightPx = height, flingPerSecond = fling))
    }

    @Test
    fun aFastFlickCloseEvenBelowTheThreshold() {
        // Mindestens 5 % der Höhe und Geschwindigkeit nach unten über der Grenze.
        assertTrue(shouldDismiss(offsetPx = 60f, velocityYPerSecond = 900f, heightPx = height, flingPerSecond = fling))
    }

    @Test
    fun aFastButTinyMovementDoesNotClose() {
        // Ein Zucken von wenigen Pixeln schließt den Viewer nicht, auch bei hoher Geschwindigkeit.
        assertFalse(
            shouldDismiss(offsetPx = 10f, velocityYPerSecond = 2_000f, heightPx = height, flingPerSecond = fling),
        )
    }

    @Test
    fun aSlowMovementBelowTheThresholdDoesNotClose() {
        assertFalse(shouldDismiss(offsetPx = 60f, velocityYPerSecond = 100f, heightPx = height, flingPerSecond = fling))
    }

    @Test
    fun noOffsetOrNoHeightNeverCloses() {
        assertFalse(
            shouldDismiss(offsetPx = 0f, velocityYPerSecond = 9_000f, heightPx = height, flingPerSecond = fling),
        )
        assertFalse(shouldDismiss(offsetPx = 500f, velocityYPerSecond = 0f, heightPx = 0f, flingPerSecond = fling))
    }

    @Test
    fun progressIsTheShareOfTheHeightClampedToZeroAndOne() {
        assertEquals(0f, dismissProgress(offsetPx = -20f, heightPx = height), 0f)
        assertEquals(0.25f, dismissProgress(offsetPx = 250f, heightPx = height), 1e-6f)
        assertEquals(1f, dismissProgress(offsetPx = 5_000f, heightPx = height), 0f)
        assertEquals(0f, dismissProgress(offsetPx = 5f, heightPx = 0f), 0f)
    }

    @Test
    fun dismissOnlyStartsWhenTheImageIsNotZoomed() {
        assertTrue(canStartDismiss(zoomFraction = null))
        assertTrue(canStartDismiss(zoomFraction = 0f))
        assertFalse(canStartDismiss(zoomFraction = 0.4f))
        assertFalse(canStartDismiss(zoomFraction = 1f))
    }

    @Test
    fun theThresholdsAreTheDocumentedValues() {
        assertEquals(0.15f, DISMISS_FRACTION, 0f)
        assertEquals(0.1f, SHRINK_MAX, 0f)
    }
}
