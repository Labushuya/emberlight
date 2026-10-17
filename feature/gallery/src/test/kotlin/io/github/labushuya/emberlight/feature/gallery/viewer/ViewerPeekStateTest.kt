package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Kurzer Blick und Infobereich der Zustandsmaschine (ADR 0010, Kurzer Blick und Infobereich). Die Animationen laufen im
 * Testscheduler. Die Debug-Marken werden gesammelt, damit ihre Reihenfolge und ihr Inhalt geprüft werden können.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewerPeekStateTest {
    private val tiles = mapOf(1L to Rect(0f, 100f, 100f, 200f), 2L to Rect(100f, 100f, 200f, 200f))
    private val marks = mutableListOf<String>()

    @Before
    fun collectTraceMarks() {
        ViewerTrace.output = { line -> marks += line.substringBefore(" id=") }
    }

    @After
    fun restoreTrace() {
        ViewerTrace.output = { _ -> }
    }

    @Test
    fun holdingWithoutProgressBuildsThePeekStageAfterTheGrace() =
        runTest(FakeFrameClock) {
            val state = overlay(this, shown = 1L)

            state.backStarted()
            assertEquals(OverlayPhase.PEEKING, state.phase)
            advanceTimeBy(PEEK_GRACE_MS - 1)
            assertEquals(1f, state.progress, EPS)
            assertEquals(0f, state.peek, EPS)

            advanceUntilIdle()
            assertEquals(PEEK_STAGE, state.progress, EPS)
            assertEquals(1f, state.peek, EPS)
            assertEquals(1f, state.highlight, EPS)
            assertEquals(OverlayPhase.PEEKING, state.phase)
            assertEquals(
                listOf("Blick Start (ohne Fortschritt)", "Blick Stufe erreicht"),
                marks.filter { it.startsWith("Blick") },
            )
        }

    @Test
    fun cancellingTheHoldReturnsToTheFullImageAndStaysOpen() =
        runTest(FakeFrameClock) {
            val state = overlay(this, shown = 1L)
            state.backStarted()
            advanceUntilIdle()

            state.backCancelled()
            advanceUntilIdle()

            assertEquals(OverlayPhase.OPEN, state.phase)
            assertEquals(1L, state.shownId)
            assertEquals(1f, state.progress, EPS)
            assertEquals(0f, state.peek, EPS)
            assertEquals(0f, state.highlight, EPS)
            assertTrue(marks.any { it.startsWith("Blick abgebrochen nach ") && it.endsWith(" ms") })
        }

    @Test
    fun completingTheHoldClosesTheViewerToTheTile() =
        runTest(FakeFrameClock) {
            val state = overlay(this, shown = 1L)
            state.backStarted()
            advanceUntilIdle()

            state.backCommitted()
            advanceUntilIdle()

            assertNull(state.shownId)
            assertEquals(OverlayPhase.CLOSED, state.phase)
            assertTrue(marks.contains("Blick vollendet"))
        }

    @Test
    fun releasingBeforeTheGraceIsAPlainCloseWithoutAStage() =
        runTest(FakeFrameClock) {
            val state = overlay(this, shown = 1L)
            state.backStarted()
            state.backCommitted()
            advanceUntilIdle()

            assertNull(state.shownId)
            assertFalse(marks.any { it.startsWith("Blick Stufe") })
        }

    @Test
    fun progressDuringTheGraceFollowsTheFingerWithoutAStage() =
        runTest(FakeFrameClock) {
            val state = overlay(this, shown = 1L)
            state.backStarted()
            state.backProgressed(0.3f)
            advanceUntilIdle()

            assertEquals(OverlayPhase.BACK, state.phase)
            assertEquals(0.7f, state.progress, EPS)
            assertEquals(0f, state.peek, EPS)
            assertTrue(marks.contains("Blick Start (mit Fortschritt)"))
            assertFalse(marks.contains("Blick Stufe erreicht"))
        }

    @Test
    fun anOpenInfoSheetTakesTheBackGestureFirst() =
        runTest(FakeFrameClock) {
            val state = overlay(this, shown = 1L)
            state.openInfo()
            advanceUntilIdle()
            assertTrue(state.infoOpen)

            state.backStarted()
            assertEquals(OverlayPhase.OPEN, state.phase)
            state.backProgressed(0.5f)
            assertEquals(OverlayPhase.OPEN, state.phase)

            state.backCommitted()
            advanceUntilIdle()

            assertFalse(state.infoOpen)
            assertEquals(0f, state.infoFraction, EPS)
            assertEquals(1L, state.shownId)
            assertEquals(OverlayPhase.OPEN, state.phase)
        }

    @Test
    fun closingWithAnOpenInfoSheetClosesOnlyTheSheet() =
        runTest(FakeFrameClock) {
            val state = overlay(this, shown = 1L)
            state.openInfo()
            advanceUntilIdle()

            state.close()
            advanceUntilIdle()

            assertFalse(state.infoOpen)
            assertEquals(1L, state.shownId)
            assertEquals(OverlayPhase.OPEN, state.phase)
        }

    @Test
    fun aShortUpwardDragStaysClosedUnlessItFlings() =
        runTest(FakeFrameClock) {
            val state = overlay(this, shown = 1L)

            state.infoDragStarted()
            state.infoDragBy(-240f)
            assertEquals(0.4f, state.infoFraction, EPS)
            state.infoDragEnded(velocityYPerSecond = 0f, flingPerSecond = FLING)
            advanceUntilIdle()
            assertFalse(state.infoOpen)
            assertEquals(0f, state.infoFraction, EPS)

            state.infoDragStarted()
            state.infoDragBy(-50f)
            state.infoDragEnded(velocityYPerSecond = -700f, flingPerSecond = FLING)
            advanceUntilIdle()
            assertTrue(state.infoOpen)
            assertEquals(1f, state.infoFraction, EPS)
        }

    @Test
    fun aDragToHalfwayRestsOpen() =
        runTest(FakeFrameClock) {
            val state = overlay(this, shown = 1L)

            state.infoDragStarted()
            state.infoDragBy(-300f)
            state.infoDragEnded(velocityYPerSecond = 0f, flingPerSecond = FLING)
            advanceUntilIdle()

            assertTrue(state.infoOpen)
        }

    @Test
    fun aDownwardFlingClosesAnOpenSheetEvenFromAboveHalf() =
        runTest(FakeFrameClock) {
            val state = overlay(this, shown = 1L)
            state.openInfo()
            advanceUntilIdle()

            state.infoDragStarted()
            state.infoDragBy(120f)
            state.infoDragEnded(velocityYPerSecond = 700f, flingPerSecond = FLING)
            advanceUntilIdle()

            assertFalse(state.infoOpen)
        }

    @Test
    fun infoStaysClosedWhileTheViewerIsNotOpen() {
        val state = overlay(scope = null, shown = null)

        state.openInfo()

        assertFalse(state.infoOpen)
        assertEquals(0f, state.infoFraction, EPS)
    }

    @Test
    fun theImageMovesUpByTheHiddenPartWhenTheSheetIsOpen() {
        val state = overlay(scope = null, shown = null)
        state.fit = Rect(0f, 0f, 500f, 1000f)

        // Die Unterkante des Bildes rückt auf 40 % der Höhe. Dort endet der sichtbare Teil über dem Infobereich.
        assertEquals(600f, state.infoShiftPx(), EPS)
    }

    @Test
    fun theBackdropFadesOnTheStageAndIsFullWhenOpen() {
        assertEquals(PEEK_BACKDROP_ALPHA, backdropAlpha(OverlayPhase.PEEKING, PEEK_STAGE, 0f, HEIGHT, peek = 1f), EPS)
        assertEquals(PEEK_STAGE, backdropAlpha(OverlayPhase.PEEKING, PEEK_STAGE, 0f, HEIGHT, peek = 0f), EPS)
        assertEquals(1f, backdropAlpha(OverlayPhase.OPEN, 1f, 0f, HEIGHT), EPS)
    }

    private fun overlay(
        scope: TestScope?,
        shown: Long?,
    ): ViewerOverlayState {
        val state =
            ViewerOverlayState(
                scope = scope ?: TestScope(),
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
