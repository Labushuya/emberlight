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
 * Golden des Infobereichs auf dem Handy, Deutsch (ADR 0010, Kurzer Blick und Infobereich). Eigene Klasse, weil Robolectric den
 * Zustand je Konfiguration teilt (ADR 0006). Das Video-Golden gibt es nur auf Englisch.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w412dp-h915dp")
class ViewerInfoGermanScreenshotTest {
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
            path = "src/test/screenshots/Viewer_phone_de_info.png",
            waitFor = "Emberlight TestCam 1",
        )
    }

    private fun capture(
        items: List<ViewerItem>,
        path: String,
        waitFor: String,
    ) {
        composeRule.setContent {
            ViewerInfoGoldenContent(
                items = items,
                exif = FakeExif,
                golden = InfoGolden.INFO_OPEN,
            )
        }
        // Erst aufnehmen, wenn die EXIF-Angaben stehen. Die Abfrage läuft lazy, beim Öffnen des Bereichs.
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithText(waitFor).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.settleFrames()
        composeRule.onRoot().captureRoboImage(path)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
