package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.core.design.motion.LocalNavAnimatedVisibilityScope
import io.github.labushuya.emberlight.core.design.motion.LocalSharedTransitionScope
import io.github.labushuya.emberlight.core.design.motion.sharedMediaBounds
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import io.github.labushuya.emberlight.feature.gallery.timeline.TestThumbnails
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZoneId

/**
 * Öffnen aus dem Raster und Zurück, mit und ohne gemeinsamen Übergangs-Scope (ADR 0010). Die Uhr läuft von Hand, damit der
 * Übergang in Frames fortschreitet. Ohne Scope fällt jedes gemeinsame Element auf „kein Element“ zurück, der Übergang ist
 * dann nur das Einblenden.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-w412dp-h915dp")
class SharedElementTransitionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        TestThumbnails.install(TimelineFixtures.sampleItems())
        composeRule.mainClock.autoAdvance = false
    }

    @After
    fun tearDown() {
        TestThumbnails.reset()
    }

    @Test
    fun openingTheViewerAndGoingBackWorksWithASharedScope() {
        openAndGoBack(withScope = true)
    }

    @Test
    fun openingTheViewerAndGoingBackWorksWithoutASharedScope() {
        openAndGoBack(withScope = false)
    }

    @Test
    fun duringTheTransitionTheGridIsStillThereAndTheViewerAppears() {
        setHost(withScope = true)

        composeRule.onNodeWithTag(CELL_TAG).performClick()
        composeRule.mainClock.advanceTimeBy(FRAME_MS)
        composeRule.waitForIdle()
        assertEquals(1, composeRule.onAllNodesWithTag(CELL_TAG).fetchSemanticsNodes().size)

        composeRule.mainClock.advanceTimeBy(TRANSITION_MS)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(BACK).assertIsDisplayed()
    }

    private fun openAndGoBack(withScope: Boolean) {
        setHost(withScope)

        composeRule.onNodeWithTag(CELL_TAG).performClick()
        composeRule.mainClock.advanceTimeBy(TRANSITION_MS)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(BACK).assertIsDisplayed()

        composeRule.onNodeWithContentDescription(BACK).performClick()
        composeRule.mainClock.advanceTimeBy(TRANSITION_MS)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CELL_TAG).assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithContentDescription(BACK).fetchSemanticsNodes().size)
    }

    /** Gastgeber: ein Raster mit einer Kachel, die den Viewer öffnet, und der Viewer selbst. Der Scope ist optional. */
    private fun setHost(withScope: Boolean) {
        composeRule.setContent {
            EmberlightTheme {
                if (withScope) {
                    SharedTransitionLayout {
                        Host(sharedScope = this)
                    }
                } else {
                    Host(sharedScope = null)
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Composable
    private fun Host(sharedScope: SharedTransitionScope?) {
        var openedId by remember { mutableStateOf<Long?>(null) }
        CompositionLocalProvider(LocalSharedTransitionScope provides sharedScope) {
            AnimatedContent(targetState = openedId, label = "viewer") { id ->
                CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                    if (id == null) {
                        Grid(onOpen = { openedId = it })
                    } else {
                        ViewerContent(
                            items = listOf(photo(1, "2026-10-16T09:30")),
                            index = 0,
                            playback = VideoPlaybackState(),
                            muted = false,
                            player = null,
                            chromeVisible = true,
                            onChromeToggle = {},
                            onBack = { openedId = null },
                            onPageAtRest = {},
                            onPlay = {},
                            onPause = {},
                            onSeek = {},
                            onToggleMute = {},
                            onVideoEnd = {},
                            onVideoError = {},
                            imageSource = solidImageSources,
                            zone = ZoneId.of("Europe/Berlin"),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun Grid(onOpen: (Long) -> Unit) {
        Box(
            modifier =
                Modifier
                    .testTag(CELL_TAG)
                    .size(CELL_DP.dp)
                    .sharedMediaBounds(key = viewerSharedKey(1L))
                    .clickable { onOpen(1L) },
        )
    }

    private companion object {
        const val CELL_TAG = "grid-cell-1"
        const val CELL_DP = 120
        const val BACK = "Back"
        const val FRAME_MS = 16L
        const val TRANSITION_MS = 1_000L
    }
}
