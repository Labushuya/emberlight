package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.feature.gallery.timeline.GalleryHarness
import io.github.labushuya.emberlight.feature.gallery.timeline.GridProbeHolder
import io.github.labushuya.emberlight.feature.gallery.timeline.GridProbeRecorder
import io.github.labushuya.emberlight.feature.gallery.timeline.TestThumbnails
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Die Hervorhebung der Kachel während des Kurzen Blicks rekomponiert keine Zelle des Rasters (ADR 0010, Kurzer Blick). Gezählt
 * werden die Kompositionen der Zellen, solange der Blick läuft. Die Hervorhebung liegt im Overlay und liest nur Zustandswerte in
 * der Zeichenphase.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w412dp-h915dp")
@OptIn(ExperimentalTestApi::class)
class PeekHighlightRecompositionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val items = TimelineFixtures.sampleItems()

    @Before
    fun setUp() {
        TestThumbnails.install(items)
    }

    @After
    fun tearDown() {
        GridProbeHolder.current = null
        TestThumbnails.reset()
    }

    @Test
    fun theHighlightDoesNotRecomposeAnyCellWhileThePeekRuns() {
        val probe = GridProbeRecorder()
        GridProbeHolder.current = probe
        GalleryHarness(items, probe = probe).show(composeRule)
        composeRule.waitForIdle()

        composeRule.onAllNodes(hasClickAction() and hasContentDescription("from", substring = true))[0].performClick()
        composeRule.waitUntil(
            WAIT_MS,
        ) { composeRule.onAllNodesWithTag(VIEWER_OVERLAY_TAG).fetchSemanticsNodes().isNotEmpty() }
        composeRule.waitForIdle()
        val before = probe.cellCompositions.toMap()

        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle {
            dispatcher().dispatchOnBackStarted(
                BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT, 0L),
            )
        }
        // Die Blick-Stufe läuft über ihre Dauer. Der Takt wird von Hand vorangetrieben.
        composeRule.mainClock.advanceTimeBy(BLICK_MS)
        composeRule.waitForIdle()

        assertEquals("Der Kurze Blick darf keine Zelle neu zusammensetzen.", before, probe.cellCompositions.toMap())
        composeRule.runOnIdle { dispatcher().dispatchOnBackCancelled() }
        composeRule.mainClock.autoAdvance = true
    }

    private fun dispatcher() = composeRule.activity.onBackPressedDispatcher

    private companion object {
        const val WAIT_MS = 5_000L
        const val BLICK_MS = 600L
    }
}
