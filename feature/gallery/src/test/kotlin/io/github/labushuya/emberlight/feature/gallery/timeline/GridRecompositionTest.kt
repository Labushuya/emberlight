package io.github.labushuya.emberlight.feature.gallery.timeline

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.TODAY
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

/**
 * Scroll-Wiederherstellung und Recomposition des Rasters (Befund A). Die Tests prüfen, dass Scrollen keine Zellen neu
 * zusammensetzt, dass eine manuelle Scrollposition nach dem Rücksprung aus dem Viewer erhalten bleibt und dass das Ziel
 * einer Rücksprung-Meldung genau einmal angewendet wird, auch über einen Konfigurationswechsel hinweg (ADR 0008).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-w412dp-h915dp")
class GridRecompositionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val items = TimelineFixtures.longLibrary()
    private var hostZone: TimeZone? = null

    @Before
    fun setUp() {
        // Die Datumsbeschriftung der Zellen folgt der Zone des Rechners. UTC hält die Beschreibungen gleich.
        hostZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        TestThumbnails.install(items)
    }

    @After
    fun tearDown() {
        GridProbeHolder.current = null
        TestThumbnails.reset()
        TimeZone.setDefault(hostZone)
    }

    @Test
    fun scrollingWhileTheHeaderCollapsesDoesNotRecomposeCellsThatStayVisible() {
        // Vier Kacheln je Zeile: genug Zellen, die über das Einklappen sichtbar bleiben.
        val dense = denseLibrary()
        TestThumbnails.install(dense)
        val probe = GridProbeRecorder()
        GalleryHarness(dense, probe = probe).show(composeRule)
        composeRule.waitForIdle()
        val visibleBefore = probe.visibleCells.toSet()
        val compositionsBefore = probe.cellCompositions.toMap()

        repeat(4) { scrollGridUp(distance = 60f) }
        composeRule.waitForIdle()

        val stayed = visibleBefore intersect probe.visibleCells
        assertTrue("Es müssen Zellen über das Scrollen sichtbar bleiben.", stayed.size >= 5)
        val recomposed = stayed.filter { probe.cellCompositions[it] != compositionsBefore[it] }
        assertEquals("Zellen, die sichtbar blieben, wurden neu komponiert.", emptyList<String>(), recomposed)
    }

    @Test
    fun manualScrollAfterReturnSurvivesLaterRecomposition() {
        val harness = GalleryHarness(items, initialLastViewed = items[ELEMENT_INDEX].id)
        harness.show(composeRule)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(cellLabel(ELEMENT_INDEX)).assertIsDisplayed()

        scrollGridToTop()
        composeRule.onNodeWithContentDescription(cellLabel(0)).assertIsDisplayed()

        // Eine Neukomposition ohne neue Meldung darf die Scrollposition nicht zurücksetzen.
        harness.tick++
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(cellLabel(0)).assertIsDisplayed()
        assertEquals(1, harness.handledCount)
    }

    @Test
    fun aConsumedReturnDoesNotScrollAgainAfterAConfigurationChange() {
        val harness = GalleryHarness(items, initialLastViewed = items[ELEMENT_INDEX].id)
        harness.show(composeRule)
        composeRule.waitForIdle()
        scrollGridToTop()

        harness.generation++
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(cellLabel(0)).assertIsDisplayed()
        assertEquals(1, harness.handledCount)
    }

    @Test
    fun aReturnIsAppliedOnceEvenIfTheMessageStaysAcrossARestore() {
        // Die Meldung wird verbraucht, bleibt im Zustand aber stehen (etwa bis der Zustand asynchron leer wird).
        // Ein Konfigurationswechsel darf das Ziel dann nicht erneut anwenden.
        val harness = GalleryHarness(items, initialLastViewed = items[ELEMENT_INDEX].id, clearOnHandled = false)
        val restorer = StateRestorationTester(composeRule)
        restorer.setContent {
            EmberlightTheme {
                harness.Body()
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(cellLabel(ELEMENT_INDEX)).assertIsDisplayed()
        scrollGridToTop()
        composeRule.onNodeWithContentDescription(cellLabel(0)).assertIsDisplayed()

        restorer.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(cellLabel(0)).assertIsDisplayed()
        assertEquals(1, harness.handledCount)
    }

    private fun scrollGridUp(distance: Float) {
        composeRule.mediaGrid().performTouchInput {
            swipeUp(startY = height * 0.7f, endY = height * 0.7f - distance, durationMillis = 200)
        }
    }

    private fun scrollGridToTop() {
        repeat(40) {
            composeRule.mediaGrid().performTouchInput {
                swipeDown(startY = height * 0.2f, endY = height * 0.9f, durationMillis = 100)
            }
        }
        composeRule.waitForIdle()
    }

    /** Beschreibung der Zelle an Position [index], wie das Raster sie ausgibt (englische Locale der Tests). */
    private fun cellLabel(index: Int): String = "Photo from ${DATE.format(TODAY.minusDays(index * 2L))}"

    private companion object {
        const val ELEMENT_INDEX = 150
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)
    }
}
