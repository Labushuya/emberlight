package io.github.labushuya.emberlight.feature.gallery.timeline

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.center
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import io.github.labushuya.emberlight.core.media.model.MediaItem
import io.github.labushuya.emberlight.core.media.permission.MediaAccess
import io.github.labushuya.emberlight.feature.gallery.GalleryContent
import io.github.labushuya.emberlight.feature.gallery.GalleryUiState
import io.github.labushuya.emberlight.feature.gallery.album.AlbumList
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.TODAY
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures.ZONE
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import io.github.labushuya.emberlight.feature.gallery.R as GalleryR

/**
 * Geste und Bedienung der Zeitleiste im Compose-Test: Pinch mit Stufenwechsel, Grenzen, Ein-Finger-Scrollen,
 * Bedienungshilfen und die Schnell-Scrollleiste mit Datums-Blase.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class GalleryZoomUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val items = TimelineFixtures.longLibrary()

    @Before
    fun setUp() {
        TestThumbnails.install(items)
    }

    @After
    fun tearDown() {
        TestThumbnails.reset()
    }

    @Test
    fun pinchOutEnlargesTilesAndLowersTheStage() {
        val harness = Harness(level = ZoomLevels.DEFAULT_LEVEL)
        harness.show(composeRule)

        grid().performTouchInput { pinchApart() }
        composeRule.waitForIdle()

        assertTrue(harness.changes.isNotEmpty())
        assertEquals(ZoomLevels.DEFAULT_LEVEL - 1, harness.changes.first())
    }

    @Test
    fun pinchInShrinksTilesAndRaisesTheStage() {
        val harness = Harness(level = ZoomLevels.DEFAULT_LEVEL)
        harness.show(composeRule)

        grid().performTouchInput { pinchTogether() }
        composeRule.waitForIdle()

        assertTrue(harness.changes.isNotEmpty())
        assertEquals(ZoomLevels.DEFAULT_LEVEL + 1, harness.changes.first())
    }

    @Test
    fun pinchOutAtTheLargestTilesDoesNotSwitch() {
        val harness = Harness(level = ZoomLevels.MIN_LEVEL)
        harness.show(composeRule)

        grid().performTouchInput { pinchApart() }
        composeRule.waitForIdle()

        assertTrue(harness.changes.isEmpty())
        assertEquals(ZoomLevels.MIN_LEVEL, harness.level)
    }

    @Test
    fun pinchInAtTheSmallestTilesDoesNotSwitch() {
        val harness = Harness(level = ZoomLevels.MAX_LEVEL)
        harness.show(composeRule)

        grid().performTouchInput { pinchTogether() }
        composeRule.waitForIdle()

        assertTrue(harness.changes.isEmpty())
    }

    @Test
    fun oneFingerSwipeStillScrolls() {
        val harness = Harness(level = ZoomLevels.DEFAULT_LEVEL)
        harness.show(composeRule)
        val today = context.getString(GalleryR.string.feature_gallery_header_today)
        composeRule.onNode(hasText(today) and isHeading()).assertIsDisplayed()

        repeat(6) { grid().performTouchInput { swipeUp() } }
        composeRule.waitForIdle()

        composeRule.onAllNodes(hasText(today) and isHeading()).assertCountEquals(0)
        assertTrue(harness.changes.isEmpty())
    }

    @Test
    fun customActionsChangeTheStageLikeTheGesture() {
        val harness = Harness(level = ZoomLevels.DEFAULT_LEVEL)
        harness.show(composeRule)

        assertTrue(invokeAction(context.getString(GalleryR.string.feature_gallery_zoom_in)))
        composeRule.waitForIdle()
        assertTrue(invokeAction(context.getString(GalleryR.string.feature_gallery_zoom_out)))
        composeRule.waitForIdle()

        assertEquals(listOf(ZoomLevels.DEFAULT_LEVEL - 1, ZoomLevels.DEFAULT_LEVEL), harness.changes)
    }

    @Test
    fun customActionReportsTheEdge() {
        val harness = Harness(level = ZoomLevels.MIN_LEVEL)
        harness.show(composeRule)

        assertFalse(invokeAction(context.getString(GalleryR.string.feature_gallery_zoom_in)))
        assertTrue(harness.changes.isEmpty())
    }

    @Test
    fun stageIsAnnouncedAsColumnCount() {
        Harness(level = ZoomLevels.DEFAULT_LEVEL).show(composeRule)

        val node = grid().fetchSemanticsNode()
        val widthDp = node.size.width / context.resources.displayMetrics.density
        val columns = ZoomLevels.columnsFor(ZoomLevels.DEFAULT_LEVEL, widthDp)
        assertEquals("$columns columns", node.config[SemanticsProperties.StateDescription])
    }

    @Test
    fun dragOnTheFastScrollBarShowsTheMonthBubble() {
        Harness(level = ZoomLevels.DEFAULT_LEVEL).show(composeRule)
        val rail =
            composeRule.onNodeWithContentDescription(
                context.getString(GalleryR.string.feature_gallery_fast_scroll),
            )
        rail.assertIsDisplayed()

        rail.performTouchInput {
            down(center)
            moveTo(Offset(center.x, 4f))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(monthLabel(TODAY)).assertIsDisplayed()

        rail.performTouchInput { up() }
        composeRule.waitForIdle()
    }

    @Test
    fun dragToTheEndShowsTheOldestMonth() {
        Harness(level = ZoomLevels.DEFAULT_LEVEL).show(composeRule)
        val rail =
            composeRule.onNodeWithContentDescription(
                context.getString(GalleryR.string.feature_gallery_fast_scroll),
            )

        rail.performTouchInput {
            down(center)
            moveTo(Offset(center.x, height - 4f))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(monthLabel(oldestDate())).assertIsDisplayed()

        rail.performTouchInput { up() }
        composeRule.waitForIdle()
    }

    @Test
    fun monthStageShowsTheYearInTheBubble() {
        Harness(level = ZoomLevels.MAX_LEVEL).show(composeRule)
        val rail =
            composeRule.onNodeWithContentDescription(
                context.getString(GalleryR.string.feature_gallery_fast_scroll),
            )

        rail.performTouchInput {
            down(center)
            moveTo(Offset(center.x, 4f))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("2026").assertIsDisplayed()

        rail.performTouchInput { up() }
        composeRule.waitForIdle()
    }

    private fun grid() = composeRule.mediaGrid()

    /** Ruft eine Bedienungshilfe-Aktion des Rasters auf und liefert ihr Ergebnis. */
    private fun invokeAction(label: String): Boolean {
        val actions = grid().fetchSemanticsNode().config[SemanticsActions.CustomActions]
        return actions.first { it.label == label }.action.invoke()
    }

    private fun oldestDate(): LocalDate = Instant.ofEpochMilli(items.last().timestamp).atZone(ZONE).toLocalDate()

    /** Erwarteter Blasentext in der Sprache der Tests (Englisch), gebildet über dieselben Formatierer wie die App. */
    private fun monthLabel(date: LocalDate): String =
        TimelineFormats(Locale.ENGLISH, ZONE).scrollLabel(GroupingMode.DAY, date)

    /** Festes Zustandsmodell des Tests: Jede angeforderte Stufe wird gemeldet und übernommen. */
    private inner class Harness(
        level: Int,
    ) {
        var level by mutableIntStateOf(level)
        val changes = mutableListOf<Int>()

        fun show(rule: ComposeContentTestRule) {
            rule.setContent {
                EmberlightTheme {
                    GalleryContent(
                        state = GalleryUiState(grid = content(), albums = AlbumList(emptyList())),
                        onRequestPermission = {},
                        onOpenSettings = {},
                        onMediaClick = {},
                        onAlbumClick = {},
                        onZoomLevelChange = { next ->
                            changes += next
                            level = next
                        },
                    )
                }
            }
        }

        private fun content(): MediaGridState.Content {
            val clamped = ZoomLevels.clamp(level)
            return MediaGridState.Content(
                sections = TimelineGrouping.group(items, ZoomLevels.groupingFor(clamped), TODAY, ZONE),
                access = MediaAccess.FULL,
                missingTypes = emptySet(),
                zoomLevel = clamped,
                today = TODAY,
            )
        }
    }
}
