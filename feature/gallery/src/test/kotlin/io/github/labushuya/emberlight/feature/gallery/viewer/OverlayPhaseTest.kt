package io.github.labushuya.emberlight.feature.gallery.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reine Zustandsmaschine des Overlays: jede erlaubte Übergangsfolge und die Ereignisse, die nichts ändern. */
class OverlayPhaseTest {
    @Test
    fun openingFinishesOpen() {
        assertEquals(OverlayPhase.OPENING, nextPhase(OverlayPhase.CLOSED, OverlayEvent.Open))
        assertEquals(OverlayPhase.OPEN, nextPhase(OverlayPhase.OPENING, OverlayEvent.OpenFinished))
    }

    @Test
    fun draggingSettlesBackOrCloses() {
        assertEquals(OverlayPhase.DRAGGING, nextPhase(OverlayPhase.OPEN, OverlayEvent.DragStarted))
        assertEquals(OverlayPhase.SETTLING, nextPhase(OverlayPhase.DRAGGING, OverlayEvent.DragEnded(dismiss = false)))
        assertEquals(OverlayPhase.CLOSING, nextPhase(OverlayPhase.DRAGGING, OverlayEvent.DragEnded(dismiss = true)))
        assertEquals(OverlayPhase.OPEN, nextPhase(OverlayPhase.SETTLING, OverlayEvent.SettleFinished))
    }

    @Test
    fun backProgressCancelsOrCommits() {
        assertEquals(OverlayPhase.BACK, nextPhase(OverlayPhase.OPEN, OverlayEvent.BackProgressed))
        assertEquals(OverlayPhase.BACK, nextPhase(OverlayPhase.BACK, OverlayEvent.BackProgressed))
        assertEquals(OverlayPhase.SETTLING, nextPhase(OverlayPhase.BACK, OverlayEvent.BackCancelled))
        assertEquals(OverlayPhase.CLOSING, nextPhase(OverlayPhase.BACK, OverlayEvent.BackCommitted))
    }

    @Test
    fun closingFinishesClosed() {
        assertEquals(OverlayPhase.CLOSING, nextPhase(OverlayPhase.OPEN, OverlayEvent.CloseRequested))
        assertEquals(OverlayPhase.CLOSING, nextPhase(OverlayPhase.OPENING, OverlayEvent.CloseRequested))
        assertEquals(OverlayPhase.CLOSED, nextPhase(OverlayPhase.CLOSING, OverlayEvent.CloseFinished))
    }

    @Test
    fun staleEventsLeaveThePhaseAlone() {
        assertEquals(OverlayPhase.OPEN, nextPhase(OverlayPhase.OPEN, OverlayEvent.OpenFinished))
        assertEquals(OverlayPhase.OPEN, nextPhase(OverlayPhase.OPEN, OverlayEvent.BackCancelled))
        assertEquals(OverlayPhase.OPEN, nextPhase(OverlayPhase.OPEN, OverlayEvent.CloseFinished))
        assertEquals(OverlayPhase.CLOSED, nextPhase(OverlayPhase.CLOSED, OverlayEvent.CloseRequested))
        assertEquals(OverlayPhase.CLOSING, nextPhase(OverlayPhase.CLOSING, OverlayEvent.CloseRequested))
        assertEquals(OverlayPhase.DRAGGING, nextPhase(OverlayPhase.DRAGGING, OverlayEvent.Open))
    }

    @Test
    fun visibilityOfTheLayers() {
        assertFalse(OverlayPhase.CLOSED.visible)
        assertTrue(OverlayPhase.OPENING.heroShown)
        assertFalse(OverlayPhase.OPENING.pagerShown)
        assertTrue(OverlayPhase.OPEN.pagerShown)
        assertTrue(OverlayPhase.BACK.pagerShown)
        assertTrue(OverlayPhase.CLOSING.heroShown)
    }
}
