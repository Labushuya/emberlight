package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Zustandsmaschine des Overlays mit Animationen: Öffnen, Schließen, Wegwischen über und unter der Schwelle, Rückgeste mit
 * Fortschritt, Abbruch und Vollenden. Die Animationen laufen im Testscheduler, `advanceUntilIdle` lässt sie enden.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewerOverlayStateTest {
    private val tiles = mapOf(1L to Rect(0f, 100f, 100f, 200f), 2L to Rect(100f, 100f, 200f, 200f))

    /** Die Debug-Marken sammeln, statt sie nach logcat zu schreiben (reiner JVM-Test). */
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

    @Test
    fun theHeroStaysUntilThePageShowsItsImage() =
        runTest(FakeFrameClock) {
            val state = overlay(this)

            state.open(1L)
            advanceTimeBy(BEFORE_FALLBACK_MS)
            assertEquals(OverlayPhase.OPEN, state.phase)
            assertEquals("Ohne Bereitschaft der Seite bleibt das Vorschaubild stehen.", 1f, state.heroAlpha, EPS)

            state.pageShown(1L)
            advanceUntilIdle()
            assertEquals(0f, state.heroAlpha, EPS)
            assertEquals(
                listOf("Hero-Animation Start", "Hero-Animation Ende", "Seite Bild angezeigt", "Hero entfernt"),
                marks,
            )
        }

    @Test
    fun aPageThatReadyBeforeTheAnimationEndsRetiresTheHeroAfterIt() =
        runTest(FakeFrameClock) {
            val state = overlay(this)

            state.open(1L)
            state.pageShown(1L)
            advanceUntilIdle()

            assertEquals(0f, state.heroAlpha, EPS)
            assertEquals(
                listOf("Hero-Animation Start", "Seite Bild angezeigt", "Hero-Animation Ende", "Hero entfernt"),
                marks,
            )
        }

    @Test
    fun theHeroFallsBackWhenThePageNeverShowsItsImage() =
        runTest(FakeFrameClock) {
            val state = overlay(this)

            state.open(1L)
            advanceUntilIdle()

            assertEquals(0f, state.heroAlpha, EPS)
            assertTrue(marks.contains("Hero-Rückfall ohne Bild"))
        }

    @Test
    fun aPageOfAnotherElementDoesNotCountAsReady() =
        runTest(FakeFrameClock) {
            val state = overlay(this)

            state.open(1L)
            state.pageShown(2L)
            advanceTimeBy(BEFORE_FALLBACK_MS)

            assertEquals(1f, state.heroAlpha, EPS)
        }

    @Test
    fun choosingAnotherElementDropsTheHero() =
        runTest(FakeFrameClock) {
            val state = overlay(this)

            state.open(1L)
            advanceTimeBy(BEFORE_FALLBACK_MS)
            state.select(2L)

            assertEquals(0f, state.heroAlpha, EPS)
            assertEquals(2L, state.shownId)
        }

    @Test
    fun closingSecuresTheTileFirstAndKeepsTheHeroOpaque() =
        runTest(FakeFrameClock) {
            val state = overlay(this, shown = 1L)
            val secured = Rect(5f, 110f, 95f, 190f)
            state.beforeClose = {
                delay(16L)
                secured
            }

            state.close()
            advanceTimeBy(1L)
            assertEquals(1f, state.heroAlpha, EPS)

            advanceUntilIdle()
            assertNull(state.shownId)
            assertEquals(OverlayPhase.CLOSED, state.phase)
            assertEquals(secured, state.heroTile)
            assertTrue(marks.contains("Ziel-Grenzen aktuell"))
        }

    @Test
    fun aTileThatIsNotVisibleAfterTheLayoutFallsBackToTheMiddle() =
        runTest(FakeFrameClock) {
            val state = overlay(this, shown = 1L)
            state.beforeClose = { null }

            state.close()
            advanceUntilIdle()

            assertNull(state.heroTile)
            assertTrue(marks.contains("Ziel ohne Kachel"))
        }

    @Test
    fun openingTakesTheTileItWasGivenInsteadOfTheCellLookup() =
        runTest(FakeFrameClock) {
            val state = overlay(this)
            val given = Rect(1f, 2f, 3f, 4f)

            state.open(1L, given)

            assertEquals(given, state.heroTile)
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
        const val BEFORE_FALLBACK_MS = 500L
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
