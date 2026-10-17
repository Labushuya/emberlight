package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.labushuya.emberlight.feature.gallery.timeline.TestThumbnails
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Goldens des Infobereichs und des Kurzen Blicks auf dem Handy, Englisch (ADR 0010, Kurzer Blick und Infobereich). Feste
 * Angaben, feste Zustände ohne Animation. Deutsch liegt in eigener Klasse, wie bei den übrigen Viewer-Goldens.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w412dp-h915dp")
class ViewerInfoScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        flushMainLooper()
        TestThumbnails.install(TimelineFixtures.sampleItems())
    }

    @After
    fun tearDown() {
        TestThumbnails.reset()
    }

    @Test
    fun infoOpenOnAPhotoWithCameraDetails() {
        capture(
            items = listOf(photoWithDetails(1, "2026-10-16T09:30"), photoWithDetails(2, "2026-10-15T10:02")),
            golden = InfoGolden.INFO_OPEN,
            path = "src/test/screenshots/Viewer_phone_en_info.png",
            waitFor = "Emberlight TestCam 1",
        )
    }

    @Test
    fun infoOpenOnAVideo() {
        capture(
            items =
                listOf(
                    videoWithDetails(9, "2026-10-16T09:30", durationMs = 42_000L),
                    photoWithDetails(8, "2026-10-16T08:15"),
                ),
            golden = InfoGolden.INFO_OPEN,
            path = "src/test/screenshots/Viewer_phone_en_info_video.png",
            waitFor = "VID_0042.mp4",
        )
    }

    @Test
    fun peekStageWithHighlightedTile() {
        capture(
            items = listOf(photoWithDetails(1, "2026-10-16T09:30"), photoWithDetails(2, "2026-10-15T10:02")),
            golden = InfoGolden.PEEK,
            path = "src/test/screenshots/Viewer_phone_en_peek_highlight.png",
            waitFor = null,
        )
    }

    private fun capture(
        items: List<ViewerItem>,
        golden: InfoGolden,
        path: String,
        waitFor: String?,
    ) {
        composeRule.setContent { ViewerInfoGoldenContent(items = items, exif = FakeExif, golden = golden) }
        // Erst aufnehmen, wenn die Angaben stehen. Die EXIF-Abfrage läuft lazy, sie ist sonst noch nicht sichtbar.
        if (waitFor != null) {
            composeRule.waitUntil(TIMEOUT_MS) {
                composeRule.onAllNodesWithText(waitFor).fetchSemanticsNodes().isNotEmpty()
            }
        }
        composeRule.settleFrames()
        composeRule.onRoot().captureRoboImage(path)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
