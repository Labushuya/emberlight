package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.feature.gallery.timeline.GalleryHarness
import io.github.labushuya.emberlight.feature.gallery.timeline.TestThumbnails
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/**
 * Das Overlay über dem Raster: Antippen einer Kachel öffnet es, das Raster bleibt im Baum. Das Overlay trägt eine Markierung
 * ([VIEWER_OVERLAY_TAG]), damit der Test es vom Raster unterscheidet.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w412dp-h915dp")
@OptIn(ExperimentalTestApi::class)
class ViewerOverlayComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val items = TimelineFixtures.sampleItems()
    private var hostZone: TimeZone? = null

    @Before
    fun setUp() {
        hostZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        TestThumbnails.install(items)
    }

    @After
    fun tearDown() {
        TestThumbnails.reset()
        TimeZone.setDefault(hostZone)
    }

    @Test
    fun tappingATileOpensTheOverlayAndKeepsTheGridInTheTree() {
        GalleryHarness(items).show(composeRule)
        composeRule.waitForIdle()
        val cells = hasContentDescription("Photo from", substring = true)

        composeRule.onAllNodes(cells).onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = WAIT_MS) {
            composeRule.onAllNodesWithTag(VIEWER_OVERLAY_TAG).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag(VIEWER_OVERLAY_TAG).assertIsDisplayed()
        assertTrue(composeRule.onAllNodes(cells).fetchSemanticsNodes().isNotEmpty())
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
