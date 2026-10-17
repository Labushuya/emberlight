package io.github.labushuya.emberlight.feature.gallery.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Übergänge des Kurzen Blicks (Phase PEEKING) in der reinen Zustandsmaschine (ADR 0010, Kurzer Blick). */
class OverlayPeekPhaseTest {
    @Test
    fun aBackStartFromOpenEntersThePeek() {
        assertEquals(OverlayPhase.PEEKING, nextPhase(OverlayPhase.OPEN, OverlayEvent.BackStarted))
    }

    @Test
    fun aBackStartElsewhereChangesNothing() {
        assertEquals(OverlayPhase.CLOSED, nextPhase(OverlayPhase.CLOSED, OverlayEvent.BackStarted))
        assertEquals(OverlayPhase.BACK, nextPhase(OverlayPhase.BACK, OverlayEvent.BackStarted))
        assertEquals(OverlayPhase.OPENING, nextPhase(OverlayPhase.OPENING, OverlayEvent.BackStarted))
    }

    @Test
    fun progressTakesThePeekOverToTheFingerAndCancelSettles() {
        assertEquals(OverlayPhase.BACK, nextPhase(OverlayPhase.PEEKING, OverlayEvent.BackProgressed))
        assertEquals(OverlayPhase.SETTLING, nextPhase(OverlayPhase.PEEKING, OverlayEvent.BackCancelled))
    }

    @Test
    fun committingThePeekClosesTheViewer() {
        assertEquals(OverlayPhase.CLOSING, nextPhase(OverlayPhase.PEEKING, OverlayEvent.BackCommitted))
    }

    @Test
    fun thePeekShowsThePagerAndIsVisible() {
        assertTrue(OverlayPhase.PEEKING.pagerShown)
        assertTrue(OverlayPhase.PEEKING.visible)
        assertFalse(OverlayPhase.PEEKING.heroShown)
    }

    @Test
    fun aDragCannotStartOutOfThePeek() {
        assertEquals(OverlayPhase.PEEKING, nextPhase(OverlayPhase.PEEKING, OverlayEvent.DragStarted))
    }
}
