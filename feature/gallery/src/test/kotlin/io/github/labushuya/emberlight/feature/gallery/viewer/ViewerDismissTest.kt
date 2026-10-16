package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZoneId

/**
 * Wegwischen nach unten und die System-Rückgeste auf Compose-Ebene (ADR 0010). Die Schwelle liegt bei ca. 15 % der Höhe, der
 * Test wischt darüber (40 %) und darunter (5 %). Die Lage des Bildes wird über die Grenzen der Semantik gelesen.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-w412dp-h915dp")
class ViewerDismissTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var backs = 0

    @Test
    fun swipingDownOverTheThresholdClosesTheViewer() {
        setContent()

        composeRule.onNodeWithContentDescription(LABEL_FIRST).performTouchInput {
            swipe(start = center, end = center + Offset(0f, height * 0.4f), durationMillis = SWIPE_MS)
        }
        composeRule.waitUntil(TIMEOUT_MS) { backs == 1 }

        assertEquals(1, backs)
    }

    @Test
    fun swipingDownUnderTheThresholdSpringsBack() {
        setContent()
        val rest = imageTop()

        composeRule.onNodeWithContentDescription(LABEL_FIRST).performTouchInput {
            swipe(start = center, end = center + Offset(0f, height * 0.05f), durationMillis = SWIPE_MS)
        }
        composeRule.waitUntil(TIMEOUT_MS) { imageTop() <= rest + SETTLED_PX }

        assertEquals(0, backs)
        assertEquals(rest, imageTop(), SETTLED_PX)
    }

    @Test
    fun theImageFollowsTheFingerWhileDragging() {
        setContent()
        val rest = imageTop()

        composeRule.onNodeWithContentDescription(LABEL_FIRST).performTouchInput {
            down(center)
            moveBy(Offset(0f, height * 0.1f))
        }
        composeRule.waitForIdle()
        val dragged = imageTop()
        composeRule.onNodeWithContentDescription(LABEL_FIRST).performTouchInput { up() }
        composeRule.waitUntil(TIMEOUT_MS) { backs == 0 && imageTop() <= rest + SETTLED_PX }

        assertTrue("Das Bild folgt dem Finger ($dragged > $rest)", dragged > rest + SETTLED_PX)
        assertEquals(0, backs)
    }

    @Test
    fun horizontalSwipeChangesThePageAndDoesNotDismiss() {
        setContent()

        composeRule.onNodeWithContentDescription(LABEL_FIRST).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertEquals(0, backs)
        composeRule.onNodeWithContentDescription(LABEL_SECOND).assertIsDisplayed()
    }

    @Test
    fun aZoomedImageDoesNotDismiss() {
        var dismissed = 0
        lateinit var zoom: me.saket.telephoto.zoomable.ZoomableImageState
        composeRule.setContent {
            EmberlightTheme {
                zoom = rememberZoomableImageState(rememberZoomableState())
                ViewerImagePage(
                    item = photos().first(),
                    contentDescription = LABEL_FIRST,
                    imageSource = solidImageSources,
                    onTap = {},
                    state = zoom,
                    onDismiss = { dismissed++ },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(LABEL_FIRST).performTouchInput { doubleClick() }
        composeRule.waitUntil(TIMEOUT_MS) { (zoom.zoomableState.zoomFraction ?: 0f) > 0f }

        composeRule.onNodeWithContentDescription(LABEL_FIRST).performTouchInput {
            swipe(start = center, end = center + Offset(0f, height * 0.4f), durationMillis = SWIPE_MS)
        }
        composeRule.waitForIdle()

        assertEquals(0, dismissed)
    }

    @Test
    fun cancelledBackGestureLeavesTheViewerWithoutArtefacts() {
        setContent()
        val rest = imageWidth()
        val dispatcher = composeRule.activity.onBackPressedDispatcher

        composeRule.runOnUiThread {
            dispatcher.dispatchOnBackStarted(backEvent(0f))
            dispatcher.dispatchOnBackProgressed(backEvent(0.5f))
        }
        composeRule.waitUntil(TIMEOUT_MS) { imageWidth() < rest - SETTLED_PX }
        composeRule.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        composeRule.waitUntil(TIMEOUT_MS) { kotlin.math.abs(imageWidth() - rest) <= SETTLED_PX }

        assertEquals(0, backs)
        assertEquals(rest, imageWidth(), SETTLED_PX)
    }

    @Test
    fun completedBackGestureClosesTheViewer() {
        setContent()
        val dispatcher = composeRule.activity.onBackPressedDispatcher

        composeRule.runOnUiThread {
            dispatcher.dispatchOnBackStarted(backEvent(0f))
            dispatcher.dispatchOnBackProgressed(backEvent(0.9f))
            dispatcher.onBackPressed()
        }
        composeRule.waitUntil(TIMEOUT_MS) { backs == 1 }

        assertEquals(1, backs)
    }

    /** Oberkante des Bildes in Pixeln, so wie die Oberfläche es gerade zeichnet. */
    private fun imageTop(): Float =
        composeRule
            .onNodeWithContentDescription(LABEL_FIRST)
            .fetchSemanticsNode()
            .boundsInRoot.top

    /** Breite des Bildes in Pixeln. Die Vorschau verkleinert sich mit dem Rückweg. */
    private fun imageWidth(): Float =
        composeRule
            .onNodeWithContentDescription(LABEL_FIRST)
            .fetchSemanticsNode()
            .boundsInRoot.width

    private fun backEvent(progress: Float) = BackEventCompat(0f, 0f, progress, BackEventCompat.EDGE_LEFT)

    private fun setContent() {
        composeRule.setContent {
            EmberlightTheme {
                ViewerContent(
                    items = photos(),
                    index = 0,
                    playback = VideoPlaybackState(),
                    muted = false,
                    player = null,
                    chromeVisible = true,
                    onChromeToggle = {},
                    onBack = { backs++ },
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
        composeRule.waitForIdle()
    }

    private fun photos() =
        listOf(
            photo(1, "2026-10-16T09:30"),
            photo(2, "2026-10-15T10:02"),
        )

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val SWIPE_MS = 300L

        /** Toleranz, ab der eine Bewegung als eingeschwungen gilt (Pixel). */
        const val SETTLED_PX = 1f
        const val LABEL_FIRST = "Photo from October 16, 2026"
        const val LABEL_SECOND = "Photo from October 15, 2026"
    }
}
