package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Zustandsmaschine des Overlays mit Animationen: Öffnen, Schließen, Wegwischen über und unter der Schwelle, Rückgeste mit
 * Fortschritt, Abbruch und Vollenden. Die Animationen laufen im Testscheduler, `advanceUntilIdle` lässt sie enden.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewerOverlayStateTest {
    private val tiles = mapOf(1L to Rect(0f, 100f, 100f, 200f), 2L to Rect(100f, 100f, 200f, 200f))

    @Test
    fun openAnimatesFromTheTileAndEndsOpen() =
        runTest(FakeFrameClock) {
            val state = overlay(this)

            state.open(1L)
            assertEquals(OverlayPhase.OPENING, state.phase)
            assertEquals(1L, state.shownId)
            assertEquals(tiles[1L], state.heroTile)

            advanceUntilIdle()
            assertEquals(OverlayPhase.OPEN, state.phase)
            assertEquals(1f, state.progress, EPS)
        }

    @Test
    fun openWhileOpenChangesNothing() =
        runTest(FakeFrameClock) {
            val state = overlay(this, shown = 1L)

            state.open(2L)
            advanceUntilIdle()

            assertEquals(OverlayPhase.OPEN, state.phase)
            assertEquals(1L, state.shownId)
        }

    @Test
    fun aRestoredOverlayIsOpenWithoutAnimation() =
        runTest(FakeFrameClock) {
            val state = overlay(this, shown = 2L)

            assertEquals(OverlayPhase.OPEN, state.phase)
            assertEquals(1f, state.progress, EPS)
        }

    @Test
    fun aShortDragSettlesBack() =
        runTest(FakeFrameClock) {
            val state = openState(this)

            state.dragStarted()
            state.dragBy(100f)
            assertEquals(OverlayPhase.DRAGGING, state.phase)
            // 100 von 1000 Pixeln sind 10 %, unter der Schwelle von 15 %.
            state.dragEnded(velocityYPerSecond = 0f, flingPerSecond = FLING)
            assertEquals(OverlayPhase.SETTLING, state.phase)

            advanceUntilIdle()
            assertEquals(OverlayPhase.OPEN, state.phase)
            assertEquals(0f, state.dragY, EPS)
            assertEquals(1L, state.shownId)
        }

    @Test
    fun aDragPastTheThresholdCloses() =
        runTest(FakeFrameClock) {
            val state = openState(this)

            state.dragStarted()
            state.dragBy(200f)
            state.dragEnded(velocityYPerSecond = 0f, flingPerSecond = FLING)
            assertEquals(OverlayPhase.CLOSING, state.phase)

            advanceUntilIdle()
            assertEquals(OverlayPhase.CLOSED, state.phase)
            assertNull(state.shownId)
        }

    @Test
    fun aFastShortFlingCloses() =
        runTest(FakeFrameClock) {
            val state = openState(this)

            state.dragStarted()
            state.dragBy(60f)
            state.dragEnded(velocityYPerSecond = FLING + 1f, flingPerSecond = FLING)

            assertEquals(OverlayPhase.CLOSING, state.phase)
        }

    @Test
    fun aDragKeepsTheImageBelowZeroOutOfReach() =
        runTest(FakeFrameClock) {
            val state = openState(this)

            state.dragStarted()
            state.dragBy(-50f)

            assertEquals(0f, state.dragY, EPS)
        }

    @Test
    fun backProgressShrinksTowardsTheTileAndCancelSettlesOpen() =
        runTest(FakeFrameClock) {
            val state = openState(this)

            state.backProgressed(0.4f)
            assertEquals(OverlayPhase.BACK, state.phase)
            assertEquals(0.6f, state.progress, EPS)

            state.backCancelled()
            assertEquals(OverlayPhase.SETTLING, state.phase)
            advanceUntilIdle()
            assertEquals(OverlayPhase.OPEN, state.phase)
            assertEquals(1f, state.progress, EPS)
        }

    @Test
    fun backCommittedClosesFromTheCurrentProgress() =
        runTest(FakeFrameClock) {
            val state = openState(this)

            state.backProgressed(0.5f)
            state.backCommitted()
            assertEquals(OverlayPhase.CLOSING, state.phase)

            advanceUntilIdle()
            assertEquals(OverlayPhase.CLOSED, state.phase)
            assertNull(state.shownId)
        }

    @Test
    fun closeRequestedOnAnOpenViewerClosesToTheCurrentElement() =
        runTest(FakeFrameClock) {
            val state = openState(this)
            state.select(2L)

            state.close()
            advanceUntilIdle()

            assertEquals(OverlayPhase.CLOSED, state.phase)
            assertNull(state.shownId)
            assertEquals(tiles[2L], state.heroTile)
        }

    @Test
    fun closeWhileClosedDoesNothing() =
        runTest(FakeFrameClock) {
            val state = overlay(this)

            state.close()
            advanceUntilIdle()

            assertEquals(OverlayPhase.CLOSED, state.phase)
            assertNull(state.shownId)
        }

    @Test
    fun selectChangesTheElementOnlyWhileOpen() =
        runTest(FakeFrameClock) {
            val state = overlay(this)

            state.select(2L)
            assertNull(state.shownId)

            state.open(1L)
            advanceUntilIdle()
            state.select(2L)
            assertEquals(2L, state.shownId)
        }

    @Test
    fun forceCloseHidesWithoutAnimation() =
        runTest(FakeFrameClock) {
            val state = openState(this)

            state.forceClose()

            assertEquals(OverlayPhase.CLOSED, state.phase)
            assertNull(state.shownId)
            assertEquals(0f, state.progress, EPS)
        }

    @Test
    fun dragEndedOutsideADragIsIgnored() =
        runTest(FakeFrameClock) {
            val state = openState(this)

            state.dragEnded(velocityYPerSecond = 0f, flingPerSecond = FLING)

            assertEquals(OverlayPhase.OPEN, state.phase)
        }

    private fun openState(scope: TestScope): ViewerOverlayState {
        val state = overlay(scope)
        state.open(1L)
        scope.advanceUntilIdle()
        return state
    }

    private fun overlay(
        scope: TestScope,
        shown: Long? = null,
    ): ViewerOverlayState {
        val state =
            ViewerOverlayState(
                scope = scope,
                shown = mutableStateOf(shown),
                cellRect = { id -> tiles[id] },
            )
        state.heightPx = HEIGHT
        return state
    }

    private companion object {
        const val EPS = 0.001f
        const val HEIGHT = 1000f
        const val FLING = 600f
    }
}

/**
 * Taktgeber für die Animationen im Test. Jeder Takt liefert sofort den nächsten Zeitpunkt (je 16 ms), ohne auf ein Bild zu
 * warten. Die Compose-Animationen brauchen einen solchen Takt im Kontext.
 */
private object FakeFrameClock : MonotonicFrameClock {
    private var nanos = 0L

    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
        nanos += FRAME_NANOS
        return onFrame(nanos)
    }

    private const val FRAME_NANOS = 16_000_000L
}
