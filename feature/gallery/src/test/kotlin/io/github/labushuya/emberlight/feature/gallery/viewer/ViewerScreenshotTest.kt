package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.labushuya.emberlight.core.media.model.MediaType
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
 * Goldens des Vollbild-Viewers auf dem Handy, Englisch (ADR 0010, ADR 0006). Fester Inhalt: Bilder sind Farbflächen,
 * Vorschaubilder kommen aus dem Fake-ImageLoader, Zeiten liegen in Berlin. Keine Berührung, um Zustände zu wechseln.
 *
 * Deutsch und das Innendisplay liegen in eigenen Klassen. Robolectric teilt den Zustand zwischen Konfigurationen innerhalb
 * einer Klasse, dabei wurde eine Aufnahme leer eingefangen.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w412dp-h915dp")
class ViewerScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Derselbe Loader für jeden Test. Ein Test, der den Loader anders setzt, hinterließ eine leere Aufnahme im nächsten. */
    @Before
    fun setUp() {
        flushMainLooper()
        TestThumbnails.install(
            listOf(TimelineFixtures.item(9, millis = 0L, type = MediaType.VIDEO, durationMs = 42_000L)),
        )
    }

    @After
    fun tearDown() {
        TestThumbnails.reset()
    }

    @Test
    fun chromeVisibleWithPhoto() {
        capture(
            photos(),
            chromeVisible = true,
            path = "src/test/screenshots/Viewer_phone_en_chrome.png",
            firstLabel = PHOTO_LABEL,
        )
    }

    @Test
    fun videoWithControls() {
        capture(
            listOf(video(9, "2026-10-16T09:30", durationMs = 42_000L), photo(8, "2026-10-16T08:15")),
            chromeVisible = true,
            path = "src/test/screenshots/Viewer_phone_en_video.png",
            firstLabel = VIDEO_LABEL,
        )
    }

    private fun photos() =
        listOf(
            photo(1, "2026-10-16T09:30"),
            photo(2, "2026-10-15T10:02"),
        )

    private fun capture(
        items: List<ViewerItem>,
        chromeVisible: Boolean,
        path: String,
        firstLabel: String,
    ) {
        composeRule.setContent { ViewerGoldenContent(items = items, chromeVisible = chromeVisible) }
        // Erst wenn die erste Seite im Baum steht, aufnehmen. Sonst fängt der Erstaufbau einen leeren Viewer ein.
        // Die Seite muss auch eine Fläche im Layout haben, sonst ist sie noch nicht gezeichnet.
        composeRule.waitUntil(TIMEOUT_MS) {
            val nodes = composeRule.onAllNodesWithContentDescription(firstLabel).fetchSemanticsNodes()
            nodes.isNotEmpty() && nodes.first().size.width > 0 && nodes.first().size.height > 0
        }
        composeRule.settleFrames()
        composeRule.onRoot().captureRoboImage(path)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val PHOTO_LABEL = "Photo from October 16, 2026"
        const val VIDEO_LABEL = "Video, 0:42, from October 16, 2026"
    }
}
