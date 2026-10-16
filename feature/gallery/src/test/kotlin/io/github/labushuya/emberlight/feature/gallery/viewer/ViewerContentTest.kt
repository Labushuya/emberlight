package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.feature.gallery.timeline.TestThumbnails
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures
import me.saket.telephoto.zoomable.ZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZoneId

/**
 * Der Viewer auf Compose-Ebene: Wischen wechselt Seite und Titel, Antippen schaltet die Leisten, Doppeltipp zoomt (über die
 * Zustands-API von Telephoto), das Video zeigt den Abspiel-Knopf, Zurück ruft [ViewerContent.onBack] auf.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-w412dp-h915dp")
class ViewerContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun tearDown() {
        TestThumbnails.reset()
    }

    @Test
    fun swipeChangesThePageAndTheTitle() {
        val settled = mutableListOf<Long>()
        setContent(items = photos(), onPageAtRest = { settled += it })
        composeRule.onNodeWithText(TITLE_FIRST).assertIsDisplayed()

        composeRule.onNodeWithContentDescription(LABEL_FIRST).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(TITLE_SECOND).assertIsDisplayed()
        assertTrue("Die eingerastete Seite wird gemeldet", settled.contains(2L))
    }

    @Test
    fun tappingTogglesTheChrome() {
        var toggles = 0
        setContent(items = photos(), onChromeToggle = { toggles++ })

        composeRule.onNodeWithContentDescription(LABEL_FIRST).performClick()

        composeRule.waitUntil(TIMEOUT_MS) { toggles == 1 }
    }

    @Test
    fun hiddenChromeHidesTheTopBar() {
        setContent(items = photos(), chromeVisible = false)

        composeRule.onNodeWithContentDescription(BACK).assertDoesNotExist()
    }

    @Test
    fun backButtonLeavesTheViewer() {
        var backs = 0
        setContent(items = photos(), onBack = { backs++ })

        composeRule.onNodeWithContentDescription(BACK).performClick()

        assertEquals(1, backs)
    }

    @Test
    fun doubleTapZoomsTheImage() {
        lateinit var zoom: ZoomableImageState
        composeRule.setContent {
            EmberlightTheme {
                zoom = rememberZoomableImageState(rememberZoomableState())
                ViewerImagePage(
                    item = photos().first(),
                    contentDescription = LABEL_FIRST,
                    imageSource = solidImageSources,
                    onTap = {},
                    state = zoom,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(LABEL_FIRST).performTouchInput { doubleClick() }

        composeRule.waitUntil(TIMEOUT_MS) { (zoom.zoomableState.zoomFraction ?: 0f) > 0f }
    }

    @Test
    fun videoPageShowsThePlayButtonAndTheControls() {
        installVideoThumbnail()
        val plays = mutableListOf<Long>()
        setContent(items = listOf(video(9, "2026-10-16T09:30", durationMs = 42_000L)), onPlay = { plays += it })

        composeRule.onNodeWithText("0:00 / 0:42").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription(PLAY).onFirst().performClick()

        assertEquals(listOf(9L), plays)
    }

    @Test
    fun videoControlsAreHiddenWithTheChrome() {
        installVideoThumbnail()
        setContent(items = listOf(video(9, "2026-10-16T09:30", durationMs = 42_000L)), chromeVisible = false)

        composeRule.onNodeWithText("0:00 / 0:42").assertDoesNotExist()
    }

    @Test
    fun unreadableImageShowsACalmError() {
        val missing = photo(1, "2026-10-16T09:30").copy(uri = "content://de.example.missing/images/1")
        setContent(items = listOf(missing), imageSource = ContentUriImageSources)

        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithText(ERROR_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Standbild für das Video 9, damit die Video-Seite nicht in den Fehlerzustand fällt. */
    private fun installVideoThumbnail() {
        TestThumbnails.install(
            listOf(
                TimelineFixtures.item(9, millis = 0L, type = MediaType.VIDEO, durationMs = 42_000L),
            ),
        )
    }

    private fun photos() =
        listOf(
            photo(1, "2026-10-16T09:30"),
            photo(2, "2026-10-15T10:02"),
            photo(3, "2026-10-12T17:55"),
        )

    private fun setContent(
        items: List<ViewerItem>,
        index: Int = 0,
        chromeVisible: Boolean = true,
        onChromeToggle: () -> Unit = {},
        onBack: () -> Unit = {},
        onPageAtRest: (Long) -> Unit = {},
        onPlay: (Long) -> Unit = {},
        imageSource: ViewerImageSource = solidImageSources,
    ) {
        composeRule.setContent {
            EmberlightTheme {
                ViewerContent(
                    items = items,
                    index = index,
                    playback = VideoPlaybackState(),
                    muted = false,
                    player = null,
                    chromeVisible = chromeVisible,
                    onChromeToggle = onChromeToggle,
                    onBack = onBack,
                    onPageAtRest = onPageAtRest,
                    onPlay = onPlay,
                    onPause = {},
                    onSeek = {},
                    onToggleMute = {},
                    onVideoEnd = {},
                    onVideoError = {},
                    imageSource = imageSource,
                    zone = ZoneId.of("Europe/Berlin"),
                )
            }
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val BACK = "Back"
        const val PLAY = "Play"
        const val LABEL_FIRST = "Photo from October 16, 2026"
        const val TITLE_FIRST = "October 16, 2026 at 09:30"
        const val TITLE_SECOND = "October 15, 2026 at 10:02"
        const val ERROR_TITLE = "This item can't be shown"
    }
}
