package io.github.labushuya.emberlight.feature.gallery.viewer

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Golden des Viewers auf dem Innendisplay eines Faltgeräts (673 × 841 dp). Eigene Klasse, siehe ViewerScreenshotTest. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w673dp-h841dp")
class ViewerFoldScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        flushMainLooper()
    }

    @Test
    fun chromeVisibleOnInnerDisplay() {
        composeRule.setContent {
            ViewerGoldenContent(
                items = listOf(photo(1, "2026-10-16T09:30"), photo(2, "2026-10-15T10:02")),
                chromeVisible = true,
            )
        }
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithContentDescription(PHOTO_LABEL).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.settleFrames()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/Viewer_fold_inner_en.png")
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val PHOTO_LABEL = "Photo from October 16, 2026"
    }
}
