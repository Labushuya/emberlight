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

/**
 * Golden des Viewers im immersiven Modus (Leisten ausgeblendet), Handy Englisch.
 *
 * Eigene Klasse mit genau diesem Test. Im selben Lauf wie die Leisten-Goldens entstand eine leere, helle Aufnahme. Allein
 * aufgenommen ist sie korrekt. Die genaue Ursache im Robolectric-Zustand ist nicht geklärt, siehe ADR 0010.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w412dp-h915dp")
class ViewerImmersiveScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        flushMainLooper()
    }

    @Test
    fun immersiveWithoutChrome() {
        composeRule.setContent {
            ViewerGoldenContent(
                items = listOf(photo(1, "2026-10-16T09:30"), photo(2, "2026-10-15T10:02")),
                chromeVisible = false,
            )
        }
        composeRule.waitUntil(TIMEOUT_MS) {
            val nodes = composeRule.onAllNodesWithContentDescription(PHOTO_LABEL).fetchSemanticsNodes()
            nodes.isNotEmpty() && nodes.first().size.width > 0 && nodes.first().size.height > 0
        }
        composeRule.settleFrames()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/Viewer_phone_en_immersive.png")
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val PHOTO_LABEL = "Photo from October 16, 2026"
    }
}
